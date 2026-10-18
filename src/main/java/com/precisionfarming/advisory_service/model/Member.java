package com.precisionfarming.advisory_service.model;

/**
 * A farmer as shown in the community: the login name plus whatever the farmer
 * saved in their profile. `hasProfile` tells the page whether to draw the farm
 * details or the "no profile yet" note.
 *
 * A record keeps this read-only carrier short enough to read aloud in the viva.
 */
public record Member(
        Long userId,
        String username,
        String fullName,
        String district,
        String taluka,
        String village,
        String crops,
        double farmArea,
        String unit,
        boolean hasProfile,
        String photoPath) {

    /** The name to print: the saved full name, else the username. */
    public String displayName() {
        return fullName != null && !fullName.isBlank() ? fullName : username;
    }

    public String initials() {
        String[] words = displayName().trim().split("\\s+");
        if (words.length == 1) {
            return words[0].substring(0, 1).toUpperCase();
        }
        return (words[0].substring(0, 1) + words[words.length - 1].substring(0, 1)).toUpperCase();
    }

    public String avatarColor() {
        String[] colors = {"#176b57", "#8b4a26", "#365f9d", "#7b3f66", "#55742f", "#965d13"};
        return colors[Math.floorMod(displayName().hashCode(), colors.length)];
    }
}
