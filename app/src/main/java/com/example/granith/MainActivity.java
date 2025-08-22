package com.example.granith;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.FirebaseApp;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;
    private static final int BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE = 2;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 3;
    private static final int BATTERY_OPTIMIZATION_REQUEST_CODE = 4;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 5;

    private TextView statusTextView;
    private FirebaseFirestore db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Intent serviceIntent = new Intent(this, ShutdownMonitorService.class);
        startService(serviceIntent);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Inicializa o Firebase e componentes da UI
        FirebaseApp.initializeApp(this);
        statusTextView = findViewById(R.id.statusTextView);
        db = FirebaseFirestore.getInstance();

        // Verifica se há nome salvo nas SharedPreferences
        SharedPreferences sharedPreferences = getSharedPreferences("MyAppPrefs", MODE_PRIVATE);
        String userName = UserPreferences.loadUserName(sharedPreferences);

        if (userName == null) {
            // Se não houver nome, redireciona para a InputActivity
            startActivity(new Intent(MainActivity.this, InputActivity.class));
            finish();
            return;
        }

        // Verifica se o nome salvo é válido (existe no Firestore)
        checkIfStoredUserNameIsValid(userName, sharedPreferences);
        FloatingActionButton btnAssignedVehicles = findViewById(R.id.btnAssignedVehicles);
        btnAssignedVehicles.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, AssignedVehiclesActivity.class));
        });
    }

    /**
     * Consulta o Firestore para verificar se o nome salvo é válido.
     * Se for válido, continua o fluxo; caso contrário, limpa a preferência e redireciona para a InputActivity.
     */
    private void checkIfStoredUserNameIsValid(String userName, SharedPreferences sharedPreferences) {
        db.collection("Teste")
                .whereEqualTo("nome", userName)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        QuerySnapshot querySnapshot = task.getResult();
                        if (querySnapshot != null && !querySnapshot.isEmpty()) {
                            // Nome é válido: continua o fluxo
                            checkAndUpdateUserInfo(userName);
                            continueApp(userName);
                        } else {
                            // Nome não encontrado: limpa a preferência e redireciona para InputActivity
                            clearUserNamePreference(sharedPreferences);
                            startActivity(new Intent(MainActivity.this, InputActivity.class));
                            finish();
                        }
                    } else {
                        // Em caso de erro na consulta, limpa a preferência e redireciona para InputActivity
                        clearUserNamePreference(sharedPreferences);
                        startActivity(new Intent(MainActivity.this, InputActivity.class));
                        finish();
                    }
                });
    }

    /**
     * Continuação do fluxo após a confirmação de que o nome é válido.
     * Exibe a mensagem de boas-vindas, registra o download (caso ainda não tenha sido feito)
     * e inicia as verificações de permissões.
     */
    private void continueApp(String userName) {
        statusTextView.setText("Bem-vindo, " + userName);
        // Registra o download do aplicativo (apenas uma vez)
        logDownload(userName);
        checkLocationPermissions(userName);
    }

    /**
     * Registra o download do aplicativo na coleção "download" do Firestore.
     * São enviados o nome do usuário, o timestamp (unix) e a versão do app.
     * Essa ação é realizada apenas uma vez, controlada por um flag nas SharedPreferences.
     */

    private void logDownload(String userName) {
        SharedPreferences sharedPreferences = getSharedPreferences("MyAppPrefs", MODE_PRIVATE);
        boolean downloadLogged = sharedPreferences.getBoolean("DOWNLOAD_LOGGED", false);
        if (!downloadLogged) {
            long timestamp = System.currentTimeMillis();
            String version = "";
            try {
                version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                e.printStackTrace();
            }
            // Obtém o nome do aplicativo a partir dos recursos (certifique-se de definir app_name em strings.xml)
            String appName = getString(R.string.app_name);

            Map<String, Object> record = new HashMap<>();
            record.put("user_name", userName);
            record.put("download_timestamp", timestamp);
            record.put("version", version);
            record.put("app_name", appName);

            db.collection("download").add(record)
                    .addOnSuccessListener(documentReference -> {
                        // Marca que o download foi registrado para que não seja feito novamente.
                        sharedPreferences.edit().putBoolean("DOWNLOAD_LOGGED", true).apply();
                    })
                    .addOnFailureListener(e -> {
                        // Se ocorrer falha, pode-se logar o erro ou tentar novamente em outro momento.
                        e.printStackTrace();
                    });
        }
    }
    private void checkAndUpdateUserInfo(String userName) {
        SharedPreferences sharedPreferences = getSharedPreferences("MyAppPrefs", MODE_PRIVATE);
        String savedVersion = sharedPreferences.getString("APP_VERSION", "");
        String currentVersion = "";

        try {
            currentVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            e.printStackTrace();
        }

        final String finalCurrentVersion = currentVersion;
        final String finalUserName = userName; // Nome original (pode ser antigo)
        final String appName = getString(R.string.app_name);

        db.collection("Teste")
                .whereEqualTo("nome", finalUserName)
                .get()
                .addOnCompleteListener(task -> {
                    String updatedUserName = finalUserName;
                    if (task.isSuccessful() && task.getResult() != null && !task.getResult().isEmpty()) {
                        String firestoreName = task.getResult().getDocuments().get(0).getString("nome");
                        if (firestoreName != null && !firestoreName.equals(finalUserName)) {
                            // Atualiza o nome localmente
                            sharedPreferences.edit().putString("USER_NAME", firestoreName).apply();
                            updatedUserName = firestoreName;

                            // Atualiza o user_name na coleção "download" (Adição crítica!)
                            db.collection("download")
                                    .whereEqualTo("user_name", finalUserName) // Busca pelo nome antigo
                                    .get()
                                    .addOnSuccessListener(querySnapshot -> {
                                        for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                                            doc.getReference().update("user_name", firestoreName); // Atualiza para o novo nome
                                        }
                                    });
                        }
                    }

                    // Verifica e atualiza a versão
                    if (!finalCurrentVersion.equals(savedVersion)) {
                        updateUserVersion(updatedUserName, finalCurrentVersion, appName);
                        sharedPreferences.edit().putString("APP_VERSION", finalCurrentVersion).apply();
                    }
                });
    }

    private void updateUserVersion(String userName, String version, String appName) {
        db.collection("download")
                .whereEqualTo("user_name", userName)
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    if (!querySnapshot.isEmpty()) {
                        querySnapshot.getDocuments().get(0).getReference()
                                .update("version", version, "app_name", appName);
                    } else {
                        logDownload(userName);
                    }
                });
    }

    /**
     * Limpa a preferência que armazena o nome do usuário.
     */
    private void clearUserNamePreference(SharedPreferences sharedPreferences) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.remove("USER_NAME");
        editor.apply();
    }

    private void checkLocationPermissions(String userName) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, LOCATION_PERMISSION_REQUEST_CODE);
        } else {
            // Após obter as permissões básicas de localização, verifica a permissão de segundo plano
            checkBackgroundLocationPermission(userName);
        }
    }

    private void checkBackgroundLocationPermission(String userName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // Mostra um diálogo explicativo antes de solicitar a permissão
                showBackgroundLocationPermissionDialog(userName);
            } else {
                // Permissão já concedida, continua com as outras verificações
                checkCameraPermission(userName);
            }
        } else {
            // Android 9 ou inferior não precisa da permissão de segundo plano
            checkCameraPermission(userName);
        }
    }

    private void showBackgroundLocationPermissionDialog(String userName) {
        new AlertDialog.Builder(this)
                .setTitle("Permissão de Localização")
                .setMessage("Para funcionar corretamente, este aplicativo precisa acessar sua localização mesmo quando não estiver em uso.\n\n" +
                        "Na próxima tela, selecione 'Permitir o tempo todo' para garantir o funcionamento adequado do aplicativo.")
                .setPositiveButton("Entendi", (dialog, which) -> {
                    // Atualiza o status para orientar o usuário
                    statusTextView.setText("Aguardando permissão de localização em segundo plano...\n\nIMPORTANTE: Selecione 'Permitir o tempo todo' na próxima tela.");

                    // Solicita a permissão de localização em segundo plano
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ActivityCompat.requestPermissions(this,
                                new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                                BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE);
                    }
                })
                .setNegativeButton("Cancelar", (dialog, which) -> {
                    statusTextView.setText("Permissão de localização em segundo plano é necessária para o funcionamento do aplicativo.");
                    showManualPermissionInstructions();
                })
                .setCancelable(false)
                .show();
    }

    private void checkCameraPermission(String userName) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // Mostra um diálogo explicativo antes de solicitar a permissão da câmera
            showCameraPermissionDialog(userName);
        } else {
            // Permissão já concedida, continua com as outras verificações
            checkNotificationPermission(userName);
        }
    }

    private void showCameraPermissionDialog(String userName) {
        new AlertDialog.Builder(this)
                .setTitle("Permissão da Câmera")
                .setMessage("Este aplicativo precisa de acesso à câmera para escanear códigos QR dos veículos.\n\n" +
                        "Sem essa permissão, você não conseguirá escanear os códigos QR para atribuir veículos.")
                .setPositiveButton("Permitir", (dialog, which) -> {
                    statusTextView.setText("Aguardando permissão da câmera...");
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.CAMERA},
                            CAMERA_PERMISSION_REQUEST_CODE);
                })
                .setNegativeButton("Cancelar", (dialog, which) -> {
                    statusTextView.setText("Permissão da câmera é necessária para escanear códigos QR.");
                    showManualCameraPermissionInstructions(userName);
                })
                .setCancelable(false)
                .show();
    }

    private void showManualCameraPermissionInstructions(String userName) {
        new AlertDialog.Builder(this)
                .setTitle("Como permitir acesso à câmera")
                .setMessage("Para permitir o acesso à câmera manualmente:\n\n" +
                        "1. Vá em Configurações do Android\n" +
                        "2. Acesse 'Aplicativos' ou 'Gerenciar aplicativos'\n" +
                        "3. Encontre este aplicativo na lista\n" +
                        "4. Toque em 'Permissões'\n" +
                        "5. Toque em 'Câmera'\n" +
                        "6. Selecione 'Permitir'\n\n" +
                        "Após isso, você poderá escanear códigos QR.")
                .setPositiveButton("Abrir Configurações", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.fromParts("package", getPackageName(), null));
                    startActivity(intent);
                })
                .setNegativeButton("Tentar Novamente", (dialog, which) -> {
                    checkCameraPermission(userName);
                })
                .setNeutralButton("Continuar sem câmera", (dialog, which) -> {
                    statusTextView.setText("Câmera desabilitada. Você não poderá escanear códigos QR.");
                    checkNotificationPermission(userName);
                })
                .show();
    }

    private void showManualPermissionInstructions() {
        new AlertDialog.Builder(this)
                .setTitle("Como permitir localização o tempo todo")
                .setMessage("Para permitir o acesso à localização o tempo todo manualmente:\n\n" +
                        "1. Vá em Configurações do Android\n" +
                        "2. Acesse 'Aplicativos' ou 'Gerenciar aplicativos'\n" +
                        "3. Encontre este aplicativo na lista\n" +
                        "4. Toque em 'Permissões'\n" +
                        "5. Toque em 'Localização'\n" +
                        "6. Selecione 'Permitir o tempo todo'\n\n" +
                        "Após isso, reinicie o aplicativo.")
                .setPositiveButton("Abrir Configurações", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.fromParts("package", getPackageName(), null));
                    startActivity(intent);
                })
                .setNegativeButton("Tentar Novamente", (dialog, which) -> {
                    String userName = UserPreferences.loadUserName(getSharedPreferences("MyAppPrefs", MODE_PRIVATE));
                    checkBackgroundLocationPermission(userName);
                })
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        String userName = UserPreferences.loadUserName(getSharedPreferences("MyAppPrefs", MODE_PRIVATE));

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted(grantResults)) {
                checkBackgroundLocationPermission(userName);
            } else {
                handlePermissionDenied("Permissão de localização necessária.");
            }
        } else if (requestCode == BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                statusTextView.setText("Bem-vindo, " + userName + "\nPermissão de localização em segundo plano concedida!");
                checkCameraPermission(userName);
            } else {
                statusTextView.setText("Permissão de localização em segundo plano negada.\n\nO aplicativo pode não funcionar corretamente.");
                showManualPermissionInstructions();
            }
        } else if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                statusTextView.setText("Bem-vindo, " + userName + "\nPermissão da câmera concedida!");
                checkNotificationPermission(userName);
            } else {
                statusTextView.setText("Permissão da câmera negada.\n\nVocê não poderá escanear códigos QR.");
                showManualCameraPermissionInstructions(userName);
            }
        } else if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkBatteryOptimization(userName);
            } else {
                statusTextView.setText("Permissão de notificações negada.");
            }
        }
    }

    private boolean allPermissionsGranted(int[] grantResults) {
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void handlePermissionDenied(String message) {
        statusTextView.setText(message);
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
    }

    private void checkNotificationPermission(String userName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST_CODE);
        } else {
            checkBatteryOptimization(userName);
        }
    }

    private void checkBatteryOptimization(String userName) {
        SharedPreferences sharedPreferences = getSharedPreferences("MyAppPrefs", MODE_PRIVATE);
        boolean batteryOptimizationAsked = sharedPreferences.getBoolean("BatteryOptimizationAsked", false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName()) && !batteryOptimizationAsked) {
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putBoolean("BatteryOptimizationAsked", true);
                editor.apply();

                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, BATTERY_OPTIMIZATION_REQUEST_CODE);
            } else {
                startLocationService(userName);
            }
        } else {
            startLocationService(userName);
        }
    }

    private void startLocationService(String userName) {
        Intent serviceIntent = new Intent(this, LocationForegroundService.class);
        serviceIntent.putExtra("USER_NAME", userName);
        ContextCompat.startForegroundService(this, serviceIntent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == BATTERY_OPTIMIZATION_REQUEST_CODE) {
            new Handler().postDelayed(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                    if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
                        String userName = UserPreferences.loadUserName(getSharedPreferences("MyAppPrefs", MODE_PRIVATE));
                        startLocationService(userName);
                    } else {
                        statusTextView.setText("A otimização de bateria ainda está ativada. Por favor, desative-a.");
                    }
                }
            }, 2000); // Delay de 2 segundos para permitir que o sistema aplique as alterações
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Verifica novamente as permissões quando o usuário volta para o app
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                String userName = UserPreferences.loadUserName(getSharedPreferences("MyAppPrefs", MODE_PRIVATE));
                if (userName != null) {
                    statusTextView.setText("Bem-vindo, " + userName);
                }

            }
        }
    }
}