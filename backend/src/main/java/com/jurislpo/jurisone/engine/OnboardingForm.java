package com.jurislpo.jurisone.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/**
 * The data of the employee onboarding wizard, exactly as the API sends and receives it (references/ONBOARDING_WIZARD_API.md).
 * Dates are ISO strings ({@code YYYY-MM-DD}); the salary is in rupees. Bank account number, PAN, Aadhaar and UAN are masked on the way out
 * and {@code null} on the way in means "keep the stored value".
 */
public final class OnboardingForm {
    private OnboardingForm() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Personal(String bloodGroup, String personalEmail, String fatherName, String maritalStatus, String marriageDate, String spouseName, String countryOfOrigin,
                           String nationality, Boolean isInternationalEmployee, Boolean isPhysicallyChallenged, String disabilityType, String gender, String dateOfBirth,
                           BigDecimal heightCm, BigDecimal weightKg, String identificationMark, String religion, String caste, String hobby) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Address(String line1, String line2, String line3, String city, String district, String state, String pin, String country, String phone) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PresentAddress(String line1, String line2, String line3, String city, String district, String state, String pin, String country, String phone,
                                 Boolean isSameAsPermanent) {

        public Address asAddress() {
            return new Address(line1, line2, line3, city, district, state, pin, country, phone);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmergencyContact(String name, String mobile, String relationship) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Education(String id, String qualification, Integer fromYear, Integer toYear, String institute, String gradeOrPercentage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Course(String id, String courseName, String institute, Integer fromYear, Integer toYear) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Employment(String id, String companyName, String designation, BigDecimal lastDrawnSalary, String fromDate, String toDate, String reasonForLeaving,
                             String companyAddress) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Bank(String country, String bankAccountNumber, Boolean hasBankAccountNumber, String ifsc, String bankName, String branch, String branchAddress,
                       String nameAsPerBank, String accountType) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Statutory(String previousPfNumber, String uan, Boolean hasUan, String pan, Boolean hasPan, String namePerPan, String aadhaar, Boolean hasAadhaar,
                            String namePerAadhaar, String passportNumber, String namePerPassport, String passportExpiry) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Guardian(String name, String relationship, String line1, String line2, String line3, String city, String state, String country, String pin, String mobile,
                           String email) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FamilyMember(String id, String name, String relationship, String dateOfBirth, String gender, String bloodGroup, String nationality, Boolean isMinor,
                               Boolean hasMentalIllness, Guardian guardian) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NomineeShare(String familyMemberId, Integer percentage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Nominee(String familyMemberId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Nominations(List<NomineeShare> epf, Nominee eps, Nominee esi, List<NomineeShare> gratuity) {}

    // ───────── step bodies (PUT /api/onboarding/mine/steps/{step}) ─────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PersonalStep(Personal personal, Address permanentAddress, PresentAddress presentAddress, EmergencyContact emergencyContact, List<Education> education,
                               List<Course> courses) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmploymentStep(List<Employment> employments) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatutoryStep(Bank bank, Statutory statutory) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FamilyStep(List<FamilyMember> familyMembers) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NominationStep(Nominations nominations) {}

    /** Everything an employee has entered so far; the secrets here are the real values (used by validation and never sent out as is). */
    public record Data(Personal personal, Address permanentAddress, PresentAddress presentAddress, EmergencyContact emergencyContact, List<Education> education,
                       List<Course> courses, List<Employment> employments, Bank bank, Statutory statutory, List<FamilyMember> familyMembers, Nominations nominations) {}
}
