package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.crypto.Masks;
import com.jurislpo.jurisone.crypto.PiiCrypto;
import com.jurislpo.jurisone.engine.Phones;
import com.jurislpo.jurisone.engine.PeopleRules;
import com.jurislpo.jurisone.repository.ProfileRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * "My profile": everyone sees their own record and edits a few personal details themselves — father's name, date of
 * birth, addresses and emergency contact. Name, work email, phone, IDs, bank and job details are changed by HR.
 */
@Service
public class ProfileService {

    private final ProfileRepository repo;
    private final PiiCrypto pii;
    private final AuditService audit;

    public ProfileService(ProfileRepository repo, PiiCrypto pii, AuditService audit) {
        this.repo = repo;
        this.pii = pii;
        this.audit = audit;
    }

    static String nz(Object value) {
        return value == null ? "" : value.toString();
    }

    public Map<String, Object> mine(CurrentUser user) {
        Map<String, Object> row = repo.findProfile(user.id());
        String[] names = PeopleService.names(row);
        String[] emergency = splitPhone((String) row.get("emergency_phone"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", row.get("code"));
        out.put("photo", PhotoService.version(row.get("photo_updated_at")));
        out.put("fullName", row.get("full_name"));
        out.put("firstName", names[0]);
        out.put("middleName", names[1]);
        out.put("lastName", names[2]);
        out.put("email", row.get("email"));
        out.put("phone", row.get("phone_last4") == null ? null : (row.get("phone_country") == null ? "" : row.get("phone_country") + " ") + Masks.phone((String) row.get("phone_last4")));
        out.put("designation", row.get("designation"));
        out.put("department", row.get("dept"));
        out.put("manager", row.get("manager"));
        out.put("role", row.get("role_name"));
        out.put("dateOfJoining", row.get("date_of_joining").toString());
        out.put("joiningOffice", row.get("joining_office"));
        out.put("location", PeopleRules.locationOfTimeZone((String) row.get("time_zone")).label());
        out.put("shift", row.get("shift_name"));
        out.put("pan", row.get("pan_last4") == null ? null : Masks.pan((String) row.get("pan_last4")));
        out.put("aadhaar", row.get("aadhaar_last4") == null ? null : Masks.aadhaar((String) row.get("aadhaar_last4")));
        out.put("bank", row.get("bank_account_last4") == null ? null : nz(row.get("bank_name")) + " · " + Masks.bank((String) row.get("bank_account_last4")));
        // editable by the employee
        out.put("fatherName", nz(row.get("father_name")));
        out.put("dateOfBirth", row.get("date_of_birth") == null ? "" : row.get("date_of_birth").toString());
        out.put("currentAddress", row.get("address_enc") == null ? "" : pii.decrypt((String) row.get("address_enc")));
        out.put("permanentAddress", row.get("permanent_address_enc") == null ? "" : pii.decrypt((String) row.get("permanent_address_enc")));
        out.put("emergencyName", nz(row.get("emergency_name")));
        out.put("emergencyRelation", nz(row.get("emergency_relation")));
        out.put("emergencyPhoneCountry", emergency[0]);
        out.put("emergencyPhone", emergency[1]);
        return out;
    }

    /** "+91 9810044556" → ["+91", "9810044556"]. */
    static String[] splitPhone(String stored) {
        if (stored == null || stored.isBlank()) return new String[] {"+91", ""};
        String t = stored.trim();
        int sp = t.indexOf(' ');
        if (t.startsWith("+") && sp > 0) return new String[] {t.substring(0, sp), PeopleRules.digits(t.substring(sp + 1))};
        return new String[] {"+91", PeopleRules.digits(t)};
    }

    public record ProfileInput(String fatherName, String dateOfBirth, String currentAddress, String permanentAddress, String emergencyName,
                               String emergencyRelation, String emergencyPhoneCountry, String emergencyPhone) {}

    static UserException bad(String m) {
        return new UserException(HttpStatus.BAD_REQUEST, m);
    }

    static String text(String value, int max, String field) {
        String t = value == null ? "" : value.trim();
        if (t.length() > max) throw bad("Keep the " + field + " under " + max + " characters.");
        return t;
    }

    public String update(CurrentUser user, ProfileInput input, String ip) {
        if (input == null) throw bad("Your details are missing.");
        String father = text(input.fatherName(), 120, "father's name");
        if (father.length() < 2) throw bad("Enter your father's name.");
        String dob = text(input.dateOfBirth(), 10, "date of birth");
        LocalDate birth = null;
        if (!dob.isEmpty()) {
            try {
                birth = LocalDate.parse(dob);
            } catch (java.time.DateTimeException e) {
                throw bad("Check your date of birth.");
            }
            if (birth.isAfter(LocalDate.now().minusYears(14)) || birth.isBefore(LocalDate.now().minusYears(100))) throw bad("Date of birth isn't valid: you must be at least 14 years old.");
        }
        String current = text(input.currentAddress(), 500, "address");
        String permanent = text(input.permanentAddress(), 500, "address");
        String eName = text(input.emergencyName(), 120, "emergency contact");
        String eRel = text(input.emergencyRelation(), 40, "relationship");
        String eCode = text(input.emergencyPhoneCountry(), 5, "country code");
        String ePhone = PeopleRules.digits(text(input.emergencyPhone(), 30, "phone"));
        if (!ePhone.isEmpty()) {
            String p = Phones.problem(eCode.isEmpty() ? "+91" : eCode, ePhone);
            if (p != null) throw bad("Emergency contact: " + p + ".");
        }
        if (!ePhone.isEmpty() && eName.length() < 2) throw bad("Enter your emergency contact's name.");
        repo.updateSelfService(user.id(),
                father, birth == null ? null : Date.valueOf(birth), current.isEmpty() ? null : pii.encrypt(current), permanent.isEmpty() ? null : pii.encrypt(permanent),
                eName.isEmpty() ? null : eName, eRel.isEmpty() ? null : eRel, ePhone.isEmpty() ? null : (eCode.isEmpty() ? "+91" : eCode) + " " + ePhone);
        audit.log(user.id(), "profile.self_update", "employee", user.id().toString(), null,
                Map.of("fields", List.of("fatherName", "dateOfBirth", "address", "permanentAddress", "emergencyContact")), ip);
        return "Your profile was saved";
    }
}
