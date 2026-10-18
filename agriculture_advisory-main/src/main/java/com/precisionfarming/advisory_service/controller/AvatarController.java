package com.precisionfarming.advisory_service.controller;

import com.precisionfarming.advisory_service.model.FarmerProfile;
import com.precisionfarming.advisory_service.model.User;
import com.precisionfarming.advisory_service.repository.FarmerProfileDao;
import com.precisionfarming.advisory_service.repository.UserRepository;
import com.precisionfarming.advisory_service.util.Initials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;

/**
 * The profile picture of any account (PART 2a).
 *
 *   GET /avatar/{userId}
 *
 * Role: any authenticated user. SecurityConfig's anyRequest().authenticated()
 * already covers /avatar/**, so an anonymous request is redirected to login.
 *
 * Behaviour:
 *   - the farmer has a stored picture -> the 256x256 JPEG is streamed back.
 *   - there is NO picture (or the file has gone), or the name is blank -> a
 *     small server-rendered SVG avatar (initials over a colour derived from the
 *     name) with HTTP 200.
 *
 * A blank or unknown name NEVER becomes a 500: the SVG falls back to the "?"
 * placeholder. Nothing here needs JavaScript.
 */
@Controller
public class AvatarController {

    private static final Logger log = LoggerFactory.getLogger(AvatarController.class);

    /** Side of the generated SVG fallback, in CSS pixels. */
    private static final int SVG_SIZE = 128;

    private final FarmerProfileDao profiles;
    private final UserRepository users;
    private final Initials initials;

    public AvatarController(FarmerProfileDao profiles, UserRepository users, Initials initials) {
        this.profiles = profiles;
        this.users = users;
        this.initials = initials;
    }

    /**
     * URL  : GET /avatar/{userId}
     * Role : authenticated
     * Serves the stored JPEG, else a server-rendered SVG initials avatar.
     */
    @GetMapping("/avatar/{userId}")
    public ResponseEntity<byte[]> avatar(@PathVariable Long userId) {
        Optional<FarmerProfile> profile = profiles.findByUserId(userId);

        String name = profile.map(FarmerProfile::getFullName).filter(n -> n != null && !n.isBlank())
                .orElseGet(() -> users.findById(userId).map(User::getUsername).orElse(null));

        String storedPath = profile.map(FarmerProfile::getPhotoPath)
                .filter(p -> p != null && !p.isBlank())
                .orElse(null);
        if (storedPath != null) {
            File file = new File(storedPath);
            if (file.exists() && file.isFile()) {
                try {
                    byte[] bytes = Files.readAllBytes(file.toPath());
                    return ResponseEntity.ok()
                            .contentType(MediaType.IMAGE_JPEG)
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    ContentDisposition.inline().build().toString())
                            .body(bytes);
                } catch (IOException e) {
                    // A broken file on disk must not 500 the page: fall through to
                    // the initials avatar instead.
                    log.info("[AVATAR] could not read stored picture for user {}: {}",
                            userId, e.getMessage());
                }
            }
        }
        return svg(name);
    }

    /** A tiny inline SVG: two initials centred on a colour from the name. */
    private ResponseEntity<byte[]> svg(String name) {
        String initialsText = initials.of(name);
        String color = initials.colorFor(name);
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + SVG_SIZE
                + "\" height=\"" + SVG_SIZE + "\" viewBox=\"0 0 " + SVG_SIZE + " " + SVG_SIZE
                + "\" role=\"img\">"
                + "<rect width=\"" + SVG_SIZE + "\" height=\"" + SVG_SIZE + "\" fill=\"" + color + "\"/>"
                + "<text x=\"50%\" y=\"50%\" dy=\".35em\" text-anchor=\"middle\""
                + " font-family=\"system-ui, sans-serif\" font-size=\"" + (SVG_SIZE / 2)
                + "\" font-weight=\"700\" fill=\"#ffffff\">" + escapeXml(initialsText) + "</text>"
                + "</svg>";
        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.valueOf("image/svg+xml"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().build().toString())
                .body(svg.getBytes(StandardCharsets.UTF_8));
    }

    /** Escapes the few characters that would break an XML document. */
    private static String escapeXml(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
