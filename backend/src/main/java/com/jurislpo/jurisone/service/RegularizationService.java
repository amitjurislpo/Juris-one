package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.crypto.FileCrypto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.config.JurisOneProperties;
import com.jurislpo.jurisone.engine.Csv;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.RegularizationRules;
import com.jurislpo.jurisone.entity.DayStatus;
import com.jurislpo.jurisone.exception.ApiException;
import com.jurislpo.jurisone.exception.ForbiddenException;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.RegularizationRepository;
import com.jurislpo.jurisone.repository.RegularizationRepository.Filter;
import com.jurislpo.jurisone.repository.RegularizationRepository.Item;
import com.jurislpo.jurisone.repository.RegularizationRepository.NewRequest;
import com.jurislpo.jurisone.repository.RegularizationRepository.Reason;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.service.AttendanceService.AttendanceEmployee;
import com.jurislpo.jurisone.service.AttendanceService.DayView;
import com.jurislpo.jurisone.service.SettingsService.RegularizationPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attendance regularization requests (REQUIREMENTS §5.4): the employee asks for the real check-in / check-out of a day, with a reason and an
 * optional attachment; Admin and HR (appr.reg) decide. This class makes, lists, shows and withdraws requests and keeps the reason list and
 * the rules; the decision itself is {@link ApprovalsService#decideRegularization}.
 */
@Service
public class RegularizationService {

    public static final int MAX_ATTACHMENT_MB = 5;
    public static final int PAGE_SIZE = 50;
    public static final int MAX_BULK = 50;
    static final String ALREADY_DECIDED = "REGULARIZATION_ALREADY_DECIDED";
    static final DateTimeFormatter DAY_FIRST = DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ROOT);
    static final DateTimeFormatter LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ROOT);
    static final List<String> STATUSES = List.of("PENDING", "APPROVED", "DECLINED", "CANCELLED");

    record FileType(String extension, String mime, byte[] magic) {}

    private static final byte[] OLE_MAGIC = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    private static final byte[] ZIP_MAGIC = {0x50, 0x4b, 0x03, 0x04};
    private static final byte[] JPEG_MAGIC = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
    private static final List<FileType> FILE_TYPES = List.of(
            new FileType("pdf", "application/pdf", new byte[] {0x25, 0x50, 0x44, 0x46}),
            new FileType("png", "image/png", new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}),
            new FileType("jpg", "image/jpeg", JPEG_MAGIC), new FileType("jpeg", "image/jpeg", JPEG_MAGIC),
            new FileType("doc", "application/msword", OLE_MAGIC), new FileType("xls", "application/vnd.ms-excel", OLE_MAGIC),
            new FileType("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ZIP_MAGIC),
            new FileType("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ZIP_MAGIC));

    /** The body of a new request. {@code checkIn}/{@code checkOut} are local to the employee ({@code yyyy-MM-ddTHH:mm}); {@code proposedIn}/{@code proposedOut} ("HH:mm") and {@code reason} are the old body, still accepted. */
    public record CreateInput(String date, String toDate, String employeeId, String kind, String checkIn, String checkOut, String reasonId, String description, String proposedIn, String proposedOut, String reason) {}

    public record Upload(String fileName, byte[] bytes) {}

    public record FileOut(String name, String mime, byte[] bytes) {}

    public record DecisionResult(String id, boolean ok, String message) {}

    private final AttendanceService attendanceService;
    private final RegularizationRepository repo;
    private final SettingsService settings;
    private final AuditService audit;
    private final NotificationService notifications;
    private final ApprovalsService approvals;
    private final ObjectMapper json;
    private final Path storageDir;
    private final FileCrypto fileCrypto;

    public RegularizationService(AttendanceService attendanceService, RegularizationRepository repo, SettingsService settings, AuditService audit, NotificationService notifications,
                                 ApprovalsService approvals, ObjectMapper json, JurisOneProperties properties, FileCrypto fileCrypto) {
        this.attendanceService = attendanceService;
        this.repo = repo;
        this.settings = settings;
        this.audit = audit;
        this.notifications = notifications;
        this.approvals = approvals;
        this.json = json;
        this.storageDir = Path.of(properties.storageDir()).toAbsolutePath().normalize();
        this.fileCrypto = fileCrypto;
    }

    private AttendanceEmployee employeeOf(CurrentUser user) {
        return employeeFor(user.id(), false);
    }

    /** A tracked employee of the signed-in user's business (row-level security hides other businesses); "not found" for anybody else. */
    private AttendanceEmployee employeeFor(UUID employeeId, boolean onBehalf) {
        List<AttendanceEmployee> employees = attendanceService.loadAttendanceEmployees("e.id = ?", employeeId);
        if (employees.isEmpty()) throw onBehalf ? new UserException(HttpStatus.NOT_FOUND, "Employee not found.") : new UserException(MyAttendanceService.NOT_TRACKED);
        return employees.get(0);
    }

    private DayView dayOf(AttendanceEmployee employee, LocalDate date, Instant now) {
        return attendanceService.attendanceFor(List.of(employee), date, date, now).get(employee.id()).get(0);
    }

    /** Why a request of this kind does not fit the day (null = it fits). */
    static String kindProblem(String kind, DayView day) {
        boolean fits = switch (kind) {
            case "ABSENT" -> day.dayResult.lopDays > 0 && day.dayResult.status != DayStatus.LEAVE;
            case "LATE_CHECKIN" -> day.dayResult.late;
            case "MISSED_CHECKOUT" -> day.dayResult.missedCheckout || (!day.punches.isEmpty() && !day.dayResult.live && day.dayResult.lastOut == null);
            default -> day.dayResult.offDayWork;
        };
        if (fits) return null;
        return switch (kind) {
            case "ABSENT" -> "This day isn't marked as loss of pay.";
            case "LATE_CHECKIN" -> "There's no late mark on this day.";
            case "MISSED_CHECKOUT" -> "There's no missing check-out on this day.";
            default -> "You need 4h+ of work on a holiday or weekly off for a comp-off.";
        };
    }

    // ───────────────────────── a day, for the form ─────────────────────────

    public record DayPreview(String date, String status, String label, String checkIn, String checkOut, String checkInLocal, String checkOutLocal, int workedMinutes,
                             List<String> eligibleKinds, String pendingRequestId) {}

    /** What is recorded for one of my days and which kinds of request fit it: prefills the request form. */
    public DayPreview dayPreview(CurrentUser user, String dateText, String employeeIdText) {
        Check.isoDate(dateText, "Invalid date.");
        LocalDate date;
        try {
            date = LocalDate.parse(dateText);
        } catch (java.time.DateTimeException e) {
            throw new UserException(HttpStatus.BAD_REQUEST, "Invalid date.");
        }
        UUID subjectId = user.id();
        if (!isBlank(employeeIdText) && !Check.uuid(employeeIdText).equals(user.id())) {
            if (!user.can("appr.reg")) throw new ForbiddenException();
            subjectId = Check.uuid(employeeIdText);
        }
        AttendanceEmployee employee = employeeFor(subjectId, !subjectId.equals(user.id()));
        Instant now = Instant.now();
        DayView day = dayOf(employee, date, now);
        List<String> eligible = new ArrayList<>();
        if (!date.isAfter(attendanceService.currentWorkDate(employee, now))) {
            for (String kind : RegularizationRules.KINDS) {
                if (kindProblem(kind, day) == null && repo.openStatus(subjectId, date, kind).isEmpty()) eligible.add(kind);
            }
        }
        String zone = employee.timeZone();
        Instant in = day.dayResult.firstIn, out = day.dayResult.lastOut;
        return new DayPreview(dateText, day.dayResult.status.name(), day.dayResult.label, in == null ? null : MyAttendanceService.formatIsoInstant(in), out == null ? null : MyAttendanceService.formatIsoInstant(out),
                in == null ? null : in.atZone(ZoneId.of(zone)).format(LOCAL_DATE_TIME), out == null ? null : out.atZone(ZoneId.of(zone)).format(LOCAL_DATE_TIME),
                day.dayResult.workedMinutes, eligible, null);
    }

    // ───────────────────────── make a request ─────────────────────────

    public static final int MAX_RANGE_DAYS = 31;

    /** One day of a request: whether it was made, and why not. */
    public record DayOutcome(String date, boolean ok, String message) {}

    public record Created(String message, List<DayOutcome> days) {}

    /** The stored attachment of one call (written once, shared by every day of a range). */
    private record StoredFile(String name, String mime, int bytes, String path) {}

    /**
     * Makes a request for the signed-in employee, or (Admin / HR, {@code employeeId}) on behalf of an employee of the same business.
     * With {@code toDate} it is a range: one request per day sharing a batch id, every day following all the rules on its own (a day
     * that does not fit is reported and skipped). The times given are for the first day; later days get the same times of day.
     */
    public Created create(CurrentUser user, CreateInput input, Upload upload, String ipAddress) {
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input");
        Check.isoDate(input.date(), "Invalid string: must match pattern /^\\d{4}-\\d{2}-\\d{2}$/");
        LocalDate first = parseDate(input.date());
        LocalDate last = first;
        if (!isBlank(input.toDate())) {
            Check.isoDate(input.toDate(), "Invalid date.");
            last = parseDate(input.toDate());
            if (last.isBefore(first)) throw new UserException(HttpStatus.BAD_REQUEST, "The last day can't be before the first day.");
            if (first.until(last, java.time.temporal.ChronoUnit.DAYS) + 1 > MAX_RANGE_DAYS) throw new UserException(HttpStatus.BAD_REQUEST, "A range can cover at most " + MAX_RANGE_DAYS + " days.");
        }
        boolean isRange = !last.equals(first);

        UUID subjectId = user.id();
        if (!isBlank(input.employeeId())) {
            UUID requested = Check.uuid(input.employeeId());
            if (!requested.equals(user.id())) {
                if (!user.can("appr.reg")) throw new ForbiddenException();
                subjectId = requested;
            }
        }
        boolean onBehalf = !subjectId.equals(user.id());
        AttendanceEmployee subject = employeeFor(subjectId, onBehalf);

        StoredFile stored = null;
        if (upload != null) {
            String name = safeFileName(upload.fileName());
            FileType type = checkedType(name, upload.bytes());
            stored = new StoredFile(name, type.mime(), upload.bytes().length, storeFile(subject.code(), UUID.randomUUID(), name, upload.bytes()));
        }
        UUID batchId = isRange ? UUID.randomUUID() : null;
        List<DayOutcome> outcomes = new ArrayList<>();
        int created = 0;
        long dayOffset = 0;
        List<LocalDate> waiting = new ArrayList<>(); // days now waiting for a decision (not auto-approved): the approvers get ONE email for them
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1), dayOffset++) {
            try {
                if (createOne(user, subject, onBehalf, day, dayOffset, input, stored, upload != null, batchId, ipAddress)) waiting.add(day);
                outcomes.add(new DayOutcome(day.toString(), true, "Regularization request added successfully"));
                created++;
            } catch (ApiException e) {
                if (!isRange) {
                    removeFile(stored == null ? null : stored.path());
                    throw e;
                }
                outcomes.add(new DayOutcome(day.toString(), false, e.getMessage()));
            }
        }
        if (created == 0) {
            removeFile(stored == null ? null : stored.path());
            throw new UserException(outcomes.get(0).message());
        }
        emailApprovers(user, subject, onBehalf, waiting);
        String message = isRange ? created + " of " + outcomes.size() + " requests added" : "Regularization request added successfully";
        return new Created(message, outcomes);
    }

    private static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text);
        } catch (java.time.DateTimeException e) {
            throw new UserException(HttpStatus.BAD_REQUEST, "Invalid date.");
        }
    }

    /** One email to every Admin / HR approver for the request (or the whole range) just raised; the bell notification is sent per day. */
    private void emailApprovers(CurrentUser actor, AttendanceEmployee employee, boolean onBehalf, List<LocalDate> days) {
        if (days.isEmpty()) return;
        String span = days.size() == 1 ? Format.formatDayMonth(days.get(0)) : Format.formatDayMonth(days.get(0)) + " to " + Format.formatDayMonth(days.get(days.size() - 1)) + " (" + days.size() + " days)";
        String who = onBehalf ? actor.name() + " raised a regularization request for " + employee.name() : employee.name() + " asked to regularize attendance";
        for (UUID approverId : repo.approverIds(employee.id())) {
            if (approverId.equals(actor.id())) continue;
            notifications.emailEmployee(approverId, name -> MailTemplates.regularizationRequested(name, who, span));
        }
    }

    /** Returns true when the new request is waiting for a decision (false when auto-approve decided it at once). */
    private boolean createOne(CurrentUser actor, AttendanceEmployee employee, boolean onBehalf, LocalDate date, long dayOffset, CreateInput input, StoredFile stored, boolean hasUpload,
                           UUID batchId, String ipAddress) {
        String kind = Check.oneOf(input.kind(), RegularizationRules.KINDS);
        boolean legacy = isBlank(input.checkIn()) && isBlank(input.checkOut()) && isBlank(input.reasonId());
        UUID subjectId = employee.id();
        String zone = employee.timeZone();
        Instant now = Instant.now();
        RegularizationPolicy rules = settings.regularization();
        LocalDate today = attendanceService.currentWorkDate(employee, now);
        if (date.isAfter(today)) throw new UserException("You can't regularize a future day.");
        AttendanceService.ResolvedAttendancePolicy policy = attendanceService.policy();
        if (attendanceService.isMonthLocked(date.toString().substring(0, 7), policy, now)) throw new UserException("Attendance for this month is locked. Ask Finance to adjust LOP in payroll.");
        if (date.isBefore(today.minusDays(rules.backDateLimitDays()))) throw new UserException("Requests can be made up to " + rules.backDateLimitDays() + " days back.");

        DayView day = dayOf(employee, date, now);
        String problem = kindProblem(kind, day);
        if (problem != null) throw new UserException(problem);
        if (repo.hasPending(subjectId, date)) throw alreadyPending(date);
        if (!repo.openStatus(subjectId, date, kind).isEmpty()) throw new UserException("This day is already regularized.");
        YearMonth month = YearMonth.from(date);
        int used = repo.countUsed(subjectId, month.atDay(1), month.atEndOfMonth());
        if (used >= rules.monthlyLimit()) throw new UserException((onBehalf ? "They have" : "You have") + " used all " + rules.monthlyLimit() + " regularization requests for " + Format.monthLabel(month.toString()) + ".");

        Instant checkIn, checkOut;
        String reasonText;
        UUID reasonId = null;
        String description = null;
        if (legacy) {
            for (String punchTime : new String[] {input.proposedIn(), input.proposedOut()}) {
                if (punchTime == null || (!punchTime.isEmpty() && !punchTime.matches("^\\d{2}:\\d{2}$"))) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input");
            }
            reasonText = Check.requireString(input.reason()).trim();
            if (reasonText.length() > RegularizationRules.MAX_DESCRIPTION_LENGTH) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the reason under 500 characters.");
            checkIn = input.proposedIn().isEmpty() ? null : MyAttendanceService.toInstant(employee, date, input.proposedIn(), null);
            Instant after = checkIn != null ? checkIn : day.dayResult.firstIn;
            checkOut = input.proposedOut().isEmpty() ? null : MyAttendanceService.toInstant(employee, date, input.proposedOut(), after);
            if ("MISSED_CHECKOUT".equals(kind) && checkOut == null) throw new UserException("Enter the time you actually checked out.");
            if (reasonText.isEmpty()) reasonText = null;
        } else {
            checkIn = shifted(RegularizationRules.parseLocalDateTime(input.checkIn(), zone), input.checkIn(), dayOffset, zone);
            checkOut = shifted(RegularizationRules.parseLocalDateTime(input.checkOut(), zone), input.checkOut(), dayOffset, zone);
            RegularizationRules.requireTimesFor(kind, checkIn, checkOut);
            RegularizationRules.checkTimes(date, checkIn, checkOut, day.dayResult.firstIn, zone, now);
            if (isBlank(input.reasonId())) throw new UserException(HttpStatus.BAD_REQUEST, "Choose a reason.");
            Reason reason = repo.findReason(Check.uuid(input.reasonId(), "Choose a reason."));
            if (reason == null || !reason.active()) throw new UserException(HttpStatus.BAD_REQUEST, "Choose a reason.");
            reasonId = UUID.fromString(reason.id());
            description = input.description() == null ? "" : input.description().trim();
            if (description.length() > RegularizationRules.MAX_DESCRIPTION_LENGTH) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the description under 500 characters.");
            if (reason.requiresDescription() && description.isEmpty()) throw new UserException(HttpStatus.BAD_REQUEST, "Add a description for this reason.");
            if (description.isEmpty()) description = null;
            reasonText = description == null ? reason.label() : reason.label() + " — " + description;
            if (rules.attachmentMandatory() && !hasUpload) throw new UserException(HttpStatus.BAD_REQUEST, "Attach a supporting document.");
        }

        // Old = what is recorded now; New = what the day would be once approved (the server's numbers, never the browser's).
        Instant effectiveIn = checkIn != null ? checkIn : day.dayResult.firstIn, effectiveOut = checkOut != null ? checkOut : day.dayResult.lastOut;
        Integer minutes = RegularizationRules.minutesBetween(effectiveIn, effectiveOut);
        Integer newMinutes = minutes != null ? minutes : day.dayResult.workedMinutes;
        String oldStatus = day.dayResult.status.name();
        String newStatus = RegularizationRules.newStatus(oldStatus, kind, newMinutes, policy.engine());

        UUID id = UUID.randomUUID();
        try {
            repo.insert(new NewRequest(id, subjectId, date, kind, checkIn, checkOut, reasonText, reasonId, description, day.dayResult.firstIn, day.dayResult.lastOut, day.dayResult.workedMinutes,
                    oldStatus, newMinutes, newStatus, actor.id(), stored == null ? null : stored.name(), stored == null ? null : stored.mime(), stored == null ? null : stored.bytes(),
                    stored == null ? null : stored.path(), batchId));
        } catch (DuplicateKeyException e) {
            throw alreadyPending(date); // two requests for the same day at the same moment: the database index decides
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("date", date.toString());
        view.put("kind", kind);
        view.put("checkIn", checkIn == null ? null : MyAttendanceService.formatIsoInstant(checkIn));
        view.put("checkOut", checkOut == null ? null : MyAttendanceService.formatIsoInstant(checkOut));
        view.put("reasonId", reasonId == null ? null : reasonId.toString());
        view.put("hasAttachment", stored != null);
        if (batchId != null) view.put("batchId", batchId.toString());
        if (onBehalf) {
            view.put("onBehalfOf", subjectId.toString()); // the actor is the audit row's actor; the employee it was raised for is named here
            view.put("employeeCode", employee.code());
        }
        audit.log(actor.id(), onBehalf ? "regularization.request_on_behalf" : "regularization.request", "regularization", id.toString(), null, view, ipAddress);
        if (onBehalf) {
            notifications.notify(subjectId, actor.name() + " raised a regularization request for you for " + Format.formatDayMonth(date), "/attendance?tab=regularization");
        }
        if (onBehalf && rules.autoApprove() && actor.can("appr.reg")) {
            // Auto-approve is on and an approver raised this for an employee: approve it now, as the approver. If the approval is refused (for example a
            // rule changed a moment ago) the request simply stays pending for a normal decision.
            try {
                approvals.decideRegularization(actor, new ApprovalsService.Decision(id.toString(), true, "Auto-approved: raised by " + actor.name() + " for the employee"), ipAddress, true);
                return false;
            } catch (ApiException e) {
                // stays pending
            }
        }
        for (UUID approverId : repo.approverIds(subjectId)) {
            if (approverId.equals(actor.id())) continue;
            notifications.notify(approverId, (onBehalf ? actor.name() + " raised a regularization request for " + employee.name() : actor.name() + " asked to regularize attendance")
                    + " for " + Format.formatDayMonth(date), "/approvals?tab=regularizations");
        }
        return true;
    }

    /** Later days of a range get the first day's times of day (the check-out keeps its day offset, so a night shift still ends next morning). */
    private static Instant shifted(Instant instant, String text, long dayOffset, String zone) {
        if (instant == null || dayOffset == 0) return instant;
        return java.time.LocalDateTime.parse(text).plusDays(dayOffset).atZone(ZoneId.of(zone)).toInstant();
    }

    private static ApiException alreadyPending(LocalDate date) {
        return new ApiException(HttpStatus.CONFLICT, "REGULARIZATION_ALREADY_PENDING", "A regularization request for " + DAY_FIRST.format(date) + " is already pending.");
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    // ───────────────────────── withdraw ─────────────────────────

    public String cancel(CurrentUser user, String idRaw, String ipAddress) {
        UUID id = Check.uuid(idRaw);
        List<Map<String, Object>> rows = repo.findOwnerAndAttachment(id);
        if (rows.isEmpty() || !user.id().equals(rows.get(0).get("employee_id"))) throw new UserException(HttpStatus.NOT_FOUND, "Request not found.");
        if (!"PENDING".equals(rows.get(0).get("status"))) throw new UserException("Only pending requests can be withdrawn.");
        if (repo.cancel(id, user.id()) == 0) throw new ApiException(HttpStatus.CONFLICT, ALREADY_DECIDED, "This request was decided a moment ago.");
        audit.log(user.id(), "regularization.cancel", "regularization", id.toString(), Map.of("status", "PENDING"), Map.of("status", "CANCELLED"), ipAddress);
        return "Request withdrawn";
    }

    // ───────────────────────── read ─────────────────────────

    private static Filter filterOf(Map<String, String> queryParams, UUID employeeId) {
        String status = ListParams.pick(queryParams, "status", STATUSES, "");
        LocalDate from = null, to = null;
        String month = ListParams.text(queryParams, "month", "");
        if (month.matches("^\\d{4}-(0[1-9]|1[0-2])$")) {
            from = YearMonth.parse(month).atDay(1);
            to = YearMonth.parse(month).atEndOfMonth();
        }
        UUID departmentId = null;
        String departmentText = ListParams.text(queryParams, "departmentId", "");
        if (!departmentText.isEmpty()) departmentId = Check.uuid(departmentText);
        UUID requestedEmployee = employeeId;
        String employeeText = ListParams.text(queryParams, "employeeId", "");
        if (employeeId == null && !employeeText.isEmpty()) requestedEmployee = Check.uuid(employeeText);
        return new Filter(requestedEmployee, status.isEmpty() ? null : status, from, to, ListParams.text(queryParams, "search", "").trim(), departmentId);
    }

    /** Requests with their Old/New values: {@code scope=mine} (self) or {@code scope=all} (appr.reg; the whole business), filtered by status, month, search, departmentId, employeeId. */
    public Map<String, Object> list(CurrentUser user, boolean everyone, Map<String, String> queryParams) {
        Filter filter = filterOf(queryParams, everyone ? null : user.id());
        ListParams page = ListParams.of(queryParams, List.of("date"), "date", PAGE_SIZE, "desc");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", everyone ? "all" : "mine");
        out.put("items", repo.list(filter, page.skip(), page.take()));
        out.put("total", repo.count(filter));
        out.put("page", page.page());
        out.put("pageSize", PAGE_SIZE);
        return out;
    }

    /** One request: its owner, or an approver; anybody else (another person, another business) gets "not found". */
    public Item details(CurrentUser user, String idRaw) {
        Item item = repo.find(Check.uuid(idRaw));
        if (item == null || (!item.employeeId().equals(user.id().toString()) && !user.can("appr.reg"))) throw new UserException(HttpStatus.NOT_FOUND, "Request not found.");
        return item;
    }

    public record Export(String filename, String body) {}

    /** CSV of the requests the filters match (up to 5000), for approvers. */
    public Export export(CurrentUser user, Map<String, String> queryParams) {
        List<Item> items = repo.list(filterOf(queryParams, null), 0, 5000);
        List<List<?>> rows = new ArrayList<>();
        for (Item item : items) {
            rows.add(List.of(item.employeeCode(), item.employeeName(), item.department() == null ? "" : item.department(), item.date(), item.kind(),
                    time(item.checkIn(), user), time(item.checkOut(), user), hours(item.oldMinutes()), hours(item.newMinutes()), nullToEmpty(item.oldStatus()), nullToEmpty(item.newStatus()),
                    nullToEmpty(item.reason()), nullToEmpty(item.description()), "DECLINED".equals(item.status()) ? "REJECTED" : item.status(), nullToEmpty(item.decidedBy()),
                    time(item.decidedAt(), user), nullToEmpty(item.decisionNote())));
        }
        String body = Csv.toCsv(List.of("Employee code", "Employee", "Department", "Date", "Type", "Check-in", "Check-out", "Old hours", "New hours", "Old status", "New status",
                "Reason", "Description", "Approval status", "Decided by", "Decided at", "Remarks"), rows);
        return new Export("regularizations-" + LocalDate.now(ZoneOffset.UTC) + ".csv", body);
    }

    private static String time(String isoInstant, CurrentUser user) {
        return isoInstant == null ? "" : Format.formatDateTime(Instant.parse(isoInstant), user.timeZone());
    }

    private static String hours(Integer minutes) {
        return minutes == null ? "" : String.format(Locale.ROOT, "%d:%02d", minutes / 60, minutes % 60);
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    // ───────────────────────── decisions in bulk ─────────────────────────

    /** Approves or rejects several requests; each one is decided on its own (one failing does not stop the others). A rejection needs remarks. */
    public List<DecisionResult> decideMany(CurrentUser user, List<String> ids, Boolean approve, String note, String ipAddress) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_BULK) throw new UserException(HttpStatus.BAD_REQUEST, "Choose between 1 and " + MAX_BULK + " requests.");
        if (approve == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected boolean, received undefined");
        List<DecisionResult> results = new ArrayList<>();
        for (String id : ids.stream().distinct().toList()) {
            try {
                results.add(new DecisionResult(id, true, approvals.decideRegularization(user, new ApprovalsService.Decision(id, approve, note), ipAddress)));
            } catch (ApiException e) {
                results.add(new DecisionResult(id, false, e.getMessage()));
            }
        }
        return results;
    }

    // ───────────────────────── rules and reasons ─────────────────────────

    public Map<String, Object> rules(CurrentUser user, String monthText) {
        RegularizationPolicy rules = settings.regularization();
        YearMonth month;
        try {
            month = isBlank(monthText) ? YearMonth.now(ZoneId.of(user.timeZone())) : YearMonth.parse(monthText);
        } catch (java.time.DateTimeException e) {
            throw new UserException(HttpStatus.BAD_REQUEST, "Invalid month.");
        }
        int used = user.can("self") ? repo.countUsed(user.id(), month.atDay(1), month.atEndOfMonth()) : 0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("backDateLimitDays", rules.backDateLimitDays());
        out.put("monthlyLimit", rules.monthlyLimit());
        out.put("attachmentMandatory", rules.attachmentMandatory());
        out.put("autoApprove", rules.autoApprove());
        out.put("maxAttachmentMb", MAX_ATTACHMENT_MB);
        out.put("month", month.toString());
        out.put("usedThisMonth", used);
        out.put("remainingThisMonth", Math.max(0, rules.monthlyLimit() - used));
        out.put("reasons", repo.reasons(!user.can("appr.reg")));
        return out;
    }

    @Transactional
    public String saveRules(CurrentUser user, RegularizationPolicy input, String ipAddress) {
        if (input == null || input.backDateLimitDays() < 0 || input.backDateLimitDays() > 365 || input.monthlyLimit() < 1 || input.monthlyLimit() > 1000) {
            throw new UserException(HttpStatus.BAD_REQUEST, "Back-date limit must be 0–365 days and the monthly limit 1–1000.");
        }
        RegularizationPolicy before = settings.regularization();
        JsonNode value = json.valueToTree(input);
        settings.save("attendance.regularization", value);
        audit.log(user.id(), "regularization.settings", "settings", "attendance.regularization", before, input, ipAddress);
        return "Regularization rules saved";
    }

    public record ReasonInput(String label, Boolean requiresDescription, Boolean active) {}

    public String addReason(CurrentUser user, ReasonInput input, String ipAddress) {
        String label = reasonLabel(input);
        if (repo.labelExists(label, null)) throw new ApiException(HttpStatus.CONFLICT, "That reason already exists.");
        UUID id = UUID.randomUUID();
        repo.insertReason(id, label, Boolean.TRUE.equals(input.requiresDescription()));
        audit.log(user.id(), "regularization.reason_add", "regularization_reason", id.toString(), null, Map.of("label", label), ipAddress);
        return "Reason added";
    }

    public String updateReason(CurrentUser user, String idRaw, ReasonInput input, String ipAddress) {
        UUID id = Check.uuid(idRaw);
        Reason before = repo.findReason(id);
        if (before == null) throw new UserException(HttpStatus.NOT_FOUND, "Reason not found.");
        String label = input.label() == null ? before.label() : reasonLabel(input);
        if (repo.labelExists(label, id)) throw new ApiException(HttpStatus.CONFLICT, "That reason already exists.");
        boolean requiresDescription = input.requiresDescription() == null ? before.requiresDescription() : input.requiresDescription();
        boolean active = input.active() == null ? before.active() : input.active();
        repo.updateReason(id, label, requiresDescription, active);
        audit.log(user.id(), "regularization.reason_update", "regularization_reason", id.toString(), before,
                Map.of("label", label, "requiresDescription", requiresDescription, "active", active), ipAddress);
        return "Reason saved";
    }

    private static String reasonLabel(ReasonInput input) {
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input");
        return Check.trimmed(input.label(), 1, "Enter a reason.", 80, "Keep the reason under 80 characters.");
    }

    // ───────────────────────── attachment ─────────────────────────

    /** The attachment of a request, for its owner or an approver (anybody else gets "not found"). */
    public FileOut attachment(CurrentUser user, String idRaw) {
        List<Map<String, Object>> rows = repo.findOwnerAndAttachment(Check.uuid(idRaw));
        if (rows.isEmpty() || rows.get(0).get("attachment_path") == null || (!user.id().equals(rows.get(0).get("employee_id")) && !user.can("appr.reg"))) {
            throw new UserException(HttpStatus.NOT_FOUND, "File not found.");
        }
        Path absolutePath = storageDir.resolve((String) rows.get(0).get("attachment_path")).normalize();
        if (!absolutePath.startsWith(storageDir) || !Files.isRegularFile(absolutePath)) throw new UserException(HttpStatus.NOT_FOUND, "File not found.");
        try {
            return new FileOut((String) rows.get(0).get("attachment_name"), (String) rows.get(0).get("attachment_mime"), fileCrypto.read(absolutePath));
        } catch (IOException e) {
            throw new UserException(HttpStatus.NOT_FOUND, "File not found.");
        }
    }

    static FileType checkedType(String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new UserException(HttpStatus.BAD_REQUEST, "Choose a file.");
        if (bytes.length > MAX_ATTACHMENT_MB * 1024 * 1024) throw new UserException(HttpStatus.PAYLOAD_TOO_LARGE, "Files up to " + MAX_ATTACHMENT_MB + " MB.");
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        FileType type = FILE_TYPES.stream().filter(candidate -> candidate.extension().equals(extension)).findFirst()
                .orElseThrow(() -> new UserException(HttpStatus.BAD_REQUEST, "Only pdf, xls, xlsx, doc, docx, jpg & png."));
        if (bytes.length < type.magic().length || !Arrays.equals(Arrays.copyOf(bytes, type.magic().length), type.magic())) {
            throw new UserException(HttpStatus.BAD_REQUEST, "That file is not a valid " + extension.toUpperCase(Locale.ROOT) + " file.");
        }
        return type;
    }

    /** Never trust the client's name: letters, digits, dot, dash, underscore, space and brackets; no path parts; at most 120 characters. */
    static String safeFileName(String originalFileName) {
        String name = originalFileName == null ? "" : originalFileName;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1).replaceAll("[^\\w.\\- ()]", "_").replaceAll("\\.{2,}", ".").trim();
        if (name.length() > 120) {
            int dot = name.lastIndexOf('.');
            String extension = dot >= 0 && name.length() - dot <= 6 ? name.substring(dot) : "";
            name = name.substring(0, 120 - extension.length()) + extension;
        }
        return name.isBlank() || name.startsWith(".") ? "document" + name : name;
    }

    /** Stored outside the web root as {@code <storage-dir>/regularizations/<YYYY-MM>/<employeeCode>/<uuid>-<safeName>}; returns the path relative to the storage dir. */
    private String storeFile(String employeeCode, UUID id, String safeName, byte[] bytes) {
        String safeCode = employeeCode == null || employeeCode.isBlank() ? "unknown" : employeeCode.replaceAll("[^A-Za-z0-9_-]", "_");
        Path directory = storageDir.resolve("regularizations").resolve(YearMonth.now(ZoneOffset.UTC).toString()).resolve(safeCode).normalize();
        Path absolutePath = directory.resolve(id + "-" + safeName).normalize();
        if (!absolutePath.startsWith(storageDir)) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid file name.");
        try {
            Files.createDirectories(directory);
            fileCrypto.write(absolutePath, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("Could not store the attachment", e);
        }
        return storageDir.relativize(absolutePath).toString().replace('\\', '/');
    }

    private void removeFile(String storagePath) {
        if (storagePath == null) return;
        Path absolutePath = storageDir.resolve(storagePath).normalize();
        if (!absolutePath.startsWith(storageDir)) return;
        try {
            Files.deleteIfExists(absolutePath);
        } catch (IOException e) {
            // the row was not saved; a file left behind is harmless
        }
    }
}
