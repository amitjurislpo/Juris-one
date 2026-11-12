package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.exception.ApiException;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.ShiftRosterRepository;
import com.jurislpo.jurisone.repository.ShiftRosterRepository.AssignmentDetail;
import com.jurislpo.jurisone.repository.ShiftRosterRepository.AssignmentRow;
import com.jurislpo.jurisone.repository.ShiftRosterRepository.ShiftRow;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.service.AttendanceService.AttendanceEmployee;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The shift roster: the business's shifts (the platform's three defaults are read-only) and date-range shift assignments per employee.
 * The shift of an employee on a date is the assignment that covers it, else the employee's own shift (see {@code AttendanceEmployee.shiftOn}).
 * New assignments start today or later, so lateness already computed for past days never changes.
 */
@Service
public class ShiftRosterService {

    static final int MAX_ROSTER_DAYS = 42;
    static final int MAX_EMPLOYEES_PER_REQUEST = 200;
    static final int MAX_ASSIGNMENT_YEARS = 3;
    private static final String TIME_PATTERN = "^([01][0-9]|2[0-3]):[0-5][0-9]$";

    public record ShiftView(String id, String name, String startLocal, String endLocal, int graceMinutes, String region, boolean crossesMidnight, boolean isCustom) {}

    public record ShiftInput(String name, String startLocal, String endLocal, String region) {}

    public record AssignmentInput(List<String> employeeIds, String shiftId, String fromDate, String toDate) {}

    /** One day of one employee: the shift worked ({@code assignmentId} is null when it is the employee's own shift) and whether the policy makes it a weekly off. */
    public record RosterDay(String date, String shiftId, String assignmentId, boolean isWeeklyOff) {}

    public record EmployeeRoster(String id, String code, String name, String designation, List<RosterDay> days) {}

    public record Roster(String from, String to, String scope, boolean canManage, List<ShiftView> shifts, List<EmployeeRoster> employees) {}

    private final ShiftRosterRepository repo;
    private final AttendanceService attendance;
    private final AuditService audit;

    public ShiftRosterService(ShiftRosterRepository repo, AttendanceService attendance, AuditService audit) {
        this.repo = repo;
        this.attendance = attendance;
        this.audit = audit;
    }

    static ShiftView view(ShiftRow row) {
        return new ShiftView(row.id().toString(), row.name(), row.startLocal(), row.endLocal(), row.graceMinutes(), row.region(), row.crossesMidnight(), row.isCustom());
    }

    public List<ShiftView> shifts() {
        return repo.shifts().stream().map(ShiftRosterService::view).toList();
    }

    @Transactional
    public ShiftView createShift(CurrentUser user, ShiftInput input, String ipAddress) {
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input");
        String name = shiftName(input.name());
        String startLocal = time(input.startLocal()), endLocal = time(input.endLocal());
        String region = input.region() == null ? "IN" : Check.oneOf(input.region(), List.of("IN", "US"));
        if (startLocal.equals(endLocal)) throw new UserException("A shift's start and end can't be the same time.");
        assertNameFree(name, null);
        UUID id = UUID.randomUUID();
        String code = "CUSTOM_" + id.toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        repo.insertShift(id, code, name, startLocal, endLocal, region, endLocal.compareTo(startLocal) <= 0);
        audit.log(user.id(), "shift.create", "shift", id.toString(), null, Map.of("name", name, "startLocal", startLocal, "endLocal", endLocal, "region", region), ipAddress);
        return view(repo.findShift(id));
    }

    @Transactional
    public ShiftView updateShift(CurrentUser user, String idText, ShiftInput input, String ipAddress) {
        UUID id = Check.uuid(idText);
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input");
        ShiftRow before = repo.findShift(id);
        if (before == null) throw new UserException(HttpStatus.NOT_FOUND, "Shift not found.");
        if (!before.isCustom()) throw new UserException("The built-in shifts can't be changed. Create your own shift instead.");
        String name = input.name() == null ? before.name() : shiftName(input.name());
        String startLocal = input.startLocal() == null ? before.startLocal() : time(input.startLocal());
        String endLocal = input.endLocal() == null ? before.endLocal() : time(input.endLocal());
        if (startLocal.equals(endLocal)) throw new UserException("A shift's start and end can't be the same time.");
        assertNameFree(name, id);
        if (repo.updateShift(id, name, startLocal, endLocal, endLocal.compareTo(startLocal) <= 0) == 0) throw new UserException(HttpStatus.NOT_FOUND, "Shift not found.");
        audit.log(user.id(), "shift.update", "shift", id.toString(), Map.of("name", before.name(), "startLocal", before.startLocal(), "endLocal", before.endLocal()),
                Map.of("name", name, "startLocal", startLocal, "endLocal", endLocal), ipAddress);
        return view(repo.findShift(id));
    }

    private static String shiftName(String name) {
        return Check.trimmed(name, 1, "Enter a shift name.", 40, "Keep the shift name under 40 characters.");
    }

    private static String time(String value) {
        if (value == null || !value.matches(TIME_PATTERN)) throw new UserException(HttpStatus.BAD_REQUEST, "Enter the time as HH:mm (24 hours).");
        return value;
    }

    private void assertNameFree(String name, UUID exceptId) {
        for (ShiftRow row : repo.shifts()) {
            if (row.name().equalsIgnoreCase(name) && !row.id().equals(exceptId)) throw new ApiException(HttpStatus.CONFLICT, "A shift with that name already exists.");
        }
    }

    /** The roster for [from, to] (at most 42 days). {@code everyone}: all tracked employees (managers), else only the user. */
    public Roster roster(CurrentUser user, boolean everyone, String fromText, String toText) {
        if (!Check.isIsoDate(fromText) || !Check.isIsoDate(toText)) throw new UserException(HttpStatus.BAD_REQUEST, "Enter the period as from=YYYY-MM-DD and to=YYYY-MM-DD.");
        LocalDate from = LocalDate.parse(fromText), to = LocalDate.parse(toText);
        if (to.isBefore(from) || from.plusDays(MAX_ROSTER_DAYS).isBefore(to.plusDays(1))) throw new UserException(HttpStatus.BAD_REQUEST, "Pick a period of at most " + MAX_ROSTER_DAYS + " days.");
        List<AttendanceEmployee> employees = everyone ? attendance.loadAttendanceEmployees("") : attendance.loadAttendanceEmployees("e.id = ?", user.id());
        List<ShiftRow> shiftRows = repo.shifts();
        Map<String, UUID> shiftIdByCode = new HashMap<>();
        for (ShiftRow row : shiftRows) shiftIdByCode.putIfAbsent(row.code(), row.id());
        Map<UUID, List<AssignmentRow>> assignmentsByEmployee = new HashMap<>();
        if (!employees.isEmpty()) {
            UUID[] employeeIds = employees.stream().map(AttendanceEmployee::id).toArray(UUID[]::new);
            for (AssignmentRow row : repo.assignments(employeeIds, from, to)) assignmentsByEmployee.computeIfAbsent(row.employeeId(), k -> new ArrayList<>()).add(row);
        }
        Set<Integer> weeklyOffs = attendance.policy().engine().weeklyOffs();
        List<EmployeeRoster> out = new ArrayList<>();
        for (AttendanceEmployee employee : employees) {
            List<AssignmentRow> assignments = assignmentsByEmployee.getOrDefault(employee.id(), List.of());
            UUID ownShiftId = shiftIdByCode.get(employee.shift().code());
            List<RosterDay> days = new ArrayList<>();
            for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
                LocalDate day = date;
                AssignmentRow assignment = assignments.stream().filter(a -> !day.isBefore(a.fromDate()) && (a.toDate() == null || !day.isAfter(a.toDate()))).findFirst().orElse(null);
                UUID shiftId = assignment != null ? assignment.shiftId() : ownShiftId;
                days.add(new RosterDay(date.toString(), shiftId == null ? null : shiftId.toString(), assignment == null ? null : assignment.id().toString(),
                        weeklyOffs.contains(date.getDayOfWeek().getValue() % 7)));
            }
            out.add(new EmployeeRoster(employee.id().toString(), employee.code(), employee.name(), employee.designation(), days));
        }
        return new Roster(fromText, toText, everyone ? "all" : "mine", user.can("shifts.manage"), shiftRows.stream().map(ShiftRosterService::view).toList(), out);
    }

    /** Gives every listed employee the shift for [fromDate, toDate] (toDate empty = open ended). All or nothing: any overlap with an existing assignment refuses the lot. */
    @Transactional
    public List<String> assign(CurrentUser user, AssignmentInput input, String ipAddress) {
        if (input == null || input.employeeIds() == null || input.employeeIds().isEmpty()) throw new UserException(HttpStatus.BAD_REQUEST, "Pick at least one employee.");
        if (input.employeeIds().size() > MAX_EMPLOYEES_PER_REQUEST) throw new UserException(HttpStatus.BAD_REQUEST, "Pick at most " + MAX_EMPLOYEES_PER_REQUEST + " employees at a time.");
        List<UUID> employeeIds = input.employeeIds().stream().map(Check::uuid).distinct().toList();
        UUID shiftId = Check.uuid(input.shiftId());
        if (!Check.isIsoDate(input.fromDate())) throw new UserException(HttpStatus.BAD_REQUEST, "Enter the start date as YYYY-MM-DD.");
        LocalDate from = LocalDate.parse(input.fromDate());
        LocalDate to = null;
        if (input.toDate() != null && !input.toDate().isBlank()) {
            if (!Check.isIsoDate(input.toDate())) throw new UserException(HttpStatus.BAD_REQUEST, "Enter the end date as YYYY-MM-DD.");
            to = LocalDate.parse(input.toDate());
            if (to.isBefore(from)) throw new UserException(HttpStatus.BAD_REQUEST, "The end date can't be before the start date.");
            if (to.isAfter(from.plusYears(MAX_ASSIGNMENT_YEARS))) throw new UserException(HttpStatus.BAD_REQUEST, "A shift can be assigned for at most " + MAX_ASSIGNMENT_YEARS + " years at a time.");
        }
        if (from.isBefore(today().minusDays(1))) throw new UserException("Shifts can only be assigned from today onwards; attendance already recorded is not changed.");
        if (repo.findShift(shiftId) == null) throw new UserException(HttpStatus.NOT_FOUND, "Shift not found.");
        UUID[] idArray = employeeIds.toArray(UUID[]::new);
        if (repo.lockTrackedEmployees(idArray).size() != employeeIds.size()) throw new UserException(HttpStatus.NOT_FOUND, "Employee not found.");
        List<String> overlapping = repo.employeesWithOverlap(idArray, from, to);
        if (!overlapping.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "Already on a shift in that period: " + String.join(", ", overlapping) + ". Remove that assignment first.");
        List<String> ids = new ArrayList<>();
        for (UUID employeeId : employeeIds) {
            UUID id = UUID.randomUUID();
            repo.insertAssignment(id, employeeId, shiftId, from, to, user.id());
            audit.log(user.id(), "shift_assignment.create", "shift_assignment", id.toString(), null, assignmentValues(employeeId, shiftId, from, to), ipAddress);
            ids.add(id.toString());
        }
        return ids;
    }

    @Transactional
    public void removeAssignment(CurrentUser user, String idText, String ipAddress) {
        UUID id = Check.uuid(idText);
        AssignmentDetail assignment = repo.findAssignment(id);
        if (assignment == null) throw new UserException(HttpStatus.NOT_FOUND, "Shift assignment not found.");
        if (assignment.toDate() != null && assignment.toDate().isBefore(today())) throw new UserException("A finished shift assignment is part of the history and can't be removed.");
        if (repo.deleteAssignment(id) == 0) throw new UserException(HttpStatus.NOT_FOUND, "Shift assignment not found.");
        audit.log(user.id(), "shift_assignment.delete", "shift_assignment", id.toString(),
                assignmentValues(assignment.employeeId(), assignment.shiftId(), assignment.fromDate(), assignment.toDate()), null, ipAddress);
    }

    private static Map<String, Object> assignmentValues(UUID employeeId, UUID shiftId, LocalDate from, LocalDate to) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("employeeId", employeeId.toString());
        values.put("shiftId", shiftId.toString());
        values.put("fromDate", from.toString());
        values.put("toDate", to == null ? null : to.toString());
        return values;
    }

    /** The date in UTC; {@link #assign} allows one day before it, so a date that is still today in any time zone is accepted. */
    LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
