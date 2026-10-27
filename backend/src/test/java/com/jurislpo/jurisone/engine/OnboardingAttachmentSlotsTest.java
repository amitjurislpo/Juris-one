package com.jurislpo.jurisone.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.jurislpo.jurisone.engine.OnboardingAttachmentSlots.Education;
import com.jurislpo.jurisone.engine.OnboardingAttachmentSlots.Employment;
import com.jurislpo.jurisone.engine.OnboardingAttachmentSlots.OnboardingData;
import com.jurislpo.jurisone.engine.OnboardingAttachmentSlots.Slot;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OnboardingAttachmentSlotsTest {

    static final UUID EDUCATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID EMPLOYMENT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void fixedSlotsForAnEmployeeWithNoEmploymentAndNoPassport() {
        List<Slot> slots = OnboardingAttachmentSlots.slotsFor(new OnboardingData(List.of(new Education(EDUCATION, "B.Com")), List.of(), false));
        assertThat(slots).extracting(Slot::slotKey).containsExactly("address_permanent", "address_present", "qualification:" + EDUCATION, "bank", "pan", "aadhaar");
        assertThat(slots).allMatch(Slot::isMandatory);
        assertThat(slots.get(0).title()).isEqualTo("Address : Permanent Address");
        assertThat(slots.get(1).title()).isEqualTo("Address : Present Address");
        assertThat(slots.get(0).tooltip()).isEqualTo("For proof attach one of the following aadhaar card/ driving license/ passport/ electricity bill, water bill/ bank pass book.");
        assertThat(slots.get(2).title()).isEqualTo("Qualification: B.Com");
        assertThat(slots.get(2).tooltip()).isEqualTo("For proof attach certificate (mandatory) and marksheet (mandatory).");
        assertThat(slots.get(2).group()).isEqualTo("Personal Information");
        assertThat(slots.get(3).title()).isEqualTo("Bank Account Details");
        assertThat(slots.get(3).tooltip()).isEqualTo("For proof attach Passbook/cancelled Check");
        assertThat(slots.get(4).title()).isEqualTo("Permanent Account Number (PAN)");
        assertThat(slots.get(5).group()).isEqualTo("Accounts & Statutory");
    }

    @Test
    void employmentSlotIsNotMandatoryAndPassportAppearsOnlyWithANumber() {
        List<Slot> slots = OnboardingAttachmentSlots.slotsFor(new OnboardingData(List.of(), List.of(new Employment(EMPLOYMENT, "Lex & Co")), true));
        Slot employment = slots.stream().filter(slot -> slot.slotKey().startsWith("employment:")).findFirst().orElseThrow();
        assertThat(employment.slotKey()).isEqualTo("employment:" + EMPLOYMENT);
        assertThat(employment.title()).isEqualTo("Previous Employment: Lex & Co");
        assertThat(employment.group()).isEqualTo("Previous Employment");
        assertThat(employment.isMandatory()).isFalse();
        assertThat(employment.tooltip()).startsWith("For proof attach latest pay slip (mandatory)");
        Slot passport = slots.get(slots.size() - 1);
        assertThat(passport.slotKey()).isEqualTo("passport");
        assertThat(passport.isMandatory()).isTrue();
    }

    @Test
    void missingMandatorySlotsIgnoresOptionalOnes() {
        List<Slot> slots = OnboardingAttachmentSlots.slotsFor(new OnboardingData(List.of(), List.of(new Employment(EMPLOYMENT, "X")), false));
        assertThat(OnboardingAttachmentSlots.missingMandatorySlots(slots, Set.of("address_permanent", "pan")))
                .extracting(Slot::slotKey).containsExactly("address_present", "bank", "aadhaar");
        assertThat(OnboardingAttachmentSlots.missingMandatorySlots(slots, Set.of("address_permanent", "address_present", "bank", "pan", "aadhaar"))).isEmpty();
    }

    @Test
    void folderNameIsPathSafe() {
        assertThat(OnboardingAttachmentSlots.folderName("qualification:" + EDUCATION)).isEqualTo("qualification-" + EDUCATION);
        assertThat(OnboardingAttachmentSlots.folderName("../x:y")).doesNotContain("/", ".", ":");
    }
}
