package com.precisionfarming.advisory_service.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One row of the `weather_forecast` table - a single day of the 7-day outlook.
 *
 * WHY ONE ROW PER DAY: the greenalerts.in block page publishes each day as its
 * own article (`article.ga-day#day-YYYY-MM-DD`), so the scraper can replace
 * one day without rewriting a whole serialised string. The unique key
 * (district, taluka, forecast_day) makes that an in-place update.
 *
 * Values are TEXT for the same reason as WeatherReading: the source publishes
 * "33.5 °C" and "0 mm", Open-Meteo publishes "19 %".
 */
public class WeatherDay {

    private LocalDate day;
    private String tempMin;
    private String tempMax;
    private String rain;
    private String humidity;
    private String wind;
    private String summary;
    private String source;
    private LocalDateTime updatedAt;

    public WeatherDay() {
    }

    public WeatherDay(LocalDate day, String tempMin, String tempMax, String rain,
                      String humidity, String wind, String summary, String source) {
        this.day = day;
        this.tempMin = tempMin;
        this.tempMax = tempMax;
        this.rain = rain;
        this.humidity = humidity;
        this.wind = wind;
        this.summary = summary;
        this.source = source;
    }

    public LocalDate getDay() { return day; }
    public void setDay(LocalDate day) { this.day = day; }

    public String getTempMin() { return tempMin; }
    public void setTempMin(String tempMin) { this.tempMin = tempMin; }

    public String getTempMax() { return tempMax; }
    public void setTempMax(String tempMax) { this.tempMax = tempMax; }

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

    /** True when this day came from the seeded SAMPLE rows, not from a real source. */
    public boolean isSample() {
        return "SAMPLE".equalsIgnoreCase(source);
    }

    /** Highest temperature of the day as a whole number, or null when unparsable. */
    public Integer tempMaxNumber() {
        return WeatherReading.numberIn(tempMax);
    }
}
