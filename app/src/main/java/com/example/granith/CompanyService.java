package com.example.granith;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

public class CompanyService {
    private static final String TAG = "CompanyService";
    private static final String PREFS_NAME = "company_prefs";
    private static final String KEY_COMPANY_ID = "current_company_id";
    private static final String KEY_COMPANY_NAME = "current_company_name";

    private static CompanyService instance;
    private final SharedPreferences sharedPreferences;
    private String currentCompanyId;
    private String currentCompanyName;

    private CompanyService(Context context) {
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadCompanyData();
    }

    public static synchronized CompanyService getInstance(Context context) {
        if (instance == null) {
            instance = new CompanyService(context.getApplicationContext());
        }
        return instance;
    }

    private void loadCompanyData() {
        currentCompanyId = sharedPreferences.getString(KEY_COMPANY_ID, null);
        currentCompanyName = sharedPreferences.getString(KEY_COMPANY_NAME, null);

        Log.d(TAG, "Dados da empresa carregados - ID: " + currentCompanyId + ", Nome: " + currentCompanyName);
    }

    public void setCurrentCompany(String companyId, String companyName) {
        this.currentCompanyId = companyId;
        this.currentCompanyName = companyName;

        sharedPreferences.edit()
                .putString(KEY_COMPANY_ID, companyId)
                .putString(KEY_COMPANY_NAME, companyName)
                .apply();

        Log.d(TAG, "Empresa definida - ID: " + companyId + ", Nome: " + companyName);
    }

    public String getCurrentCompanyId() {
        return currentCompanyId;
    }

    public String getCurrentCompanyName() {
        return currentCompanyName;
    }

    public boolean hasCompanySet() {
        return currentCompanyId != null && !currentCompanyId.trim().isEmpty();
    }

    public void clearCompanyData() {
        currentCompanyId = null;
        currentCompanyName = null;

        sharedPreferences.edit()
                .remove(KEY_COMPANY_ID)
                .remove(KEY_COMPANY_NAME)
                .apply();

        Log.d(TAG, "Dados da empresa removidos");
    }
}