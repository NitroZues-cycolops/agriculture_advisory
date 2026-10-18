package com.precisionfarming.advisory_service.controller;

import com.precisionfarming.advisory_service.model.FarmerProfile;
import com.precisionfarming.advisory_service.model.Member;
import com.precisionfarming.advisory_service.repository.CommunityDao;
import com.precisionfarming.advisory_service.repository.FarmerProfileDao;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.io.File;

@Controller
public class AvatarController {

    private static final List<String> COLORS =
            List.of("#176b57", "#8b4a26", "#365f9d", "#7b3f66", "#55742f", "#965d13");

    private final FarmerProfileDao profiles;
    private final CommunityDao community;
    private final String uploadDir;

    public AvatarController(FarmerProfileDao profiles, CommunityDao community,
                            @Value("${pfas.uploads.dir:uploads}") String uploadDir) {
        this.profiles = profiles;
        this.community = community;
        this.uploadDir = uploadDir;
    }

    @GetMapping("/avatar/{userId}")
    public ResponseEntity<byte[]> avatar(@PathVariable Long userId, Authentication auth) {
        Member member = community.findMember(userId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND));
        FarmerProfile profile = profiles.findByUserId(userId).orElse(null);
        if (profile != null && profile.getPhotoPath() != null && !profile.getPhotoPath().isBlank()) {
            try {
                Path avatarRoot = new File(uploadDir, "avatars").getAbsoluteFile().toPath().normalize();
                Path photo = Path.of(profile.getPhotoPath()).toAbsolutePath().normalize();
                if (photo.startsWith(avatarRoot) && Files.isRegularFile(photo)) {
                    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                            .contentType(MediaType.IMAGE_JPEG).body(Files.readAllBytes(photo));
                }
            } catch (IOException | RuntimeException ex) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND);
            }
        }
        String initials = initials(member.displayName());
        String color = COLORS.get(Math.floorMod(member.displayName().hashCode(), COLORS.size()));
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 96 96\">"
                + "<rect width=\"96\" height=\"96\" rx=\"48\" fill=\"" + color + "\"/>"
                + "<text x=\"48\" y=\"55\" text-anchor=\"middle\" dominant-baseline=\"middle\" "
                + "font-family=\"sans-serif\" font-size=\"34\" fill=\"#ffffff\">"
                + xml(initials) + "</text></svg>";
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .contentType(MediaType.parseMediaType("image/svg+xml;charset=UTF-8"))
                .body(svg.getBytes(StandardCharsets.UTF_8));
    }

    private static String initials(String name) {
        String[] words = name.trim().split("\\s+");
        if (words.length == 1) {
            return words[0].substring(0, 1).toUpperCase();
        }
        return (words[0].substring(0, 1) + words[words.length - 1].substring(0, 1)).toUpperCase();
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}