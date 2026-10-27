package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.engine.OnboardingAttachmentSlots.OnboardingData;
import java.util.Optional;
import java.util.UUID;

/**
 * What the attachment and secrets endpoints need to know about the signed-in employee's own onboarding.
 * Implemented by the wizard service, which owns the onboarding tables.
 */
public interface OwnOnboardingSource {

    /**
     * @param onboardingId id in {@code employee_onboardings}
     * @param employeeCode e.g. JC074 (used in the storage path)
     * @param isSubmitted status is SUBMITTED (writes are refused)
     * @param data education / employment ids and the passport flag, from the saved steps 1 to 3
     * @param encryptedBankAccountNumber stored PII ciphertext (or null), as written with {@code PiiCrypto.encrypt}
     */
    record OwnOnboarding(UUID onboardingId, String employeeCode, boolean isSubmitted, OnboardingData data,
                         String encryptedBankAccountNumber, String encryptedPan, String encryptedAadhaar) {}

    /** The employee's own onboarding; empty when onboarding is not required for this employee (answered as 404). */
    Optional<OwnOnboarding> findOwn(UUID employeeId);
}
