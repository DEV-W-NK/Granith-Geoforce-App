package com.example.granith;

import android.content.SharedPreferences;
import android.location.Location;
import android.os.Build;
import com.google.firebase.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class GeofenceEvent {
    private String eventType; // Ex.: "Entrada Confirmada"
    private double longitude;
    private Timestamp timestamp; // Usando Timestamp do Firebase
    private String userName; // Nome do usuário ou modelo do dispositivo
    private String geofenceCode; // Código da geofence
    private String geofenceName; // Nome da geofence
    private boolean isSynced; // Indica se o evento foi sincronizado com o Firebase

    // Construtor
    public GeofenceEvent(String eventType, Location location, String userName, String geofenceCode, String geofenceName) {
        this.eventType = eventType;
        this.longitude = location.getLongitude();
        this.timestamp = new Timestamp(new Date());

        // Se o userName for null ou vazio, então use o modelo do dispositivo
        this.userName = userName;

        this.geofenceCode = geofenceCode;
        this.geofenceName = geofenceName;
        this.isSynced = false;
    }

    // Getters
    public String getEventType() {
        return eventType;
    }

    public String getUserName() {
        return userName;
    }

    // Método para carregar o nome do usuário das SharedPreferences
    public static String loadUserName(SharedPreferences sharedPreferences) {
        return sharedPreferences.getString("USER_NAME", null);
    }
}
