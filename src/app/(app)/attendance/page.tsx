import type { Metadata } from "next";
import Link from "next/link";
import { Icon } from "@/components/Icon";
import { ComingInPhase, PageHeader } from "@/components/PageHeader";
import { addDays, summarize } from "@/lib/attendance/engine";
import { attendanceFor, attendancePolicy, currentWorkDate, fmtTime, isMonthLocked, loadAttEmployees } from "@/lib/attendance/service";
import { requirePage } from "@/lib/auth";
import { db } from "@/lib/db";
import { dateToIso, fmtDL, isoToDate, MON3, MONTHS } from "@/lib/format";
import { upcomingHolidays } from "@/lib/holidays";
import { str } from "@/lib/list";
import { AttendanceCalendar, type CalDay, type RegView } from "./AttendanceClient";

export const metadata: Metadata = { title: "My attendance" };

const shiftYm = (ym: string, n: number) => new Date(Date.UTC(Number(ym.slice(0, 4)), Number(ym.slice(5, 7)) - 1 + n, 1)).toISOString().slice(0, 7);

export default async function AttendancePage(props: PageProps<"/attendance">) {
  const u = await requirePage("attendance");
  const sp = await props.searchParams;
  const [emp] = await loadAttEmployees({ id: u.id });
  if (!emp) return <div className="col"><PageHeader eyebrow="Attendance" title="My" em="attendance" /><ComingInPhase phase={2} what="Attendance isn't tracked for your account." /></div>;

  const now = new Date();
  const policy = await attendancePolicy();
  const wd = currentWorkDate(emp, now);
  const minYm = [policy.trackingStart, emp.doj].sort().at(-1)!.slice(0, 7);
  let ym = str(sp, "m", wd.slice(0, 7));
  if (!/^\d{4}-\d{2}$/.test(ym) || ym > wd.slice(0, 7)) ym = wd.slice(0, 7);
  if (ym < minYm) ym = minYm;
  const first = `${ym}-01`;
  const last = addDays(`${shiftYm(ym, 1)}-01`, -1);

  const [days, regs, hols] = await Promise.all([
    attendanceFor([emp], first, last, now).then((m) => m.get(emp.id)!),
    db.regularizationRequest.findMany({ where: { employeeId: u.id, workDate: { gte: isoToDate(first), lte: isoToDate(last) } }, include: { decidedBy: { select: { fullName: true } } }, orderBy: { createdAt: "desc" } }),
    upcomingHolidays(emp.region, wd, 6),
  ]);
  const s = summarize(days.filter((d) => !d.future), policy);
  const locked = isMonthLocked(ym, policy, now);

  const cal: CalDay[] = days.map((d) => ({
    date: d.date, status: d.status, label: d.label, late: d.late, lateWaived: d.lateWaived, hours: d.workedMinutes / 60, today: d.date === wd, future: d.future,
    firstIn: d.firstIn ? fmtTime(d.firstIn, emp.tz) : null, lastOut: d.lastOut ? fmtTime(d.lastOut, emp.tz) : null, live: d.live,
    missedCheckout: d.missedCheckout || (d.punches.length > 0 && !d.live && !d.lastOut), offDayWork: d.offDayWork, lopDays: d.lopDays, leaveDays: d.leaveDays, regularized: d.regularized,
  }));
  const regView: RegView[] = regs.map((r) => ({ id: r.id, date: dateToIso(r.workDate), kind: r.kind, status: r.status, by: r.decidedBy?.fullName ?? null, reason: r.reason }));
  const sel = str(sp, "d");
  const initialSel = sel.startsWith(ym) ? sel : wd.startsWith(ym) ? wd : (days.findLast((d) => !d.future)?.date ?? first);
  const prev = shiftYm(ym, -1), next = shiftYm(ym, 1);

  return (
    <div className="col" style={{ gap: 24 }}>
      <PageHeader eyebrow="Attendance" title={MONTHS[Number(ym.slice(5)) - 1]} em={ym.slice(0, 4)} sub="Tap any day to see its check-in, check-out and how it counts.">
        {prev >= minYm ? <Link className="icon-btn" href={`/attendance?m=${prev}`} aria-label="Previous month"><Icon n="chevL" /></Link> : <span className="icon-btn" aria-disabled="true" style={{ opacity: 0.4 }}><Icon n="chevL" /></span>}
        {next <= wd.slice(0, 7) ? <Link className="icon-btn" href={`/attendance?m=${next}`} aria-label="Next month"><Icon n="chevR" /></Link> : <span className="icon-btn" aria-disabled="true" style={{ opacity: 0.4 }}><Icon n="chevR" /></span>}
      </PageHeader>
      <div className="kpis">
        <div className="card kpi enter" style={{ ["--i" as string]: 1 }}><span className="spot" aria-hidden="true"></span><span className="l"><i className="dot" style={{ color: "var(--ok)" }}></i> Present</span><span className="v num">{s.present}</span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 2 }}><span className="spot" aria-hidden="true"></span><span className="l"><i className="dot" style={{ color: "var(--bad)" }}></i> Absent · loss of pay</span><span className="v num">{s.absent}</span>{s.lateMarks > 0 && <span className="s">{s.lateMarks} late mark{s.lateMarks === 1 ? "" : "s"}{s.lateLopDays ? ` · +${s.lateLopDays} day LOP` : ""}</span>}</div>
        <div className="card kpi enter" style={{ ["--i" as string]: 3 }}><span className="spot" aria-hidden="true"></span><span className="l"><i className="dot" style={{ color: "var(--info)" }}></i> On leave</span><span className="v num">{s.leave}</span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 4 }}><span className="spot" aria-hidden="true"></span><span className="l"><i className="dot" style={{ color: "var(--gold)" }}></i> Holidays · weekly off</span><span className="v">{s.holidays} · {s.weeklyOffs}</span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 5 }}><span className="spot" aria-hidden="true"></span><span className="l"><i className="dot" style={{ color: "var(--text)" }}></i> Hours worked</span><span className="v num">{(s.workedMinutes / 60).toFixed(1)}</span></div>
      </div>
      <AttendanceCalendar key={ym} days={cal} regs={regView} initialSel={initialSel} locked={locked} monthLabel={`${MONTHS[Number(ym.slice(5)) - 1]} ${ym.slice(0, 4)}`} />
      <section className="card pad enter" style={{ ["--i" as string]: 8 }}>
        <span className="spot" aria-hidden="true"></span>
        <div className="card-h"><div><h3>Upcoming holidays</h3><span className="faint" style={{ fontSize: 12.5 }}>Official holidays for your team, set by HR</span></div></div>
        <div className="up-hol">
          {hols.map((h, i) => (
            <div className="it" key={h.id} style={{ animationDelay: `${i * 50}ms` }}>
              <span className="hol-date"><b>{Number(h.date.slice(8))}</b><small>{MON3[Number(h.date.slice(5, 7)) - 1].toUpperCase()}</small></span>
              <div style={{ minWidth: 0 }}><b style={{ fontSize: 13.5, fontWeight: 600 }}>{h.name}</b><div className="faint" style={{ fontSize: 12.5 }}>{fmtDL(h.date)}</div></div>
            </div>
          ))}
          {!hols.length && <div className="faint">No more holidays this year.</div>}
        </div>
      </section>
    </div>
  );
}
