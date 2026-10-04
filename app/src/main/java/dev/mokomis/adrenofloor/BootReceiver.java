// SPDX-License-Identifier: GPL-3.0-only
package dev.mokomis.adrenofloor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** After a reboot, puts back what "Apply at startup" asked for: the watcher, or the floor itself. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences prefs = Floor.prefs(context);
        if (!prefs.getBoolean(Floor.KEY_BOOT, false)) return;
        if (prefs.getBoolean(Floor.KEY_WATCH, false)) {
            WatchService.start(context);
            return;
        }
        int mhz = prefs.getInt(Floor.KEY_MHZ, Floor.DEFAULT_MHZ);
        PendingResult pending = goAsync();
        new Thread(() -> {
            Floor.runRoot(Floor.changeScript(mhz));
            pending.finish();
        }, "floor-boot").start();
    }
}
