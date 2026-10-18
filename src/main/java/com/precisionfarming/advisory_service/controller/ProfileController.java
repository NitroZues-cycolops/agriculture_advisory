package com.precisionfarming.advisory_service.controller;

import com.precisionfarming.advisory_service.model.FarmerProfile;
import com.precisionfarming.advisory_service.model.ReferenceData;
import com.precisionfarming.advisory_service.model.User;
import com.precisionfarming.advisory_service.repository.DistrictDao;
import com.precisionfarming.advisory_service.repository.CommunityDao;
import com.precisionfarming.advisory_service.repository.FarmerProfileDao;
import com.precisionfarming.advisory_service.repository.UserRepository;
import com.precisionfarming.advisory_service.service.EmailVerificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.imageio.ImageIO;

/**
 * The farmer's own profile (workspace 7) - STEP 2.
 *
 * OWNERSHIP: every read and write is scoped to the logged-in user's id, taken
 * from the security context, never from the URL. A farmer can therefore only
 * ever see and edit their own row.
 *
 * Role handling: /profile is for farmers. A lab account is sent to its own
 * dashboard and the owner to /debug, so no top-bar link can ever 403 here.
 */
@Controller
public class ProfileController {

    private static final Logger log = LoggerFactory.getLogger(ProfileController.class);

    private final UserRepository users;
    private final FarmerProfileDao profiles;
    private final DistrictDao districts;
    private final CommunityDao community;
    private final MessageSource messages;
    private final EmailVerificationService emailService;
    private final String uploadDir;
    private static final long MAX_PHOTO_BYTES = 2L * 1024 * 1024;

    public ProfileController(UserRepository users, FarmerProfileDao profiles,
                             DistrictDao districts, CommunityDao community, MessageSource messages,
                             EmailVerificationService emailService,
                             @Value("${pfas.uploads.dir:uploads}") String uploadDir) {
        this.users = users;
        this.profiles = profiles;
        this.districts = districts;
        this.community = community;
        this.messages = messages;
        this.emailService = emailService;
        this.uploadDir = uploadDir;
    }

    /**
     * GET /profile
     * Role: authenticated; farmers edit here, other roles are redirected.
     * Model: profile (may be absent), districts, crops, soils, irrigations, units, langs
     * Template: pages/profile.html
     */
    @GetMapping("/profile")
    public String view(Authentication auth, Model model) {
        String role = role(auth);
        if ("LAB".equals(role)) {
            return "redirect:/lab";
        }
        if ("OWNER".equals(role)) {
            return "redirect:/debug";
        }

        Long userId = currentUserId(auth);
        Optional<FarmerProfile> existing = profiles.findByUserId(userId);

        addOptions(model);
        addAccount(model, userId);
        model.addAttribute("viewerId", userId);
        model.addAttribute("profile", existing.orElseGet(() -> blankProfile(userId, auth.getName())));
        model.addAttribute("selectedCrops", existing
                .map(p -> p.getCrops() == null ? List.<String>of() : List.of(p.getCrops().split(",")))
                .orElse(List.of()));
        model.addAttribute("hasProfile", existing.isPresent());
        model.addAttribute("profilePhoto", existing.map(FarmerProfile::getPhotoPath).orElse(null));
        model.addAttribute("profileInitials", initials(existing.map(FarmerProfile::getFullName).orElse(auth.getName())));
        model.addAttribute("profileAvatarColor", avatarColor(existing.map(FarmerProfile::getFullName).orElse(auth.getName())));
        addRelationships(model, userId);
        model.addAttribute("photoError", model.asMap().get("photoError"));
        model.addAttribute("photoSuccess", model.asMap().get("photoSuccess"));
        // The template reads errors['field'] for every field. An empty map keeps
        // that lookup valid on a plain GET (a null map made the page fail).
        model.addAttribute("errors", Map.of());
        return "pages/profile";
    }

    /**
     * POST /profile
     * Role: FARMER (ownership enforced by user_id)
     * Updates the farmer's own row, or inserts it if the account had none.
     * Per-field errors are translated here and the typed values are echoed back.
     */
    @PostMapping("/profile")
    public String save(Authentication auth,
                       @RequestParam Map<String, String> params,
                       @RequestParam(name = "crops", required = false) List<String> crops,
                       Model model, Locale locale) {

        if (!"FARMER".equals(role(auth))) {
            return "redirect:/profile";
        }

        Long userId = currentUserId(auth);
        String cropList = crops == null ? "" : String.join(",", crops);
        Map<String, String> errors = validate(params, locale);

        FarmerProfile p = new FarmerProfile();
        p.setUserId(userId);
        p.setFullName(trim(params.get("fullName")));
        p.setPhone(trim(params.get("mobile")));
        p.setDistrict(trim(params.get("district")));
        p.setTaluka(trim(params.get("taluka")));
        p.setVillage(trim(params.get("village")));
        p.setCrops(cropList);
        p.setFarmArea(parseArea(params.get("area")));
        p.setUnit(params.getOrDefault("unit", "acre"));
        p.setSoilType(blankToNull(params.get("soil")));
        p.setIrrigation(blankToNull(params.get("irrigation")));
        p.setDescription(blankToNull(params.get("description")));
        p.setPreferredLang(params.getOrDefault("lang", "en"));
        profiles.findByUserId(userId).ifPresent(old -> p.setPhotoPath(old.getPhotoPath()));

        if (!errors.isEmpty()) {
            log.info("[REG] Profile update rejected for user {}: {}", userId, errors.keySet());
            addOptions(model);
            addAccount(model, userId);
            model.addAttribute("viewerId", userId);
            model.addAttribute("errors", errors);
            model.addAttribute("profile", p);
            model.addAttribute("profilePhoto", p.getPhotoPath());
            addRelationships(model, userId);
            model.addAttribute("selectedCrops", crops == null ? List.of() : crops);
            model.addAttribute("hasProfile", profiles.findByUserId(userId).isPresent());
            return "pages/profile";
        }

        boolean exists = profiles.findByUserId(userId).isPresent();
        if (exists) {
            profiles.update(p);
        } else {
            profiles.insert(p);
        }
        log.info("[REG] Profile saved for user {} (district={})", userId, p.getDistrict());
        return "redirect:/profile?saved";
    }

    @PostMapping("/profile/photo")
    public String uploadPhoto(Authentication auth, @RequestParam("photo") MultipartFile photo,
                              RedirectAttributes flash, Locale locale) {
        Long userId = currentUserId(auth);
        if (!"FARMER".equals(role(auth))) {
            return "redirect:/profile";
        }
        if (photo == null || photo.isEmpty()) {
            flash.addFlashAttribute("photoError", msg("profile.photo.error.empty", locale));
            return "redirect:/profile";
        }
        if (photo.getSize() > MAX_PHOTO_BYTES) {
            flash.addFlashAttribute("photoError", msg("error.image.size", locale));
            return "redirect:/profile";
        }
        byte[] bytes;
        BufferedImage source;
        try {
            bytes = photo.getBytes();
            if (!isJpeg(bytes) && !isPng(bytes)) {
                throw new IOException("Unsupported image signature");
            }
            source = ImageIO.read(new ByteArrayInputStream(bytes));
            if (source == null || source.getWidth() < 1 || source.getHeight() < 1
                    || (long) source.getWidth() * source.getHeight() > 20_000_000L) {
                throw new IOException("Invalid image data");
            }
        } catch (IOException ex) {
            flash.addFlashAttribute("photoError", msg("error.image.type", locale));
            return "redirect:/profile";
        }

        Path directory = new File(uploadDir, "avatars").getAbsoluteFile().toPath().normalize();
        Path target = directory.resolve(UUID.randomUUID() + ".jpg").normalize();
        try {
            Files.createDirectories(directory);
            BufferedImage output = cropAndResize(source);
            if (!ImageIO.write(output, "jpg", target.toFile())) {
                throw new IOException("JPEG encoder unavailable");
            }
            String oldPath = profiles.findByUserId(userId).map(FarmerProfile::getPhotoPath).orElse(null);
            if (profiles.updatePhoto(userId, target.toAbsolutePath().toString()) == 0) {
                Files.deleteIfExists(target);
                flash.addFlashAttribute("photoError", msg("profile.photo.error.save", locale));
                return "redirect:/profile";
            }
            deleteOldPhoto(oldPath, directory);
            flash.addFlashAttribute("photoSuccess", msg("profile.photo.success", locale));
        } catch (IOException ex) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // Keep the original path; cleanup can be retried by an operator.
            }
            flash.addFlashAttribute("photoError", msg("profile.photo.error.save", locale));
        }
        return "redirect:/profile";
    }

    @PostMapping("/profile/photo/remove")
    public String removePhoto(Authentication auth, RedirectAttributes flash, Locale locale) {
        Long userId = currentUserId(auth);
        if (!"FARMER".equals(role(auth))) {
            return "redirect:/profile";
        }
        Path directory = new File(uploadDir, "avatars").getAbsoluteFile().toPath().normalize();
        String oldPath = profiles.findByUserId(userId).map(FarmerProfile::getPhotoPath).orElse(null);
        profiles.updatePhoto(userId, null);
        deleteOldPhoto(oldPath, directory);
        flash.addFlashAttribute("photoSuccess", msg("profile.photo.removed", locale));
        return "redirect:/profile";
    }

    @PostMapping("/profile/email/resend")
    public String resendVerificationEmail(Authentication auth, RedirectAttributes flash, Locale locale) {
        Long userId = currentUserId(auth);
        User user = users.findById(userId).orElseThrow();
        EmailVerificationService.Dispatch dispatch = emailService.send(userId, user.getEmail());
        if (dispatch.demo() && dispatch.token() != null) {
            flash.addFlashAttribute("demoLink", emailService.verificationLink(dispatch.token()));
        }
        String key = switch (dispatch.status()) {
            case SENT -> "profile.email.sent";
            case ALREADY -> "profile.email.already";
            case TOO_SOON -> "verify.email.too_soon";
            case LIMIT -> "verify.email.limit";
        };
        flash.addFlashAttribute("emailNotice", msg(key, locale));
        return "redirect:/profile";
    }

    private void addRelationships(Model model, Long userId) {
        model.addAttribute("followingMembers", community.followingMembers(userId));
        model.addAttribute("followerMembers", community.followerMembers(userId));
        model.addAttribute("connectionMembers", community.connectionMembers(userId));
        model.addAttribute("followingIds", new HashSet<>(community.followingIds(userId)));
    }

    private static BufferedImage cropAndResize(BufferedImage source) {
        int side = Math.min(source.getWidth(), source.getHeight());
        int x = (source.getWidth() - side) / 2;
        int y = (source.getHeight() - side) / 2;
        BufferedImage output = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.drawImage(source, 0, 0, 256, 256, x, y, x + side, y + side, null);
        graphics.dispose();
        return output;
    }

    private static boolean isPng(byte[] bytes) {
        byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        if (bytes.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) if (bytes[i] != signature[i]) return false;
        return true;
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes.length >= 3 && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
    }

    private static void deleteOldPhoto(String oldPath, Path directory) {
        if (oldPath == null || oldPath.isBlank()) return;
        Path old = Path.of(oldPath).toAbsolutePath().normalize();
        if (old.startsWith(directory)) {
            try {
                Files.deleteIfExists(old);
            } catch (IOException ignored) {
                // A failed cleanup does not invalidate the newly saved avatar.
            }
        }
    }

    private static String initials(String name) {
        String safeName = name == null || name.isBlank() ? "?" : name.trim();
        String[] words = safeName.split("\\s+");
        return words.length == 1 ? words[0].substring(0, 1).toUpperCase()
                : (words[0].substring(0, 1) + words[words.length - 1].substring(0, 1)).toUpperCase();
    }

    private static String avatarColor(String name) {
        String[] colors = {"#176b57", "#8b4a26", "#365f9d", "#7b3f66", "#55742f", "#965d13"};
        return colors[Math.floorMod(name == null ? 0 : name.hashCode(), colors.length)];
    }

    /** Same rules as registration, minus username and password. */
    private Map<String, String> validate(Map<String, String> f, Locale locale) {
        Map<String, String> errors = new LinkedHashMap<>();

        if (trim(f.get("fullName")).isEmpty()) {
            errors.put("fullName", msg("error.required", locale));
        }

        String mobile = trim(f.get("mobile"));
        if (mobile.isEmpty()) {
            errors.put("mobile", msg("error.required", locale));
        } else if (!mobile.matches("[6-9]\\d{9}")) {
            errors.put("mobile", msg("error.mobile.invalid", locale));
        }

        String district = trim(f.get("district"));
        if (district.isEmpty()) {
            errors.put("district", msg("error.required", locale));
        } else if (!districts.nameExists(district)) {
            errors.put("district", msg("error.district.invalid", locale));
        }

        if (trim(f.get("taluka")).isEmpty()) {
            errors.put("taluka", msg("error.required", locale));
        }
        if (trim(f.get("village")).isEmpty()) {
            errors.put("village", msg("error.required", locale));
        }

        String area = trim(f.get("area"));
        if (area.isEmpty()) {
            errors.put("area", msg("error.required", locale));
        } else if (parseArea(area) <= 0) {
            errors.put("area", msg("error.area.invalid", locale));
        }
        return errors;
    }

    private void addOptions(Model model) {
        model.addAttribute("districts", districts.findAll());
        model.addAttribute("crops", ReferenceData.CROPS);
        model.addAttribute("soils", ReferenceData.SOILS);
        model.addAttribute("irrigations", ReferenceData.IRRIGATION);
        model.addAttribute("units", ReferenceData.UNITS);
        model.addAttribute("langs", ReferenceData.LANGS);
    }

    /**
     * PART B: the account tile also shows the email address and whether the
     * phone and email have been verified. Read-only: verification is changed on
     * the /verify pages, never by editing this form.
     */
    private void addAccount(Model model, Long userId) {
        users.findById(userId).ifPresent(u -> {
            model.addAttribute("accountEmail", u.getEmail());
            model.addAttribute("emailVerified", u.isEmailVerified());
        });
    }

    private FarmerProfile blankProfile(Long userId, String username) {
        FarmerProfile p = new FarmerProfile();
        p.setUserId(userId);
        p.setFullName(username);
        p.setUnit("acre");
        p.setPreferredLang("en");
        return p;
    }

    private Long currentUserId(Authentication auth) {
        return users.findByUsername(auth.getName())
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException("Logged-in user not found: " + auth.getName()));
    }

    private static String role(Authentication auth) {
        if (auth == null) {
            return "";
        }
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority().replace("ROLE_", ""))
                .findFirst().orElse("");
    }

    private String msg(String key, Locale locale) {
        return messages.getMessage(key, null, locale);
    }

    private static double parseArea(String s) {
        try {
            return Double.parseDouble(trim(s));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
