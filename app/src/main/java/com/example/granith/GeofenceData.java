package com.example.granith;

import android.util.Log;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import org.json.JSONException;
import org.json.JSONObject;

public class GeofenceData {
    private static final String TAG = "GeofenceData";

    private String codigo;
    private String name;
    private double latitude;
    private double longitude;
    private float radius;

    // Construtores
    public GeofenceData(double latitude, double longitude, float radius, String name, String codigo) {
        this.codigo = codigo;
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.radius = radius;
    }

    public GeofenceData() {
    }

    // === MÉTODOS DE CONVERSÃO ===

    /**
     * Cria uma instância de GeofenceData a partir de um documento do Firestore
     */
    public static GeofenceData fromDocument(QueryDocumentSnapshot document) {
        try {
            Log.d(TAG, "Processando documento: " + document.getId());
            debugDocument(document); // Método separado para debug

            // Obtém os dados do documento considerando campos alternativos
            String name = getStringFromDocument(document, "name", "NomeObra");
            String codigo = getStringFromDocument(document, "codigo", "CodObra");

            // Verifica se os campos obrigatórios existem
            if (name == null || codigo == null) {
                Log.w(TAG, "Documento com campos obrigatórios nulos: " + document.getId());
                return null;
            }

            // Obtém coordenadas com validação melhorada
            double latitude = getDoubleFromDocument(document, "latitude", "Latitude");
            double longitude = getDoubleFromDocument(document, "longitude", "Longitude");
            float radius = getFloatFromDocument(document, "radius", "Metros");

            // Validação mais específica de coordenadas
            if (!areValidCoordinates(latitude, longitude)) {
                Log.w(TAG, String.format("Coordenadas inválidas para geofence %s: lat=%.6f, lng=%.6f",
                        name, latitude, longitude));
                return null;
            }

            if (radius <= 0) {
                Log.w(TAG, String.format("Raio inválido para geofence %s: %.1f - usando padrão 100m",
                        name, radius));
                radius = 100.0f; // Raio padrão
            }

            GeofenceData geofence = new GeofenceData(latitude, longitude, radius, name, codigo);

            // Log de sucesso usando as variáveis locais
            Log.d(TAG, String.format("✅ Geofence criada: %s - Lat:%.6f, Lng:%.6f, Radius:%.1f",
                    name, latitude, longitude, radius));

            return geofence;

        } catch (Exception e) {
            Log.e(TAG, "Erro ao converter documento para GeofenceData: " + document.getId(), e);
            return null;
        }
    }

    /**
     * Método para debug detalhado de documentos
     */
    private static void debugDocument(QueryDocumentSnapshot document) {
        Log.d(TAG, "=== DEBUG DOCUMENTO ===");
        Log.d(TAG, "ID: " + document.getId());
        Log.d(TAG, "Dados completos: " + document.getData());

        // Debug específico dos campos críticos
        Object latObj = document.get("Latitude");
        Object lngObj = document.get("Longitude");
        Object metrosObj = document.get("Metros");

        Log.d(TAG, String.format("Latitude: %s (tipo: %s)",
                latObj, latObj != null ? latObj.getClass().getSimpleName() : "null"));
        Log.d(TAG, String.format("Longitude: %s (tipo: %s)",
                lngObj, lngObj != null ? lngObj.getClass().getSimpleName() : "null"));
        Log.d(TAG, String.format("Metros: %s (tipo: %s)",
                metrosObj, metrosObj != null ? metrosObj.getClass().getSimpleName() : "null"));
        Log.d(TAG, "========================");
    }

    /**
     * Validação mais robusta de coordenadas
     */
    private static boolean areValidCoordinates(double lat, double lng) {
        // Verifica se são números válidos
        if (Double.isNaN(lat) || Double.isNaN(lng) || Double.isInfinite(lat) || Double.isInfinite(lng)) {
            return false;
        }

        // Verifica se estão dentro dos limites geográficos válidos
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            return false;
        }

        // Verifica se não são exatamente (0,0) - geralmente indica dados inválidos
        // Mas permite coordenadas próximas a zero
        return Math.abs(lat) > 0.0001 || Math.abs(lng) > 0.0001;
    }

    private static String getStringFromDocument(QueryDocumentSnapshot document, String... possibleFields) {
        for (String field : possibleFields) {
            String value = document.getString(field);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * Método melhorado para obter doubles do documento
     */
    private static double getDoubleFromDocument(QueryDocumentSnapshot document, String... possibleFields) {
        for (String field : possibleFields) {
            try {
                Object value = document.get(field);
                if (value == null) continue;

                // Se for Number (Long, Double, Integer, etc.)
                if (value instanceof Number) {
                    double doubleValue = ((Number) value).doubleValue();
                    if (doubleValue != 0.0) { // Só retorna se não for zero
                        Log.d(TAG, String.format("Campo %s: %.6f (Number)", field, doubleValue));
                        return doubleValue;
                    }
                }

                // Se for String
                if (value instanceof String) {
                    String stringValue = ((String) value).trim();
                    if (!stringValue.isEmpty()) {
                        try {
                            double doubleValue = Double.parseDouble(stringValue);
                            if (doubleValue != 0.0) { // Só retorna se não for zero
                                Log.d(TAG, String.format("Campo %s: %.6f (String convertida)", field, doubleValue));
                                return doubleValue;
                            }
                        } catch (NumberFormatException e) {
                            Log.w(TAG, String.format("Não foi possível converter string '%s' para double no campo %s",
                                    stringValue, field));
                        }
                    }
                }

                Log.d(TAG, String.format("Campo %s ignorado: %s (tipo: %s)",
                        field, value, value.getClass().getSimpleName()));

            } catch (Exception e) {
                Log.w(TAG, String.format("Erro ao obter double do campo %s: %s", field, e.getMessage()));
            }
        }

        Log.w(TAG, String.format("Nenhum valor válido encontrado para os campos: %s",
                java.util.Arrays.toString(possibleFields)));
        return 0.0;
    }

    /**
     * Método melhorado para obter floats do documento
     */
    private static float getFloatFromDocument(QueryDocumentSnapshot document, String... possibleFields) {
        for (String field : possibleFields) {
            try {
                Object value = document.get(field);
                if (value == null) continue;

                // Se for Number
                if (value instanceof Number) {
                    float floatValue = ((Number) value).floatValue();
                    if (floatValue > 0) { // Só aceita valores positivos para raio
                        Log.d(TAG, String.format("Campo %s: %.1f (Number)", field, floatValue));
                        return floatValue;
                    }
                }

                // Se for String
                if (value instanceof String) {
                    String stringValue = ((String) value).trim();
                    if (!stringValue.isEmpty()) {
                        try {
                            float floatValue = Float.parseFloat(stringValue);
                            if (floatValue > 0) { // Só aceita valores positivos para raio
                                Log.d(TAG, String.format("Campo %s: %.1f (String convertida)", field, floatValue));
                                return floatValue;
                            }
                        } catch (NumberFormatException e) {
                            Log.w(TAG, String.format("Não foi possível converter string '%s' para float no campo %s",
                                    stringValue, field));
                        }
                    }
                }

            } catch (Exception e) {
                Log.w(TAG, String.format("Erro ao obter float do campo %s: %s", field, e.getMessage()));
            }
        }

        Log.d(TAG, String.format("Usando valor padrão 100.0 para raio (campos testados: %s)",
                java.util.Arrays.toString(possibleFields)));
        return 100.0f; // Valor padrão
    }

    /**
     * Cria uma instância de GeofenceData a partir de um JSONObject
     */
    public static GeofenceData fromJson(JSONObject jsonObject) {
        try {
            String name = jsonObject.getString("name");
            String codigo = jsonObject.getString("codigo");
            double latitude = jsonObject.getDouble("latitude");
            double longitude = jsonObject.getDouble("longitude");
            float radius = (float) jsonObject.getDouble("radius");

            return new GeofenceData(latitude, longitude, radius, name, codigo);

        } catch (JSONException e) {
            Log.e(TAG, "Erro ao converter JSON para GeofenceData", e);
            return null;
        }
    }

    /**
     * Converte a instância atual para JSONObject
     */
    public JSONObject toJson() throws JSONException {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("name", name);
        jsonObject.put("codigo", codigo);
        jsonObject.put("latitude", latitude);
        jsonObject.put("longitude", longitude);
        jsonObject.put("radius", radius);
        return jsonObject;
    }

    // === GETTERS E SETTERS ===

    public String getCodigoObra() {
        return codigo;
    }

    public void setCodigoObra(String codigo) {
        this.codigo = codigo;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getLatitude() {
        return latitude;
    }

    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    public float getRadius() {
        return radius;
    }

    public void setRadius(float radius) {
        this.radius = radius;
    }

    // === MÉTODOS UTILITÁRIOS ===

    @Override
    public String toString() {
        return String.format("GeofenceData{codigo='%s', name='%s', lat=%.6f, lng=%.6f, radius=%.1f}",
                codigo, name, latitude, longitude, radius);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        GeofenceData that = (GeofenceData) obj;
        return Double.compare(that.latitude, latitude) == 0 &&
                Double.compare(that.longitude, longitude) == 0 &&
                Float.compare(that.radius, radius) == 0 &&
                java.util.Objects.equals(codigo, that.codigo) &&
                java.util.Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(codigo, name, latitude, longitude, radius);
    }

    /**
     * Verifica se a geofence é válida
     */
    public boolean isValid() {
        return name != null && !name.trim().isEmpty() &&
                codigo != null && !codigo.trim().isEmpty() &&
                areValidCoordinates(latitude, longitude) &&
                radius > 0;
    }

    /**
     * Método para debug da instância atual
     */
    public void logDetails() {
        Log.d(TAG, String.format("=== DETALHES DA GEOFENCE ==="));
        Log.d(TAG, String.format("Código: %s", codigo));
        Log.d(TAG, String.format("Nome: %s", name));
        Log.d(TAG, String.format("Latitude: %.6f", latitude));
        Log.d(TAG, String.format("Longitude: %.6f", longitude));
        Log.d(TAG, String.format("Raio: %.1f metros", radius));
        Log.d(TAG, String.format("Válida: %s", isValid()));
        Log.d(TAG, "==============================");
    }
}