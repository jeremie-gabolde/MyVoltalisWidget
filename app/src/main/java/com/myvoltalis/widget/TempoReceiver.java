package com.myvoltalis.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires at each Tempo slot start, and after reboot / app update to re-arm the alarm. */
public class TempoReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, Intent intent) {
        final String action = intent.getAction();
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                if (TempoSchedule.ACTION_TICK.equals(action)) {
                    TempoSchedule.tick(context);
                } else {
                    TempoSchedule.reschedule(context); // BOOT_COMPLETED / MY_PACKAGE_REPLACED
                }
            } finally {
                pr.finish();
            }
        }).start();
    }
}
