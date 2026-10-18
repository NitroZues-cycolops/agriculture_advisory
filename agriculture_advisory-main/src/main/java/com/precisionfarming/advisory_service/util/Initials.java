package com.precisionfarming.advisory_service.util;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * PART 2a: ONE shared helper for the two things a profile picture needs when a
 * farmer has not uploaded one - the initials to draw and a background colour.
 *
 * Both the server-rendered SVG avatar (AvatarController) and the CSS-initials
 * circle in a template (via the Spring bean reference {@code ${@initials...}})
 * use this class, so the fallback looks the same everywhere.
 *
 * The methods never throw and never return null: an empty or unknown name still
 * renders a usable avatar (the "?" placeholder).
 */
@Component("initials")
public class Initials {

    /**
     * A small palette of colours that read well with white text on the dark UI.
     * A name always maps to the SAME entry, so a farmer keeps one colour.
     */
    private static final String[] PALETTE = {
            "#19d3b0", "#2f7fd8", "#8b5cf6", "#e0724a",
            "#d9a534", "#3fae6b", "#d95f8a", "#5a8fa8"
    };

    /** The placeholder shown when there is no usable character in the name. */
    public static final String PLACEHOLDER = "?";

    /**
     * Up to two initials for a display name: the first character of the first
     * word and the first character of the last word ("Ramesh Patil" -> "RP").
     * A single word gives one letter ("Ramesh" -> "R").
     *
     * Handles null, blank, extra/multiple spaces and characters OUTSIDE the BMP
     * (emoji, rare scripts) by walking CODE POINTS, never UTF-16 chars - so a
     * surrogate pair is never cut in half. An empty result becomes "?".
     */
    public String of(String name) {
        if (name == null) {
            return PLACEHOLDER;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return PLACEHOLDER;
        }
        String[] words = trimmed.split("\\s+");
        StringBuilder sb = new StringBuilder();
        int count = 0;
        count += appendFirstCodePoint(sb, words[0]);
        if (words.length > 1) {
            count += appendFirstCodePoint(sb, words[words.length - 1]);
        }
        if (count == 0) {
            return PLACEHOLDER;
        }
        return sb.toString().toUpperCase(Locale.ROOT);
    }

    /**
     * A deterministic background colour for a name (same name -> same colour).
     * Null or blank returns the first palette entry, so the caller always gets a
     * usable value.
     */
    public String colorFor(String name) {
        if (name == null || name.isBlank()) {
            return PALETTE[0];
        }
        int hash = 0;
        for (int cp : name.codePoints().toArray()) {
            hash = 31 * hash + cp;
        }
        return PALETTE[Math.floorMod(hash, PALETTE.length)];
    }

    /** Appends the first code point of the word; returns 1 when one was added. */
    private static int appendFirstCodePoint(StringBuilder sb, String word) {
        if (word == null || word.isEmpty()) {
            return 0;
        }
        int first = word.codePointAt(0);
        if (Character.isWhitespace(first)) {
            return 0;
        }
        sb.appendCodePoint(first);
        return 1;
    }
}
