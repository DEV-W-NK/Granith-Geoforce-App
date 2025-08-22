package com.example.granith;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.button.MaterialButton;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.util.ArrayList;
import java.util.List;

public class AssignedVehiclesActivity extends AppCompatActivity {
    private static final String TAG = "AssignedVehiclesAct";
    private RecyclerView rvVehicles;
    private ProgressBar progressBar;
    private TextView tvEmpty;
    private VehicleAdapter adapter;
    private FirebaseFirestore db;
    private String currentDriver;
    private SharedPreferences sharedPreferences;
    private static final int SCAN_REQUEST_CODE = 123; // Código único para o scan

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_assigned_vehicles);
        MaterialButton btnScanQRCode = findViewById(R.id.btnScanQRCode);
        btnScanQRCode = findViewById(R.id.btnScanQRCode);
        btnScanQRCode.setOnClickListener(v -> iniciarScanner());

        rvVehicles = findViewById(R.id.rvVehicles);
        progressBar = findViewById(R.id.progressBar);
        tvEmpty = findViewById(R.id.tvEmpty);

        rvVehicles.setLayoutManager(new LinearLayoutManager(this));
        adapter = new VehicleAdapter(new ArrayList<>());
        rvVehicles.setAdapter(adapter);

        sharedPreferences = getSharedPreferences("MyAppPrefs", MODE_PRIVATE);
        currentDriver = sharedPreferences.getString("USER_NAME", "");
        if (currentDriver.isEmpty()) {
            Toast.makeText(this, "Motorista não identificado", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        db = FirebaseFirestore.getInstance();
        fetchAssignedVehicles();
    }

    private void fetchAssignedVehicles() {
        progressBar.setVisibility(View.VISIBLE);
        db.collection("Veiculos")
                .whereEqualTo("motorista", currentDriver)
                .get()
                .addOnCompleteListener(task -> {
                    progressBar.setVisibility(View.GONE);
                    if (task.isSuccessful()) {
                        QuerySnapshot snap = task.getResult();
                        List<Vehicle> list = new ArrayList<>();
                        for (DocumentSnapshot doc : snap.getDocuments()) {
                            Vehicle v = doc.toObject(Vehicle.class);
                            if (v != null) {
                                v.setId(doc.getId());
                                list.add(v);
                            }
                        }
                        if (list.isEmpty()) {
                            tvEmpty.setVisibility(View.VISIBLE);
                        } else {
                            adapter.updateList(list);
                        }
                    } else {
                        Log.e(TAG, "Erro ao buscar veículos", task.getException());
                        Toast.makeText(this, "Erro na consulta", Toast.LENGTH_SHORT).show();
                    }
                });
    }
    private void iniciarScanner() {
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setPrompt("Escaneie o QR Code do veículo");
        integrator.setBeepEnabled(true);
        integrator.setOrientationLocked(false);
        integrator.initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // Processar resultado do scan
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null && result.getContents() != null) {
            String vehicleId = result.getContents();
            verificarEAbirVeiculo(vehicleId);
        }
    }
    private void verificarEAbirVeiculo(String vehicleId) {
        // Verificar se o veículo existe no banco de dados
        db.collection("Veiculos").document(vehicleId)
                .get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists()) {
                        // O veículo existe, permitir acesso à tela de atribuição
                        // independentemente de quem seja o motorista atual
                        Intent intent = new Intent(this, VeicleController.class);
                        intent.putExtra("VEHICLE_ID", vehicleId);
                        startActivity(intent);

                        // Opcional: Adicionar log para depuração
                        Vehicle vehicle = documentSnapshot.toObject(Vehicle.class);
                        String motoristaAtual = (vehicle != null) ? vehicle.getMotorista() : "nenhum";
                        Log.d(TAG, "Veículo encontrado. Motorista atual: " + motoristaAtual);
                    } else {
                        // O veículo não foi encontrado no banco de dados
                        Toast.makeText(this, "Veículo não encontrado no sistema", Toast.LENGTH_LONG).show();
                    }
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Erro ao verificar veículo", Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Erro na verificação", e);
                });
    }
}