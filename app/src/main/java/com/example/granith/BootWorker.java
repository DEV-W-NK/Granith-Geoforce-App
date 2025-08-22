package com.example.granith;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import android.util.Log;

public class BootWorker extends Worker {
    private static final String TAG = "BootWorker";

    public BootWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();

        try {
            // Inicia o serviço de monitoramento ao invés do serviço principal
            Intent monitorIntent = new Intent(context, ServiceConditionMonitor.class);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(monitorIntent);
            } else {
                context.startService(monitorIntent);
            }

            Log.d(TAG, "Serviço de monitoramento iniciado pelo BootWorker.");
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Falha ao iniciar o serviço de monitoramento", e);
            return Result.failure();
        }
    }
}
