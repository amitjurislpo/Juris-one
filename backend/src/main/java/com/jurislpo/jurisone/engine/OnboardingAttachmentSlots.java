package com.jurislpo.jurisone.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The upload slots of onboarding step 6, generated from the data of steps 1 to 3 (spec "Step 6: Attachments").
 * Pure: no database, no clock.
 */
public final class OnboardingAttachmentSlots {

    private OnboardingAttachmentSlots() {}

    public static final String GROUP_PERSONAL = "Personal Information";
    public static final String GROUP_EMPLOYMENT = "Previous Employment";
    public static final String GROUP_ACCOUNTS = "Accounts & Statutory";

    static final String ADDRESS_TOOLTIP = "For proof attach one of the following aadhaar card/ driving license/ passport/ electricity bill, water bill/ bank pass book.";
    static final String QUALIFICATION_TOOLTIP = "For proof attach certificate (mandatory) and marksheet (mandatory).";
    static final String EMPLOYMENT_TOOLTIP = "For proof attach latest pay slip (mandatory) and one of the following offer letter/appointment letter/ company relieving/ experience letter/ form 16.";
    static final String BANK_TOOLTIP = "For proof attach Passbook/cancelled Check";

    /** One upload slot. */
    public record Slot(String slotKey, String group, String title, String tooltip, boolean isMandatory) {}

    /** What the slots depend on: the education and employment entries (id plus the text used in the title) and whether a passport number was entered. */
    public record OnboardingData(List<Education> educations, List<Employment> employments, boolean hasPassportNumber) {

        public OnboardingData {
            educations = educations == null ? List.of() : List.copyOf(educations);
            employments = employments == null ? List.of() : List.copyOf(employments);
        }
    }

    public record Education(UUID id, String qualification) {}

    public record Employment(UUID id, String companyName) {}

    /** The slots in display order. */
    public static List<Slot> slotsFor(OnboardingData data) {
        List<Slot> slots = new ArrayList<>();
        slots.add(new Slot("address_permanent", GROUP_PERSONAL, "Address : Permanent Address", ADDRESS_TOOLTIP, true));
        slots.add(new Slot("address_present", GROUP_PERSONAL, "Address : Present Address", ADDRESS_TOOLTIP, true));
        for (Education education : data.educations()) {
            slots.add(new Slot("qualification:" + education.id(), GROUP_PERSONAL, "Qualification: " + textOrEmpty(education.qualification()), QUALIFICATION_TOOLTIP, true));
        }
        for (Employment employment : data.employments()) {
            slots.add(new Slot("employment:" + employment.id(), GROUP_EMPLOYMENT, "Previous Employment: " + textOrEmpty(employment.companyName()), EMPLOYMENT_TOOLTIP, false));
        }
        slots.add(new Slot("bank", GROUP_ACCOUNTS, "Bank Account Details", BANK_TOOLTIP, true));
        slots.add(new Slot("pan", GROUP_ACCOUNTS, "Permanent Account Number (PAN)", null, true));
        slots.add(new Slot("aadhaar", GROUP_ACCOUNTS, "Aadhaar", null, true));
        if (data.hasPassportNumber()) slots.add(new Slot("passport", GROUP_ACCOUNTS, "Passport", null, true));
        return List.copyOf(slots);
    }

    /** The mandatory slots that have no file yet, in display order. */
    public static List<Slot> missingMandatorySlots(List<Slot> slots, Set<String> uploadedSlotKeys) {
        return slots.stream().filter(slot -> slot.isMandatory() && !uploadedSlotKeys.contains(slot.slotKey())).toList();
    }

    /** The slot key made safe to use as a folder name (the colon is not allowed in Windows paths). */
    public static String folderName(String slotKey) {
        return slotKey.replace(':', '-').replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String textOrEmpty(String text) {
        return text == null ? "" : text.trim();
    }
}
