package com.precisionfarming.advisory_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application entry point.
 *
 * PURPOSE : start the Spring Boot context for the Precision Farming Advisory Service.
 * ROUTE   : n/a (this is the main class).
 *
 * WHY @EnableScheduling: STEP 4's WeatherService refreshes weather_cache every
 * 3 hours with @Scheduled. Without this annotation the annotation on the method
 * would simply be ignored and the cache would never be refreshed.
 *
 * If this breaks, check:
 *   - Nothing is ever scraped -> @EnableScheduling is missing here.
 *   - Port 8080 already in use -> an older run of this app is still alive
 *     (Ctrl+C it, or start with --server.port=8082).
 */
@SpringBootApplication
@EnableScheduling
public class AdvisoryServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(AdvisoryServiceApplication.class, args);
	}

}
