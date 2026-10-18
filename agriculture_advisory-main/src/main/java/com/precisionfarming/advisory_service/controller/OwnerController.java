package com.precisionfarming.advisory_service.controller;

import com.precisionfarming.advisory_service.model.Lab;
import com.precisionfarming.advisory_service.model.Scheme;
import com.precisionfarming.advisory_service.repository.CommunityDao;
import com.precisionfarming.advisory_service.repository.LabDao;
import com.precisionfarming.advisory_service.repository.LabRequestDao;
import com.precisionfarming.advisory_service.repository.LabResultDao;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * OWNER workspace (STEP 7). Every route here is locked to ROLE_OWNER by
 * SecurityConfig (`/owner`, `/owner/**` -> hasRole("OWNER")), so the role is
 * never taken from the URL.
 *
 *   GET  /owner                     dashboard: the five live counts, the labs
 *                                   waiting for a decision and shortcuts to
 *                                   /debug and /community moderation
 *   POST /owner/lab/{id}/approve    approve a PENDING lab (it can then log in)
 *   POST /owner/lab/{id}/reject     reject a PENDING lab
 *   GET  /owner/schemes             the schemes the owner can edit
 *   GET  /owner/schemes/{key}       edit one scheme in one language
 *   POST /owner/schemes/{key}       save that language
 *
 * NO JAVASCRIPT: approve/reject are small POST forms (CSRF via th:action) and
 * the scheme editor is a plain form.
 */
@Controller
public class OwnerController {

    private static final Logger log = LoggerFactory.getLogger(OwnerController.class);

    /** Languages the owner may edit; anything else falls back to English. */
    private static final Set<String> LANGS = Set.of("en", "hi", "mr");

    private final UserRepository users;
    private final LabDao labs;
    private final LabRequestDao requests;
    private final LabResultDao results;
    private final CommunityDao community;
    private final SchemeDao schemes;

    public OwnerController(UserRepository users, LabDao labs, LabRequestDao requests,
                           LabResultDao results, CommunityDao community, SchemeDao schemes) {
        this.users = users;
        this.labs = labs;
        this.requests = requests;
        this.results = results;
        this.community = community;
        this.schemes = schemes;
    }

    /**
     * URL     : GET /owner
     * Role    : OWNER only
     * Model   : farmers, labs, requests, reports, posts, pending (List of Lab)
     * Template: pages/owner.html
     */
    @GetMapping("/owner")
    public String dashboard(Model model) {
        List<Lab> pending = labs.findPending();
        model.addAttribute("farmers", users.countByRole("FARMER"));
        model.addAttribute("labs", labs.countAll());
        model.addAttribute("requests", requests.countAll());
        model.addAttribute("reports", results.countReports());
        model.addAttribute("posts", community.countPosts());
        model.addAttribute("pending", pending);
        model.addAttribute("pendingCount", pending.size());
        return "pages/owner";
    }

    /**
     * Approves a PENDING lab. Once APPROVED the lab account stops being
     * disabled in SecurityConfig, so it can log in to its dashboard.
     */
    @PostMapping("/owner/lab/{id}/approve")
    public String approveLab(@PathVariable Long id, Authentication auth) {
        if (labs.updateStatus(id, "APPROVED") == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        log.info("[OWNER] {} approved lab {}", auth.getName(), id);
        return "redirect:/owner?saved";
    }

    @PostMapping("/owner/lab/{id}/reject")
    public String rejectLab(@PathVariable Long id, Authentication auth) {
        if (labs.updateStatus(id, "REJECTED") == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        log.info("[OWNER] {} rejected lab {}", auth.getName(), id);
        return "redirect:/owner?saved";
    }

    /**
     * URL     : GET /owner/schemes
     * Role    : OWNER only
     * Model   : schemes (List of Scheme, English text)
     * Template: pages/owner-schemes.html
     */
    @GetMapping("/owner/schemes")
    public String schemes(Model model) {
        model.addAttribute("schemes", schemes.findAll("en"));
        return "pages/owner-schemes";
    }

    /**
     * URL     : GET /owner/schemes/{key}?lang=en
     * Role    : OWNER only
     * Model   : scheme, lang (en/hi/mr)
     * Template: pages/owner-scheme.html
     * An unknown key -> 404, not 500.
     */
    @GetMapping("/owner/schemes/{key}")
    public String editScheme(@PathVariable String key,
                             @RequestParam(name = "lang", required = false) String lang,
                             Model model) {
        String l = lang(lang);
        Scheme scheme = schemes.findByKey(key, l)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("scheme", scheme);
        model.addAttribute("lang", l);
        if (!model.containsAttribute("errors")) {
            model.addAttribute("errors", Map.of());
        }
        return "pages/owner-scheme";
    }

    /**
     * Saves one language of one scheme. The name is required; the five text
     * blocks may be cleared. Invalid input re-renders the form (200) instead
     * of throwing, so nothing typed is lost.
     */
    @PostMapping("/owner/schemes/{key}")
    public String saveScheme(@PathVariable String key,
                             @RequestParam(name = "lang", required = false) String lang,
                             @RequestParam(name = "name", required = false) String name,
                             @RequestParam(name = "what", required = false) String what,
                             @RequestParam(name = "who", required = false) String who,
                             @RequestParam(name = "docs", required = false) String docs,
                             @RequestParam(name = "steps", required = false) String steps,
                             @RequestParam(name = "where", required = false) String where,
                             Authentication auth, Model model) {
        String l = lang(lang);
        Scheme scheme = schemes.findByKey(key, l)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        String trimmedName = name == null ? "" : name.trim();
        if (trimmedName.isEmpty()) {
            log.info("[OWNER] scheme {} [{}] rejected: empty name", key, l);
            Scheme echo = scheme;
            echo.setName("");
            echo.setWhat(what);
            echo.setWho(who);
            echo.setDocs(docs);
            echo.setSteps(steps);
            echo.setWhere(where);
            model.addAttribute("scheme", echo);
            model.addAttribute("lang", l);
            model.addAttribute("errors", Map.of("name", "error.required"));
            return "pages/owner-scheme";
        }

        schemes.updateText(scheme.getId(), l, trimmedName, what, who, docs, steps, where);
        log.info("[OWNER] {} saved scheme {} [{}]", auth.getName(), key, l);
        return "redirect:/owner/schemes/" + key + "?lang=" + l + "&saved";
    }

    private static String lang(String lang) {
        String l = lang == null ? "en" : lang.trim().toLowerCase(Locale.ROOT);
        return LANGS.contains(l) ? l : "en";
    }
}
