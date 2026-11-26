package com.myvoltalis.widget;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Timetable of mode overrides applied during the heures pleines of EDF Tempo red days. */
public class TempoActivity extends Activity {

    private static final Mode[] CHOICES = {Mode.COMFORT, Mode.ECO, Mode.FROST};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView colorStatus;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Tempo red-day overrides");
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView info = new TextView(this);
        info.setText("On EDF Tempo red days (jour rouge), each slot below switches the heaters to its mode "
                + "when it starts and holds it until it ends, then the normal programming resumes. "
                + "Slots must fall within heures pleines (06:00–22:00).");
        root.addView(info);

        Switch enabled = new Switch(this);
        enabled.setText("Enable red-day overrides");
        enabled.setChecked(TempoSchedule.isEnabled(this));
        enabled.setOnCheckedChangeListener((v, on) -> {
            TempoSchedule.setEnabled(this, on);
            if (on && android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        });
        root.addView(enabled);

        colorStatus = new TextView(this);
        colorStatus.setText("Checking today's Tempo colour…");
        root.addView(colorStatus);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        Button add = new Button(this);
        add.setText("＋ Add a slot");
        add.setOnClickListener(v -> pickStart());
        root.addView(add);

        TextView credit = new TextView(this);
        credit.setText("Tempo colours: calendrier-tempo.fr (CC BY 4.0)");
        credit.setTextSize(12);
        credit.setPadding(0, pad / 2, 0, 0);
        credit.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
        credit.setText("Tempo colours: https://www.calendrier-tempo.fr (CC BY 4.0)");
        root.addView(credit);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        refreshList();
        new Thread(() -> {
            final String label = TempoSchedule.colorsSummary(this);
            ui.post(() -> colorStatus.setText(label));
        }).start();
    }

    private void refreshList() {
        list.removeAllViews();
        final List<TempoSchedule.Slot> slots = TempoSchedule.slots(this);
        if (slots.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("No slot yet.");
            none.setGravity(Gravity.CENTER);
            list.addView(none);
        }
        for (final TempoSchedule.Slot s : slots) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = new TextView(this);
            t.setText(s.describe());
            t.setTextSize(16);
            row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button del = new Button(this);
            del.setText("✕");
            del.setOnClickListener(v -> {
                List<TempoSchedule.Slot> left = new ArrayList<>(slots);
                left.remove(s);
                TempoSchedule.saveSlots(this, left);
                refreshList();
            });
            row.addView(del);
            list.addView(row);
        }
    }

    private void pickStart() {
        new TimePickerDialog(this, (view, h, m) -> pickEnd(h * 60 + m), 6, 0, true).show();
    }

    private void pickEnd(final int startMin) {
        new TimePickerDialog(this, (view, h, m) -> pickMode(startMin, h * 60 + m),
                Math.min(startMin / 60 + 1, 22), startMin % 60, true).show();
    }

    private void pickMode(final int startMin, final int endMin) {
        String[] names = new String[CHOICES.length];
        for (int i = 0; i < CHOICES.length; i++) names[i] = CHOICES[i].glyph + "  " + CHOICES[i].label;
        new AlertDialog.Builder(this)
                .setTitle(TempoSchedule.fmt(startMin) + " – " + TempoSchedule.fmt(endMin) + ": mode?")
                .setItems(names, (d, which) -> {
                    TempoSchedule.Slot n = new TempoSchedule.Slot(startMin, endMin, CHOICES[which]);
                    List<TempoSchedule.Slot> all = TempoSchedule.slots(this);
                    String err = TempoSchedule.validate(all, n);
                    if (err != null) {
                        Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                        return;
                    }
                    all.add(n);
                    TempoSchedule.saveSlots(this, all);
                    refreshList();
                })
                .show();
    }
}
