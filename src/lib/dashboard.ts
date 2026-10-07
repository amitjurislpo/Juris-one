import "server-only";
import { can, type CurrentUser } from "./auth";
import { db } from "./db";
import { todayIn } from "./format";
import { payrollMonths, payrollView } from "./payroll/view";
import { todayStatuses } from "./team-status";

/** The payroll month Finance is working on (first open month) and the latest closed one. */
export async function payrollState() {
  const { dflt } = await payrollMonths(todayIn("Asia/Kolkata"));
  const [run, lastClosed] = await Promise.all([
    db.payrollRun.findUnique({ where: { month: dflt }, select: { status: true, approvedAt: true } }),
    db.payrollRun.findFirst({ where: { status: "CLOSED" }, orderBy: { month: "desc" }, select: { month: true } }),
  ]);
  const status: "Closed" | "Approved" | "In review" = run?.status === "CLOSED" ? "Closed" : run?.approvedAt ? "Approved" : "In review";
  return { month: dflt, status, lastClosed: lastClosed?.month ?? null };
}

/** Pending approval queues, everyone's (Overview) or excluding the viewer's own (Home). */
export async function pendingCounts(excludeId?: string) {
  const notMine = excludeId ? { employeeId: { not: excludeId } } : {};
  const [leave, reg, proofs] = await Promise.all([
    db.leaveRequest.count({ where: { status: "PENDING", ...notMine } }),
    db.regularizationRequest.count({ where: { status: "PENDING", ...notMine } }),
    db.declarationItem.count({ where: { status: "PENDING", ...notMine } }),
  ]);
  return { leave, reg, proofs };
}

/** Company overview for Admin (REQUIREMENTS §5.12). */
export async function overviewData() {
  const [current, depts, pending, pay] = await Promise.all([
    db.employee.findMany({ where: { status: { not: "EXITED" } }, select: { id: true, region: true, portalAccess: true, departmentId: true } }),
    db.department.findMany({ where: { archivedAt: null }, select: { id: true, name: true }, orderBy: { name: "asc" } }),
    pendingCounts(),
    payrollState(),
  ]);
  const statuses = await todayStatuses(current.map((e) => e.id));
  const inNow = [...statuses.values()].filter((s) => s.label.startsWith("In since")).length;

  const { rows } = await payrollView(pay.month);
  const lastDay = new Date(Date.UTC(Number(pay.month.slice(0, 4)), Number(pay.month.slice(5)), 0));
  const noStructure = pay.status === "Closed" ? 0 : await db.employee.count({
    where: { region: "IN", status: { not: "EXITED" }, dateOfJoining: { lte: lastDay }, salaryStructures: { none: { effectiveFrom: { lte: lastDay } } } },
  });

  const byDept = depts
    .map((d) => ({ name: d.name, n: current.filter((e) => e.departmentId === d.id).length }))
    .filter((d) => d.n > 0)
    .sort((a, b) => b.n - a.n || a.name.localeCompare(b.name));
  const unassigned = current.filter((e) => !e.departmentId).length;
  if (unassigned) byDept.push({ name: "No department", n: unassigned });

  return {
    head: current.length,
    india: current.filter((e) => e.region === "IN").length,
    us: current.filter((e) => e.region === "US").length,
    accessOff: current.filter((e) => !e.portalAccess).length,
    deptCount: depts.length,
    inNow,
    pending,
    payroll: { ...pay, net: rows.reduce((a, r) => a + r.net, 0), people: rows.length, noStructure },
    byDept,
  };
}

/** Permission-driven cards on Home (REQUIREMENTS §5.12). Salary is never shown here. */
export async function homeCards(u: CurrentUser) {
  const finance = can(u, "pay.view");
  // Finance approves regularizations too, but sees them on its payroll card instead
  const approver = can(u, "appr.leave") || (can(u, "appr.reg") && !finance);
  const showMyLeave = !finance && !can(u, "appr.reg") && !u.hasReports;

  const [myLeaves, team, pending, pay, slip] = await Promise.all([
    showMyLeave
      ? db.leaveRequest.findMany({ where: { employeeId: u.id }, orderBy: { appliedAt: "desc" }, take: 3, include: { type: { select: { name: true } } } })
      : [],
    u.hasReports
      ? db.employee.findMany({ where: { reportingManagerId: u.id, status: { not: "EXITED" } }, select: { id: true, fullName: true, designation: true }, orderBy: { fullName: "asc" } })
      : [],
    approver || finance ? pendingCounts(u.id) : null,
    finance || u.region === "IN" ? payrollState() : null,
    u.region === "IN" ? db.payslip.findFirst({ where: { employeeId: u.id, run: { status: "CLOSED" } }, orderBy: { run: { month: "desc" } }, select: { run: { select: { month: true } } } }) : null,
  ]);
  const statuses = team.length ? await todayStatuses(team.map((m) => m.id)) : new Map();

  return {
    myLeaves: showMyLeave ? myLeaves.map((l) => ({ id: l.id, type: l.type.name, from: l.fromDate.toISOString().slice(0, 10), to: l.toDate.toISOString().slice(0, 10), days: Number(l.days), status: l.status })) : null,
    team: u.hasReports ? team.map((m) => ({ id: m.id, name: m.fullName, title: m.designation, st: statuses.get(m.id) ?? { label: "Not tracked", tone: "neutral" as const } })) : null,
    approvals: approver && pending
      ? { leaveToo: can(u, "appr.leave"), count: (can(u, "appr.leave") ? pending.leave : 0) + (can(u, "appr.reg") ? pending.reg : 0) }
      : null,
    payroll: finance && pay && pending ? { month: pay.month, status: pay.status, proofs: can(u, "proofs.verify") ? pending.proofs : null, regs: pending.reg } : null,
    payslip: u.region === "IN" && pay
      ? slip ? { month: slip.run.month, ready: true } : { month: pay.month, ready: false }
      : null,
  };
}
