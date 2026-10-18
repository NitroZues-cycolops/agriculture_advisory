package com.precisionfarming.advisory_service.controller;

import com.precisionfarming.advisory_service.model.District;
import com.precisionfarming.advisory_service.model.DistrictOffice;
import com.precisionfarming.advisory_service.model.FarmerProfile;
import com.precisionfarming.advisory_service.model.Scheme;
import com.precisionfarming.advisory_service.model.User;
import com.precisionfarming.advisory_service.repository.DistrictDao;
import com.precisionfarming.advisory_service.repository.DistrictOfficeDao;
import com.precisionfarming.advisory_service.repository.FarmerProfileDao;
import com.precisionfarming.advisory_service.repository.SchemeDao;
import com.precisionfarming.advisory_service.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Government schemes and the registration guide (workspace 5) - STEP 3.
 *
 *   GET /schemes            one card per scheme + a "Find my district office" tile
 *   GET /schemes/{key}      the full guide for one scheme (what / who / documents
 *                           / steps / where + the official link)
 *
 * The content is read from the database (`schemes` + `scheme_text`) in the
 * language that is currently active, so the OWNER can edit it in STEP 7 without
 * touching code. Both pages are visible to every logged-in role; the district
 * office tile is personalised from the FARMER's own profile only.
 *
 * Role: any authenticated user (see SecurityConfig - the top bar hides nothing).
 */
@Controller
public class SchemesController {

    private static final Logger log = LoggerFactory.getLogger(SchemesController.class);

    private final SchemeDao schemes;
    private final DistrictOfficeDao offices;
    private final DistrictDao districts;
    private final FarmerProfileDao profiles;
    private final UserRepository users;

    public SchemesController(SchemeDao schemes, DistrictOfficeDao offices, DistrictDao districts,
                             FarmerProfileDao profiles, UserRepository users) {
        this.schemes = schemes;
        this.offices = offices;
        this.districts = districts;
        this.profiles = profiles;
        this.users = users;
    }

    /**
     * GET /schemes
     * Role: any authenticated user
     * Model: schemes       -> List<Scheme> cards in the active language
     *        hasProfile    -> true when a farmer profile (with a district) exists
     *        districtName  -> the farmer's district, shown in the office tile
     *        office        -> DistrictOffice (SAMPLE) or null
     *        sampleKey     -> message key of the SAMPLE warning
     * Template: pages/schemes.html
     */
    @GetMapping("/schemes")
    public String list(Authentication auth, Model model, Locale locale) {
        List<Scheme> cards = schemes.findAll(locale.getLanguage());
        model.addAttribute("schemes", cards);
        log.debug("[SCRAPER] Schemes list rendered: {} schemes, lang={}", cards.size(), locale.getLanguage());

        addOfficeTile(auth, model);
        return "pages/schemes";
    }

    /**
     * GET /schemes/{key}
     * Role: any authenticated user
     * Model: scheme -> Scheme with the full text in the active language
     * Template: pages/scheme-detail.html
     * A key that does not exist answers 404 (never 500) so the top bar stays safe.
     */
    @GetMapping("/schemes/{key}")
    public String detail(@PathVariable String key, Model model, Locale locale) {
        Optional<Scheme> found = schemes.findByKey(key, locale.getLanguage());
        if (found.isEmpty()) {
            log.warn("[SCRAPER] Unknown scheme key requested: {}", key);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown scheme: " + key);
        }
        model.addAttribute("scheme", found.get());
        return "pages/scheme-detail";
    }

    /**
     * Works out the "Find my district office" tile: only a FARMER with a saved
     * profile has a district, so other roles (and farmers without a profile)
     * just get hasProfile=false and the template shows a link to /profile.
     */
    private void addOfficeTile(Authentication auth, Model model) {
        boolean hasProfile = false;
        String districtName = null;
        DistrictOffice office = null;

        if (auth != null && "FARMER".equals(role(auth))) {
            Optional<Long> userId = users.findByUsername(auth.getName()).map(User::getId);
            Optional<FarmerProfile> profile = userId.flatMap(profiles::findByUserId);
            if (profile.isPresent() && profile.get().getDistrict() != null) {
                districtName = profile.get().getDistrict();
                hasProfile = true;
                Optional<District> d = districts.findByName(districtName);
                if (d.isPresent()) {
                    office = offices.findByDistrictCode(d.get().code()).orElse(null);
                }
            }
        }

        model.addAttribute("hasProfile", hasProfile);
        model.addAttribute("districtName", districtName);
        model.addAttribute("office", office);
    }

    private static String role(Authentication auth) {
        if (auth == null) {
            return "";
        }
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority().replace("ROLE_", ""))
                .findFirst().orElse("");
    }
}
