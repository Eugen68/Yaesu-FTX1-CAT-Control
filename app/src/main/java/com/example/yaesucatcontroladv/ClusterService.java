package com.example.yaesucatcontroladv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * Foreground Service care menține conexiunea la DX Cluster activă.
 * Rulează ClusterManager într-un thread separat și raportează rezultatele către ClusterRepository.
 */
public class ClusterService extends Service {
    private static final String CHANNEL_ID = "DX_CLUSTER_CHANNEL";
    private static final int NOTIF_ID = 1001;

    private ClusterManager clusterManager;

    @Override
    public void onCreate() {
        super.onCreate();
        clusterManager = new ClusterManager(this);
        clusterManager.setListener(new ClusterManager.ClusterListener() {
            @Override
            public void onSpotReceived(Spot spot) {
                ClusterRepository.getInstance().addSpot(spot);
            }

            @Override
            public void onConnectionStatusChanged(boolean connected, String message) {
                // Repository-ul (folosit de UI, ex. EcranCluster) trebuie actualizat
                // ÎNTOTDEAUNA, chiar dacă serviciul se oprește, altfel UI-ul ar putea
                // rămâne cu o stare veche ("Connected") dacă userul oprește conexiunea
                // cât timp aplicația e încă deschisă.
                ClusterRepository.getInstance().updateStatus(connected, message);

                // Notificarea de foreground însă NU trebuie recreată dacă am intrat
                // deja în cleanup — acest callback poate ajunge asincron (postat pe
                // mainHandler din connectionThread) DUPĂ ce performFullCleanup() a
                // anulat deja notificarea, recreând-o din greșeală.
                if (isStopping) return;

                updateNotification(message);

                // Auto-reconnect logic
                if (!connected) {
                    boolean keepLive = getSharedPreferences(EcranSetariProprii.PREFS_NAME, MODE_PRIVATE)
                            .getBoolean("cluster_keep_live", false);
                    if (keepLive) {
                        reconnectWithDelay();
                    }
                }
            }

            @Override
            public void onError(String error) {
                ClusterRepository.getInstance().updateStatus(false, error);
            }
        });
        createNotificationChannel();
    }

    private boolean isStopping = false;
    private final Handler handler = new Handler();

    private void reconnectWithDelay() {
        handler.postDelayed(() -> {
            if (!isStopping && !clusterManager.isConnected()) {
                clusterManager.connect();
            }
        }, 5000);
    }

    /**
     * Execută curățarea completă a resurselor: deconectare, oprire thread-uri și eliminare notificare.
     */
    private void performFullCleanup() {
        isStopping = true;
        handler.removeCallbacksAndMessages(null);

        if (clusterManager != null) {
            clusterManager.disconnect();
            clusterManager.shutdown();
        }

        // Golim lista de spoturi la orice oprire a serviciului (task removed,
        // onDestroy, etc.) ca la următoarea pornire să nu apară spoturi vechi.
        ClusterRepository.getInstance().clearSpots();

        // Eliminare forțată a notificării prin NotificationManager
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(NOTIF_ID);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        isStopping = false;
        startForeground(NOTIF_ID, createNotification("Starting DX Cluster..."));

        if (intent != null && "SEND_COMMAND".equals(intent.getAction())) {
            String cmd = intent.getStringExtra("command");
            if (cmd != null && clusterManager != null) {
                clusterManager.sendCommand(cmd);
            }
        } else if (!clusterManager.isConnected()) {
            clusterManager.connect();
        }

        // Folosim START_NOT_STICKY pentru a preveni repornirea automată
        // a serviciului după ce aplicația a fost închisă manual.
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        performFullCleanup();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // Utilizatorul a închis aplicația din lista de recente -> curățăm și oprim imediat
        performFullCleanup();
        stopSelf();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "DX Cluster Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background DX Cluster Connection");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification(String content) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Yaesu ADV Control - Cluster")
                .setContentText(content)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String content) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIF_ID, createNotification(content));
        }
    }
}