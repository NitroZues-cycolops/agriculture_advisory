package com.precisionfarming.advisory_service.service;

import com.precisionfarming.advisory_service.repository.VerificationDao;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * The random one-time handle used in the /verify?h=... link (PART B).
 *
 * Kept in its own tiny service so BOTH the registration controllers (which
 * create the handle) and the verify controller (which resolves it) use the same
 * rule, and neither has to know the table or the token format.
 *
 * A handle is a 32-character random hex string that stays valid for
 * verification.handle.hours (default 24). It only ever identifies the account
 * on the verify pages - it is not a login credential.
 */
@Service
public class VerificationSessionService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final VerificationDao dao;
    private final long handleHours;

    public VerificationSessionService(VerificationDao dao,
                                      @Value("${verification.handle.hours:24}") long handleHours) {
        this.dao = dao;
        this.handleHours = handleHours;
    }

    /** Creates a fresh handle for an account and returns it. */
    public String create(Long userId) {
        dao.deleteExpiredSessions();          // cheap housekeeping on every use
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String handle = HexFormat.of().formatHex(bytes);
        dao.createSession(handle, userId, LocalDateTime.now().plusHours(handleHours));
        return handle;
    }

    /** The account behind a handle, or empty when the handle is unknown/expired. */
    public Optional<Long> resolve(String handle) {
        if (handle == null || handle.isBlank()) {
            return Optional.empty();
        }
        return dao.findSessionUser(handle.trim());
    }

    /** Removes every handle of an account (called when the flow is finished). */
    public void clear(Long userId) {
        dao.deleteSessionsForUser(userId);
    }
}
