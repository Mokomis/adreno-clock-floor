// SPDX-License-Identifier: GPL-3.0-only
package dev.mokomis.adrenofloor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Holds the chosen floor while a streaming app is in front and restores stock when it is not.
 * Runs as a foreground service so ColorOS keeps it alive beside the stream.
 */
public final class WatchService extends Service {
    private static final String CHANNEL = "watch";
    private static final int NOTIFICATION = 1;
    private static final long POLL_MS = 2_000;
    /** How far back the first look goes for the app already in front. */
    private static final long FIRST_LOOK_MS = 10 * 60_000;

    private volatile boolean running;
    /** Set to make the loop write the driver again: the chosen floor changed. */
    private volatile boolean dirty;
    private Thread loop;

    static void start(Context context) {
        Intent intent = new Intent(context, WatchService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, WatchService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = notification("Watching for a streaming app");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION, notification);
        }
        dirty = true;
        if (loop == null) {
            running = true;
            loop = new Thread(this::watch, "floor-watch");
            loop.start();
        }
        return START_STICKY;
    }

    private void watch() {
        UsageStatsManager usage = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        long since = System.currentTimeMillis() - FIRST_LOOK_MS;
        String front = null;
        Boolean held = null;
        UsageEvents.Event event = new UsageEvents.Event();
        while (running) {
            long now = System.currentTimeMillis();
            UsageEvents events = usage.queryEvents(since, now);
            while (events != null && events.getNextEvent(event)) {
                // ACTIVITY_RESUMED on API 29+: the same value under its older name.
                if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    front = event.getPackageName();
                }
            }
            since = now;
            if (front != null) {
                boolean want = Floor.isStreamingApp(front);
                if (held == null || held != want || dirty) {
                    dirty = false;
                    int mhz = Floor.prefs(this).getInt(Floor.KEY_MHZ, Floor.DEFAULT_MHZ);
                    Floor.Result result = Floor.runRoot(Floor.changeScript(want ? mhz : Floor.STOCK));
                    held = want;
                    post(!result.ok() ? "Couldn't set the GPU clock floor"
                            : want ? "Floor " + mhz + " MHz while streaming"
                            : "Watching for a streaming app");
                }
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void post(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(text));
    }

    private Notification notification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Streaming watcher", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("GPU Clock Floor")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (loop != null) loop.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
