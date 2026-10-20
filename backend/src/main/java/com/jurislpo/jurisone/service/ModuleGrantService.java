package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.ModuleGrantRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Modules the super admin grants to a business on their own, independent of its plan: complimentary (free, optionally
 * open-ended) or paid (price and an end date). Revoking keeps the row for the record. Runs in system mode.
 */
@Service
public class ModuleGrantService {

    public static final List<String> SOURCES = List.of("COMPLIMENTARY", "PAID");

    /** {@code state}: ACTIVE (covers today), UPCOMING, ENDED or REVOKED. */
    public record Grant(String id, String module, String source, long pricePaise, String startsOn, String endsOn, String notes, String createdAt,
                        String revokedAt, String state) {}

    public record GrantInput(String module, String source, Double price, String startsOn, String endsOn, String notes) {}

    private final ModuleGrantRepository repo;

    public ModuleGrantService(ModuleGrantRepository repo) {
        this.repo = repo;
    }

    public List<Grant> list(UUID businessId) {
        LocalDate today = SubscriptionService.today();
        return repo.findByBusiness(businessId, today);
    }

    record Valid(String module, String source, long pricePaise, LocalDate starts, LocalDate ends, String notes) {}

    Valid validate(GrantInput in, UUID businessId, UUID editing) {
        if (in == null) throw new UserException(HttpStatus.BAD_REQUEST, "Grant details are missing.");
        String module = in.module() == null || in.module().isBlank() ? "JURIS_TRACK" : Check.oneOf(in.module(), SubscriptionService.MODULES);
        String source = Check.oneOf(in.source(), SOURCES);
        LocalDate starts = LocalDate.parse(Check.isoDate(in.startsOn() == null || in.startsOn().isBlank() ? SubscriptionService.today().toString() : in.startsOn(),
                "Pick a start date."));
        LocalDate ends = in.endsOn() == null || in.endsOn().isBlank() ? null : LocalDate.parse(Check.isoDate(in.endsOn(), "Pick an end date."));
        if (ends != null && ends.isBefore(starts)) throw new UserException("The end date can't be before the start date.");
        long price = 0;
        if ("PAID".equals(source)) {
            if (in.price() == null || in.price() <= 0) throw new UserException(HttpStatus.BAD_REQUEST, "Enter the price paid (more than ₹0).");
            if (in.price() > 100_000_000 || in.price() * 100 != Math.rint(in.price() * 100)) throw new UserException(HttpStatus.BAD_REQUEST, "Enter a valid price in rupees.");
            if (ends == null) throw new UserException(HttpStatus.BAD_REQUEST, "A paid grant needs an end date.");
            price = Math.round(in.price() * 100);
        }
        String notes = in.notes() == null ? null : in.notes().trim();
        if (notes != null && notes.length() > 500) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the notes under 500 characters.");
        Boolean overlaps = repo.overlaps(businessId, module, editing, starts, ends);
        if (Boolean.TRUE.equals(overlaps)) throw new UserException("This business already has a grant for these dates. Change or revoke that one first.");
        return new Valid(module, source, price, starts, ends, notes == null || notes.isEmpty() ? null : notes);
    }

    public String grant(UUID businessId, GrantInput in, UUID by) {
        if (!repo.customerBusinessExists(businessId)) throw new UserException(HttpStatus.NOT_FOUND, "Business not found.");
        Valid v = validate(in, businessId, null);
        UUID id = UUID.randomUUID();
        repo.insert(id, businessId, v.module(), v.source(), v.pricePaise(), v.starts(), v.ends(), v.notes(), by);
        return id.toString();
    }

    /** Changes a grant that hasn't been revoked. Returns its business. */
    public UUID update(String idRaw, GrantInput in) {
        UUID id = Check.uuid(idRaw);
        UUID business = existing(id);
        Valid v = validate(in, business, id);
        repo.update(id, v.module(), v.source(), v.pricePaise(), v.starts(), v.ends(), v.notes());
        return business;
    }

    /** Ends a grant at once (kept for the record). Returns its business. */
    public UUID revoke(String idRaw) {
        UUID id = Check.uuid(idRaw);
        UUID business = existing(id);
        repo.revoke(id);
        return business;
    }

    private UUID existing(UUID id) {
        List<Map<String, Object>> rows = repo.findState(id);
        if (rows.isEmpty()) throw new UserException(HttpStatus.NOT_FOUND, "Grant not found.");
        if (rows.get(0).get("revoked_at") != null) throw new UserException("This grant has already been revoked.");
        return (UUID) rows.get(0).get("business_id");
    }
}
