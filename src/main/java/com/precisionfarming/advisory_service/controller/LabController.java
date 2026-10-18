package com.precisionfarming.advisory_service.controller;

import com.precisionfarming.advisory_service.model.Lab;
import com.precisionfarming.advisory_service.model.LabResult;
import com.precisionfarming.advisory_service.model.LabStatus;
import com.precisionfarming.advisory_service.model.LabStatusEvent;
import com.precisionfarming.advisory_service.model.LabRequest;
import com.precisionfarming.advisory_service.model.ReferenceData;
import com.precisionfarming.advisory_service.model.User;
import com.precisionfarming.advisory_service.repository.DistrictDao;
import com.precisionfarming.advisory_service.repository.FarmerProfileDao;
import com.precisionfarming.advisory_service.repository.LabDao;
import com.precisionfarming.advisory_service.repository.LabRequestDao;
import com.precisionfarming.advisory_service.repository.LabResultDao;
import com.precisionfarming.advisory_service.repository.UserRepository;
import com.precisionfarming.advisory_service.service.LabReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The farmer's side of the lab workspace (workspace 4) - STEP 5.
 *
 * THREE THINGS LIVE ON ONE PAGE (/lab):
 *   1. a request form (own district's APPROVED labs, sample type, crop, notes)
 *   2. the farmer's own requests, each with a CSS tracker
 *   3. "labs near me" - address, phone, timings, accepted samples, requirements,
 *      an OpenStreetMap iframe and a Google Maps link
 *
 * OWNERSHIP: every read and write uses the id from the security context, never
 * an id typed into the URL, so a farmer can only ever see their own rows.
 * The report download repeats that check before a byte is written.
 *
 * NO JAVASCRIPT: the tracker is CSS only; the map is an <iframe>; every form
 * uses th:action so Spring Security adds the CSRF token.
 */
@Controller
public class LabController {

    private static final Logger log = LoggerFactory.getLogger(LabController.class);

    private final LabDao labs;
    private final LabRequestDao requests;
    private final LabResultDao results;
    private final LabReportService reportService;
    private final FarmerProfileDao profiles;
    private final DistrictDao districts;
    private final UserRepository users;
    private final MessageSource messages;

    public LabController(LabDao labs, LabRequestDao requests, LabResultDao results,
                         LabReportService reportService, FarmerProfileDao profiles,
                         DistrictDao districts, UserRepository users, MessageSource messages) {
        this.labs = labs;
        this.requests = requests;
        this.results = results;
        this.reportService = reportService;
        this.profiles = profiles;
        this.districts = districts;
        this.users = users;
        this.messages = messages;
    }

    /**
     * URL     : GET /lab
     * Method  : page()
     * Role    : FARMER (a LAB account is sent to its own dashboard)
     * Params  : district (optional), track (optional tracking id to show)
     * Model   : role, hasProfile, districtName, districtFiltered, labs, samples,
     *           myRequests, track, trackHistory, trackResult, trackReportReady,
     *           form (echo of the typed values), errors (per-field messages)
     * Template: pages/lab.html
     */
    @GetMapping("/lab")
    public String page(Authentication auth,
                       @RequestParam(name = "district", required = false) String district,
                       @RequestParam(name = "track", required = false) String track,
                       Model model) {
        String role = role(auth);
        if ("LAB".equals(role)) {
            return "redirect:/lab/dashboard";
        }

        Long userId = currentUserId(auth);
        Optional<String> ownDistrict = profiles.findByUserId(userId)
                .map(p -> p.getDistrict())
                .filter(d -> d != null && !d.isBlank());

        // Which district's labs to show: ?district=, else the farmer's own.
        String wanted = (district == null || district.isBlank())
                ? ownDistrict.orElse(null) : district;
        List<Lab> nearMe = wanted == null ? labs.findApproved() : labs.findApprovedByDistrict(wanted);

        model.addAttribute("role", role);
        model.addAttribute("hasProfile", ownDistrict.isPresent());
        model.addAttribute("districtName", wanted);
        model.addAttribute("districtFiltered", wanted != null);
        model.addAttribute("labs", nearMe);
        model.addAttribute("samples", ReferenceData.SAMPLE_TYPES);
        model.addAttribute("crops", ReferenceData.CROPS);
        model.addAttribute("districts", districts.findAll());

        // The farmer's own requests only (ownership by user_id).
        model.addAttribute("myRequests", "FARMER".equals(role)
                ? requests.findingsForFarmer(userId) : List.of());

        // Optional tracker for one request, still ownership checked.
        if (track != null && !track.isBlank()) {
            requests.findByTrackingId(track)
                    .filter(r -> canSee(r, auth))
                    .ifPresent(r -> {
                        model.addAttribute("track", r);
                        model.addAttribute("trackHistory", requests.history(r.getId()));
                        Optional<LabResult> result = results.findByRequestId(r.getId());
                        model.addAttribute("trackResult", result.orElse(null));
                        model.addAttribute("trackReportReady",
                                r.stage() == LabStatus.REPORT_READY || r.stage() == LabStatus.REPORT_COLLECTED);
                    });
        }

        model.addAttribute("form", Map.of());
        model.addAttribute("errors", Map.of());
        return "pages/lab";
    }

    /**
     * URL     : POST /lab/request
     * Method  : create()
     * Role    : FARMER only
     * Validates the lab (must be APPROVED), the sample type and the crop, then
     * creates the request with a fresh tracking id and redirects to its tracker.
     */
    @PostMapping("/lab/request")
    public String create(Authentication auth,
                         @RequestParam(name = "labId", required = false) Long labId,
                         @RequestParam(name = "sampleType", required = false) String sampleType,
                         @RequestParam(name = "crop", required = false) String crop,
                         @RequestParam(name = "notes", required = false) String notes,
                         Model model) {
        if (!"FARMER".equals(role(auth))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        Map<String, String> errors = new LinkedHashMap<>();
        Optional<Lab> lab = labId == null ? Optional.empty() : labs.findById(labId);
        if (lab.isEmpty() || !"APPROVED".equals(lab.get().getStatus())) {
            errors.put("labId", msg("error.lab.choose"));
        }
        String sample = sampleType == null ? "" : sampleType.trim();
        if (!ReferenceData.SAMPLE_TYPES.contains(sample)) {
            errors.put("sampleType", msg("error.required"));
        }
        String cropCode = crop == null ? "" : crop.trim();
        if (!cropCode.isEmpty() && !ReferenceData.CROPS.contains(cropCode)) {
            errors.put("crop", msg("error.required"));
        }
        String noteText = notes == null ? "" : notes.trim();
        if (noteText.length() > 500) {
            errors.put("notes", msg("error.too.long"));
        }

        Long userId = currentUserId(auth);

        if (!errors.isEmpty()) {
            log.info("[LAB] Request rejected for user {}: {}", userId, errors.keySet());
            model.addAttribute("role", role(auth));
            model.addAttribute("hasProfile", profiles.findByUserId(userId).isPresent());
            model.addAttribute("labs", labs.findApproved());
            model.addAttribute("samples", ReferenceData.SAMPLE_TYPES);
            model.addAttribute("crops", ReferenceData.CROPS);
            model.addAttribute("districts", districts.findAll());
            model.addAttribute("districtName", null);
            model.addAttribute("districtFiltered", false);
            model.addAttribute("myRequests", requests.findingsForFarmer(userId));
            Map<String, Object> echo = new HashMap<>();
            echo.put("labId", labId);
            echo.put("sampleType", sample);
            echo.put("crop", cropCode);
            echo.put("notes", noteText);
            model.addAttribute("form", echo);
            model.addAttribute("errors", errors);
            return "pages/lab";
        }

        LabRequest r = new LabRequest();
        r.setFarmerId(userId);
        r.setLabId(labId);
        r.setSampleType(sample);
        r.setCrop(cropCode.isEmpty() ? null : cropCode);
        r.setNotes(noteText.isEmpty() ? null : noteText);
        r.setTrackingId(nextTrackingId());

        requests.insert(r);
        log.info("[LAB] Request {} created by user {} for lab {}", r.getTrackingId(), userId, labId);
        return "redirect:/lab?created=" + r.getTrackingId();
    }

    /**
     * URL     : GET /lab/request/{trackingId}
     * Method  : tracker()
     * Role    : the request's own farmer, its own lab, or the OWNER
     * A tracking id that is not yours gives a 404 - it does not reveal whether
     * somebody else's tracking id exists.
     */
    @GetMapping("/lab/request/{trackingId}")
    public String tracker(@PathVariable String trackingId, Authentication auth, Model model) {
        LabRequest r = requests.findByTrackingId(trackingId)
                .filter(req -> canSee(req, auth))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        model.addAttribute("request", r);
        model.addAttribute("history", requests.history(r.getId()));
        model.addAttribute("result", results.findByRequestId(r.getId()).orElse(null));
        model.addAttribute("reportReady",
                r.stage() == LabStatus.REPORT_READY || r.stage() == LabStatus.REPORT_COLLECTED);
        return "pages/lab-request";
    }

    /**
     * URL     : GET /lab/request/{trackingId}/report
     * Method  : downloadReport()
     * Role    : the request's own farmer, its own lab, or the OWNER
     * The PDF is written on first download and then re-used. The file lives
     * outside /static, so this method is the ONLY way to reach it.
     */
    @GetMapping("/lab/request/{trackingId}/report")
    public ResponseEntity<byte[]> downloadReport(@PathVariable String trackingId, Authentication auth) {
        LabRequest r = requests.findByTrackingId(trackingId)
                .filter(req -> canSee(req, auth))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (r.stage() != LabStatus.REPORT_READY && r.stage() != LabStatus.REPORT_COLLECTED) {
            // The lab has not finished the test, so there is nothing to download.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        LabResult result = results.findByRequestId(r.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        File file = reportService.fileFor(trackingId);
        if (!file.exists()) {
            file = reportService.generate(r, result);
            results.updatePdfPath(r.getId(), file.getAbsolutePath());
        }

        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            log.info("[LAB] Report {} downloaded by {}", trackingId, auth.getName());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename("report-" + trackingId + ".pdf").build());
            return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
        } catch (IOException e) {
            log.error("[LAB] Could not read the report for {}: {}", trackingId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /* ---------- helpers ---------- */

    /** A farmer sees their own request; a lab sees its queue; the owner sees all. */
    private boolean canSee(LabRequest r, Authentication auth) {
        String role = role(auth);
        if ("OWNER".equals(role)) {
            return true;
        }
        Long userId = currentUserId(auth);
        if ("LAB".equals(role)) {
            return labs.findByUserId(userId).map(l -> l.getId().equals(r.getLabId())).orElse(false);
        }
        return r.getFarmerId().equals(userId);
    }

    /** TRK-1004, TRK-1005 ...  (checked against the table before it is used). */
    private String nextTrackingId() {
        int n = 1000 + requests.countAll() + 1;
        String candidate = String.format("TRK-%04d", n);
        while (requests.findByTrackingId(candidate).isPresent()) {
            n++;
            candidate = String.format("TRK-%04d", n);
        }
        return candidate;
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

    private String msg(String key) {
        // Validation text must appear in the language the farmer is reading.
        return messages.getMessage(key, null, key, LocaleContextHolder.getLocale());
    }
}
