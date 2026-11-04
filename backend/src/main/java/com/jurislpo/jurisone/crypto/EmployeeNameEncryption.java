package com.jurislpo.jurisone.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stage 1 of encrypting employee names and designations: every statement that writes {@code full_name}, {@code first_name},
 * {@code last_name} or {@code designation} on {@code employees} also writes the encrypted copy ({@code *_enc}) from here.
 * Reads stay on the plaintext columns in this stage. Switch: {@code jurisone.security.encrypt-names.dual-write}
 * (code default false; true in dev, which the tests use; sqe, uat and prd set it from ENCRYPT_NAMES_DUAL_WRITE). When off, the encrypted columns are written as NULL so that
 * they never hold a stale value, and the backfill ({@code encrypt-existing}) fills them later.
 */
@Component
public class EmployeeNameEncryption {

    private final PiiCrypto piiCrypto;
    private final boolean isDualWriteEnabled;

    public EmployeeNameEncryption(PiiCrypto piiCrypto, @Value("${jurisone.security.encrypt-names.dual-write:false}") boolean isDualWriteEnabled) {
        this.piiCrypto = piiCrypto;
        this.isDualWriteEnabled = isDualWriteEnabled;
    }

    /** The encrypted copy of the value, or null when the flag is off or the value is empty. */
    public String encrypt(String plain) {
        return isDualWriteEnabled ? piiCrypto.encrypt(plain) : null;
    }
}
