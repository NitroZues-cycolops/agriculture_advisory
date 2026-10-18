package com.precisionfarming.advisory_service.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * SMS gateway OTP sender (PART B) - PLACEHOLDER, selected by `otp.provider=sms`.
 *
 * Nothing is sent yet on purpose: the app must run without any API key.
 * This class exists to show WHERE a real provider plugs in, and it documents
 * the two decisions a real implementation must make.
 *
 * TO WIRE A REAL PROVIDER (for example MSG91 or Twilio):
 *
 * TODO 1 - add the credentials as properties (never hard-code a key):
 *            otp.sms.api-key=...
 *            otp.sms.sender-id=...
 *            otp.sms.template-id=...
 *          and read them here with @Value, or (cleaner) register a
 *          RestClient.Builder bean and inject it into this constructor.
 *
 * TODO 2 - replace the body of send() with the provider's HTTP call, e.g.
 *            POST https://api.msg91.com/api/v5/flow/
 *            Authorization: <api-key>
 *            { "template_id": ..., "sender": ..., "mobiles": "91" + phone,
 *              "OTP": code }
 *          Wrap it in try/catch: send() must never throw, otherwise a gateway
 *          outage would break registration. Log the status code and the
 *          provider's request id so a support call can be traced.
 *
 * TODO 3 - keep the contract used by OtpService: the caller has ALREADY stored
 *          the hashed code, so this method only delivers it.
 */
@Service
public class SmsGatewayOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(SmsGatewayOtpSender.class);

    @Override
    public String provider() {
        return "sms";
    }

    @Override
    public boolean demo() {
        return false;
    }

    @Override
    public void send(String phone, String code) {
        // Deliberately a no-op: no provider is configured, and throwing here
        // would break registration. The code is in the log for the demo.
        log.warn("[OTP][SMS] otp.provider=sms but no gateway is wired yet. "
                + "Implement SmsGatewayOtpSender.send() - the code for {} was NOT delivered.", phone);
    }
}
