package com.example.granith;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.util.Log;

/**
 * Receiver que monitora bateria baixa e crítica
 */
public class BatteryReceiver extends BroadcastReceiver {
    private static final String TAG = "BatteryReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();

        if (Intent.ACTION_BATTERY_LOW.equals(action)) {
            Log.w(TAG, "🔋 BATERIA BAIXA - preparando para possível shutdown");
            prepareBatteryShutdown(context, "Bateria Baixa");

        } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
            checkBatteryLevel(context, intent);
        }
    }

    private void prepareBatteryShutdown(Context context, String reason) {
        try {
            Log.w(TAG, "⚠️ Preparando para shutdown por: " + reason);

            // Salva estado de emergência
            SharedPreferences prefs = context.getSharedPreferences("DeviceStatePrefs", Context.MODE_PRIVATE);
            prefs.edit()
                    .putBoolean("battery_emergency", true)
                    .putString("battery_emergency_reason", reason)
                    .putLong("battery_emergency_time", System.currentTimeMillis())
                    .apply();

            // Se bateria crítica, processa geofences imediatamente
            if ("Bateria Crítica".equals(reason)) {
                processGeofencesForBatteryShutdown(context, reason);
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao preparar shutdown por bateria", e);
        }
    }

    private void checkBatteryLevel(Context context, Intent intent) {
        try {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

            if (level >= 0 && scale > 0) {
                float batteryPct = (level / (float) scale) * 100;

                // Se bateria muito baixa (< 3%), processa geofences preventivamente
                if (batteryPct <= 3.0f) {
                    Log.w(TAG, "🔋⚡ Bateria extremamente baixa: " + batteryPct + "%");
                    processGeofencesForBatteryShutdown(context, "Bateria " + batteryPct + "%");
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao verificar nível da bateria", e);
        }
    }

    private void processGeofencesForBatteryShutdown(Context context, String reason) {
        // Reutiliza a lógica do ShutdownReceiver
        ShutdownReceiver shutdownReceiver = new ShutdownReceiver();
        shutdownReceiver.processActiveGeofencesOnShutdown(context, reason);
    }
}
