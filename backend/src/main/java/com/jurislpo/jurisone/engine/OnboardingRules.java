package com.jurislpo.jurisone.engine;

import com.jurislpo.jurisone.engine.OnboardingForm.Address;
import com.jurislpo.jurisone.engine.OnboardingForm.Bank;
import com.jurislpo.jurisone.engine.OnboardingForm.Course;
import com.jurislpo.jurisone.engine.OnboardingForm.Education;
import com.jurislpo.jurisone.engine.OnboardingForm.EmergencyContact;
import com.jurislpo.jurisone.engine.OnboardingForm.Employment;
import com.jurislpo.jurisone.engine.OnboardingForm.FamilyMember;
import com.jurislpo.jurisone.engine.OnboardingForm.Guardian;
import com.jurislpo.jurisone.engine.OnboardingForm.Nominations;
import com.jurislpo.jurisone.engine.OnboardingForm.NomineeShare;
import com.jurislpo.jurisone.engine.OnboardingForm.Personal;
import com.jurislpo.jurisone.engine.OnboardingForm.PresentAddress;
import com.jurislpo.jurisone.engine.OnboardingForm.Statutory;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The validation of the onboarding wizard, step by step (spec §10.1 and the end of references/ONBOARDING_WIZARD_API.md). Each method
 * returns the problems as {@code JSON path -> English message}; an empty map means the step is valid. The paths are those of the step body,
 * e.g. {@code personal.bloodGroup} or {@code education[1].institute}.
 */
public final class OnboardingRules {
    private OnboardingRules() {}

    public static final List<String> BLOOD_GROUPS = List.of("A +ve", "A -ve", "B +ve", "B -ve", "AB +ve", "AB -ve", "O +ve", "O -ve");
    public static final List<String> MARITAL_STATUSES = List.of("Single", "Married", "Divorced", "Widowed");
    public static final List<String> NATIONALITIES = List.of("Indian", "Others");
    public static final List<String> GENDERS = List.of("Male", "Female", "Others");
    public static final List<String> DISABILITY_TYPES = List.of("Locomotor", "Visual", "Hearing", "Speech", "Intellectual", "Multiple", "Other");
    public static final List<String> RELIGIONS = List.of("Hindu", "Muslim", "Christian", "Sikh", "Buddhist", "Jain", "Parsi", "Other", "Prefer not to say");
    public static final List<String> EMERGENCY_RELATIONSHIPS = List.of("Brother", "Daughter", "Father", "Husband", "Mother", "Sister", "Son", "Wife");
    public static final List<String> FAMILY_RELATIONSHIPS = List.of("Father", "Mother", "Husband", "Wife", "Son", "Daughter", "Brother", "Sister");
    public static final List<String> ACCOUNT_TYPES = List.of("Saving", "Current", "Salary");

    static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
    static final Pattern PAN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
    static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    static final int MAX_TEXT = 200;
    static final int MAX_LONG_TEXT = 1000;
    static final int MAX_BLOCKS = 20;
    static final int FIRST_YEAR = 1950;

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Digits only, or {@code null} when empty. */
    public static String digits(String value) {
        return value == null ? null : clean(value.replaceAll("[\\s\\-]", ""));
    }

    private static void required(Map<String, String> errors, String path, String value, String label) {
        if (blank(value)) errors.put(path, label + " is mandatory");
        else if (value.trim().length() > MAX_TEXT) errors.put(path, label + " is too long");
    }

    private static void optionalText(Map<String, String> errors, String path, String value, String label, int max) {
        if (value != null && value.trim().length() > max) errors.put(path, label + " is too long");
    }

    private static void requiredOneOf(Map<String, String> errors, String path, String value, List<String> allowed, String label) {
        if (blank(value)) errors.put(path, label + " is mandatory");
        else if (!allowed.contains(value.trim())) errors.put(path, "Choose a valid " + label);
    }

    private static LocalDate date(Map<String, String> errors, String path, String value, String label) {
        if (blank(value)) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            errors.put(path, label + " must be a date (YYYY-MM-DD)");
            return null;
        }
    }

    private static void requiredDigits(Map<String, String> errors, String path, String value, int length, String label) {
        String digits = digits(value);
        if (digits == null) errors.put(path, label + " is mandatory");
        else if (!digits.matches("\\d{" + length + "}")) errors.put(path, label + " must be " + length + " digits");
    }

    private static void optionalDigits(Map<String, String> errors, String path, String value, int length, String label) {
        String digits = digits(value);
        if (digits != null && !digits.matches("\\d{" + length + "}")) errors.put(path, label + " must be " + length + " digits");
    }

    private static void year(Map<String, String> errors, String path, Integer value, String label, boolean isRequired) {
        if (value == null) {
            if (isRequired) errors.put(path, label + " is mandatory");
        } else if (value < FIRST_YEAR || value > LocalDate.now().getYear() + 1) errors.put(path, label + " is not a valid year");
    }

    // ───────────────────────── step 1 ─────────────────────────

    public static Map<String, String> personalStep(Personal personal, Address permanent, PresentAddress present, EmergencyContact emergency, List<Education> education,
                                                   List<Course> courses, LocalDate today) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (personal == null) {
            errors.put("personal", "Personal information is mandatory");
        } else {
            requiredOneOf(errors, "personal.bloodGroup", personal.bloodGroup(), BLOOD_GROUPS, "Blood Group");
            if (blank(personal.personalEmail())) errors.put("personal.personalEmail", "Personal Email is mandatory");
            else if (personal.personalEmail().trim().length() > MAX_TEXT || !EMAIL.matcher(personal.personalEmail().trim()).matches()) errors.put("personal.personalEmail", "Enter a valid email");
            required(errors, "personal.fatherName", personal.fatherName(), "Father name");
            requiredOneOf(errors, "personal.maritalStatus", personal.maritalStatus(), MARITAL_STATUSES, "Marital Status");
            boolean isMarried = "Married".equals(clean(personal.maritalStatus()));
            LocalDate marriageDate = date(errors, "personal.marriageDate", personal.marriageDate(), "Marriage Date");
            if (isMarried && blank(personal.marriageDate())) errors.put("personal.marriageDate", "Marriage Date is mandatory");
            if (marriageDate != null && marriageDate.isAfter(today)) errors.put("personal.marriageDate", "Marriage Date cannot be in the future");
            optionalText(errors, "personal.spouseName", personal.spouseName(), "Spouse Name", MAX_TEXT);
            required(errors, "personal.countryOfOrigin", personal.countryOfOrigin(), "Country Of Origin");
            requiredOneOf(errors, "personal.nationality", personal.nationality(), NATIONALITIES, "Nationality");
            if (personal.isInternationalEmployee() == null) errors.put("personal.isInternationalEmployee", "International Employee is mandatory");
            if (personal.isPhysicallyChallenged() == null) errors.put("personal.isPhysicallyChallenged", "Physically Challenged is mandatory");
            if (Boolean.TRUE.equals(personal.isPhysicallyChallenged())) requiredOneOf(errors, "personal.disabilityType", personal.disabilityType(), DISABILITY_TYPES, "Disability Type");
            if (!blank(personal.gender()) && !GENDERS.contains(personal.gender().trim())) errors.put("personal.gender", "Choose a valid Gender");
            LocalDate dateOfBirth = date(errors, "personal.dateOfBirth", personal.dateOfBirth(), "Date of Birth");
            if (dateOfBirth != null && dateOfBirth.isAfter(today)) errors.put("personal.dateOfBirth", "Date of Birth cannot be in the future");
            if (personal.heightCm() != null && (personal.heightCm().signum() <= 0 || personal.heightCm().intValue() > 300)) errors.put("personal.heightCm", "Enter a valid height");
            if (personal.weightKg() != null && (personal.weightKg().signum() <= 0 || personal.weightKg().intValue() > 500)) errors.put("personal.weightKg", "Enter a valid weight");
            optionalText(errors, "personal.identificationMark", personal.identificationMark(), "Identification Mark", MAX_TEXT);
            if (!blank(personal.religion()) && !RELIGIONS.contains(personal.religion().trim())) errors.put("personal.religion", "Choose a valid Religion");
            optionalText(errors, "personal.caste", personal.caste(), "Caste", MAX_TEXT);
            optionalText(errors, "personal.hobby", personal.hobby(), "Hobby", MAX_TEXT);
        }
        address(errors, "permanentAddress", permanent);
        if (present == null) errors.put("presentAddress", "Present address is mandatory");
        else if (!Boolean.TRUE.equals(present.isSameAsPermanent())) address(errors, "presentAddress", present.asAddress());
        if (emergency == null) {
            errors.put("emergencyContact", "Emergency contact is mandatory");
        } else {
            required(errors, "emergencyContact.name", emergency.name(), "Name");
            requiredDigits(errors, "emergencyContact.mobile", emergency.mobile(), 10, "Mobile Number");
            requiredOneOf(errors, "emergencyContact.relationship", emergency.relationship(), EMERGENCY_RELATIONSHIPS, "Relationship");
        }
        if (education == null || education.isEmpty()) errors.put("education", "Add at least one education");
        else if (education.size() > MAX_BLOCKS) errors.put("education", "Too many entries");
        else {
            for (int i = 0; i < education.size(); i++) {
                Education entry = education.get(i);
                String path = "education[" + i + "].";
                required(errors, path + "qualification", entry.qualification(), "Qualification");
                year(errors, path + "fromYear", entry.fromYear(), "From Year", true);
                year(errors, path + "toYear", entry.toYear(), "To Year", true);
                if (entry.fromYear() != null && entry.toYear() != null && entry.toYear() < entry.fromYear() && !errors.containsKey(path + "toYear")) errors.put(path + "toYear", "To Year cannot be before From Year");
                required(errors, path + "institute", entry.institute(), "Institute");
                required(errors, path + "gradeOrPercentage", entry.gradeOrPercentage(), "Grade/Percentage");
            }
        }
        if (courses != null) {
            if (courses.size() > MAX_BLOCKS) errors.put("courses", "Too many entries");
            else for (int i = 0; i < courses.size(); i++) {
                Course entry = courses.get(i);
                String path = "courses[" + i + "].";
                optionalText(errors, path + "courseName", entry.courseName(), "Course Name", MAX_TEXT);
                optionalText(errors, path + "institute", entry.institute(), "Institute", MAX_TEXT);
                year(errors, path + "fromYear", entry.fromYear(), "From Year", false);
                year(errors, path + "toYear", entry.toYear(), "To Year", false);
                if (entry.fromYear() != null && entry.toYear() != null && entry.toYear() < entry.fromYear() && !errors.containsKey(path + "toYear")) errors.put(path + "toYear", "To Year cannot be before From Year");
            }
        }
        return errors;
    }

    private static void address(Map<String, String> errors, String path, Address address) {
        if (address == null) {
            errors.put(path, "Address is mandatory");
            return;
        }
        required(errors, path + ".line1", address.line1(), "Address");
        optionalText(errors, path + ".line2", address.line2(), "Address Line 2", MAX_TEXT);
        optionalText(errors, path + ".line3", address.line3(), "Address Line 3", MAX_TEXT);
        required(errors, path + ".city", address.city(), "City");
        optionalText(errors, path + ".district", address.district(), "District", MAX_TEXT);
        required(errors, path + ".state", address.state(), "State");
        requiredDigits(errors, path + ".pin", address.pin(), 6, "Pin");
        required(errors, path + ".country", address.country(), "Country");
        requiredDigits(errors, path + ".phone", address.phone(), 10, "Phone Number");
    }

    // ───────────────────────── step 2 ─────────────────────────

    /** A block is in use as soon as any field has something in it. */
    public static boolean isEmploymentUsed(Employment employment) {
        return !blank(employment.companyName()) || !blank(employment.designation()) || employment.lastDrawnSalary() != null || !blank(employment.fromDate())
                || !blank(employment.toDate()) || !blank(employment.reasonForLeaving()) || !blank(employment.companyAddress());
    }

    public static Map<String, String> employmentStep(List<Employment> employments) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (employments == null) return errors;
        if (employments.size() > MAX_BLOCKS) {
            errors.put("employments", "Too many entries");
            return errors;
        }
        for (int i = 0; i < employments.size(); i++) {
            Employment entry = employments.get(i);
            if (!isEmploymentUsed(entry)) continue;
            String path = "employments[" + i + "].";
            required(errors, path + "companyName", entry.companyName(), "Company Name");
            optionalText(errors, path + "designation", entry.designation(), "Designation", MAX_TEXT);
            if (entry.lastDrawnSalary() != null && (entry.lastDrawnSalary().signum() < 0 || entry.lastDrawnSalary().compareTo(new java.math.BigDecimal("100000000000")) > 0)) errors.put(path + "lastDrawnSalary", "Enter a valid salary");
            LocalDate from = date(errors, path + "fromDate", entry.fromDate(), "From Date");
            LocalDate to = date(errors, path + "toDate", entry.toDate(), "To Date");
            if (blank(entry.fromDate())) errors.put(path + "fromDate", "From Date is mandatory");
            if (blank(entry.toDate())) errors.put(path + "toDate", "To Date is mandatory");
            if (from != null && to != null && to.isBefore(from)) errors.put(path + "toDate", "To Date cannot be before From Date");
            optionalText(errors, path + "reasonForLeaving", entry.reasonForLeaving(), "Reason for Leaving", MAX_LONG_TEXT);
            optionalText(errors, path + "companyAddress", entry.companyAddress(), "Company Address", MAX_LONG_TEXT);
        }
        return errors;
    }

    // ───────────────────────── step 3 ─────────────────────────

    /** {@code bank} and {@code statutory} carry the real values (the service has already put stored secrets back where the client sent {@code null}). */
    public static Map<String, String> statutoryStep(Bank bank, Statutory statutory) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (bank == null) {
            errors.put("bank", "Bank account details are mandatory");
        } else {
            required(errors, "bank.country", bank.country(), "Country");
            String account = digits(bank.bankAccountNumber());
            if (account == null) errors.put("bank.bankAccountNumber", "Bank Account Number is mandatory");
            else if (!account.matches("\\d{9,18}")) errors.put("bank.bankAccountNumber", "Bank Account Number must be 9 to 18 digits");
            String ifsc = clean(bank.ifsc());
            if (ifsc == null) errors.put("bank.ifsc", "Bank Code is mandatory");
            else if (!IFSC.matcher(ifsc.toUpperCase()).matches()) errors.put("bank.ifsc", "Enter a valid IFSC (e.g. ICIC0000571)");
            optionalText(errors, "bank.bankName", bank.bankName(), "Bank Name", MAX_TEXT);
            optionalText(errors, "bank.branch", bank.branch(), "Branch", MAX_TEXT);
            optionalText(errors, "bank.branchAddress", bank.branchAddress(), "Branch Address", MAX_LONG_TEXT);
            required(errors, "bank.nameAsPerBank", bank.nameAsPerBank(), "Name as per Bank Account");
            requiredOneOf(errors, "bank.accountType", bank.accountType(), ACCOUNT_TYPES, "Account Type");
        }
        if (statutory == null) {
            errors.put("statutory", "Statutory details are mandatory");
        } else {
            optionalText(errors, "statutory.previousPfNumber", statutory.previousPfNumber(), "Previous PF Account Number", MAX_TEXT);
            optionalDigits(errors, "statutory.uan", statutory.uan(), 12, "UAN");
            String pan = clean(statutory.pan());
            if (pan == null) errors.put("statutory.pan", "PAN Number is mandatory");
            else if (!PAN.matcher(pan.toUpperCase()).matches()) errors.put("statutory.pan", "PAN looks like ABCDE1234F");
            required(errors, "statutory.namePerPan", statutory.namePerPan(), "Name in PAN");
            requiredDigits(errors, "statutory.aadhaar", statutory.aadhaar(), 12, "Aadhaar Number");
            required(errors, "statutory.namePerAadhaar", statutory.namePerAadhaar(), "Name in Aadhaar");
            optionalText(errors, "statutory.passportNumber", statutory.passportNumber(), "Passport Number", MAX_TEXT);
            optionalText(errors, "statutory.namePerPassport", statutory.namePerPassport(), "Name in Passport", MAX_TEXT);
            date(errors, "statutory.passportExpiry", statutory.passportExpiry(), "Expiry Date");
            if (!blank(statutory.passportNumber()) && blank(statutory.passportExpiry())) errors.put("statutory.passportExpiry", "Expiry Date is mandatory");
        }
        return errors;
    }

    // ───────────────────────── step 4 ─────────────────────────

    public static Map<String, String> familyStep(List<FamilyMember> members, LocalDate today) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (members == null) return errors;
        if (members.size() > MAX_BLOCKS) {
            errors.put("familyMembers", "Too many entries");
            return errors;
        }
        for (int i = 0; i < members.size(); i++) {
            FamilyMember member = members.get(i);
            String path = "familyMembers[" + i + "].";
            required(errors, path + "name", member.name(), "Name");
            requiredOneOf(errors, path + "relationship", member.relationship(), FAMILY_RELATIONSHIPS, "Relationship");
            if (blank(member.dateOfBirth())) errors.put(path + "dateOfBirth", "DOB is mandatory");
            else {
                LocalDate dateOfBirth = date(errors, path + "dateOfBirth", member.dateOfBirth(), "DOB");
                if (dateOfBirth != null && dateOfBirth.isAfter(today)) errors.put(path + "dateOfBirth", "DOB cannot be in the future");
            }
            requiredOneOf(errors, path + "gender", member.gender(), GENDERS, "Gender");
            requiredOneOf(errors, path + "bloodGroup", member.bloodGroup(), BLOOD_GROUPS, "Blood Group");
            requiredOneOf(errors, path + "nationality", member.nationality(), NATIONALITIES, "Nationality");
            if (Boolean.TRUE.equals(member.isMinor())) {
                Guardian guardian = member.guardian();
                if (guardian == null) {
                    errors.put(path + "guardian.name", "Guardian Name is mandatory");
                    errors.put(path + "guardian.relationship", "Relationship is mandatory");
                    errors.put(path + "guardian.mobile", "Mobile is mandatory");
                } else {
                    required(errors, path + "guardian.name", guardian.name(), "Guardian Name");
                    required(errors, path + "guardian.relationship", guardian.relationship(), "Relationship");
                    requiredDigits(errors, path + "guardian.mobile", guardian.mobile(), 10, "Mobile");
                    optionalDigits(errors, path + "guardian.pin", guardian.pin(), 6, "Pin");
                    if (!blank(guardian.email()) && !EMAIL.matcher(guardian.email().trim()).matches()) errors.put(path + "guardian.email", "Enter a valid email");
                    optionalText(errors, path + "guardian.line1", guardian.line1(), "Address Line 1", MAX_TEXT);
                    optionalText(errors, path + "guardian.line2", guardian.line2(), "Address Line 2", MAX_TEXT);
                    optionalText(errors, path + "guardian.line3", guardian.line3(), "Address Line 3", MAX_TEXT);
                }
            }
        }
        return errors;
    }

    // ───────────────────────── step 5 ─────────────────────────

    public static Map<String, String> nominationStep(Nominations nominations, Set<String> familyMemberIds) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (nominations == null) return errors;
        shares(errors, "nominations.epf", nominations.epf(), familyMemberIds);
        shares(errors, "nominations.gratuity", nominations.gratuity(), familyMemberIds);
        if (nominations.eps() != null && (blank(nominations.eps().familyMemberId()) || !familyMemberIds.contains(nominations.eps().familyMemberId()))) errors.put("nominations.eps.familyMemberId", "Choose a family member");
        if (nominations.esi() != null && (blank(nominations.esi().familyMemberId()) || !familyMemberIds.contains(nominations.esi().familyMemberId()))) errors.put("nominations.esi.familyMemberId", "Choose a family member");
        return errors;
    }

    private static void shares(Map<String, String> errors, String path, List<NomineeShare> shares, Set<String> familyMemberIds) {
        if (shares == null || shares.isEmpty()) return;
        if (shares.size() > MAX_BLOCKS) {
            errors.put(path, "Too many entries");
            return;
        }
        Set<String> seen = new HashSet<>();
        int total = 0;
        boolean isComplete = true;
        for (int i = 0; i < shares.size(); i++) {
            NomineeShare share = shares.get(i);
            String entry = path + "[" + i + "].";
            if (blank(share.familyMemberId()) || !familyMemberIds.contains(share.familyMemberId())) {
                errors.put(entry + "familyMemberId", "Choose a family member");
                isComplete = false;
            } else if (!seen.add(share.familyMemberId())) {
                errors.put(entry + "familyMemberId", "A family member can be nominated only once");
                isComplete = false;
            }
            if (share.percentage() == null || share.percentage() < 1 || share.percentage() > 100) {
                errors.put(entry + "percentage", "Enter a percentage between 1 and 100");
                isComplete = false;
            } else total += share.percentage();
        }
        if (!isComplete) return;
        if (shares.size() == 1 && total != 100) errors.put(path + "[0].percentage", "Nomination % must be 100 in case of single nominee.");
        else if (shares.size() > 1 && total != 100) errors.put(path + "[0].percentage", "Total nomination % must be 100.");
    }
}
