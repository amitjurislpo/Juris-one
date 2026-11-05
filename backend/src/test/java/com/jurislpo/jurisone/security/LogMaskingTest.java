package com.jurislpo.jurisone.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Log masking")
class LogMaskingTest {

    @Test
    @DisplayName("hides email addresses but keeps the domain")
    void emails() {
        assertThat(LogMasking.mask("send failed for meera.iyer@example.com and a@b.co")).isEqualTo("send failed for m***@example.com and a***@b.co");
    }

    @Test
    @DisplayName("hides PAN, grouped and plain Aadhaar, bank and phone numbers down to the last four")
    void identityNumbers() {
        assertThat(LogMasking.mask("pan ABCDE1234F")).isEqualTo("pan XXXXXX234F");
        assertThat(LogMasking.mask("aadhaar 2345 6789 1234 and 234567891234")).isEqualTo("aadhaar XXXX XXXX 1234 and XXXX1234");
        assertThat(LogMasking.mask("account 123456789012345 phone +919876543210")).isEqualTo("account XXXX2345 phone +XXXX3210");
    }

    @Test
    @DisplayName("hides tokens")
    void tokens() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJl";
        assertThat(LogMasking.mask("token " + jwt)).isEqualTo("token eyJ***");
        assertThat(LogMasking.mask("Authorization: Bearer abc.def-123")).isEqualTo("Authorization: Bearer ***");
    }

    @Test
    @DisplayName("hides the values in PostgreSQL error details")
    void databaseDetails() {
        assertThat(LogMasking.mask("ERROR: null value in column \"x\"\n  Detail: Failing row contains (1, Meera Iyer, ABCDE1234F, 5000000)."))
                .isEqualTo("ERROR: null value in column \"x\"\n  Detail: Failing row contains (***)");
        assertThat(LogMasking.mask("Detail: Key (email)=(meera@example.com) already exists.")).isEqualTo("Detail: Key (email)=(***) already exists.");
    }

    @Test
    @DisplayName("leaves ordinary log text, ids, dates and short numbers alone")
    void ordinaryText() {
        String uuid = UUID.randomUUID().toString();
        String text = "[api] GET /api/v1/people/" + uuid + " took 123 ms at 2026-11-05T10:15:30.123456Z, 12 rows, JC-058, version 1.2.3";
        assertThat(LogMasking.mask(text)).isEqualTo(text);
        assertThat(LogMasking.mask(null)).isNull();
        assertThat(LogMasking.mask("")).isEmpty();
    }
}
