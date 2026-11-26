package com.myvoltalis.widget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * EDF Tempo "jour rouge" overrides: a timetable of modes applied during the heures pleines
 * (06:00-22:00) of red days. Each slot is applied (until its end time) when it starts.
 */
public class TempoSchedule {

    public static final String ACTION_TICK = "com.myvoltalis.widget.TEMPO_TICK";
    public static final int HP_START = 6 * 60;   // heures pleines start (minutes since midnight)
    public static final int HP_END = 22 * 60;
    private static final String PREFS = "tempo";
    private static final String URL_TODAY = "https://www.calendrier-tempo.fr/api/today";
    private static final String URL_TOMORROW = "https://www.calendrier-tempo.fr/api/tomorrow";
    private static final long RETRY_MS = 5 * 60 * 1000L;

    public static class Slot {
        public final int startMin, endMin;
        public final Mode mode;

        public Slot(int startMin, int endMin, Mode mode) {
            this.startMin = startMin;
            this.endMin = endMin;
            this.mode = mode;
        }

        public String describe() {
            return fmt(startMin) + " – " + fmt(endMin) + "   " + mode.glyph + " " + mode.label;
        }
    }

    public static String fmt(int min) {
        return String.format(java.util.Locale.US, "%02d:%02d", min / 60, min % 60);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ storage

    public static boolean isEnabled(Context c) {
        return prefs(c).getBoolean("enabled", false);
    }

    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("enabled", on).remove("applied").apply();
        reschedule(c);
    }

    public static List<Slot> slots(Context c) {
        List<Slot> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("slots", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Slot(o.getInt("s"), o.getInt("e"), Mode.valueOf(o.getString("m"))));
            }
        } catch (Exception ignored) {
        }
        Collections.sort(out, (x, y) -> Integer.compare(x.startMin, y.startMin));
        return out;
    }

    public static void saveSlots(Context c, List<Slot> slots) {
        JSONArray a = new JSONArray();
        try {
            for (Slot s : slots) {
                a.put(new JSONObject().put("s", s.startMin).put("e", s.endMin).put("m", s.mode.name()));
            }
        } catch (Exception ignored) {
        }
        prefs(c).edit().putString("slots", a.toString()).remove("applied").apply();
        reschedule(c);
    }

    /** Returns an error message if the slot is not acceptable, else null. */
    public static String validate(List<Slot> existing, Slot n) {
        if (n.startMin < HP_START || n.endMin > HP_END) return "Slots must stay within heures pleines (06:00–22:00).";
        if (n.endMin <= n.startMin) return "End must be after start.";
        for (Slot s : existing) {
            if (n.startMin < s.endMin && s.startMin < n.endMin) return "Overlaps " + s.describe();
        }
        return null;
    }

    // ------------------------------------------------------------------ Tempo colour

    /** Tempo day (06:00 to 06:00) as yyyy-MM-dd, {@code plusDays} after the current one. */
    private static String tempoDay(int plusDays) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.HOUR_OF_DAY, -6);
        cal.add(Calendar.DAY_OF_MONTH, plusDays);
        return String.format(java.util.Locale.US, "%04d-%02d-%02d",
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    /** True if the current Tempo day is red. Cached per Tempo day. Blocking, throws if unknown. */
    static boolean isRedToday(Context c) throws Exception {
        return codeFor(c, false) == 3;
    }

    /** Colour code (1 blue, 2 white, 3 red) of today / tomorrow, from cache or network. Throws if unknown. */
    private static int codeFor(Context c, boolean tomorrow) throws Exception {
        int cached = cachedCode(c, tomorrow);
        if (cached != 0) return cached;
        JSONObject json = fetchDay(tomorrow ? URL_TOMORROW : URL_TODAY);
        String date = json.optString("date");
        int code = json.getInt("code");
        String prefix = tomorrow ? "tom" : "today";
        prefs(c).edit().putString(prefix + "_date", date).putInt(prefix + "_code", code).apply();
        if (!date.equals(tempoDay(tomorrow ? 1 : 0))) throw new Exception("Tempo colour is for " + date);
        return code;
    }

    /** Cached colour code for today / tomorrow, or 0 if unknown or stale. No network. */
    public static int cachedCode(Context c, boolean tomorrow) {
        String prefix = tomorrow ? "tom" : "today";
        SharedPreferences p = prefs(c);
        return tempoDay(tomorrow ? 1 : 0).equals(p.getString(prefix + "_date", "")) ? p.getInt(prefix + "_code", 0) : 0;
    }

    /** Refresh both cached colours, ignoring failures. Blocking. */
    public static void refreshColors(Context c) {
        try {
            codeFor(c, false);
        } catch (Exception ignored) {
        }
        try {
            codeFor(c, true);
        } catch (Exception ignored) {
        }
    }

    private static JSONObject fetchDay(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(10000);
        con.setRequestProperty("Accept", "application/json");
        if (con.getResponseCode() != 200) throw new Exception("Tempo colour: HTTP " + con.getResponseCode());
        try (InputStream in = con.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            JSONObject json = new JSONObject(out.toString("UTF-8"));
            // Colour not announced yet / source down: don't cache, let the caller retry later.
            if (!"ok".equals(json.optString("status"))) throw new Exception("Tempo colour not available yet");
            return json; // code: 1 blue, 2 white, 3 red
        } finally {
            con.disconnect();
        }
    }

    private static String colorText(int code) {
        return code == 3 ? "🔴 RED" : code == 1 ? "🔵 Blue" : code == 2 ? "⚪ White" : null;
    }

    /** Two-line "Today / Tomorrow" colour summary for the UI. Blocking. */
    public static String colorsSummary(Context c) {
        refreshColors(c);
        String today = colorText(cachedCode(c, false));
        String tomorrow = colorText(cachedCode(c, true));
        return "Tempo today: " + (today != null ? today : "unavailable")
                + "\nTempo tomorrow: " + (tomorrow != null ? tomorrow : "not announced yet");
    }

    // ------------------------------------------------------------------ notification

    private static final String CHANNEL = "tempo_override";

    private static void notifyOverride(Context c, Slot s) {
        android.app.NotificationManager nm =
                (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new android.app.NotificationChannel(
                    CHANNEL, "Tempo overrides", android.app.NotificationManager.IMPORTANCE_DEFAULT));
        }
        PendingIntent open = PendingIntent.getActivity(c, 3, new Intent(c, TempoActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(c, CHANNEL) : new android.app.Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Tempo red day: " + s.mode.glyph + " " + s.mode.label)
                .setContentText("Heating set to " + s.mode.label + " until " + fmt(s.endMin))
                .setContentIntent(open)
                .setAutoCancel(true);
        nm.notify(1001, b.build());
    }

    private static String dayKey(Calendar cal) {
        return cal.get(Calendar.YEAR) + "-" + cal.get(Calendar.MONTH) + "-" + cal.get(Calendar.DAY_OF_MONTH);
    }

    // ------------------------------------------------------------------ scheduling

    private static PendingIntent tickIntent(Context c) {
        Intent i = new Intent(c, TempoReceiver.class).setAction(ACTION_TICK);
        return PendingIntent.getBroadcast(c, 2, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void alarmAt(Context c, long at) {
        ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE))
                .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, tickIntent(c));
    }

    /** Arm the alarm for the next slot start (or cancel it when disabled / empty), and run a check now. */
    public static void reschedule(Context c) {
        Context app = c.getApplicationContext();
        if (!isEnabled(app) || slots(app).isEmpty()) {
            ((AlarmManager) app.getSystemService(Context.ALARM_SERVICE)).cancel(tickIntent(app));
            return;
        }
        alarmAt(app, nextStart(app));
        app.sendBroadcast(new Intent(app, TempoReceiver.class).setAction(ACTION_TICK)); // apply now if inside a slot
    }

    private static long nextStart(Context c) {
        long now = System.currentTimeMillis();
        long best = Long.MAX_VALUE;
        for (Slot s : slots(c)) {
            Calendar cal = atMinute(s.startMin);
            if (cal.getTimeInMillis() <= now) cal.add(Calendar.DAY_OF_MONTH, 1);
            best = Math.min(best, cal.getTimeInMillis());
        }
        return best;
    }

    private static Calendar atMinute(int min) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, min / 60);
        cal.set(Calendar.MINUTE, min % 60);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal;
    }

    // ------------------------------------------------------------------ the check itself

    /** Called from the alarm (background thread). Applies the slot we are in, if today is red. */
    static void tick(Context c) {
        Context app = c.getApplicationContext();
        if (!isEnabled(app)) return;

        Calendar now = Calendar.getInstance();
        int min = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        Slot active = null;
        for (Slot s : slots(app)) {
            if (s.startMin <= min && min < s.endMin) active = s;
        }

        if (active != null) {
            String key = dayKey(now) + "@" + active.startMin;
            if (!key.equals(prefs(app).getString("applied", ""))) {
                try {
                    if (isRedToday(app)) {
                        long end = atMinute(active.endMin).getTimeInMillis();
                        new VoltalisApi(app).setModeUntil(active.mode, end);
                        ModeWidgetProvider.updateAll(app, active.mode, end);
                        try {
                            notifyOverride(app, active);
                        } catch (Exception ignored) { // e.g. notifications not permitted
                        }
                    }
                    prefs(app).edit().putString("applied", key).apply(); // once per day & slot
                } catch (Exception e) {
                    alarmAt(app, System.currentTimeMillis() + RETRY_MS); // retry shortly, keep slot pending
                    return;
                }
            }
        }
        alarmAt(app, nextStart(app));
    }
}
