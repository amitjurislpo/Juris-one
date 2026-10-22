package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.exception.ForbiddenException;
import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.config.JurisOneProperties;
import com.jurislpo.jurisone.engine.Csv;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.PeopleRules;
import com.jurislpo.jurisone.repository.TaxOperationsRepository;
import com.jurislpo.jurisone.service.PayrollService.PayRow;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.service.TaxService.DeclarationMode;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Tax declarations (self), investment proofs (proofs.verify) and Tax & TDS (tax.view). */
@Service
public class TaxOperationsService {

    private final TaxOperationsRepository repo;
    private final TransactionTemplate transactionTemplate;
    private final TaxPositions positions;
    private final SettingsService settings;
    private final AuditService audit;
    private final NotificationService notify;
    private final Path storageDir;

    public TaxOperationsService(TaxOperationsRepository repo, TransactionTemplate transactionTemplate, TaxPositions positions, SettingsService settings, AuditService audit, NotificationService notify,
                         JurisOneProperties props) {
        this.repo = repo;
        this.transactionTemplate = transactionTemplate;
        this.positions = positions;
        this.settings = settings;
        this.audit = audit;
        this.notify = notify;
        this.storageDir = Path.of(props.storageDir()).toAbsolutePath().normalize();
    }

    static LocalDate today() {
        return Format.todayIn("Asia/Kolkata");
    }

    // ───────────────────────── declarations (employee) ─────────────────────────

    public record ItemView(String id, String key, double amount, String status, String proofName, String reason) {}

    public Map<String, Object> declarePage(CurrentUser user) {
        SettingsService.TaxPolicy policy = settings.tax();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("financialYear", policy.financialYear());
        if (!"IN".equals(user.region())) {
            out.put("india", false);
            return out;
        }
        out.put("india", true);
        String today = today().toString();
        List<Map<String, Object>> prof = repo.declareProfile(user.id(), policy.financialYear());
        Map<String, Object> profile = prof.isEmpty() ? null : prof.get(0);
        List<ItemView> items = repo.itemViews(user.id(), policy.financialYear());
        TaxPositions.Positions employeePositions = positions.taxPositions(List.of(user.id()));
        if (employeePositions.rows().isEmpty()) {
            out.put("hasStructure", false);
            return out;
        }
        PayRow row = employeePositions.rows().get(0);
        // live comparison uses everything you've entered (drafts too); TDS itself uses approved proofs only
        Projection what = positions.reproject(row, employeePositions.month(), DeclarationMode.all, null);
        out.put("hasStructure", true);
        out.put("regime", profile == null ? "NEW" : profile.get("regime"));
        out.put("overridden", profile != null && Boolean.TRUE.equals(profile.get("regime_overridden")));
        out.put("canChangeRegime", today.compareTo(policy.regimeChangeUntil()) <= 0);
        out.put("windowOpen", today.compareTo(policy.proofWindowFrom()) >= 0 && today.compareTo(policy.proofWindowUntil()) <= 0);
        out.put("windowText", "Declarations and proofs are open until " + Format.formatDayMonthYear(LocalDate.parse(policy.proofWindowUntil())) + "; the regime can be changed until "
                + Format.formatDayMonthYear(LocalDate.parse(policy.regimeChangeUntil())) + ".");
        out.put("newTax", what.newRegime().total());
        out.put("oldTax", what.oldRegime().total());
        out.put("monthlyTds", row.tax().monthly());
        out.put("epf", what.pfAnnual());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("metro", profile != null && Boolean.TRUE.equals(profile.get("metro")));
        view.put("parentsSenior", profile != null && Boolean.TRUE.equals(profile.get("parents_senior")));
        view.put("landlordPan", profile == null || profile.get("landlord_pan") == null ? "" : profile.get("landlord_pan"));
        out.put("profile", view);
        out.put("items", items);
        return out;
    }

    record TaxContext(CurrentUser user, SettingsService.TaxPolicy policy, String today, Map<String, Object> profile) {}

    private TaxContext me(CurrentUser user) {
        if (!"IN".equals(user.region())) throw new UserException("Tax declarations are for the India payroll.");
        SettingsService.TaxPolicy policy = settings.tax();
        repo.ensureProfile(user.id(), policy.financialYear());
        Map<String, Object> profile = repo.profileRegime(user.id(), policy.financialYear());
        return new TaxContext(user, policy, today().toString(), profile);
    }

    private static void windowOpen(SettingsService.TaxPolicy taxPolicy, String today) {
        if (today.compareTo(taxPolicy.proofWindowFrom()) < 0) throw new UserException("Declarations open on " + Format.formatDayMonthYear(LocalDate.parse(taxPolicy.proofWindowFrom())) + ".");
        if (today.compareTo(taxPolicy.proofWindowUntil()) > 0) {
            throw new UserException("Declarations closed on " + Format.formatDayMonthYear(LocalDate.parse(taxPolicy.proofWindowUntil())) + ". Ask Finance for changes.");
        }
    }

    public String setRegime(CurrentUser user, String regimeRaw, String ipAddress) {
        TaxContext taxContext = me(user);
        String regime = Check.oneOf(regimeRaw, List.of("NEW", "OLD"));
        if (Boolean.TRUE.equals(taxContext.profile().get("regime_overridden"))) throw new UserException("Finance has set your regime. Contact Finance to change it.");
        if (taxContext.today().compareTo(taxContext.policy().regimeChangeUntil()) > 0) {
            throw new UserException("The regime could be changed until " + Format.formatDayMonthYear(LocalDate.parse(taxContext.policy().regimeChangeUntil())) + ".");
        }
        if (regime.equals(taxContext.profile().get("regime"))) return null;
        repo.updateRegime(regime, user.id(), taxContext.policy().financialYear());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("regime", regime);
        after.put("by", "employee");
        audit.log(user.id(), "tax.regime_change", "tax_profile", user.id() + ":" + taxContext.policy().financialYear(), Map.of("regime", taxContext.profile().get("regime")), after, ipAddress);
        return "You're on the " + ("NEW".equals(regime) ? "new" : "old") + " regime — TDS updates from the next payroll";
    }

    public record ProfileInput(Boolean metro, Boolean parentsSenior, String landlordPan) {}

    public void saveProfile(CurrentUser user, ProfileInput input) {
        TaxContext taxContext = me(user);
        windowOpen(taxContext.policy(), taxContext.today());
        if (input.metro() == null || input.parentsSenior() == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected boolean, received undefined");
        String pan = Check.requireString(input.landlordPan()).trim().toUpperCase();
        if (pan.length() > 10) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected string to have <=10 characters");
        if (!pan.isEmpty() && !PeopleRules.PAN_RE.matcher(pan).matches()) throw new UserException("Landlord PAN looks like ABCDE1234F.");
        repo.updateProfileDetails(input.metro(), input.parentsSenior(), pan.isEmpty() ? null : pan, user.id(), taxContext.policy().financialYear());
    }

    public record ItemInput(String key, Double amount) {}

    /** Saves a declared amount (₹). Changing an approved or rejected amount sends it back to Draft. */
    public String saveItem(CurrentUser user, ItemInput input) {
        TaxContext taxContext = me(user);
        windowOpen(taxContext.policy(), taxContext.today());
        if (input.key() == null || !TaxCatalogue.declarable(input.key())) throw new UserException(HttpStatus.BAD_REQUEST, "Unknown item");
        Double rupees = input.amount();
        if (rupees == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected number, received undefined");
        if (rupees != Math.rint(rupees)) throw new UserException(HttpStatus.BAD_REQUEST, "Whole rupees only");
        if (rupees < 0) throw new UserException(HttpStatus.BAD_REQUEST, "Too small: expected number to be >=0");
        if (rupees > 100_000_000) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected number to be <=100000000");
        long amount = rupees.longValue() * 100;
        List<Map<String, Object>> currentItems = repo.findItem(user.id(), taxContext.policy().financialYear(), input.key());
        Map<String, Object> row = currentItems.isEmpty() ? null : currentItems.get(0);
        if (row != null && ((Number) row.get("amount")).longValue() == amount) return null;
        if (row != null && "PENDING".equals(row.get("status"))) throw new UserException("This item is waiting for Finance — you can change it after they decide.");
        repo.upsertItemAmount(UUID.randomUUID(), user.id(), taxContext.policy().financialYear(), TaxCatalogue.ITEM_SECTION.get(input.key()), input.key(), amount);
        audit.log(user.id(), "declaration.save", "declaration_item", user.id() + ":" + taxContext.policy().financialYear() + ":" + input.key(),
                row == null ? null : Map.of("amount", ((Number) row.get("amount")).longValue()), Map.of("amount", amount), null);
        if (row != null && "APPROVED".equals(row.get("status"))) {
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("amount", ((Number) row.get("amount")).longValue());
            before.put("status", "APPROVED");
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("amount", amount);
            after.put("status", "DRAFT");
            audit.log(user.id(), "declaration.reopened", "declaration_item", row.get("id").toString(), before, after, null);
            return TaxCatalogue.ITEM_LABEL.get(input.key()) + " changed — back to draft until you resubmit";
        }
        return null;
    }

    /** Sends every draft with an amount for verification; each needs a proof. */
    public String submitForVerification(CurrentUser user, String ipAddress) {
        TaxContext taxContext = me(user);
        windowOpen(taxContext.policy(), taxContext.today());
        if (!"OLD".equals(taxContext.profile().get("regime"))) throw new UserException("Declarations only matter on the old regime.");
        List<Map<String, Object>> drafts = repo.draftsWithAmount(user.id(), taxContext.policy().financialYear());
        if (drafts.isEmpty()) throw new UserException("Nothing new to submit — enter an amount and attach its proof first.");
        List<String> noProof = drafts.stream().filter(d -> d.get("proof_path") == null).map(d -> TaxCatalogue.ITEM_LABEL.get((String) d.get("item_key"))).toList();
        if (!noProof.isEmpty()) throw new UserException("Attach a proof for: " + String.join(", ", noProof) + ".");
        boolean bigRent = drafts.stream().anyMatch(d -> "rent".equals(d.get("item_key")) && ((Number) d.get("amount")).longValue() > 100_000_00L);
        if (bigRent && taxContext.profile().get("landlord_pan") == null) throw new UserException("Rent is above ₹1,00,000 a year — add your landlord's PAN.");
        UUID[] ids = drafts.stream().map(d -> (UUID) d.get("id")).toArray(UUID[]::new);
        repo.markPending(ids);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> draft : drafts) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("key", draft.get("item_key"));
            view.put("amount", ((Number) draft.get("amount")).longValue());
            items.add(view);
        }
        audit.log(user.id(), "declaration.submit", "declaration_item", user.id() + ":" + taxContext.policy().financialYear(), null, Map.of("items", items), ipAddress);
        return drafts.size() + " item" + (drafts.size() == 1 ? "" : "s") + " sent to Finance for verification";
    }

    // ───────────────────────── proof files ─────────────────────────

    record FileKind(String extension, byte[] magic) {}

    static final List<FileKind> KINDS = List.of(new FileKind("pdf", new byte[] {0x25, 0x50, 0x44, 0x46}), new FileKind("png", new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47}),
            new FileKind("jpg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}));

    /** Upload a proof for one declaration item. Owner only, within the window. Errors → 400 {error}. */
    public String uploadProof(CurrentUser user, String itemKey, String fileName, byte[] bytes) {
        SettingsService.TaxPolicy policy = settings.tax();
        String today = today().toString();
        if (today.compareTo(policy.proofWindowFrom()) < 0 || today.compareTo(policy.proofWindowUntil()) > 0) throw new UserException(HttpStatus.BAD_REQUEST, "The proof window is closed.");
        if (itemKey == null || !TaxCatalogue.declarable(itemKey)) throw new UserException(HttpStatus.BAD_REQUEST, "Unknown item.");
        if (bytes == null || bytes.length == 0) throw new UserException(HttpStatus.BAD_REQUEST, "Choose a file.");
        if (bytes.length > policy.maxProofMb() * 1024 * 1024) throw new UserException(HttpStatus.BAD_REQUEST, "Files up to " + LeaveNum.formatNumber(policy.maxProofMb()) + " MB.");
        FileKind kind = KINDS.stream().filter(k -> startsWith(bytes, k.magic())).findFirst().orElse(null); // trust content, not the extension
        if (kind == null) throw new UserException(HttpStatus.BAD_REQUEST, "Upload a PDF, JPG or PNG.");
        List<Map<String, Object>> currentItems = repo.itemStatus(user.id(), policy.financialYear(), itemKey);
        String status = currentItems.isEmpty() ? null : (String) currentItems.get(0).get("status");
        if ("PENDING".equals(status)) throw new UserException(HttpStatus.BAD_REQUEST, "Waiting for Finance — replace it after they decide.");
        Path directory = storageDir.resolve("proofs").resolve(user.id().toString());
        Path absolutePath = directory.resolve(UUID.randomUUID() + "." + kind.extension());
        try {
            Files.createDirectories(directory);
            Files.write(absolutePath, bytes);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        String name = safeName(fileName == null ? "" : fileName);
        name = name.substring(0, Math.min(120, name.length()));
        if (name.isEmpty()) name = "proof." + kind.extension();
        String relativePath = storageDir.relativize(absolutePath).toString();
        boolean reopen = "APPROVED".equals(status) || "REJECTED".equals(status);
        repo.upsertItemProof(UUID.randomUUID(), user.id(), policy.financialYear(), TaxCatalogue.ITEM_SECTION.get(itemKey), itemKey, relativePath, name, reopen);
        audit.log(user.id(), "declaration.proof_upload", "declaration_item", user.id() + ":" + policy.financialYear() + ":" + itemKey, null, Map.of("file", name, "bytes", bytes.length), null);
        return name;
    }

    /** {@code name.replace(/[^\w.\- ()]/g, "_")} — per UTF-16 unit, like JavaScript. */
    static String safeName(String name) {
        StringBuilder builder = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            boolean isAllowed = (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z') || (character >= '0' && character <= '9') || character == '_' || character == '.' || character == '-' || character == ' ' || character == '(' || character == ')';
            builder.append(isAllowed ? character : '_');
        }
        return builder.toString();
    }

    static boolean startsWith(byte[] bytes, byte[] magic) {
        if (bytes.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) if (bytes[i] != magic[i]) return false;
        return true;
    }

    public record ProofFile(String name, String mime, byte[] bytes) {}

    /** View a proof: the employee who uploaded it, or Finance with proofs.verify. Null = 404 with {@code missing} message. */
    public ProofFile proofFile(CurrentUser user, String id) {
        if (id == null || !id.matches("^[0-9a-f-]{36}$")) return null;
        List<Map<String, Object>> rows = repo.proofOf(id);
        if (rows.isEmpty() || rows.get(0).get("proof_path") == null) return null;
        Map<String, Object> item = rows.get(0);
        if (!user.id().equals(item.get("employee_id")) && !user.can("proofs.verify")) throw new ForbiddenException();
        Path absolutePath = storageDir.resolve(((String) item.get("proof_path")).replace('\\', '/')).normalize();
        if (!absolutePath.startsWith(storageDir)) return null;
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(absolutePath);
        } catch (IOException e) {
            return new ProofFile(null, null, null); // "The file is missing."
        }
        String fileName = absolutePath.getFileName().toString().toLowerCase();
        String mime = fileName.endsWith(".pdf") ? "application/pdf" : fileName.endsWith(".png") ? "image/png" : fileName.endsWith(".jpg") ? "image/jpeg" : "application/octet-stream";
        String name = item.get("proof_name") == null ? "proof" : ((String) item.get("proof_name")).replace("\"", "");
        return new ProofFile(name, mime, bytes);
    }

    // ───────────────────────── proofs (Finance) ─────────────────────────

    public record ProofRow(String id, String name, String code, String department, String regime, long declared, int pending, int approved) {}

    public Map<String, Object> proofsPage(Map<String, String> queryParams) {
        ListParams listParams = ListParams.of(queryParams, List.of("name", "regime", "declared", "pending"), "pending", 100, "desc");
        String statusFilter = ListParams.pick(queryParams, "status", List.of("All", "Pending", "Verified"), "All");
        String financialYear = settings.tax().financialYear();
        List<ProofRow> all = repo.proofRows(financialYear);
        String search = listParams.search().toLowerCase();
        List<ProofRow> rows = new ArrayList<>(all.stream().filter(row -> (search.isEmpty() || (row.name() + " " + row.code() + " " + row.department()).toLowerCase().contains(search))
                && ("All".equals(statusFilter) || ("Pending".equals(statusFilter) ? row.pending() > 0 : row.pending() == 0 && row.approved() > 0))).toList());
        Map<String, Function<ProofRow, ? extends Comparable>> keys = Map.of("name", ProofRow::name, "regime", ProofRow::regime, "declared", ProofRow::declared, "pending", ProofRow::pending);
        rows.sort(listParams.comparator(keys));
        return Map.of("rows", rows, "total", all.size());
    }

    public record ProofItem(String id, String key, String label, String section, long amount, String status, String proofName, boolean hasProof, String reason) {}

    /** Drawer contents: items + TDS now vs. if everything pending is approved. */
    public Map<String, Object> proofDetail(CurrentUser user, String employeeIdRaw) {
        UUID employeeId = Check.uuid(employeeIdRaw);
        String financialYear = settings.tax().financialYear();
        Map<String, Object> employee = repo.employeeNameCode(employeeId);
        List<Map<String, Object>> prof = repo.proofProfile(employeeId, financialYear);
        List<ProofItem> items = repo.proofItems(employeeId, financialYear);
        TaxPositions.Positions employeePositions = positions.taxPositions(List.of(employeeId));
        Long tdsNow = null, tdsIfAll = null;
        if (!employeePositions.rows().isEmpty()) {
            PayRow row = employeePositions.rows().get(0);
            // preview on the old regime — that's where proofs matter
            tdsNow = positions.reproject(row, employeePositions.month(), DeclarationMode.approved, "OLD").monthly();
            tdsIfAll = positions.reproject(row, employeePositions.month(), DeclarationMode.submitted, "OLD").monthly();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", employee.get("full_name"));
        out.put("code", employee.get("code"));
        out.put("regime", prof.isEmpty() ? "NEW" : prof.get(0).get("regime"));
        out.put("metro", !prof.isEmpty() && Boolean.TRUE.equals(prof.get(0).get("metro")));
        out.put("landlordPan", prof.isEmpty() ? null : prof.get(0).get("landlord_pan"));
        out.put("items", items);
        out.put("tdsNow", tdsNow);
        out.put("tdsIfAll", tdsIfAll);
        out.put("mine", employeeId.equals(user.id()));
        return out;
    }

    public record ProofDecision(String id, Boolean approve, String reason) {}

    /** Approve / reject one proof. Only approved amounts reduce TDS. */
    public String decideProof(CurrentUser user, ProofDecision proofDecision, String ipAddress) {
        UUID id = Check.uuid(proofDecision.id());
        if (proofDecision.approve() == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected boolean, received undefined");
        String reason = proofDecision.reason() == null ? null : proofDecision.reason().trim();
        if (reason != null && reason.length() > 300) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected string to have <=300 characters");
        List<Map<String, Object>> rows = repo.itemForDecision(id);
        if (rows.isEmpty()) throw new UserException("Item not found.");
        Map<String, Object> item = rows.get(0);
        UUID owner = (UUID) item.get("employee_id");
        if (owner.equals(user.id())) throw new UserException("You can't verify your own proofs.");
        if (!proofDecision.approve() && (reason == null || reason.isEmpty())) throw new UserException("Give a reason so the employee can fix it.");
        int count = repo.decideItem(proofDecision.approve() ? "APPROVED" : "REJECTED", user.id(), proofDecision.approve() ? null : reason, id);
        if (count == 0) throw new UserException("This item was already decided.");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", proofDecision.approve() ? "APPROVED" : "REJECTED");
        after.put("amount", ((Number) item.get("amount")).longValue());
        if (reason != null) after.put("reason", reason);
        audit.log(user.id(), "proof.decision", "declaration_item", id.toString(), Map.of("status", "PENDING"), after, ipAddress);
        String key = (String) item.get("item_key");
        String label = TaxCatalogue.ITEM_LABEL.getOrDefault(key, key);
        notify.notify(owner, label + " proof " + (proofDecision.approve() ? "approved" : "rejected — " + reason), "/declare");
        return TaxCatalogue.ITEM_LABEL.get(key) + " " + (proofDecision.approve() ? "approved" : "rejected");
    }

    public String approveAllPending(CurrentUser user, String employeeIdRaw, String ipAddress) {
        UUID employeeId = Check.uuid(employeeIdRaw);
        if (employeeId.equals(user.id())) throw new UserException("You can't verify your own proofs.");
        String financialYear = settings.tax().financialYear();
        List<Map<String, Object>> pending = repo.pendingItems(employeeId, financialYear);
        if (pending.isEmpty()) throw new UserException("Nothing pending.");
        transactionTemplate.executeWithoutResult(transactionStatus -> {
            UUID[] ids = pending.stream().map(p -> (UUID) p.get("id")).toArray(UUID[]::new);
            repo.approvePending(user.id(), ids);
            List<Map<String, Object>> approved = new ArrayList<>();
            for (Map<String, Object> row : pending) {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("key", row.get("item_key"));
                view.put("amount", ((Number) row.get("amount")).longValue());
                approved.add(view);
            }
            audit.log(user.id(), "proof.decision", "declaration_item", employeeId + ":" + financialYear, null, Map.of("approved", approved), ipAddress);
        });
        int count = pending.size();
        notify.notify(employeeId, count + " investment proof" + (count == 1 ? "" : "s") + " approved — your TDS goes down from the next payroll", "/declare");
        return count + " proof" + (count == 1 ? "" : "s") + " approved";
    }

    // ───────────────────────── Tax & TDS (Finance) ─────────────────────────

    public Map<String, Object> taxPage(CurrentUser user, Map<String, String> queryParams) {
        TaxPositions.Rows taxRows = positions.taxRows(queryParams);
        return Map.of("month", taxRows.month(), "financialYear", taxRows.financialYear(), "all", taxRows.all(), "rows", taxRows.rows(), "canVerify", user.can("proofs.verify"));
    }

    public Map<String, Object> taxDetail(String employeeIdRaw) {
        UUID employeeId = Check.uuid(employeeIdRaw);
        TaxPositions.Positions employeePositions = positions.taxPositions(List.of(employeeId));
        if (employeePositions.rows().isEmpty()) throw new UserException("No salary structure — nothing to compute yet.");
        PayRow row = employeePositions.rows().get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", employeePositions.month());
        out.put("tax", row.tax());
        out.put("name", row.name());
        out.put("code", row.code());
        out.put("title", row.designation());
        return out;
    }

    /** Finance overrides an employee's regime (REQUIREMENTS §5.9). */
    public String overrideRegime(CurrentUser user, String employeeIdRaw, String regimeRaw, String ipAddress) {
        UUID employeeId = Check.uuid(employeeIdRaw);
        String regime = Check.oneOf(regimeRaw, List.of("NEW", "OLD"));
        String financialYear = settings.tax().financialYear();
        List<String> before = repo.regimeOf(employeeId, financialYear);
        String previous = before.isEmpty() ? "NEW" : before.get(0);
        repo.upsertOverriddenRegime(employeeId, financialYear, regime);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("regime", regime);
        after.put("by", "finance_override");
        audit.log(user.id(), "tax.regime_change", "tax_profile", employeeId + ":" + financialYear, Map.of("regime", previous), after, ipAddress);
        if (!previous.equals(regime)) notify.notify(employeeId, "Finance set your tax regime to the " + ("NEW".equals(regime) ? "new" : "old") + " regime", "/declare");
        return "Regime set to " + ("NEW".equals(regime) ? "new" : "old") + " — TDS updates from this payroll";
    }

    public record PreviousEmployerInput(String employeeId, Double income, Double tds) {}

    static long rupees(Double value) {
        if (value == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected number, received undefined");
        if (value != Math.rint(value)) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected int, received number");
        if (value < 0) throw new UserException(HttpStatus.BAD_REQUEST, "Too small: expected number to be >=0");
        if (value > 1_000_000_000) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected number to be <=1000000000");
        return value.longValue();
    }

    /** Previous employer income and TDS this FY (Form 12B), in rupees. */
    public String setPreviousEmployer(CurrentUser user, PreviousEmployerInput input, String ipAddress) {
        UUID employeeId = Check.uuid(input.employeeId());
        long income = rupees(input.income()), tds = rupees(input.tds());
        if (tds > income) throw new UserException("TDS can't be more than the income.");
        String financialYear = settings.tax().financialYear();
        List<Map<String, Object>> before = repo.previousEmployer(employeeId, financialYear);
        repo.upsertPreviousEmployer(employeeId, financialYear, income * 100, tds * 100);
        Map<String, Object> row = null;
        if (!before.isEmpty()) {
            row = new LinkedHashMap<>();
            row.put("income", ((Number) before.get(0).get("prev_employer_income")).longValue());
            row.put("tds", ((Number) before.get(0).get("prev_employer_tds")).longValue());
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("income", income * 100);
        view.put("tds", tds * 100);
        audit.log(user.id(), "tax.previous_employer", "tax_profile", employeeId + ":" + financialYear, row, view, ipAddress);
        return "Previous employer figures saved";
    }

    public record Export(String filename, String body) {}

    /** Tax & TDS export (tax.view) — same filters as the page. */
    public Export export(CurrentUser user, Map<String, String> queryParams) {
        TaxPositions.Rows taxRows = positions.taxRows(queryParams);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("rows", taxRows.rows().size());
        after.put("month", taxRows.month());
        audit.log(user.id(), "tax.export", "tax", taxRows.financialYear(), null, after, null);
        List<List<Object>> rows = new ArrayList<>();
        for (TaxPositions.Row row : taxRows.rows()) {
            rows.add(Arrays.asList(row.code(), row.name(), "OLD".equals(row.regime()) ? "Old" : "New", row.income(), row.deductions(), row.taxable(), row.tax(), row.tds(), row.pending()));
        }
        return new Export("tax-tds-" + taxRows.financialYear() + "-" + taxRows.month() + ".csv", Csv.toCsv(List.of("Employee ID", "Name", "Regime", "Projected income",
                "Deductions (incl. standard)", "Taxable income", "Annual tax", "Monthly TDS", "Pending proofs"), rows));
    }

    /** JS number text for messages. */
    static final class LeaveNum {
        static String formatNumber(double value) {
            return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
        }
    }
}
