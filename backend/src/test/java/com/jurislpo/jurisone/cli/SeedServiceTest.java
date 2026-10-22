package com.jurislpo.jurisone.cli;

import com.jurislpo.jurisone.service.SeedService;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The demo seed's deterministic values match prisma/seed.ts (same fake IDs and punch times for the same employee/day). */
class SeedServiceTest {

    @Test
    void fakeIdsMatchTheOriginalSeed() {
        SeedService.FakeIds indianIds = SeedService.fakeIds("JC-074", true);
        assertThat(indianIds.phone()).isEqualTo("+91 98006 49946");
        assertThat(indianIds.pan()).isEqualTo("VAFPL1094R");
        assertThat(indianIds.aadhaar()).isEqualTo("725445186074");
        assertThat(indianIds.uan()).isEqualTo("100900327154");
        assertThat(indianIds.bank()).isEqualTo("501005755498");
        assertThat(indianIds.address()).isEqualTo("84, Sector 15, Noida, Uttar Pradesh 201301");

        SeedService.FakeIds americanIds = SeedService.fakeIds("JC-090", false);
        assertThat(americanIds.phone()).isEqualTo("+1 (212) 555-3330");
        assertThat(americanIds.pan()).isNull();
        assertThat(americanIds.aadhaar()).isNull();
        assertThat(americanIds.address()).isEqualTo("190 Park Avenue, New York, NY 10017");
    }

    @Test
    void punchHashMatchesTheOriginalSeed() {
        assertThat(SeedService.hash("JC-0742026-10-05")).isEqualTo(51034);
        assertThat(SeedService.hash("JC-0582026-09-15")).isEqualTo(45258);
    }
}
