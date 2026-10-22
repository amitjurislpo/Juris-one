package com.jurislpo.jurisone.engine;

import java.time.Instant;
import java.time.LocalDate;

/** When a month's attendance locks (lockInstant / isMonthLocked). */
public final class AttendanceLock {
    private AttendanceLock() {}

    /** The configured day of the next month at the lock time, IST ({@code Date.UTC(y, m, day)} overflow kept). */
    public static Instant lockInstant(String yearMonth, int lockDayOfNextMonth, String lockTime) {
        int year = Integer.parseInt(yearMonth.substring(0, 4)), month = Integer.parseInt(yearMonth.substring(5, 7));
        LocalDate next = LocalDate.of(year, 1, 1).plusMonths(month).plusDays(lockDayOfNextMonth - 1L);
        return AttendanceEngine.atLocal(next, lockTime, "Asia/Kolkata");
    }

    public static boolean isMonthLocked(String yearMonth, int lockDayOfNextMonth, String lockTime, Instant now) {
        return !now.isBefore(lockInstant(yearMonth, lockDayOfNextMonth, lockTime));
    }
}
