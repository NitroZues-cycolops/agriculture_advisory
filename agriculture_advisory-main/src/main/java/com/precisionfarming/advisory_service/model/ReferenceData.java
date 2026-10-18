package com.precisionfarming.advisory_service.model;

import java.util.List;

/**
 * The fixed option lists used by the registration and profile forms.
 *
 * Each entry is a CODE. The label shown to the user is a message key such as
 * "crop.rice" or "soil.black", so every language gets a real translation and
 * nothing is hard-coded in a template.
 *
 * Why codes and not free text: the DB stores codes ("rice,onion"), the UI
 * translates them, and a typo in the DB cannot invent a new crop.
 */
public final class ReferenceData {

    private ReferenceData() {
        // constants only
    }

    /** Crops a farmer can tick, in the order they appear on the form. */
    public static final List<String> CROPS = List.of(
            "rice", "wheat", "sugarcane", "cotton", "soybean", "jowar",
            "bajra", "onion", "grapes", "pomegranate", "mango", "other");

    /** Soil types offered in the dropdown. */
    public static final List<String> SOILS = List.of(
            "black", "red", "alluvial", "laterite", "sandy", "loamy", "clay", "other");

    /** Irrigation methods offered in the dropdown. */
    public static final List<String> IRRIGATION = List.of(
            "drip", "sprinkler", "flood", "rainfed", "canal", "well", "other");

    /** Farm area units. */
    public static final List<String> UNITS = List.of("acre", "hectare");

    /** Preferred languages the farmer can pick at registration. */
    public static final List<String> LANGS = List.of("en", "hi", "mr");

    /** Sample types a lab can accept / a farmer can request. */
    public static final List<String> SAMPLE_TYPES = List.of(
            "soil", "water", "seed", "fertilizer", "plant", "other");
}
