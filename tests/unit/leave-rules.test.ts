import { describe, expect, it } from "vitest";
import { accrualFor, compOffDays, compOffExpiries, lopSplit, paidFractions, requestDays, workingDays, yearEndLapse } from "@/lib/leave/rules";

const HOL = new Map([["2026-10-20", "Dussehra"]]);

describe("working days", () => {
  it("skips weekends and the employee's holidays", () => {
    const r = workingDays("2026-10-16", "2026-10-21", [0, 6], HOL); // Fri → Wed
    expect(r.dates).toEqual(["2026-10-16", "2026-10-19", "2026-10-21"]);
    expect(r.skipped).toEqual([{ date: "2026-10-20", name: "Dussehra" }]);
  });
  it("half day applies only to a single date", () => {
    expect(requestDays(["2026-10-21"], "FIRST_HALF")).toBe(0.5);
    expect(requestDays(["2026-10-21", "2026-10-22"], "FIRST_HALF")).toBe(2);
    expect(requestDays([], "FULL")).toBe(0);
  });
});

describe("loss of pay when the balance is short", () => {
  it("pays what the balance covers, in half-day steps", () => {
    expect(lopSplit(3, 1.77, true, true)).toEqual({ paidDays: 1.5, lopDays: 1.5 });
    expect(lopSplit(2, 5, true, true)).toEqual({ paidDays: 2, lopDays: 0 });
    expect(lopSplit(2, 0, true, true)).toEqual({ paidDays: 0, lopDays: 2 });
  });
  it("leave without pay is all LOP", () => expect(lopSplit(2, 10, false, false)).toEqual({ paidDays: 0, lopDays: 2 }));
  it("shortfall lands on the last days", () => {
    const f = paidFractions(["2026-10-21", "2026-10-22", "2026-10-23"], "FULL", 1.5);
    expect([...f.values()]).toEqual([1, 0.5, 0]);
    expect([...paidFractions(["2026-10-21"], "FIRST_HALF", 0.5).values()]).toEqual([1]);
  });
});

describe("accrual", () => {
  it("full month after joining, pro-rata in the joining month, none before", () => {
    expect(accrualFor("2026-10", "2026-09-08", 1)).toBe(1);
    expect(accrualFor("2026-09", "2026-09-08", 1)).toBe(0.77); // 23 of 30 days — prototype JC-074
    expect(accrualFor("2026-09", "2026-09-08", 1.5)).toBe(1.15);
    expect(accrualFor("2026-08", "2026-09-08", 1)).toBe(0);
  });
});

describe("comp-off", () => {
  it("credits a full or half day by hours worked", () => {
    expect(compOffDays(8 * 60 + 40, 8, 4)).toBe(1);
    expect(compOffDays(5 * 60, 8, 4)).toBe(0.5);
    expect(compOffDays(3 * 60, 8, 4)).toBe(0);
  });
  it("expires the unused part of old credits first-in-first-out", () => {
    const credits = [
      { id: "a", amount: 1, expiresOn: "2026-09-01", expired: 0 },
      { id: "b", amount: 1, expiresOn: "2026-12-01", expired: 0 },
    ];
    expect(compOffExpiries(credits, 0.5, "2026-10-06")).toEqual([{ id: "a", amount: 0.5 }]);
    expect(compOffExpiries(credits, 1, "2026-10-06")).toEqual([]);
    expect(compOffExpiries([{ ...credits[0], expired: 0.5 }, credits[1]], 0.5, "2026-10-06")).toEqual([]);
  });
});

describe("year end", () => {
  it("casual leave lapses, earned leave carries up to 30", () => {
    expect(yearEndLapse(4, 0)).toBe(4);
    expect(yearEndLapse(34.5, 30)).toBe(4.5);
    expect(yearEndLapse(12, 30)).toBe(0);
  });
});
