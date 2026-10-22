package com.jurislpo.jurisone.engine;

import static com.jurislpo.jurisone.engine.Num.round2;

import com.jurislpo.jurisone.engine.AttendanceEngine.LeavePart;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Leave maths — pure functions (REQUIREMENTS §5.6). Dates are in the employee's zone. */
public final class LeaveRules {

    private LeaveRules() {}

    public record Skipped(LocalDate date, String name) {}

    public record WorkingDays(List<LocalDate> dates, List<Skipped> skipped) {}

    /** Working days in [from, to]: weekly offs and the employee's holidays are skipped (never counted as leave). */
    public static WorkingDays workingDays(LocalDate from, LocalDate to, Set<Integer> weeklyOffs, Map<LocalDate, String> holidays) {
        List<LocalDate> dates = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (weeklyOffs.contains(AttendanceEngine.dowOf(date))) continue;
            String holidayName = holidays.get(date);
            if (holidayName != null && !holidayName.isEmpty()) {
                skipped.add(new Skipped(date, holidayName));
                continue;
            }
            dates.add(date);
        }
        return new WorkingDays(dates, skipped);
    }

    /** Days a request consumes: a half-day part only applies to a single-day request. */
    public static double requestDays(List<LocalDate> dates, LeavePart part) {
        if (dates.isEmpty()) return 0;
        return dates.size() == 1 && part != LeavePart.FULL ? 0.5 : dates.size();
    }

    public record LopSplit(double paidDays, double lopDays) {}

    /** How much is paid from the balance and how much becomes loss of pay. */
    public static LopSplit lopSplit(double days, double available, boolean needsBalance, boolean paid) {
        if (!paid) return new LopSplit(0, days);
        if (!needsBalance) return new LopSplit(days, 0);
        double paidDays = Math.max(0, Math.min(days, Math.floor(Math.max(0, available) * 2) / 2));
        return new LopSplit(paidDays, round2(days - paidDays));
    }

    /**
     * Paid fraction of each date (1 = fully paid, 0 = LOP). Paid days are used up from the start
     * of the range, so any shortfall falls on the last days. Iteration order = date order.
     */
    public static Map<LocalDate, Double> paidFractions(List<LocalDate> dates, LeavePart part, double paidDays) {
        double unit = dates.size() == 1 && part != LeavePart.FULL ? 0.5 : 1;
        Map<LocalDate, Double> out = new LinkedHashMap<>();
        double left = paidDays;
        for (LocalDate date : dates) {
            double take = Math.min(unit, Math.max(0, left));
            out.put(date, unit != 0 ? take / unit : 0);
            left -= take;
        }
        return out;
    }

    /** Monthly accrual, pro-rata in the joining month (and zero before it). */
    public static double accrualFor(YearMonth yearMonth, LocalDate dateOfJoining, double perMonth) {
        int daysInMonth = yearMonth.lengthOfMonth();
        LocalDate first = yearMonth.atDay(1), last = yearMonth.atEndOfMonth();
        if (dateOfJoining.isAfter(last)) return 0;
        if (!dateOfJoining.isAfter(first)) return perMonth;
        int daysLeft = daysInMonth - dateOfJoining.getDayOfMonth() + 1;
        return round2((perMonth * daysLeft) / daysInMonth);
    }

    /** Comp-off for work on a holiday / weekly off: a full day's hours → 1, half-day hours → 0.5. */
    public static double compOffDays(int workedMinutes, double fullDayHours, double halfDayHours) {
        if (workedMinutes >= fullDayHours * 60) return 1;
        if (workedMinutes >= halfDayHours * 60) return 0.5;
        return 0;
    }

    public record Credit(String id, double amount, LocalDate expiresOn, double expired) {}

    public record Expiry(String id, double amount) {}

    /**
     * First-in-first-out comp-off expiry: usage is taken from the oldest credits; whatever is left
     * of a credit past its expiry date (and not yet written off) expires now.
     */
    public static List<Expiry> compOffExpiries(List<Credit> credits, double used, LocalDate today) {
        double toAllocate = used;
        List<Expiry> out = new ArrayList<>();
        List<Credit> sorted = new ArrayList<>(credits);
        sorted.sort(Comparator.comparing(Credit::expiresOn));
        for (Credit credit : sorted) {
            double usable = credit.amount() - credit.expired();
            double fromThis = Math.min(Math.max(0, toAllocate), usable);
            toAllocate -= fromThis;
            double remaining = round2(usable - fromThis);
            if (credit.expiresOn().isBefore(today) && remaining > 0) out.add(new Expiry(credit.id(), remaining));
        }
        return out;
    }

    /** Year-end: casual leave lapses entirely; earned leave carries forward up to the cap. */
    public static double yearEndLapse(double balance, double carryForwardCap) {
        return round2(Math.max(0, balance - carryForwardCap));
    }
}
