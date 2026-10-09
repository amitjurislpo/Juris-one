package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.crypto.Masks;
import com.jurislpo.jurisone.crypto.PiiCrypto;
import com.jurislpo.jurisone.engine.Phones;
import com.jurislpo.jurisone.engine.PeopleRules;
import com.jurislpo.jurisone.security.CurrentUser;
import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * "My profile": everyone sees their own record and edits a few personal details themselves — father's name, date of
 * birth, addresses and emergency contact. Name, work email, phone, IDs, bank and job details are changed by HR.
 */
@Service
public class ProfileService {

    private final JdbcTemplate jdbc;
    private final PiiCrypto pii;
    private final AuditService audit;

    public ProfileService(JdbcTemplate jdbc, PiiCrypto pii, AuditService audit) {
        this.jdbc = jdbc;
        this.pii = pii;
        this.audit = audit;
    }

    static String nz(Object o) {
        return o == null ? "" : o.toString();
    }

    public Map<String, Object> mine(CurrentUser u) {
        Map<String, Object> e = jdbc.queryForMap("""
            SELECT e.*, d.name AS dept, m.full_name AS manager, r.name AS role_name, s.name AS shift_name
            FROM employees e JOIN roles r ON r.id = e.role_id LEFT JOIN departments d ON d.id = e.department_id
            LEFT JOIN employees m ON m.id = e.reporting_manager_id LEFT JOIN shifts s ON s.id = e.shift_id WHERE e.id = ?""", u.id());
        String[] names = PeopleService.names(e);
        String[] emergency = splitPhone((String) e.get("emergency_phone"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", e.get("code"));
        out.put("fullName", e.get("full_name"));
        out.put("firstName", names[0]);
        out.put("middleName", names[1]);
        out.put("lastName", names[2]);
        out.put("email", e.get("email"));
        out.put("phone", e.get("phone_last4") == null ? null : (e.get("phone_country") == null ? "" : e.get("phone_country") + " ") + Masks.phone((String) e.get("phone_last4")));
        out.put("designation", e.get("designation"));
        out.put("department", e.get("dept"));
        out.put("manager", e.get("manager"));
        out.put("role", e.get("role_name"));
        out.put("dateOfJoining", e.get("date_of_joining").toString());
        out.put("joiningOffice", e.get("joining_office"));
        out.put("location", PeopleRules.locationOfTz((String) e.get("time_zone")).l());
        out.put("shift", e.get("shift_name"));
        out.put("pan", e.get("pan_last4") == null ? null : Masks.pan((String) e.get("pan_last4")));
        out.put("aadhaar", e.get("aadhaar_last4") == null ? null : Masks.aadhaar((String) e.get("aadhaar_last4")));
        out.put("bank", e.get("bank_account_last4") == null ? null : nz(e.get("bank_name")) + " · " + Masks.bank((String) e.get("bank_account_last4")));
        // editable by the employee
        out.put("fatherName", nz(e.get("father_name")));
        out.put("dateOfBirth", e.get("date_of_birth") == null ? "" : e.get("date_of_birth").toString());
        out.put("currentAddress", e.get("address_enc") == null ? "" : pii.decrypt((String) e.get("address_enc")));
        out.put("permanentAddress", e.get("permanent_address_enc") == null ? "" : pii.decrypt((String) e.get("permanent_address_enc")));
        out.put("emergencyName", nz(e.get("emergency_name")));
        out.put("emergencyRelation", nz(e.get("emergency_relation")));
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

    static String text(String v, int max, String field) {
        String t = v == null ? "" : v.trim();
        if (t.length() > max) throw bad("Keep the " + field + " under " + max + " characters.");
        return t;
    }

    public String update(CurrentUser u, ProfileInput in, String ip) {
        if (in == null) throw bad("Your details are missing.");
        String father = text(in.fatherName(), 120, "father's name");
        if (father.length() < 2) throw bad("Enter your father's name.");
        String dob = text(in.dateOfBirth(), 10, "date of birth");
        LocalDate birth = null;
        if (!dob.isEmpty()) {
            try {
                birth = LocalDate.parse(dob);
            } catch (java.time.DateTimeException e) {
                throw bad("Check your date of birth.");
            }
            if (birth.isAfter(LocalDate.now().minusYears(14)) || birth.isBefore(LocalDate.now().minusYears(100))) throw bad("Check your date of birth.");
        }
        String current = text(in.currentAddress(), 500, "address");
        String permanent = text(in.permanentAddress(), 500, "address");
        String eName = text(in.emergencyName(), 120, "emergency contact");
        String eRel = text(in.emergencyRelation(), 40, "relationship");
        String eCode = text(in.emergencyPhoneCountry(), 5, "country code");
        String ePhone = PeopleRules.digits(text(in.emergencyPhone(), 30, "phone"));
        if (!ePhone.isEmpty()) {
            String p = Phones.problem(eCode.isEmpty() ? "+91" : eCode, ePhone);
            if (p != null) throw bad("Emergency contact: " + p + ".");
        }
        if (!ePhone.isEmpty() && eName.length() < 2) throw bad("Enter your emergency contact's name.");
        jdbc.update("""
            UPDATE employees SET father_name = ?, date_of_birth = ?, address_enc = ?, permanent_address_enc = ?, emergency_name = ?, emergency_relation = ?,
              emergency_phone = ?, updated_at = now() WHERE id = ?""",
                father, birth == null ? null : Date.valueOf(birth), current.isEmpty() ? null : pii.encrypt(current), permanent.isEmpty() ? null : pii.encrypt(permanent),
                eName.isEmpty() ? null : eName, eRel.isEmpty() ? null : eRel, ePhone.isEmpty() ? null : (eCode.isEmpty() ? "+91" : eCode) + " " + ePhone, u.id());
        audit.log(u.id(), "profile.self_update", "employee", u.id().toString(), null,
                Map.of("fields", List.of("fatherName", "dateOfBirth", "address", "permanentAddress", "emergencyContact")), ip);
        return "Your profile was saved";
    }
}
