package com.pmr.admin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

public class PmrService extends Service {
    public static final String CHANNEL_ID = "pmr_admin_ch";
    public static final int NOTIF_ID = 1;
    public static final String ACTION_STOP = "com.pmr.admin.STOP_SERVICE";

    public static PmrSocket pmrSocket;
    public static AudioEngine audioEngine;
    public static volatile boolean isRunning = false;

    public static PmrSocket getPmrSocket() {
        return pmrSocket;
    }

    public static void setChanListListener(PmrSocket.OnChanListListener listener) {
        PmrSocket.setChanListListener(listener);
    }

    public static void setVoiceListener(PmrSocket.OnVoiceListener listener) {
        if (pmrSocket != null) {
            pmrSocket.setVoiceListener(listener);
        }
    }

    private boolean isSimplex() {
        SharedPreferences prefs = getSharedPreferences("pmr_prefs", MODE_PRIVATE);
        return prefs.getBoolean("simplex", false);
    }

    private BroadcastReceiver controlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("PMR_START_RECORDING".equals(action)) {
                boolean simplex = isSimplex();
                AppLog.add("PmrService: PMR_START_RECORDING → startRecording (simplex=" + simplex + ")");
                if (audioEngine != null) {
                    if (simplex) {
                        AppLog.add("PmrService: simplex → stopPlaying перед startRecording");
                        audioEngine.stopPlaying();
                    }
                    audioEngine.startRecording();
                }
            } else if ("PMR_STOP_RECORDING".equals(action)) {
                boolean simplex = isSimplex();
                AppLog.add("PmrService: PMR_STOP_RECORDING → stopRecording (simplex=" + simplex + ")");
                if (audioEngine != null) {
                    audioEngine.stopRecording();
                    if (simplex) {
                        AppLog.add("PmrService: simplex → startPlaying после stopRecording");
                        audioEngine.startPlaying();
                    }
                }
            } else if (ACTION_STOP.equals(action)) {
                AppLog.add("PmrService: ACTION_STOP — остановка сервиса");
                stopForeground(true);
                stopSelf();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.add("PmrService: onCreate — старт V10.4");

        IntentFilter filter = new IntentFilter();
        filter.addAction("PMR_START_RECORDING");
        filter.addAction("PMR_STOP_RECORDING");
        filter.addAction(ACTION_STOP);
        registerReceiver(controlReceiver, filter);

        pmrSocket = new PmrSocket(getApplicationContext(), null);
        audioEngine = new AudioEngine(getApplicationContext(), pmrSocket);
        pmrSocket.setAudioEngine(audioEngine);
        audioEngine.startPlaying();
        pmrSocket.start();

        createChannel();
        startForeground(NOTIF_ID, buildNotification());
        isRunning = true;
        AppLog.add("PmrService: служба запущена, audioEngine и pmrSocket созданы, isRunning=true, simplex=" + isSimplex());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AppLog.add("PmrService: onStartCommand START_NOT_STICKY");
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        AppLog.add("PmrService: onDestroy");
        isRunning = false;
        try { unregisterReceiver(controlReceiver); } catch (Exception ignored) {}
        if (audioEngine != null) {
            try { audioEngine.stopRecording(); } catch (Exception ignored) {}
            try { audioEngine.stopPlaying(); } catch (Exception ignored) {}
        }
        if (pmrSocket != null) pmrSocket.stop();
        pmrSocket = null;
        audioEngine = null;
        stopForeground(true);
        super.onDestroy();
        AppLog.add("PmrService: onDestroy — isRunning=false");
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent piOpen = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, PmrService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent piStop = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Работа в фоне...")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(piOpen)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "ВЫКЛ", piStop)
                .build();
    }
}