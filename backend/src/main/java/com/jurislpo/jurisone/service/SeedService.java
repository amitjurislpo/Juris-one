package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.config.JurisOneProperties;
import com.jurislpo.jurisone.crypto.Passwords;
import com.jurislpo.jurisone.crypto.PiiCrypto;
import com.jurislpo.jurisone.engine.AttendanceEngine;
import com.jurislpo.jurisone.engine.Permissions;
import com.jurislpo.jurisone.repository.SeedRepository;
import com.jurislpo.jurisone.tenancy.TenantContext;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Seed â€” idempotent (safe to re-run).
 *   Platform: shifts, leave types, FY 2026-27 tax tables and the super admin (SUPER_ADMIN_EMAIL / SUPER_ADMIN_PASSWORD;
 *             an existing password is never changed). Businesses are created by the super admin.
 *   Demo (SEED_DEMO=true, development and tests only): a "Juris Consultants" business with the prototype's people,
 *             holidays, salary structures, declarations, punches, requests and closed Aprâ€“Aug 2026 payroll. Its owner
 *             (JC-002) signs in with SEED_OWNER_PASSWORD, everyone else with SEED_DEMO_PASSWORD.
 */
@Service
public class SeedService {

    private static final Logger log = LoggerFactory.getLogger(SeedService.class);

    private final SeedRepository repo;
    private final PiiCrypto pii;
    private final Passwords passwords;
    private final PayrollOpsService payrollOps;
    private final JobsService jobs;
    private final BusinessSetupService setup;
    private final Environment env;
    private final Path projectDir;
    private final String uploadDir;

    private final com.jurislpo.jurisone.service.PlatformCompanyService platformCompany;

    public SeedService(SeedRepository repo, PiiCrypto pii, Passwords passwords, PayrollOpsService payrollOps, JobsService jobs, BusinessSetupService setup,
                       com.jurislpo.jurisone.service.PlatformCompanyService platformCompany, Environment env, JurisOneProperties props,
                       @Value("${UPLOAD_DIR:./storage}") String uploadDir) {
        this.platformCompany = platformCompany;
        this.repo = repo;
        this.pii = pii;
        this.passwords = passwords;
        this.payrollOps = payrollOps;
        this.jobs = jobs;
        this.setup = setup;
        this.env = env;
        this.projectDir = Path.of(props.projectDir() == null ? "." : props.projectDir()).toAbsolutePath().normalize();
        this.uploadDir = uploadDir;
    }

    static Date d(String iso) {
        return Date.valueOf(LocalDate.parse(iso));
    }

    static long paise(double rupees) {
        return Math.round(rupees * 100);
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ platform role â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    static final String SUPER_ADMIN_ROLE = "super_admin";

    /** The super admin's role: platform-only (no business), with the one platform permission. */
    UUID seedPlatformRole() {
        List<UUID> found = repo.findPlatformRoleIds(SUPER_ADMIN_ROLE);
        UUID id = found.isEmpty() ? UUID.randomUUID() : found.get(0);
        if (found.isEmpty()) {
            repo.insertPlatformRole(id, SUPER_ADMIN_ROLE);
        }
        repo.deletePermissions(id);
        repo.insertPermission(id, Permissions.PLATFORM_MANAGE);
        return id;
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ shifts â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    Map<String, UUID> seedShifts() {
        record S(String code, String name, String start, String end, String region, boolean crosses) {}
        List<S> list = List.of(new S("IN_NIGHT", "India Night", "19:30", "04:30", "IN", true), new S("IN_DAY", "India Day", "09:30", "18:30", "IN", false),
                new S("US_DAY", "US Day", "09:00", "18:00", "US", false));
        Map<String, UUID> ids = new HashMap<>();
        for (S s : list) {
            ids.put(s.code(), repo.upsertShift(UUID.randomUUID(), s.code(), s.name(), s.start(), s.end(), s.region(), s.crosses()));
        }
        return ids;
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ leave types â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    void seedLeaveTypes() {
        repo.insertLeaveTypes();
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ tax (FY 2026-27 â€” Finance/CA must verify) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    void seedTax() {
        String fy = "2026-27";
        String surcharge = "[{\"above\":5000000,\"rate\":0.1},{\"above\":10000000,\"rate\":0.15},{\"above\":20000000,\"rate\":0.25}";
        repo.insertTaxYear(fy, "NEW", 75000, 1200000, true,
                "[{\"upto\":400000,\"rate\":0},{\"upto\":800000,\"rate\":0.05},{\"upto\":1200000,\"rate\":0.1},{\"upto\":1600000,\"rate\":0.15},{\"upto\":2000000,\"rate\":0.2},{\"upto\":2400000,\"rate\":0.25},{\"upto\":null,\"rate\":0.3}]",
                surcharge + "]"); // new regime caps surcharge at 25%
        repo.insertTaxYear(fy, "OLD", 50000, 500000, false,
                "[{\"upto\":250000,\"rate\":0},{\"upto\":500000,\"rate\":0.05},{\"upto\":1000000,\"rate\":0.2},{\"upto\":null,\"rate\":0.3}]",
                surcharge + ",{\"above\":50000000,\"rate\":0.37}]");
        Object[][] sections = {{"HRA", "HRA", null}, {"80C", "80C", 150000}, {"80CCD1B", "80CCD(1B)", 50000}, {"80D_SELF", "80D Â· self & family", 25000},
                {"80D_PARENTS", "80D Â· parents", 25000}, {"80D_PARENTS_SENIOR", "80D Â· senior-citizen parents", 50000}, {"24B", "24(b)", 200000},
                {"80E", "80E", null}, {"80G", "80G", null}, {"80TTA", "80TTA", 10000}};
        for (int i = 0; i < sections.length; i++) {
            repo.insertTaxSection(fy, sections[i][0], sections[i][1], sections[i][2], i);
        }
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ employees â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    record Person(String code, String name, String title, String dept, String manager, String doj, String role, String status, String shift, String tz, String father,
                  String exit, Boolean access, String email) {
        Person(String code, String name, String title, String dept, String manager, String doj, String role, String status, String shift, String tz, String father) {
            this(code, name, title, dept, manager, doj, role, status, shift, tz, father, null, null, null);
        }
    }

    static String region(String tz) {
        return "Asia/Kolkata".equals(tz) ? "IN" : "US";
    }

    public record FakeIds(String phone, String pan, String aadhaar, String uan, String bank, String address) {}

    static String pad(long x, int w) {
        return String.format(Locale.ROOT, "%0" + w + "d", x);
    }

    /** Deterministic fake IDs so demo data is stable across re-seeds. */
    public static FakeIds fakeIds(String code, boolean india) {
        long n = Long.parseLong(code.replaceAll("\\D", ""));
        String letters = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        java.util.function.IntFunction<Character> L = i -> letters.charAt((int) ((n * 7 + i * 5L) % letters.length()));
        return new FakeIds(
                india ? "+91 98" + pad((n * 7919) % 1000, 3) + " " + pad((n * 104729) % 100000, 5) : "+1 (212) 555-" + pad((n * 37) % 10000, 4),
                india ? "" + L.apply(1) + L.apply(2) + L.apply(3) + "P" + L.apply(4) + pad((n * 1231) % 10000, 4) + L.apply(5) : null,
                india ? pad(2000 + ((n * 3571) % 7000), 4) + pad((n * 6007) % 10000, 4) + pad((n * 9001) % 10000, 4) : null,
                india ? "1009" + pad((n * 4421) % 100000000, 8) : null,
                india ? "5010" + pad((n * 77777) % 100000000, 8) : null,
                india ? ((n % 90) + 10) + ", Sector " + ((n % 60) + 1) + ", Noida, Uttar Pradesh 201301" : (100 + n) + " Park Avenue, New York, NY 10017");
    }

    static final List<Person> PEOPLE = List.of(
            new Person("JC-002", "Sana Kapoor", "Portal Administrator", null, null, "2021-04-12", "owner", "ACTIVE", null, "Asia/Kolkata", "Rajeev Kapoor"),
            new Person("JC-004", "Arjun Mehta", "Finance Manager", "Finance", null, "2020-07-01", "fm", "ACTIVE", "IN_DAY", "Asia/Kolkata", "Suresh Mehta"),
            new Person("JC-012", "Riya Malhotra", "HR Manager", "Human Resources", null, "2025-08-04", "hrm", "ACTIVE", "IN_DAY", "Asia/Kolkata", "Anil Malhotra"),
            new Person("JC-015", "Pooja Nair", "HR Executive", "Human Resources", "JC-012", "2026-01-12", "hre", "ACTIVE", "IN_DAY", "Asia/Kolkata", "Mohan Nair"),
            new Person("JC-018", "Rohan Gupta", "Finance Executive", "Finance", "JC-004", "2025-11-03", "fe", "ACTIVE", "IN_DAY", "Asia/Kolkata", "Vinod Gupta"),
            new Person("JC-030", "Vikas Rao", "Legal Researcher", "Litigation Support", "JC-035", "2023-02-01", "employee", "EXITED", "IN_NIGHT", "Asia/Kolkata", "Prakash Rao",
                    "2026-08-31", false, null),
            new Person("JC-035", "Nazneen Khan", "Engineering Manager", "Technology", "JC-004", "2022-02-14", "employee", "ACTIVE", "IN_NIGHT", "Asia/Kolkata", "Imran Khan"),
            new Person("JC-049", "Naman Tyagi", "Litigation Analyst", "Litigation Support", "JC-035", "2024-11-18", "employee", "ACTIVE", "IN_NIGHT", "Asia/Kolkata", "Rakesh Tyagi"),
            new Person("JC-058", "Kavya Iyer", "Contract Analyst", "Contract Management", "JC-035", "2023-06-05", "employee", "ACTIVE", "IN_NIGHT", "Asia/Kolkata", "Ramesh Iyer"),
            new Person("JC-072", "Siddharth Krishna", "Document Reviewer", "Document Review", "JC-035", "2025-01-20", "employee", "ACTIVE", "IN_NIGHT", "Asia/Kolkata", "Gopal Krishna"),
            new Person("JC-074", "Amit Jaspal", "Tech Lead", "Technology", "JC-035", "2026-09-08", "employee", "PROBATION", "IN_NIGHT", "Asia/Kolkata", "Harish Jaspal"),
            new Person("JC-081", "Farhan Siddiqui", "Paralegal", "Litigation Support", "JC-035", "2026-03-02", "employee", "PROBATION", "IN_NIGHT", "Asia/Kolkata", "Salim Siddiqui",
                    null, false, null),
            new Person("JC-091", "Daniel Brooks", "Client Services Lead", "Client Services", "JC-002", "2024-09-09", "employee", "ACTIVE", "US_DAY", "America/Los_Angeles", null),
            new Person("JC-090", "Emily Carter", "Client Success Associate", "Client Services", "JC-091", "2025-05-19", "employee", "ACTIVE", "US_DAY", "America/New_York", null));

    String adminEmail() {
        String e = env.getProperty("SUPER_ADMIN_EMAIL", "");
        return (e.isEmpty() ? "admin@jurislpo.com" : e).toLowerCase();
    }

    /**
     * The super admin: a platform user with no business. An existing super admin (found by email) is moved onto the
     * platform role; their password is never changed.
     */
    UUID upsertSuperAdmin(UUID platformRole, String password) {
        String email = adminEmail();
        List<UUID> existing = repo.findEmployeeIdsByEmail(email);
        UUID id;
        if (existing.isEmpty()) {
            id = UUID.randomUUID();
            repo.insertSuperAdminEmployee(id, email, platformRole, d("2020-06-01"));
        } else {
            id = existing.get(0);
            repo.moveToPlatformRole(id, platformRole);
        }
        setPassword(id, password, true);
        return id;
    }

    UUID upsertEmployee(Person p, Map<String, UUID> roleIds, Map<String, UUID> shiftIds, Map<String, UUID> deptIds, Map<String, UUID> codeToId, UUID createdBy) {
        boolean india = "Asia/Kolkata".equals(p.tz());
        FakeIds ids = fakeIds(p.code(), india);
        String email = p.email() != null ? p.email() : String.join(".", p.name().toLowerCase().split(" ")) + "@jurislpo.com";
        UUID manager = p.manager() == null ? null : codeToId.get(p.manager());
        List<UUID> existing = repo.findEmployeeIdsByCode(p.code());
        if (!existing.isEmpty()) {
            repo.updateReportingManager(existing.get(0), manager);
            return existing.get(0);
        }
        UUID id = UUID.randomUUID();
        repo.insertEmployee(
                id, p.code(), p.name(), p.father(), email, p.title(), roleIds.get(p.role()), p.dept() == null ? null : deptIds.get(p.dept()), manager, d(p.doj()),
                p.exit() == null ? null : d(p.exit()), p.status(), p.tz(), region(p.tz()), p.shift() == null ? null : shiftIds.get(p.shift()),
                !"EXITED".equals(p.status()) && !Boolean.FALSE.equals(p.access()), createdBy,
                pii.encrypt(ids.phone()), PiiCrypto.last4(ids.phone()), pii.encrypt(ids.address()), pii.encrypt(ids.pan()), PiiCrypto.last4(ids.pan()),
                pii.encrypt(ids.aadhaar()), PiiCrypto.last4(ids.aadhaar()), pii.encrypt(ids.uan()), PiiCrypto.last4(ids.uan()), pii.encrypt(ids.bank()),
                PiiCrypto.last4(ids.bank()), india ? "HDFC Bank" : null, india ? "HDFC0001234" : null);
        return id;
    }

    void setPassword(UUID employeeId, String password, boolean superAdmin) {
        List<Map<String, Object>> existing = repo.findUser(employeeId);
        if (!existing.isEmpty() && existing.get(0).get("password_hash") != null) {
            if (superAdmin && !Boolean.TRUE.equals(existing.get(0).get("is_super_admin"))) repo.markSuperAdmin(employeeId);
            return; // never overwrite a password someone has already set
        }
        String hash = password == null ? null : passwords.hash(password);
        Timestamp setAt = password == null ? null : Timestamp.from(Instant.now());
        repo.upsertUser(employeeId, hash, setAt, superAdmin);
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ demo attendance â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    /** Prototype absences (no punches that day) and late marks. */
    static final Map<String, Set<String>> ABSENT = Map.of("JC-074", Set.of("2026-09-22"), "JC-049", Set.of("2026-08-19", "2026-09-10", "2026-09-11"),
            "JC-012", Set.of("2026-09-25"), "JC-058", Set.of("2026-09-03", "2026-09-04", "2026-09-08"), "JC-090", Set.of("2026-09-16"));
    static final Map<String, Set<String>> LATE = Map.of("JC-074", Set.of("2026-09-17"), "JC-072", Set.of("2026-09-09", "2026-09-23"), "JC-049", Set.of("2026-09-15"));

    boolean jobRan(String key) {
        return repo.seedStepRan(key);
    }

    public static int hash(String s) {
        int h = 7;
        for (int i = 0; i < s.length(); i++) h = (h * 31 + s.charAt(i)) % 100003;
        return h;
    }

    /** Deterministic check-ins/outs from the go-live date up to now, once (tracked in job_runs). */
    void seedPunches(Map<String, UUID> codeToId) {
        if (jobRan("punches")) return;
        LocalDate start = LocalDate.parse("2026-04-01");
        Instant now = Instant.now();
        List<SeedRepository.PunchEmployee> emps = repo.findPunchEmployees(codeToId.keySet().toArray(String[]::new));
        List<SeedRepository.HolidayDay> holidays = repo.findHolidays();
        List<Object[]> rows = new ArrayList<>();
        for (SeedRepository.PunchEmployee e : emps) {
            if (e.startLocal() == null) continue;
            for (LocalDate day = e.doj().isAfter(start) ? e.doj() : start; ; day = day.plusDays(1)) {
                if (e.exit() != null && day.isAfter(e.exit())) break;
                Instant inAtBase = AttendanceEngine.atLocal(day, e.startLocal(), e.tz());
                if (inAtBase.isAfter(now)) break;
                LocalDate dd = day;
                int dow = AttendanceEngine.dowOf(day);
                if (dow == 0 || dow == 6 || holidays.stream().anyMatch(h -> h.date().equals(dd) && ("ALL".equals(h.region()) || h.region().equals(e.region())))
                        || ABSENT.getOrDefault(e.code(), Set.of()).contains(day.toString())) continue;
                int h = hash(e.code() + day);
                boolean late = LATE.getOrDefault(e.code(), Set.of()).contains(day.toString());
                int offset = late ? 18 + (h % 15) : (h % 23) - 10; // minutes vs shift start
                Instant inAt = inAtBase.plusSeconds(offset * 60L);
                int worked = (8 * 60 + 36) + (h % 49); // 8h36m â€“ 9h24m
                Instant outAt = inAt.plusSeconds(worked * 60L);
                if (outAt.isAfter(now)) break; // only completed shifts â€” today stays free to check in
                rows.add(new Object[] {UUID.randomUUID(), e.id(), Timestamp.from(inAt), "IN"});
                rows.add(new Object[] {UUID.randomUUID(), e.id(), Timestamp.from(outAt), "OUT"});
            }
        }
        repo.insertPunches(rows);
        repo.insertPunchesRun(UUID.randomUUID(), "{\"punches\":" + rows.size() + "}");
        log.info("Demo punches: {}", rows.size());
    }

    /**
     * Closed payroll for Aprilâ€“August 2026 (the prototype shows earlier months closed and September
     * in review). Closing runs the real payroll + snapshot + PDF code; nobody is notified.
     */
    void seedPayroll(Map<String, UUID> codeToId) {
        for (String ym : List.of("2026-04", "2026-05", "2026-06", "2026-07", "2026-08")) {
            List<String> st = repo.findPayrollRunStatus(ym);
            if (!st.isEmpty() && "CLOSED".equals(st.get(0))) continue;
            String next = YearMonth.parse(ym).plusMonths(1).atDay(1).toString();
            int n = payrollOps.closeRun(ym, codeToId.get("JC-004"), false, next);
            log.info("Payroll {} closed â€” {} payslips", ym, n);
        }
    }

    /** Demo declarations name a proof file â€” write a small PDF for each so Finance can open it. */
    void seedProofFiles() throws IOException {
        List<Map<String, Object>> items = repo.findItemsMissingProofFile();
        for (Map<String, Object> it : items) {
            Path dir = projectDir.resolve(uploadDir).normalize().resolve("proofs").resolve(it.get("employee_id").toString());
            Files.createDirectories(dir);
            Path abs = dir.resolve(it.get("item_key") + "-demo.pdf");
            long amount = ((Number) it.get("amount")).longValue();
            String body = "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj 2 0 obj<</Type/Pages/Count 0/Kids[]>>endobj\n% Demo proof: " + it.get("item_key") + ", "
                    + num(amount / 100.0) + " INR\ntrailer<</Root 1 0 R>>\n%%EOF\n";
            Files.writeString(abs, body, StandardCharsets.UTF_8);
            repo.updateProofPath(it.get("id"), projectDir.relativize(abs).toString());
        }
        if (!items.isEmpty()) log.info("Demo proof files: {}", items.size());
    }

    static String num(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }

    /** Prototype leave requests and regularizations (once). Balances come from the accrual job. */
    void seedRequests(Map<String, UUID> codeToId) {
        if (jobRan("requests")) return;
        UUID hr = codeToId.get("JC-012");
        String[][] leaves = {
                {"JC-058", "EL", "2026-10-23", "2026-10-23", "Personal work", "PENDING", "2026-10-05"},
                {"JC-074", "CL", "2026-11-02", "2026-11-02", "", "PENDING", "2026-10-04"},
                {"JC-049", "CL", "2026-10-21", "2026-10-22", "Sisterâ€™s wedding", "PENDING", "2026-10-06"},
                {"JC-058", "EL", "2026-10-06", "2026-10-06", "Family function", "APPROVED", "2026-09-30"},
                {"JC-074", "LOP", "2026-09-22", "2026-09-22", "Unapplied absence", "APPROVED", "2026-10-01"}};
        for (String[] l : leaves) {
            String code = l[0], type = l[1], from = l[2], to = l[3], reason = l[4], status = l[5], applied = l[6];
            int days = from.equals(to) ? 1 : 2;
            UUID id = UUID.randomUUID();
            boolean approved = "APPROVED".equals(status);
            repo.insertLeaveRequest(
                    id, codeToId.get(code), type, d(from), d(to), days, "LOP".equals(type) ? days : 0, reason.isEmpty() ? null : reason, status,
                    Timestamp.from(Instant.parse(applied + "T05:00:00Z")), approved ? hr : null, approved ? Timestamp.from(Instant.parse(applied + "T09:00:00Z")) : null);
            if (approved && !"LOP".equals(type)) {
                repo.insertLeaveUsed(UUID.randomUUID(), codeToId.get(code), type, BigDecimal.valueOf(-days), id.toString());
            }
        }

        // JC-072 forgot to check out of the 1 Oct night shift (last activity 4:36 AM on 2 Oct)
        List<UUID> out = repo.findOutPunch(
                codeToId.get("JC-072"), Timestamp.from(Instant.parse("2026-10-01T20:00:00Z")), Timestamp.from(Instant.parse("2026-10-02T03:00:00Z")));
        if (!out.isEmpty()) repo.deletePunch(out.get(0));
        repo.insertMissedCheckout(
                UUID.randomUUID(), codeToId.get("JC-072"), d("2026-10-01"), Timestamp.from(AttendanceEngine.atLocal(LocalDate.parse("2026-10-02"), "04:36", "Asia/Kolkata")),
                "Laptop shut down on battery", Timestamp.from(Instant.parse("2026-10-02T06:00:00Z")));
        // JC-049 worked 8h40m on Gandhi Jayanti â†’ asks for a comp-off
        repo.insertPunchPair(
                UUID.randomUUID(), codeToId.get("JC-049"), Timestamp.from(AttendanceEngine.atLocal(LocalDate.parse("2026-10-02"), "19:30", "Asia/Kolkata")),
                UUID.randomUUID(), Timestamp.from(AttendanceEngine.atLocal(LocalDate.parse("2026-10-03"), "04:10", "Asia/Kolkata")));
        repo.insertHolidayWork(
                UUID.randomUUID(), codeToId.get("JC-049"), d("2026-10-02"), "Client filing deadline", Timestamp.from(Instant.parse("2026-10-03T06:00:00Z")));
        repo.insertRequestsRun(UUID.randomUUID());
        log.info("Demo leave requests and regularizations added");
    }

    UUID upsertDepartment(String name, String description, UUID createdBy) {
        return repo.upsertDepartment(UUID.randomUUID(), name, description, createdBy);
    }

    public void run() throws IOException {
        String adminPw = env.getProperty("SUPER_ADMIN_PASSWORD", "");
        if (adminPw.isBlank()) throw new IllegalStateException("Set SUPER_ADMIN_PASSWORD (in .env or the environment) before seeding.");

        // platform: shared shifts, leave types and tax tables, and the super admin (no business)
        UUID platformRole = seedPlatformRole();
        Map<String, UUID> shiftIds = seedShifts();
        seedLeaveTypes();
        seedTax();
        UUID superAdminId = upsertSuperAdmin(platformRole, adminPw);
        platformCompany.ensure(); // the operator's own company; the super admin belongs to it
        seedPlans();

        // demo business (development and tests only): the prototype company, with Sana as its owner (sub admin)
        boolean demo = "true".equals(env.getProperty("SEED_DEMO", "false"));
        if (demo) {
            List<UUID> found = repo.findDemoBusinessIds();
            UUID business = found.isEmpty()
                    ? setup.create(new BusinessSetupService.NewBusiness("Juris Consultants", "Juris Consultants LLP", "juris-consultants", "JC", "Asia/Kolkata",
                            "hr@jurislpo.com", null, "[Registered office address]", null), superAdminId)
                    : found.get(0);
            if (repo.countSubscriptions(business) == 0) {
                UUID plan = repo.findDemoPlanId();
                repo.insertDemoSubscription(UUID.randomUUID(), business, plan, superAdminId);
            }
            TenantContext.run(business, () -> {
                try {
                    seedDemo(superAdminId, shiftIds);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }

        long[] counts = {count("businesses"), count("employees"), count("roles"), count("departments"), count("holidays")};
        log.info("Seed complete â€” businesses {}, employees {}, roles {}, departments {}, holidays {}", counts[0], counts[1], counts[2], counts[3], counts[4]);
        log.info("Super admin: {}{}", adminEmail(), demo ? " Â· demo business \"Juris Consultants\": owner JC-002 (SEED_OWNER_PASSWORD), others (SEED_DEMO_PASSWORD)" : "");
    }

    /**
     * Two starting plans for the catalogue (the super admin sets their prices and limits). Existing plans are left as they are.
     */
    void seedPlans() {
        Object[][] plans = {
                {"Standard", "HRMS and payroll for one business. Set the price before offering it.", new String[0]},
                {"Standard + Juris Track", "Everything in Standard, plus Juris Track. Set the price before offering it.", new String[] {"JURIS_TRACK"}}};
        for (Object[] p : plans) {
            if (repo.countPlansNamed(p[0]) > 0) continue;
            repo.insertStarterPlan(UUID.randomUUID(), (String) p[0], (String) p[1], (String[]) p[2]);
        }
    }

    /** The demo business's people and history; runs inside that business. */
    void seedDemo(UUID superAdminId, Map<String, UUID> shiftIds) throws IOException {
        Map<String, UUID> roleIds = setup.initDefaults(superAdminId);
        Map<String, UUID> deptIds = new HashMap<>();
        deptIds.putAll(repo.findDepartmentIds());
        Map<String, UUID> codeToId = new LinkedHashMap<>();
            // Sana (the owner) needs to exist before she can be "creator" of the departments in the demo story.
            codeToId.put("JC-002", upsertEmployee(PEOPLE.get(0), roleIds, shiftIds, deptIds, codeToId, superAdminId));
            for (Person p : PEOPLE.subList(1, PEOPLE.size())) {
                if (p.dept() != null && !deptIds.containsKey(p.dept())) {
                    UUID creator = codeToId.getOrDefault("JC-012", codeToId.get("JC-002"));
                    deptIds.put(p.dept(), upsertDepartment(p.dept(), null, creator));
                }
            }
            // two passes so managers resolve regardless of order
            for (int pass = 1; pass <= 2; pass++) {
                for (Person p : PEOPLE.subList(1, PEOPLE.size())) {
                    UUID creator = "fe".equals(p.role()) ? codeToId.get("JC-004")
                            : Set.of("hrm", "fm").contains(p.role()) ? codeToId.get("JC-002") : codeToId.get("JC-012");
                    codeToId.put(p.code(), upsertEmployee(p, roleIds, shiftIds, deptIds, codeToId, creator != null ? creator : codeToId.get("JC-002")));
                }
                if (pass == 1) {
                    // departments created by Riya once she exists
                    for (String name : List.of("Technology", "Litigation Support", "Contract Management", "Document Review", "Client Services")) {
                        repo.updateDepartmentCreator(name, codeToId.get("JC-012"));
                    }
                }
            }
            String[][] heads = {{"Human Resources", "JC-012"}, {"Finance", "JC-004"}, {"Technology", "JC-035"}, {"Litigation Support", "JC-035"}, {"Client Services", "JC-091"}};
            for (String[] h : heads) repo.updateDepartmentHead(h[0], codeToId.get(h[1]));

            String demoPw = env.getProperty("SEED_DEMO_PASSWORD", "");
            String ownerPw = env.getProperty("SEED_OWNER_PASSWORD", "");
            for (Person p : PEOPLE) {
                String pw = "owner".equals(p.role()) ? ownerPw : demoPw;
                setPassword(codeToId.get(p.code()), pw.isEmpty() ? null : pw, false);
            }

            // 2026 holidays (prototype calendar), added by the HR Manager
            String[][] hol = {{"2026-01-26", "Republic Day", "IN"}, {"2026-03-04", "Holi", "IN"}, {"2026-08-15", "Independence Day", "IN"}, {"2026-09-07", "Labor Day", "US"},
                    {"2026-09-14", "Ganesh Chaturthi", "IN"}, {"2026-10-02", "Gandhi Jayanti", "IN"}, {"2026-10-20", "Dussehra", "IN"}, {"2026-11-09", "Govardhan Puja", "IN"},
                    {"2026-11-24", "Guru Nanak Jayanti", "IN"}, {"2026-11-26", "Thanksgiving Day", "US"}, {"2026-12-25", "Christmas", "ALL"},
                    {"2026-01-01", "New Year's Day", "US"}, {"2026-05-25", "Memorial Day", "US"}, {"2026-07-03", "Independence Day (observed)", "US"}};
            for (String[] h : hol) {
                repo.insertHoliday(UUID.randomUUID(), d(h[0]), h[1], h[2], codeToId.get("JC-012"));
            }

            // Salary structures (monthly, â‚¹ â†’ paise). Effective-dated; history only grows.
            Object[][] st = {
                    {"JC-002", "2026-04-01", 55000, 22000, 5500, 27500, "APPRAISAL"},
                    {"JC-004", "2025-04-01", 55000, 22000, 5500, 27500, "APPRAISAL"}, {"JC-004", "2026-04-01", 60000, 24000, 6000, 30000, "APPRAISAL"},
                    {"JC-012", "2025-08-04", 47500, 19000, 4750, 23750, "JOINING"},
                    {"JC-015", "2026-01-12", 26000, 10400, 2600, 13000, "JOINING"},
                    {"JC-018", "2025-11-03", 30000, 12000, 3000, 15000, "JOINING"},
                    {"JC-030", "2023-02-01", 28000, 11200, 2800, 14000, "JOINING"},
                    {"JC-035", "2025-04-01", 82500, 33000, 8250, 41250, "APPRAISAL"}, {"JC-035", "2026-04-01", 92500, 37000, 9250, 46250, "APPRAISAL"},
                    {"JC-049", "2024-11-18", 31000, 12400, 3100, 15500, "JOINING"},
                    {"JC-058", "2023-06-05", 36000, 14400, 3600, 18000, "JOINING"},
                    {"JC-072", "2025-01-20", 29000, 11600, 2900, 14500, "JOINING"},
                    {"JC-074", "2026-09-08", 75000, 30000, 7500, 37500, "JOINING"},
                    {"JC-081", "2026-03-02", 24000, 9600, 2400, 12000, "JOINING"}};
            for (Object[] s : st) {
                repo.insertSalaryStructure(
                        UUID.randomUUID(), codeToId.get((String) s[0]), d((String) s[1]), paise((Integer) s[2]), paise((Integer) s[3]), paise((Integer) s[4]),
                        paise((Integer) s[5]), s[6], codeToId.get("JC-004"));
            }

            // Tax profiles + old-regime declarations (prototype)
            String fy = "2026-27";
            repo.insertTaxProfile(codeToId.get("JC-074"), fy, "NEW", paise(750000), paise(38000));
            record Decl(String code, Map<String, Object[]> items, String status) {}
            Map<String, Object[]> d58 = new LinkedHashMap<>();
            d58.put("rent", new Object[] {"HRA", 180000});
            d58.put("ppf", new Object[] {"80C", 80000});
            d58.put("lic", new Object[] {"80C", 25000});
            d58.put("medSelf", new Object[] {"80D_SELF", 18000});
            Map<String, Object[]> d49 = new LinkedHashMap<>();
            d49.put("rent", new Object[] {"HRA", 144000});
            d49.put("elss", new Object[] {"80C", 60000});
            d49.put("medParents", new Object[] {"80D_PARENTS", 30000});
            for (Decl dc : List.of(new Decl("JC-058", d58, "PENDING"), new Decl("JC-049", d49, "APPROVED"))) {
                UUID employeeId = codeToId.get(dc.code());
                repo.insertTaxProfile(employeeId, fy, "OLD", 0, 0);
                boolean approved = "APPROVED".equals(dc.status());
                for (Map.Entry<String, Object[]> it : dc.items().entrySet()) {
                    repo.insertDeclarationItem(
                            UUID.randomUUID(), employeeId, fy, it.getValue()[0], it.getKey(), paise((Integer) it.getValue()[1]), it.getKey() + "-proof.pdf", dc.status(),
                            approved ? codeToId.get("JC-004") : null, approved ? Timestamp.from(Instant.now()) : null);
                }
            }

            seedPunches(codeToId);
            seedRequests(codeToId);
            seedPayroll(codeToId);
            seedProofFiles();
        // leave balances: credit every month so far (idempotent)
        jobs.jobLeaveAccrual(Instant.now());
    }

    /** Deletes stored payslip PDFs and proof files (after a database reset they belong to nothing). */
    public void clearStoredFiles() throws IOException {
        for (String sub : List.of("payslips", "proofs")) {
            Path dir = projectDir.resolve(uploadDir).normalize().resolve(sub);
            if (!Files.exists(dir)) continue;
            try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
                for (Path f : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(f);
            }
        }
        log.info("Stored payslips and proofs cleared");
    }

    long count(String table) {
        return repo.countRows(table);
    }
}
