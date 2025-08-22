package com.example.granith;


import com.google.firebase.Timestamp;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

/**
 * Classe auxiliar para gerenciar aprovações administrativas no Firestore.
 */
public class AdminApprovalHelper {

    private static final String COLLECTION_NAME = "PermissionRequests";
    private FirebaseFirestore db;

    public AdminApprovalHelper() {
        this.db = FirebaseFirestore.getInstance();
    }

    /**
     * Interface para callbacks de operações assíncronas.
     */
    public interface AdminApprovalCallback {
        void onSuccess();
        void onFailure(Exception e);
    }

    /**
     * Aprova um usuário e define seu papel no sistema.
     * Esta função seria usada por um administrador para aprovar solicitações.
     *
     * @param userName Nome do usuário a ser aprovado.
     * @param assignedRole Papel atribuído ao usuário (ex: "Coordenador", "Usuario", etc.).
     * @param callback Callback para resultado da operação.
     */
    public void approveUser(String userName, String assignedRole, AdminApprovalCallback callback) {
        // Primeiro busca se já existe uma solicitação pendente
        db.collection(COLLECTION_NAME)
                .whereEqualTo("userName", userName)
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    Map<String, Object> approvalData = new HashMap<>();
                    approvalData.put("userName", userName);
                    approvalData.put("assignedRole", assignedRole);
                    approvalData.put("status", "approved");
                    approvalData.put("approvedDate", Timestamp.now());
                    approvalData.put("deviceInfo", "Android 14 (API 34)"); // Exemplo
                    approvalData.put("deviceModel", "motorola motorola edge 30 neo"); // Exemplo
                    approvalData.put("requestDate", System.currentTimeMillis());

                    if (!querySnapshot.isEmpty()) {
                        // Atualiza o documento existente
                        String documentId = querySnapshot.getDocuments().get(0).getId();
                        db.collection(COLLECTION_NAME)
                                .document(documentId)
                                .update(approvalData)
                                .addOnSuccessListener(aVoid -> callback.onSuccess())
                                .addOnFailureListener(callback::onFailure);
                    } else {
                        // Cria um novo documento
                        db.collection(COLLECTION_NAME)
                                .add(approvalData)
                                .addOnSuccessListener(documentReference -> callback.onSuccess())
                                .addOnFailureListener(callback::onFailure);
                    }
                })
                .addOnFailureListener(callback::onFailure);
    }

    /**
     * Rejeita um usuário.
     *
     * @param userName Nome do usuário a ser rejeitado.
     * @param callback Callback para resultado da operação.
     */
    public void rejectUser(String userName, AdminApprovalCallback callback) {
        db.collection(COLLECTION_NAME)
                .whereEqualTo("userName", userName)
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    if (!querySnapshot.isEmpty()) {
                        String documentId = querySnapshot.getDocuments().get(0).getId();
                        Map<String, Object> updateData = new HashMap<>();
                        updateData.put("status", "rejected");
                        updateData.put("rejectedDate", Timestamp.now());

                        db.collection(COLLECTION_NAME)
                                .document(documentId)
                                .update(updateData)
                                .addOnSuccessListener(aVoid -> callback.onSuccess())
                                .addOnFailureListener(callback::onFailure);
                    } else {
                        callback.onFailure(new Exception("Usuário não encontrado"));
                    }
                })
                .addOnFailureListener(callback::onFailure);
    }

    /**
     * Remove a aprovação de um usuário (revoga acesso).
     *
     * @param userName Nome do usuário.
     * @param callback Callback para resultado da operação.
     */
    public void revokeAccess(String userName, AdminApprovalCallback callback) {
        db.collection(COLLECTION_NAME)
                .whereEqualTo("userName", userName)
                .whereEqualTo("status", "approved")
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    if (!querySnapshot.isEmpty()) {
                        String documentId = querySnapshot.getDocuments().get(0).getId();
                        Map<String, Object> updateData = new HashMap<>();
                        updateData.put("status", "revoked");
                        updateData.put("revokedDate", Timestamp.now());

                        db.collection(COLLECTION_NAME)
                                .document(documentId)
                                .update(updateData)
                                .addOnSuccessListener(aVoid -> callback.onSuccess())
                                .addOnFailureListener(callback::onFailure);
                    } else {
                        callback.onFailure(new Exception("Aprovação não encontrada"));
                    }
                })
                .addOnFailureListener(callback::onFailure);
    }

    /**
     * Exemplo de como um administrador poderia usar esta classe.
     * Este método não seria chamado na aplicação normal, apenas para demonstração.
     */
    public void exemploUsoAdministrador() {
        // Aprovar um usuário
        approveUser("Guilherme Leonardo de Barros", "Coordenador", new AdminApprovalCallback() {
            @Override
            public void onSuccess() {
                System.out.println("Usuário aprovado com sucesso!");
            }

            @Override
            public void onFailure(Exception e) {
                System.out.println("Erro ao aprovar usuário: " + e.getMessage());
            }
        });
    }
}
