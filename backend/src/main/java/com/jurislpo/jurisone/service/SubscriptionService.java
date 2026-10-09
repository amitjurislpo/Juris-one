package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.repository.SubscriptionRepository;
import com.jurislpo.jurisone.tenancy.TenantContext;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Subscription plans (the super admin's catalogue) and the subscriptions of businesses. A business can be used only
 * while one of its subscriptions covers today (India time); its plan can also cap the number of employees.
 */
@Service
public class SubscriptionService {

    public static final String ZONE = "Asia/Kolkata";
    public static final List<String> MODULES = List.of("JURIS_TRACK");

    public record Plan(String id, String name, String description, long pricePaise, int periodMonths, Integer maxEmployees, List<String> modules, boolean active,
                       long businesses) {}

    public record Subscription(String id, String planId, String planName, String startsOn, String endsOn, long pricePaise, String status, String notes,
                               String createdAt) {}

    /**
     * The business's subscription today. {@code state}: ACTIVE (covers today), UPCOMING (starts later, none today),
     * EXPIRED (the latest has ended) or NONE (never had one). {@code daysLeft}: whole days until the end date (0 = ends today).
     */
    public record Current(String state, boolean active, String planName, String startsOn, String endsOn, Integer daysLeft, Integer maxEmployees,
                          List<String> modules, long employees) {}

    private final SubscriptionRepository repo;

    public SubscriptionService(SubscriptionRepository repo) {
        this.repo = repo;
    }

    public static LocalDate today() {
        return Format.todayIn(ZONE);
    }

    // ───────────────────────── current business ─────────────────────────

    /** Current subscription of the business in {@link TenantContext} (or of {@code businessId} in system mode). */
    public Current current(UUID businessId) {
        UUID b = businessId != null ? businessId : TenantContext.current();
        if (b == null) throw new IllegalStateException("No business");
        LocalDate today = today();
        long employees = repo.countActiveEmployees(b);
        if (isPlatform(b)) return new Current("ACTIVE", true, "Platform company", null, null, null, null, MODULES, employees); // never needs a plan
        List<Current> now = repo.findCovering(b, today, employees);
        if (!now.isEmpty()) {
            // the plan's modules plus modules granted on their own (complimentary or paid) that cover today
            Current c = now.get(0);
            java.util.TreeSet<String> all = new java.util.TreeSet<>(c.modules());
            all.addAll(grantedModules(b, today));
            return new Current(c.state(), c.active(), c.planName(), c.startsOn(), c.endsOn(), c.daysLeft(), c.maxEmployees(), List.copyOf(all), c.employees());
        }
        List<Current> later = repo.findUpcoming(b, today, employees);
        if (!later.isEmpty()) return later.get(0);
        List<Current> past = repo.findLastExpired(b, today, employees);
        return past.isEmpty() ? new Current("NONE", false, null, null, null, null, null, List.of(), employees) : past.get(0);
    }

    /** Modules granted to the business on their own that cover {@code day} (not revoked). */
    List<String> grantedModules(UUID businessId, LocalDate day) {
        return repo.findGrantedModules(businessId, day);
    }

    /** Modules the business can use today: none while its subscription isn't active. */
    public java.util.Set<String> activeModules(UUID businessId) {
        if (businessId == null) return java.util.Set.of();
        Current c = current(businessId);
        return c.active() ? java.util.Set.copyOf(c.modules()) : java.util.Set.of();
    }

    /** The JurisOne operator's own business (needs no subscription). */
    boolean isPlatform(UUID businessId) {
        return Boolean.TRUE.equals(repo.isPlatform(businessId));
    }

    public boolean isActive(UUID businessId) {
        if (isPlatform(businessId)) return true;
        return Boolean.TRUE.equals(repo.hasCovering(businessId, today()));
    }

    /** The plan's employee limit: adding (or re-enabling) one more employee must stay within it. */
    public void checkRoomForOneMore() {
        UUID b = TenantContext.current();
        if (b == null) return;
        Current c = current(b);
        if (c.maxEmployees() != null && c.employees() + 1 > c.maxEmployees()) {
            throw new UserException("Your plan (" + c.planName() + ") allows up to " + c.maxEmployees() + " employees. Contact JurisOne to upgrade.");
        }
    }

    public List<Subscription> history(UUID businessId) {
        return repo.findHistory(businessId);
    }

    // ───────────────────────── plans (super admin, system mode) ─────────────────────────

    public List<Plan> plans() {
        return repo.findPlans(today());
    }

    public record PlanInput(String name, String description, Double price, Integer periodMonths, Integer maxEmployees, List<String> modules, Boolean active) {}

    record ValidPlan(String name, String description, long pricePaise, int periodMonths, Integer maxEmployees, String[] modules, boolean active) {}

    static ValidPlan validate(PlanInput in) {
        String name = Check.trimmed(in.name(), 2, "Give the plan a name.", 60, "Keep the name under 60 characters.");
        String desc = in.description() == null ? null : in.description().trim();
        if (desc != null && desc.length() > 300) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the description under 300 characters.");
        if (in.price() == null || in.price() < 0 || in.price() > 100_000_000) throw new UserException(HttpStatus.BAD_REQUEST, "Enter a price in rupees (0 or more).");
        if (in.price() * 100 != Math.rint(in.price() * 100)) throw new UserException(HttpStatus.BAD_REQUEST, "Price can have at most 2 decimals.");
        if (in.periodMonths() == null || in.periodMonths() < 1 || in.periodMonths() > 60) throw new UserException(HttpStatus.BAD_REQUEST, "The period is 1 to 60 months.");
        if (in.maxEmployees() != null && (in.maxEmployees() < 1 || in.maxEmployees() > 100_000)) {
            throw new UserException(HttpStatus.BAD_REQUEST, "The employee limit is 1 to 100,000 (or empty for no limit).");
        }
        List<String> mods = in.modules() == null ? List.of() : in.modules();
        for (String m : mods) if (!MODULES.contains(m)) throw new UserException(HttpStatus.BAD_REQUEST, "Unknown module " + m + ".");
        return new ValidPlan(name, desc == null || desc.isEmpty() ? null : desc, Math.round(in.price() * 100), in.periodMonths(), in.maxEmployees(),
                mods.stream().distinct().toArray(String[]::new), in.active() == null || in.active());
    }

    public String createPlan(PlanInput in) {
        ValidPlan p = validate(in);
        UUID id = UUID.randomUUID();
        repo.insertPlan(id, p.name(), p.description(), p.pricePaise(), p.periodMonths(), p.maxEmployees(), p.modules(), p.active());
        return id.toString();
    }

    public void updatePlan(String idRaw, PlanInput in) {
        UUID id = Check.uuid(idRaw);
        ValidPlan p = validate(in);
        int n = repo.updatePlan(id, p.name(), p.description(), p.pricePaise(), p.periodMonths(), p.maxEmployees(), p.modules(), p.active());
        if (n == 0) throw new UserException(HttpStatus.NOT_FOUND, "Plan not found.");
    }

    // ───────────────────────── subscriptions (super admin, system mode) ─────────────────────────

    public record SubscriptionInput(String planId, String startsOn, String endsOn, Double price, String notes) {}

    /** Assigns / renews a plan for a business. End date defaults to the plan's period; price defaults to the plan's price. */
    public String addSubscription(UUID businessId, SubscriptionInput in, UUID by) {
        if (isPlatform(businessId)) throw new UserException(HttpStatus.NOT_FOUND, "Business not found.");
        UUID planId = Check.uuid(in.planId(), "Choose a plan.");
        List<Map<String, Object>> plan = repo.findPlanTerms(planId);
        if (plan.isEmpty()) throw new UserException("Plan not found.");
        if (!Boolean.TRUE.equals(plan.get(0).get("active"))) throw new UserException("That plan is no longer offered.");
        LocalDate starts = LocalDate.parse(Check.isoDate(in.startsOn() == null || in.startsOn().isBlank() ? today().toString() : in.startsOn(), "Pick a start date."));
        int months = ((Number) plan.get(0).get("period_months")).intValue();
        LocalDate ends = in.endsOn() == null || in.endsOn().isBlank() ? starts.plusMonths(months).minusDays(1)
                : LocalDate.parse(Check.isoDate(in.endsOn(), "Pick an end date."));
        if (ends.isBefore(starts)) throw new UserException("The end date can't be before the start date.");
        long price = in.price() == null ? ((Number) plan.get(0).get("price_paise")).longValue() : Math.round(in.price() * 100);
        if (price < 0) throw new UserException(HttpStatus.BAD_REQUEST, "The price can't be negative.");
        String notes = in.notes() == null ? null : in.notes().trim();
        if (notes != null && notes.length() > 500) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the notes under 500 characters.");
        UUID id = UUID.randomUUID();
        repo.insertSubscription(id, businessId, planId, starts, ends, price, notes == null || notes.isEmpty() ? null : notes, by);
        return id.toString();
    }

    public record SubscriptionUpdate(String startsOn, String endsOn, String status, String notes) {}

    /** Changes dates / notes, or cancels a subscription (cancelled ones no longer give access). */
    public UUID updateSubscription(String idRaw, SubscriptionUpdate in) {
        UUID id = Check.uuid(idRaw);
        List<Map<String, Object>> rows = repo.findSubscription(id);
        if (rows.isEmpty()) throw new UserException(HttpStatus.NOT_FOUND, "Subscription not found.");
        Map<String, Object> r = rows.get(0);
        LocalDate starts = in.startsOn() == null ? ((Date) r.get("starts_on")).toLocalDate() : LocalDate.parse(Check.isoDate(in.startsOn(), "Pick a start date."));
        LocalDate ends = in.endsOn() == null ? ((Date) r.get("ends_on")).toLocalDate() : LocalDate.parse(Check.isoDate(in.endsOn(), "Pick an end date."));
        if (ends.isBefore(starts)) throw new UserException("The end date can't be before the start date.");
        String status = in.status() == null ? (String) r.get("status") : Check.oneOf(in.status(), List.of("ACTIVE", "CANCELLED"));
        String notes = in.notes() == null ? (String) r.get("notes") : in.notes().trim();
        if (notes != null && notes.length() > 500) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the notes under 500 characters.");
        repo.updateSubscription(id, starts, ends, status, notes == null || notes.isEmpty() ? null : notes);
        return (UUID) r.get("business_id");
    }

    /** Owners of the current business who should hear about the subscription (portal access on). */
    public List<Map<String, Object>> owners() {
        return new ArrayList<>(repo.findOwners());
    }
}
