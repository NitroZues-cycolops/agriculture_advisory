package com.precisionfarming.advisory_service.model;

import java.util.List;

/**
 * The five stages a lab test request moves through, in order.
 *
 * WHY AN ENUM INSTEAD OF FREE TEXT IN THE DB: the column `lab_requests.status`
 * stores the enum NAME ("REQUEST_SUBMITTED"), while the page shows the message
 * key ("lab.status.submitted"). That way the tracker is translated in English,
 * Hindi and Marathi without storing a sentence in the database (rule 4).
 *
 * The lab dashboard can only ever move a request FORWARD by one stage, because
 * the "next" button asks this enum what comes after the current value.
 */
public enum LabStatus {

    REQUEST_SUBMITTED("lab.status.submitted"),
    SAMPLE_RECEIVED("lab.status.received"),
    TESTING_IN_PROGRESS("lab.status.testing"),
    REPORT_READY("lab.status.ready"),
    REPORT_COLLECTED("lab.status.collected");

    private final String messageKey;

    LabStatus(String messageKey) {
        this.messageKey = messageKey;
    }

    /** The message key of this stage, e.g. "lab.status.received". */
    public String messageKey() {
        return messageKey;
    }

    /** All stages in order, for drawing the CSS tracker. */
    public static List<LabStatus> ordered() {
        return List.of(values());
    }

    /** The stage after this one, or null when the request is finished. */
    public LabStatus next() {
        int i = ordinal() + 1;
        return i < values().length ? values()[i] : null;
    }

    /** Parses a stored status; anything unknown is treated as the first stage. */
    public static LabStatus parse(String stored) {
        if (stored != null) {
            for (LabStatus s : values()) {
                if (s.name().equalsIgnoreCase(stored)) {
                    return s;
                }
            }
        }
        return REQUEST_SUBMITTED;
    }

    /** True when `step` has already happened for a request sitting at this stage. */
    public boolean isDoneAt(LabStatus current) {
        return this.ordinal() <= current.ordinal();
    }
}
