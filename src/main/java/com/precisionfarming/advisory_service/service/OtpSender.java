package com.precisionfarming.advisory_service.service;

/**
 * Where a one-time code is actually delivered (PART B).
 *
 * Two implementations are registered as Spring beans:
 *   DemoOtpSender          (otp.provider=demo, the default) - no SMS at all;
 *                          the code goes to the log and is shown on the page
 *                          inside a clearly labelled DEMO MODE box.
 *   SmsGatewayOtpSender    (otp.provider=sms) - placeholder for a real provider.
 *
 * {@link OtpService} picks the implementation whose {@link #provider()} matches
 * the `otp.provider` property, so nothing else in the app needs to know which
 * one is active.
 */
public interface OtpSender {

    /** Value of `otp.provider` that selects this sender ("demo" or "sms"). */
    String provider();

    /** True when no real message leaves the machine (the code may be shown). */
    boolean demo();

    /**
     * Delivers the code. Implementations must not throw: a delivery problem is
     * logged and the flow continues, because the OTP row is already stored.
     */
    void send(String phone, String code);
}
