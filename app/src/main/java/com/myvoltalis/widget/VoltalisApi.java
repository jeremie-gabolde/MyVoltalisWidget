package com.myvoltalis.widget;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal client for the (unofficial) MyVoltalis REST API. Blocking: call from a background thread. */
public class VoltalisApi {
    private static final String BASE = "https://api.myvoltalis.com";
    private static final String PREFS = "voltalis";

    public static class ApiException extends Exception {
        public final int status;

        ApiException(String msg, int status) {
            super(msg);
            this.status = status;
        }
    }

    private final SharedPreferences prefs;

    public VoltalisApi(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void saveCredentials(Context ctx, String login, String password) {
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("login", login).putString("password", password)
                .remove("token").remove("site").apply();
    }

    public static String savedLogin(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("login", "");
    }

    public boolean hasCredentials() {
        return !prefs.getString("login", "").isEmpty() && !prefs.getString("password", "").isEmpty();
    }

    // ---------------------------------------------------------------- high level

    /** Current mode of the heaters. AUTO if they follow the programming, else the manual mode. */
    public Mode getCurrentMode() throws Exception {
        return getCurrentState().mode;
    }

    /** Current mode plus, for a timed manual mode, the moment it ends (0 = no end). */
    public static class State {
        public final Mode mode;
        public final long endMillis;

        State(Mode mode, long endMillis) {
            this.mode = mode;
            this.endMillis = endMillis;
        }
    }

    public State getCurrentState() throws Exception {
        JSONArray appliances = getJsonArray("/api/site/%s/managed-appliance");
        List<JSONObject> heaters = heaters(appliances);
        if (heaters.isEmpty()) return new State(Mode.UNKNOWN, 0);
        JSONObject prog = heaters.get(0).optJSONObject("programming");
        if (prog == null) return new State(Mode.UNKNOWN, 0);
        if (!"MANUAL".equals(prog.optString("progType"))) return new State(Mode.AUTO, 0);
        long end = 0;
        if (!prog.optBoolean("untilFurtherNotice", true)) end = parseEnd(prog.optString("endDate", null));
        return new State(Mode.fromApi(prog.optString("mode", null)), end);
    }

    /** Parses "yyyy-MM-ddTHH:mm:ss" (local time, extra suffix ignored). 0 if absent/unparsable. */
    private static long parseEnd(String s) {
        if (s == null || s.length() < 19) return 0;
        try {
            java.text.SimpleDateFormat fmt =
                    new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            return fmt.parse(s.substring(0, 19)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Apply a mode to every heater. */
    public void setMode(Mode mode) throws Exception {
        setMode(mode, 0);
    }

    /** Apply a mode to every heater. {@code hours <= 0} means until further notice. */
    public long setMode(Mode mode, int hours) throws Exception {
        return setModeUntil(mode, hours > 0 ? System.currentTimeMillis() + hours * 3600_000L : 0);
    }

    /** Apply a mode to every heater until {@code endMillis} (0 = until further notice). Returns endMillis. */
    public long setModeUntil(Mode mode, long endMillis) throws Exception {
        String endDate = null;
        if (endMillis > 0 && mode != Mode.AUTO) {
            java.text.SimpleDateFormat fmt =
                    new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            // Same as the MyVoltalis web app: local time, no zone suffix, no milliseconds.
            endDate = fmt.format(new java.util.Date(endMillis));
        } else {
            endMillis = 0;
        }
        JSONArray appliances = getJsonArray("/api/site/%s/managed-appliance");
        List<JSONObject> heaters = heaters(appliances);
        JSONArray settings = getJsonArray("/api/site/%s/manualsetting");

        int applied = 0;
        java.util.Set<Integer> appliedIds = new java.util.HashSet<>();
        for (JSONObject heater : heaters) {
            int applianceId = heater.getInt("id");
            JSONObject setting = null;
            for (int i = 0; i < settings.length(); i++) {
                JSONObject s = settings.getJSONObject(i);
                if (s.optInt("idAppliance", -1) == applianceId) {
                    setting = s;
                    break;
                }
            }
            if (setting == null) continue;

            JSONObject body = new JSONObject();
            body.put("idAppliance", applianceId);
            body.put("untilFurtherNotice", endDate == null);
            body.put("endDate", endDate == null ? JSONObject.NULL : endDate);
            body.put("temperatureTarget", setting.optDouble("temperatureTarget", 19.0));

            if (mode == Mode.AUTO) {
                // Disable the manual override: the Voltalis programming takes over again.
                body.put("enabled", false);
                body.put("isOn", setting.optBoolean("isOn", true));
                body.put("mode", setting.optString("mode", "ECO"));
            } else {
                String apiMode = toApiMode(mode, heater.optJSONArray("availableModes"));
                if (apiMode == null) continue; // this heater cannot do that mode
                body.put("enabled", true);
                body.put("isOn", true);
                body.put("mode", apiMode);
            }
            request("PUT", "/api/site/%s/manualsetting/" + setting.getInt("id"), body.toString(), true);
            applied++;
            appliedIds.add(setting.getInt("id"));
        }
        if (applied == 0) throw new Exception("No heater accepted " + mode.label);

        // Read back and make sure the server really stored what we sent.
        JSONArray after = getJsonArray("/api/site/%s/manualsetting");
        for (int i = 0; i < after.length(); i++) {
            JSONObject s = after.getJSONObject(i);
            if (!appliedIds.contains(s.optInt("id", -1))) continue;
            boolean wantEnabled = mode != Mode.AUTO;
            if (s.optBoolean("enabled", wantEnabled) != wantEnabled
                    || (wantEnabled && s.optBoolean("untilFurtherNotice", endDate == null) != (endDate == null))) {
                String snippet = s.toString();
                throw new Exception("Server ignored the change: "
                        + (snippet.length() > 200 ? snippet.substring(0, 200) : snippet));
            }
        }
        return endMillis;
    }

    private static String toApiMode(Mode mode, JSONArray available) {
        List<String> av = new ArrayList<>();
        if (available != null) for (int i = 0; i < available.length(); i++) av.add(available.optString(i));
        switch (mode) {
            case COMFORT:
                if (av.contains("CONFORT")) return "CONFORT";
                if (av.contains("NORMAL")) return "NORMAL";
                return null;
            case ECO:
                if (av.contains("ECOV")) return "ECOV";
                return av.contains("ECO") ? "ECO" : null;
            case FROST:
                return av.contains("HORS_GEL") ? "HORS_GEL" : null;
            default:
                return null;
        }
    }

    private static List<JSONObject> heaters(JSONArray appliances) {
        List<JSONObject> all = new ArrayList<>();
        List<JSONObject> heaters = new ArrayList<>();
        for (int i = 0; i < appliances.length(); i++) {
            JSONObject a = appliances.optJSONObject(i);
            if (a == null) continue;
            all.add(a);
            if ("HEATER".equals(a.optString("applianceType"))) heaters.add(a);
        }
        return heaters.isEmpty() ? all : heaters;
    }

    // ---------------------------------------------------------------- plumbing

    private JSONArray getJsonArray(String path) throws Exception {
        return new JSONArray(request("GET", path, null, true));
    }

    private String siteId() throws Exception {
        String site = prefs.getString("site", null);
        if (site != null) return site;
        JSONObject me = new JSONObject(request("GET", "/api/account/me", null, true));
        site = String.valueOf(me.getJSONObject("defaultSite").get("id"));
        prefs.edit().putString("site", site).apply();
        return site;
    }

    private void login() throws Exception {
        JSONObject body = new JSONObject();
        body.put("login", prefs.getString("login", ""));
        body.put("password", prefs.getString("password", ""));
        String resp = rawRequest("POST", "/auth/login", body.toString(), null);
        prefs.edit().putString("token", new JSONObject(resp).getString("token")).apply();
    }

    /** {@code %s} in path is replaced by the site id. Re-logs in once on 401. */
    private String request(String method, String path, String body, boolean retry) throws Exception {
        if (!hasCredentials()) throw new ApiException("Not logged in", 401);
        if (prefs.getString("token", null) == null) login();
        String resolved = path.contains("%s") ? String.format(path, siteId()) : path;
        try {
            return rawRequest(method, resolved, body, prefs.getString("token", null));
        } catch (ApiException e) {
            if (e.status == 401 && retry) {
                prefs.edit().remove("token").apply();
                login();
                return request(method, path, body, false);
            }
            throw e;
        }
    }

    private String rawRequest(String method, String path, String body, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = in == null ? "" : readAll(in);
        c.disconnect();
        if (status >= 400) {
            String snippet = text.length() > 200 ? text.substring(0, 200) : text;
            throw new ApiException("HTTP " + status + " " + path + " " + snippet, status);
        }
        return text;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }
}
