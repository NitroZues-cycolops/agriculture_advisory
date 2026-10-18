package com.precisionfarming.advisory_service.controller;

import com.precisionfarming.advisory_service.model.Lab;
import com.precisionfarming.advisory_service.model.LabRequest;
import com.precisionfarming.advisory_service.model.LabResult;
import com.precisionfarming.advisory_service.model.LabStatus;
import com.precisionfarming.advisory_service.model.LabStatusEvent;
import com.precisionfarming.advisory_service.model.User;
import com.precisionfarming.advisory_service.repository.LabDao;
import com.precisionfarming.advisory_service.repository.LabRequestDao;
import com.precisionfarming.advisory_service.repository.LabResultDao;
import com.precisionfarming.advisory_service.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The LAB side of the lab workspace (workspace 4) - STEP 5.
 *
 *   GET  /lab/dashboard          the lab's own queue, one card per request
 *   POST /lab/dashboard/advance  move one request to the next stage
 *   POST /lab/dashboard/result   save / correct the measured values
 *
 * WHO MAY CALL THESE: only a user with the LAB role (SecurityConfig maps
 * /lab/dashboard/** to hasRole("LAB")) AND who owns the request's lab row.
 * The lab id is always looked up from the security context (users -> labs),
 * never taken from a form field, so a lab cannot touch another lab's queue
 * even by editing the hidden requestId.
 *
 * NO JAVASCRIPT: stage advance and result saving are two normal POST forms
 * using th:action, so Spring Security adds the CSRF token itself.
 */
@Controller
public class LabDashboardController {

    private static final Logger log = LoggerFactory.getLogger(LabDashboardController.class);

    private static final int MAX_REMARKS = 500;

    private final LabDao labs;
    private final LabRequestDao requests;
    private final LabResultDao results;
    private final UserRepository users;
    private final MessageSource messages;

    public LabDashboardController(LabDao labs, LabRequestDao requests, LabResultDao results,
                                  UserRepository users, MessageSource messages) {
        this.labs = labs;
        this.requests = requests;
        this.results = results;
        this.users = users;
        this.messages = messages;
    }

    /**
     * One row of the dashboard: the request, its saved result (may be null so
     * the form starts empty), the stage the "advance" button would move to
     * (null when the request is already at the last stage) and the request's
     * own status timeline.
     */
    public record QueueRow(LabRequest request, LabResult result, LabStatus next,
                           List<LabStatusEvent> history) { }

    /**
     * URL     : GET /lab/dashboard
     * Method  : dashboard()
     * Role    : LAB only
     * Params  : saved (tracking id just updated -> success banner),
     *           track (tracking id to jump to, still ownership checked)
     * Model   : lab, labName, queue (List<QueueRow>), total, openCount,
     *           readyCount, samples, form (echo on a failed save),
     *           errors (per-field messages)
     * Template: pages/lab-dashboard.html
     */
    @GetMapping("/lab/dashboard")
    public String dashboard(Authentication auth,
                            @RequestParam(name = "saved", required = false) String saved,
                            @RequestParam(name = "track", required = false) String track,
                            Model model) {
        Lab lab = myLab(auth);
        List<LabRequest> queue = requests.findingsForLab(lab.getId());

        List<QueueRow> rows = new ArrayList<>();
        for (LabRequest r : queue) {
            rows.add(new QueueRow(r, results.findByRequestId(r.getId()).orElse(null),
                    r.stage().next(), requests.history(r.getId())));
        }

        int open = 0;
        int ready = 0;
        for (LabRequest r : queue) {
            if (r.stage().next() != null) {
                open++;
            }
            if (r.stage() == LabStatus.REPORT_READY) {
                ready++;
            }
        }

        model.addAttribute("lab", lab);
        model.addAttribute("labName", lab.getName());
        model.addAttribute("queue", rows);
        model.addAttribute("total", queue.size());
        model.addAttribute("openCount", open);
        model.addAttribute("readyCount", ready);
        model.addAttribute("saved", saved);
        model.addAttribute("track", track);
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", Map.of());
        }
        if (!model.containsAttribute("errors")) {
            model.addAttribute("errors", Map.of());
        }
        log.debug("[LAB] Dashboard for lab {} ({} requests)", lab.getName(), queue.size());
        return "pages/lab-dashboard";
    }

    /**
     * URL     : POST /lab/dashboard/advance
     * Method  : advance()
     * Role    : LAB only, and only for a request of its own queue
     * Moves the request exactly one stage forward (the tracker order lives in
     * LabStatus.next()), appends a line to the status history and returns to
     * the dashboard. A request already at the last stage is left untouched.
     */
    @PostMapping("/lab/dashboard/advance")
    public String advance(Authentication auth,
                          @RequestParam("requestId") Long requestId,
                          @RequestParam(name = "note", required = false) String note) {
        Lab lab = myLab(auth);
        LabRequest r = requests.findById(requestId)
                .filter(x -> lab.getId().equals(x.getLabId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        LabStatus next = r.stage().next();
        if (next == null) {
            log.info("[LAB] {} is already finished; nothing to advance", r.getTrackingId());
            return "redirect:/lab/dashboard";
        }

        String noteText = note == null || note.isBlank() ? null : note.trim();
        if (noteText != null && noteText.length() > MAX_REMARKS) {
            noteText = noteText.substring(0, MAX_REMARKS);
        }
        requests.updateStatus(r.getId(), next, noteText);
        log.info("[LAB] {} advanced {} -> {}", lab.getName(), r.getTrackingId(), next.name());
        return "redirect:/lab/dashboard?saved=" + r.getTrackingId();
    }

    /**
     * URL     : POST /lab/dashboard/result
     * Method  : saveResult()
     * Role    : LAB only, and only for a request of its own queue
     * Saves (or corrects) the measured values. Blank fields are stored as NULL
     * - a half-finished entry is allowed and can be completed later. A value
     * that is present but not a number, or remarks over 500 characters, is
     * reported back on the page in the language the lab is reading.
     */
    @PostMapping("/lab/dashboard/result")
    public String saveResult(Authentication auth, Model model,
                             @RequestParam("requestId") Long requestId,
                             @RequestParam(name = "ph", required = false) String ph,
                             @RequestParam(name = "nitrogen", required = false) String nitrogen,
                             @RequestParam(name = "phosphorus", required = false) String phosphorus,
                             @RequestParam(name = "potassium", required = false) String potassium,
                             @RequestParam(name = "organicCarbon", required = false) String organicCarbon,
                             @RequestParam(name = "electricalConductivity", required = false) String ec,
                             @RequestParam(name = "remarks", required = false) String remarks) {
        Lab lab = myLab(auth);
        LabRequest r = requests.findById(requestId)
                .filter(x -> lab.getId().equals(x.getLabId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        Map<String, String> errors = new LinkedHashMap<>();
        Double phValue = number(ph, "ph", errors);
        Double nValue = number(nitrogen, "nitrogen", errors);
        Double pValue = number(phosphorus, "phosphorus", errors);
        Double kValue = number(potassium, "potassium", errors);
        Double ocValue = number(organicCarbon, "organicCarbon", errors);
        Double ecValue = number(ec, "electricalConductivity", errors);

        String remarkText = remarks == null ? "" : remarks.trim();
        if (remarkText.length() > MAX_REMARKS) {
            errors.put("remarks", msg("error.too.long"));
        }

        if (!errors.isEmpty()) {
            log.info("[LAB] Result for {} rejected: {}", r.getTrackingId(), errors.keySet());
            model.addAttribute("form", echo(requestId, ph, nitrogen, phosphorus, potassium,
                    organicCarbon, ec, remarkText));
            model.addAttribute("errors", errors);
            return dashboard(auth, null, r.getTrackingId(), model);
        }

        LabResult result = new LabResult();
        result.setRequestId(r.getId());
        result.setPh(phValue);
        result.setNitrogen(nValue);
        result.setPhosphorus(pValue);
        result.setPotassium(kValue);
        result.setOrganicCarbon(ocValue);
        result.setElectricalConductivity(ecValue);
        result.setRemarks(remarkText.isEmpty() ? null : remarkText);
        results.upsert(result);

        log.info("[LAB] {} saved the result for {}", lab.getName(), r.getTrackingId());
        return "redirect:/lab/dashboard?saved=" + r.getTrackingId();
    }

    /* ---------- helpers ---------- */

    /**
     * The lab row of the logged-in user. A user without a LAB role, or a LAB
     * account with no lab row (should not happen), gets a 403 rather than a
     * peek at anybody else's queue.
     */
    private Lab myLab(Authentication auth) {
        if (!"LAB".equals(role(auth))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        Long userId = users.findByUsername(auth.getName()).map(User::getId).orElse(null);
        return labs.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
    }

    /** Parses one optional number field, recording a translated error on failure. */
    private Double number(String raw, String field, Map<String, String> errors) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            if (value < 0) {
                errors.put(field, msg("error.number"));
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            errors.put(field, msg("error.number"));
            return null;
        }
    }

    private Map<String, Object> echo(Long requestId, String ph, String nitrogen, String phosphorus,
                                     String potassium, String organicCarbon, String ec, String remarks) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("requestId", requestId);
        form.put("ph", ph);
        form.put("nitrogen", nitrogen);
        form.put("phosphorus", phosphorus);
        form.put("potassium", potassium);
        form.put("organicCarbon", organicCarbon);
        form.put("electricalConductivity", ec);
        form.put("remarks", remarks);
        return form;
    }

    private String msg(String key) {
        // Validation text must appear in the language the lab is reading.
        return messages.getMessage(key, null, key, LocaleContextHolder.getLocale());
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
