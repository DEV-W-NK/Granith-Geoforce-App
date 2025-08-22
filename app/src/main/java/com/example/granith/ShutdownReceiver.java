package com.example.granith;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Receiver que detecta shutdown em tempo real
 */
public class ShutdownReceiver extends BroadcastReceiver {
    private static final String TAG = "ShutdownReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        Log.w(TAG, "🚨 SHUTDOWN DETECTADO: " + action);

        if (Intent.ACTION_SHUTDOWN.equals(action)) {
            Log.w(TAG, "📱 Dispositivo sendo desligado - processando geofences ativas");
            processActiveGeofencesOnShutdown(context, "Desligamento do Dispositivo");
        }
    }

    void processActiveGeofencesOnShutdown(Context context, String shutdownReason) {
        try {
            SharedPreferences geofencePrefs = context.getSharedPreferences("GeofencePrefs", Context.MODE_PRIVATE);
            SharedPreferences userPrefs = context.getSharedPreferences("MyAppPrefs", Context.MODE_PRIVATE);

            Map<String, ?> allEntries = geofencePrefs.getAll();
            String userName = userPrefs.getString("user_name", "Usuário Desconhecido");

            Log.d(TAG, "🔍 Verificando geofences ativas para shutdown imediato...");

            for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
                String key = entry.getKey();

                if (key.endsWith("_active") && Boolean.TRUE.equals(entry.getValue())) {
                    String geofenceName = key.replace("_active", "");

                    double latitude = Double.longBitsToDouble(
                            geofencePrefs.getLong(geofenceName + "_lat", 0));
                    double longitude = Double.longBitsToDouble(
                            geofencePrefs.getLong(geofenceName + "_lng", 0));

                    if (latitude != 0 && longitude != 0) {
                        Log.w(TAG, "🚪 Gerando saída imediata para: " + geofenceName);

                        // Gera evento de saída imediata
                        generateImmediateExitEvent(context, geofenceName, latitude, longitude,
                                userName, shutdownReason);

                        // Limpa estado da geofence
                        clearGeofenceState(geofencePrefs, geofenceName);
                    }
                }
            }

            // Marca que o shutdown foi processado
            SharedPreferences devicePrefs = context.getSharedPreferences("DeviceStatePrefs", Context.MODE_PRIVATE);
            devicePrefs.edit()
                    .putBoolean("shutdown_processed", true)
                    .putString("last_shutdown_reason", shutdownReason)
                    .putLong("last_shutdown_timestamp", System.currentTimeMillis())
                    .apply();

            Log.w(TAG, "✅ Processamento de shutdown imediato concluído");

        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao processar shutdown imediato", e);
        }
    }

    private void generateImmediateExitEvent(Context context, String geofenceName,
                                            double latitude, double longitude,
                                            String userName, String shutdownReason) {
        try {
            // Cria evento de saída imediata
            Map<String, Object> exitEvent = new HashMap<>();
            exitEvent.put("event_type", "Saída por Shutdown");
            exitEvent.put("latitude", latitude);
            exitEvent.put("longitude", longitude);
            exitEvent.put("geofence_name", geofenceName);
            exitEvent.put("timestamp", System.currentTimeMillis());
            exitEvent.put("user_name", userName);
            exitEvent.put("is_automatic_exit", true);
            exitEvent.put("shutdown_cause", shutdownReason);
            exitEvent.put("detection_method", "realtime_shutdown");
            exitEvent.put("accuracy", 1.0f);
            exitEvent.put("transition_type", "EXIT");
            exitEvent.put("source", "shutdown_receiver");

            Log.w(TAG, "📤 Salvando evento de saída imediata: " + geofenceName);

            // Como o tempo é crítico, salva direto localmente
            // O Firebase será tentado em background se houver tempo
            storeEventLocally(context, exitEvent);

            // Tenta enviar para Firebase em background (pode não dar tempo)
            tryFirebaseInBackground(context, exitEvent);

        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao gerar evento imediato", e);
        }
    }

    private void storeEventLocally(Context context, Map<String, Object> event) {
        try {
            SharedPreferences prefs = context.getSharedPreferences("MyAppPrefs", Context.MODE_PRIVATE);
            String offlineEventsString = prefs.getString("offline_events", "[]");
            JSONArray offlineEvents = new JSONArray(offlineEventsString);

            JSONObject eventJson = new JSONObject();
            for (Map.Entry<String, Object> entry : event.entrySet()) {
                eventJson.put(entry.getKey(), entry.getValue());
            }

            eventJson.put("is_shutdown_event", true);
            eventJson.put("is_immediate_shutdown", true);
            eventJson.put("local_id", "immediate_exit_" + System.currentTimeMillis());
            eventJson.put("needs_sync", true);

            offlineEvents.put(eventJson);
            prefs.edit().putString("offline_events", offlineEvents.toString()).apply();

            Log.w(TAG, "💾 Evento salvo localmente com sucesso");

        } catch (Exception e) {
            Log.e(TAG, "❌ Erro crítico ao salvar localmente", e);
        }
    }

    private void tryFirebaseInBackground(Context context, Map<String, Object> event) {
        // Tentativa rápida de envio para Firebase
        // (pode não dar tempo se o shutdown for muito rápido)
        try {
            if (isNetworkAvailable(context)) {
                FirebaseFirestore firestore = FirebaseFirestore.getInstance();
                firestore.collection("geofence_records")
                        .add(event)
                        .addOnSuccessListener(documentReference ->
                                Log.w(TAG, "✅ Evento enviado imediatamente para Firebase"))
                        .addOnFailureListener(e ->
                                Log.w(TAG, "⚠️ Falha no envio imediato - evento já está salvo localmente"));
            }
        } catch (Exception e) {
            Log.w(TAG, "⚠️ Tentativa de Firebase falhou - evento salvo localmente");
        }
    }

    private void clearGeofenceState(SharedPreferences prefs, String geofenceName) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.remove(geofenceName + "_active");
        editor.remove(geofenceName + "_entry_count");
        editor.remove(geofenceName + "_exit_count");
        editor.remove(geofenceName + "_last_entry_time");
        editor.apply();

        Log.d(TAG, "🧹 Estado limpo para: " + geofenceName);
    }

    private boolean isNetworkAvailable(Context context) {
        try {
            android.net.ConnectivityManager connectivityManager =
                    (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

            if (connectivityManager != null) {
                android.net.NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
                return activeNetworkInfo != null && activeNetworkInfo.isConnected();
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
