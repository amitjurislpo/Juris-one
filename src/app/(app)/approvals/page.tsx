import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { SearchBox, SegFilter } from "@/components/table/Table";
import { attendanceFor, fmtTime, loadAttEmployees } from "@/lib/attendance/service";
import { can, requirePage } from "@/lib/auth";
import { db } from "@/lib/db";
import { avc, dateToIso, fmtD, fmtDateTime, fmtH, initials } from "@/lib/format";
import { pick, str } from "@/lib/list";
import { balances } from "@/lib/leave/service";
import { DecideButtons } from "./ApprovalsClient";

export const metadata: Metadata = { title: "Approvals" };

const KIND: Record<string, string> = { ABSENT: "Absent", LATE_CHECKIN: "Late check-in", MISSED_CHECKOUT: "Missed check-out", HOLIDAY_WORK: "Holiday work" };
const PART: Record<string, string> = { FULL: "Full day", FIRST_HALF: "First half", SECOND_HALF: "Second half" };
const daysAgo = (n: number) => new Date(Date.now() - n * 86400_000);

export default async function ApprovalsPage(props: PageProps<"/approvals">) {
  const u = await requirePage("approvals");
  const sp = await props.searchParams;
  const canLeave = can(u, "appr.leave"), canReg = can(u, "appr.reg");
  const tab = pick(sp, "tab", ["leave", "att"] as const, canLeave ? "leave" : "att");
  const q = str(sp, "q").trim();
  const nameFilter = q ? { employee: { fullName: { contains: q, mode: "insensitive" as const } } } : {};

  const [leaves, regs, leaveCount, regCount] = await Promise.all([
    canLeave ? db.leaveRequest.findMany({ where: { status: "PENDING", employeeId: { not: u.id }, ...nameFilter }, include: { type: true, employee: { select: { id: true, fullName: true, code: true, departmentId: true, department: { select: { name: true } } } } }, orderBy: { fromDate: "asc" } }) : [],
    canReg ? db.regularizationRequest.findMany({ where: { status: "PENDING", employeeId: { not: u.id }, ...nameFilter }, include: { employee: { select: { id: true, fullName: true, code: true, timeZone: true, department: { select: { name: true } } } } }, orderBy: { workDate: "asc" } }) : [],
    canLeave ? db.leaveRequest.count({ where: { status: "PENDING", employeeId: { not: u.id } } }) : 0,
    canReg ? db.regularizationRequest.count({ where: { status: "PENDING", employeeId: { not: u.id } } }) : 0,
  ]);

  // Leave cards: balance impact + overlapping requests in the same team (department)
  const leaveCards = await Promise.all(leaves.map(async (l) => {
    const f = dateToIso(l.fromDate), t = dateToIso(l.toDate);
    const b = (await balances(l.employeeId)).find((x) => x.code === l.leaveType)!;
    const paid = Number(l.days) - Number(l.lopDays);
    const available = Math.round((b.balance - b.pending + paid) * 100) / 100; // this request's own hold added back
    const impact = !b.needsBalance ? "Unpaid leave" : available >= Number(l.days) ? `Balance ${available} → ${Math.round((available - Number(l.days)) * 100) / 100}` : `Only ${Math.max(0, available)} available — ${Math.round((Number(l.days) - Math.max(0, Math.floor(available * 2) / 2)) * 100) / 100} day(s) become loss of pay`;
    const overlap = l.employee.departmentId ? await db.leaveRequest.count({ where: { id: { not: l.id }, employeeId: { not: l.employeeId }, status: { in: ["PENDING", "APPROVED"] }, employee: { departmentId: l.employee.departmentId }, fromDate: { lte: l.toDate }, toDate: { gte: l.fromDate } } }) : 0;
    return { id: l.id, name: l.employee.fullName, dept: l.employee.department?.name ?? "—", type: l.type.name, span: f === t ? fmtD(f) : `${fmtD(f)} – ${fmtD(t)}`, days: Number(l.days), part: PART[l.part], reason: l.reason, impact, overlap };
  }));

  // Regularization cards: what actually happened that day
  const regEmps = regs.length ? await loadAttEmployees({ id: { in: [...new Set(regs.map((r) => r.employeeId))] } }) : [];
  const regCards = await Promise.all(regs.map(async (r) => {
    const date = dateToIso(r.workDate);
    const e = regEmps.find((x) => x.id === r.employeeId);
    const d = e ? (await attendanceFor([e], date, date)).get(e.id)![0] : null;
    const tz = r.employee.timeZone;
    const detail = r.kind === "MISSED_CHECKOUT"
      ? `Checked in ${d?.firstIn ? fmtTime(d.firstIn, tz) : "—"} · asks to set check-out to ${r.proposedOut ? fmtTime(r.proposedOut, tz) : "—"}`
      : r.kind === "HOLIDAY_WORK" ? `Worked ${d ? fmtH(d.workedMinutes / 60) : "—"} on ${d?.label || "a day off"} — credit comp-off`
      : r.kind === "LATE_CHECKIN" ? `Checked in ${d?.firstIn ? fmtTime(d.firstIn, tz) : "—"}${r.proposedIn ? ` · says ${fmtTime(r.proposedIn, tz)}` : ""}`
      : `${d?.label || "Absent"}${r.proposedIn ? ` · says worked from ${fmtTime(r.proposedIn, tz)}${r.proposedOut ? ` to ${fmtTime(r.proposedOut, tz)}` : ""}` : ""}`;
    return { id: r.id, name: r.employee.fullName, dept: r.employee.department?.name ?? "—", kind: KIND[r.kind], date: fmtD(date), detail, reason: r.reason };
  }));

  const since = daysAgo(30);
  const [dl, dr] = await Promise.all([
    canLeave ? db.leaveRequest.findMany({ where: { status: { in: ["APPROVED", "DECLINED"] }, decidedAt: { gte: since } }, include: { type: true, employee: { select: { fullName: true } }, decidedBy: { select: { fullName: true } } }, orderBy: { decidedAt: "desc" }, take: 15 }) : [],
    canReg ? db.regularizationRequest.findMany({ where: { status: { in: ["APPROVED", "DECLINED"] }, decidedAt: { gte: since } }, include: { employee: { select: { fullName: true } }, decidedBy: { select: { fullName: true } } }, orderBy: { decidedAt: "desc" }, take: 15 }) : [],
  ]);
  const decided = [
    ...dl.map((l) => ({ id: l.id, at: l.decidedAt!, who: l.employee.fullName, what: `${l.type.name} · ${fmtD(dateToIso(l.fromDate))}${l.toDate.getTime() !== l.fromDate.getTime() ? ` – ${fmtD(dateToIso(l.toDate))}` : ""}`, ok: l.status === "APPROVED", by: l.decidedBy?.fullName ?? "—" })),
    ...dr.map((r) => ({ id: r.id, at: r.decidedAt!, who: r.employee.fullName, what: `${KIND[r.kind]} · ${fmtD(dateToIso(r.workDate))}`, ok: r.status === "APPROVED", by: `${r.decidedBy?.fullName ?? "—"}${r.decidedRole ? ` (${r.decidedRole})` : ""}` })),
  ].filter((x) => tab === "leave" ? dl.some((l) => l.id === x.id) : dr.some((r) => r.id === x.id)).sort((a, b) => b.at.getTime() - a.at.getTime());

  const total = leaveCount + regCount;
  const tabs = [...(canLeave ? [{ v: "leave", l: `Leave · ${leaveCount}` }] : []), ...(canReg ? [{ v: "att", l: `Attendance · ${regCount}` }] : [])];

  return (
    <div className="col" style={{ gap: 24 }}>
      <PageHeader eyebrow="Approvals" title={<><span className="num">{total}</span> request{total === 1 ? "" : "s"}</>} em="awaiting you"
        sub={tab === "att" ? "Regularizations go to HR, Finance and Admin — the first decision counts and updates LOP in payroll." : "Approving leave updates the employee's balance and calendar instantly."}>
        {tabs.length > 1 && <SegFilter name="tab" dflt={canLeave ? "leave" : "att"} label="Queue" options={tabs} />}
      </PageHeader>
      <div className="toolbar enter"><SearchBox placeholder="Search by employee" label="Search requests" /></div>
      <div className="col" style={{ gap: 14 }}>
        {tab === "leave" && leaveCards.map((l) => (
          <article className="card req enter" key={l.id}>
            <span className="spot" aria-hidden="true"></span>
            <span className={`avatar lg ${avc(initials(l.name))}`}>{initials(l.name)}</span>
            <div className="body">
              <div style={{ display: "flex", flexWrap: "wrap", gap: 10, alignItems: "center" }}><b style={{ fontSize: 16 }}>{l.name}</b><span className="chip info">{l.type}</span><span className="faint" style={{ fontSize: 12.5 }}>{l.dept}</span></div>
              <span style={{ fontSize: 14.5 }}>{l.span} · {l.days} day{l.days === 1 ? "" : "s"} <span className="faint">({l.part})</span></span>
              <span className="muted" style={{ fontSize: 13.5 }}>{l.reason ? `“${l.reason}”` : "No reason given"}</span>
              <span className="faint" style={{ fontSize: 12.5 }}>{l.impact}</span>
              {l.overlap > 0 && <span className="chip warn" style={{ alignSelf: "flex-start" }}>{l.overlap} overlapping request{l.overlap > 1 ? "s" : ""} in {l.dept}</span>}
            </div>
            <DecideButtons id={l.id} kind="leave" who={l.name} />
          </article>
        ))}
        {tab === "att" && regCards.map((r) => (
          <article className="card req enter" key={r.id}>
            <span className="spot" aria-hidden="true"></span>
            <span className={`avatar lg ${avc(initials(r.name))}`}>{initials(r.name)}</span>
            <div className="body">
              <div style={{ display: "flex", flexWrap: "wrap", gap: 10, alignItems: "center" }}><b style={{ fontSize: 16 }}>{r.name}</b><span className="chip gold">{r.kind}</span><span className="faint" style={{ fontSize: 12.5 }}>{r.dept} · sent to HR, Finance &amp; Admin</span></div>
              <span style={{ fontSize: 14.5 }}>{r.date} · {r.detail}</span>
              <span className="muted" style={{ fontSize: 13.5 }}>{r.reason ? `“${r.reason}”` : "No reason given"}</span>
            </div>
            <DecideButtons id={r.id} kind="reg" who={r.name} />
          </article>
        ))}
        {((tab === "leave" && !leaveCards.length) || (tab === "att" && !regCards.length)) && <div className="empty enter"><b>All clear</b><span className="muted">{q ? "No pending request matches that name." : "Nothing waiting in this queue."}</span></div>}
      </div>
      {decided.length > 0 && (
        <section className="card pad enter">
          <span className="spot" aria-hidden="true"></span>
          <div className="card-h"><h3>Decided · last 30 days</h3></div>
          <div className="list">
            {decided.map((d) => (
              <div className="it" key={d.id}>
                <span style={{ flex: 1 }}><b>{d.who}</b> <span className="muted">· {d.what}</span><div className="faint" style={{ fontSize: 12 }}>{fmtDateTime(d.at, u.timeZone)}</div></span>
                <span className={`chip ${d.ok ? "ok" : "bad"}`}>{d.ok ? "Approved" : "Declined"} · {d.by}</span>
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}
