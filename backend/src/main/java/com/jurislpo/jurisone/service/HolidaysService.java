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

/** Holiday calendar (REQUIREMENTS §5.5; src/app/(app)/holidays). holidays.manage. */
@Service
public class HolidaysService {

    static final List<String> SORTS = List.of("k", "n", "region", "by");

    public record Item(String id, String k, String n, String region, String by) {}

    public record Page(List<Item> items, List<Item> rows, String today) {}

    private final HolidaysRepository repo;
    private final AuditService audit;
    private final SettingsService settings;

    public HolidaysService(HolidaysRepository repo, AuditService audit, SettingsService settings) {
        this.repo = repo;
        this.audit = audit;
        this.settings = settings;
    }

    public Page page(CurrentUser u, Map<String, String> sp) {
        ListParams ls = ListParams.of(sp, SORTS, "k");
        String region = ListParams.pick(sp, "region", List.of("All", "IN", "US"), "All");
        List<Item> items = repo.findAll();
        String q = ls.q().toLowerCase();
        List<Item> rows = new ArrayList<>(items.stream()
                .filter(h -> ("All".equals(region) || h.region().equals(region) || "ALL".equals(h.region())) && (q.isEmpty() || h.n().toLowerCase().contains(q)))
                .toList());
        Map<String, Function<Item, ? extends Comparable>> keys = Map.of("k", Item::k, "n", Item::n, "region", Item::region, "by", Item::by);
        rows.sort(ls.comparator(keys));
        return new Page(items, rows, Format.todayIn(u.timeZone()).toString());
    }

    static String label(String r) {
        return "ALL".equals(r) ? "everyone" : "IN".equals(r) ? "India team" : "US team";
    }

    @Transactional
    public String add(CurrentUser u, String dateRaw, String nameRaw, String regionRaw, String ip) {
        String date = Check.isoDate(dateRaw, "Pick a date");
        String name = Check.trimmed(nameRaw, 2, "Enter the holiday name", 80, "Too big: expected string to have <=80 characters");
        String region = Check.oneOf(regionRaw, List.of("IN", "US", "ALL"));
        SettingsService.AttendancePolicy p = settings.attendance();
        if (AttendanceLock.isMonthLocked(date.substring(0, 7), p.lockDayOfNextMonth(), p.lockTime(), Instant.now())) {
            throw new UserException("That month's attendance is already locked.");
        }
        List<String> regions = "ALL".equals(region) ? List.of("IN", "US", "ALL") : List.of(region, "ALL");
        List<String> clash = repo.findClashingName(Date.valueOf(date), regions.get(0), regions.get(1), regions.size() > 2 ? regions.get(2) : regions.get(1));
        if (!clash.isEmpty()) throw new UserException("There's already a holiday on this date for this team (" + clash.get(0) + ").");
        UUID id = UUID.randomUUID();
        repo.insert(id, Date.valueOf(date), name, region, u.id());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("date", date);
        after.put("name", name);
        after.put("region", region);
        audit.log(u.id(), "holiday.create", "holiday", id.toString(), null, after, ip);
        return name + " added for " + label(region) + " · " + Format.fmtDL(LocalDate.parse(date));
    }

    @Transactional
    public String delete(CurrentUser u, String idRaw, String ip) {
        UUID id = Check.uuid(idRaw);
        List<Map<String, Object>> hs = repo.findById(id);
        if (hs.isEmpty()) throw new UserException("That holiday no longer exists.");
        Map<String, Object> h = hs.get(0);
        LocalDate date = ((Date) h.get("date")).toLocalDate();
        if (date.isBefore(Format.todayIn("Asia/Kolkata"))) throw new UserException("Past holidays can't be deleted.");
        repo.delete(id);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("date", date.toString());
        before.put("name", h.get("name"));
        before.put("region", h.get("region"));
        audit.log(u.id(), "holiday.delete", "holiday", id.toString(), before, null, ip);
        return h.get("name") + " removed from the calendar";
    }
}
