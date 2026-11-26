package com.myvoltalis.widget;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Dialog-style popup with one button per mode. */
public class ModePickerActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Button[] buttons = new Button[Mode.SELECTABLE.length];
    private final Button[] durationButtons = new Button[5];
    private LinearLayout modePanel, durationPanel;
    private TextView titleView, status;
    private Mode pendingMode;
    private boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setFinishOnTouchOutside(true);

        if (!new VoltalisApi(this).hasCredentials()) {
            Toast.makeText(this, "Enter your MyVoltalis login first", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Heating mode");
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        title.setText("Heating mode");
        titleView = title;

        modePanel = new LinearLayout(this);
        modePanel.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < Mode.SELECTABLE.length; i++) {
            final Mode m = Mode.SELECTABLE[i];
            Button btn = new Button(this);
            btn.setText(m.glyph + "  " + m.label);
            btn.setOnClickListener(v -> {
                if (m == Mode.AUTO) apply(m, 0);
                else showDurationPanel(m);
            });
            buttons[i] = btn;
            modePanel.addView(btn, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        root.addView(modePanel);

        durationPanel = new LinearLayout(this);
        durationPanel.setOrientation(LinearLayout.VERTICAL);
        durationPanel.setVisibility(View.GONE);
        int[] hours = {1, 2, 3, 4, 0};
        String[] labels = {"1 h", "2 h", "3 h", "4 h", "Until further notice"};
        for (int i = 0; i < hours.length; i++) {
            final int h = hours[i];
            Button btn = new Button(this);
            btn.setText(labels[i]);
            btn.setOnClickListener(v -> apply(pendingMode, h));
            durationButtons[i] = btn;
            durationPanel.addView(btn, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        Button back = new Button(this);
        back.setText("←  Back");
        back.setOnClickListener(v -> {
            if (busy) return;
            durationPanel.setVisibility(View.GONE);
            modePanel.setVisibility(View.VISIBLE);
            titleView.setText("Heating mode");
        });
        durationPanel.addView(back, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(durationPanel);

        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        root.addView(status);
        setContentView(root);

        // Show the real current mode (and refresh the widget with it).
        new Thread(() -> {
            try {
                VoltalisApi.State st = new VoltalisApi(this).getCurrentState();
                ModeWidgetProvider.updateAll(this, st.mode, st.endMillis);
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void showDurationPanel(Mode mode) {
        pendingMode = mode;
        titleView.setText(mode.glyph + "  " + mode.label + " — for how long?");
        modePanel.setVisibility(View.GONE);
        durationPanel.setVisibility(View.VISIBLE);
    }

    private void apply(final Mode mode, final int hours) {
        if (busy) return;
        busy = true;
        for (Button b : buttons) b.setEnabled(false);
        for (Button b : durationButtons) b.setEnabled(false);
        status.setText("Applying " + mode.label + "…");
        new Thread(() -> {
            String error = null;
            try {
                VoltalisApi api = new VoltalisApi(this);
                long end = api.setMode(mode, hours);
                ModeWidgetProvider.updateAll(this, mode, end);
            } catch (Exception e) {
                error = e.getMessage();
            }
            final String err = error;
            ui.post(() -> {
                if (err == null) {
                    Toast.makeText(this, "Mode: " + mode.label, Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    busy = false;
                    for (Button b : buttons) b.setEnabled(true);
                    for (Button b : durationButtons) b.setEnabled(true);
                    status.setText("Failed: " + err);
                }
            });
        }).start();
    }
}
