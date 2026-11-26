package com.myvoltalis.widget;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Login screen: stores the MyVoltalis credentials used by the widget. */
public class MainActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView info = new TextView(this);
        info.setText("Enter your MyVoltalis account, then add the \"MyVoltalis Widget\" "
                + "to your home screen. Tap it to choose the heating mode.\n"
                + "Credentials are stored only on this phone.");
        root.addView(info);

        final EditText login = new EditText(this);
        login.setHint("Email / login");
        login.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        login.setText(VoltalisApi.savedLogin(this));
        root.addView(login);

        final EditText password = new EditText(this);
        password.setHint("Password");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(password);

        final TextView status = new TextView(this);
        Button save = new Button(this);
        save.setText("Save & test");
        save.setOnClickListener((View v) -> {
            final String l = login.getText().toString().trim();
            final String p = password.getText().toString();
            VoltalisApi.saveCredentials(this, l, p);
            status.setText("Connecting…");
            new Thread(() -> {
                String msg;
                try {
                    VoltalisApi.State st = new VoltalisApi(this).getCurrentState();
                    Mode m = st.mode;
                    ModeWidgetProvider.updateAll(this, m, st.endMillis);
                    msg = "OK - current mode: " + m.label;
                } catch (Exception e) {
                    msg = "Failed: " + e.getMessage();
                }
                final String text = msg;
                ui.post(() -> status.setText(text));
            }).start();
        });
        root.addView(save);
        root.addView(status);

        final TextView tempoColors = new TextView(this);
        tempoColors.setPadding(0, pad / 2, 0, 0);
        tempoColors.setText("Tempo: loading…");
        root.addView(tempoColors);
        new Thread(() -> {
            final String s = TempoSchedule.colorsSummary(this);
            ui.post(() -> tempoColors.setText(s));
        }).start();

        Button tempo = new Button(this);
        tempo.setText("EDF Tempo – red-day overrides");
        tempo.setOnClickListener(v -> startActivity(new android.content.Intent(this, TempoActivity.class)));
        root.addView(tempo);
        setContentView(root);
    }
}
