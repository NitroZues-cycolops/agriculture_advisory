package com.precisionfarming.advisory_service.model;

import java.time.LocalDateTime;

/**
 * One row of the `weather_cache` table - the current conditions for one place.
 *
 * WHY THIS CLASS EXISTS
 * The weather pages never call the internet. A @Scheduled job writes readings
 * here and every page reads only from this table, so the demo keeps working
 * when the college wifi does not.
 *
 * The temperature / rain / humidity / wind fields are stored as TEXT because
 * the two sources use different units ("33.5 °C" and "0 mm" from
 * greenalerts.in, "31.7 °C" and "19 %" from Open-Meteo). Storing the number
 * together with its unit keeps the UI honest: it can never show a percentage
 * where the source actually published millimetres.
 *
 * `isSample` marks the seeded demo rows. They are shown with a SAMPLE badge so
 * nobody mistakes them for a real observation.
 *
 * This is a plain carrier, not a JPA entity - it is read with JdbcTemplate.
 */
public class WeatherReading {

    private Long id;
    private String state;
    private String district;
    private String taluka;
    private String temperature;
    private String rain;
    private String humidity;
    private String wind;
    private String summary;
    private String source;
    private LocalDateTime updatedAt;
    private boolean sample;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getDistrict() { return district; }
    public void setDistrict(String district) { this.district = district; }

    public String getTaluka() { return taluka; }
    public void setTaluka(String taluka) { this.taluka = taluka; }

    public String getTemperature() { return temperature; }
    public void setTemperature(String temperature) { this.temperature = temperature; }

    public String getRain() { return rain; }
    public void setRain(String rain) { this.rain = rain; }

    public String getHumidity() { return humidity; }
    public void setHumidity(String humidity) { this.humidity = humidity; }

    public String getWind() { return wind; }
    public void setWind(String wind) { this.wind = wind; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public boolean isSample() { return sample; }
    public void setSample(boolean sample) { this.sample = sample; }

    /**
     * The first whole number found in a text value, or null when there is none.
     *
     * Used by the crop rules and by the map to turn "33.5 °C" into 33. Keeping
     * it here means the parsing rule exists in exactly one place.
     */
    public static Integer numberIn(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("-?\\d+(?:\\.\\d+)?")
                .matcher(text);
        if (!m.find()) {
            return null;
        }
        return (int) Math.round(Double.parseDouble(m.group()));
    }

    /** Temperature in whole degrees Celsius, or null when the text has no number. */
    public Integer tempCelsius() {
        return numberIn(temperature);
    }

    /** Rain amount / chance as a whole number, or null when the text has no number. */
    public Integer rainNumber() {
        return numberIn(rain);
    }

    /** Wind speed as a whole number, or null when the text has no number. */
    public Integer windNumber() {
        return numberIn(wind);
    }
}
