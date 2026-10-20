package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.DepartmentsRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Departments (REQUIREMENTS §5.1). dept.manage. */
@Service
public class DepartmentsService {

    static final List<String> SORTS = List.of("name", "head", "n", "by", "on");

    public record Row(String id, String name, String note, String head, int n, String by, String on) {}

    public record Person(String id, String name, String title) {}

    public record Page(List<Row> rows, List<Person> heads) {}

    private final DepartmentsRepository repo;
    private final AuditService audit;

    public DepartmentsService(DepartmentsRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    public Page page(CurrentUser u, Map<String, String> sp) {
        ListParams ls = ListParams.of(sp, SORTS, "name");
        List<Row> all = repo.findActive(u.timeZone());
        String q = ls.q().toLowerCase();
        List<Row> rows = new ArrayList<>(all.stream()
                .filter(r -> q.isEmpty() || (r.name() + " " + r.head() + " " + r.by() + " " + r.note()).toLowerCase().contains(q)).toList());
        Map<String, Function<Row, ? extends Comparable>> keys = Map.of("name", Row::name, "head", Row::head, "n", Row::n, "by", Row::by, "on", Row::on);
        rows.sort(ls.comparator(keys));
        List<Person> heads = repo.findHeadCandidates();
        return new Page(rows, heads);
    }

    @Transactional
    public String create(CurrentUser u, String nameRaw, String headIdRaw, String descRaw, String ip) {
        String name = Check.trimmed(nameRaw, 2, "Department name is required.", 80, "Keep the name under 80 characters.");
        UUID headId = headIdRaw == null || headIdRaw.isEmpty() ? null : Check.uuid(headIdRaw);
        String description = descRaw == null ? null : descRaw.trim();
        if (description != null && description.length() > 300) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the description under 300 characters.");

        List<Map<String, Object>> existing = repo.findByNameIgnoreCase(name);
        if (!existing.isEmpty() && existing.get(0).get("archived_at") == null) throw new UserException("This department already exists.");
        if (headId != null) {
            List<String> st = repo.findEmployeeStatus(headId);
            if (st.isEmpty() || "EXITED".equals(st.get(0))) throw new UserException("Pick a current employee as head.");
        }
        String desc = description == null || description.isEmpty() ? null : description;
        UUID id;
        boolean restore = !existing.isEmpty();
        if (restore) {
            // A previously archived department with the same name is brought back rather than duplicated.
            id = (UUID) existing.get(0).get("id");
            repo.restore(id, name, headId, desc, u.id(), new Timestamp(System.currentTimeMillis()));
        } else {
            id = UUID.randomUUID();
            repo.insert(id, name, headId, desc, u.id());
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", name);
        after.put("headEmployeeId", headId == null ? null : headId.toString());
        if (description != null) after.put("description", description);
        audit.log(u.id(), restore ? "department.restore" : "department.create", "department", id.toString(), null, after, ip);
        return "Department “" + name + "” created";
    }

    @Transactional
    public String delete(CurrentUser u, String idRaw, String ip) {
        UUID id = Check.uuid(idRaw);
        List<Map<String, Object>> ds = repo.findForDelete(id);
        if (ds.isEmpty() || ds.get(0).get("archived_at") != null) throw new UserException("That department no longer exists.");
        Map<String, Object> d = ds.get(0);
        Integer total = repo.countEmployees(id);
        Integer current = repo.countCurrentEmployees(id);
        if (current != null && current > 0) {
            throw new UserException(d.get("name") + " still has " + current + " current employee" + (current == 1 ? "" : "s") + ". Move them first.");
        }
        // Past employees still point at it, so it is archived to keep their records intact.
        boolean archive = total != null && total > 0;
        if (archive) repo.archive(id);
        else repo.delete(id);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("name", d.get("name"));
        before.put("description", d.get("description"));
        audit.log(u.id(), "department.delete", "department", id.toString(), before, archive ? Map.of("archived", true) : null, ip);
        return "Department “" + d.get("name") + "” removed";
    }
}
