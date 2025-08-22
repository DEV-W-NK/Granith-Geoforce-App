package com.example.granith;

import android.content.IntentFilter;
import android.os.BatteryManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.Priority;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.QuerySnapshot;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LocationForegroundService extends Service implements DeviceStateReceiver.DeviceStateListener {
    private static final String TAG = "LocationForegroundService";

    // === CONSTANTES CONSOLIDADAS ===
    private static final String CHANNEL_ID = "location_service_channel";
    private static final long SYNC_INTERVAL_MS = 5 * 60 * 1000; // 5 minutos
    private static final long STALE_CHECK_INTERVAL = 30 * 60 * 1000; // 30 minutos
    private static final long DUPLICATE_WINDOW_MS = 2 * 60 * 1000; // 2 minutos
    private static final long AUTO_EXIT_THRESHOLD_MS = 24 * 60 * 60 * 1000; // 24 horas
    private static final long EVENT_CLEANUP_AGE_MS = 7 * 24 * 60 * 60 * 1000; // 7 dias
    private String currentCompanyId;
    private CompanyService companyService;
    // Dentro da classe LocationForegroundService
    private boolean lowBatteryDetected = false;
    private boolean criticalBatteryDetected = false;
    private int lastBatteryLevel = -1;
    private DeviceStateReceiver deviceStateReceiver;


    // Parâmetros de confirmação consolidados
    private static final class ConfirmationParams {
        static final int REQUIRED_UPDATES_ONLINE = 3;
        static final long MIN_INTERVAL_ONLINE_MS = 2 * 60 * 1000; // 20 minutos
        static final int REQUIRED_UPDATES_OFFLINE = 3;
        static final long MIN_INTERVAL_OFFLINE_MS = 2 * 60 * 1000; // 20 minutos
    }

    // === ESTADO DO SERVIÇO ===
    private final Map<String, Long> entryFirstTimestamp = new HashMap<>();
    private final Map<String, Long> exitFirstTimestamp = new HashMap<>();
    private final Map<String, Integer> entryUpdateCounter = new HashMap<>();
    private final Map<String, Integer> exitUpdateCounter = new HashMap<>();
    private final Map<String, Boolean> geofenceEntryState = new HashMap<>();

    // === COMPONENTES ===
    private List<GeofenceData> geofenceList;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private LocationRequest locationRequest;
    private PowerManager.WakeLock wakeLock;
    private FirebaseFirestore firestore;
    private SharedPreferences sharedPreferences;

    // === HANDLERS E RECEIVERS ===
    private final Handler syncHandler = new Handler();
    private final Handler updateHandler = new Handler();
    private final Handler exitCheckHandler = new Handler();
    private NetworkReceiver networkReceiver;
    private GpsStatusReceiver gpsStatusReceiver;

    // === ESTADO DE PRECISÃO ===
    private enum PrecisionState {HIGH, LOW, VERY_LOW}

    private PrecisionState currentPrecisionState = null;

    // === RUNNABLES ===
    private final Runnable syncRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                syncOfflineEventsToFirebase();
            } catch (Exception e) {
                Log.e(TAG, "Erro durante sincronização", e);
            } finally {
                syncHandler.postDelayed(this, SYNC_INTERVAL_MS);
            }
        }
    };

    private final Runnable updateGeofencesRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                fetchGeofencesFromFirestore();
            } catch (Exception e) {
                Log.e(TAG, "Erro ao atualizar geofences", e);
            } finally {
                updateHandler.postDelayed(this, 24 * 60 * 60 * 1000);
            }
        }
    };

    private final Runnable exitCheckRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                checkForStaleEntries();
            } catch (Exception e) {
                Log.e(TAG, "Erro na verificação de entradas obsoletas", e);
            } finally {
                exitCheckHandler.postDelayed(this, STALE_CHECK_INTERVAL);
            }
        }
    };


    @Override
    public void onCreate() {
        super.onCreate();
        loadDeviceState();
        registerDeviceStateReceiver();

        // ADICIONE ESTAS LINHAS:
        companyService = CompanyService.getInstance(this);
        currentCompanyId = companyService.getCurrentCompanyId();

        if (currentCompanyId == null) {
            Log.e(TAG, "CompanyId não definido - parando serviço");
            stopSelf();
            return;
        }

        Log.d(TAG, "Serviço iniciado para empresa: " + currentCompanyId);

        try {
            initializeService();
        } catch (Exception e) {
            Log.e(TAG, "Erro fatal na inicialização do serviço", e);
            stopSelf();
        }
    }

    private void initializeService() {
        // Inicializa WakeLock
        deviceStateReceiver.setDeviceStateListener(this);
        initializeWakeLock();

        // Inicializa componentes básicos
        sharedPreferences = getSharedPreferences("GeofencesPrefs", MODE_PRIVATE);
        firestore = FirebaseFirestore.getInstance();
        geofenceList = new ArrayList<>();

        // Carrega estado anterior
        loadLastGeofenceEvent();

        // Configura notificação em primeiro plano
        createNotificationChannel();
        startForeground(1, createNotification());

        // Inicializa cliente de localização
        fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this);

        // Carrega dados e inicia serviços
        loadGeofencesFromLocal();
        startGeofenceUpdateCycle();
        listenForGeofenceChanges();

        // Registra receivers
        registerBroadcastReceivers();

        // Configura localização
        setupLocationTracking();

        //Status Inicial do GPS
        recordInitialGpsStatus();

        // Inicia handlers periódicos
        startPeriodicTasks();
    }

    private void initializeWakeLock() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationService::WakeLock");
            wakeLock.acquire(10 * 60 * 1000L); // 10 minutos timeout para segurança
        }
    }

    private void loadDeviceState() {
        try {
            if (sharedPreferences == null) {
                sharedPreferences = getSharedPreferences("device_state_prefs", Context.MODE_PRIVATE);
            }

            // Load battery state
            lowBatteryDetected = sharedPreferences.getBoolean("low_battery_detected", false);
            criticalBatteryDetected = sharedPreferences.getBoolean("critical_battery_detected", false);
            lastBatteryLevel = sharedPreferences.getInt("last_battery_level", -1);

            Log.d(TAG, "Device state loaded - Low battery: " + lowBatteryDetected +
                    ", Critical battery: " + criticalBatteryDetected +
                    ", Last battery level: " + lastBatteryLevel);

        } catch (Exception e) {
            Log.e(TAG, "Error loading device state", e);
        }
    }

    private void registerDeviceStateReceiver() {
        try {
            if (deviceStateReceiver == null) {
                deviceStateReceiver = new DeviceStateReceiver();
                deviceStateReceiver.setDeviceStateListener(this);
            }

            // Create intent filter for battery and device state changes
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_BATTERY_LOW);
            filter.addAction(Intent.ACTION_BATTERY_OKAY);
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);

            // Register the receiver
            registerReceiver(deviceStateReceiver, filter);

            Log.d(TAG, "DeviceStateReceiver registered successfully");

        } catch (Exception e) {
            Log.e(TAG, "Error registering DeviceStateReceiver", e);
        }
    }

    private void registerBroadcastReceivers() {
        try {
            networkReceiver = new NetworkReceiver();
            gpsStatusReceiver = new GpsStatusReceiver();

            IntentFilter networkFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
            registerReceiver(networkReceiver, networkFilter);

            IntentFilter gpsFilter = new IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION);
            registerReceiver(gpsStatusReceiver, gpsFilter);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao registrar BroadcastReceivers", e);
        }
    }

    private void setupLocationTracking() {
        locationRequest = createLocationRequest(true);
        currentPrecisionState = PrecisionState.HIGH;

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                try {
                    for (Location location : locationResult.getLocations()) {
                        Log.d(TAG, "Localização: " + location.getLatitude() + ", " + location.getLongitude());
                        adjustLocationRequestBasedOnProximity(location);
                        checkGeofence(location);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Erro ao processar resultado de localização", e);
                }
            }
        };

        startLocationUpdates();
    }

    private void startPeriodicTasks() {
        syncHandler.postDelayed(syncRunnable, SYNC_INTERVAL_MS);
        exitCheckHandler.postDelayed(exitCheckRunnable, STALE_CHECK_INTERVAL);

        // Cleanup de eventos antigos
        Handler cleanupHandler = new Handler();
        cleanupHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    cleanOldOfflineEvents();
                } catch (Exception e) {
                    Log.e(TAG, "Erro na limpeza de eventos", e);
                } finally {
                    cleanupHandler.postDelayed(this, 24 * 60 * 60 * 1000);
                }
            }
        }, 60 * 60 * 1000);
    }

    // === VERIFICAÇÃO DE GEOFENCES MELHORADA ===
    private void checkGeofence(Location location) {
        if (location == null || geofenceList.isEmpty()) return;

        long currentTime = System.currentTimeMillis();
        SharedPreferences prefs = getSharedPreferences("GeofenceStatePrefs", MODE_PRIVATE);
        String lastGeofenceName = prefs.getString("last_geofence_name", null);
        String lastEventType = prefs.getString("last_event_type", null);

        for (GeofenceData geofence : geofenceList) {
            try {
                float distance = calculateDistance(location, geofence);
                boolean currentlyInside = distance < geofence.getRadius();
                boolean wasInside = Boolean.TRUE.equals(geofenceEntryState.getOrDefault(geofence.getName(), false));

                if (currentlyInside && !wasInside) {
                    handleGeofenceTransition(location, geofence, currentTime, true, lastGeofenceName, lastEventType);
                } else if (!currentlyInside && wasInside) {
                    handleGeofenceTransition(location, geofence, currentTime, false, null, null);
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro ao verificar geofence: " + geofence.getName(), e);
            }
        }
    }

    // === MÉTODO CONSOLIDADO para transições de geofence ===
    private void handleGeofenceTransition(Location location, GeofenceData geofence, long currentTime,
                                          boolean isEntry, String lastGeofenceName, String lastEventType) {

        boolean offline = !isNetworkAvailable();

        // Parâmetros baseados no modo e tipo de evento
        int requiredUpdates = offline ? ConfirmationParams.REQUIRED_UPDATES_OFFLINE : ConfirmationParams.REQUIRED_UPDATES_ONLINE;
        long minInterval = offline ? ConfirmationParams.MIN_INTERVAL_OFFLINE_MS : ConfirmationParams.MIN_INTERVAL_ONLINE_MS;

        Map<String, Long> timestampMap = isEntry ? entryFirstTimestamp : exitFirstTimestamp;
        Map<String, Integer> counterMap = isEntry ? entryUpdateCounter : exitUpdateCounter;

        String geofenceName = geofence.getName();

        // Inicializa timestamp se necessário
        if (!timestampMap.containsKey(geofenceName)) {
            timestampMap.put(geofenceName, currentTime);
        }

        // Incrementa contador
        int counter = counterMap.getOrDefault(geofenceName, 0) + 1;
        counterMap.put(geofenceName, counter);

        // Verifica se deve confirmar o evento
        if (counter >= requiredUpdates) {
            long firstTimestamp = timestampMap.get(geofenceName);
            if (currentTime - firstTimestamp >= minInterval) {
                confirmGeofenceEvent(location, geofence, isEntry, lastGeofenceName, lastEventType);
                clearCountersForGeofence(geofenceName);
            }
        }
    }

    private void confirmGeofenceEvent(Location location, GeofenceData geofence, boolean isEntry,
                                      String lastGeofenceName, String lastEventType) {
        String eventType = isEntry ? "Entrada Confirmada" : "Saída Confirmada";
        String geofenceName = geofence.getName();

        if (isEntry) {
            // Força saída da geofence anterior se necessário
            if (lastGeofenceName != null && !lastGeofenceName.equals(geofenceName)
                    && "Entrada Confirmada".equals(lastEventType)) {

                GeofenceData lastGeofence = findGeofenceByName(lastGeofenceName);
                if (lastGeofence != null) {
                    geofenceEntryState.put(lastGeofence.getName(), false);
                    generateGeofenceEvent(location, lastGeofence, "Saída Confirmada");
                    clearCountersForGeofence(lastGeofence.getName());
                }
            }
            geofenceEntryState.put(geofenceName, true);
        } else {
            geofenceEntryState.put(geofenceName, false);
        }

        generateGeofenceEvent(location, geofence, eventType);
        saveLastGeofenceEvent(geofenceName, eventType);
    }

    public void clearCountersForGeofence(String geofenceName) {
        entryUpdateCounter.remove(geofenceName);
        exitUpdateCounter.remove(geofenceName);
        entryFirstTimestamp.remove(geofenceName);
        exitFirstTimestamp.remove(geofenceName);
    }

    // === GERAÇÃO DE EVENTOS MELHORADA ===
    private void generateGeofenceEvent(Location location, GeofenceData geofence, String eventType) {
        if (geofence == null) {
            Log.e(TAG, "Dados da geofence são nulos");
            return;
        }

        // Cria localização fictícia para saídas automáticas
        if (location == null && eventType.equals("Saída Automática")) {
            location = createFakeLocation(geofence);
        }

        if (location == null) {
            Log.e(TAG, "Localização é nula para evento: " + eventType);
            return;
        }

        String userName = UserPreferences.loadUserName(getSharedPreferences("MyAppPrefs", MODE_PRIVATE));

        try {
            if (isNetworkAvailable()) {
                sendEventToFirestoreWithDuplicateCheck(location, geofence, eventType, userName);
            } else {
                storeEventLocally(eventType, location, geofence);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao gerar evento de geofence", e);
            // Fallback para armazenamento local
            storeEventLocally(eventType, location, geofence);
        }
    }

    private Location createFakeLocation(GeofenceData geofence) {
        Location fakeLocation = new Location("automatic");
        fakeLocation.setLatitude(geofence.getLatitude());
        fakeLocation.setLongitude(geofence.getLongitude());
        return fakeLocation;
    }

    // === SINCRONIZAÇÃO MELHORADA ===
    public void syncOfflineEventsToFirebase() {
        if (!isNetworkAvailable()) {
            Log.d(TAG, "Rede não disponível para sincronização");
            return;
        }

        // Sincroniza eventos de geofence (código existente)
        SharedPreferences prefs = getSharedPreferences("MyAppPrefs", MODE_PRIVATE);
        String offlineEventsString = prefs.getString("offline_events", null);

        if (offlineEventsString != null && !offlineEventsString.trim().isEmpty()) {
            try {
                JSONArray offlineEventsArray = new JSONArray(offlineEventsString);
                if (offlineEventsArray.length() > 0) {
                    Log.d(TAG, "Iniciando sincronização de " + offlineEventsArray.length() + " eventos de geofence");
                    syncEventByEvent(offlineEventsArray, 0, prefs);
                }
            } catch (JSONException e) {
                Log.e(TAG, "Erro ao processar eventos offline", e);
            }
        }

        // ADICIONE ESTA LINHA: Sincroniza eventos de GPS
        syncOfflineGpsEventsToFirebase();
    }

    @Override
    public void onBatteryStateChanged(int batteryLevel, boolean isLow, boolean isCritical) {

    }

    private void recordInitialGpsStatus() {
        try {
            LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locationManager != null) {
                boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
                String statusMessage = gpsEnabled ? "GPS habilitado (serviço iniciado)" : "GPS desabilitado (serviço iniciado)";

                Log.d(TAG, "Status inicial do GPS: " + statusMessage);
                recordGpsStatusEvent(statusMessage, gpsEnabled);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao verificar status inicial do GPS", e);
        }
    }

    private void syncEventByEvent(JSONArray eventsArray, int index, SharedPreferences prefs) {
        if (index >= eventsArray.length()) {
            Log.d(TAG, "Sincronização concluída. Limpando eventos offline");
            prefs.edit().remove("offline_events").apply();
            return;
        }

        try {
            JSONObject eventJson = eventsArray.getJSONObject(index);
            checkAndInsertEvent(eventJson, eventsArray, index, prefs);
        } catch (JSONException e) {
            Log.e(TAG, "Erro ao processar evento no índice " + index, e);
            syncEventByEvent(eventsArray, index + 1, prefs);
        }
    }

    private void checkAndInsertEvent(JSONObject eventJson, JSONArray eventsArray, int index, SharedPreferences prefs) {
        try {
            String geofenceName = eventJson.getString("geofence_name");
            String eventType = eventJson.getString("event_type");
            String userName = eventJson.getString("user_name");
            long timestamp = eventJson.getLong("timestamp");

            long startTime = timestamp - DUPLICATE_WINDOW_MS;
            long endTime = timestamp + DUPLICATE_WINDOW_MS;

            firestore.collection("geofence_records")
                    .whereEqualTo("geofence_name", geofenceName)
                    .whereEqualTo("event_type", eventType)
                    .whereEqualTo("user_name", userName)
                    .whereGreaterThanOrEqualTo("timestamp", startTime)
                    .whereLessThanOrEqualTo("timestamp", endTime)
                    .get()
                    .addOnCompleteListener(task -> {
                        try {
                            if (task.isSuccessful() && task.getResult() != null && task.getResult().isEmpty()) {
                                insertEventToFirestore(eventJson, eventsArray, index, prefs);
                            } else {
                                Log.d(TAG, "Evento duplicado ignorado: " + eventType + " - " + geofenceName);
                                syncEventByEvent(eventsArray, index + 1, prefs);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Erro no processamento da verificação de duplicata", e);
                            syncEventByEvent(eventsArray, index + 1, prefs);
                        }
                    });
        } catch (JSONException e) {
            Log.e(TAG, "Erro ao extrair dados do evento", e);
            syncEventByEvent(eventsArray, index + 1, prefs);
        }
    }

    // === ARMAZENAMENTO LOCAL MELHORADO ===
    private void storeEventLocally(String eventType, Location location, GeofenceData geofence) {
        SharedPreferences prefs = getSharedPreferences("MyAppPrefs", MODE_PRIVATE);

        try {
            JSONArray offlineEventsArray = getOfflineEventsArray(prefs);

            if (isDuplicateEvent(offlineEventsArray, eventType, geofence, prefs)) {
                Log.d(TAG, "Evento duplicado local ignorado: " + eventType + " - " + geofence.getName());
                return;
            }

            JSONObject eventJson = createEventJson(eventType, location, geofence, prefs);
            // ADICIONE ESTA LINHA:
            eventJson.put("companyId", currentCompanyId);

            offlineEventsArray.put(eventJson);

            prefs.edit().putString("offline_events", offlineEventsArray.toString()).apply();
            Log.d(TAG, "Evento armazenado offline para empresa " + currentCompanyId + ": " + eventType + " - " + geofence.getName());

        } catch (JSONException e) {
            Log.e(TAG, "Erro ao armazenar evento localmente", e);
        }
    }

    private JSONArray getOfflineEventsArray(SharedPreferences prefs) throws JSONException {
        String offlineEventsString = prefs.getString("offline_events", null);
        return offlineEventsString != null ? new JSONArray(offlineEventsString) : new JSONArray();
    }

    private boolean isDuplicateEvent(JSONArray eventsArray, String eventType, GeofenceData geofence, SharedPreferences prefs) throws JSONException {
        long currentTime = System.currentTimeMillis();
        String userName = UserPreferences.loadUserName(prefs);
        String eventKey = geofence.getName() + "_" + eventType + "_" + userName;

        for (int i = 0; i < eventsArray.length(); i++) {
            JSONObject existingEvent = eventsArray.getJSONObject(i);
            String existingKey = existingEvent.getString("geofence_name") + "_" +
                    existingEvent.getString("event_type") + "_" +
                    existingEvent.getString("user_name");

            if (existingKey.equals(eventKey) &&
                    Math.abs(currentTime - existingEvent.getLong("timestamp")) < DUPLICATE_WINDOW_MS) {
                return true;
            }
        }
        return false;
    }

    private JSONObject createEventJson(String eventType, Location location, GeofenceData geofence, SharedPreferences prefs) throws JSONException {
        long currentTime = System.currentTimeMillis();
        String userName = UserPreferences.loadUserName(prefs);

        JSONObject eventJson = new JSONObject();
        eventJson.put("event_type", eventType);
        eventJson.put("latitude", location.getLatitude());
        eventJson.put("longitude", location.getLongitude());
        eventJson.put("geofence_name", geofence.getName());
        eventJson.put("geofence_code", geofence.getCodigoObra());
        eventJson.put("timestamp", currentTime);
        eventJson.put("user_name", userName);
        eventJson.put("local_id", geofence.getName() + "_" + eventType + "_" + userName + "_" + currentTime);

        return eventJson;
    }

    // === MÉTODOS DE LIMPEZA E UTILITÁRIOS ===
    private void cleanOldOfflineEvents() {
        SharedPreferences prefs = getSharedPreferences("MyAppPrefs", MODE_PRIVATE);
        String offlineEventsString = prefs.getString("offline_events", null);

        if (offlineEventsString == null) return;

        try {
            JSONArray offlineEventsArray = new JSONArray(offlineEventsString);
            JSONArray cleanedArray = new JSONArray();
            long currentTime = System.currentTimeMillis();

            for (int i = 0; i < offlineEventsArray.length(); i++) {
                JSONObject event = offlineEventsArray.getJSONObject(i);
                long eventTime = event.getLong("timestamp");

                if (currentTime - eventTime < EVENT_CLEANUP_AGE_MS) {
                    cleanedArray.put(event);
                }
            }

            if (cleanedArray.length() != offlineEventsArray.length()) {
                prefs.edit().putString("offline_events", cleanedArray.toString()).apply();
                Log.d(TAG, "Removidos " + (offlineEventsArray.length() - cleanedArray.length()) + " eventos antigos");
            }

        } catch (JSONException e) {
            Log.e(TAG, "Erro ao limpar eventos antigos", e);
        }
    }


    private void checkForStaleEntries() {
        long currentTime = System.currentTimeMillis();
        SharedPreferences prefs = getSharedPreferences("GeofenceStatePrefs", MODE_PRIVATE);

        for (String geofenceName : new ArrayList<>(geofenceEntryState.keySet())) {
            if (Boolean.TRUE.equals(geofenceEntryState.get(geofenceName))) {
                GeofenceData geofence = findGeofenceByName(geofenceName);
                if (geofence != null) {
                    long lastEntryTime = prefs.getLong("last_event_timestamp", 0);
                    if (currentTime - lastEntryTime > AUTO_EXIT_THRESHOLD_MS) {
                        generateGeofenceEvent(null, geofence, "Saída Automática");
                        geofenceEntryState.put(geofenceName, false);
                        saveLastGeofenceEvent(geofenceName, "Saída Automática");
                        clearCountersForGeofence(geofenceName);
                    }
                }
            }
        }
    }

    @Override
    public Map<String, Boolean> getGeofenceEntryState() {
        return Collections.emptyMap();
    }

    // === MÉTODOS AUXILIARES CONSOLIDADOS ===
    public GeofenceData findGeofenceByName(String name) {
        return geofenceList.stream()
                .filter(geofence -> geofence.getName().equals(name))
                .findFirst()
                .orElse(null);

    }

    public boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;

            Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork == null) return false;

            NetworkCapabilities capabilities = cm.getNetworkCapabilities(activeNetwork);
            return capabilities != null &&
                    (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
        } catch (Exception e) {
            Log.e(TAG, "Erro ao verificar conectividade", e);
            return false;
        }
    }

    private float calculateDistance(Location location, GeofenceData geofence) {
        float[] results = new float[1];
        Location.distanceBetween(location.getLatitude(), location.getLongitude(),
                geofence.getLatitude(), geofence.getLongitude(), results);
        return results[0];
    }

    // === PRECISÃO DE LOCALIZAÇÃO ===
    private void adjustLocationRequestBasedOnProximity(Location location) {
        PrecisionState desiredPrecision = calculateDesiredPrecision(location);

        if (currentPrecisionState != desiredPrecision) {
            currentPrecisionState = desiredPrecision;
            locationRequest = createLocationRequestForPrecision(desiredPrecision);
            Log.d(TAG, "Mudando precisão para: " + desiredPrecision);
            restartLocationUpdates();
        }
    }

    private PrecisionState calculateDesiredPrecision(Location location) {
        boolean isInsideGeofence = false;
        boolean isCloseToGeofence = false;
        boolean isVeryFarFromAll = true;

        for (GeofenceData geofence : geofenceList) {
            float distance = calculateDistance(location, geofence);

            if (distance < geofence.getRadius()) {
                isInsideGeofence = true;
                break;
            } else if (distance < geofence.getRadius() + 10) {
                isCloseToGeofence = true;
            }

            if (distance < geofence.getRadius() + 400) {
                isVeryFarFromAll = false;
            }
        }

        if (isInsideGeofence) {
            return PrecisionState.LOW;
        } else if (isCloseToGeofence) {
            return PrecisionState.HIGH;
        } else if (isVeryFarFromAll) {
            return PrecisionState.VERY_LOW;
        } else {
            return PrecisionState.LOW;
        }
    }

    private LocationRequest createLocationRequestForPrecision(PrecisionState precision) {
        switch (precision) {
            case HIGH:
                return createLocationRequest(true);
            case VERY_LOW:
                return createLowPrecisionLocationRequest();
            default:
                return createLocationRequest(false);
        }
    }

    // === MÉTODOS DE LOCALIZAÇÃO ===
    private LocationRequest createLocationRequest(boolean highAccuracy) {
        long minInterval = highAccuracy ? 60 * 1000 : 2 * 60 * 1000;
        long maxDelay = highAccuracy ? 5 * 60 * 1000 : 10 * 60 * 1000;
        int priority = highAccuracy ? Priority.PRIORITY_HIGH_ACCURACY : Priority.PRIORITY_BALANCED_POWER_ACCURACY;

        return new LocationRequest.Builder(priority)
                .setMinUpdateIntervalMillis(minInterval)
                .setMaxUpdateDelayMillis(maxDelay)
                .build();
    }

    private LocationRequest createLowPrecisionLocationRequest() {
        return new LocationRequest.Builder(Priority.PRIORITY_LOW_POWER)
                .setMinUpdateIntervalMillis(2 * 60 * 1000)
                .setMaxUpdateDelayMillis(5 * 60 * 1000)
                .build();
    }

    private void startLocationUpdates() {
        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null);
        } catch (SecurityException e) {
            Log.e(TAG, "Permissão de localização não concedida", e);
        }
    }

    private void restartLocationUpdates() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null);
        } catch (SecurityException e) {
            Log.e(TAG, "Erro ao reiniciar atualizações de localização", e);
        }
    }

    // 3. SUBSTITUA O MÉTODO fetchGeofencesFromFirestore()
    private void fetchGeofencesFromFirestore() {
        if (currentCompanyId == null) {
            Log.e(TAG, "CompanyId não definido para buscar geofences");
            return;
        }

        Log.d(TAG, "Buscando geofences da empresa: " + currentCompanyId);

        firestore.collection("companies")
                .document(currentCompanyId)
                .collection("geofences")
                .whereEqualTo("active", true)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        processGeofencesFromFirestore(task.getResult());
                    } else {
                        Log.e(TAG, "Erro ao buscar geofences da empresa", task.getException());
                    }
                });
    }

    private void processGeofencesFromFirestore(QuerySnapshot querySnapshot) {
        List<GeofenceData> newGeofenceList = new ArrayList<>();

        for (QueryDocumentSnapshot document : querySnapshot) {
            try {
                GeofenceData geofence = GeofenceData.fromDocument(document);
                if (geofence != null) {
                    newGeofenceList.add(geofence);
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro ao processar documento de geofence", e);
            }
        }

        if (!newGeofenceList.isEmpty()) {
            geofenceList = newGeofenceList;
            saveGeofencesToLocal();
            Log.d(TAG, "Carregadas " + geofenceList.size() + " geofences do Firestore");
        }
    }

    private void listenForGeofenceChanges() {
        if (currentCompanyId == null) return;

        firestore.collection("companies")
                .document(currentCompanyId)
                .collection("geofences")
                .whereEqualTo("active", true)
                .addSnapshotListener((snapshots, e) -> {
                    if (e != null) {
                        Log.w(TAG, "Erro ao escutar mudanças nas geofences da empresa", e);
                        return;
                    }

                    if (snapshots != null && !snapshots.isEmpty()) {
                        Log.d(TAG, "Detectada mudança nas geofences da empresa: " + currentCompanyId);
                        processGeofencesFromFirestore(snapshots);
                    }
                });
    }

    private void sendEventToFirestoreWithDuplicateCheck(Location location, GeofenceData geofence,
                                                        String eventType, String userName) {
        if (currentCompanyId == null) {
            Log.e(TAG, "CompanyId não definido - armazenando localmente");
            storeEventLocally(eventType, location, geofence);
            return;
        }

        long currentTime = System.currentTimeMillis();
        long startTime = currentTime - DUPLICATE_WINDOW_MS;
        long endTime = currentTime + DUPLICATE_WINDOW_MS;

        firestore.collection("companies")
                .document(currentCompanyId)
                .collection("geofence_events")
                .whereEqualTo("geofenceName", geofence.getName())
                .whereEqualTo("eventType", convertEventType(eventType))
                .whereEqualTo("employeeName", userName)
                .whereGreaterThanOrEqualTo("timestamp", startTime)
                .whereLessThanOrEqualTo("timestamp", endTime)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        if (task.getResult().isEmpty()) {
                            sendEventToFirestore(location, geofence, eventType, userName, currentTime);
                        } else {
                            Log.d(TAG, "Evento duplicado ignorado na empresa " + currentCompanyId + ": " + eventType + " - " + geofence.getName());
                        }
                    } else {
                        Log.e(TAG, "Erro na verificação de duplicata da empresa", task.getException());
                        // Fallback para armazenamento local
                        storeEventLocally(eventType, location, geofence);
                    }
                });
    }

    private String convertEventType(String eventType) {
        switch (eventType) {
            case "Entrada Confirmada":
                return "entry";
            case "Saída Confirmada":
                return "exit";
            case "Saída Automática":
                return "auto_exit";
            default:
                return "unknown";
        }
    }
    private String getEmployeeId(String userName) {
        // Implementar busca do employeeId baseado no userName
        // Por enquanto, retorna null - você pode implementar cache local ou busca
        return null;
    }


    private void sendEventToFirestore(Location location, GeofenceData geofence, String eventType,
                                      String userName, long timestamp) {
        if (currentCompanyId == null) {
            Log.e(TAG, "CompanyId não definido - armazenando localmente");
            storeEventLocally(eventType, location, geofence);
            return;
        }

        // Buscar employeeId se disponível (implementar se necessário)
        String employeeId = getEmployeeId(userName); // Você precisa implementar este método

        Map<String, Object> geofenceRecord = new HashMap<>();
        geofenceRecord.put("employeeId", employeeId);
        geofenceRecord.put("employeeName", userName);
        geofenceRecord.put("geofenceName", geofence.getName());
        geofenceRecord.put("geofenceCode", geofence.getCodigoObra());
        geofenceRecord.put("eventType", convertEventType(eventType));
        geofenceRecord.put("latitude", location.getLatitude());
        geofenceRecord.put("longitude", location.getLongitude());
        geofenceRecord.put("timestamp", timestamp);
        geofenceRecord.put("deviceInfo", Build.MODEL + " - " + Build.MANUFACTURER);
        geofenceRecord.put("accuracy", location.getAccuracy());
        geofenceRecord.put("isOfflineSync", false);

        firestore.collection("companies")
                .document(currentCompanyId)
                .collection("geofence_events")
                .add(geofenceRecord)
                .addOnSuccessListener(documentReference -> {
                    Log.d(TAG, "Evento enviado com sucesso para empresa " + currentCompanyId + ": " + eventType + " - " + geofence.getName());
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Erro ao enviar evento para Firestore da empresa", e);
                    storeEventLocally(eventType, location, geofence);
                });
    }


    private void insertEventToFirestore(JSONObject eventJson, JSONArray eventsArray, int index,
                                        SharedPreferences prefs) {
        try {
            // Verifica se o evento tem companyId, se não, usa o atual
            String eventCompanyId = eventJson.optString("companyId", currentCompanyId);

            if (eventCompanyId == null) {
                Log.e(TAG, "CompanyId não encontrado para evento offline");
                syncEventByEvent(eventsArray, index + 1, prefs);
                return;
            }

            String employeeId = eventJson.optString("employeeId", null);

            Map<String, Object> geofenceRecord = new HashMap<>();
            geofenceRecord.put("employeeId", employeeId);
            geofenceRecord.put("employeeName", eventJson.getString("user_name"));
            geofenceRecord.put("geofenceId", eventJson.optString("geofence_id", null));
            geofenceRecord.put("geofenceName", eventJson.getString("geofence_name"));
            geofenceRecord.put("geofenceCode", eventJson.getString("geofence_code"));
            geofenceRecord.put("eventType", convertEventType(eventJson.getString("event_type")));
            geofenceRecord.put("latitude", eventJson.getDouble("latitude"));
            geofenceRecord.put("longitude", eventJson.getDouble("longitude"));
            geofenceRecord.put("timestamp", eventJson.getLong("timestamp"));
            geofenceRecord.put("deviceInfo", eventJson.optString("device_info", Build.MODEL + " - " + Build.MANUFACTURER));
            geofenceRecord.put("accuracy", eventJson.optDouble("accuracy", 0.0));
            geofenceRecord.put("isOfflineSync", true);

            firestore.collection("companies")
                    .document(eventCompanyId)
                    .collection("geofence_events")
                    .add(geofenceRecord)
                    .addOnSuccessListener(documentReference -> {
                        Log.d(TAG, "Evento offline sincronizado para empresa " + eventCompanyId + ": " + eventJson.optString("event_type"));
                        syncEventByEvent(eventsArray, index + 1, prefs);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Erro ao sincronizar evento da empresa", e);
                        syncEventByEvent(eventsArray, index + 1, prefs);
                    });
        } catch (JSONException e) {
            Log.e(TAG, "Erro ao converter evento para Firestore da empresa", e);
            syncEventByEvent(eventsArray, index + 1, prefs);
        }
    }

    // === ARMAZENAMENTO LOCAL DE GEOFENCES ===
    private void loadGeofencesFromLocal() {
        String geofencesJson = sharedPreferences.getString("geofences_data", null);
        if (geofencesJson != null) {
            try {
                JSONArray jsonArray = new JSONArray(geofencesJson);
                geofenceList = new ArrayList<>();

                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject jsonObject = jsonArray.getJSONObject(i);
                    GeofenceData geofence = GeofenceData.fromJson(jsonObject);
                    if (geofence != null) {
                        geofenceList.add(geofence);
                    }
                }

                Log.d(TAG, "Carregadas " + geofenceList.size() + " geofences do armazenamento local");
            } catch (JSONException e) {
                Log.e(TAG, "Erro ao carregar geofences locais", e);
                geofenceList = new ArrayList<>();
            }
        } else {
            geofenceList = new ArrayList<>();
        }
    }

    private void saveGeofencesToLocal() {
        try {
            JSONArray jsonArray = new JSONArray();
            for (GeofenceData geofence : geofenceList) {
                jsonArray.put(geofence.toJson());
            }

            sharedPreferences.edit()
                    .putString("geofences_data", jsonArray.toString())
                    .apply();

            Log.d(TAG, "Geofences salvas localmente");
        } catch (JSONException e) {
            Log.e(TAG, "Erro ao salvar geofences localmente", e);
        }
    }

    private void startGeofenceUpdateCycle() {
        if (isNetworkAvailable()) {
            fetchGeofencesFromFirestore();
        }
        updateHandler.postDelayed(updateGeofencesRunnable, 24 * 60 * 60 * 1000);
    }

    // === ESTADO DAS GEOFENCES ===
    private void saveLastGeofenceEvent(String geofenceName, String eventType) {
        SharedPreferences prefs = getSharedPreferences("GeofenceStatePrefs", MODE_PRIVATE);
        prefs.edit()
                .putString("last_geofence_name", geofenceName)
                .putString("last_event_type", eventType)
                .putLong("last_event_timestamp", System.currentTimeMillis())
                .apply();
    }

    private void loadLastGeofenceEvent() {
        SharedPreferences prefs = getSharedPreferences("GeofenceStatePrefs", MODE_PRIVATE);
        String lastGeofenceName = prefs.getString("last_geofence_name", null);
        String lastEventType = prefs.getString("last_event_type", null);

        if (lastGeofenceName != null && "Entrada Confirmada".equals(lastEventType)) {
            geofenceEntryState.put(lastGeofenceName, true);
            Log.d(TAG, "Estado anterior carregado: dentro de " + lastGeofenceName);
        }
    }

    // === NOTIFICAÇÃO ===
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Serviço de Localização",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Monitoramento contínuo de localização");

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation) // ÍCONE ADICIONADO
                .setContentTitle("Monitoramento de Localização")
                .setContentText("Serviço ativo em segundo plano")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    // === BROADCAST RECEIVERS ===
    private class NetworkReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
                boolean isConnected = isNetworkAvailable();
                Log.d(TAG, "Mudança de conectividade: " + (isConnected ? "Conectado" : "Desconectado"));

                if (isConnected) {
                    syncHandler.post(syncRunnable);
                }
            }
        }
    }

    private class GpsStatusReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (LocationManager.PROVIDERS_CHANGED_ACTION.equals(intent.getAction())) {
                try {
                    LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                    if (locationManager != null) {
                        boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
                        String statusMessage = gpsEnabled ? "GPS habilitado" : "GPS desabilitado";

                        Log.d(TAG, "Status GPS mudou: " + statusMessage);

                        // Registra o evento de GPS no Firestore
                        recordGpsStatusEvent(statusMessage, gpsEnabled);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Erro ao processar mudança de GPS", e);
                }
            }
        }
    }
    private Map<String, Object> createGpsStatusEvent(String message, boolean gpsEnabled) {
        SharedPreferences prefs = getSharedPreferences("MyAppPrefs", MODE_PRIVATE);
        String userName = UserPreferences.loadUserName(prefs);
        String employeeId = getEmployeeId(userName);

        Map<String, Object> gpsStatusEvent = new HashMap<>();
        gpsStatusEvent.put("employeeId", employeeId);
        gpsStatusEvent.put("employeeName", userName);
        gpsStatusEvent.put("eventType", gpsEnabled ? "gps_enabled" : "gps_disabled");
        gpsStatusEvent.put("message", message);
        gpsStatusEvent.put("timestamp", System.currentTimeMillis());
        gpsStatusEvent.put("deviceInfo", Build.MODEL + " - " + Build.MANUFACTURER + " - Android " + Build.VERSION.RELEASE);
        gpsStatusEvent.put("gpsEnabled", gpsEnabled);

        return gpsStatusEvent;
    }

    // Método para registrar eventos de GPS no Firestore
    private void recordGpsStatusEvent(String message, boolean gpsEnabled) {
        if (currentCompanyId == null) {
            Log.e(TAG, "CompanyId não definido para evento GPS - armazenando localmente");
            Map<String, Object> gpsStatusEvent = createGpsStatusEvent(message, gpsEnabled);
            storeGpsEventLocally(gpsStatusEvent);
            return;
        }

        try {
            SharedPreferences prefs = getSharedPreferences("MyAppPrefs", MODE_PRIVATE);
            String userName = UserPreferences.loadUserName(prefs);
            String employeeId = getEmployeeId(userName);

            Map<String, Object> gpsStatusEvent = new HashMap<>();
            gpsStatusEvent.put("employeeId", employeeId);
            gpsStatusEvent.put("employeeName", userName);
            gpsStatusEvent.put("eventType", gpsEnabled ? "gps_enabled" : "gps_disabled");
            gpsStatusEvent.put("message", message);
            gpsStatusEvent.put("timestamp", System.currentTimeMillis());
            gpsStatusEvent.put("deviceInfo", Build.MODEL + " - " + Build.MANUFACTURER + " - Android " + Build.VERSION.RELEASE);
            gpsStatusEvent.put("gpsEnabled", gpsEnabled);

            if (isNetworkAvailable()) {
                firestore.collection("companies")
                        .document(currentCompanyId)
                        .collection("system_events")
                        .add(gpsStatusEvent)
                        .addOnSuccessListener(documentReference -> {
                            Log.d(TAG, "Evento GPS registrado para empresa " + currentCompanyId + ": " + message);
                        })
                        .addOnFailureListener(e -> {
                            Log.e(TAG, "Erro ao registrar evento GPS da empresa", e);
                            storeGpsEventLocally(gpsStatusEvent);
                        });
            } else {
                storeGpsEventLocally(gpsStatusEvent);
            }

        } catch (Exception e) {
            Log.e(TAG, "Erro ao criar evento GPS da empresa", e);
        }
    }

    // Método para armazenar eventos de GPS localmente
    private void storeGpsEventLocally(Map<String, Object> gpsStatusEvent) {
        try {
            SharedPreferences prefs = getSharedPreferences("MyAppPrefs", MODE_PRIVATE);
            String offlineGpsEventsString = prefs.getString("offline_gps_events", null);

            JSONArray offlineGpsEventsArray;
            if (offlineGpsEventsString != null) {
                offlineGpsEventsArray = new JSONArray(offlineGpsEventsString);
            } else {
                offlineGpsEventsArray = new JSONArray();
            }

            // Converte o Map para JSONObject
            JSONObject gpsEventJson = new JSONObject();
            for (Map.Entry<String, Object> entry : gpsStatusEvent.entrySet()) {
                gpsEventJson.put(entry.getKey(), entry.getValue());
            }

            // Adiciona um ID local único
            gpsEventJson.put("local_id", "gps_" + System.currentTimeMillis());

            offlineGpsEventsArray.put(gpsEventJson);

            // Salva no SharedPreferences
            prefs.edit().putString("offline_gps_events", offlineGpsEventsArray.toString()).apply();

            Log.d(TAG, "Evento GPS armazenado offline: " + gpsStatusEvent.get("message"));

        } catch (Exception e) {
            Log.e(TAG, "Erro ao armazenar evento GPS localmente", e);
        }
    }

    // Método para sincronizar eventos de GPS offline
    private void syncOfflineGpsEventsToFirebase() {
        if (!isNetworkAvailable()) {
            Log.d(TAG, "Rede não disponível para sincronização de eventos GPS");
            return;
        }

        SharedPreferences prefs = getSharedPreferences("MyAppPrefs", MODE_PRIVATE);
        String offlineGpsEventsString = prefs.getString("offline_gps_events", null);

        if (offlineGpsEventsString == null || offlineGpsEventsString.trim().isEmpty()) {
            return;
        }

        try {
            JSONArray offlineGpsEventsArray = new JSONArray(offlineGpsEventsString);
            if (offlineGpsEventsArray.length() == 0) {
                return;
            }

            Log.d(TAG, "Iniciando sincronização de " + offlineGpsEventsArray.length() + " eventos GPS");
            syncGpsEventByEvent(offlineGpsEventsArray, 0, prefs);

        } catch (JSONException e) {
            Log.e(TAG, "Erro ao processar eventos GPS offline", e);
        }
    }

    // Método para sincronizar eventos GPS um por vez
    private void syncGpsEventByEvent(JSONArray eventsArray, int index, SharedPreferences prefs) {
        if (index >= eventsArray.length()) {
            Log.d(TAG, "Sincronização de eventos GPS concluída. Limpando eventos offline");
            prefs.edit().remove("offline_gps_events").apply();
            return;
        }

        try {
            JSONObject eventJson = eventsArray.getJSONObject(index);

            // Converte JSONObject para Map
            Map<String, Object> gpsStatusEvent = new HashMap<>();
            gpsStatusEvent.put("message", eventJson.getString("message"));
            gpsStatusEvent.put("timestamp", eventJson.getLong("timestamp"));
            gpsStatusEvent.put("user_name", eventJson.getString("user_name"));
            gpsStatusEvent.put("gps_enabled", eventJson.getBoolean("gps_enabled"));

            if (eventJson.has("device_info")) {
                gpsStatusEvent.put("device_info", eventJson.getString("device_info"));
            }

            firestore.collection("gps_status")
                    .add(gpsStatusEvent)
                    .addOnSuccessListener(documentReference -> {
                        Log.d(TAG, "Evento GPS offline sincronizado: " + eventJson.optString("message"));
                        syncGpsEventByEvent(eventsArray, index + 1, prefs);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Erro ao sincronizar evento GPS", e);
                        syncGpsEventByEvent(eventsArray, index + 1, prefs);
                    });

        } catch (JSONException e) {
            Log.e(TAG, "Erro ao processar evento GPS no índice " + index, e);
            syncGpsEventByEvent(eventsArray, index + 1, prefs);
        }
    }


    // === CICLO DE VIDA DO SERVIÇO ===
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand chamado");
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Serviço sendo destruído");

        try {
            // Para handlers
            syncHandler.removeCallbacks(syncRunnable);
            updateHandler.removeCallbacks(updateGeofencesRunnable);
            exitCheckHandler.removeCallbacks(exitCheckRunnable);

            // Para atualizações de localização
            if (fusedLocationClient != null && locationCallback != null) {
                fusedLocationClient.removeLocationUpdates(locationCallback);
            }

            // Desregistra receivers
            if (networkReceiver != null) {
                unregisterReceiver(networkReceiver);
            }
            if (gpsStatusReceiver != null) {
                unregisterReceiver(gpsStatusReceiver);
            }

            // Libera WakeLock
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }

        } catch (Exception e) {
            Log.e(TAG, "Erro durante destruição do serviço", e);
        }
        if (deviceStateReceiver != null) {
            try {
                unregisterReceiver(deviceStateReceiver);
            } catch (Exception e) {
                Log.e(TAG, "Erro ao desregistrar receiver", e);
            }
        }

        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "Task removida - reiniciando serviço");

        Intent restartServiceIntent = new Intent(getApplicationContext(), this.getClass());
        restartServiceIntent.setPackage(getPackageName());

        PendingIntent restartServicePendingIntent = PendingIntent.getService(
                getApplicationContext(), 1, restartServiceIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT
        );

        android.app.AlarmManager alarmService = (android.app.AlarmManager) getApplicationContext()
                .getSystemService(Context.ALARM_SERVICE);

        if (alarmService != null) {
            alarmService.set(
                    android.app.AlarmManager.ELAPSED_REALTIME,
                    android.os.SystemClock.elapsedRealtime() + 1000,
                    restartServicePendingIntent
            );
        }

        super.onTaskRemoved(rootIntent);
    }
}