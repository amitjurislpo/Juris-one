package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.config.JurisOneProperties;
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
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Seed — idempotent (safe to re-run).
 *   Platform: shifts, leave types, FY 2026-27 tax tables and the super admin (jurisone.seed.super-admin-email). Businesses are created by the super admin.
 *   Demo (jurisone.seed.demo=true, development and tests only): a "Juris Consultants" business with the prototype's people,
 *             holidays, salary structures, declarations, punches, requests and closed Apr–Aug 2026 payroll. Its owner
 *             is JC-002. Everyone signs in with their email and an emailed code (fixed 000000 in dev).
 */
@Service
public class SeedService {

    private static final Logger log = LoggerFactory.getLogger(SeedService.class);

    private final SeedRepository repo;
    private final PiiCrypto piiCrypto;
    private final PayrollOperationsService payrollOperations;
    private final JobsService jobs;
    private final BusinessSetupService setup;
    private final Environment environment;
    private final Path storageDir;

    private final com.jurislpo.jurisone.service.PlatformCompanyService platformCompany;
    private final TaskSeedService taskSeed;

    public SeedService(SeedRepository repo, PiiCrypto piiCrypto, PayrollOperationsService payrollOperations, JobsService jobs, BusinessSetupService setup,
                       com.jurislpo.jurisone.service.PlatformCompanyService platformCompany, TaskSeedService taskSeed, Environment environment, JurisOneProperties props) {
        this.platformCompany = platformCompany;
        this.taskSeed = taskSeed;
        this.repo = repo;
        this.piiCrypto = piiCrypto;
        this.payrollOperations = payrollOperations;
        this.jobs = jobs;
        this.setup = setup;
        this.environment = environment;
        this.storageDir = Path.of(props.storageDir()).toAbsolutePath().normalize();
    }

    static Date parseDate(String isoDate) {
        return Date.valueOf(LocalDate.parse(isoDate));
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
        record ShiftSeed(String code, String name, String start, String end, String region, boolean crosses) {}
        List<ShiftSeed> list = List.of(new ShiftSeed("IN_NIGHT", "India Night", "19:30", "04:30", "IN", true), new ShiftSeed("IN_DAY", "India Day", "09:30", "18:30", "IN", false),
                new ShiftSeed("US_DAY", "US Day", "09:00", "18:00", "US", false));
        Map<String, UUID> ids = new HashMap<>();
        for (ShiftSeed shiftSeed : list) {
            ids.put(shiftSeed.code(), repo.upsertShift(UUID.randomUUID(), shiftSeed.code(), shiftSeed.name(), shiftSeed.start(), shiftSeed.end(), shiftSeed.region(), shiftSeed.crosses()));
        }
        return ids;
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ leave types â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    void seedLeaveTypes() {
        repo.insertLeaveTypes();
    }

    // ───────────────────────── tax (FY 2026-27 — Finance/CA must verify) ─────────────────────────
    void seedTax() {
        String financialYear = "2026-27";
        String surcharge = "[{\"above\":5000000,\"rate\":0.1},{\"above\":10000000,\"rate\":0.15},{\"above\":20000000,\"rate\":0.25}";
        repo.insertTaxYear(financialYear, "NEW", 75000, 1200000, true,
                "[{\"upto\":400000,\"rate\":0},{\"upto\":800000,\"rate\":0.05},{\"upto\":1200000,\"rate\":0.1},{\"upto\":1600000,\"rate\":0.15},{\"upto\":2000000,\"rate\":0.2},{\"upto\":2400000,\"rate\":0.25},{\"upto\":null,\"rate\":0.3}]",
                surcharge + "]"); // new regime caps surcharge at 25%
        repo.insertTaxYear(financialYear, "OLD", 50000, 500000, false,
                "[{\"upto\":250000,\"rate\":0},{\"upto\":500000,\"rate\":0.05},{\"upto\":1000000,\"rate\":0.2},{\"upto\":null,\"rate\":0.3}]",
                surcharge + ",{\"above\":50000000,\"rate\":0.37}]");
        Object[][] sections = {{"HRA", "HRA", null}, {"80C", "80C", 150000}, {"80CCD1B", "80CCD(1B)", 50000}, {"80D_SELF", "80D · self & family", 25000},
                {"80D_PARENTS", "80D · parents", 25000}, {"80D_PARENTS_SENIOR", "80D · senior-citizen parents", 50000}, {"24B", "24(b)", 200000},
                {"80E", "80E", null}, {"80G", "80G", null}, {"80TTA", "80TTA", 10000}};
        for (int i = 0; i < sections.length; i++) {
            repo.insertTaxSection(financialYear, sections[i][0], sections[i][1], sections[i][2], i);
        }
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ employees â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    record Person(String code, String name, String title, String department, String manager, String dateOfJoining, String role, String status, String shift, String timeZone, String fatherName,
                  String exitDate, Boolean access, String email) {
        Person(String code, String name, String title, String department, String manager, String dateOfJoining, String role, String status, String shift, String timeZone, String father) {
            this(code, name, title, department, manager, dateOfJoining, role, status, shift, timeZone, father, null, null, null);
        }
    }

    static String region(String timeZone) {
        return "Asia/Kolkata".equals(timeZone) ? "IN" : "US";
    }

    public record FakeIds(String phone, String pan, String aadhaar, String uan, String bank, String address) {}

    static String zeroPad(long number, int width) {
        return String.format(Locale.ROOT, "%0" + width + "d", number);
    }

    /** Deterministic fake IDs so demo data is stable across re-seeds. */
    public static FakeIds fakeIds(String code, boolean india) {
        long number = Long.parseLong(code.replaceAll("\\D", ""));
        String letters = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        java.util.function.IntFunction<Character> L = i -> letters.charAt((int) ((number * 7 + i * 5L) % letters.length()));
        return new FakeIds(
                india ? "+91 98" + zeroPad((number * 7919) % 1000, 3) + " " + zeroPad((number * 104729) % 100000, 5) : "+1 (212) 555-" + zeroPad((number * 37) % 10000, 4),
                india ? "" + L.apply(1) + L.apply(2) + L.apply(3) + "P" + L.apply(4) + zeroPad((number * 1231) % 10000, 4) + L.apply(5) : null,
                india ? zeroPad(2000 + ((number * 3571) % 7000), 4) + zeroPad((number * 6007) % 10000, 4) + zeroPad((number * 9001) % 10000, 4) : null,
                india ? "1009" + zeroPad((number * 4421) % 100000000, 8) : null,
                india ? "5010" + zeroPad((number * 77777) % 100000000, 8) : null,
                india ? ((number % 90) + 10) + ", Sector " + ((number % 60) + 1) + ", Noida, Uttar Pradesh 201301" : (100 + number) + " Park Avenue, New York, NY 10017");
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
        String email = environment.getProperty("jurisone.seed.super-admin-email", "");
        return (email.isEmpty() ? "admin@jurislpo.com" : email).toLowerCase();
    }

    /**
     * The super admin: a platform user with no business. An existing super admin (found by email) is moved onto the
     * platform role.
     */
    UUID upsertSuperAdmin(UUID platformRole) {
        String email = adminEmail();
        List<UUID> existing = repo.findEmployeeIdsByEmail(email);
        UUID id;
        if (existing.isEmpty()) {
            id = UUID.randomUUID();
            repo.insertSuperAdminEmployee(id, email, platformRole, parseDate("2020-06-01"));
        } else {
            id = existing.get(0);
            repo.moveToPlatformRole(id, platformRole);
        }
        ensureUser(id, true);
        return id;
    }

    UUID upsertEmployee(Person person, Map<String, UUID> roleIds, Map<String, UUID> shiftIds, Map<String, UUID> departmentIds, Map<String, UUID> codeToId, UUID createdBy) {
        boolean india = "Asia/Kolkata".equals(person.timeZone());
        FakeIds ids = fakeIds(person.code(), india);
        String email = person.email() != null ? person.email() : String.join(".", person.name().toLowerCase().split(" ")) + "@jurislpo.com";
        UUID manager = person.manager() == null ? null : codeToId.get(person.manager());
        List<UUID> existing = repo.findEmployeeIdsByCode(person.code());
        if (!existing.isEmpty()) {
            repo.updateReportingManager(existing.get(0), manager);
            return existing.get(0);
        }
        UUID id = UUID.randomUUID();
        repo.insertEmployee(
                id, person.code(), person.name(), person.fatherName(), email, person.title(), roleIds.get(person.role()), person.department() == null ? null : departmentIds.get(person.department()), manager, parseDate(person.dateOfJoining()),
                person.exitDate() == null ? null : parseDate(person.exitDate()), person.status(), person.timeZone(), region(person.timeZone()), person.shift() == null ? null : shiftIds.get(person.shift()),
                !"EXITED".equals(person.status()) && !Boolean.FALSE.equals(person.access()), createdBy,
                piiCrypto.encrypt(ids.phone()), PiiCrypto.last4(ids.phone()), piiCrypto.encrypt(ids.address()), piiCrypto.encrypt(ids.pan()), PiiCrypto.last4(ids.pan()),
                piiCrypto.encrypt(ids.aadhaar()), PiiCrypto.last4(ids.aadhaar()), piiCrypto.encrypt(ids.uan()), PiiCrypto.last4(ids.uan()), piiCrypto.encrypt(ids.bank()),
                PiiCrypto.last4(ids.bank()), india ? "HDFC Bank" : null, india ? "HDFC0001234" : null);
        return id;
    }

    void ensureUser(UUID employeeId, boolean superAdmin) {
        List<Map<String, Object>> existing = repo.findUser(employeeId);
        if (existing.isEmpty()) repo.insertUser(employeeId, superAdmin);
        else if (superAdmin && !Boolean.TRUE.equals(existing.get(0).get("is_super_admin"))) repo.markSuperAdmin(employeeId);
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ demo attendance â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    /** Prototype absences (no punches that day) and late marks. */
    static final Map<String, Set<String>> ABSENT = Map.of("JC-074", Set.of("2026-09-22"), "JC-049", Set.of("2026-08-19", "2026-09-10", "2026-09-11"),
            "JC-012", Set.of("2026-09-25"), "JC-058", Set.of("2026-09-03", "2026-09-04", "2026-09-08"), "JC-090", Set.of("2026-09-16"));
    static final Map<String, Set<String>> LATE = Map.of("JC-074", Set.of("2026-09-17"), "JC-072", Set.of("2026-09-09", "2026-09-23"), "JC-049", Set.of("2026-09-15"));

    boolean jobRan(String key) {
        return repo.seedStepRan(key);
    }

    public static int hash(String text) {
        int hour = 7;
        for (int i = 0; i < text.length(); i++) hour = (hour * 31 + text.charAt(i)) % 100003;
        return hour;
    }

    /** Deterministic check-ins/outs from the go-live date up to now, once (tracked in job_runs). */
    void seedPunches(Map<String, UUID> codeToId) {
        if (jobRan("punches")) return;
        LocalDate start = LocalDate.parse("2026-04-01");
        Instant now = Instant.now();
        List<SeedRepository.PunchEmployee> employees = repo.findPunchEmployees(codeToId.keySet().toArray(String[]::new));
        List<SeedRepository.HolidayDay> holidays = repo.findHolidays();
        List<Object[]> rows = new ArrayList<>();
        for (SeedRepository.PunchEmployee punchEmployee : employees) {
            if (punchEmployee.startLocal() == null) continue;
            for (LocalDate day = punchEmployee.dateOfJoining().isAfter(start) ? punchEmployee.dateOfJoining() : start; ; day = day.plusDays(1)) {
                if (punchEmployee.exitDate() != null && day.isAfter(punchEmployee.exitDate())) break;
                Instant inAtBase = AttendanceEngine.atLocal(day, punchEmployee.startLocal(), punchEmployee.timeZone());
                if (inAtBase.isAfter(now)) break;
                LocalDate currentDay = day;
                int dayOfWeek = AttendanceEngine.dowOf(day);
                if (dayOfWeek == 0 || dayOfWeek == 6 || holidays.stream().anyMatch(h -> h.date().equals(currentDay) && ("ALL".equals(h.region()) || h.region().equals(punchEmployee.region())))
                        || ABSENT.getOrDefault(punchEmployee.code(), Set.of()).contains(day.toString())) continue;
                int hour = hash(punchEmployee.code() + day);
                boolean late = LATE.getOrDefault(punchEmployee.code(), Set.of()).contains(day.toString());
                int offset = late ? 18 + (hour % 15) : (hour % 23) - 10; // minutes vs shift start
                Instant inAt = inAtBase.plusSeconds(offset * 60L);
                int worked = (8 * 60 + 36) + (hour % 49); // 8h36m – 9h24m
                Instant outAt = inAt.plusSeconds(worked * 60L);
                if (outAt.isAfter(now)) break; // only completed shifts — today stays free to check in
                rows.add(new Object[] {UUID.randomUUID(), punchEmployee.id(), Timestamp.from(inAt), "IN"});
                rows.add(new Object[] {UUID.randomUUID(), punchEmployee.id(), Timestamp.from(outAt), "OUT"});
            }
        }
        repo.insertPunches(rows);
        repo.insertPunchesRun(UUID.randomUUID(), "{\"punches\":" + rows.size() + "}");
        log.info("Demo punches: {}", rows.size());
    }

    /**
     * Closed payroll for April–August 2026 (the prototype shows earlier months closed and September
     * in review). Closing runs the real payroll + snapshot + PDF code; nobody is notified.
     */
    void seedPayroll(Map<String, UUID> codeToId) {
        for (String yearMonth : List.of("2026-04", "2026-05", "2026-06", "2026-07", "2026-08")) {
            List<String> statuses = repo.findPayrollRunStatus(yearMonth);
            if (!statuses.isEmpty() && "CLOSED".equals(statuses.get(0))) continue;
            String next = YearMonth.parse(yearMonth).plusMonths(1).atDay(1).toString();
            int closedCount = payrollOperations.closeRun(yearMonth, codeToId.get("JC-004"), false, next);
            log.info("Payroll {} closed — {} payslips", yearMonth, closedCount);
        }
    }

    /** Demo declarations name a proof file — write a small PDF for each so Finance can open it. */
    void seedProofFiles() throws IOException {
        List<Map<String, Object>> items = repo.findItemsMissingProofFile();
        for (Map<String, Object> item : items) {
            Path directory = storageDir.resolve("proofs").resolve(item.get("employee_id").toString());
            Files.createDirectories(directory);
            Path absolutePath = directory.resolve(item.get("item_key") + "-demo.pdf");
            long amount = ((Number) item.get("amount")).longValue();
            String body = "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj 2 0 obj<</Type/Pages/Count 0/Kids[]>>endobj\n% Demo proof: " + item.get("item_key") + ", "
                    + formatNumber(amount / 100.0) + " INR\ntrailer<</Root 1 0 R>>\n%%EOF\n";
            Files.writeString(absolutePath, body, StandardCharsets.UTF_8);
            repo.updateProofPath(item.get("id"), storageDir.relativize(absolutePath).toString());
        }
        if (!items.isEmpty()) log.info("Demo proof files: {}", items.size());
    }

    static String formatNumber(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    /** Prototype leave requests and regularizations (once). Balances come from the accrual job. */
    void seedRequests(Map<String, UUID> codeToId) {
        if (jobRan("requests")) return;
        UUID hrId = codeToId.get("JC-012");
        String[][] leaves = {
                {"JC-058", "EL", "2026-10-23", "2026-10-23", "Personal work", "PENDING", "2026-10-05"},
                {"JC-074", "CL", "2026-11-02", "2026-11-02", "", "PENDING", "2026-10-04"},
                {"JC-049", "CL", "2026-10-21", "2026-10-22", "Sister’s wedding", "PENDING", "2026-10-06"},
                {"JC-058", "EL", "2026-10-06", "2026-10-06", "Family function", "APPROVED", "2026-09-30"},
                {"JC-074", "LOP", "2026-09-22", "2026-09-22", "Unapplied absence", "APPROVED", "2026-10-01"}};
        for (String[] leaveRow : leaves) {
            String code = leaveRow[0], type = leaveRow[1], from = leaveRow[2], to = leaveRow[3], reason = leaveRow[4], status = leaveRow[5], applied = leaveRow[6];
            int days = from.equals(to) ? 1 : 2;
            UUID id = UUID.randomUUID();
            boolean approved = "APPROVED".equals(status);
            repo.insertLeaveRequest(
                    id, codeToId.get(code), type, parseDate(from), parseDate(to), days, "LOP".equals(type) ? days : 0, reason.isEmpty() ? null : reason, status,
                    Timestamp.from(Instant.parse(applied + "T05:00:00Z")), approved ? hrId : null, approved ? Timestamp.from(Instant.parse(applied + "T09:00:00Z")) : null);
            if (approved && !"LOP".equals(type)) {
                repo.insertLeaveUsed(UUID.randomUUID(), codeToId.get(code), type, BigDecimal.valueOf(-days), id.toString());
            }
        }

        // JC-072 forgot to check out of the 1 Oct night shift (last activity 4:36 AM on 2 Oct)
        List<UUID> out = repo.findOutPunch(
                codeToId.get("JC-072"), Timestamp.from(Instant.parse("2026-10-01T20:00:00Z")), Timestamp.from(Instant.parse("2026-10-02T03:00:00Z")));
        if (!out.isEmpty()) repo.deletePunch(out.get(0));
        repo.insertMissedCheckout(
                UUID.randomUUID(), codeToId.get("JC-072"), parseDate("2026-10-01"), Timestamp.from(AttendanceEngine.atLocal(LocalDate.parse("2026-10-02"), "04:36", "Asia/Kolkata")),
                "Laptop shut down on battery", Timestamp.from(Instant.parse("2026-10-02T06:00:00Z")));
        // JC-049 worked 8h40m on Gandhi Jayanti → asks for a comp-off
        repo.insertPunchPair(
                UUID.randomUUID(), codeToId.get("JC-049"), Timestamp.from(AttendanceEngine.atLocal(LocalDate.parse("2026-10-02"), "19:30", "Asia/Kolkata")),
                UUID.randomUUID(), Timestamp.from(AttendanceEngine.atLocal(LocalDate.parse("2026-10-03"), "04:10", "Asia/Kolkata")));
        repo.insertHolidayWork(
                UUID.randomUUID(), codeToId.get("JC-049"), parseDate("2026-10-02"), "Client filing deadline", Timestamp.from(Instant.parse("2026-10-03T06:00:00Z")));
        repo.insertRequestsRun(UUID.randomUUID());
        log.info("Demo leave requests and regularizations added");
    }

    UUID upsertDepartment(String name, String description, UUID createdBy) {
        return repo.upsertDepartment(UUID.randomUUID(), name, description, createdBy);
    }

    public void run() throws IOException {
        // platform: shared shifts, leave types and tax tables, and the super admin (no business)
        UUID platformRole = seedPlatformRole();
        Map<String, UUID> shiftIds = seedShifts();
        seedLeaveTypes();
        seedTax();
        UUID superAdminId = upsertSuperAdmin(platformRole);
        platformCompany.ensure(); // the operator's own company; the super admin belongs to it
        seedPlans();

        // demo business (development and tests only): the prototype company, with Sana as its owner (sub admin)
        boolean demo = "true".equals(environment.getProperty("jurisone.seed.demo", "false"));
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
        log.info("Seed complete — businesses {}, employees {}, roles {}, departments {}, holidays {}", counts[0], counts[1], counts[2], counts[3], counts[4]);
        log.info("Super admin: {}{}", adminEmail(), demo ? " · demo business \"Juris Consultants\": owner JC-002" : "");
    }

    /**
     * Two starting plans for the catalogue (the super admin sets their prices and limits). Existing plans are left as they are.
     */
    void seedPlans() {
        Object[][] plans = {
                {"Standard", "HRMS and payroll for one business. Set the price before offering it.", new String[0]},
                {"Standard + Juris Track", "Everything in Standard, plus Juris Track. Set the price before offering it.", new String[] {"JURIS_TRACK"}}};
        for (Object[] plan : plans) {
            if (repo.countPlansNamed(plan[0]) > 0) continue;
            repo.insertStarterPlan(UUID.randomUUID(), (String) plan[0], (String) plan[1], (String[]) plan[2]);
        }
    }

    /** The demo business's people and history; runs inside that business. */
    void seedDemo(UUID superAdminId, Map<String, UUID> shiftIds) throws IOException {
        Map<String, UUID> roleIds = setup.initDefaults(superAdminId);
        Map<String, UUID> departmentIds = new HashMap<>();
        departmentIds.putAll(repo.findDepartmentIds());
        Map<String, UUID> codeToId = new LinkedHashMap<>();
            // Sana (the owner) needs to exist before she can be "creator" of the departments in the demo story.
            codeToId.put("JC-002", upsertEmployee(PEOPLE.get(0), roleIds, shiftIds, departmentIds, codeToId, superAdminId));
            for (Person person : PEOPLE.subList(1, PEOPLE.size())) {
                if (person.department() != null && !departmentIds.containsKey(person.department())) {
                    UUID creator = codeToId.getOrDefault("JC-012", codeToId.get("JC-002"));
                    departmentIds.put(person.department(), upsertDepartment(person.department(), null, creator));
                }
            }
            // two passes so managers resolve regardless of order
            for (int pass = 1; pass <= 2; pass++) {
                for (Person person : PEOPLE.subList(1, PEOPLE.size())) {
                    UUID creator = "fe".equals(person.role()) ? codeToId.get("JC-004")
                            : Set.of("hrm", "fm").contains(person.role()) ? codeToId.get("JC-002") : codeToId.get("JC-012");
                    codeToId.put(person.code(), upsertEmployee(person, roleIds, shiftIds, departmentIds, codeToId, creator != null ? creator : codeToId.get("JC-002")));
                }
                if (pass == 1) {
                    // departments created by Riya once she exists
                    for (String name : List.of("Technology", "Litigation Support", "Contract Management", "Document Review", "Client Services")) {
                        repo.updateDepartmentCreator(name, codeToId.get("JC-012"));
                    }
                }
            }
            String[][] heads = {{"Human Resources", "JC-012"}, {"Finance", "JC-004"}, {"Technology", "JC-035"}, {"Litigation Support", "JC-035"}, {"Client Services", "JC-091"}};
            for (String[] head : heads) repo.updateDepartmentHead(head[0], codeToId.get(head[1]));

            for (Person person : PEOPLE) ensureUser(codeToId.get(person.code()), false);

            // 2026 holidays (prototype calendar), added by the HR Manager
            String[][] holidayRows = {{"2026-01-26", "Republic Day", "IN"}, {"2026-03-04", "Holi", "IN"}, {"2026-08-15", "Independence Day", "IN"}, {"2026-09-07", "Labor Day", "US"},
                    {"2026-09-14", "Ganesh Chaturthi", "IN"}, {"2026-10-02", "Gandhi Jayanti", "IN"}, {"2026-10-20", "Dussehra", "IN"}, {"2026-11-09", "Govardhan Puja", "IN"},
                    {"2026-11-24", "Guru Nanak Jayanti", "IN"}, {"2026-11-26", "Thanksgiving Day", "US"}, {"2026-12-25", "Christmas", "ALL"},
                    {"2026-01-01", "New Year's Day", "US"}, {"2026-05-25", "Memorial Day", "US"}, {"2026-07-03", "Independence Day (observed)", "US"}};
            for (String[] holiday : holidayRows) {
                repo.insertHoliday(UUID.randomUUID(), parseDate(holiday[0]), holiday[1], holiday[2], codeToId.get("JC-012"));
            }

            // Salary structures (monthly, ₹ → paise). Effective-dated; history only grows.
            Object[][] structures = {
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
            for (Object[] structure : structures) {
                repo.insertSalaryStructure(
                        UUID.randomUUID(), codeToId.get((String) structure[0]), parseDate((String) structure[1]), paise((Integer) structure[2]), paise((Integer) structure[3]), paise((Integer) structure[4]),
                        paise((Integer) structure[5]), structure[6], codeToId.get("JC-004"));
            }

            // Tax profiles + old-regime declarations (prototype)
            String financialYear = "2026-27";
            repo.insertTaxProfile(codeToId.get("JC-074"), financialYear, "NEW", paise(750000), paise(38000));
            record DeclarationSeed(String code, Map<String, Object[]> items, String status) {}
            Map<String, Object[]> pendingDeclarationItems = new LinkedHashMap<>();
            pendingDeclarationItems.put("rent", new Object[] {"HRA", 180000});
            pendingDeclarationItems.put("ppf", new Object[] {"80C", 80000});
            pendingDeclarationItems.put("lic", new Object[] {"80C", 25000});
            pendingDeclarationItems.put("medSelf", new Object[] {"80D_SELF", 18000});
            Map<String, Object[]> approvedDeclarationItems = new LinkedHashMap<>();
            approvedDeclarationItems.put("rent", new Object[] {"HRA", 144000});
            approvedDeclarationItems.put("elss", new Object[] {"80C", 60000});
            approvedDeclarationItems.put("medParents", new Object[] {"80D_PARENTS", 30000});
            for (DeclarationSeed declarationSeed : List.of(new DeclarationSeed("JC-058", pendingDeclarationItems, "PENDING"), new DeclarationSeed("JC-049", approvedDeclarationItems, "APPROVED"))) {
                UUID employeeId = codeToId.get(declarationSeed.code());
                repo.insertTaxProfile(employeeId, financialYear, "OLD", 0, 0);
                boolean approved = "APPROVED".equals(declarationSeed.status());
                for (Map.Entry<String, Object[]> entry : declarationSeed.items().entrySet()) {
                    repo.insertDeclarationItem(
                            UUID.randomUUID(), employeeId, financialYear, entry.getValue()[0], entry.getKey(), paise((Integer) entry.getValue()[1]), entry.getKey() + "-proof.pdf", declarationSeed.status(),
                            approved ? codeToId.get("JC-004") : null, approved ? Timestamp.from(Instant.now()) : null);
                }
            }

            seedPunches(codeToId);
            seedRequests(codeToId);
            seedPayroll(codeToId);
            seedProofFiles();
            taskSeed.seed(codeToId); // demo projects and tasks (skipped when already there)
        // leave balances: credit every month so far (idempotent)
        jobs.jobLeaveAccrual(Instant.now());
    }

    /** Deletes stored payslip PDFs, proof files and task attachments (after a database reset they belong to nothing). */
    public void clearStoredFiles() throws IOException {
        for (String subfolder : List.of("payslips", "proofs", "tasks")) {
            Path directory = storageDir.resolve(subfolder);
            if (!Files.exists(directory)) continue;
            try (java.util.stream.Stream<Path> walk = Files.walk(directory)) {
                for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        log.info("Stored payslips, proofs and task attachments cleared");
    }

    long count(String table) {
        return repo.countRows(table);
    }
}
