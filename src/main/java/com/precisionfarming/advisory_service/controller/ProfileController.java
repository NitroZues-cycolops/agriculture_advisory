package com.precisionfarming.advisory_service.controller;

import com.precisionfarming.advisory_service.model.FarmerProfile;
import com.precisionfarming.advisory_service.model.ReferenceData;
import com.precisionfarming.advisory_service.model.User;
import com.precisionfarming.advisory_service.repository.DistrictDao;
import com.precisionfarming.advisory_service.repository.FarmerProfileDao;
import com.precisionfarming.advisory_service.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
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
    private final MessageSource messages;

    public ProfileController(UserRepository users, FarmerProfileDao profiles,
                             DistrictDao districts, MessageSource messages) {
        this.users = users;
        this.profiles = profiles;
        this.districts = districts;
        this.messages = messages;
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
        model.addAttribute("profile", existing.orElseGet(() -> blankProfile(userId, auth.getName())));
        model.addAttribute("selectedCrops", existing
                .map(p -> p.getCrops() == null ? List.<String>of() : List.of(p.getCrops().split(",")))
                .orElse(List.of()));
        model.addAttribute("hasProfile", existing.isPresent());
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

        if (!errors.isEmpty()) {
            log.info("[REG] Profile update rejected for user {}: {}", userId, errors.keySet());
            addOptions(model);
            addAccount(model, userId);
            model.addAttribute("errors", errors);
            model.addAttribute("profile", p);
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
            model.addAttribute("phoneVerified", u.isPhoneVerified());
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
