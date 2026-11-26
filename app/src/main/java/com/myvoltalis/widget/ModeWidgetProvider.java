package com.myvoltalis.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

public class ModeWidgetProvider extends AppWidgetProvider {

    private static final String PREFS = "widget_state";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        render(context, manager, ids, lastMode(context), lastEnd(context)); // instant: last known state
        refreshAsync(context);                                              // then fetch the real one
    }

    private static final String ACTION_REFRESH = "com.myvoltalis.widget.REFRESH";
    private static final long POLL_MS = 10 * 60 * 1000L; // catches changes made in the official app

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_REFRESH.equals(intent.getAction())) {
            final PendingResult pr = goAsync();
            refreshAsync(context, pr);
        } else {
            super.onReceive(context, intent);
        }
    }

    @Override
    public void onEnabled(Context context) {
        scheduleRefresh(context, lastEnd(context));
    }

    @Override
    public void onDisabled(Context context) {
        alarmManager(context).cancel(refreshIntent(context));
    }

    /** Fetch the current mode in the background and redraw every widget instance. */
    public static void refreshAsync(final Context ctx) {
        refreshAsync(ctx, null);
    }

    private static void refreshAsync(final Context ctx, final android.content.BroadcastReceiver.PendingResult pr) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                TempoSchedule.refreshColors(app); // today / tomorrow dots (failures ignored)
                VoltalisApi api = new VoltalisApi(app);
                if (api.hasCredentials()) {
                    VoltalisApi.State s = api.getCurrentState();
                    updateAll(app, s.mode, s.endMillis);
                    return;
                }
                updateAll(app, Mode.UNKNOWN, 0);
            } catch (Exception e) {
                // Network error: keep showing the last known state, but try again later.
                scheduleRefresh(app, lastEnd(app));
            } finally {
                if (pr != null) pr.finish();
            }
        }).start();
    }

    private static android.app.AlarmManager alarmManager(Context ctx) {
        return (android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
    }

    private static PendingIntent refreshIntent(Context ctx) {
        Intent i = new Intent(ctx, ModeWidgetProvider.class).setAction(ACTION_REFRESH);
        return PendingIntent.getBroadcast(ctx, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Next refresh: in 10 min, or just after the timed mode ends if that comes sooner. */
    private static void scheduleRefresh(Context ctx, long endMillis) {
        long now = System.currentTimeMillis();
        long at = now + POLL_MS;
        if (endMillis > now && endMillis + 2000 < at) at = endMillis + 2000;
        alarmManager(ctx).setAndAllowWhileIdle(android.app.AlarmManager.RTC, at, refreshIntent(ctx));
    }

    /** Store {@code mode} (no end time) and redraw all widgets with it. */
    public static void updateAll(Context ctx, Mode mode) {
        updateAll(ctx, mode, 0);
    }

    /** Store {@code mode} and its end time (0 = none) and redraw all widgets. */
    public static void updateAll(Context ctx, Mode mode, long endMillis) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("mode", mode.name()).putLong("end", endMillis).apply();
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, ModeWidgetProvider.class));
        render(ctx, mgr, ids, mode, endMillis);
        if (ids.length > 0) scheduleRefresh(ctx, endMillis);
    }

    private static long lastEnd(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("end", 0);
    }

    private static Mode lastMode(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            return Mode.valueOf(p.getString("mode", Mode.UNKNOWN.name()));
        } catch (IllegalArgumentException e) {
            return Mode.UNKNOWN;
        }
    }

    private static void render(Context ctx, AppWidgetManager mgr, int[] ids, Mode mode, long endMillis) {
        Intent open = new Intent(ctx, ModePickerActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        for (int id : ids) {
            RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_mode);
            rv.setTextViewText(R.id.widget_glyph, mode.glyph);
            String endText = null;
            if (endMillis > System.currentTimeMillis() && mode != Mode.AUTO) {
                endText = android.text.format.DateFormat.getTimeFormat(ctx).format(new java.util.Date(endMillis));
            }
            rv.setImageViewBitmap(R.id.widget_text, renderText(ctx, mode.label, mode.color, endText));
            rv.setInt(R.id.widget_dot_today, "setColorFilter", tempoColor(TempoSchedule.cachedCode(ctx, false)));
            rv.setInt(R.id.widget_dot_tomorrow, "setColorFilter", tempoColor(TempoSchedule.cachedCode(ctx, true)));
            rv.setOnClickPendingIntent(R.id.widget_root, pi);
            mgr.updateAppWidget(id, rv);
        }
    }

    private static int tempoColor(int code) {
        switch (code) {
            case 1: return 0xFF1E88E5; // blue
            case 2: return 0xFFFFFFFF; // white
            case 3: return 0xFFE53935; // red
            default: return 0xFF9E9E9E; // unknown / not announced
        }
    }

    /** Label (mode colour) and optional end time (white), each with a thin black outline. */
    private static android.graphics.Bitmap renderText(Context ctx, String label, int color, String endText) {
        float d = ctx.getResources().getDisplayMetrics().density;
        float stroke = 1.5f * d; // total stroke width: ~0.75dp visible outside the glyphs
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        p.setTextAlign(android.graphics.Paint.Align.CENTER);

        float labelSize = 12 * d, endSize = 11 * d;
        p.setTextSize(labelSize);
        float labelW = p.measureText(label);
        android.graphics.Paint.FontMetrics lf = p.getFontMetrics();
        float labelH = lf.descent - lf.ascent;
        float endW = 0, endH = 0;
        android.graphics.Paint.FontMetrics ef = null;
        if (endText != null) {
            p.setTextSize(endSize);
            endW = p.measureText(endText);
            ef = p.getFontMetrics();
            endH = ef.descent - ef.ascent;
        }
        float pad = stroke + 1;
        int w = (int) Math.ceil(Math.max(labelW, endW) + 2 * pad);
        int h = (int) Math.ceil(labelH + endH + 2 * pad);
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);

        drawOutlined(c, p, label, labelSize, color, w / 2f, pad - lf.ascent, stroke);
        if (endText != null) {
            drawOutlined(c, p, endText, endSize, 0xFFFFFFFF, w / 2f, pad + labelH - ef.ascent, stroke);
        }
        return bmp;
    }

    private static void drawOutlined(android.graphics.Canvas c, android.graphics.Paint p, String text,
                                     float size, int fill, float x, float y, float stroke) {
        p.setTextSize(size);
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setColor(0xFF000000);
        c.drawText(text, x, y, p);
        p.setStyle(android.graphics.Paint.Style.FILL);
        p.setColor(fill);
        c.drawText(text, x, y, p);
    }
}
