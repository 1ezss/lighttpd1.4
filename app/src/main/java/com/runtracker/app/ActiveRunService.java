package com.runtracker.app;

import android.app.*;
import android.content.*;
import android.os.IBinder;

/**
 * Foreground service that keeps the RunTracker process eligible for continuous execution
 * during an active run (screen off/locked, Settings, or another app in foreground).
 *
 * The current v0.1.4 run engine still lives in MainActivity, but its complete state is
 * checkpointed and restored. This service provides the Android foreground-execution contract.
 */
public class ActiveRunService extends Service {
    public static final String ACTION_STOP = "com.runtracker.app.STOP_ACTIVE_RUN_SERVICE";
    private static final String CHANNEL_ID = "runtracker_active_run";
    private static final int NOTIFICATION_ID = 4101;

    @Override public void onCreate() {
        super.onCreate();
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Active run", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Keeps RunTracker recording while a run is active.");
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
            this, 0, openApp, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);

        Notification n = b
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("RunTracker")
            .setContentText("Run in progress")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .build();

        startForeground(NOTIFICATION_ID, n);
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
