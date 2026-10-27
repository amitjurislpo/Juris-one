package com.jurislpo.jurisone.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The sections of the onboarding form that HR can flag when it sends the form back (keys as in the API, titles as the employee sees them). */
public final class OnboardingReview {
    private OnboardingReview() {}

    /** Section key to its title, in the order of the wizard. */
    public static final Map<String, String> SECTIONS = sections();

    private static Map<String, String> sections() {
        Map<String, String> sections = new LinkedHashMap<>();
        sections.put("PERSONAL", "Personal information");
        sections.put("ADDRESS", "Address");
        sections.put("QUALIFICATIONS", "Qualifications");
        sections.put("EMPLOYMENT", "Previous employment");
        sections.put("ACCOUNTS", "Accounts & statutory");
        sections.put("FAMILY", "Family details");
        sections.put("NOMINATIONS", "Nomination details");
        sections.put("ATTACHMENTS", "Attachments");
        return Collections.unmodifiableMap(sections);
    }

    /** The flaggable sections that live on one wizard step (step 1 holds personal, address and qualifications): saving the step corrects them. */
    public static List<String> sectionsOfStep(int step) {
        return switch (step) {
            case 1 -> List.of("PERSONAL", "ADDRESS", "QUALIFICATIONS");
            case 2 -> List.of("EMPLOYMENT");
            case 3 -> List.of("ACCOUNTS");
            case 4 -> List.of("FAMILY");
            case 5 -> List.of("NOMINATIONS");
            case 6 -> List.of("ATTACHMENTS");
            default -> List.of();
        };
    }
}
