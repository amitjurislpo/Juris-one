package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.engine.AttendanceLock;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.repository.HolidaysRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Holiday calendar (REQUIREMENTS §5.5). holidays.manage. */
@Service
public class HolidaysService {

    static final List<String> SORTS = List.of("date", "name", "region", "addedBy");

    public record Item(String id, String date, String name, String region, String addedBy) {}

    public record Page(List<Item> items, List<Item> rows, String today) {}

    private final HolidaysRepository repo;
    private final AuditService audit;
    private final SettingsService settings;

    public HolidaysService(HolidaysRepository repo, AuditService audit, SettingsService settings) {
        this.repo = repo;
        this.audit = audit;
        this.settings = settings;
    }

    public Page page(CurrentUser user, Map<String, String> queryParams) {
        ListParams listParams = ListParams.of(queryParams, SORTS, "date");
        String region = ListParams.pick(queryParams, "region", List.of("All", "IN", "US"), "All");
        List<Item> items = repo.findAll();
        String search = listParams.search().toLowerCase();
        List<Item> rows = new ArrayList<>(items.stream()
                .filter(h -> ("All".equals(region) || h.region().equals(region) || "ALL".equals(h.region())) && (search.isEmpty() || h.name().toLowerCase().contains(search)))
                .toList());
        Map<String, Function<Item, ? extends Comparable>> keys = Map.of("date", Item::date, "name", Item::name, "region", Item::region, "addedBy", Item::addedBy);
        rows.sort(listParams.comparator(keys));
        return new Page(items, rows, Format.todayIn(user.timeZone()).toString());
    }

    static String label(String region) {
        return "ALL".equals(region) ? "everyone" : "IN".equals(region) ? "India team" : "US team";
    }

    @Transactional
    public String add(CurrentUser user, String dateRaw, String nameRaw, String regionRaw, String ipAddress) {
        String date = Check.isoDate(dateRaw, "Pick a date");
        String name = Check.trimmed(nameRaw, 2, "Enter the holiday name", 80, "Too big: expected string to have <=80 characters");
        String region = Check.oneOf(regionRaw, List.of("IN", "US", "ALL"));
        SettingsService.AttendancePolicy attendancePolicy = settings.attendance();
        if (AttendanceLock.isMonthLocked(date.substring(0, 7), attendancePolicy.lockDayOfNextMonth(), attendancePolicy.lockTime(), Instant.now())) {
            throw new UserException("That month's attendance is already locked.");
        }
        List<String> regions = "ALL".equals(region) ? List.of("IN", "US", "ALL") : List.of(region, "ALL");
        List<String> clash = repo.findClashingName(Date.valueOf(date), regions.get(0), regions.get(1), regions.size() > 2 ? regions.get(2) : regions.get(1));
        if (!clash.isEmpty()) throw new UserException("There's already a holiday on this date for this team (" + clash.get(0) + ").");
        UUID id = UUID.randomUUID();
        repo.insert(id, Date.valueOf(date), name, region, user.id());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("date", date);
        after.put("name", name);
        after.put("region", region);
        audit.log(user.id(), "holiday.create", "holiday", id.toString(), null, after, ipAddress);
        return name + " added for " + label(region) + " · " + Format.formatWeekdayDate(LocalDate.parse(date));
    }

    @Transactional
    public String delete(CurrentUser user, String idRaw, String ipAddress) {
        UUID id = Check.uuid(idRaw);
        List<Map<String, Object>> rows = repo.findById(id);
        if (rows.isEmpty()) throw new UserException("That holiday no longer exists.");
        Map<String, Object> row = rows.get(0);
        LocalDate date = ((Date) row.get("date")).toLocalDate();
        if (date.isBefore(Format.todayIn("Asia/Kolkata"))) throw new UserException("Past holidays can't be deleted.");
        repo.delete(id);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("date", date.toString());
        before.put("name", row.get("name"));
        before.put("region", row.get("region"));
        audit.log(user.id(), "holiday.delete", "holiday", id.toString(), before, null, ipAddress);
        return row.get("name") + " removed from the calendar";
    }
}
