package com.example.granith;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

public class ServiceConditionMonitor extends Service {
    private static final String TAG = "ServiceConditionMonitor";
    private static final String CHANNEL_ID = "service_monitor_channel";
    private static final long CHECK_INTERVAL_MS = 30 * 1000; // 30 segundos

    private Handler checkHandler = new Handler();
    private LocationStatusReceiver locationStatusReceiver;
    private boolean isMainServiceRunning = false;

    private final Runnable conditionCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkConditionsAndStartService();
            checkHandler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Monitor de condições iniciado");

        createNotificationChannel();
        startForeground(2, createMonitorNotification("Aguardando condições..."));

        registerLocationReceiver();
        startConditionChecking();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Monitor de Serviço",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Monitora condições para iniciar o serviço principal");

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createMonitorNotification(String status) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ?
                        PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Monitor de Localização")
                .setContentText(status)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void registerLocationReceiver() {
        locationStatusReceiver = new LocationStatusReceiver();
        IntentFilter filter = new IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION);
        registerReceiver(locationStatusReceiver, filter);
    }

    private void startConditionChecking() {
        checkHandler.post(conditionCheckRunnable);
    }

    private void checkConditionsAndStartService() {
        try {
            ConditionCheckResult result = checkAllConditions();

            if (result.allConditionsMet && !isMainServiceRunning) {
                startMainService();
            } else if (!result.allConditionsMet) {
                updateNotificationStatus(result.getStatusMessage());
                Log.d(TAG, "Condições não atendidas: " + result.getStatusMessage());
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao verificar condições", e);
        }
    }

    private ConditionCheckResult checkAllConditions() {
        ConditionCheckResult result = new ConditionCheckResult();

        // Verifica permissões de localização
        boolean hasLocationPermission = ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        boolean hasBackgroundPermission = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hasBackgroundPermission = ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }

        // Verifica GPS
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        boolean isGpsEnabled = locationManager != null &&
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);

        // Avalia condições
        result.hasLocationPermission = hasLocationPermission;
        result.hasBackgroundPermission = hasBackgroundPermission;
        result.isGpsEnabled = isGpsEnabled;
        result.allConditionsMet = hasLocationPermission && hasBackgroundPermission && isGpsEnabled;

        return result;
    }

    private void startMainService() {
        try {
            Intent serviceIntent = new Intent(this, LocationForegroundService.class);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            isMainServiceRunning = true;
            updateNotificationStatus("Serviço principal ativo");
            Log.d(TAG, "Serviço principal iniciado com sucesso");

            // Para o monitoramento após 5 minutos (para dar tempo do serviço principal estabilizar)
            checkHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    stopSelf();
                }
            }, 5 * 60 * 1000);

        } catch (Exception e) {
            Log.e(TAG, "Erro ao iniciar serviço principal", e);
            isMainServiceRunning = false;
        }
    }

    private void updateNotificationStatus(String status) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(2, createMonitorNotification(status));
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (checkHandler != null) {
            checkHandler.removeCallbacks(conditionCheckRunnable);
        }

        if (locationStatusReceiver != null) {
            try {
                unregisterReceiver(locationStatusReceiver);
            } catch (Exception e) {
                Log.e(TAG, "Erro ao desregistrar receiver", e);
            }
        }

        Log.d(TAG, "Monitor de condições finalizado");
    }

    // ============= Classes auxiliares =============
    private static class ConditionCheckResult {
        boolean hasLocationPermission = false;
        boolean hasBackgroundPermission = false;
        boolean isGpsEnabled = false;
        boolean allConditionsMet = false;

        String getStatusMessage() {
            if (!hasLocationPermission) {
                return "Aguardando permissão de localização";
            }
            if (!hasBackgroundPermission) {
                return "Aguardando permissão de localização em segundo plano";
            }
            if (!isGpsEnabled) {
                return "Aguardando GPS ser ligado";
            }
            return "Todas condições atendidas";
        }
    }

    private class LocationStatusReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (LocationManager.PROVIDERS_CHANGED_ACTION.equals(intent.getAction())) {
                Log.d(TAG, "Status do GPS alterado, verificando condições...");
                // Força uma verificação imediata
                checkHandler.removeCallbacks(conditionCheckRunnable);
                checkHandler.post(conditionCheckRunnable);
            }
        }
    }
}
