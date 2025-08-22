package com.example.granith;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.BatteryManager;
import android.util.Log;

import androidx.core.app.JobIntentService;

import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class DeviceStateReceiver extends BroadcastReceiver {
    private static final String TAG = "DeviceStateReceiver";

    // Constantes para tipos de desligamento
    private static final String SHUTDOWN_USER = "Saída por Desligamento Manual";
    private static final String SHUTDOWN_BATTERY = "Saída por Bateria Esgotada";
    private static final String SHUTDOWN_REBOOT = "Saída por Reinicialização";
    private static final String SHUTDOWN_EMERGENCY = "Saída por Desligamento de Emergência";

    // Interface para comunicação com o serviço
    public interface DeviceStateListener {
        Map<String, Boolean> getGeofenceEntryState();

        GeofenceData findGeofenceByName(String name);

        void clearCountersForGeofence(String geofenceName);

        boolean isNetworkAvailable();

        void syncOfflineEventsToFirebase();

        void onBatteryStateChanged(int batteryLevel, boolean isLow, boolean isCritical);
    }

    private DeviceStateListener listener;
    private FirebaseFirestore firestore;

    public DeviceStateReceiver() {
        this.firestore = FirebaseFirestore.getInstance();
    }

    public void setDeviceStateListener(DeviceStateListener listener) {
        this.listener = listener;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "DeviceStateReceiver recebeu: " + action);

        try {
            switch (action) {
                case Intent.ACTION_SHUTDOWN:
                    enqueueShutdownJob(context, SHUTDOWN_USER);
                    break;

                case Intent.ACTION_REBOOT:
                    enqueueShutdownJob(context, SHUTDOWN_REBOOT);
                    break;

                case "android.intent.action.QUICKBOOT_POWEROFF":
                case "com.htc.intent.action.QUICKBOOT_POWEROFF":
                case "com.android.internal.intent.action.REQUEST_SHUTDOWN":
                    enqueueShutdownJob(context, SHUTDOWN_USER);
                    break;

                case Intent.ACTION_BATTERY_LOW:
                    handleLowBattery(context);
                    break;

                case Intent.ACTION_BATTERY_CHANGED:
                    handleBatteryChanged(context, intent);
                    break;

                case Intent.ACTION_DEVICE_STORAGE_LOW:
                    Log.d(TAG, "Armazenamento baixo detectado");
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro no DeviceStateReceiver", e);
        }
    }

    /**
     * Enfileira um JobIntentService para processar o shutdown de forma segura.
     */
    private void enqueueShutdownJob(Context context, String shutdownReason) {
        Intent jobIntent = new Intent(context, ShutdownJobIntentService.class);
        jobIntent.putExtra("shutdown_reason", shutdownReason);
        JobIntentService.enqueueWork(
                context,
                ShutdownJobIntentService.class,
                ShutdownJobIntentService.JOB_ID,
                jobIntent
        );
    }

    private void handleLowBattery(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("DeviceStatePrefs", Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean("low_battery_detected", true)
                .putLong("low_battery_timestamp", System.currentTimeMillis())
                .apply();

        Log.d(TAG, "Bateria baixa detectada");

        if (listener != null) {
            int batteryLevel = prefs.getInt("last_battery_level", -1);
            listener.onBatteryStateChanged(batteryLevel, true, false);
        }
    }

    private void handleCriticalBattery(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("DeviceStatePrefs", Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean("critical_battery_detected", true)
                .putLong("critical_battery_timestamp", System.currentTimeMillis())
                .apply();

        Log.d(TAG, "Bateria crítica detectada - iniciando shutdown");

        if (listener != null) {
            int batteryLevel = prefs.getInt("last_battery_level", -1);
            listener.onBatteryStateChanged(batteryLevel, true, true);
        }

        handleDeviceShutdown(context, SHUTDOWN_BATTERY);
    }

    private void handleBatteryChanged(Context context, Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

        if (level >= 0 && scale > 0) {
            int batteryLevel = (int) ((level / (float) scale) * 100);

            SharedPreferences prefs = context.getSharedPreferences("DeviceStatePrefs", Context.MODE_PRIVATE);
            prefs.edit().putInt("last_battery_level", batteryLevel).apply();

            boolean wasLow = prefs.getBoolean("low_battery_detected", false);
            boolean wasCritical = prefs.getBoolean("critical_battery_detected", false);

            if (batteryLevel <= 3) {
                handleCriticalBattery(context);
            } else if (batteryLevel > 15) {
                prefs.edit()
                        .putBoolean("low_battery_detected", false)
                        .putBoolean("critical_battery_detected", false)
                        .apply();

                if (listener != null && (wasLow || wasCritical)) {
                    listener.onBatteryStateChanged(batteryLevel, false, false);
                }
            }
        }
    }

    private void handleDeviceShutdown(Context context, String shutdownReason) {
        Log.w(TAG, "Dispositivo desligando: " + shutdownReason);

        try {
            processActiveGeofencesOnShutdown(context, shutdownReason);
            saveShutdownState(context, shutdownReason);

            if (listener != null && listener.isNetworkAvailable()) {
                listener.syncOfflineEventsToFirebase();
            }

            Intent shutdownIntent = new Intent("com.example.granith.DEVICE_SHUTDOWN");
            shutdownIntent.putExtra("shutdown_reason", shutdownReason);
            context.sendBroadcast(shutdownIntent);

        } catch (Exception e) {
            Log.e(TAG, "Erro durante processo de desligamento", e);
        }
    }

    private void processActiveGeofencesOnShutdown(Context context, String shutdownReason) {
        if (listener == null) return;

        Map<String, Boolean> state = listener.getGeofenceEntryState();
        for (Map.Entry<String, Boolean> e : state.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) {
                GeofenceData gf = listener.findGeofenceByName(e.getKey());
                if (gf != null) {
                    generateShutdownGeofenceEvent(context, gf, shutdownReason);
                    listener.clearCountersForGeofence(e.getKey());
                }
            }
        }
    }

    private void generateShutdownGeofenceEvent(Context ctx, GeofenceData geofence, String eventType) {
        Location fake = createFakeLocation(geofence);
        String user = UserPreferences.loadUserName(
                ctx.getSharedPreferences("MyAppPrefs", Context.MODE_PRIVATE));
        Map<String, Object> event = new HashMap<>();
        event.put("event_type", eventType);
        event.put("latitude", fake.getLatitude());
        event.put("longitude", fake.getLongitude());
        event.put("geofence_name", geofence.getName());
        event.put("timestamp", System.currentTimeMillis());
        event.put("user_name", user);
        event.put("is_automatic_exit", true);
        event.put("shutdown_cause", "device_shutdown");

        if (listener.isNetworkAvailable()) {
            firestore.collection("geofence_records")
                    .add(event)
                    .addOnFailureListener(e -> storeShutdownEventLocally(ctx, event));
        } else {
            storeShutdownEventLocally(ctx, event);
        }
    }

    private Location createFakeLocation(GeofenceData geofence) {
        Location location = new Location("shutdown");
        location.setLatitude(geofence.getLatitude());
        location.setLongitude(geofence.getLongitude());
        location.setTime(System.currentTimeMillis());
        location.setAccuracy(1.0f);
        return location;
    }

    private void storeShutdownEventLocally(Context context, Map<String, Object> shutdownEvent) {
        try {
            SharedPreferences prefs = context.getSharedPreferences("MyAppPrefs", Context.MODE_PRIVATE);
            JSONArray offlineEventsArray = getOfflineEventsArray(prefs);

            JSONObject eventJson = new JSONObject();
            for (Map.Entry<String, Object> entry : shutdownEvent.entrySet()) {
                eventJson.put(entry.getKey(), entry.getValue());
            }

            eventJson.put("is_shutdown_event", true);
            eventJson.put("local_id", "shutdown_" + System.currentTimeMillis());

            offlineEventsArray.put(eventJson);
            prefs.edit().putString("offline_events", offlineEventsArray.toString()).apply();

            Log.d(TAG, "Evento de shutdown armazenado localmente");

        } catch (Exception e) {
            Log.e(TAG, "Erro ao armazenar evento localmente", e);
        }
    }

    private JSONArray getOfflineEventsArray(SharedPreferences prefs) {
        try {
            String offlineEventsString = prefs.getString("offline_events", "[]");
            return new JSONArray(offlineEventsString);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao carregar eventos offline", e);
            return new JSONArray();
        }
    }

    private void saveShutdownState(Context context, String shutdownReason) {
        SharedPreferences prefs = context.getSharedPreferences("DeviceStatePrefs", Context.MODE_PRIVATE);
        prefs.edit()
                .putString("last_shutdown_reason", shutdownReason)
                .putLong("last_shutdown_timestamp", System.currentTimeMillis())
                .putBoolean("shutdown_processed", true)
                .putBoolean("shutdown_occurred", true)
                .apply();
    }
}