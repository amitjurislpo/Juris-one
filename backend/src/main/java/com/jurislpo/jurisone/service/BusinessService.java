package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.crypto.Masks;
import com.jurislpo.jurisone.crypto.PiiCrypto;
import com.jurislpo.jurisone.engine.Shifts;
import com.jurislpo.jurisone.repository.BusinessRepository;
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
import org.springframework.stereotype.Service;

/** The owner's (sub admin's) view of their business: profile, subscription, and the employee export. */
@Service
public class BusinessService {

    private final BusinessRepository repo;
    private final SubscriptionService subscriptions;
    private final PiiCrypto piiCrypto;
    private final AuditService audit;

    public BusinessService(BusinessRepository repo, SubscriptionService subscriptions, PiiCrypto piiCrypto, AuditService audit) {
        this.repo = repo;
        this.subscriptions = subscriptions;
        this.piiCrypto = piiCrypto;
        this.audit = audit;
    }

    /** Profile, the current subscription and its history. */
    public Map<String, Object> subscriptionPage() {
        UUID businessId = TenantContext.current();
        Map<String, Object> businessProfile = repo.findProfile(businessId);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", businessProfile.get("name"));
        profile.put("legalName", businessProfile.get("legal_name"));
        profile.put("code", businessProfile.get("code"));
        profile.put("employeePrefix", businessProfile.get("employee_prefix"));
        profile.put("contactEmail", businessProfile.get("contact_email"));
        profile.put("contactPhone", businessProfile.get("contact_phone"));
        profile.put("address", businessProfile.get("address"));
        profile.put("gstin", businessProfile.get("gstin"));
        profile.put("timeZone", businessProfile.get("time_zone"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("business", profile);
        out.put("subscription", subscriptions.current(businessId));
        out.put("history", subscriptions.history(businessId));
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
    public Export employees(CurrentUser user, boolean full, String ipAddress) throws IOException {
        List<Map<String, Object>> rows = repo.findEmployeesForExport();
        String business = repo.findCode(TenantContext.current());
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Employees");
            CellStyle bold = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            bold.setFont(font);
            Row head = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = head.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(bold);
            }
            int count = 1;
            for (Map<String, Object> employee : rows) {
                List<String> values = new ArrayList<>();
                values.add(textOrEmpty(employee.get("code")));
                values.add(textOrEmpty(employee.get("full_name")));
                values.add(textOrEmpty(employee.get("father_name")));
                values.add(textOrEmpty(employee.get("email")));
                values.add(full ? orDash(piiCrypto.decrypt((String) employee.get("phone_enc"))) : Masks.phone((String) employee.get("phone_last4")));
                values.add(textOrEmpty(employee.get("designation")));
                values.add(orDash((String) employee.get("department_name")));
                values.add(textOrEmpty(employee.get("role")));
                values.add(orDash((String) employee.get("manager")));
                values.add(textOrEmpty(employee.get("status")));
                values.add(date(employee.get("date_of_joining")));
                values.add(date(employee.get("exit_date")));
                values.add("IN".equals(employee.get("region")) ? "India" : "US");
                values.add(textOrEmpty(employee.get("time_zone")));
                values.add(Shifts.label((String) employee.get("shift_code"), (String) employee.get("start_local"), (String) employee.get("end_local")));
                values.add(Boolean.TRUE.equals(employee.get("portal_access")) ? "On" : "Off");
                values.add(full ? orDash(piiCrypto.decrypt((String) employee.get("pan_enc"))) : Masks.pan((String) employee.get("pan_last4")));
                values.add(full ? orDash(piiCrypto.decrypt((String) employee.get("aadhaar_enc"))) : Masks.aadhaar((String) employee.get("aadhaar_last4")));
                values.add(full ? orDash(piiCrypto.decrypt((String) employee.get("uan_enc"))) : Masks.uan((String) employee.get("uan_last4")));
                values.add(orDash((String) employee.get("bank_name")));
                values.add(full ? orDash(piiCrypto.decrypt((String) employee.get("bank_account_enc"))) : Masks.bank((String) employee.get("bank_account_last4")));
                values.add(orDash((String) employee.get("bank_ifsc")));
                values.add(full ? orDash(piiCrypto.decrypt((String) employee.get("address_enc"))) : (employee.get("address_enc") == null ? "—" : "(hidden)"));
                Row row = sheet.createRow(count++);
                for (int i = 0; i < values.size(); i++) row.createCell(i).setCellValue(values.get(i));
            }
            sheet.createFreezePane(0, 1);
            workbook.write(out);
            workbook.dispose();
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("rows", rows.size());
            after.put("identityNumbers", full ? "full" : "masked");
            audit.log(user.id(), "business.employee_export", "business", TenantContext.current().toString(), null, after, ipAddress);
            return new Export(business + "-employees-" + SubscriptionService.today() + ".xlsx", out.toByteArray());
        }
    }

    static String textOrEmpty(Object value) {
        return value == null ? "" : value.toString();
    }

    static String orDash(String text) {
        return text == null || text.isEmpty() ? "—" : text;
    }

    static String date(Object value) {
        return value == null ? "" : ((Date) value).toLocalDate().toString();
    }
}
