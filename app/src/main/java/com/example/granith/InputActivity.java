package com.example.granith;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.ProgressBar;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InputActivity extends AppCompatActivity {

    private static final String SHARED_PREFS_NAME = "MyAppPrefs";
    private static final String USER_NAME_KEY = "USER_NAME";
    private static final String USER_ROLE_KEY = "USER_ROLE";
    private static final String TAG = "InputActivity";

    private AutoCompleteTextView nameAutoCompleteTextView;
    private ProgressBar loadingProgressBar;
    private Button startMonitoringButton;
    private FirebaseFirestore db;
    private ArrayAdapter<String> adapter;

    // Lista completa com os nomes obtidos do Firestore
    private List<String> allNames = new ArrayList<>();
    // Lista filtrada conforme o usuário digita
    private List<String> filteredNames = new ArrayList<>();

    // Map para armazenar informações dos funcionários (nome -> dados)
    private Map<String, EmployeeData> employeesMap = new HashMap<>();

    // Listener para monitorar aprovações em tempo real
    private ListenerRegistration approvalListener;
    private String currentUserName;
    private String currentCompanyId;
    private boolean isWaitingForApproval = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_input);

        nameAutoCompleteTextView = findViewById(R.id.nameAutoCompleteTextView);
        loadingProgressBar = findViewById(R.id.loadingProgressBar);
        startMonitoringButton = findViewById(R.id.startMonitoringButton);
        db = FirebaseFirestore.getInstance();

        // Inicializa o adapter usando a lista filtrada
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, filteredNames);
        nameAutoCompleteTextView.setAdapter(adapter);

        // Carrega os funcionários de todas as empresas
        loadEmployeesFromFirestore();

        // Listener para atualizar a lista de sugestões conforme o usuário digita
        nameAutoCompleteTextView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterNames(s.toString().toLowerCase());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // Ao selecionar um item, atualiza o texto do AutoCompleteTextView
        nameAutoCompleteTextView.setOnItemClickListener((parent, view, position, id) -> {
            String selectedName = (String) parent.getItemAtPosition(position);
            nameAutoCompleteTextView.setText(selectedName);
            Log.d(TAG, "Nome selecionado: " + selectedName);
        });

        // Configura o botão de "Entrar"
        startMonitoringButton.setOnClickListener(v -> {
            String userName = nameAutoCompleteTextView.getText().toString().trim();
            if (userName.isEmpty()) {
                showAlert("Nome inválido", "Por favor, insira um nome válido.");
            } else {
                authenticateUser(userName);
            }
        });
    }

    /**
     * Carrega funcionários de todas as empresas usando collectionGroup
     */
    private void loadEmployeesFromFirestore() {
        loadingProgressBar.setVisibility(View.VISIBLE);

        db.collectionGroup("employees")
                .whereEqualTo("active", true)
                .addSnapshotListener(new EventListener<QuerySnapshot>() {
                    @Override
                    public void onEvent(@Nullable QuerySnapshot value, @Nullable FirebaseFirestoreException error) {
                        loadingProgressBar.setVisibility(View.GONE);
                        if (error != null) {
                            Log.e(TAG, "Erro ao carregar funcionários", error);
                            return;
                        }
                        if (value != null) {
                            allNames.clear();
                            employeesMap.clear();

                            for (QueryDocumentSnapshot doc : value) {
                                String nome = doc.getString("name");
                                if (nome != null) {
                                    allNames.add(nome);

                                    // Extrair companyId do caminho do documento
                                    String companyId = doc.getReference().getParent().getParent().getId();

                                    EmployeeData employeeData = new EmployeeData(
                                            doc.getId(),
                                            nome,
                                            companyId,
                                            doc.getString("role"),
                                            doc.getString("department")
                                    );
                                    employeesMap.put(nome, employeeData);
                                }
                            }

                            // Atualiza a filtragem com o texto atual
                            filterNames(nameAutoCompleteTextView.getText().toString().toLowerCase());
                            Log.d(TAG, "Carregados " + allNames.size() + " funcionários");
                        }
                    }
                });
    }

    /**
     * Filtra a lista de nomes com base no texto digitado.
     */
    private void filterNames(String searchText) {
        filteredNames.clear();
        if (!searchText.isEmpty()) {
            for (String name : allNames) {
                if (name.toLowerCase().startsWith(searchText)) {
                    filteredNames.add(name);
                }
            }
        }
        adapter.notifyDataSetChanged();
        nameAutoCompleteTextView.showDropDown();
    }

    /**
     * Autentica o usuário verificando se existe e tem permissões
     */
    private void authenticateUser(String userName) {
        loadingProgressBar.setVisibility(View.VISIBLE);
        currentUserName = userName;

        // Verifica se o funcionário existe
        EmployeeData employee = employeesMap.get(userName);
        if (employee == null) {
            loadingProgressBar.setVisibility(View.GONE);
            showAlert("Erro", "Funcionário não encontrado ou inativo.");
            return;
        }

        currentCompanyId = employee.companyId;
        Log.d(TAG, "Funcionário encontrado: " + userName + " da empresa: " + currentCompanyId);

        // Verifica aprovação administrativa na empresa específica
        checkAdminApproval(userName, currentCompanyId, employee);
    }

    /**
     * Verifica se o usuário tem aprovação administrativa na empresa específica
     */
    private void checkAdminApproval(String userName, String companyId, EmployeeData employee) {
        db.collection("companies")
                .document(companyId)
                .collection("permission_requests")
                .whereEqualTo("userName", userName)
                .whereEqualTo("status", "approved")
                .get()
                .addOnCompleteListener(task -> {
                    loadingProgressBar.setVisibility(View.GONE);
                    if (task.isSuccessful()) {
                        QuerySnapshot querySnapshot = task.getResult();
                        if (querySnapshot != null && !querySnapshot.isEmpty()) {
                            // Usuário aprovado
                            QueryDocumentSnapshot doc = (QueryDocumentSnapshot) querySnapshot.getDocuments().get(0);
                            String assignedRole = doc.getString("assignedRole");
                            Log.d(TAG, "Usuário aprovado: " + userName + " - Papel: " + assignedRole);

                            // Configura a empresa no CompanyService
                            setupCompanyAndProceed(companyId, employee, userName, assignedRole);
                        } else {
                            // Usuário não aprovado, verifica se já existe solicitação pendente
                            checkExistingRequest(userName, companyId, employee);
                        }
                    } else {
                        showAlert("Erro", "Falha ao verificar aprovação administrativa.");
                    }
                });
    }

    /**
     * Configura a empresa e procede com o login
     */
    private void setupCompanyAndProceed(String companyId, EmployeeData employee, String userName, String assignedRole) {
        // Buscar nome da empresa (opcional, pode usar um nome padrão)
        db.collection("companies")
                .document(companyId)
                .get()
                .addOnSuccessListener(documentSnapshot -> {
                    String companyName = documentSnapshot.getString("name");
                    if (companyName == null) companyName = "Empresa " + companyId;

                    // Configura a empresa no CompanyService
                    CompanyService companyService = CompanyService.getInstance(this);
                    companyService.setCurrentCompany(companyId, companyName);

                    // Salva dados do usuário
                    saveUserData(userName, assignedRole, employee.employeeId);

                    // Cria sessão de login
                    createLoginSession(companyId, employee.employeeId, userName);

                    navigateToMainActivity();
                })
                .addOnFailureListener(e -> {
                    // Mesmo se falhar ao buscar nome da empresa, continua com o login
                    CompanyService companyService = CompanyService.getInstance(this);
                    companyService.setCurrentCompany(companyId, "Empresa " + companyId);

                    saveUserData(userName, assignedRole, employee.employeeId);
                    createLoginSession(companyId, employee.employeeId, userName);
                    navigateToMainActivity();
                });
    }

    /**
     * Verifica se já existe uma solicitação pendente para o usuário na empresa
     */
    private void checkExistingRequest(String userName, String companyId, EmployeeData employee) {
        db.collection("companies")
                .document(companyId)
                .collection("permission_requests")
                .whereEqualTo("userName", userName)
                .whereEqualTo("status", "pending")
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        QuerySnapshot querySnapshot = task.getResult();
                        if (querySnapshot != null && !querySnapshot.isEmpty()) {
                            // Já existe solicitação pendente, inicia monitoramento
                            startApprovalMonitoring(userName, companyId);
                            showWaitingDialog();
                        } else {
                            // Não existe solicitação, cria uma nova
                            createAccessRequest(userName, companyId, employee);
                        }
                    } else {
                        showAlert("Erro", "Falha ao verificar solicitações existentes.");
                    }
                });
    }

    /**
     * Cria uma solicitação de acesso na empresa específica
     */
    private void createAccessRequest(String userName, String companyId, EmployeeData employee) {
        Map<String, Object> accessRequest = new HashMap<>();
        accessRequest.put("employeeId", employee.employeeId);
        accessRequest.put("userName", userName);
        accessRequest.put("requestDate", System.currentTimeMillis());
        accessRequest.put("status", "pending");
        accessRequest.put("deviceInfo", getDeviceInfo());
        accessRequest.put("deviceModel", getDeviceModel());
        accessRequest.put("department", employee.department);

        db.collection("companies")
                .document(companyId)
                .collection("permission_requests")
                .add(accessRequest)
                .addOnSuccessListener(documentReference -> {
                    Log.d(TAG, "Solicitação de acesso criada na empresa " + companyId + ": " + documentReference.getId());

                    // Inicia o monitoramento da aprovação
                    startApprovalMonitoring(userName, companyId);
                    showWaitingDialog();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Erro ao criar solicitação de acesso", e);
                    showAlert("Erro", "Falha ao enviar solicitação de acesso.");
                });
    }

    /**
     * Inicia o monitoramento em tempo real para aprovações na empresa específica
     */
    private void startApprovalMonitoring(String userName, String companyId) {
        // Remove listener anterior se existir
        if (approvalListener != null) {
            approvalListener.remove();
        }

        isWaitingForApproval = true;
        Log.d(TAG, "Iniciando monitoramento de aprovação para: " + userName + " na empresa: " + companyId);

        approvalListener = db.collection("companies")
                .document(companyId)
                .collection("permission_requests")
                .whereEqualTo("userName", userName)
                .addSnapshotListener(new EventListener<QuerySnapshot>() {
                    @Override
                    public void onEvent(@Nullable QuerySnapshot value, @Nullable FirebaseFirestoreException error) {
                        if (error != null) {
                            Log.e(TAG, "Erro no monitoramento de aprovação", error);
                            return;
                        }

                        if (value != null && !value.isEmpty()) {
                            for (QueryDocumentSnapshot doc : value) {
                                String status = doc.getString("status");
                                Log.d(TAG, "Status da solicitação: " + status);

                                if ("approved".equals(status)) {
                                    // Usuário foi aprovado!
                                    String assignedRole = doc.getString("assignedRole");
                                    Log.d(TAG, "Usuário aprovado automaticamente: " + userName + " - Papel: " + assignedRole);

                                    stopApprovalMonitoring();

                                    EmployeeData employee = employeesMap.get(userName);
                                    setupCompanyAndProceed(companyId, employee, userName, assignedRole);

                                    runOnUiThread(() -> {
                                        dismissWaitingDialog();
                                        showAlert("Aprovado!", "Sua solicitação foi aprovada! Entrando no sistema...");

                                        nameAutoCompleteTextView.postDelayed(() -> {
                                            navigateToMainActivity();
                                        }, 2000);
                                    });

                                    return;
                                } else if ("rejected".equals(status)) {
                                    // Usuário foi rejeitado
                                    Log.d(TAG, "Usuário rejeitado: " + userName);

                                    stopApprovalMonitoring();

                                    runOnUiThread(() -> {
                                        dismissWaitingDialog();
                                        showAlert("Solicitação Rejeitada",
                                                "Sua solicitação de acesso foi rejeitada pelo administrador.");
                                    });

                                    return;
                                }
                            }
                        }
                    }
                });
    }

    /**
     * Cria uma sessão de login na empresa
     */
    private void createLoginSession(String companyId, String employeeId, String userName) {
        Map<String, Object> session = new HashMap<>();
        session.put("employeeId", employeeId);
        session.put("employeeName", userName);
        session.put("loginTime", System.currentTimeMillis());
        session.put("deviceInfo", getDeviceModel());
        session.put("appVersion", "1.0.0"); // Você pode tornar isso dinâmico
        session.put("active", true);

        db.collection("companies")
                .document(companyId)
                .collection("sessions")
                .add(session)
                .addOnSuccessListener(documentReference -> {
                    Log.d(TAG, "Sessão criada: " + documentReference.getId());

                    // Salva o ID da sessão para poder encerrar depois
                    SharedPreferences prefs = getSharedPreferences(SHARED_PREFS_NAME, MODE_PRIVATE);
                    prefs.edit().putString("SESSION_ID", documentReference.getId()).apply();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Erro ao criar sessão", e);
                });
    }

    /**
     * Para o monitoramento de aprovação
     */
    private void stopApprovalMonitoring() {
        if (approvalListener != null) {
            approvalListener.remove();
            approvalListener = null;
        }
        isWaitingForApproval = false;
    }

    /**
     * Exibe dialog de aguardando aprovação
     */
    private void showWaitingDialog() {
        runOnUiThread(() -> {
            new AlertDialog.Builder(this)
                    .setTitle("Aguardando Aprovação")
                    .setMessage("Sua solicitação foi enviada para aprovação administrativa.\n\n" +
                            "O aplicativo entrará automaticamente quando sua solicitação for aprovada.\n\n" +
                            "Mantenha o aplicativo aberto para receber a aprovação em tempo real.")
                    .setCancelable(false)
                    .setNegativeButton("Cancelar", (dialog, which) -> {
                        stopApprovalMonitoring();
                        dialog.dismiss();
                    })
                    .show();
        });
    }

    /**
     * Fecha o dialog de espera
     */
    private void dismissWaitingDialog() {
        // Implementar se necessário manter referência ao dialog
    }

    /**
     * Obtém informações do dispositivo
     */
    private String getDeviceInfo() {
        return "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
    }

    /**
     * Obtém o modelo do dispositivo
     */
    private String getDeviceModel() {
        return Build.MANUFACTURER + " " + Build.MODEL;
    }

    /**
     * Salva os dados do usuário nas SharedPreferences
     */
    private void saveUserData(String userName, String userRole, String employeeId) {
        SharedPreferences sharedPreferences = getSharedPreferences(SHARED_PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(USER_NAME_KEY, userName);
        editor.putString(USER_ROLE_KEY, userRole != null ? userRole : "Usuario");
        editor.putString("EMPLOYEE_ID", employeeId);
        editor.apply();

        Log.d(TAG, "Dados salvos - Usuário: " + userName + ", Papel: " + userRole + ", ID: " + employeeId);
    }

    /**
     * Navega para a MainActivity
     */
    private void navigateToMainActivity() {
        Intent intent = new Intent(InputActivity.this, MainActivity.class);
        startActivity(intent);
        finish();
    }

    /**
     * Exibe um alerta
     */
    private void showAlert(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopApprovalMonitoring();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!isWaitingForApproval) {
            stopApprovalMonitoring();
        }
    }

    /**
     * Classe auxiliar para armazenar dados do funcionário
     */
    private static class EmployeeData {
        String employeeId;
        String name;
        String companyId;
        String role;
        String department;

        EmployeeData(String employeeId, String name, String companyId, String role, String department) {
            this.employeeId = employeeId;
            this.name = name;
            this.companyId = companyId;
            this.role = role;
            this.department = department;
        }
    }
}