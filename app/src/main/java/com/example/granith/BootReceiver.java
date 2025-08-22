package com.example.granith;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

/**
 * Receiver que combina funcionalidade original com detecção de shutdown perdido
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";
    private static final long SHUTDOWN_DETECTION_WINDOW = 300000; // 5 minutos

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        Log.d(TAG, "BootReceiver recebido: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
                Intent.ACTION_PACKAGE_REPLACED.equals(action)) {

            Log.d(TAG, "Boot concluído. Iniciando monitoramento de condições.");

            // === FUNCIONALIDADE ORIGINAL ===
            // Inicia o BootWorker original
            OneTimeWorkRequest bootWorkRequest = new OneTimeWorkRequest.Builder(BootWorker.class)
                    .build();
            WorkManager.getInstance(context).enqueue(bootWorkRequest);

            // === NOVA FUNCIONALIDADE ===
            // Verifica se houve shutdown não processado
            checkForUnprocessedShutdown(context);

            // Inicia o serviço de monitoramento de shutdown
            startShutdownMonitoringService(context);

            // Reseta flags de inicialização
            resetStartupFlags(context);
        }
    }

    /**
     * Verifica se houve shutdown não processado baseado no heartbeat
     */
    private void checkForUnprocessedShutdown(Context context) {
        try {
            SharedPreferences heartbeatPrefs = context.getSharedPreferences("HeartbeatPrefs", Context.MODE_PRIVATE);
            SharedPreferences devicePrefs = context.getSharedPreferences("DeviceStatePrefs", Context.MODE_PRIVATE);

            long lastHeartbeat = heartbeatPrefs.getLong("last_heartbeat", 0);
            boolean wasRunning = heartbeatPrefs.getBoolean("app_running", false);
            boolean shutdownProcessed = devicePrefs.getBoolean("shutdown_processed", true);

            long currentTime = System.currentTimeMillis();
            long timeSinceLastHeartbeat = currentTime - lastHeartbeat;

            Log.d(TAG, "=== VERIFICAÇÃO DE SHUTDOWN ===");
            Log.d(TAG, "Último heartbeat: " + timeSinceLastHeartbeat + "ms atrás");
            Log.d(TAG, "App estava rodando: " + wasRunning);
            Log.d(TAG, "Shutdown já processado: " + shutdownProcessed);

            // Condições para detectar shutdown perdido:
            // 1. App estava rodando antes do boot
            // 2. Passou tempo suficiente desde último heartbeat
            // 3. Shutdown ainda não foi processado
            if (wasRunning &&
                    lastHeartbeat > 0 &&
                    timeSinceLastHeartbeat > SHUTDOWN_DETECTION_WINDOW &&
                    !shutdownProcessed) {

                Log.w(TAG, "🚨 SHUTDOWN NÃO PROCESSADO DETECTADO!");
                Log.w(TAG, "Gap de tempo: " + (timeSinceLastHeartbeat / 1000) + " segundos");

                // Processa o shutdown perdido
                processUnprocessedShutdown(context, lastHeartbeat);
            } else {
                Log.d(TAG, "✅ Nenhum shutdown perdido detectado");
            }

            // Limpa flags antigas do heartbeat
            heartbeatPrefs.edit()
                    .putBoolean("app_running", false)
                    .putLong("last_heartbeat", currentTime)
                    .apply();

        } catch (Exception e) {
            Log.e(TAG, "Erro ao verificar shutdown não processado", e);
        }
    }

    /**
     * Processa shutdown que não foi detectado durante o desligamento
     */
    private void processUnprocessedShutdown(Context context, long lastHeartbeatTime) {
        try {
            Log.w(TAG, "🔄 Processando shutdown perdido...");

            // Cria Intent para processar o shutdown com timestamp estimado
            Intent shutdownIntent = new Intent(context, ShutdownJobIntentService.class);
            shutdownIntent.putExtra("shutdown_reason", "Shutdown Não Detectado (Recovery)");
            shutdownIntent.putExtra("estimated_shutdown_time", lastHeartbeatTime);
            shutdownIntent.putExtra("is_recovery", true);
            shutdownIntent.putExtra("detection_method", "boot_heartbeat_gap");

            // Enfileira o trabalho usando JobIntentService
            androidx.core.app.JobIntentService.enqueueWork(
                    context,
                    ShutdownJobIntentService.class,
                    ShutdownJobIntentService.JOB_ID,
                    shutdownIntent
            );

            Log.d(TAG, "✅ Shutdown perdido enfileirado para processamento");

        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao processar shutdown perdido", e);
        }
    }

    /**
     * Inicia o serviço de monitoramento contínuo de shutdown
     */
    private void startShutdownMonitoringService(Context context) {
        try {
            Intent serviceIntent = new Intent(context, ShutdownMonitorService.class);
            context.startService(serviceIntent);
            Log.d(TAG, "🚀 Serviço de monitoramento de shutdown iniciado");
        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao iniciar serviço de monitoramento", e);
        }
    }

    /**
     * Reseta flags relacionadas ao boot e shutdown
     */
    private void resetStartupFlags(Context context) {
        try {
            SharedPreferences devicePrefs = context.getSharedPreferences("DeviceStatePrefs", Context.MODE_PRIVATE);

            // Reseta flags de controle
            devicePrefs.edit()
                    .putBoolean("shutdown_processed", true)
                    .putLong("last_boot_time", System.currentTimeMillis())
                    .putBoolean("boot_completed", true)
                    .apply();

            Log.d(TAG, "🔄 Flags de inicialização resetadas");

        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao resetar flags de inicialização", e);
        }
    }
}
