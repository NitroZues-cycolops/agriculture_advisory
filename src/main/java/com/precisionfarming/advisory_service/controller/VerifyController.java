package com.precisionfarming.advisory_service.controller;

import com.precisionfarming.advisory_service.model.PhoneVerification;
import com.precisionfarming.advisory_service.model.User;
import com.precisionfarming.advisory_service.repository.UserRepository;
import com.precisionfarming.advisory_service.repository.VerificationDao;
import com.precisionfarming.advisory_service.service.EmailVerificationService;
import com.precisionfarming.advisory_service.service.OtpService;
import com.precisionfarming.advisory_service.service.VerificationSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Phone and email verification (PART B).
 *
 * ROUTES
 *   GET  /verify                ?h=...     - verify the phone (step 1)
 *   POST /verify/phone          h, code    - submit the 6-digit code
 *   POST /verify/resend         h          - ask for a new code
 *   GET  /verify/email          ?h=...     - verify the email (step 2, optional)
 *   POST /verify/email/resend   h          - send the link again
 *   GET  /verify/email/confirm  ?token=... - the link inside the email
 *   POST /verify/recover        username   - the "login is blocked" link on /login
 *   POST /verify/skip           h          - skip email, go to login
 * ROLE: public (a new account is not logged in yet; SecurityConfig permits /verify/**)
 *
 * The account is identified by the random one-time handle in the link (table
 * verification_session), never by a user id typed into the URL - so nobody can
 * verify someone else's phone by guessing a number. CSRF: every form uses
 * th:action, which adds the hidden token.
 */
@Controller
public class VerifyController {

    private static final Logger log = LoggerFactory.getLogger(VerifyController.class);

    private final UserRepository users;
    private final VerificationDao dao;
    private final OtpService otpService;
    private final EmailVerificationService emailService;
    private final VerificationSessionService sessions;
    private final MessageSource messages;

    public VerifyController(UserRepository users, VerificationDao dao, OtpService otpService,
                            EmailVerificationService emailService,
                            VerificationSessionService sessions, MessageSource messages) {
        this.users = users;
        this.dao = dao;
        this.otpService = otpService;
        this.emailService = emailService;
        this.sessions = sessions;
        this.messages = messages;
    }

    // ==================================================================
    // STEP 1 - phone
    // ==================================================================

    /**
     * GET /verify?h=...
     * Model: h, username, role, phoneMasked, phoneVerified, demoMode, demoCode, errors, notice
     * Template: pages/verify-phone
     */
    @GetMapping("/verify")
    public String phonePage(@RequestParam(name = "h", required = false) String handle,
                            @RequestParam(name = "sent", required = false) String sent,
                            @RequestParam(name = "r", required = false) String resendStatus,
                            Model model, Locale locale) {

        Optional<Long> userId = sessions.resolve(handle);
        if (userId.isEmpty()) {
            return linkExpired(model);
        }
        Long id = userId.get();

        // A verified phone moves straight on to the (optional) email step.
        if (isPhoneVerified(id)) {
            return "redirect:/verify/email?h=" + handle;
        }

        phoneModel(model, id, handle, Map.of());
        if (sent != null) {
            model.addAttribute("notice", msg("verify.phone.sent", locale));
        }
        if (resendStatus != null) {
            model.addAttribute("notice", resendMessage(resendStatus, locale));
        }
        return "pages/verify-phone";
    }

    /**
     * POST /verify/phone
     * Checks the code against the stored hash and applies the lockout rule.
     * On success -> /verify/email (the optional step). On failure the same page
     * is re-rendered with a translated message and the typed code kept.
     */
    @PostMapping("/verify/phone")
    public String submitPhone(@RequestParam(name = "h", required = false) String handle,
                              @RequestParam(name = "code", required = false) String code,
                              Model model, Locale locale) {

        Optional<Long> userId = sessions.resolve(handle);
        if (userId.isEmpty()) {
            return linkExpired(model);
        }
        Long id = userId.get();
        OtpService.VerifyResult result = otpService.verify(id, code);

        if (result.status() == OtpService.Status.OK || result.status() == OtpService.Status.ALREADY) {
            log.info("[VERIFY] phone step complete for user {}", id);
            return "redirect:/verify/email?h=" + handle;
        }

        Map<String, String> errors = new LinkedHashMap<>();
        switch (result.status()) {
            case EXPIRED -> errors.put("code", msg("verify.phone.expired", locale));
            case LOCKED -> errors.put("code", msg("verify.phone.locked", locale, result.lockMinutes()));
            case NONE -> errors.put("code", msg("verify.phone.none", locale));
            default -> errors.put("code", msg("verify.phone.wrong", locale, result.attemptsLeft()));
        }
        phoneModel(model, id, handle, errors);
        model.addAttribute("code", code);
        return "pages/verify-phone";
    }

    /**
     * POST /verify/resend
     * A new code at most once a minute and 5 times an hour; refusing is a normal
     * outcome, not an error, so the page explains it and stays usable.
     */
    @PostMapping("/verify/resend")
    public String resendPhone(@RequestParam(name = "h", required = false) String handle) {
        Optional<Long> userId = sessions.resolve(handle);
        if (userId.isEmpty()) {
            return "redirect:/login";
        }
        Long id = userId.get();
        User user = users.findById(id).orElse(null);
        if (user == null) {
            return "redirect:/login";
        }
        OtpService.ResendStatus status = otpService.resend(id, user.getPhone());
        if (status == OtpService.ResendStatus.OK) {
            return "redirect:/verify?h=" + handle + "&sent=1";
        }
        return "redirect:/verify?h=" + handle + "&r=" + status.name();
    }

    // ==================================================================
    // STEP 2 - email (optional)
    // ==================================================================

    /**
     * GET /verify/email?h=...
     * Only reachable once the phone is verified. Shows the address and the button
     * that sends the link; in demo mode the link comes back as a flash attribute.
     * Template: pages/verify-email
     */
    @GetMapping("/verify/email")
    public String emailPage(@RequestParam(name = "h", required = false) String handle,
                            @RequestParam(name = "sent", required = false) String sent,
                            Model model, Locale locale) {

        Optional<Long> userId = sessions.resolve(handle);
        if (userId.isEmpty()) {
            return linkExpired(model);
        }
        Long id = userId.get();
        if (!isPhoneVerified(id)) {
            return "redirect:/verify?h=" + handle;
        }
        emailModel(model, id, handle);
        if (sent != null) {
            model.addAttribute("notice", msg("verify.email.sent", locale));
        }
        return "pages/verify-email";
    }

    /** POST /verify/email/resend - creates and (really or in demo) sends the link. */
    @PostMapping("/verify/email/resend")
    public String resendEmail(@RequestParam(name = "h", required = false) String handle,
                              HttpServletRequest request, RedirectAttributes flash) {
        Optional<Long> userId = sessions.resolve(handle);
        if (userId.isEmpty()) {
            return "redirect:/login";
        }
        Long id = userId.get();
        User user = users.findById(id).orElse(null);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            return "redirect:/verify/email?h=" + handle;
        }
        EmailVerificationService.Dispatch dispatch =
                emailService.send(id, user.getEmail(), baseUrl(request));
        if (dispatch.demo()) {
            // Demo mode: carry the link through ONE redirect (flash attribute) so
            // the token is never written into the address bar.
            flash.addFlashAttribute("demoLink",
                    baseUrl(request) + "/verify/email/confirm?token=" + dispatch.token());
        }
        return "redirect:/verify/email?h=" + handle + "&sent=1";
    }

    /** GET /verify/email/confirm?token=... - the link that was emailed/displayed. */
    @GetMapping("/verify/email/confirm")
    public String confirmEmail(@RequestParam(name = "token", required = false) String token,
                               Model model) {
        EmailVerificationService.ConfirmResult result = emailService.confirm(token);
        model.addAttribute("result", result.status().name());
        model.addAttribute("ok", result.status() == EmailVerificationService.ConfirmStatus.OK
                || result.status() == EmailVerificationService.ConfirmStatus.ALREADY);
        return "pages/verify-email-done";
    }

    /**
     * POST /verify/skip - email is optional, so the user can go straight to login.
     * The one-time handle is removed here: it has served its purpose.
     */
    @PostMapping("/verify/skip")
    public String skipEmail(@RequestParam(name = "h", required = false) String handle) {
        sessions.resolve(handle).ifPresent(sessions::clear);
        return "redirect:/login";
    }

    // ==================================================================
    // Recover - the "login is blocked" link on the login page
    // ==================================================================

    /**
     * POST /verify/recover
     * Called with the username the visitor just typed on /login. A fresh code is
     * sent to the phone ALREADY on the account, so guessing a username only lets
     * someone trigger a message to the real owner - it never reveals the code.
     */
    @PostMapping("/verify/recover")
    public String recover(@RequestParam(name = "username", required = false) String username) {
        String name = username == null ? "" : username.trim();
        Optional<User> found = users.findByUsername(name);
        if (found.isEmpty()) {
            // Same answer as a wrong password: never confirm whether a name exists.
            return "redirect:/login?error";
        }
        User user = found.get();
        if (user.isPhoneVerified()) {
            return "redirect:/login?verified";
        }
        String handle = sessions.create(user.getId());
        OtpService.ResendStatus status = otpService.resend(user.getId(), user.getPhone());
        if (status == OtpService.ResendStatus.NONE) {
            // No OTP row (an account created before PART B): issue a first code.
            otpService.issue(user.getId(), user.getPhone());
            return "redirect:/verify?h=" + handle + "&sent=1";
        }
        if (status == OtpService.ResendStatus.OK) {
            return "redirect:/verify?h=" + handle + "&sent=1";
        }
        return "redirect:/verify?h=" + handle + "&r=" + status.name();
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private boolean isPhoneVerified(Long userId) {
        return users.findById(userId).map(User::isPhoneVerified).orElse(false);
    }

    private String linkExpired(Model model) {
        return "pages/verify-link";
    }

    private void phoneModel(Model model, Long userId, String handle, Map<String, String> errors) {
        User u = users.findById(userId).orElseThrow();
        PhoneVerification pv = dao.findPhone(userId).orElse(null);
        String phone = pv != null ? pv.phone() : u.getPhone();
        model.addAttribute("h", handle);
        model.addAttribute("username", u.getUsername());
        model.addAttribute("role", u.getRole());
        model.addAttribute("phoneMasked", mask(phone));
        model.addAttribute("phoneVerified", u.isPhoneVerified());
        model.addAttribute("demoMode", otpService.demoMode());
        model.addAttribute("demoCode", otpService.demoCode(phone));
        model.addAttribute("maxAttempts", otpService.maxAttempts());
        model.addAttribute("errors", errors);
    }

    private void emailModel(Model model, Long userId, String handle) {
        User u = users.findById(userId).orElseThrow();
        model.addAttribute("h", handle);
        model.addAttribute("username", u.getUsername());
        model.addAttribute("email", u.getEmail());
        model.addAttribute("emailVerified", u.isEmailVerified());
        model.addAttribute("realMail", emailService.realMail());
        model.addAttribute("errors", Map.of());
    }

    private String resendMessage(String status, Locale locale) {
        return switch (status) {
            case "TOO_SOON" -> msg("verify.phone.too_soon", locale);
            case "LIMIT" -> msg("verify.phone.limit", locale);
            case "LOCKED" -> msg("verify.phone.locked", locale, otpService.lockMinutes());
            default -> msg("verify.phone.none", locale);
        };
    }

    private String baseUrl(HttpServletRequest request) {
        return ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath(null).replaceQuery(null).build().toUriString();
    }

    /** 9876543210 -> 98******10 (never show a full phone number on screen). */
    static String mask(String phone) {
        if (phone == null || phone.isBlank()) {
            return "";
        }
        String p = phone.trim();
        if (p.length() <= 4) {
            return "*".repeat(p.length());
        }
        return p.substring(0, 2) + "*".repeat(p.length() - 4) + p.substring(p.length() - 2);
    }

    private String msg(String key, Locale locale, Object... args) {
        return messages.getMessage(key, args, locale);
    }
}
