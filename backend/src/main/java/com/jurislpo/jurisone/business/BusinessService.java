package com.jurislpo.jurisone.business;

import com.jurislpo.jurisone.crypto.Masks;
import com.jurislpo.jurisone.crypto.PiiCrypto;
import com.jurislpo.jurisone.engine.Shifts;
import com.jurislpo.jurisone.platform.AuditService;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.tenancy.TenantContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Date;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** The owner's (sub admin's) view of their business: profile, subscription, and the employee export. */
@Service
public class BusinessService {

    private final JdbcTemplate jdbc;
    private final SubscriptionService subscriptions;
    private final PiiCrypto pii;
    private final AuditService audit;

    public BusinessService(JdbcTemplate jdbc, SubscriptionService subscriptions, PiiCrypto pii, AuditService audit) {
        this.jdbc = jdbc;
        this.subscriptions = subscriptions;
        this.pii = pii;
        this.audit = audit;
    }

    /** Profile, the current subscription and its history. */
    public Map<String, Object> subscriptionPage() {
        UUID b = TenantContext.current();
        Map<String, Object> biz = jdbc.queryForMap("SELECT name, legal_name, code, employee_prefix, contact_email, contact_phone, address, gstin, time_zone FROM businesses WHERE id = ?", b);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", biz.get("name"));
        profile.put("legalName", biz.get("legal_name"));
        profile.put("code", biz.get("code"));
        profile.put("employeePrefix", biz.get("employee_prefix"));
        profile.put("contactEmail", biz.get("contact_email"));
        profile.put("contactPhone", biz.get("contact_phone"));
        profile.put("address", biz.get("address"));
        profile.put("gstin", biz.get("gstin"));
        profile.put("timeZone", biz.get("time_zone"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("business", profile);
        out.put("subscription", subscriptions.current(b));
        out.put("history", subscriptions.history(b));
        return out;
    }

    public record Export(String filename, byte[] body) {}

    static final String[] HEADERS = {"Employee ID", "Full name", "Father's name", "Email", "Phone", "Designation", "Department", "Role", "Reporting manager",
            "Status", "Date of joining", "Last working day", "Location", "Time zone", "Shift", "Portal access", "PAN", "Aadhaar", "UAN", "Bank", "Account number",
            "IFSC", "Address"};

    /**
     * Every employee of the business as an Excel sheet. Identity numbers, phone, bank account and address are masked
     * unless {@code full} — reading them in full is audited.
     */
    public Export employees(CurrentUser u, boolean full, String ip) throws IOException {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT e.code, e.full_name, e.father_name, e.email, e.phone_enc, e.phone_last4, e.designation, d.name AS dept, r.name AS role,
                   m.full_name AS manager, e.status::text AS status, e.date_of_joining, e.exit_date, e.region::text AS region, e.time_zone,
                   s.code AS s_code, s.start_local, s.end_local, e.portal_access, e.pan_enc, e.pan_last4, e.aadhaar_enc, e.aadhaar_last4,
                   e.uan_enc, e.uan_last4, e.bank_name, e.bank_account_enc, e.bank_account_last4, e.bank_ifsc, e.address_enc
            FROM employees e JOIN roles r ON r.id = e.role_id
            LEFT JOIN departments d ON d.id = e.department_id LEFT JOIN employees m ON m.id = e.reporting_manager_id LEFT JOIN shifts s ON s.id = e.shift_id
            WHERE r.key NOT IN ('owner', 'super_admin') -- the business admin's own details are never exported
            ORDER BY e.code""");
        String business = jdbc.queryForObject("SELECT code FROM businesses WHERE id = ?", String.class, TenantContext.current());
        try (SXSSFWorkbook wb = new SXSSFWorkbook(100); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Employees");
            CellStyle bold = wb.createCellStyle();
            Font f = wb.createFont();
            f.setBold(true);
            bold.setFont(f);
            Row head = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = head.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(bold);
            }
            int n = 1;
            for (Map<String, Object> e : rows) {
                List<String> v = new ArrayList<>();
                v.add(str(e.get("code")));
                v.add(str(e.get("full_name")));
                v.add(str(e.get("father_name")));
                v.add(str(e.get("email")));
                v.add(full ? orDash(pii.decrypt((String) e.get("phone_enc"))) : Masks.phone((String) e.get("phone_last4")));
                v.add(str(e.get("designation")));
                v.add(orDash((String) e.get("dept")));
                v.add(str(e.get("role")));
                v.add(orDash((String) e.get("manager")));
                v.add(str(e.get("status")));
                v.add(date(e.get("date_of_joining")));
                v.add(date(e.get("exit_date")));
                v.add("IN".equals(e.get("region")) ? "India" : "US");
                v.add(str(e.get("time_zone")));
                v.add(Shifts.label((String) e.get("s_code"), (String) e.get("start_local"), (String) e.get("end_local")));
                v.add(Boolean.TRUE.equals(e.get("portal_access")) ? "On" : "Off");
                v.add(full ? orDash(pii.decrypt((String) e.get("pan_enc"))) : Masks.pan((String) e.get("pan_last4")));
                v.add(full ? orDash(pii.decrypt((String) e.get("aadhaar_enc"))) : Masks.aadhaar((String) e.get("aadhaar_last4")));
                v.add(full ? orDash(pii.decrypt((String) e.get("uan_enc"))) : Masks.uan((String) e.get("uan_last4")));
                v.add(orDash((String) e.get("bank_name")));
                v.add(full ? orDash(pii.decrypt((String) e.get("bank_account_enc"))) : Masks.bank((String) e.get("bank_account_last4")));
                v.add(orDash((String) e.get("bank_ifsc")));
                v.add(full ? orDash(pii.decrypt((String) e.get("address_enc"))) : (e.get("address_enc") == null ? "—" : "(hidden)"));
                Row row = sheet.createRow(n++);
                for (int i = 0; i < v.size(); i++) row.createCell(i).setCellValue(v.get(i));
            }
            sheet.createFreezePane(0, 1);
            wb.write(out);
            wb.dispose();
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("rows", rows.size());
            after.put("identityNumbers", full ? "full" : "masked");
            audit.log(u.id(), "business.employee_export", "business", TenantContext.current().toString(), null, after, ip);
            return new Export(business + "-employees-" + SubscriptionService.today() + ".xlsx", out.toByteArray());
        }
    }

    static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    static String orDash(String s) {
        return s == null || s.isEmpty() ? "—" : s;
    }

    static String date(Object o) {
        return o == null ? "" : ((Date) o).toLocalDate().toString();
    }
}
