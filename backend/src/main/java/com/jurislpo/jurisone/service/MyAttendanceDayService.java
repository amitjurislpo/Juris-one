package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.engine.AttendanceEngine;
import com.jurislpo.jurisone.engine.AttendanceEngine.DayResult;
import com.jurislpo.jurisone.engine.AttendanceEngine.Session;
import com.jurislpo.jurisone.engine.AttendanceEngine.ShiftWindow;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.MyAttendanceRepository;
import com.jurislpo.jurisone.repository.MyAttendanceRepository.SwipeRow;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.service.AttendanceService.AttendanceEmployee;
import com.jurislpo.jurisone.service.AttendanceService.DayView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** The detail of one of my attendance days: shift, times, sessions, breaks, shortfall / excess and every swipe (REQUIREMENTS §5.4). Own data only. */
@Service
public class MyAttendanceDayService {

    /** Days outside this range are refused (400) before anything is loaded. */
    static final LocalDate EARLIEST_DATE = LocalDate.of(2000, 1, 1);
    static final LocalDate LATEST_DATE = LocalDate.of(2100, 12, 31);

    private static final DateTimeFormatter SWIPE_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    public record SessionView(int number, String startAt, String endAt, int minutes) {}

    public record SwipeView(String id, String at, String time, String date, String kind, String source, String location) {}

    public record EmployeeView(String name, String code, String accessCardNumber) {}

    public record DayDetail(String date, String shiftName, String shiftStart, String shiftEnd, String processedAt, String status, String label, String code,
                            String firstIn, String lastOut, int lateMinutes, int earlyMinutes, int totalMinutes, int breakMinutes, int actualMinutes,
                            int shortfallMinutes, int excessMinutes, String remarks, List<SessionView> sessions, List<SwipeView> swipes, EmployeeView employee, String punchMode) {}

    private final MyAttendanceService mine;
    private final AttendanceService attendanceService;
    private final MyAttendanceRepository repo;

    public MyAttendanceDayService(MyAttendanceService mine, AttendanceService attendanceService, MyAttendanceRepository repo) {
        this.mine = mine;
        this.attendanceService = attendanceService;
        this.repo = repo;
    }

    /** The signed-in employee's own day: 400 for a date that is not a real day (or is out of range), 404 when attendance is not tracked for the user. */
    public DayDetail day(CurrentUser user, String dateRaw) {
        LocalDate date = LocalDate.parse(Check.isoDate(dateRaw, "The date must be a day written YYYY-MM-DD."));
        if (date.isBefore(EARLIEST_DATE) || date.isAfter(LATEST_DATE)) throw new UserException(HttpStatus.BAD_REQUEST, "The date must be between 2000 and 2100.");
        AttendanceEmployee employee = mine.me(user);
        if (employee == null) throw new UserException(HttpStatus.NOT_FOUND, MyAttendanceService.NOT_TRACKED);

        Instant now = Instant.now();
        AttendanceService.ResolvedAttendancePolicy policy = attendanceService.policy();
        DayView dayView = attendanceService.attendanceFor(List.of(employee), date, date, now).get(employee.id()).get(0);
        DayResult dayResult = dayView.dayResult;
        AttendanceService.Shift shift = employee.shiftOn(date);
        ShiftWindow window = AttendanceEngine.shiftWindow(date, shift.shiftDefinition(), employee.timeZone());

        List<Session> sessions = AttendanceEngine.sessions(dayView.punches);
        Instant liveUntil = dayResult.live ? now : null; // an open check-in counts until now only while the day is still running
        List<SessionView> sessionViews = new ArrayList<>();
        for (int i = 0; i < sessions.size(); i++) {
            Session session = sessions.get(i);
            sessionViews.add(new SessionView(i + 1, MyAttendanceService.formatIsoInstant(session.start()), session.end() == null ? null : MyAttendanceService.formatIsoInstant(session.end()),
                    AttendanceEngine.sessionMinutes(session, liveUntil)));
        }
        boolean isOpen = !sessions.isEmpty() && sessions.get(sessions.size() - 1).end() == null;
        Instant totalEnd = isOpen && liveUntil != null ? liveUntil : dayResult.lastOut;

        // the swipes of this work date (same attribution as the engine), with their ids
        Instant from = date.atStartOfDay(ZoneId.of("UTC")).toInstant().minusSeconds(36 * 3600), to = date.atStartOfDay(ZoneId.of("UTC")).toInstant().plusSeconds(60 * 3600);
        ZoneId zone = ZoneId.of(employee.timeZone());
        List<SwipeView> swipes = new ArrayList<>();
        for (SwipeRow swipe : repo.swipes(employee.id(), from, to)) {
            if (!employee.workDateAt(swipe.punchedAt()).equals(date)) continue;
            swipes.add(new SwipeView(swipe.id().toString(), MyAttendanceService.formatIsoInstant(swipe.punchedAt()), SWIPE_TIME.format(swipe.punchedAt().atZone(zone)),
                    swipe.punchedAt().atZone(zone).toLocalDate().toString(), swipe.kind().name(), swipe.source(), null));
        }

        Map<String, Object> identity = repo.attendanceIdentity(employee.id());
        String accessCardNumber = identity == null ? null : (String) identity.get("access_card_number");
        Instant processedAt = repo.processedAt(employee.id(), date);
        String remarks = dayView.checkOutNote() != null ? dayView.checkOutNote() : repo.approvedRegularizationReason(employee.id(), date);

        return new DayDetail(date.toString(), shift.name(), shift.startLocal(), shift.endLocal(), processedAt == null ? null : MyAttendanceService.formatIsoInstant(processedAt),
                dayResult.status.name(), dayResult.label, AttendanceEngine.dayCode(dayResult, window),
                dayResult.firstIn == null ? null : MyAttendanceService.formatIsoInstant(dayResult.firstIn), dayResult.lastOut == null ? null : MyAttendanceService.formatIsoInstant(dayResult.lastOut),
                AttendanceEngine.lateMinutes(dayResult, window), AttendanceEngine.earlyMinutes(dayResult, window), AttendanceEngine.totalMinutes(dayResult.firstIn, totalEnd),
                AttendanceEngine.breakMinutes(sessions), dayResult.workedMinutes, AttendanceEngine.shortfallMinutes(dayResult, policy.engine()),
                AttendanceEngine.excessMinutes(dayResult, policy.engine()), remarks, sessionViews, swipes,
                new EmployeeView(employee.name(), employee.code(), accessCardNumber), policy.raw().effectivePunchMode(identity == null ? null : (String) identity.get("punch_mode")));
    }
}
