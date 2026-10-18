package com.precisionfarming.advisory_service.controller;

import com.precisionfarming.advisory_service.model.FarmerProfile;
import com.precisionfarming.advisory_service.model.Member;
import com.precisionfarming.advisory_service.model.ReferenceData;
import com.precisionfarming.advisory_service.model.User;
import com.precisionfarming.advisory_service.repository.CommunityDao;
import com.precisionfarming.advisory_service.repository.DistrictDao;
import com.precisionfarming.advisory_service.repository.FarmerProfileDao;
import com.precisionfarming.advisory_service.repository.UserRepository;
import com.precisionfarming.advisory_service.service.ProfilePhotoService;
import com.precisionfarming.advisory_service.service.VerificationSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The farmer's own profile (workspace 7) - STEP 2, extended in PART 2a.
 *
 * OWNERSHIP: every read and write is scoped to the logged-in user's id, taken
 * from the security context, never from the URL. A farmer can therefore only
 * ever see and edit their own row.
 *
 * Role handling: /profile is for farmers. A lab account is sent to its own
 * dashboard and the owner to /debug, so no top-bar link can ever 403 here.
 *
 * PART 2a adds:
 *   POST /profile/photo         - upload a JPG/PNG profile picture (multipart)
 *   POST /profile/photo/remove  - remove it
 * and a one-time verification handle on the account tile, so the "Send
 * verification email" button can POST to the existing /verify/email/resend.
 */
@Controller
public class ProfileController {

    private static final Logger log = LoggerFactory.getLogger(ProfileController.class);

    private final UserRepository users;
    private final FarmerProfileDao profiles;
    private final DistrictDao districts;
    private final MessageSource messages;
    private final VerificationSessionService sessions;
    private final ProfilePhotoService photos;
    private final CommunityDao community;

    public ProfileController(UserRepository users, FarmerProfileDao profiles,
                             DistrictDao districts, MessageSource messages,
                             VerificationSessionService sessions, ProfilePhotoService photos,
                             CommunityDao community) {
        this.users = users;
        this.profiles = profiles;
        this.districts = districts;
        this.messages = messages;
        this.sessions = sessions;
        this.photos = photos;
        this.community = community;
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
        return profileView(model, auth, currentUserId(auth), Map.of(), null, null);
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

        if (!errors.isEmpty()) {
            log.info("[REG] Profile update rejected for user {}: {}", userId, errors.keySet());
            return profileView(model, auth, userId, errors, p,
                    crops == null ? List.of() : crops);
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

    /**
     * POST /profile/photo
     * Role: FARMER (ownership enforced by user_id)
     * Multipart upload (CSRF via th:action). The image is validated and stored by
     * ProfilePhotoService; on refusal a friendly per-field error is shown and the
     * page is re-rendered, never a 500.
     */
    @PostMapping("/profile/photo")
    public String uploadPhoto(Authentication auth, Model model, Locale locale,
                              @RequestParam(name = "photo", required = false) MultipartFile photo) {
        if (!"FARMER".equals(role(auth))) {
            return "redirect:/profile";
        }
        Long userId = currentUserId(auth);

        ProfilePhotoService.StoreResult result = photos.store(userId, photo);
        if (result.errorKey() != null) {
            log.info("[PROFILE] photo rejected for user {}: {}", userId, result.errorKey());
            return profileView(model, auth, userId,
                    Map.of("photo", msg(result.errorKey(), locale)), null, null);
        }

        // Keep the previous file only long enough to delete it after the DB write.
        String previous = profiles.findByUserId(userId)
                .map(FarmerProfile::getPhotoPath).orElse(null);
        profiles.upsertPhoto(userId, result.path());
        if (previous != null && !previous.equals(result.path())) {
            photos.deleteQuietly(previous);
        }
        log.info("[PROFILE] photo updated for user {}", userId);
        return "redirect:/profile?photo=saved";
    }

    /**
     * POST /profile/photo/remove
     * Role: FARMER (ownership enforced by user_id)
     * Clears the stored path and deletes the file. CSRF via th:action.
     */
    @PostMapping("/profile/photo/remove")
    public String removePhoto(Authentication auth) {
        if (!"FARMER".equals(role(auth))) {
            return "redirect:/profile";
        }
        Long userId = currentUserId(auth);
        String previous = profiles.findByUserId(userId)
                .map(FarmerProfile::getPhotoPath).orElse(null);
        profiles.clearPhoto(userId);
        photos.deleteQuietly(previous);
        log.info("[PROFILE] photo removed for user {}", userId);
        return "redirect:/profile?photo=removed";
    }

    /**
     * Renders pages/profile.html with the full model it needs - used by the GET,
     * and by every error re-render (profile validation OR a bad photo upload) so
     * a failure never loses what the farmer typed.
     */
    private String profileView(Model model, Authentication auth, Long userId,
                               Map<String, String> errors, FarmerProfile typedProfile,
                               List<String> typedCrops) {
        Optional<FarmerProfile> existing = profiles.findByUserId(userId);

        addOptions(model);
        addAccount(model, userId);
        addPhoto(model, existing);
        model.addAttribute("errors", errors);
        model.addAttribute("profile", typedProfile != null
                ? typedProfile
                : existing.orElseGet(() -> blankProfile(userId, auth.getName())));
        model.addAttribute("selectedCrops", typedCrops != null
                ? typedCrops
                : existing.map(p -> p.getCrops() == null
                            ? List.<String>of() : List.of(p.getCrops().split(",")))
                        .orElse(List.of()));
        model.addAttribute("hasProfile", existing.isPresent());
        // PART 2b: the farmer's OWN community posts, newest first, so /profile can
        // offer an Edit / Delete link per post (ownership is enforced elsewhere).
        model.addAttribute("myPosts", community.postsBy(userId, userId));
        // PART 3: the Following / Followers / Connections tiles. The viewer is
        // always excluded from their own lists, and the cards show only public
        // details (avatar, name, district, taluka, crops) - never phone or email.
        List<Member> followingList = community.following(userId);
        List<Member> followerList = community.followers(userId);
        List<Member> connectionList = community.connections(userId);
        model.addAttribute("followingList", followingList);
        model.addAttribute("followerList", followerList);
        model.addAttribute("connectionList", connectionList);
        model.addAttribute("followingCount", followingList.size());
        model.addAttribute("followerCount", followerList.size());
        model.addAttribute("connectionCount", connectionList.size());
        model.addAttribute("followingIds", new HashSet<>(community.followingIds(userId)));
        model.addAttribute("me", userId);
        model.addAttribute("withPhoto", profiles.idsWithPhoto());
        return "pages/profile";
    }

    /** Same rules as registration, minus username and password. */
    private Map<String, String> validate(Map<String, String> f, Locale locale) {
        Map<String, String> errors = new LinkedHashMap<>();

        if (trim(f.get("fullName")).isEmpty()) {
            errors.put("fullName", msg("error.required", locale));
        }

        // The contact number is OPTIONAL. If supplied it must be a 10 digit
        // number starting with 6-9 (same rule as registration).
        String mobile = trim(f.get("mobile"));
        if (!mobile.isEmpty() && !mobile.matches("[6-9]\\d{9}")) {
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
     * PART 1: the account tile also shows the email address, its verified status
     * and the contact number (shown on one's OWN profile only, never to others).
     * Read-only: verification is changed on the /verify pages, never by editing
     * this form.
     *
     * PART 2a: when the account has an email, a fresh one-time verification handle
     * is created here so the "Send verification email" button can POST to the
     * existing /verify/email/resend endpoint (which needs an ?h= handle - a
     * logged-in farmer has none by default).
     */
    private void addAccount(Model model, Long userId) {
        users.findById(userId).ifPresent(u -> {
            model.addAttribute("accountEmail", u.getEmail());
            model.addAttribute("emailVerified", u.isEmailVerified());
            model.addAttribute("accountContact", u.getPhone());
            if (u.getEmail() != null && !u.getEmail().isBlank()) {
                model.addAttribute("verifyHandle", sessions.create(userId));
            }
        });
    }

    /** PART 2a: exposes the current photo path and a simple has/has-not flag. */
    private void addPhoto(Model model, Optional<FarmerProfile> existing) {
        String path = existing.map(FarmerProfile::getPhotoPath).orElse(null);
        model.addAttribute("photoPath", path);
        model.addAttribute("hasPhoto", path != null && !path.isBlank());
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
