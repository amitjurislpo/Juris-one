/**
 * Attendance engine — pure functions, no database (REQUIREMENTS §5.4).
 *
 * Every date here is a *work date* in the employee's own IANA zone. A night shift belongs to
 * the date it started on: punches are attributed to the shift whose window contains them, where
 * a window opens 4 hours before shift start and lasts 24 hours.
 */
import { TZDate } from "@date-fns/tz";

export type Status = "PRESENT" | "HALF_DAY" | "ABSENT" | "LEAVE" | "HOLIDAY" | "WEEKLY_OFF" | "BEFORE_JOINING" | "AFTER_EXIT" | "PENDING" | "NOT_TRACKED";

export interface ShiftDef {
  startLocal: string; // "19:30"
  endLocal: string; // "04:30"
  graceMinutes: number;
}

export interface Policy {
  fullDayHours: number;
  halfDayHours: number;
  weeklyOffs: number[]; // 0 = Sunday
  absenceJobBufferMinutes: number;
  lateMarksPerHalfDayLop: number;
}

export interface Punch {
  at: Date;
  kind: "IN" | "OUT";
  source?: string;
}

export interface DayInput {
  date: string;
  now: Date;
  tz: string;
  shift: ShiftDef;
  policy: Policy;
  doj: string;
  exitDate: string | null;
  trackingStart: string;
  holiday: string | null;
  /** Approved leave covering this date. */
  leave: { code: string; name: string; part: "FULL" | "FIRST_HALF" | "SECOND_HALF"; /** 1 = paid from balance, 0 = loss of pay */ paidFraction: number } | null;
  punches: Punch[]; // attributed to this work date, any order
  /** Approved regularizations for this date. */
  reg: { absent: boolean; lateWaived: boolean };
}

export interface DayResult {
  date: string;
  status: Status;
  label: string;
  firstIn: Date | null;
  lastOut: Date | null;
  workedMinutes: number;
  /** Open check-in (no check-out yet). */
  openSince: Date | null;
  late: boolean;
  lateWaived: boolean;
  missedCheckout: boolean;
  regularized: boolean;
  lopDays: number;
  leaveDays: number;
  /** Day not finished yet (today, or future). */
  live: boolean;
  future: boolean;
  /** Worked on a holiday / weekly off (comp-off candidate). */
  offDayWork: boolean;
}

// ───────────────────────── time-zone helpers ─────────────────────────

const toMin = (hm: string) => {
  const [h, m] = hm.split(":").map(Number);
  return h * 60 + m;
};

// Building an Intl.DateTimeFormat is expensive; one per zone is reused for every conversion.
const FMT = new Map<string, Intl.DateTimeFormat>();
const fmtFor = (tz: string) => {
  let f = FMT.get(tz);
  if (!f) FMT.set(tz, (f = new Intl.DateTimeFormat("en-CA", { timeZone: tz, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23" })));
  return f;
};

/** Local calendar date + minutes-after-midnight of an instant in `tz`. */
export function localParts(at: Date, tz: string): { date: string; minutes: number; dow: number } {
  const p = Object.fromEntries(fmtFor(tz).formatToParts(at).map((x) => [x.type, x.value]));
  const date = `${p.year}-${p.month}-${p.day}`;
  return { date, minutes: Number(p.hour) * 60 + Number(p.minute), dow: new Date(`${date}T00:00:00Z`).getUTCDay() };
}

export const addDays = (iso: string, n: number) => new Date(Date.parse(`${iso}T00:00:00Z`) + n * 86400_000).toISOString().slice(0, 10);
export const dowOf = (iso: string) => new Date(`${iso}T00:00:00Z`).getUTCDay();

/** The instant of a local wall-clock time on `date` in `tz`. */
export function atLocal(date: string, hm: string, tz: string): Date {
  const [y, m, d] = date.split("-").map(Number);
  const [h, mi] = hm.split(":").map(Number);
  return new Date(new TZDate(y, m - 1, d, h, mi, 0, tz).getTime());
}

export const WINDOW_LEAD_MIN = 240;

/** Which work date a punch belongs to. */
export function workDateOf(at: Date, shift: ShiftDef, tz: string): string {
  const { date, minutes } = localParts(at, tz);
  const anchor = (toMin(shift.startLocal) - WINDOW_LEAD_MIN + 1440) % 1440;
  return minutes < anchor ? addDays(date, -1) : date;
}

/** Shift start, grace deadline and end for a work date (end rolls to the next day for night shifts). */
export function shiftWindow(date: string, shift: ShiftDef, tz: string) {
  const start = atLocal(date, shift.startLocal, tz);
  const crosses = toMin(shift.endLocal) <= toMin(shift.startLocal);
  const end = atLocal(crosses ? addDays(date, 1) : date, shift.endLocal, tz);
  return { start, graceUntil: new Date(start.getTime() + shift.graceMinutes * 60_000), end };
}

/** Sum of check-in → check-out pairs; an open check-in counts up to `liveUntil` if given. */
export function workedMinutes(punches: Punch[], liveUntil?: Date): { minutes: number; openSince: Date | null } {
  const ps = [...punches].sort((a, b) => a.at.getTime() - b.at.getTime());
  let ms = 0;
  let open: Date | null = null;
  for (const p of ps) {
    if (p.kind === "IN") {
      if (!open) open = p.at;
    } else if (open) {
      ms += p.at.getTime() - open.getTime();
      open = null;
    }
  }
  if (open && liveUntil && liveUntil > open) ms += liveUntil.getTime() - open.getTime();
  return { minutes: Math.floor(ms / 60_000), openSince: open };
}

// ───────────────────────── day status ─────────────────────────

/** Leave days vs LOP days for one date of leave (half-day parts count 0.5). */
function leaveSplit(l: NonNullable<DayInput["leave"]>) {
  const unit = l.part === "FULL" ? 1 : 0.5;
  const paid = Math.round(unit * Math.min(1, Math.max(0, l.paidFraction)) * 100) / 100;
  return { leaveDays: paid, lopDays: Math.round((unit - paid) * 100) / 100 };
}

export function computeDay(i: DayInput): DayResult {
  const today = localParts(i.now, i.tz).date;
  const base: DayResult = {
    date: i.date, status: "PENDING", label: "", firstIn: null, lastOut: null, workedMinutes: 0, openSince: null,
    late: false, lateWaived: false, missedCheckout: false, regularized: false, lopDays: 0, leaveDays: 0,
    live: false, future: i.date > today, offDayWork: false,
  };
  if (i.date < i.trackingStart) return { ...base, status: "NOT_TRACKED", label: "Not tracked" };
  if (i.exitDate && i.date > i.exitDate) return { ...base, status: "AFTER_EXIT", label: "After exit" };
  if (i.date < i.doj) return { ...base, status: "BEFORE_JOINING", label: "Before joining" };

  const win = shiftWindow(i.date, i.shift, i.tz);
  const dayOver = i.now.getTime() >= win.end.getTime() + i.policy.absenceJobBufferMinutes * 60_000;
  const sorted = [...i.punches].sort((a, b) => a.at.getTime() - b.at.getTime());
  const firstIn = sorted.find((p) => p.kind === "IN")?.at ?? null;
  const lastOut = [...sorted].reverse().find((p) => p.kind === "OUT")?.at ?? null;
  const w = workedMinutes(sorted, dayOver ? undefined : i.now);
  const lastIsIn = sorted.at(-1)?.kind === "IN";
  const rawLate = !!firstIn && firstIn.getTime() > win.graceUntil.getTime();
  const punchFacts = {
    firstIn, lastOut, workedMinutes: w.minutes, openSince: w.openSince,
    late: rawLate && !i.reg.lateWaived, lateWaived: rawLate && i.reg.lateWaived,
    missedCheckout: lastIsIn && dayOver, live: !dayOver && !base.future,
  };

  // Off days: never leave or LOP; work on them is a comp-off candidate.
  const off = i.holiday ?? (i.policy.weeklyOffs.includes(dowOf(i.date)) ? "Weekly off" : null);
  if (off) {
    return { ...base, ...punchFacts, late: false, lateWaived: false, status: i.holiday ? "HOLIDAY" : "WEEKLY_OFF", label: off, offDayWork: w.minutes >= i.policy.halfDayHours * 60 };
  }
  if (base.future) {
    if (i.leave) return { ...base, status: "LEAVE", label: i.leave.part === "FULL" ? i.leave.name : `${i.leave.name} · half day`, ...leaveSplit(i.leave) };
    return { ...base, status: "PENDING", label: "" };
  }

  const full = i.policy.fullDayHours * 60;
  const half = i.policy.halfDayHours * 60;

  if (i.leave?.part === "FULL") {
    return { ...base, ...punchFacts, late: false, status: "LEAVE", label: i.leave.name, ...leaveSplit(i.leave) };
  }

  // Half-day leave: the other half needs half-day hours.
  if (i.leave) {
    const short = dayOver && w.minutes < half; // the working half wasn't worked
    const regularized = short && i.reg.absent;
    return {
      ...base, ...punchFacts, status: "LEAVE", label: `${i.leave.name} · half day`, regularized,
      leaveDays: leaveSplit(i.leave).leaveDays,
      lopDays: leaveSplit(i.leave).lopDays + (short && !regularized ? 0.5 : 0),
    };
  }

  if (!sorted.length) {
    if (i.reg.absent) return { ...base, status: "PRESENT", label: "Regularized", regularized: true, live: false };
    if (!dayOver) return { ...base, status: "PENDING", label: "Not checked in", live: true };
    return { ...base, status: "ABSENT", label: "Absent · LOP", lopDays: 1 };
  }

  if (!dayOver && (lastIsIn || w.minutes < full)) {
    return { ...base, ...punchFacts, status: "PENDING", label: lastIsIn ? "In progress" : "Checked out" };
  }
  if (i.reg.absent && w.minutes < full) return { ...base, ...punchFacts, status: "PRESENT", label: "Regularized", regularized: true };
  if (w.minutes >= full) return { ...base, ...punchFacts, status: "PRESENT", label: "Present" };
  if (w.minutes >= half) return { ...base, ...punchFacts, status: "HALF_DAY", label: "Half day", lopDays: 0.5 };
  return { ...base, ...punchFacts, status: "ABSENT", label: punchFacts.missedCheckout ? "Missed check-out · LOP" : "Short day · LOP", lopDays: 1 };
}

// ───────────────────────── month summary ─────────────────────────

export interface MonthSummary {
  present: number; // half days count 0.5
  absent: number; // LOP days from attendance (absences + half days + unpaid leave)
  leave: number;
  holidays: number;
  weeklyOffs: number;
  workedMinutes: number;
  lateMarks: number;
  lateLopDays: number;
  lopDays: number; // absent + late-mark LOP
}

export function summarize(days: DayResult[], policy: Policy): MonthSummary {
  const s: MonthSummary = { present: 0, absent: 0, leave: 0, holidays: 0, weeklyOffs: 0, workedMinutes: 0, lateMarks: 0, lateLopDays: 0, lopDays: 0 };
  for (const d of days) {
    if (d.status === "PRESENT") s.present += 1;
    if (d.status === "HALF_DAY") s.present += 0.5;
    if (d.status === "LEAVE" && d.workedMinutes >= policy.halfDayHours * 60) s.present += 0.5;
    if (d.status === "HOLIDAY") s.holidays += 1;
    if (d.status === "WEEKLY_OFF") s.weeklyOffs += 1;
    s.leave += d.leaveDays;
    s.absent += d.lopDays;
    s.workedMinutes += d.workedMinutes;
    if (d.late) s.lateMarks += 1;
  }
  s.lateLopDays = Math.floor(s.lateMarks / policy.lateMarksPerHalfDayLop) * 0.5;
  s.lopDays = s.absent + s.lateLopDays;
  return s;
}
