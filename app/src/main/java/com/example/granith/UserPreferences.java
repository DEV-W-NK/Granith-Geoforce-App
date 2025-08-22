package com.example.granith;

import android.content.SharedPreferences;

public class UserPreferences {

    private static final String USER_NAME_KEY = "USER_NAME";
    private static final String USER_DEPARTMENT_KEY = "USER_DEPARTMENT"; // Nova chave para o departamento

    // Método para salvar o nome e o departamento do usuário nas SharedPreferences
    public static void saveUserDetails(SharedPreferences sharedPreferences, String userName, String userDepartment) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(USER_NAME_KEY, userName);
        editor.putString(USER_DEPARTMENT_KEY, userDepartment); // Salva o departamento também
        editor.apply();
    }

    // Método para carregar o nome do usuário das SharedPreferences
    public static String loadUserName(SharedPreferences sharedPreferences) {
        return sharedPreferences.getString(USER_NAME_KEY, null); // Retorna null caso o nome não exista
    }

    // Método para carregar o departamento do usuário das SharedPreferences
    public static String loadUserDepartment(SharedPreferences sharedPreferences) {
        return sharedPreferences.getString(USER_DEPARTMENT_KEY, null); // Retorna null caso o departamento não exista
    }

    //Método para verificar se o nome e o departamento estão configurados
    public static boolean areUserDetailsConfigured(SharedPreferences sharedPreferences) {
        String userName = loadUserName(sharedPreferences);
        String userDepartment = loadUserDepartment(sharedPreferences);
        return userName != null && userDepartment != null; // Verifica se ambos os campos estão preenchidos
    }
}
