package com.example.granith;


import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.util.Log;
import com.google.firebase.firestore.FirebaseFirestore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Serviço que sincroniza eventos offline com Firebase
 */
public class OfflineSyncService extends Service {
    private static final String TAG = "OfflineSyncService";
    private FirebaseFirestore firestore;

    @Override
    public void onCreate() {
        super.onCreate();
        firestore = FirebaseFirestore.getInstance();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        syncOfflineEvents();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void syncOfflineEvents() {
        try {
            SharedPreferences prefs = getSharedPreferences("MyAppPrefs", Context.MODE_PRIVATE);
            String offlineEventsString = prefs.getString("offline_events", "[]");
            JSONArray offlineEvents = new JSONArray(offlineEventsString);

            if (offlineEvents.length() == 0) {
                Log.d(TAG, "Nenhum evento offline para sincronizar");
                stopSelf();
                return;
            }

            Log.d(TAG, "📤 Sincronizando " + offlineEvents.length() + " eventos offline");

            for (int i = 0; i < offlineEvents.length(); i++) {
                JSONObject eventJson = offlineEvents.getJSONObject(i);

                // Converte JSON para Map
                Map<String, Object> eventMap = new HashMap<>();
                for (Iterator<String> it = eventJson.keys(); it.hasNext(); ) {
                    String key = it.next();
                    eventMap.put(key, eventJson.get(key));
                }

                // Envia para Firebase
                firestore.collection("geofence_records")
                        .add(eventMap)
                        .addOnSuccessListener(documentReference -> {
                            Log.d(TAG, "✅ Evento sincronizado: " + documentReference.getId());
                        })
                        .addOnFailureListener(e -> {
                            Log.e(TAG, "❌ Falha na sincronização", e);
                        });
            }

            // Limpa eventos após envio
            prefs.edit().putString("offline_events", "[]").apply();
            Log.d(TAG, "🧹 Eventos offline limpos após sincronização");

        } catch (Exception e) {
            Log.e(TAG, "❌ Erro na sincronização", e);
        } finally {
            stopSelf();
        }
    }
}
