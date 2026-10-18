package com.precisionfarming.advisory_service.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DEMO OTP sender (PART B) - the default when `otp.provider=demo`.
 *
 * NO SMS IS SENT. The code is written to the application log and kept in
 * memory so the verification page can show it inside a clearly labelled
 * "DEMO MODE: no SMS is sent" box. This is what makes the whole verification
 * flow demonstrable in a viva with no API key and no phone.
 *
 * The in-memory map holds only the codes this JVM run issued (bounded to 200
 * entries, oldest dropped first), so a restart simply means "ask for a new
 * code" - the stored hash is unaffected.
 */
@Service
public class DemoOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(DemoOtpSender.class);
    private static final int MAX_KEPT = 200;

    private final Map<String, String> lastCodeByPhone = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_KEPT;
        }
    };

    @Override
    public String provider() {
        return "demo";
    }

    @Override
    public boolean demo() {
        return true;
    }

    @Override
    public void send(String phone, String code) {
        synchronized (lastCodeByPhone) {
            lastCodeByPhone.put(phone, code);
        }
        log.info("[OTP][DEMO] no SMS is sent. Verification code for {} is {}", phone, code);
    }

    /** The newest code issued for this phone in this run, or null. */
    public String lastCode(String phone) {
        synchronized (lastCodeByPhone) {
            return lastCodeByPhone.get(phone);
        }
    }
}
