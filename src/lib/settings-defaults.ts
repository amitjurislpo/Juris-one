/** Policy defaults (REQUIREMENTS §5.4, §4, §9). Overridable per key in the `settings` table. */
export const DEFAULT_SETTINGS = {
  "attendance.policy": {
    fullDayHours: 8,
    halfDayHours: 4,
    graceMinutes: 15,
    lateMarksPerHalfDayLop: 3,
    weeklyOffs: [0, 6] as number[], // Sun, Sat
    absenceJobBufferMinutes: 120,
    lockDayOfNextMonth: 1,
    lockTime: "10:00", // IST
    shiftTargetHours: 9,
    trackingStartDate: "2026-04-01", // attendance go-live; earlier days are "Not tracked"
  },
  "employee.policy": { probationMonths: 6 },
  "auth.policy": {
    maxFailedAttempts: 5,
    lockMinutes: 15,
    ipWindowMinutes: 15,
    ipMaxAttempts: 30,
    inviteTtlHours: 72,
    resetTtlMinutes: 60,
  },
  "payroll.policy": {
    pfRate: 0.12,
    pfWageCeiling: 15000, // rupees
    professionalTax: { state: "Default", monthly: 200 }, // §9.1 — open: state slab
  },
  company: {
    name: "Juris Consultants",
    address: "[Registered office address]",
  },
} as const;
