package com.example.granith;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.JobIntentService;

import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class ShutdownJobIntentService extends JobIntentService {
    private static final String TAG = "ShutdownJobIntentService";
    public static final int JOB_ID = 1234;

    private FirebaseFirestore firestore;

    public ShutdownJobIntentService() {
        this.firestore = FirebaseFirestore.getInstance();
    }

    @Override
    protected void onHandleWork(@NonNull Intent intent) {
        String shutdownReason = intent.getStringExtra("shutdown_reason");
        long estimatedShutdownTime = intent.getLongExtra("estimated_shutdown_time", System.currentTimeMillis());
        boolean isRecovery = intent.getBooleanExtra("is_recovery", false);
        String detectionMethod = intent.getStringExtra("detection_method");

        Log.d(TAG, "=== PROCESSANDO SHUTDOWN ===");
        Log.d(TAG, "Razão: " + shutdownReason);
        Log.d(TAG, "É recovery: " + isRecovery);
        Log.d(TAG, "Método de detecção: " + detectionMethod);

        try {
            // Processa geofences ativas no momento do shutdown
            if (isRecovery) {
                processActiveGeofencesOnRecovery(shutdownReason, estimatedShutdownTime, detectionMethod);
            } else {
                processActiveGeofencesOnShutdown(shutdownReason);
            }

            // Salva o estado do shutdown
            saveShutdownState(shutdownReason, isRecovery);

            // Tenta sincronizar eventos offline se houver rede
            if (isNetworkAvailable()) {
                syncOfflineEventsToFirebase();
            }

            // Envia broadcast para notificar outros componentes
            sendShutdownBroadcast(shutdownReason, isRecovery);

            Log.d(TAG, "✅ Processamento de shutdown concluído");

        } catch (Exception e) {
            Log.e(TAG, "❌ Erro durante processamento de shutdown", e);
        }
    }

    /**
     * Processa geofences ativas durante shutdown normal
     */
    private void processActiveGeofencesOnShutdown(String shutdownReason) {
        processActiveGeofences(shutdownReason, System.currentTimeMillis(), "real_time_shutdown");
    }

    /**
     * Processa geofences ativas durante recovery (shutdown perdido)
     */
    private void processActiveGeofencesOnRecovery(String shutdownReason, long estimatedTime, String detectionMethod) {
        processActiveGeofences(shutdownReason, estimatedTime, detectionMethod);
    }

    /**
     * Método unificado para processar geofences ativas
     */
    private void processActiveGeofences(String shutdownReason, long eventTime, String detectionMethod) {
        try {
            SharedPreferences prefs = getSharedPreferences("GeofencePrefs", Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            // Recupera estado das geofences ativas
            Map<String, ?> allEntries = prefs.getAll();
            int processedGeofences = 0;

            Log.d(TAG, "🔍 Verificando geofences ativas...");

            for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
                String key = entry.getKey();

                // Verifica se é uma entrada de geofence ativa
                if (key.endsWith("_active") && Boolean.TRUE.equals(entry.getValue())) {
                    String geofenceName = key.replace("_active", "");

                    // Recupera dados da geofence
                    double latitude = Double.longBitsToDouble(prefs.getLong(geofenceName + "_lat", 0));
                    double longitude = Double.longBitsToDouble(prefs.getLong(geofenceName + "_lng", 0));

                    if (latitude != 0 && longitude != 0) {
                        Log.d(TAG, "📍 Processando geofence ativa: " + geofenceName);

                        // Gera evento de saída automática
                        generateShutdownGeofenceEvent(geofenceName, latitude, longitude,
                                shutdownReason, eventTime, detectionMethod);

                        // Limpa contadores e estado da geofence
                        editor.remove(geofenceName + "_active");
                        editor.remove(geofenceName + "_entry_count");
                        editor.remove(geofenceName + "_exit_count");
                        editor.remove(geofenceName + "_lat");
                        editor.remove(geofenceName + "_lng");

                        processedGeofences++;
                    }
                }
            }

            editor.apply();

            Log.d(TAG, "✅ Processadas " + processedGeofences + " geofences ativas");

        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao processar geofences ativas", e);
        }
    }

    private void generateShutdownGeofenceEvent(String geofenceName, double latitude, double longitude,
                                               String eventType, long eventTime, String detectionMethod) {
        try {
            // Recupera nome do usuário
            SharedPreferences userPrefs = getSharedPreferences("MyAppPrefs", Context.MODE_PRIVATE);
            String userName = userPrefs.getString("user_name", "Usuário Desconhecido");

            // Cria evento de shutdown
            Map<String, Object> event = new HashMap<>();
            event.put("event_type", eventType);
            event.put("latitude", latitude);
            event.put("longitude", longitude);
            event.put("geofence_name", geofenceName);
            event.put("timestamp", eventTime);
            event.put("user_name", userName);
            event.put("is_automatic_exit", true);
            event.put("shutdown_cause", "device_shutdown");
            event.put("accuracy", 1.0f);
            event.put("detection_method", detectionMethod != null ? detectionMethod : "unknown");

            Log.d(TAG, "🎯 Gerando evento para: " + geofenceName + " (" + eventType + ")");

            // Tenta enviar para Firebase, senão armazena localmente
            if (isNetworkAvailable()) {
                firestore.collection("geofence_records")
                        .add(event)
                        .addOnSuccessListener(documentReference -> {
                            Log.d(TAG, "📤 Evento enviado: " + documentReference.getId());
                        })
                        .addOnFailureListener(e -> {
                            Log.e(TAG, "❌ Falha ao enviar evento, armazenando localmente", e);
                            storeShutdownEventLocally(event);
                        });
            } else {
                Log.d(TAG, "📱 Sem internet, armazenando localmente");
                storeShutdownEventLocally(event);
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao gerar evento de shutdown", e);
        }
    }

    private void storeShutdownEventLocally(Map<String, Object> shutdownEvent) {
        try {
            SharedPreferences prefs = getSharedPreferences("MyAppPrefs", Context.MODE_PRIVATE);
            JSONArray offlineEventsArray = getOfflineEventsArray(prefs);

            JSONObject eventJson = new JSONObject();
            for (Map.Entry<String, Object> entry : shutdownEvent.entrySet()) {
                eventJson.put(entry.getKey(), entry.getValue());
            }

            eventJson.put("is_shutdown_event", true);
            eventJson.put("local_id", "shutdown_" + System.currentTimeMillis());

            offlineEventsArray.put(eventJson);
            prefs.edit().putString("offline_events", offlineEventsArray.toString()).apply();

            Log.d(TAG, "💾 Evento de shutdown armazenado localmente");

        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao armazenar evento localmente", e);
        }
    }

    private JSONArray getOfflineEventsArray(SharedPreferences prefs) {
        try {
            String offlineEventsString = prefs.getString("offline_events", "[]");
            return new JSONArray(offlineEventsString);
        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao carregar eventos offline", e);
            return new JSONArray();
        }
    }

    private void saveShutdownState(String shutdownReason, boolean isRecovery) {
        SharedPreferences prefs = getSharedPreferences("DeviceStatePrefs", Context.MODE_PRIVATE);
        prefs.edit()
                .putString("last_shutdown_reason", shutdownReason)
                .putLong("last_shutdown_timestamp", System.currentTimeMillis())
                .putBoolean("shutdown_processed", true)
                .putBoolean("shutdown_occurred", true)
                .putBoolean("was_recovery_shutdown", isRecovery)
                .apply();

        Log.d(TAG, "💾 Estado de shutdown salvo: " + shutdownReason +
                (isRecovery ? " (Recovery)" : ""));
    }

    private void syncOfflineEventsToFirebase() {
        try {
            SharedPreferences prefs = getSharedPreferences("MyAppPrefs", Context.MODE_PRIVATE);
            JSONArray offlineEvents = getOfflineEventsArray(prefs);

            if (offlineEvents.length() == 0) {
                Log.d(TAG, "📭 Nenhum evento offline para sincronizar");
                return;
            }

            Log.d(TAG, "🔄 Sincronizando " + offlineEvents.length() + " eventos offline");

            for (int i = 0; i < offlineEvents.length(); i++) {
                JSONObject eventJson = offlineEvents.getJSONObject(i);

                Map<String, Object> eventMap = new HashMap<>();
                eventMap.put("event_type", eventJson.optString("event_type"));
                eventMap.put("latitude", eventJson.optDouble("latitude"));
                eventMap.put("longitude", eventJson.optDouble("longitude"));
                eventMap.put("geofence_name", eventJson.optString("geofence_name"));
                eventMap.put("timestamp", eventJson.optLong("timestamp"));
                eventMap.put("user_name", eventJson.optString("user_name"));
                eventMap.put("is_automatic_exit", eventJson.optBoolean("is_automatic_exit"));
                eventMap.put("shutdown_cause", eventJson.optString("shutdown_cause"));
                eventMap.put("accuracy", eventJson.optDouble("accuracy", 1.0));
                eventMap.put("detection_method", eventJson.optString("detection_method", "offline_sync"));

                firestore.collection("geofence_records").add(eventMap);
            }

            // Limpa eventos offline após sincronização
            prefs.edit().putString("offline_events", "[]").apply();
            Log.d(TAG, "✅ Eventos offline sincronizados e limpos");

        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao sincronizar eventos offline", e);
        }
    }

    private void sendShutdownBroadcast(String shutdownReason, boolean isRecovery) {
        try {
            Intent shutdownIntent = new Intent("com.example.granith.DEVICE_SHUTDOWN");
            shutdownIntent.putExtra("shutdown_reason", shutdownReason);
            shutdownIntent.putExtra("timestamp", System.currentTimeMillis());
            shutdownIntent.putExtra("is_recovery", isRecovery);
            sendBroadcast(shutdownIntent);

            Log.d(TAG, "📡 Broadcast de shutdown enviado" +
                    (isRecovery ? " (Recovery)" : ""));

        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao enviar broadcast de shutdown", e);
        }
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
            Log.e(TAG, "❌ Erro ao verificar conectividade", e);
            return false;
        }
    }
}
