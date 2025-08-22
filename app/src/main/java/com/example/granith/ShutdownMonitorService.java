package com.example.granith;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.Nullable;
import com.google.firebase.firestore.FirebaseFirestore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Serviço que monitora continuamente o estado do dispositivo
 * e detecta shutdowns através de heartbeat
 */
public class ShutdownMonitorService extends Service {
    private static final String TAG = "ShutdownMonitorService";
    private static final long HEARTBEAT_INTERVAL = 30000; // 30 segundos
    private static final long SHUTDOWN_THRESHOLD = 90000; // 90 segundos

    private ScheduledExecutorService scheduler;
    private Handler mainHandler;
    private FirebaseFirestore firestore;
    private boolean isRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        firestore = FirebaseFirestore.getInstance();
        mainHandler = new Handler(Looper.getMainLooper());
        scheduler = Executors.newScheduledThreadPool(2);

        Log.d(TAG, "ShutdownMonitorService criado");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isRunning) {
            startHeartbeatMonitoring();
            isRunning = true;
            Log.d(TAG, "Monitoramento de heartbeat iniciado");
        }

        // Retorna START_STICKY para reiniciar automaticamente
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;

        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }

        // Quando o serviço é destruído, pode indicar shutdown
        Log.w(TAG, "Serviço destruído - possível shutdown detectado");
        handlePossibleShutdown("Destruição do Serviço");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startHeartbeatMonitoring() {
        // Atualiza heartbeat a cada 30 segundos
        scheduler.scheduleAtFixedRate(
                this::updateHeartbeat,
                0,
                HEARTBEAT_INTERVAL,
                TimeUnit.MILLISECONDS
        );

        // Verifica por shutdown perdido a cada 60 segundos
        scheduler.scheduleAtFixedRate(
                this::checkForMissedShutdown,
                60000,
                60000,
                TimeUnit.MILLISECONDS
        );
    }

    private void updateHeartbeat() {
        try {
            SharedPreferences prefs = getSharedPreferences("HeartbeatPrefs", Context.MODE_PRIVATE);
            long currentTime = System.currentTimeMillis();

            prefs.edit()
                    .putLong("last_heartbeat", currentTime)
                    .putBoolean("app_running", true)
                    .apply();

            Log.v(TAG, "Heartbeat atualizado: " + currentTime);

        } catch (Exception e) {
            Log.e(TAG, "Erro ao atualizar heartbeat", e);
        }
    }

    private void checkForMissedShutdown() {
        try {
            SharedPreferences prefs = getSharedPreferences("HeartbeatPrefs", Context.MODE_PRIVATE);
            SharedPreferences devicePrefs = getSharedPreferences("DeviceStatePrefs", Context.MODE_PRIVATE);

            long lastHeartbeat = prefs.getLong("last_heartbeat", 0);
            long currentTime = System.currentTimeMillis();
            long timeSinceLastHeartbeat = currentTime - lastHeartbeat;

            boolean lastShutdownProcessed = devicePrefs.getBoolean("shutdown_processed", true);

            // Se passou muito tempo desde o último heartbeat e não foi processado
            if (timeSinceLastHeartbeat > SHUTDOWN_THRESHOLD && !lastShutdownProcessed) {
                Log.w(TAG, "Shutdown perdido detectado - gap de " + timeSinceLastHeartbeat + "ms");
                handlePossibleShutdown("Shutdown Perdido Detectado");
            }

        } catch (Exception e) {
            Log.e(TAG, "Erro ao verificar shutdown perdido", e);
        }
    }

    private void handlePossibleShutdown(String reason) {
        try {
            Log.w(TAG, "Processando possível shutdown: " + reason);

            // Marca como não processado para próxima verificação
            SharedPreferences devicePrefs = getSharedPreferences("DeviceStatePrefs", Context.MODE_PRIVATE);
            devicePrefs.edit().putBoolean("shutdown_processed", false).apply();

            // Processa geofences ativas
            processActiveGeofencesOnShutdown(reason);

            // Salva estado do shutdown
            saveShutdownState(reason);

            // Marca como processado
            devicePrefs.edit().putBoolean("shutdown_processed", true).apply();

        } catch (Exception e) {
            Log.e(TAG, "Erro ao processar shutdown", e);
        }
    }

    private void processActiveGeofencesOnShutdown(String shutdownReason) {
        try {
            SharedPreferences prefs = getSharedPreferences("GeofencePrefs", Context.MODE_PRIVATE);
            Map<String, ?> allEntries = prefs.getAll();

            for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
                String key = entry.getKey();

                if (key.endsWith("_active") && Boolean.TRUE.equals(entry.getValue())) {
                    String geofenceName = key.replace("_active", "");

                    // Recupera coordenadas da geofence
                    double latitude = Double.longBitsToDouble(
                            prefs.getLong(geofenceName + "_lat", 0));
                    double longitude = Double.longBitsToDouble(
                            prefs.getLong(geofenceName + "_lng", 0));

                    if (latitude != 0 && longitude != 0) {
                        generateShutdownGeofenceEvent(geofenceName, latitude, longitude, shutdownReason);

                        // Limpa estado da geofence
                        SharedPreferences.Editor editor = prefs.edit();
                        editor.remove(geofenceName + "_active");
                        editor.remove(geofenceName + "_entry_count");
                        editor.remove(geofenceName + "_exit_count");
                        editor.apply();

                        Log.d(TAG, "Evento de saída gerado para: " + geofenceName);
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Erro ao processar geofences ativas", e);
        }
    }

    private void generateShutdownGeofenceEvent(String geofenceName, double latitude,
                                               double longitude, String eventType) {
        try {
            SharedPreferences userPrefs = getSharedPreferences("MyAppPrefs", Context.MODE_PRIVATE);
            String userName = userPrefs.getString("user_name", "Usuário Desconhecido");

            Map<String, Object> event = new HashMap<>();
            event.put("event_type", eventType);
            event.put("latitude", latitude);
            event.put("longitude", longitude);
            event.put("geofence_name", geofenceName);
            event.put("timestamp", System.currentTimeMillis());
            event.put("user_name", userName);
            event.put("is_automatic_exit", true);
            event.put("shutdown_cause", "device_shutdown");
            event.put("accuracy", 1.0f);
            event.put("detection_method", "heartbeat_monitor");

            // Tenta enviar para Firebase
            if (isNetworkAvailable()) {
                firestore.collection("geofence_records")
                        .add(event)
                        .addOnSuccessListener(documentReference ->
                                Log.d(TAG, "Evento de shutdown enviado: " + documentReference.getId()))
                        .addOnFailureListener(e -> {
                            Log.e(TAG, "Falha ao enviar evento, armazenando localmente", e);
                            storeEventLocally(event);
                        });
            } else {
                storeEventLocally(event);
            }

        } catch (Exception e) {
            Log.e(TAG, "Erro ao gerar evento de shutdown", e);
        }
    }

    private void storeEventLocally(Map<String, Object> event) {
        try {
            SharedPreferences prefs = getSharedPreferences("MyAppPrefs", Context.MODE_PRIVATE);
            String offlineEventsString = prefs.getString("offline_events", "[]");
            JSONArray offlineEvents = new JSONArray(offlineEventsString);

            JSONObject eventJson = new JSONObject();
            for (Map.Entry<String, Object> entry : event.entrySet()) {
                eventJson.put(entry.getKey(), entry.getValue());
            }

            eventJson.put("is_shutdown_event", true);
            eventJson.put("local_id", "shutdown_" + System.currentTimeMillis());

            offlineEvents.put(eventJson);
            prefs.edit().putString("offline_events", offlineEvents.toString()).apply();

            Log.d(TAG, "Evento armazenado localmente");

        } catch (Exception e) {
            Log.e(TAG, "Erro ao armazenar evento localmente", e);
        }
    }

    private void saveShutdownState(String shutdownReason) {
        SharedPreferences prefs = getSharedPreferences("DeviceStatePrefs", Context.MODE_PRIVATE);
        prefs.edit()
                .putString("last_shutdown_reason", shutdownReason)
                .putLong("last_shutdown_timestamp", System.currentTimeMillis())
                .putBoolean("shutdown_occurred", true)
                .apply();
    }

    private boolean isNetworkAvailable() {
        try {
            android.net.ConnectivityManager connectivityManager =
                    (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

            if (connectivityManager != null) {
                android.net.NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
                return activeNetworkInfo != null && activeNetworkInfo.isConnected();
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Erro ao verificar conectividade", e);
            return false;
        }
    }
}
