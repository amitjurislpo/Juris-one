import { describe, expect, it } from "vitest";
import { atLocal, computeDay, shiftWindow, summarize, workDateOf, workedMinutes, type DayInput, type Punch } from "@/lib/attendance/engine";

const IST = "Asia/Kolkata";
const NIGHT = { startLocal: "19:30", endLocal: "04:30", graceMinutes: 15 };
const DAY = { startLocal: "09:30", endLocal: "18:30", graceMinutes: 15 };
const US = { startLocal: "09:00", endLocal: "18:00", graceMinutes: 15 };
const POLICY = { fullDayHours: 8, halfDayHours: 4, weeklyOffs: [0, 6], absenceJobBufferMinutes: 120, lateMarksPerHalfDayLop: 3 };

const at = (date: string, hm: string, tz = IST) => atLocal(date, hm, tz);
const p = (date: string, hm: string, kind: "IN" | "OUT", tz = IST): Punch => ({ at: at(date, hm, tz), kind });

function day(over: Partial<DayInput>): DayInput {
  return {
    date: "2026-09-16", now: at("2026-10-06", "12:00"), tz: IST, shift: NIGHT, policy: POLICY, doj: "2025-01-01", exitDate: null,
    trackingStart: "2026-04-01", holiday: null, leave: null, punches: [], reg: { absent: false, lateWaived: false }, ...over,
  };
}

describe("night shift attribution", () => {
  it("assigns a 2 AM check-out to the shift that started the evening before", () => {
    expect(workDateOf(at("2026-09-17", "02:00"), NIGHT, IST)).toBe("2026-09-16");
    expect(workDateOf(at("2026-09-16", "19:25"), NIGHT, IST)).toBe("2026-09-16");
    expect(workDateOf(at("2026-09-16", "15:29"), NIGHT, IST)).toBe("2026-09-15");
  });
  it("day shift: an early 6 AM check-in is the same day", () => {
    expect(workDateOf(at("2026-09-16", "06:00"), DAY, IST)).toBe("2026-09-16");
  });
  it("night window ends the next morning", () => {
    const w = shiftWindow("2026-09-16", NIGHT, IST);
    expect(w.start.toISOString()).toBe("2026-09-16T14:00:00.000Z");
    expect(w.end.toISOString()).toBe("2026-09-16T23:00:00.000Z");
    expect(w.graceUntil.toISOString()).toBe("2026-09-16T14:15:00.000Z");
  });
});

describe("worked time", () => {
  it("sums multiple pairs", () => {
    const ps = [p("2026-09-16", "19:30", "IN"), p("2026-09-16", "23:30", "OUT"), p("2026-09-17", "00:00", "IN"), p("2026-09-17", "04:45", "OUT")];
    expect(workedMinutes(ps).minutes).toBe(4 * 60 + 4 * 60 + 45);
  });
  it("counts an open check-in up to now while live", () => {
    const r = workedMinutes([p("2026-10-06", "09:30", "IN")], at("2026-10-06", "11:00"));
    expect(r.minutes).toBe(90);
    expect(r.openSince).not.toBeNull();
  });
});

describe("day status", () => {
  const full = [p("2026-09-16", "19:31", "IN"), p("2026-09-17", "04:40", "OUT")];
  it("present when 8h+ is worked", () => {
    const d = computeDay(day({ punches: full }));
    expect(d.status).toBe("PRESENT");
    expect(d.late).toBe(false);
    expect(d.lopDays).toBe(0);
  });
  it("late after the 15-minute grace", () => {
    const d = computeDay(day({ punches: [p("2026-09-16", "19:46", "IN"), p("2026-09-17", "04:50", "OUT")] }));
    expect(d.late).toBe(true);
    expect(computeDay(day({ punches: [p("2026-09-16", "19:45", "IN"), p("2026-09-17", "04:50", "OUT")] })).late).toBe(false);
  });
  it("a regularized late mark is waived", () => {
    const d = computeDay(day({ punches: [p("2026-09-16", "20:10", "IN"), p("2026-09-17", "05:00", "OUT")], reg: { absent: false, lateWaived: true } }));
    expect(d.late).toBe(false);
    expect(d.lateWaived).toBe(true);
  });
  it("half day between 4h and 8h; absent below 4h", () => {
    expect(computeDay(day({ punches: [p("2026-09-16", "19:30", "IN"), p("2026-09-17", "01:00", "OUT")] }))).toMatchObject({ status: "HALF_DAY", lopDays: 0.5 });
    expect(computeDay(day({ punches: [p("2026-09-16", "19:30", "IN"), p("2026-09-16", "22:00", "OUT")] }))).toMatchObject({ status: "ABSENT", lopDays: 1 });
  });
  it("no check-in: pending until shift end + buffer, then absent (LOP)", () => {
    // night shift of 6 Oct ends 4:30 AM on 7 Oct; buffer 2h → 6:30 AM
    expect(computeDay(day({ date: "2026-10-06", now: at("2026-10-07", "06:00") })).status).toBe("PENDING");
    expect(computeDay(day({ date: "2026-10-06", now: at("2026-10-07", "06:31") }))).toMatchObject({ status: "ABSENT", lopDays: 1 });
  });
  it("an approved absence regularization makes the day paid", () => {
    expect(computeDay(day({ reg: { absent: true, lateWaived: false } }))).toMatchObject({ status: "PRESENT", lopDays: 0, regularized: true });
  });
  it("missed check-out is flagged", () => {
    const d = computeDay(day({ punches: [p("2026-09-16", "19:30", "IN")] }));
    expect(d.missedCheckout).toBe(true);
    expect(d.status).toBe("ABSENT");
  });
  it("in progress while checked in today", () => {
    const d = computeDay(day({ date: "2026-10-06", shift: DAY, now: at("2026-10-06", "12:00"), punches: [p("2026-10-06", "09:31", "IN")] }));
    expect(d).toMatchObject({ status: "PENDING", label: "In progress", live: true, workedMinutes: 149 });
  });
  it("holidays and weekly offs are never leave or LOP", () => {
    expect(computeDay(day({ date: "2026-10-02", holiday: "Gandhi Jayanti" }))).toMatchObject({ status: "HOLIDAY", lopDays: 0, leaveDays: 0 });
    expect(computeDay(day({ date: "2026-09-19" }))).toMatchObject({ status: "WEEKLY_OFF", lopDays: 0 });
  });
  it("holiday work of 4h+ is a comp-off candidate", () => {
    const d = computeDay(day({ date: "2026-10-02", holiday: "Gandhi Jayanti", punches: [p("2026-10-02", "19:30", "IN"), p("2026-10-03", "04:10", "OUT")] }));
    expect(d.offDayWork).toBe(true);
  });
  it("leave: paid full day, unpaid LOP leave, and half-day leave", () => {
    expect(computeDay(day({ leave: { code: "CL", name: "Casual leave", part: "FULL", paidFraction: 1 } }))).toMatchObject({ status: "LEAVE", leaveDays: 1, lopDays: 0 });
    expect(computeDay(day({ leave: { code: "LOP", name: "Leave without pay", part: "FULL", paidFraction: 0 } }))).toMatchObject({ status: "LEAVE", leaveDays: 0, lopDays: 1 });
    const half = { code: "CL", name: "Casual leave", part: "FIRST_HALF" as const, paidFraction: 1 };
    expect(computeDay(day({ leave: half, punches: [p("2026-09-16", "23:30", "IN"), p("2026-09-17", "04:00", "OUT")] }))).toMatchObject({ leaveDays: 0.5, lopDays: 0 });
    expect(computeDay(day({ leave: half }))).toMatchObject({ leaveDays: 0.5, lopDays: 0.5 });
    // balance ran short: half of the day is paid, half is LOP
    expect(computeDay(day({ leave: { code: "CL", name: "Casual leave", part: "FULL", paidFraction: 0.5 } }))).toMatchObject({ leaveDays: 0.5, lopDays: 0.5 });
  });
  it("before joining, after exit and before go-live", () => {
    expect(computeDay(day({ date: "2026-09-07", doj: "2026-09-08" })).status).toBe("BEFORE_JOINING");
    expect(computeDay(day({ date: "2026-09-01", exitDate: "2026-08-31" })).status).toBe("AFTER_EXIT");
    expect(computeDay(day({ date: "2026-03-31" })).status).toBe("NOT_TRACKED");
  });
  it("future days are not judged", () => {
    expect(computeDay(day({ date: "2026-10-20" }))).toMatchObject({ status: "PENDING", future: true, lopDays: 0 });
  });
});

describe("US time zones", () => {
  it("uses the employee's local shift, including DST", () => {
    const NY = "America/New_York";
    expect(shiftWindow("2026-09-16", US, NY).start.toISOString()).toBe("2026-09-16T13:00:00.000Z"); // EDT
    expect(shiftWindow("2026-12-16", US, NY).start.toISOString()).toBe("2026-12-16T14:00:00.000Z"); // EST
    const d = computeDay(day({ tz: NY, shift: US, punches: [p("2026-09-16", "09:20", "IN", NY), p("2026-09-16", "18:05", "OUT", NY)] }));
    expect(d).toMatchObject({ status: "PRESENT", late: true });
  });
});

describe("month summary", () => {
  it("3 late marks = ½ day LOP", () => {
    const late = computeDay(day({ punches: [p("2026-09-16", "20:00", "IN"), p("2026-09-17", "05:00", "OUT")] }));
    const s = summarize([late, late, late, late], POLICY);
    expect(s.lateMarks).toBe(4);
    expect(s.lateLopDays).toBe(0.5);
    expect(s.lopDays).toBe(0.5);
    expect(s.present).toBe(4);
  });
});
