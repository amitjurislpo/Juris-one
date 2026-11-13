package com.jurislpo.jurisone.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jurislpo.jurisone.engine.AttendanceEngine;
import com.jurislpo.jurisone.engine.LeaveYear;
import com.jurislpo.jurisone.engine.LeaveYear.YearEndAction;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.PoliciesRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The business's leave year and what happens to each leave type's balance when a year ends (REQUIREMENTS §5.6.4).
 * Stored per business in the {@code leave.policy} setting (the leave types themselves are shared by all businesses).
 * Admin and HR change it; every change is audited.
 */
@Service
public class LeavePolicyService {

    static final String IST = "Asia/Kolkata";
    static final double MAX_CARRY_FORWARD_DAYS = 365;

    public record YearInput(Integer startMonth, Integer startDay) {}

    public record YearEndInput(String yearEndAction, Double carryForwardCap) {}

    public record YearView(int startMonth, int startDay, String currentFrom, String currentTo) {}

    /** {@code carryForwardCap}: days carried at most (only with CARRY_CAPPED, else 0). */
    public record TypeView(String code, String name, String yearEndAction, double carryForwardCap) {}

    public record PolicyView(YearView year, List<TypeView> types) {}

    /** What a leave type does at year end: the action and (for CARRY_CAPPED) the cap. */
    public record YearEnd(YearEndAction action, double carryForwardCap) {}

    private final PoliciesRepository repo;
    private final SettingsService settings;
    private final AuditService audit;
    private final ObjectMapper json;

    public LeavePolicyService(PoliciesRepository repo, SettingsService settings, AuditService audit, ObjectMapper json) {
        this.repo = repo;
        this.settings = settings;
        this.audit = audit;
        this.json = json;
    }

    /** Today in India (the zone the jobs run in). */
    static LocalDate today(Instant now) {
        return AttendanceEngine.localParts(now, IST).date();
    }

    /** The leave year that contains {@code date}, by the business's start. */
    public LeaveYear.Span yearOf(LocalDate date) {
        SettingsService.LeavePolicy policy = settings.leave();
        return LeaveYear.of(date, policy.yearStartMonth(), policy.yearStartDay());
    }

    /**
     * What the business chose for the leave type; a type with no choice keeps its own default
     * (a carry-forward cap of 0 = the balance collapses, above 0 = carried up to that cap).
     */
    public static YearEnd yearEndOf(SettingsService.LeavePolicy policy, String code, double defaultCap) {
        SettingsService.YearEndRule rule = policy.yearEnd() == null ? null : policy.yearEnd().get(code);
        if (rule != null && rule.action() != null) {
            YearEndAction action = YearEndAction.valueOf(rule.action());
            return new YearEnd(action, action == YearEndAction.CARRY_CAPPED && rule.carryForwardCap() != null ? rule.carryForwardCap() : 0);
        }
        return defaultCap > 0 ? new YearEnd(YearEndAction.CARRY_CAPPED, defaultCap) : new YearEnd(YearEndAction.COLLAPSE, 0);
    }

    public PolicyView view() {
        SettingsService.LeavePolicy policy = settings.leave();
        LeaveYear.Span current = LeaveYear.of(today(Instant.now()), policy.yearStartMonth(), policy.yearStartDay());
        List<TypeView> types = new ArrayList<>();
        for (PoliciesRepository.YearEndType type : repo.findYearEndTypes()) {
            YearEnd yearEnd = yearEndOf(policy, type.code(), type.carryForwardCap());
            types.add(new TypeView(type.code(), type.name(), yearEnd.action().name(), yearEnd.carryForwardCap()));
        }
        return new PolicyView(new YearView(policy.yearStartMonth(), policy.yearStartDay(), current.start().toString(), current.end().toString()), types);
    }

    @Transactional
    public void updateYear(CurrentUser user, YearInput input, String ipAddress) {
        if (input == null || input.startMonth() == null || input.startDay() == null || !LeaveYear.isValidStart(input.startMonth(), input.startDay())) {
            throw new UserException(HttpStatus.BAD_REQUEST, "Choose a month and a day that exists every year (no 29 February).");
        }
        ObjectNode before = settings.get("leave.policy");
        if (before.path("yearStartMonth").asInt() == input.startMonth() && before.path("yearStartDay").asInt() == input.startDay()) return;
        ObjectNode value = before.deepCopy();
        value.put("yearStartMonth", input.startMonth());
        value.put("yearStartDay", input.startDay());
        value.put("yearStartChangedOn", today(Instant.now()).toString());
        settings.save("leave.policy", value);
        audit.log(user.id(), "leave.year_change", "setting", "leave.policy", before, value, ipAddress);
    }

    @Transactional
    public void updateYearEnd(CurrentUser user, String code, YearEndInput input, String ipAddress) {
        if (code == null || !code.matches("^[A-Za-z0-9_-]{1,10}$")) throw new UserException(HttpStatus.NOT_FOUND, "Leave type not found.");
        PoliciesRepository.YearEndType type = repo.findYearEndTypes().stream().filter(candidate -> candidate.code().equals(code)).findFirst()
                .orElseThrow(() -> new UserException(HttpStatus.NOT_FOUND, "Leave type not found."));
        if (input == null || input.yearEndAction() == null) throw new UserException(HttpStatus.BAD_REQUEST, "Choose what happens to the balance at the end of the leave year.");
        YearEndAction action;
        try {
            action = YearEndAction.valueOf(input.yearEndAction());
        } catch (IllegalArgumentException e) {
            throw new UserException(HttpStatus.BAD_REQUEST, "Choose what happens to the balance at the end of the leave year.");
        }
        Double cap = input.carryForwardCap();
        if (action == YearEndAction.CARRY_CAPPED) {
            if (cap == null || cap.isNaN() || cap <= 0 || cap > MAX_CARRY_FORWARD_DAYS) {
                throw new UserException(HttpStatus.BAD_REQUEST, "Enter how many days can be carried forward (more than 0 and at most 365).");
            }
            cap = Math.round(cap * 100) / 100.0;
        } else if (cap != null && cap != 0) {
            throw new UserException(HttpStatus.BAD_REQUEST, "A carry-forward limit only applies when the balance is carried forward up to a limit.");
        } else {
            cap = null;
        }
        ObjectNode before = settings.get("leave.policy");
        ObjectNode value = before.deepCopy();
        ObjectNode yearEnd = value.path("yearEnd") instanceof ObjectNode existing ? existing : value.putObject("yearEnd");
        ObjectNode rule = yearEnd.putObject(type.code());
        rule.put("action", action.name());
        if (cap != null) rule.put("carryForwardCap", cap);
        settings.save("leave.policy", value);
        Map<String, Object> beforeView = yearEndOfJson(before, type);
        Map<String, Object> afterView = yearEndOfJson(value, type);
        audit.log(user.id(), "leave.year_end_change", "leave_type", type.code(), beforeView, afterView, ipAddress);
    }

    private Map<String, Object> yearEndOfJson(ObjectNode policyJson, PoliciesRepository.YearEndType type) {
        SettingsService.LeavePolicy policy = json.convertValue(policyJson, SettingsService.LeavePolicy.class);
        YearEnd yearEnd = yearEndOf(policy, type.code(), type.carryForwardCap());
        return Map.of("yearEndAction", yearEnd.action().name(), "carryForwardCap", yearEnd.carryForwardCap());
    }
}
