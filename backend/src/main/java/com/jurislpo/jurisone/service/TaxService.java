package com.jurislpo.jurisone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.engine.PayrollEngine;
import com.jurislpo.jurisone.engine.TaxEngine;
import com.jurislpo.jurisone.engine.TaxEngine.DeductionCaps;
import com.jurislpo.jurisone.engine.TaxEngine.Declared;
import com.jurislpo.jurisone.engine.TaxEngine.Deductions;
import com.jurislpo.jurisone.engine.TaxEngine.Regime;
import com.jurislpo.jurisone.engine.TaxEngine.Slab;
import com.jurislpo.jurisone.engine.TaxEngine.Surcharge;
import com.jurislpo.jurisone.engine.TaxEngine.TaxResult;
import com.jurislpo.jurisone.engine.TaxEngine.TaxYearConfig;
import com.jurislpo.jurisone.repository.TaxRepository;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Tax projection. Annual taxable income = closed payslips this FY + earlier months estimated
 * from the structure then in force + this month + current structure × remaining months + previous employer income
 * − standard deduction − (old regime) deductions. Monthly TDS = (annual tax − TDS already deducted) ÷ remaining months.
 * Amounts in rupees like the TS code, except where noted.
 */
@Service
public class TaxService {

    public enum DeclarationMode { approved, submitted, all }

    public record Config(TaxYearConfig NEW, TaxYearConfig OLD, DeductionCaps caps, Map<String, String> labels) {}

    /** Month figures in paise. */
    public record MonthFigures(long gross, long basic, long hra, long pf) {}

    /** The TS TaxProjection (rupees). */
    public record Projection(String financialYear, String regime, int monthsLeft, double jurisIncome, String jurisLabel, double prevIncome, double prevTds,
                             double jurisTdsSoFar, double income, Deductions deductions, TaxResult current, TaxResult newRegime, TaxResult oldRegime,
                             long monthly, long pfAnnual, boolean regimeOverridden) {}

    private final TaxRepository repo;
    private final ObjectMapper json;

    public TaxService(TaxRepository repo, ObjectMapper json) {
        this.repo = repo;
        this.json = json;
    }

    static double r(long paise) {
        return paise / 100.0;
    }

    public Config taxConfig(String financialYear) {
        List<Map<String, Object>> years = repo.taxYears(financialYear);
        List<Map<String, Object>> sections = repo.taxSections(financialYear);
        if (years.size() < 2) throw new IllegalStateException("Tax tables for FY " + financialYear + " are missing — add them under tax_years.");
        Map<String, TaxYearConfig> cfg = new HashMap<>();
        for (Map<String, Object> year : years) {
            try {
                List<Slab> slabs = new ArrayList<>();
                for (JsonNode s : json.readTree((String) year.get("slabs"))) slabs.add(new Slab(s.get("upto").isNull() ? null : s.get("upto").asDouble(), s.get("rate").asDouble()));
                List<Surcharge> sur = new ArrayList<>();
                for (JsonNode s : json.readTree((String) year.get("surcharge"))) sur.add(new Surcharge(s.get("above").asDouble(), s.get("rate").asDouble()));
                Regime regime = Regime.valueOf((String) year.get("regime"));
                cfg.put(regime.name(), new TaxYearConfig(regime, ((Number) year.get("std_deduction")).doubleValue(), ((Number) year.get("rebate_limit")).doubleValue(),
                        Boolean.TRUE.equals(year.get("marginal_relief")), ((java.math.BigDecimal) year.get("cess_rate")).doubleValue(), slabs, sur));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
        Map<String, Integer> caps = new HashMap<>();
        Map<String, String> labels = new HashMap<>();
        for (Map<String, Object> section : sections) {
            if (section.get("cap") != null) caps.put((String) section.get("key"), ((Number) section.get("cap")).intValue());
            labels.put((String) section.get("key"), (String) section.get("label"));
        }
        DeductionCaps deductionCaps = new DeductionCaps(caps.getOrDefault("80C", 150000), caps.getOrDefault("80CCD1B", 50000), caps.getOrDefault("80D_SELF", 25000),
                caps.getOrDefault("80D_PARENTS", 25000), caps.getOrDefault("80D_PARENTS_SENIOR", 50000), caps.getOrDefault("24B", 200000), caps.getOrDefault("80TTA", 10000));
        return new Config(cfg.get("NEW"), cfg.get("OLD"), deductionCaps, labels);
    }

    public record DeclarationSummary(Declared declared, int pendingCount) {}

    /** The regime the employee chose for the year and the income / TDS from a previous employer, in paise; null = no tax profile. */
    public record ProfileFigures(String regime, long prevIncome, long prevTds) {}

    public ProfileFigures profile(UUID employeeId, String financialYear) {
        List<Map<String, Object>> prof = repo.profileRegime(employeeId, financialYear);
        if (prof.isEmpty()) return null;
        Map<String, Object> p = prof.get(0);
        return new ProfileFigures((String) p.get("regime"), ((Number) p.get("prev_employer_income")).longValue(), ((Number) p.get("prev_employer_tds")).longValue());
    }

    /** Declared amounts (₹). TDS uses "approved" only; "submitted" adds pending (Finance preview); "all" adds drafts (employee comparison). */
    public DeclarationSummary declarations(UUID employeeId, String financialYear, DeclarationMode mode) {
        List<Map<String, Object>> items = repo.declarationItems(employeeId, financialYear);
        List<Map<String, Object>> profile = repo.profileFlags(employeeId, financialYear);
        Declared declared = new Declared();
        declared.metro = !profile.isEmpty() && Boolean.TRUE.equals(profile.get(0).get("metro"));
        declared.parentsSenior = !profile.isEmpty() && Boolean.TRUE.equals(profile.get(0).get("parents_senior"));
        int pending = 0;
        for (Map<String, Object> item : items) {
            String st = (String) item.get("status");
            if ("PENDING".equals(st)) pending++;
            boolean ok = "APPROVED".equals(st) || (mode != DeclarationMode.approved && "PENDING".equals(st)) || (mode == DeclarationMode.all && "DRAFT".equals(st));
            if (!ok) continue;
            String key = (String) item.get("item_key");
            if (Declared.isItem(key)) declared.set(key, r(((Number) item.get("amount")).longValue()));
        }
        return new DeclarationSummary(declared, pending);
    }

    record EarnedFigures(long grossEarned, long tds, long pf, long basic, long hra) {}

    public Projection projectTax(UUID employeeId, String yearMonth, MonthFigures month, long structureGross, long structureBasic, long structureHra, DeclarationMode declaration, String regimeOverride) {
        TaxEngine.FinancialYear f = TaxEngine.financialYearOf(yearMonth);
        String financialYear = f.financialYear();
        String financialYearStartMonth = financialYear.substring(0, 4) + "-04";
        Config cfg = taxConfig(financialYear);
        List<Map<String, Object>> prof = repo.profileRegime(employeeId, financialYear);
        Map<String, Object> profile = prof.isEmpty() ? null : prof.get(0);
        List<Map<String, Object>> prior = repo.closedPayslipSnapshots(employeeId, financialYearStartMonth, yearMonth);
        DeclarationSummary dec = declarations(employeeId, financialYear, declaration == null ? DeclarationMode.approved : declaration);
        Map<String, Object> employee = repo.employmentDates(employeeId);
        List<Map<String, Object>> structures = repo.salaryStructures(employeeId);

        List<EarnedFigures> snaps = new ArrayList<>();
        Set<String> closedMonths = new HashSet<>();
        for (Map<String, Object> row : prior) {
            closedMonths.add((String) row.get("month"));
            try {
                JsonNode s = json.readTree((String) row.get("snapshot"));
                long basic = 0, hra = 0;
                for (JsonNode node : s.get("components")) {
                    if ("Basic".equals(node.get("label").asText()) && basic == 0) basic = node.get("earned").asLong();
                    if ("House rent allowance".equals(node.get("label").asText()) && hra == 0) hra = node.get("earned").asLong();
                }
                snaps.add(new EarnedFigures(s.get("grossEarned").asLong(), s.get("tds").asLong(), s.get("pf").asLong(), basic, hra));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }

        // Earlier FY months without a closed payslip (e.g. before go-live) are estimated from the
        // structure then in force, pro-rata to the days employed.
        LocalDate dateOfJoining = ((Date) employee.get("date_of_joining")).toLocalDate();
        LocalDate exit = employee.get("exit_date") == null ? null : ((Date) employee.get("exit_date")).toLocalDate();
        long eGross = 0, eBasic = 0, eHra = 0, ePf = 0;
        YearMonth start = YearMonth.parse(financialYearStartMonth);
        for (int k = 0; k < f.index(); k++) {
            YearMonth forecastMonth = start.plusMonths(k);
            if (closedMonths.contains(forecastMonth.toString())) continue;
            int D = forecastMonth.lengthOfMonth();
            int elig = PayrollEngine.eligibleDays(forecastMonth, dateOfJoining, exit);
            Map<String, Object> row = null;
            for (int i = structures.size() - 1; i >= 0; i--) {
                if (!((Date) structures.get(i).get("effective_from")).toLocalDate().isAfter(forecastMonth.atEndOfMonth())) {
                    row = structures.get(i);
                    break;
                }
            }
            if (elig == 0 || row == null) continue;
            double fr = (double) elig / D;
            long sb = ((Number) row.get("basic")).longValue(), sh = ((Number) row.get("hra")).longValue(), sl = ((Number) row.get("lta")).longValue(), ss = ((Number) row.get("special")).longValue();
            long basic = Math.round(sb * fr);
            eGross += Math.round((sb + sh + sl + ss) * fr);
            eBasic += basic;
            eHra += Math.round(sh * fr);
            ePf += Math.round(0.12 * Math.min(basic, 1_500_000));
        }

        int after = f.remainingInclCurrent() - 1;
        long grossAnnual = snaps.stream().mapToLong(EarnedFigures::grossEarned).sum() + eGross + month.gross() + structureGross * after;
        long basicAnnual = snaps.stream().mapToLong(EarnedFigures::basic).sum() + eBasic + month.basic() + structureBasic * after;
        long hraAnnual = snaps.stream().mapToLong(EarnedFigures::hra).sum() + eHra + month.hra() + structureHra * after;
        long pfAnnual = snaps.stream().mapToLong(EarnedFigures::pf).sum() + ePf + month.pf() + Math.round(0.12 * Math.min(structureBasic, 1_500_000)) * after;
        long jurisTdsSoFar = snaps.stream().mapToLong(EarnedFigures::tds).sum();

        double prevIncome = profile == null ? 0 : r(((Number) profile.get("prev_employer_income")).longValue());
        double prevTds = profile == null ? 0 : r(((Number) profile.get("prev_employer_tds")).longValue());
        double income = r(grossAnnual) + prevIncome;
        Deductions deductions = TaxEngine.oldRegimeDeductions(r(basicAnnual), r(hraAnnual), r(pfAnnual), dec.declared(), cfg.caps(), cfg.labels());
        TaxResult newRegime = TaxEngine.computeTax(income, 0, cfg.NEW());
        TaxResult oldRegime = TaxEngine.computeTax(income, deductions.total(), cfg.OLD());
        String regime = regimeOverride != null ? regimeOverride : profile != null ? (String) profile.get("regime") : "NEW";
        TaxResult current = "OLD".equals(regime) ? oldRegime : newRegime;
        long monthly = TaxEngine.monthlyTds(current.total(), prevTds + r(jurisTdsSoFar), f.remainingInclCurrent());
        String label = (snaps.isEmpty() ? "" : "closed payslips + ") + (eGross != 0 ? "estimated earlier months + " : "") + "this month + " + after + " month"
                + (after == 1 ? "" : "s") + " projected";
        return new Projection(financialYear, regime, f.remainingInclCurrent(), r(grossAnnual), label, prevIncome, prevTds, r(jurisTdsSoFar), income, deductions, current,
                newRegime, oldRegime, monthly, Math.round(r(pfAnnual)), profile != null && Boolean.TRUE.equals(profile.get("regime_overridden")));
    }
}
