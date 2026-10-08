package com.jurislpo.jurisone.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The demo seed's deterministic values match prisma/seed.ts (same fake IDs and punch times for the same employee/day). */
class SeedServiceTest {

    @Test
    void fakeIdsMatchTheOriginalSeed() {
        SeedService.FakeIds in = SeedService.fakeIds("JC-074", true);
        assertThat(in.phone()).isEqualTo("+91 98006 49946");
        assertThat(in.pan()).isEqualTo("VAFPL1094R");
        assertThat(in.aadhaar()).isEqualTo("725445186074");
        assertThat(in.uan()).isEqualTo("100900327154");
        assertThat(in.bank()).isEqualTo("501005755498");
        assertThat(in.address()).isEqualTo("84, Sector 15, Noida, Uttar Pradesh 201301");

        SeedService.FakeIds us = SeedService.fakeIds("JC-090", false);
        assertThat(us.phone()).isEqualTo("+1 (212) 555-3330");
        assertThat(us.pan()).isNull();
        assertThat(us.aadhaar()).isNull();
        assertThat(us.address()).isEqualTo("190 Park Avenue, New York, NY 10017");
    }

    @Test
    void punchHashMatchesTheOriginalSeed() {
        assertThat(SeedService.hash("JC-0742026-10-05")).isEqualTo(51034);
        assertThat(SeedService.hash("JC-0582026-09-15")).isEqualTo(45258);
    }
}
