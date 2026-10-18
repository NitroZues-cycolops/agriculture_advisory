package com.precisionfarming.advisory_service.service;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import com.precisionfarming.advisory_service.model.LabRequest;
import com.precisionfarming.advisory_service.model.LabResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Builds the soil-test PDF with OpenPDF (com.github.librepdf:openpdf).
 *
 * WHERE THE FILE GOES: `${pfas.uploads.dir}/reports/report-<trackingId>.pdf`.
 * That folder is OUTSIDE src/main/resources/static, so the file can never be
 * fetched straight off the web. The only way to read it is
 * LabController#downloadReport, which checks ownership first.
 *
 * WHY THE PDF IS ALWAYS IN ENGLISH: OpenPDF's built-in fonts (Helvetica etc.)
 * have no Devanagari glyphs, so a Hindi or Marathi label would be printed as
 * empty boxes. The labels below are therefore resolved with Locale.ENGLISH from
 * the same message files, and the translated, fully tri-lingual version of the
 * same report is the HTML page in the browser. Nothing here is hard-coded prose.
 */
@Service
public class LabReportService {

    private static final Logger log = LoggerFactory.getLogger(LabReportService.class);

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    /* PDF labels, all taken from the message files (English bundle) */
    private static final String K_TITLE   = "lab.report.title";
    private static final String K_TRACK   = "lab.tracking";
    private static final String K_FARMER  = "lab.form.farmer";
    private static final String K_LAB     = "lab.field.lab";
    private static final String K_DIST    = "lab.field.district";
    private static final String K_SAMPLE  = "lab.form.sample";
    private static final String K_CROP    = "lab.form.crop";
    private static final String K_NOTES   = "lab.form.notes";
    private static final String K_STATUS  = "lab.field.status";
    private static final String K_ISSUED  = "lab.report.issued";
    private static final String K_RESULTS = "lab.report.results";
    private static final String K_PH      = "lab.result.ph";
    private static final String K_N       = "lab.result.n";
    private static final String K_P       = "lab.result.p";
    private static final String K_K       = "lab.result.k";
    private static final String K_OC      = "lab.result.oc";
    private static final String K_EC      = "lab.result.ec";
    private static final String K_REMARKS = "lab.result.remarks";
    private static final String K_COLLECT = "lab.report.collect_note";
    private static final String K_SAMPLE_NOTE = "lab.report.sample_note";

    private final MessageSource messages;
    private final String uploadDir;

    public LabReportService(MessageSource messages,
                            @Value("${pfas.uploads.dir:uploads}") String uploadDir) {
        this.messages = messages;
        this.uploadDir = uploadDir;
    }

    /**
     * Writes (or rewrites) the PDF for one request and returns the file.
     * The caller stores `file.getAbsolutePath()` in lab_results.pdf_path.
     */
    public File generate(LabRequest request, LabResult result) {
        File dir = new File(uploadDir, "reports");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Cannot create report folder: " + dir.getAbsolutePath());
        }
        File file = new File(dir, "report-" + request.getTrackingId() + ".pdf");

        Document document = new Document();
        try (FileOutputStream out = new FileOutputStream(file)) {
            PdfWriter.getInstance(document, out);
            document.open();

            Font h1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
            Font h2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
            Font body = FontFactory.getFont(FontFactory.HELVETICA, 11);

            document.add(new Paragraph(t(K_TITLE), h1));
            document.add(new Paragraph(t(K_ISSUED) + ": " + STAMP.format(LocalDateTime.now()), body));
            document.add(new Paragraph(" "));

            document.add(new Paragraph(t(K_TRACK) + ": " + request.getTrackingId(), body));
            document.add(new Paragraph(t(K_FARMER) + ": " + safe(request.getFarmerName()), body));
            document.add(new Paragraph(t(K_LAB) + ": " + safe(request.getLabName()), body));
            document.add(new Paragraph(t(K_DIST) + ": " + safe(request.getLabDistrict()), body));
            document.add(new Paragraph(t(K_SAMPLE) + ": " + t("sample." + lower(request.getSampleType())), body));
            document.add(new Paragraph(t(K_CROP) + ": " + t("crop." + lower(request.getCrop())), body));
            document.add(new Paragraph(t(K_STATUS) + ": " + t(request.stage().messageKey()), body));
            if (request.getNotes() != null && !request.getNotes().isBlank()) {
                document.add(new Paragraph(t(K_NOTES) + ": " + request.getNotes(), body));
            }
            document.add(new Paragraph(" "));

            document.add(new Paragraph(t(K_RESULTS), h2));
            document.add(new Paragraph(t(K_PH) + ": " + num(result.getPh()), body));
            document.add(new Paragraph(t(K_N) + ": " + num(result.getNitrogen()), body));
            document.add(new Paragraph(t(K_P) + ": " + num(result.getPhosphorus()), body));
            document.add(new Paragraph(t(K_K) + ": " + num(result.getPotassium()), body));
            document.add(new Paragraph(t(K_OC) + ": " + num(result.getOrganicCarbon()), body));
            document.add(new Paragraph(t(K_EC) + ": " + num(result.getElectricalConductivity()), body));
            document.add(new Paragraph(t(K_REMARKS) + ": "
                    + (result.getRemarks() == null || result.getRemarks().isBlank()
                        ? "-" : result.getRemarks()), body));
            document.add(new Paragraph(" "));

            Paragraph note = new Paragraph(t(K_COLLECT), body);
            note.setAlignment(Element.ALIGN_LEFT);
            document.add(note);

            if (request.getNotes() != null && request.getNotes().contains("SAMPLE")) {
                document.add(new Paragraph(t(K_SAMPLE_NOTE), body));
            }

            document.close();
            log.info("[LAB] Report written for {} ({} bytes)", request.getTrackingId(), file.length());
            return file;
        } catch (Exception e) {
            document.close();
            throw new IllegalStateException("Could not write the PDF report: " + e.getMessage(), e);
        }
    }

    /** Where a report WOULD be written, without creating it. */
    public File fileFor(String trackingId) {
        return new File(new File(uploadDir, "reports"), "report-" + trackingId + ".pdf");
    }

    private String t(String key) {
        return messages.getMessage(key, null, key, Locale.ENGLISH);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String num(Double value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private static String lower(String value) {
        return value == null ? "other" : value.trim().toLowerCase(Locale.ROOT);
    }
}
