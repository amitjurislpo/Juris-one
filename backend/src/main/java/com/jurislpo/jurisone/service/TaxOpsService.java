package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.exception.ForbiddenException;
import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.config.JurisOneProperties;
import com.jurislpo.jurisone.engine.Csv;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.PeopleRules;
import com.jurislpo.jurisone.service.PayrollService.PayRow;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.service.TaxService.DeclMode;
import com.jurislpo.jurisone.service.TaxService.Projection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Tax declarations (self), investment proofs (proofs.verify) and Tax & TDS (tax.view) — src/app/(app)/declare|proofs|tax + api routes. */
@Service
public class TaxOpsService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TaxPositions positions;
    private final SettingsService settings;
    private final AuditService audit;
    private final NotificationService notify;
    private final Path projectDir;
    private final String uploadDir;

    public TaxOpsService(JdbcTemplate jdbc, TransactionTemplate tx, TaxPositions positions, SettingsService settings, AuditService audit, NotificationService notify,
                         JurisOneProperties props, @Value("${UPLOAD_DIR:./storage}") String uploadDir) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.positions = positions;
        this.settings = settings;
        this.audit = audit;
        this.notify = notify;
        this.projectDir = Path.of(props.projectDir() == null ? "." : props.projectDir()).toAbsolutePath().normalize();
        this.uploadDir = uploadDir;
    }

    static LocalDate today() {
        return Format.todayIn("Asia/Kolkata");
    }

    // ───────────────────────── declarations (employee) ─────────────────────────

    public record ItemView(String id, String key, double amount, String status, String proofName, String reason) {}

    public Map<String, Object> declarePage(CurrentUser u) {
        SettingsService.TaxPolicy policy = settings.tax();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fy", policy.fy());
        if (!"IN".equals(u.region())) {
            out.put("india", false);
            return out;
        }
        out.put("india", true);
        String today = today().toString();
        List<Map<String, Object>> prof = jdbc.queryForList("SELECT regime::text AS regime, regime_overridden, metro, parents_senior, landlord_pan FROM tax_profiles WHERE employee_id = ? AND fy = ?",
                u.id(), policy.fy());
        Map<String, Object> profile = prof.isEmpty() ? null : prof.get(0);
        List<ItemView> items = jdbc.query("SELECT id::text, item_key, amount, status::text, proof_name, reject_reason FROM declaration_items WHERE employee_id = ? AND fy = ?",
                (rs, i) -> new ItemView(rs.getString(1), rs.getString(2), rs.getLong(3) / 100.0, rs.getString(4), rs.getString(5), rs.getString(6)), u.id(), policy.fy());
        TaxPositions.Positions pos = positions.taxPositions(List.of(u.id()));
        if (pos.rows().isEmpty()) {
            out.put("hasStructure", false);
            return out;
        }
        PayRow row = pos.rows().get(0);
        // live comparison uses everything you've entered (drafts too); TDS itself uses approved proofs only
        Projection what = positions.reproject(row, pos.month(), DeclMode.all, null);
        out.put("hasStructure", true);
        out.put("regime", profile == null ? "NEW" : profile.get("regime"));
        out.put("overridden", profile != null && Boolean.TRUE.equals(profile.get("regime_overridden")));
        out.put("canChangeRegime", today.compareTo(policy.regimeChangeUntil()) <= 0);
        out.put("windowOpen", today.compareTo(policy.proofWindowFrom()) >= 0 && today.compareTo(policy.proofWindowUntil()) <= 0);
        out.put("windowText", "Declarations and proofs are open until " + Format.fmtDY(LocalDate.parse(policy.proofWindowUntil())) + "; the regime can be changed until "
                + Format.fmtDY(LocalDate.parse(policy.regimeChangeUntil())) + ".");
        out.put("newTax", what.newRegime().total());
        out.put("oldTax", what.oldRegime().total());
        out.put("monthlyTds", row.tax().monthly());
        out.put("epf", what.pfAnnual());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("metro", profile != null && Boolean.TRUE.equals(profile.get("metro")));
        p.put("parentsSenior", profile != null && Boolean.TRUE.equals(profile.get("parents_senior")));
        p.put("landlordPan", profile == null || profile.get("landlord_pan") == null ? "" : profile.get("landlord_pan"));
        out.put("profile", p);
        out.put("items", items);
        return out;
    }

    record Me(CurrentUser u, SettingsService.TaxPolicy policy, String today, Map<String, Object> profile) {}

    private Me me(CurrentUser u) {
        if (!"IN".equals(u.region())) throw new UserException("Tax declarations are for the India payroll.");
        SettingsService.TaxPolicy policy = settings.tax();
        jdbc.update("INSERT INTO tax_profiles (employee_id, fy, regime, regime_overridden, metro, parents_senior, prev_employer_income, prev_employer_tds, updated_at) "
                + "VALUES (?, ?, 'NEW', false, false, false, 0, 0, now()) ON CONFLICT (employee_id, fy) DO NOTHING", u.id(), policy.fy());
        Map<String, Object> profile = jdbc.queryForMap("SELECT regime::text AS regime, regime_overridden, landlord_pan FROM tax_profiles WHERE employee_id = ? AND fy = ?", u.id(), policy.fy());
        return new Me(u, policy, today().toString(), profile);
    }

    private static void windowOpen(SettingsService.TaxPolicy p, String today) {
        if (today.compareTo(p.proofWindowFrom()) < 0) throw new UserException("Declarations open on " + Format.fmtDY(LocalDate.parse(p.proofWindowFrom())) + ".");
        if (today.compareTo(p.proofWindowUntil()) > 0) {
            throw new UserException("Declarations closed on " + Format.fmtDY(LocalDate.parse(p.proofWindowUntil())) + ". Ask Finance for changes.");
        }
    }

    public String setRegime(CurrentUser user, String regimeRaw, String ip) {
        Me m = me(user);
        String regime = Check.oneOf(regimeRaw, List.of("NEW", "OLD"));
        if (Boolean.TRUE.equals(m.profile().get("regime_overridden"))) throw new UserException("Finance has set your regime. Contact Finance to change it.");
        if (m.today().compareTo(m.policy().regimeChangeUntil()) > 0) {
            throw new UserException("The regime could be changed until " + Format.fmtDY(LocalDate.parse(m.policy().regimeChangeUntil())) + ".");
        }
        if (regime.equals(m.profile().get("regime"))) return null;
        jdbc.update("UPDATE tax_profiles SET regime = ?::\"TaxRegime\", updated_at = now() WHERE employee_id = ? AND fy = ?", regime, user.id(), m.policy().fy());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("regime", regime);
        after.put("by", "employee");
        audit.log(user.id(), "tax.regime_change", "tax_profile", user.id() + ":" + m.policy().fy(), Map.of("regime", m.profile().get("regime")), after, ip);
        return "You're on the " + ("NEW".equals(regime) ? "new" : "old") + " regime — TDS updates from the next payroll";
    }

    public record ProfileInput(Boolean metro, Boolean parentsSenior, String landlordPan) {}

    public void saveProfile(CurrentUser user, ProfileInput f) {
        Me m = me(user);
        windowOpen(m.policy(), m.today());
        if (f.metro() == null || f.parentsSenior() == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected boolean, received undefined");
        String pan = Check.str(f.landlordPan()).trim().toUpperCase();
        if (pan.length() > 10) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected string to have <=10 characters");
        if (!pan.isEmpty() && !PeopleRules.PAN_RE.matcher(pan).matches()) throw new UserException("Landlord PAN looks like ABCDE1234F.");
        jdbc.update("UPDATE tax_profiles SET metro = ?, parents_senior = ?, landlord_pan = ?, updated_at = now() WHERE employee_id = ? AND fy = ?",
                f.metro(), f.parentsSenior(), pan.isEmpty() ? null : pan, user.id(), m.policy().fy());
    }

    public record ItemInput(String key, Double amount) {}

    /** Saves a declared amount (₹). Changing an approved or rejected amount sends it back to Draft. */
    public String saveItem(CurrentUser user, ItemInput f) {
        Me m = me(user);
        windowOpen(m.policy(), m.today());
        if (f.key() == null || !TaxCatalogue.declarable(f.key())) throw new UserException(HttpStatus.BAD_REQUEST, "Unknown item");
        Double a = f.amount();
        if (a == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected number, received undefined");
        if (a != Math.rint(a)) throw new UserException(HttpStatus.BAD_REQUEST, "Whole rupees only");
        if (a < 0) throw new UserException(HttpStatus.BAD_REQUEST, "Too small: expected number to be >=0");
        if (a > 100_000_000) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected number to be <=100000000");
        long amount = a.longValue() * 100;
        List<Map<String, Object>> cur = jdbc.queryForList("SELECT id, amount, status::text AS status FROM declaration_items WHERE employee_id = ? AND fy = ? AND item_key = ?",
                user.id(), m.policy().fy(), f.key());
        Map<String, Object> c = cur.isEmpty() ? null : cur.get(0);
        if (c != null && ((Number) c.get("amount")).longValue() == amount) return null;
        if (c != null && "PENDING".equals(c.get("status"))) throw new UserException("This item is waiting for Finance — you can change it after they decide.");
        jdbc.update("""
            INSERT INTO declaration_items (id, employee_id, fy, section, item_key, amount, status, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', now())
            ON CONFLICT (employee_id, fy, item_key) DO UPDATE SET amount = EXCLUDED.amount, status = 'DRAFT', decided_by = NULL, decided_at = NULL,
              reject_reason = NULL, updated_at = now()""", UUID.randomUUID(), user.id(), m.policy().fy(), TaxCatalogue.ITEM_SECTION.get(f.key()), f.key(), amount);
        if (c != null && "APPROVED".equals(c.get("status"))) {
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("amount", ((Number) c.get("amount")).longValue());
            before.put("status", "APPROVED");
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("amount", amount);
            after.put("status", "DRAFT");
            audit.log(user.id(), "declaration.reopened", "declaration_item", c.get("id").toString(), before, after, null);
            return TaxCatalogue.ITEM_LABEL.get(f.key()) + " changed — back to draft until you resubmit";
        }
        return null;
    }

    /** Sends every draft with an amount for verification; each needs a proof. */
    public String submitForVerification(CurrentUser user, String ip) {
        Me m = me(user);
        windowOpen(m.policy(), m.today());
        if (!"OLD".equals(m.profile().get("regime"))) throw new UserException("Declarations only matter on the old regime.");
        List<Map<String, Object>> drafts = jdbc.queryForList("SELECT id, item_key, amount, proof_path FROM declaration_items WHERE employee_id = ? AND fy = ? AND status = 'DRAFT' AND amount > 0",
                user.id(), m.policy().fy());
        if (drafts.isEmpty()) throw new UserException("Nothing new to submit — enter an amount and attach its proof first.");
        List<String> noProof = drafts.stream().filter(d -> d.get("proof_path") == null).map(d -> TaxCatalogue.ITEM_LABEL.get((String) d.get("item_key"))).toList();
        if (!noProof.isEmpty()) throw new UserException("Attach a proof for: " + String.join(", ", noProof) + ".");
        boolean bigRent = drafts.stream().anyMatch(d -> "rent".equals(d.get("item_key")) && ((Number) d.get("amount")).longValue() > 100_000_00L);
        if (bigRent && m.profile().get("landlord_pan") == null) throw new UserException("Rent is above ₹1,00,000 a year — add your landlord's PAN.");
        UUID[] ids = drafts.stream().map(d -> (UUID) d.get("id")).toArray(UUID[]::new);
        jdbc.update("UPDATE declaration_items SET status = 'PENDING', updated_at = now() WHERE id = ANY (?)", (Object) ids);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> d : drafts) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("key", d.get("item_key"));
            x.put("amount", ((Number) d.get("amount")).longValue());
            items.add(x);
        }
        audit.log(user.id(), "declaration.submit", "declaration_item", user.id() + ":" + m.policy().fy(), null, Map.of("items", items), ip);
        return drafts.size() + " item" + (drafts.size() == 1 ? "" : "s") + " sent to Finance for verification";
    }

    // ───────────────────────── proof files ─────────────────────────

    record Kind(String ext, byte[] magic) {}

    static final List<Kind> KINDS = List.of(new Kind("pdf", new byte[] {0x25, 0x50, 0x44, 0x46}), new Kind("png", new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47}),
            new Kind("jpg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}));

    /** Upload a proof for one declaration item. Owner only, within the window. Errors → 400 {error}. */
    public String uploadProof(CurrentUser u, String itemKey, String fileName, byte[] bytes) {
        SettingsService.TaxPolicy policy = settings.tax();
        String today = today().toString();
        if (today.compareTo(policy.proofWindowFrom()) < 0 || today.compareTo(policy.proofWindowUntil()) > 0) throw new UserException(HttpStatus.BAD_REQUEST, "The proof window is closed.");
        if (itemKey == null || !TaxCatalogue.declarable(itemKey)) throw new UserException(HttpStatus.BAD_REQUEST, "Unknown item.");
        if (bytes == null || bytes.length == 0) throw new UserException(HttpStatus.BAD_REQUEST, "Choose a file.");
        if (bytes.length > policy.maxProofMb() * 1024 * 1024) throw new UserException(HttpStatus.BAD_REQUEST, "Files up to " + LeaveNum.num(policy.maxProofMb()) + " MB.");
        Kind kind = KINDS.stream().filter(k -> startsWith(bytes, k.magic())).findFirst().orElse(null); // trust content, not the extension
        if (kind == null) throw new UserException(HttpStatus.BAD_REQUEST, "Upload a PDF, JPG or PNG.");
        List<Map<String, Object>> cur = jdbc.queryForList("SELECT status::text AS status FROM declaration_items WHERE employee_id = ? AND fy = ? AND item_key = ?", u.id(), policy.fy(), itemKey);
        String status = cur.isEmpty() ? null : (String) cur.get(0).get("status");
        if ("PENDING".equals(status)) throw new UserException(HttpStatus.BAD_REQUEST, "Waiting for Finance — replace it after they decide.");
        Path dir = projectDir.resolve(uploadDir).normalize().resolve("proofs").resolve(u.id().toString());
        Path abs = dir.resolve(UUID.randomUUID() + "." + kind.ext());
        try {
            Files.createDirectories(dir);
            Files.write(abs, bytes);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        String name = safeName(fileName == null ? "" : fileName);
        name = name.substring(0, Math.min(120, name.length()));
        if (name.isEmpty()) name = "proof." + kind.ext();
        String rel = projectDir.relativize(abs).toString();
        boolean reopen = "APPROVED".equals(status) || "REJECTED".equals(status);
        jdbc.update("""
            INSERT INTO declaration_items (id, employee_id, fy, section, item_key, amount, status, proof_path, proof_name, updated_at)
            VALUES (?, ?, ?, ?, ?, 0, 'DRAFT', ?, ?, now())
            ON CONFLICT (employee_id, fy, item_key) DO UPDATE SET proof_path = EXCLUDED.proof_path, proof_name = EXCLUDED.proof_name, updated_at = now()"""
                + (reopen ? ", status = 'DRAFT', decided_by = NULL, decided_at = NULL, reject_reason = NULL" : ""),
                UUID.randomUUID(), u.id(), policy.fy(), TaxCatalogue.ITEM_SECTION.get(itemKey), itemKey, rel, name);
        return name;
    }

    /** {@code name.replace(/[^\w.\- ()]/g, "_")} — per UTF-16 unit, like JavaScript. */
    static String safeName(String n) {
        StringBuilder b = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-' || c == ' ' || c == '(' || c == ')';
            b.append(ok ? c : '_');
        }
        return b.toString();
    }

    static boolean startsWith(byte[] b, byte[] magic) {
        if (b.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) if (b[i] != magic[i]) return false;
        return true;
    }

    public record ProofFile(String name, String mime, byte[] bytes) {}

    /** View a proof: the employee who uploaded it, or Finance with proofs.verify. Null = 404 with {@code missing} message. */
    public ProofFile proofFile(CurrentUser u, String id) {
        if (id == null || !id.matches("^[0-9a-f-]{36}$")) return null;
        List<Map<String, Object>> rs = jdbc.queryForList("SELECT employee_id, proof_path, proof_name FROM declaration_items WHERE id = ?::uuid", id);
        if (rs.isEmpty() || rs.get(0).get("proof_path") == null) return null;
        Map<String, Object> item = rs.get(0);
        if (!u.id().equals(item.get("employee_id")) && !u.can("proofs.verify")) throw new ForbiddenException();
        Path abs = projectDir.resolve(((String) item.get("proof_path")).replace('\\', '/')).normalize();
        if (!abs.startsWith(projectDir)) return null;
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(abs);
        } catch (IOException e) {
            return new ProofFile(null, null, null); // "The file is missing."
        }
        String n = abs.getFileName().toString().toLowerCase();
        String mime = n.endsWith(".pdf") ? "application/pdf" : n.endsWith(".png") ? "image/png" : n.endsWith(".jpg") ? "image/jpeg" : "application/octet-stream";
        String name = item.get("proof_name") == null ? "proof" : ((String) item.get("proof_name")).replace("\"", "");
        return new ProofFile(name, mime, bytes);
    }

    // ───────────────────────── proofs (Finance) ─────────────────────────

    public record ProofRow(String id, String name, String code, String dept, String reg, long dec, int pend, int appr) {}

    public Map<String, Object> proofsPage(Map<String, String> sp) {
        ListParams ls = ListParams.of(sp, List.of("name", "reg", "dec", "pend"), "pend", 100, "desc");
        String flt = ListParams.pick(sp, "st", List.of("All", "Pending", "Verified"), "All");
        String fy = settings.tax().fy();
        List<ProofRow> all = jdbc.query("""
            SELECT e.id::text, e.full_name, e.code, d.name AS dept, COALESCE((SELECT t.regime::text FROM tax_profiles t WHERE t.employee_id = e.id AND t.fy = ?), 'NEW') AS reg,
                   (SELECT COALESCE(sum(i.amount), 0) FROM declaration_items i WHERE i.employee_id = e.id AND i.fy = ?) AS dec,
                   (SELECT count(*) FROM declaration_items i WHERE i.employee_id = e.id AND i.fy = ? AND i.status = 'PENDING') AS pend,
                   (SELECT count(*) FROM declaration_items i WHERE i.employee_id = e.id AND i.fy = ? AND i.status = 'APPROVED') AS appr
            FROM employees e LEFT JOIN departments d ON d.id = e.department_id
            WHERE EXISTS (SELECT 1 FROM declaration_items i WHERE i.employee_id = e.id AND i.fy = ? AND (i.amount > 0 OR i.proof_path IS NOT NULL))""",
                (rs, i) -> new ProofRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4) == null ? "—" : rs.getString(4), rs.getString(5),
                        rs.getLong(6), rs.getInt(7), rs.getInt(8)), fy, fy, fy, fy, fy);
        String q = ls.q().toLowerCase();
        List<ProofRow> rows = new ArrayList<>(all.stream().filter(r -> (q.isEmpty() || (r.name() + " " + r.code() + " " + r.dept()).toLowerCase().contains(q))
                && ("All".equals(flt) || ("Pending".equals(flt) ? r.pend() > 0 : r.pend() == 0 && r.appr() > 0))).toList());
        Map<String, Function<ProofRow, ? extends Comparable>> keys = Map.of("name", ProofRow::name, "reg", ProofRow::reg, "dec", ProofRow::dec, "pend", ProofRow::pend);
        rows.sort(ls.comparator(keys));
        return Map.of("rows", rows, "total", all.size());
    }

    public record ProofItem(String id, String key, String label, String sec, long amount, String status, String proofName, boolean hasProof, String reason) {}

    /** Drawer contents: items + TDS now vs. if everything pending is approved. */
    public Map<String, Object> proofDetail(CurrentUser u, String employeeIdRaw) {
        UUID employeeId = Check.uuid(employeeIdRaw);
        String fy = settings.tax().fy();
        Map<String, Object> e = jdbc.queryForMap("SELECT full_name, code FROM employees WHERE id = ?", employeeId);
        List<Map<String, Object>> prof = jdbc.queryForList("SELECT regime::text AS regime, metro, landlord_pan FROM tax_profiles WHERE employee_id = ? AND fy = ?", employeeId, fy);
        List<ProofItem> items = jdbc.query("""
            SELECT id::text, item_key, section, amount, status::text, proof_name, proof_path, reject_reason FROM declaration_items
            WHERE employee_id = ? AND fy = ? AND (amount > 0 OR proof_path IS NOT NULL) ORDER BY section ASC""",
                (rs, i) -> new ProofItem(rs.getString(1), rs.getString(2), TaxCatalogue.ITEM_LABEL.getOrDefault(rs.getString(2), rs.getString(2)),
                        TaxCatalogue.SECTION_OF_ITEM.getOrDefault(rs.getString(2), rs.getString(3)), rs.getLong(4), rs.getString(5), rs.getString(6),
                        rs.getString(7) != null, rs.getString(8)), employeeId, fy);
        TaxPositions.Positions pos = positions.taxPositions(List.of(employeeId));
        Long tdsNow = null, tdsIfAll = null;
        if (!pos.rows().isEmpty()) {
            PayRow row = pos.rows().get(0);
            // preview on the old regime — that's where proofs matter
            tdsNow = positions.reproject(row, pos.month(), DeclMode.approved, "OLD").monthly();
            tdsIfAll = positions.reproject(row, pos.month(), DeclMode.submitted, "OLD").monthly();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", e.get("full_name"));
        out.put("code", e.get("code"));
        out.put("regime", prof.isEmpty() ? "NEW" : prof.get(0).get("regime"));
        out.put("metro", !prof.isEmpty() && Boolean.TRUE.equals(prof.get(0).get("metro")));
        out.put("landlordPan", prof.isEmpty() ? null : prof.get(0).get("landlord_pan"));
        out.put("items", items);
        out.put("tdsNow", tdsNow);
        out.put("tdsIfAll", tdsIfAll);
        out.put("mine", employeeId.equals(u.id()));
        return out;
    }

    public record ProofDecision(String id, Boolean approve, String reason) {}

    /** Approve / reject one proof. Only approved amounts reduce TDS. */
    public String decideProof(CurrentUser u, ProofDecision f, String ip) {
        UUID id = Check.uuid(f.id());
        if (f.approve() == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected boolean, received undefined");
        String reason = f.reason() == null ? null : f.reason().trim();
        if (reason != null && reason.length() > 300) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected string to have <=300 characters");
        List<Map<String, Object>> rs = jdbc.queryForList("SELECT employee_id, item_key, amount FROM declaration_items WHERE id = ?", id);
        if (rs.isEmpty()) throw new UserException("Item not found.");
        Map<String, Object> item = rs.get(0);
        UUID owner = (UUID) item.get("employee_id");
        if (owner.equals(u.id())) throw new UserException("You can't verify your own proofs.");
        if (!f.approve() && (reason == null || reason.isEmpty())) throw new UserException("Give a reason so the employee can fix it.");
        int n = jdbc.update("UPDATE declaration_items SET status = ?::\"DeclarationStatus\", decided_by = ?, decided_at = now(), reject_reason = ?, updated_at = now() WHERE id = ? AND status = 'PENDING'",
                f.approve() ? "APPROVED" : "REJECTED", u.id(), f.approve() ? null : reason, id);
        if (n == 0) throw new UserException("This item was already decided.");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", f.approve() ? "APPROVED" : "REJECTED");
        after.put("amount", ((Number) item.get("amount")).longValue());
        if (reason != null) after.put("reason", reason);
        audit.log(u.id(), "proof.decision", "declaration_item", id.toString(), Map.of("status", "PENDING"), after, ip);
        String key = (String) item.get("item_key");
        String label = TaxCatalogue.ITEM_LABEL.getOrDefault(key, key);
        notify.notify(owner, label + " proof " + (f.approve() ? "approved" : "rejected — " + reason), "/declare");
        return TaxCatalogue.ITEM_LABEL.get(key) + " " + (f.approve() ? "approved" : "rejected");
    }

    public String approveAllPending(CurrentUser u, String employeeIdRaw, String ip) {
        UUID employeeId = Check.uuid(employeeIdRaw);
        if (employeeId.equals(u.id())) throw new UserException("You can't verify your own proofs.");
        String fy = settings.tax().fy();
        List<Map<String, Object>> pending = jdbc.queryForList("SELECT id, item_key, amount FROM declaration_items WHERE employee_id = ? AND fy = ? AND status = 'PENDING'", employeeId, fy);
        if (pending.isEmpty()) throw new UserException("Nothing pending.");
        tx.executeWithoutResult(s -> {
            UUID[] ids = pending.stream().map(p -> (UUID) p.get("id")).toArray(UUID[]::new);
            jdbc.update("UPDATE declaration_items SET status = 'APPROVED', decided_by = ?, decided_at = now(), reject_reason = NULL, updated_at = now() WHERE id = ANY (?) AND status = 'PENDING'",
                    u.id(), ids);
            List<Map<String, Object>> approved = new ArrayList<>();
            for (Map<String, Object> p : pending) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("key", p.get("item_key"));
                x.put("amount", ((Number) p.get("amount")).longValue());
                approved.add(x);
            }
            audit.log(u.id(), "proof.decision", "declaration_item", employeeId + ":" + fy, null, Map.of("approved", approved), ip);
        });
        int n = pending.size();
        notify.notify(employeeId, n + " investment proof" + (n == 1 ? "" : "s") + " approved — your TDS goes down from the next payroll", "/declare");
        return n + " proof" + (n == 1 ? "" : "s") + " approved";
    }

    // ───────────────────────── Tax & TDS (Finance) ─────────────────────────

    public Map<String, Object> taxPage(CurrentUser u, Map<String, String> sp) {
        TaxPositions.Rows r = positions.taxRows(sp);
        return Map.of("month", r.month(), "fy", r.fy(), "all", r.all(), "rows", r.rows(), "canVerify", u.can("proofs.verify"));
    }

    public Map<String, Object> taxDetail(String employeeIdRaw) {
        UUID employeeId = Check.uuid(employeeIdRaw);
        TaxPositions.Positions pos = positions.taxPositions(List.of(employeeId));
        if (pos.rows().isEmpty()) throw new UserException("No salary structure — nothing to compute yet.");
        PayRow row = pos.rows().get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", pos.month());
        out.put("tax", row.tax());
        out.put("name", row.name());
        out.put("code", row.code());
        out.put("title", row.designation());
        return out;
    }

    /** Finance overrides an employee's regime (REQUIREMENTS §5.9). */
    public String overrideRegime(CurrentUser u, String employeeIdRaw, String regimeRaw, String ip) {
        UUID employeeId = Check.uuid(employeeIdRaw);
        String regime = Check.oneOf(regimeRaw, List.of("NEW", "OLD"));
        String fy = settings.tax().fy();
        List<String> before = jdbc.queryForList("SELECT regime::text FROM tax_profiles WHERE employee_id = ? AND fy = ?", String.class, employeeId, fy);
        String prev = before.isEmpty() ? "NEW" : before.get(0);
        jdbc.update("""
            INSERT INTO tax_profiles (employee_id, fy, regime, regime_overridden, metro, parents_senior, prev_employer_income, prev_employer_tds, updated_at)
            VALUES (?, ?, ?::"TaxRegime", true, false, false, 0, 0, now())
            ON CONFLICT (employee_id, fy) DO UPDATE SET regime = EXCLUDED.regime, regime_overridden = true, updated_at = now()""", employeeId, fy, regime);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("regime", regime);
        after.put("by", "finance_override");
        audit.log(u.id(), "tax.regime_change", "tax_profile", employeeId + ":" + fy, Map.of("regime", prev), after, ip);
        if (!prev.equals(regime)) notify.notify(employeeId, "Finance set your tax regime to the " + ("NEW".equals(regime) ? "new" : "old") + " regime", "/declare");
        return "Regime set to " + ("NEW".equals(regime) ? "new" : "old") + " — TDS updates from this payroll";
    }

    public record PrevInput(String employeeId, Double income, Double tds) {}

    static long rupees(Double v) {
        if (v == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected number, received undefined");
        if (v != Math.rint(v)) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected int, received number");
        if (v < 0) throw new UserException(HttpStatus.BAD_REQUEST, "Too small: expected number to be >=0");
        if (v > 1_000_000_000) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected number to be <=1000000000");
        return v.longValue();
    }

    /** Previous employer income and TDS this FY (Form 12B), in rupees. */
    public String setPreviousEmployer(CurrentUser u, PrevInput f, String ip) {
        UUID employeeId = Check.uuid(f.employeeId());
        long income = rupees(f.income()), tds = rupees(f.tds());
        if (tds > income) throw new UserException("TDS can't be more than the income.");
        String fy = settings.tax().fy();
        List<Map<String, Object>> before = jdbc.queryForList("SELECT prev_employer_income, prev_employer_tds FROM tax_profiles WHERE employee_id = ? AND fy = ?", employeeId, fy);
        jdbc.update("""
            INSERT INTO tax_profiles (employee_id, fy, regime, regime_overridden, metro, parents_senior, prev_employer_income, prev_employer_tds, updated_at)
            VALUES (?, ?, 'NEW', false, false, false, ?, ?, now())
            ON CONFLICT (employee_id, fy) DO UPDATE SET prev_employer_income = EXCLUDED.prev_employer_income, prev_employer_tds = EXCLUDED.prev_employer_tds, updated_at = now()""",
                employeeId, fy, income * 100, tds * 100);
        Map<String, Object> b = null;
        if (!before.isEmpty()) {
            b = new LinkedHashMap<>();
            b.put("income", ((Number) before.get(0).get("prev_employer_income")).longValue());
            b.put("tds", ((Number) before.get(0).get("prev_employer_tds")).longValue());
        }
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("income", income * 100);
        a.put("tds", tds * 100);
        audit.log(u.id(), "tax.previous_employer", "tax_profile", employeeId + ":" + fy, b, a, ip);
        return "Previous employer figures saved";
    }

    public record Export(String filename, String body) {}

    /** Tax & TDS export (tax.view) — same filters as the page. */
    public Export export(CurrentUser u, Map<String, String> sp) {
        TaxPositions.Rows r = positions.taxRows(sp);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("rows", r.rows().size());
        after.put("month", r.month());
        audit.log(u.id(), "tax.export", "tax", r.fy(), null, after, null);
        List<List<Object>> rows = new ArrayList<>();
        for (TaxPositions.Row x : r.rows()) {
            rows.add(Arrays.asList(x.code(), x.name(), "OLD".equals(x.reg()) ? "Old" : "New", x.inc(), x.ded(), x.taxable(), x.tax(), x.tds(), x.pend()));
        }
        return new Export("tax-tds-" + r.fy() + "-" + r.month() + ".csv", Csv.toCsv(List.of("Employee ID", "Name", "Regime", "Projected income",
                "Deductions (incl. standard)", "Taxable income", "Annual tax", "Monthly TDS", "Pending proofs"), rows));
    }

    /** JS number text for messages. */
    static final class LeaveNum {
        static String num(double d) {
            return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
        }
    }
}
