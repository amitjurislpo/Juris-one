package com.jurislpo.jurisone.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuditTrailMaskNameTest {

    @Test
    void keepsOnlyTheFirstLetterOfEachWord() {
        assertThat(AuditTrail.maskName("Priya Sharma")).isEqualTo("P*** S***");
        assertThat(AuditTrail.maskName("  Meera   Joshi-Rao ")).isEqualTo("M*** J***");
        assertThat(AuditTrail.maskName("Élodie")).isEqualTo("É***");
        assertThat(AuditTrail.maskName("अमित कुमार")).isEqualTo("अ*** क***");
        assertThat(AuditTrail.maskName("Senior Software Engineer")).isEqualTo("S*** S*** E***");
    }

    @Test
    void emptyOrMissingStaysEmpty() {
        assertThat(AuditTrail.maskName(null)).isEmpty();
        assertThat(AuditTrail.maskName("   ")).isEmpty();
    }
}
