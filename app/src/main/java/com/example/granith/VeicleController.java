package com.example.granith;

import static android.content.ContentValues.TAG;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class VeicleController extends AppCompatActivity {
    private static final int REQUEST_CODE_SCAN = 101;
    private TextView tvCarDetails;
    private Button btnScanQRCode;
    private Button btnConfirm;
    private String scannedCarId;
    private String currentDriverId;
    private SharedPreferences sharedPreferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qrcode);
        Intent intent = getIntent();
        if (intent != null && intent.hasExtra("VEHICLE_ID")) {
            scannedCarId = intent.getStringExtra("VEHICLE_ID");
        }

        // Restaurar estado após rotação
        if (savedInstanceState != null) {
            scannedCarId = savedInstanceState.getString("SCANNED_ID");
        }

        // Inicializar views
        tvCarDetails = findViewById(R.id.tvCarDetails);
        btnScanQRCode = findViewById(R.id.btnScanQRCode);
        btnConfirm = findViewById(R.id.btnConfirm);

        // Configurar shared preferences
        sharedPreferences = getSharedPreferences("MyAppPrefs", MODE_PRIVATE);
        currentDriverId = sharedPreferences.getString("USER_NAME", "Motorista Desconhecido");

        // Atualizar estado do botão de confirmação
        btnConfirm.setEnabled(scannedCarId != null);

        // Se tiver um ID salvo, buscar detalhes
        if (scannedCarId != null) {
            buscarVeiculoNoFirestore(scannedCarId);
        }

        // Configurar listener do botão de scan
        btnScanQRCode.setOnClickListener(v -> {
            // Opcional: se quiser permitir novo scan nesta tela
            Intent scanIntent = new Intent(VeicleController.this, QRScannerActivity.class);
            startActivityForResult(scanIntent, REQUEST_CODE_SCAN);
        });

        // Configurar listener do botão de confirmar
        btnConfirm.setOnClickListener(v -> {
            if (scannedCarId != null) {
                confirmVehicleAssignment(scannedCarId);
            } else {
                Toast.makeText(this, "Nenhum veículo escaneado.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("SCANNED_ID", scannedCarId);
    }

    private void buscarVeiculoNoFirestore(String vehicleId) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("Veiculos")
                .document(vehicleId)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        DocumentSnapshot document = task.getResult();
                        if (document.exists()) {
                            processarDadosVeiculo(document);
                            scannedCarId = vehicleId;
                            btnConfirm.setEnabled(true);
                        } else {
                            Toast.makeText(this, "Veículo não encontrado", Toast.LENGTH_SHORT).show();
                            scannedCarId = null;
                            btnConfirm.setEnabled(false);
                            tvCarDetails.setText("Veículo não encontrado");
                        }
                    } else {
                        Log.e(TAG, "Erro no Firestore", task.getException());
                        Toast.makeText(this, "Erro na consulta", Toast.LENGTH_SHORT).show();
                        scannedCarId = null;
                        btnConfirm.setEnabled(false);
                        tvCarDetails.setText("Erro ao buscar veículo");
                    }
                });
    }

    private void processarDadosVeiculo(DocumentSnapshot document) {
        String codigo = document.getString("codigo");
        String descricao = document.getString("descricao");
        String motorista = document.getString("motorista");
        String status = document.getString("status");
        Date ultimaAtualizacao = document.getDate("ultimaAtualizacao");

        StringBuilder details = new StringBuilder();
        details.append("Código: ").append(codigo != null ? codigo : "N/A").append("\n");
        details.append("Descrição: ").append(descricao != null ? descricao : "N/A").append("\n");
        details.append("Motorista: ").append(motorista != null ? motorista : "Nenhum").append("\n");
        details.append("Status: ").append(status != null ? status : "indisponível").append("\n");

        if (ultimaAtualizacao != null) {
            details.append("Última Atualização: ").append(ultimaAtualizacao);
        }

        tvCarDetails.setText(details.toString());
        btnConfirm.setEnabled(true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SCAN && resultCode == RESULT_OK && data != null) {
            scannedCarId = data.getStringExtra("SCAN_RESULT");

            if (scannedCarId != null && !scannedCarId.isEmpty()) {
                Log.d(TAG, "QR Code escaneado: " + scannedCarId);
                btnConfirm.setEnabled(true);
                buscarVeiculoNoFirestore(scannedCarId);
            } else {
                Log.e(TAG, "QR Code vazio ou inválido");
                Toast.makeText(this, "QR Code inválido", Toast.LENGTH_SHORT).show();
                tvCarDetails.setText("QR Code inválido");
                btnConfirm.setEnabled(false);
            }
        } else if (requestCode == REQUEST_CODE_SCAN) {
            Log.e(TAG, "Scan cancelado ou falhou. ResultCode: " + resultCode);
            Toast.makeText(this, "Leitura do QR Code cancelada ou falhou", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmVehicleAssignment(String carId) {
        // Verificação de parâmetros
        if (carId == null || carId.isEmpty()) {
            Toast.makeText(this, "ID do veículo inválido", Toast.LENGTH_SHORT).show();
            return;
        }

        // Obter nome atual do motorista
        String currentDriver = sharedPreferences.getString("USER_NAME", "Motorista Desconhecido");
        if (currentDriver.isEmpty()) {
            Toast.makeText(this, "Nome do motorista não encontrado", Toast.LENGTH_SHORT).show();
            return;
        }

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        DocumentReference carRef = db.collection("Veiculos").document(carId);

        // Mostrar que a operação está em andamento
        Toast.makeText(this, "Processando...", Toast.LENGTH_SHORT).show();

        db.runTransaction(transaction -> {
            // Buscar documento ATUAL do veículo
            DocumentSnapshot snapshot = transaction.get(carRef);

            // Validar existência do documento
            if (!snapshot.exists()) {
                throw new FirebaseFirestoreException("Veículo não encontrado",
                        FirebaseFirestoreException.Code.NOT_FOUND);
            }

            // Obter motorista atual
            String oldDriver = snapshot.getString("motorista");

            // Validar se já é o mesmo motorista
            if (currentDriver.equals(oldDriver)) {
                throw new FirebaseFirestoreException("Você já está com este veículo",
                        FirebaseFirestoreException.Code.ABORTED);
            }

            // Preparar atualizações
            Map<String, Object> updates = new HashMap<>();
            updates.put("motorista", currentDriver);
            updates.put("status", "em uso");
            updates.put("ultimaAtualizacao", new Date());

            // Aplicar atualizações
            transaction.update(carRef, updates);

            // Criar histórico DENTRO da transação
            DocumentReference historicoRef = db.collection("Historico").document();
            Map<String, Object> historico = new HashMap<>();
            historico.put("veiculoId", carId);
            historico.put("descricao", snapshot.getString("descricao"));
            historico.put("motoristaAntigo", oldDriver != null ? oldDriver : "Nenhum");
            historico.put("novoMotorista", currentDriver);
            historico.put("data", FieldValue.serverTimestamp()); // Usar timestamp do servidor

            transaction.set(historicoRef, historico);

            return null;

        }).addOnSuccessListener(aVoid -> {
            // Atualizar UI e mostrar feedback
            Toast.makeText(this, "Veículo atribuído com sucesso!", Toast.LENGTH_LONG).show();

            // Log de sucesso para depuração
            Log.d(TAG, "Veículo " + carId + " atribuído com sucesso para " + currentDriverId);

            // Navegar para AssignedVehiclesActivity após sucesso
            Intent intent = new Intent(VeicleController.this, AssignedVehiclesActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish(); // Finaliza a activity atual

        }).addOnFailureListener(e -> {
            // Tratamento detalhado de erros
            if (e instanceof FirebaseFirestoreException) {
                FirebaseFirestoreException firestoreException = (FirebaseFirestoreException) e;
                String errorCode = firestoreException.getCode().name();

                switch (errorCode) {
                    case "NOT_FOUND":
                        Toast.makeText(this, "Veículo não encontrado", Toast.LENGTH_SHORT).show();
                        break;
                    case "ABORTED":
                        Toast.makeText(this, "Você já está com este veículo", Toast.LENGTH_SHORT).show();
                        break;
                    default:
                        Toast.makeText(this, "Erro: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            } else {
                Toast.makeText(this, "Erro ao atribuir veículo: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }

            // Log de erro para depuração
            Log.e(TAG, "Erro na transação", e);
        });
    }
}