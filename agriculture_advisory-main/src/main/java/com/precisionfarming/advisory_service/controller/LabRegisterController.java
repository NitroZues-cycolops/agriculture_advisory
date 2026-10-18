package com.precisionfarming.advisory_service.controller;

import com.precisionfarming.advisory_service.model.Lab;
import com.precisionfarming.advisory_service.model.ReferenceData;
import com.precisionfarming.advisory_service.model.User;
import com.precisionfarming.advisory_service.repository.DistrictDao;
import com.precisionfarming.advisory_service.repository.LabDao;
import com.precisionfarming.advisory_service.repository.UserRepository;
import com.precisionfarming.advisory_service.service.EmailVerificationService;
import com.precisionfarming.advisory_service.service.VerificationSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Soil / water testing lab registration (STEP 2, second half).
 *
 * A lab that registers itself is NOT visible to farmers yet: the row is saved
 * with status = PENDING and only the owner can approve it (STEP 7). This is the
 * ownership + approval rule for the lab side of the app.
 */
@Controller
public class LabRegisterController {

    private static final Logger log = LoggerFactory.getLogger(LabRegisterController.class);

    private final UserRepository users;
    private final LabDao labs;
    private final DistrictDao districts;
    private final PasswordEncoder encoder;
    private final MessageSource messages;
    private final EmailVerificationService emailService;
    private final VerificationSessionService sessions;
    /** Optional public base URL for the verification link (blank -> use the request). */
    private final String baseUrlOverride;

    public LabRegisterController(UserRepository users, LabDao labs, DistrictDao districts,
                                 PasswordEncoder encoder, MessageSource messages,
                                 EmailVerificationService emailService,
                                 VerificationSessionService sessions,
                                 @Value("${app.base-url:}") String baseUrlOverride) {
        this.users = users;
        this.labs = labs;
        this.districts = districts;
        this.encoder = encoder;
        this.messages = messages;
        this.emailService = emailService;
        this.sessions = sessions;
        this.baseUrlOverride = baseUrlOverride;
    }

    /**
     * GET /register/lab
     * Role: any (public - SecurityConfig permits /register/lab)
     * Model: districts, samples (accepted sample types option list)
     * Template: pages/register-lab.html
     */
    @GetMapping("/register/lab")
    public String form(Model model) {
        model.addAttribute("districts", districts.findAll());
        model.addAttribute("samples", ReferenceData.SAMPLE_TYPES);
        model.addAttribute("form", Map.of());
        model.addAttribute("selectedSamples", List.of());
        // The template reads errors['field'] for every field: an empty map keeps
        // that lookup valid on a plain GET (a missing map made the page fail).
        model.addAttribute("errors", Map.of());
        return "pages/register-lab";
    }

    /**
     * POST /register/lab
     * Role: any (public)
     * Creates the users row (role LAB) and the labs row with status PENDING.
     * On success -> /verify/email?h=..., then the owner must approve.
     */
    @PostMapping("/register/lab")
    public String submit(@RequestParam Map<String, String> params,
                         @RequestParam(name = "samples", required = false) List<String> samples,
                         HttpServletRequest request, RedirectAttributes flash,
                         Model model, Locale locale) {

        Map<String, String> form = new LinkedHashMap<>(params);
        String sampleList = samples == null ? "" : String.join(",", samples);
        form.put("samples", sampleList);

        Map<String, String> errors = validate(params, locale);

        if (!errors.isEmpty()) {
            log.info("[REG] Lab registration rejected: {}", errors.keySet());
            model.addAttribute("errors", errors);
            model.addAttribute("form", form);
            model.addAttribute("districts", districts.findAll());
            model.addAttribute("samples", ReferenceData.SAMPLE_TYPES);
            model.addAttribute("selectedSamples", samples == null ? List.of() : samples);
            return "pages/register-lab";
        }

        String username = params.get("username").trim();
        String email = RegisterController.normaliseEmail(params.get("email"));
        String phone = trim(params.get("phone"));

        User user = new User();
        user.setUsername(username);
        user.setPassword(encoder.encode(params.get("password")));
        user.setRole("LAB");
        // PART 1: same identity rules as a farmer - the account starts unverified
        // and cannot log in until the email is verified (and the owner approves).
        user.setEmail(email);
        user.setPhone(phone.isEmpty() ? null : phone);
        user.setEmailVerified(false);
        users.save(user);

        Lab lab = new Lab();
        lab.setUserId(user.getId());
        lab.setName(params.get("name").trim());
        lab.setAddress(params.get("address").trim());
        lab.setPhone(phone.isEmpty() ? null : phone);
        lab.setTimings(blankToNull(params.get("timings")));
        lab.setSampleTypes(sampleList.isEmpty() ? null : sampleList);
        lab.setRequirements(blankToNull(params.get("requirements")));
        lab.setDistrict(params.get("district").trim());
        lab.setTaluka(params.get("taluka").trim());
        lab.setLat(parseDouble(params.get("lat")));
        lab.setLon(parseDouble(params.get("lon")));
        labs.insert(lab);

        // Same email-verification flow as the farmer registration.
        String handle = sessions.create(user.getId());
        EmailVerificationService.Dispatch dispatch =
                emailService.send(user.getId(), email, baseUrl(request));
        if (dispatch.demo() && dispatch.token() != null) {
            flash.addFlashAttribute("demoLink",
                    baseUrl(request) + "/verify/email/confirm?token=" + dispatch.token());
        }

        log.info("[REG] Lab '{}' registered (district={}, status=PENDING), email verification sent",
                lab.getName(), lab.getDistrict());
        return "redirect:/verify/email?h=" + handle;
    }

    /** Server-side rules shared shape with RegisterController. */
    private Map<String, String> validate(Map<String, String> f, Locale locale) {
        Map<String, String> errors = new LinkedHashMap<>();

        String username = trim(f.get("username"));
        String password = f.get("password") == null ? "" : f.get("password");
        String confirm  = f.get("confirm") == null ? "" : f.get("confirm");

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

        // PART B: the email is required, well formed and unique (same as a farmer).
        String email = RegisterController.normaliseEmail(f.get("email"));
        if (email.isEmpty()) {
            errors.put("email", msg("error.required", locale));
        } else if (!RegisterController.isEmail(email)) {
            errors.put("email", msg("error.email.invalid", locale));
        } else if (users.findByEmail(email).isPresent()) {
            errors.put("email", msg("error.email.taken", locale));
        }

        if (trim(f.get("name")).isEmpty()) {
            errors.put("name", msg("error.required", locale));
        }
        if (trim(f.get("address")).isEmpty()) {
            errors.put("address", msg("error.required", locale));
        }

        // The phone is an OPTIONAL contact field. If supplied it must be a
        // 10 digit number starting with 6-9 (same rule as the farmer mobile).
        String phone = trim(f.get("phone"));
        if (!phone.isEmpty() && !phone.matches("[6-9]\\d{9}")) {
            errors.put("phone", msg("error.phone.invalid", locale));
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

        if (!isNumericOrBlank(f.get("lat")) || !isNumericOrBlank(f.get("lon"))) {
            errors.put("lat", msg("error.coordinates.invalid", locale));
        }
        return errors;
    }

    private String msg(String key, Locale locale) {
        return messages.getMessage(key, null, locale);
    }

    /**
     * Base URL for the verification link. `app.base-url` pins it when set
     * (useful behind a proxy); otherwise it is taken from the request.
     */
    private String baseUrl(HttpServletRequest request) {
        if (baseUrlOverride != null && !baseUrlOverride.isBlank()) {
            String s = baseUrlOverride.trim();
            return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
        }
        return ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath(null).replaceQuery(null).build().toUriString();
    }

    private static boolean isNumericOrBlank(String s) {
        if (s == null || s.isBlank()) {
            return true;
        }
        try {
            Double.parseDouble(s.trim());
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static Double parseDouble(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(s.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
