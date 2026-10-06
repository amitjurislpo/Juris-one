import type { NextRequest } from "next/server";
import { handler } from "@/lib/api";
import { audit } from "@/lib/audit";
import { assertPerm, ForbiddenError } from "@/lib/auth";
import { decryptPII } from "@/lib/crypto";
import { csvResponse, toCsv } from "@/lib/csv";
import { db } from "@/lib/db";
import { closedRows, computeRun, runStatus, type PayslipSnapshot } from "@/lib/payroll/service";
import { requestMeta } from "@/lib/request";

const TYPES = ["bank", "ecr", "pt", "tds", "register"] as const;
const rupees = (p: number) => (p / 100).toFixed(2);

interface Line { employeeId: string; code: string; name: string; basicEarned: number; gross: number; pf: number; pt: number; tds: number; net: number; lop: number; paid: number; days: number }

/** Payroll exports (pay.view): bank transfer file, PF ECR, PT and TDS summaries, register. */
export const GET = handler(async (req: NextRequest) => {
  const u = await assertPerm("pay.view");
  const month = req.nextUrl.searchParams.get("m") ?? "";
  const type = req.nextUrl.searchParams.get("type") as (typeof TYPES)[number];
  if (!/^\d{4}-\d{2}$/.test(month) || !TYPES.includes(type)) return new Response("Bad request", { status: 400 });
  if (type === "tds" && !u.perms.has("tax.view")) throw new ForbiddenError();

  const { run } = await runStatus(month);
  let lines: Line[];
  if (run?.status === "CLOSED") {
    lines = (await closedRows(month)).map(({ employeeId, snap: s }: { employeeId: string; snap: PayslipSnapshot }) => ({
      employeeId, code: s.employee.code, name: s.employee.name, basicEarned: s.components.find((c) => c.label === "Basic")?.earned ?? 0,
      gross: s.grossEarned, pf: s.pf, pt: s.pt, tds: s.tds, net: s.net, lop: s.attendance.lopDays, paid: s.attendance.paidDays, days: s.attendance.daysInMonth,
    }));
  } else {
    lines = (await computeRun(month)).map((r) => ({
      employeeId: r.employeeId, code: r.code, name: r.name, basicEarned: r.pay.components[0].earned, gross: r.pay.grossEarned,
      pf: r.pay.pf, pt: r.pay.pt, tds: r.pay.tds, net: r.pay.net, lop: r.pay.lopDays, paid: r.pay.paidDays, days: r.pay.daysInMonth,
    }));
  }
  const emps = await db.employee.findMany({ where: { id: { in: lines.map((l) => l.employeeId) } }, select: { id: true, bankAccountEnc: true, bankIfsc: true, bankName: true, uanEnc: true, panEnc: true } });
  const emp = (id: string) => emps.find((e) => e.id === id)!;
  await audit({ actorId: u.id, action: "payroll.export", entity: "payroll_run", entityId: month, after: { type, rows: lines.length, closed: run?.status === "CLOSED" }, ip: (await requestMeta()).ip });
  const suffix = run?.status === "CLOSED" ? "" : "-draft";

  switch (type) {
    case "bank":
      return csvResponse(`bank-transfer-${month}${suffix}.csv`, toCsv(
        ["Employee ID", "Beneficiary name", "Account number", "IFSC", "Bank", "Amount (INR)", "Narration"],
        lines.filter((l) => l.net > 0).map((l) => { const e = emp(l.employeeId); return [l.code, l.name, decryptPII(e.bankAccountEnc) ?? "", e.bankIfsc ?? "", e.bankName ?? "", rupees(l.net), `Salary ${month}`]; }),
      ));
    case "ecr": {
      // EPF ECR: wages capped at ₹15,000; employer EPS 8.33% of EPS wages, the rest of 12% to EPF.
      const cap = 1_500_000;
      return csvResponse(`pf-ecr-${month}${suffix}.csv`, toCsv(
        ["UAN", "Member name", "Gross wages", "EPF wages", "EPS wages", "EDLI wages", "EPF contribution (EE)", "EPS contribution (ER)", "EPF-EPS difference (ER)", "NCP days", "Refund of advances"],
        lines.map((l) => {
          const w = Math.min(l.basicEarned, cap);
          const eps = Math.round(w * 0.0833);
          return [decryptPII(emp(l.employeeId).uanEnc) ?? "", l.name, Math.round(l.gross / 100), Math.round(w / 100), Math.round(w / 100), Math.round(w / 100), Math.round(l.pf / 100), Math.round(eps / 100), Math.round((l.pf - eps) / 100), l.lop, 0];
        }),
      ));
    }
    case "pt":
      return csvResponse(`professional-tax-${month}${suffix}.csv`, toCsv(["Employee ID", "Name", "Gross earned", "Professional tax"], lines.map((l) => [l.code, l.name, rupees(l.gross), rupees(l.pt)])));
    case "tds":
      return csvResponse(`tds-${month}${suffix}.csv`, toCsv(["Employee ID", "Name", "PAN", "Gross earned", "TDS deducted"], lines.map((l) => [l.code, l.name, decryptPII(emp(l.employeeId).panEnc) ?? "", rupees(l.gross), rupees(l.tds)])));
    default:
      return csvResponse(`payroll-register-${month}${suffix}.csv`, toCsv(
        ["Employee ID", "Name", "Days in month", "Paid days", "LOP days", "Gross earned", "PF", "PT", "TDS", "Net pay"],
        lines.map((l) => [l.code, l.name, l.days, l.paid, l.lop, rupees(l.gross), rupees(l.pf), rupees(l.pt), rupees(l.tds), rupees(l.net)]),
      ));
  }
});
