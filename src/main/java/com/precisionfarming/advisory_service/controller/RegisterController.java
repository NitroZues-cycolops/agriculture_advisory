package com.precisionfarming.advisory_service.controller;

import com.precisionfarming.advisory_service.model.FarmerProfile;
import com.precisionfarming.advisory_service.model.ReferenceData;
import com.precisionfarming.advisory_service.model.User;
import com.precisionfarming.advisory_service.repository.DistrictDao;
import com.precisionfarming.advisory_service.repository.FarmerProfileDao;
import com.precisionfarming.advisory_service.repository.UserRepository;
import com.precisionfarming.advisory_service.service.EmailVerificationService;
import com.precisionfarming.advisory_service.service.VerificationSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Farmer self-registration (STEP 2).
 *
 * Every visible string comes from a message key, so the per-field error
 * messages are produced HERE (in the language of the request) and the template
 * only prints them. The form values are echoed back so nothing typed is lost.
 */
@Controller
public class RegisterController {

    private static final Logger log = LoggerFactory.getLogger(RegisterController.class);

    private final UserRepository users;
    private final FarmerProfileDao profiles;
    private final DistrictDao districts;
    private final PasswordEncoder encoder;
    private final MessageSource messages;
    private final EmailVerificationService emailService;
    private final VerificationSessionService sessions;

    public RegisterController(UserRepository users, FarmerProfileDao profiles,
                              DistrictDao districts, PasswordEncoder encoder,
                              MessageSource messages, EmailVerificationService emailService,
                              VerificationSessionService sessions) {
        this.users = users;
        this.profiles = profiles;
        this.districts = districts;
        this.encoder = encoder;
        this.messages = messages;
        this.emailService = emailService;
        this.sessions = sessions;
    }

    /**
     * GET /register
     * Role: any (public - SecurityConfig permits /register)
     * Model: districts, crops, soils, irrigations, units, langs (option lists)
     * Template: pages/register.html
     */
    @GetMapping("/register")
    public String form(Model model) {
        addOptions(model);
        // Always give the template a (possibly empty) form map + selection list,
        // so no template expression ever has to test for null.
        model.addAttribute("form", Map.of());
        model.addAttribute("selectedCrops", List.of());
        // The template reads errors['field'] for every field: an empty map keeps
        // that lookup valid on a plain GET (a missing map made the page fail).
        model.addAttribute("errors", Map.of());
        return "pages/register";
    }

    /**
     * POST /register
     * Role: any (public)
     * Validates, then creates the users row (role FARMER) + the farmer_profiles row
     * in ONE request. On any error the same page is re-rendered with per-field
     * messages and the typed values. On success -> /login?registered.
     * CSRF: the template form uses th:action, which adds the token automatically.
     */
    @PostMapping("/register")
    public String submit(@RequestParam Map<String, String> params,
                         @RequestParam(name = "crops", required = false) List<String> crops,
                         Model model, Locale locale, RedirectAttributes flash) {

        Map<String, String> form = new LinkedHashMap<>(params);
        String cropList = crops == null ? "" : String.join(",", crops);
        form.put("crops", cropList);

        Map<String, String> errors = validate(params, cropList, locale);

        if (!errors.isEmpty()) {
            log.info("[REG] Farmer registration rejected: {}", errors.keySet());
            addOptions(model);
            model.addAttribute("errors", errors);
            model.addAttribute("form", form);
            model.addAttribute("selectedCrops", crops == null ? List.of() : crops);
            return "pages/register";
        }

        String username = params.get("username").trim();
        String email = normaliseEmail(params.get("email"));
        String mobile = params.get("mobile").trim();

        User user = new User();
        user.setUsername(username);
        user.setPassword(encoder.encode(params.get("password")));
        user.setRole("FARMER");
        // Login remains disabled until the email link confirms this address.
        user.setEmail(email);
        user.setPhone(mobile);
        user.setEmailVerified(false);
        users.save(user);

        FarmerProfile p = new FarmerProfile();
        p.setUserId(user.getId());
        p.setFullName(username);                     // editable later on /profile
        p.setPhone(mobile);
        p.setDistrict(params.get("district").trim());
        p.setTaluka(params.get("taluka").trim());
        p.setVillage(params.get("village").trim());
        p.setCrops(cropList);
        p.setFarmArea(Double.parseDouble(params.get("area").trim()));
        p.setUnit(params.getOrDefault("unit", "acre"));
        p.setSoilType(blankToNull(params.get("soil")));
        p.setIrrigation(blankToNull(params.get("irrigation")));
        p.setDescription(blankToNull(params.get("description")));
        p.setPreferredLang(params.getOrDefault("lang", "en"));
        profiles.insert(p);

        String handle = sessions.create(user.getId());
        EmailVerificationService.Dispatch dispatch = emailService.send(user.getId(), email);
        if (dispatch.demo() && dispatch.token() != null) {
            flash.addFlashAttribute("demoLink", emailService.verificationLink(dispatch.token()));
        }

        log.info("[REG] Farmer '{}' registered (district={}), email verification dispatched",
                username, p.getDistrict());
        return "redirect:/verify/email?h=" + handle + "&sent=1";
    }

    /** Server-side rules. Returns field -> translated message (empty = all good). */
    private Map<String, String> validate(Map<String, String> f, String crops, Locale locale) {
        Map<String, String> errors = new LinkedHashMap<>();
        String username = trim(f.get("username"));
        String password = f.get("password") == null ? "" : f.get("password");
        String confirm  = f.get("confirm") == null ? "" : f.get("confirm");
        String mobile   = trim(f.get("mobile"));

        if (username.isEmpty()) {
            errors.put("username", msg("error.required", locale));
        } else if (!username.matches("[A-Za-z0-9_.]{3,30}")) {
            errors.put("username", msg("error.username.invalid", locale));
        } else if (users.findByUsername(username).isPresent()) {
            errors.put("username", msg("error.username.taken", locale));
        }

        if (password.isEmpty()) {
            errors.put("password", msg("error.required", locale));
        } else if (password.length() < 8) {
            errors.put("password", msg("error.password.short", locale));
        }

        if (confirm.isEmpty()) {
            errors.put("confirm", msg("error.required", locale));
        } else if (!confirm.equals(password)) {
            errors.put("confirm", msg("error.password.mismatch", locale));
        }

        // PART B: the email is the account identity - required, well formed, unique.
        String email = normaliseEmail(f.get("email"));
        if (email.isEmpty()) {
            errors.put("email", msg("error.required", locale));
        } else if (!isEmail(email)) {
            errors.put("email", msg("error.email.invalid", locale));
        } else if (users.findByEmail(email).isPresent()) {
            errors.put("email", msg("error.email.taken", locale));
        }

        if (mobile.isEmpty()) {
            errors.put("mobile", msg("error.required", locale));
        } else if (!mobile.matches("[6-9]\\d{9}")) {
            errors.put("mobile", msg("error.mobile.invalid", locale));
        } else if (users.findByPhone(mobile).isPresent()) {
            errors.put("mobile", msg("error.mobile.taken", locale));
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
        } else {
            try {
                if (Double.parseDouble(area) <= 0) {
                    errors.put("area", msg("error.area.invalid", locale));
                }
            } catch (NumberFormatException ex) {
                errors.put("area", msg("error.area.invalid", locale));
            }
        }
        return errors;
    }

    /** The option lists every form needs. */
    private void addOptions(Model model) {
        model.addAttribute("districts", districts.findAll());
        model.addAttribute("crops", ReferenceData.CROPS);
        model.addAttribute("soils", ReferenceData.SOILS);
        model.addAttribute("irrigations", ReferenceData.IRRIGATION);
        model.addAttribute("units", ReferenceData.UNITS);
        model.addAttribute("langs", ReferenceData.LANGS);
    }

    private String msg(String key, Locale locale) {
        return messages.getMessage(key, null, locale);
    }

    /** Emails are compared and stored in lower case, so "A@x.com" == "a@x.com". */
    static String normaliseEmail(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /** A deliberately simple, readable check (the app only needs a plausible address). */
    static boolean isEmail(String s) {
        return s.matches("^[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}$");
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
