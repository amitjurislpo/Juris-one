package com.jurislpo.jurisone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.engine.PayrollEngine;
import com.jurislpo.jurisone.engine.TaxEngine;
import com.jurislpo.jurisone.engine.TaxEngine.Caps;
import com.jurislpo.jurisone.engine.TaxEngine.Declared;
import com.jurislpo.jurisone.engine.TaxEngine.Deductions;
import com.jurislpo.jurisone.engine.TaxEngine.Regime;
import com.jurislpo.jurisone.engine.TaxEngine.Slab;
import com.jurislpo.jurisone.engine.TaxEngine.Surcharge;
import com.jurislpo.jurisone.engine.TaxEngine.TaxResult;
import com.jurislpo.jurisone.engine.TaxEngine.TaxYearConfig;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Tax projection — src/lib/tax/service.ts. Annual taxable income = closed payslips this FY + earlier months estimated
 * from the structure then in force + this month + current structure × remaining months + previous employer income
 * − standard deduction − (old regime) deductions. Monthly TDS = (annual tax − TDS already deducted) ÷ remaining months.
 * Amounts in rupees like the TS code, except where noted.
 */
@Service
public class TaxService {

    public enum DeclMode { approved, submitted, all }

    public record Config(TaxYearConfig NEW, TaxYearConfig OLD, Caps caps, Map<String, String> labels) {}

    /** Month figures in paise. */
    public record MonthFigures(long gross, long basic, long hra, long pf) {}

    /** The TS TaxProjection (rupees). */
    public record Projection(String fy, String regime, int monthsLeft, double jurisIncome, String jurisLabel, double prevIncome, double prevTds,
                             double jurisTdsSoFar, double income, Deductions deductions, TaxResult current, TaxResult newRegime, TaxResult oldRegime,
                             long monthly, long pfAnnual, boolean regimeOverridden) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public TaxService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    static double r(long paise) {
        return paise / 100.0;
    }

    public Config taxConfig(String fy) {
        List<Map<String, Object>> years = jdbc.queryForList("SELECT regime::text AS regime, std_deduction, rebate_limit, marginal_relief, cess_rate, slabs::text AS slabs, surcharge::text AS surcharge FROM tax_years WHERE fy = ?", fy);
        List<Map<String, Object>> sections = jdbc.queryForList("SELECT key, label, cap FROM tax_sections WHERE fy = ?", fy);
        if (years.size() < 2) throw new IllegalStateException("Tax tables for FY " + fy + " are missing — add them under tax_years.");
        Map<String, TaxYearConfig> cfg = new HashMap<>();
        for (Map<String, Object> y : years) {
            try {
                List<Slab> slabs = new ArrayList<>();
                for (JsonNode s : json.readTree((String) y.get("slabs"))) slabs.add(new Slab(s.get("upto").isNull() ? null : s.get("upto").asDouble(), s.get("rate").asDouble()));
                List<Surcharge> sur = new ArrayList<>();
                for (JsonNode s : json.readTree((String) y.get("surcharge"))) sur.add(new Surcharge(s.get("above").asDouble(), s.get("rate").asDouble()));
                Regime regime = Regime.valueOf((String) y.get("regime"));
                cfg.put(regime.name(), new TaxYearConfig(regime, ((Number) y.get("std_deduction")).doubleValue(), ((Number) y.get("rebate_limit")).doubleValue(),
                        Boolean.TRUE.equals(y.get("marginal_relief")), ((java.math.BigDecimal) y.get("cess_rate")).doubleValue(), slabs, sur));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
        Map<String, Integer> caps = new HashMap<>();
        Map<String, String> labels = new HashMap<>();
        for (Map<String, Object> s : sections) {
            if (s.get("cap") != null) caps.put((String) s.get("key"), ((Number) s.get("cap")).intValue());
            labels.put((String) s.get("key"), (String) s.get("label"));
        }
        Caps c = new Caps(caps.getOrDefault("80C", 150000), caps.getOrDefault("80CCD1B", 50000), caps.getOrDefault("80D_SELF", 25000),
                caps.getOrDefault("80D_PARENTS", 25000), caps.getOrDefault("80D_PARENTS_SENIOR", 50000), caps.getOrDefault("24B", 200000), caps.getOrDefault("80TTA", 10000));
        return new Config(cfg.get("NEW"), cfg.get("OLD"), c, labels);
    }

    public record Decl(Declared declared, int pendingCount) {}

    /** Declared amounts (₹). TDS uses "approved" only; "submitted" adds pending (Finance preview); "all" adds drafts (employee comparison). */
    public Decl declarations(UUID employeeId, String fy, DeclMode mode) {
        List<Map<String, Object>> items = jdbc.queryForList("SELECT item_key, amount, status::text AS status FROM declaration_items WHERE employee_id = ? AND fy = ?", employeeId, fy);
        List<Map<String, Object>> profile = jdbc.queryForList("SELECT metro, parents_senior FROM tax_profiles WHERE employee_id = ? AND fy = ?", employeeId, fy);
        Declared d = new Declared();
        d.metro = !profile.isEmpty() && Boolean.TRUE.equals(profile.get(0).get("metro"));
        d.parentsSenior = !profile.isEmpty() && Boolean.TRUE.equals(profile.get(0).get("parents_senior"));
        int pending = 0;
        for (Map<String, Object> it : items) {
            String st = (String) it.get("status");
            if ("PENDING".equals(st)) pending++;
            boolean ok = "APPROVED".equals(st) || (mode != DeclMode.approved && "PENDING".equals(st)) || (mode == DeclMode.all && "DRAFT".equals(st));
            if (!ok) continue;
            String key = (String) it.get("item_key");
            if (Declared.isItem(key)) d.set(key, r(((Number) it.get("amount")).longValue()));
        }
        return new Decl(d, pending);
    }

    record Snap(long grossEarned, long tds, long pf, long basic, long hra) {}

    public Projection projectTax(UUID employeeId, String ym, MonthFigures month, long structureGross, long structureBasic, long structureHra, DeclMode decl, String regimeOverride) {
        TaxEngine.Fy f = TaxEngine.fyOf(ym);
        String fy = f.fy();
        String fyStartYm = fy.substring(0, 4) + "-04";
        Config cfg = taxConfig(fy);
        List<Map<String, Object>> prof = jdbc.queryForList("SELECT regime::text AS regime, regime_overridden, prev_employer_income, prev_employer_tds FROM tax_profiles WHERE employee_id = ? AND fy = ?", employeeId, fy);
        Map<String, Object> profile = prof.isEmpty() ? null : prof.get(0);
        List<Map<String, Object>> prior = jdbc.queryForList("""
            SELECT p.snapshot::text AS snapshot, r.month FROM payslips p JOIN payroll_runs r ON r.id = p.run_id
            WHERE p.employee_id = ? AND r.status = 'CLOSED' AND r.month >= ? AND r.month < ?""", employeeId, fyStartYm, ym);
        Decl dec = declarations(employeeId, fy, decl == null ? DeclMode.approved : decl);
        Map<String, Object> emp = jdbc.queryForMap("SELECT date_of_joining, exit_date FROM employees WHERE id = ?", employeeId);
        List<Map<String, Object>> structures = jdbc.queryForList("SELECT effective_from, basic, hra, lta, special FROM salary_structures WHERE employee_id = ? ORDER BY effective_from ASC", employeeId);

        List<Snap> snaps = new ArrayList<>();
        Set<String> closedMonths = new HashSet<>();
        for (Map<String, Object> p : prior) {
            closedMonths.add((String) p.get("month"));
            try {
                JsonNode s = json.readTree((String) p.get("snapshot"));
                long basic = 0, hra = 0;
                for (JsonNode c : s.get("components")) {
                    if ("Basic".equals(c.get("label").asText()) && basic == 0) basic = c.get("earned").asLong();
                    if ("House rent allowance".equals(c.get("label").asText()) && hra == 0) hra = c.get("earned").asLong();
                }
                snaps.add(new Snap(s.get("grossEarned").asLong(), s.get("tds").asLong(), s.get("pf").asLong(), basic, hra));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }

        // Earlier FY months without a closed payslip (e.g. before go-live) are estimated from the
        // structure then in force, pro-rata to the days employed.
        LocalDate doj = ((Date) emp.get("date_of_joining")).toLocalDate();
        LocalDate exit = emp.get("exit_date") == null ? null : ((Date) emp.get("exit_date")).toLocalDate();
        long eGross = 0, eBasic = 0, eHra = 0, ePf = 0;
        YearMonth start = YearMonth.parse(fyStartYm);
        for (int k = 0; k < f.index(); k++) {
            YearMonth m = start.plusMonths(k);
            if (closedMonths.contains(m.toString())) continue;
            int D = m.lengthOfMonth();
            int elig = PayrollEngine.eligibleDays(m, doj, exit);
            Map<String, Object> st = null;
            for (int i = structures.size() - 1; i >= 0; i--) {
                if (!((Date) structures.get(i).get("effective_from")).toLocalDate().isAfter(m.atEndOfMonth())) {
                    st = structures.get(i);
                    break;
                }
            }
            if (elig == 0 || st == null) continue;
            double fr = (double) elig / D;
            long sb = ((Number) st.get("basic")).longValue(), sh = ((Number) st.get("hra")).longValue(), sl = ((Number) st.get("lta")).longValue(), ss = ((Number) st.get("special")).longValue();
            long basic = Math.round(sb * fr);
            eGross += Math.round((sb + sh + sl + ss) * fr);
            eBasic += basic;
            eHra += Math.round(sh * fr);
            ePf += Math.round(0.12 * Math.min(basic, 1_500_000));
        }

        int after = f.remainingInclCurrent() - 1;
        long grossAnnual = snaps.stream().mapToLong(Snap::grossEarned).sum() + eGross + month.gross() + structureGross * after;
        long basicAnnual = snaps.stream().mapToLong(Snap::basic).sum() + eBasic + month.basic() + structureBasic * after;
        long hraAnnual = snaps.stream().mapToLong(Snap::hra).sum() + eHra + month.hra() + structureHra * after;
        long pfAnnual = snaps.stream().mapToLong(Snap::pf).sum() + ePf + month.pf() + Math.round(0.12 * Math.min(structureBasic, 1_500_000)) * after;
        long jurisTdsSoFar = snaps.stream().mapToLong(Snap::tds).sum();

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
        return new Projection(fy, regime, f.remainingInclCurrent(), r(grossAnnual), label, prevIncome, prevTds, r(jurisTdsSoFar), income, deductions, current,
                newRegime, oldRegime, monthly, Math.round(r(pfAnnual)), profile != null && Boolean.TRUE.equals(profile.get("regime_overridden")));
    }
}
