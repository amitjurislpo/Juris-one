import { describe, expect, it } from "vitest";
import { daysBetween, fmtDL, inr, isoInZone } from "@/lib/format";

describe("formatting", () => {
  it("formats paise as Indian rupees", () => {
    expect(inr(1492600)).toBe("₹14,926");
    expect(inr(BigInt(75000000))).toBe("₹7,50,000");
  });

  it("formats ISO dates without time-zone drift", () => {
    expect(fmtDL("2026-10-06")).toBe("Tue, 6 Oct 2026");
    expect(daysBetween("2026-10-06", "2026-10-20")).toBe(14);
  });

  it("resolves the calendar date in the employee's zone", () => {
    const at = new Date("2026-10-06T20:00:00Z"); // 01:30 IST next day, 16:00 ET
    expect(isoInZone(at, "Asia/Kolkata")).toBe("2026-10-07");
    expect(isoInZone(at, "America/New_York")).toBe("2026-10-06");
  });
});
