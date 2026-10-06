import type { Metadata } from "next";
import Link from "next/link";
import { Greeting } from "@/components/home/Greeting";
import { TimeCard } from "@/components/home/TimeCard";
import { Icon } from "@/components/Icon";
import { addDays, atLocal, dowOf, workedMinutes } from "@/lib/attendance/engine";
import { attendanceFor, attendancePolicy, currentWorkDate, fmtTime, loadAttEmployees } from "@/lib/attendance/service";
import { requirePage } from "@/lib/auth";
import { db } from "@/lib/db";
import { daysBetween, DOW, fmtD, fmtDL, fmtH, MON3, MONTHS } from "@/lib/format";
import { upcomingHolidays } from "@/lib/holidays";

export const metadata: Metadata = { title: "Home" };

const tzAbbr = (tz: string) => new Intl.DateTimeFormat("en-US", { timeZone: tz, timeZoneName: "short" }).formatToParts(new Date()).find((p) => p.type === "timeZoneName")?.value.replace("GMT+5:30", "IST") ?? "";
const t12 = (date: string, hm: string, tz: string) => fmtTime(atLocal(date, hm, tz), tz);

export default async function HomePage() {
  const u = await requirePage("home");
  const [emp] = await loadAttEmployees({ id: u.id });
  const now = new Date();
  const policy = await attendancePolicy();

  // Untracked (e.g. a custom role with `self` but no shift row) still gets the greeting.
  if (!emp) return <div className="col"><div className="page-h enter"><Greeting first={u.name.split(" ")[0]} timeZone={u.timeZone} initialIso={now.toISOString()} /></div></div>;

  const wd = currentWorkDate(emp, now);
  const monday = addDays(wd, -((dowOf(wd) + 6) % 7));
  const heatStart = addDays(monday, -15 * 7);
  const monthStart = `${wd.slice(0, 7)}-01`;
  const from = heatStart < monthStart ? heatStart : monthStart;
  const days = (await attendanceFor([emp], from, addDays(monday, 6), now)).get(emp.id)!;
  const byDate = new Map(days.map((d) => [d.date, d]));
  const today = byDate.get(wd)!;

  // check-in card
  const closed = workedMinutes(today.punches).minutes;
  const on = today.punches.length > 0 && [...today.punches].sort((a, b) => a.at.getTime() - b.at.getTime()).at(-1)!.kind === "IN";
  const start = emp.shift.startLocal, end = emp.shift.endLocal;
  const ticks = [0, 1, 2, 3].map((i) => {
    const s = atLocal(wd, start, emp.tz).getTime();
    const e = atLocal(end <= start ? addDays(wd, 1) : wd, end, emp.tz).getTime();
    return fmtTime(new Date(s + ((e - s) * i) / 3), emp.tz);
  });
  const shiftText = `${emp.shift.code === "IN_NIGHT" ? "Night" : "Day"} shift · ${t12(wd, start, emp.tz)} – ${t12(wd, end, emp.tz)} ${tzAbbr(emp.tz)}`;
  const log = [...today.punches].sort((a, b) => a.at.getTime() - b.at.getTime()).map((p) => ({ t: fmtTime(p.at, emp.tz), l: p.kind === "IN" ? "Checked in" : "Checked out", d: p.source === "WEB" ? "Web" : p.source === "MOBILE" ? "Mobile" : "Regularization" }));

  // KPIs
  const month = days.filter((d) => d.date >= monthStart && d.date <= wd);
  const working = month.filter((d) => !["HOLIDAY", "WEEKLY_OFF", "BEFORE_JOINING", "AFTER_EXIT", "NOT_TRACKED"].includes(d.status));
  const present = month.filter((d) => d.status === "PRESENT" || d.status === "HALF_DAY" || (d.date === wd && d.punches.length > 0)).length;
  const lastShifts = days.filter((d) => d.date <= wd && (d.workedMinutes > 0 || d.date === wd) && !["HOLIDAY", "WEEKLY_OFF"].includes(d.status)).slice(-7);
  const finished = lastShifts.filter((d) => d.date !== wd && d.workedMinutes > 0);
  const avgH = finished.length ? finished.reduce((a, d) => a + d.workedMinutes, 0) / finished.length / 60 : 0;
  const sparkVals = finished.map((d) => d.workedMinutes / 60);
  const spark = sparkVals.length > 1 ? (() => {
    const mn = Math.min(...sparkVals) - 0.1, mx = Math.max(...sparkVals) + 0.1;
    return sparkVals.map((h, i) => `${i ? "L" : "M"}${((i / (sparkVals.length - 1)) * 120).toFixed(1)} ${(28 - ((h - mn) / (mx - mn)) * 24).toFixed(1)}`).join(" ");
  })() : "";
  const balances = await db.leaveLedger.groupBy({ by: ["leaveType"], where: { employeeId: u.id, leaveType: { in: ["CL", "EL"] } }, _sum: { delta: true } });
  const leaveAvail = balances.reduce((a, b) => a + Number(b._sum.delta ?? 0), 0);
  const hols = await upcomingHolidays(emp.region, wd, 4);
  const nextIn = hols[0] ? daysBetween(wd, hols[0].date) : null;

  // heatmap: 16 weeks, Monday-first columns
  const heat = Array.from({ length: 112 }, (_, i) => {
    const date = addDays(heatStart, i);
    const d = byDate.get(date);
    if (!d || d.future || date > wd) return { date, cls: "na", title: fmtDL(date) };
    let cls = "";
    if (d.status === "PRESENT" || d.status === "HALF_DAY" || (d.status === "PENDING" && d.punches.length)) {
      const h = d.workedMinutes / 60;
      cls = h < 8.8 ? "l1" : h < 9.0 ? "l2" : h < 9.2 ? "l3" : "l4";
      if (date === wd) cls += " now";
    } else cls = { HOLIDAY: "hol", ABSENT: "abs", LEAVE: "lv", BEFORE_JOINING: "na", AFTER_EXIT: "na", NOT_TRACKED: "na", WEEKLY_OFF: "", PENDING: "" }[d.status] ?? "";
    return { date, cls, title: `${fmtDL(date)} · ${d.workedMinutes ? fmtH(d.workedMinutes / 60) : d.label}` };
  });
  const heatPresent = heat.filter((c) => /l\d/.test(c.cls)).length;

  return (
    <div className="col" style={{ gap: 20 }}>
      <div className="page-h enter">
        <Greeting first={u.name.split(" ")[0]} timeZone={u.timeZone} initialIso={now.toISOString()} />
        <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
          <Link className="btn" href="/attendance"><Icon n="clock" s={15} />My attendance</Link>
          <Link className="btn btn-gold" href="/leave?apply=1"><Icon n="plus" s={15} />Apply leave</Link>
        </div>
      </div>

      <div className="bento">
        <TimeCard on={on} openSince={today.openSince?.toISOString() ?? null} closedMinutes={closed} shiftText={shiftText} ticks={ticks} targetHours={policy.shiftTargetHours} log={log} />

        <div className="span-5" style={{ display: "grid", gridTemplateColumns: "repeat(2, minmax(0, 1fr))", gap: 16 }}>
          <div className="card kpi lift enter" style={{ ["--i" as string]: 2 }}><span className="spot"></span><span className="ico" style={{ background: "var(--okbg)", color: "var(--ok)" }}><Icon n="check" s={16} /></span><span className="l">Present in {MONTHS[Number(wd.slice(5, 7)) - 1]}</span><span className="v"><span className="num">{present}</span><span className="faint" style={{ fontSize: 16, fontWeight: 500 }}> / {working.length}</span></span><span className="s">working days so far</span></div>
          <div className="card kpi lift enter" style={{ ["--i" as string]: 3 }}><span className="spot"></span><span className="ico" style={{ background: "var(--accentsoft)", color: "var(--accent)" }}><Icon n="clock" s={16} /></span><span className="l">Average day</span><span className="v"><span className="num">{avgH.toFixed(1)}</span>h</span>{spark && <svg className="spark" width="100%" height="30" viewBox="0 0 120 30" preserveAspectRatio="none" aria-hidden="true"><path d={spark} fill="none" stroke="var(--accent)" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" vectorEffect="non-scaling-stroke"></path></svg>}</div>
          <div className="card kpi lift enter" style={{ ["--i" as string]: 4 }}><span className="spot"></span><span className="ico" style={{ background: "var(--infobg)", color: "var(--info)" }}><Icon n="cal" s={16} /></span><span className="l">Leave available</span><span className="v num">{leaveAvail.toFixed(leaveAvail % 1 ? 2 : 0)}</span><span className="s">casual + earned days</span></div>
          <div className="card kpi lift enter" style={{ ["--i" as string]: 5 }}><span className="spot"></span><span className="ico" style={{ background: "var(--warnbg)", color: "var(--warn)" }}><Icon n="sparkle" s={16} /></span><span className="l">Next holiday</span><span className="v">{nextIn === null ? "—" : <><span className="num">{nextIn}</span><span className="faint" style={{ fontSize: 16, fontWeight: 500 }}> days</span></>}</span><span className="s">{hols[0]?.name ?? "No upcoming holidays"}</span></div>
        </div>

        <section className="card pad span-8 enter" style={{ ["--i" as string]: 6 }}>
          <span className="spot"></span>
          <div className="card-h">
            <div><h3>Attendance activity</h3><span className="faint" style={{ fontSize: 12.5 }}>Last 16 weeks · {heatPresent} days present</span></div>
            <div className="legend">Less<i style={{ background: "var(--heat0)" }}></i><i style={{ background: "var(--heat1)" }}></i><i style={{ background: "var(--heat2)" }}></i><i style={{ background: "var(--heat3)" }}></i><i style={{ background: "var(--heat4)" }}></i>More</div>
          </div>
          <div className="heat-wrap">
            <div className="heat-days"><span>Mon</span><span></span><span>Wed</span><span></span><span>Fri</span><span></span><span></span></div>
            <div className="heat" role="img" aria-label={`Attendance heatmap, ${heatPresent} days present in the last 16 weeks`}>
              {heat.map((c, i) => <i key={c.date} className={c.cls} title={c.title} style={{ animationDelay: `${i * 4}ms` }}></i>)}
            </div>
          </div>
          <div style={{ display: "flex", gap: 16, flexWrap: "wrap", marginTop: 14 }} className="legend">
            <span><i style={{ display: "inline-block", verticalAlign: "middle", boxShadow: "inset 0 0 0 1.5px var(--heat2)" }}></i> Holiday</span>
            <span><i style={{ display: "inline-block", verticalAlign: "middle", background: "var(--badbg)", boxShadow: "inset 0 0 0 1.5px var(--bad)" }}></i> Loss of pay</span>
            <span><i style={{ display: "inline-block", verticalAlign: "middle", background: "var(--infobg)", boxShadow: "inset 0 0 0 1.5px var(--info)" }}></i> Leave</span>
            <span><i style={{ display: "inline-block", verticalAlign: "middle", boxShadow: "inset 0 0 0 1px var(--line3)" }}></i> Before joining</span>
          </div>
        </section>

        <section className="card pad span-4 enter" style={{ ["--i" as string]: 7 }}>
          <span className="spot"></span>
          <div className="card-h"><h3>Upcoming holidays</h3><Link className="btn btn-sm btn-ghost" href="/attendance">All</Link></div>
          <div className="list">
            {hols.map((h) => (
              <div className="it" key={h.id}>
                <span className="hol-date"><b>{Number(h.date.slice(8))}</b><small>{MON3[Number(h.date.slice(5, 7)) - 1].toUpperCase()}</small></span>
                <div><b style={{ fontSize: 13.5, fontWeight: 600 }}>{h.name}</b><div className="faint" style={{ fontSize: 12.5 }}>{fmtDL(h.date)}</div></div>
              </div>
            ))}
            {!hols.length && <div className="faint" style={{ fontSize: 13 }}>No more holidays this year.</div>}
          </div>
        </section>

        <section className="card pad span-12 enter" style={{ ["--i" as string]: 8 }}>
          <span className="spot"></span>
          <div className="card-h"><h3>Last seven shifts</h3><span className="faint" style={{ fontSize: 12.5 }}>dashed line = {policy.shiftTargetHours}h target</span></div>
          <div className="bars">
            <div className="target" style={{ bottom: 140 }}></div>
            {lastShifts.map((d, i) => {
              const h = d.workedMinutes / 60;
              return (
                <div className="b" key={d.date} title={`${h.toFixed(1)} hours`}>
                  <span className="num" style={{ fontSize: 11.5, color: "var(--muted)" }}>{h.toFixed(1)}</span>
                  <i className={d.date === wd ? "today" : undefined} style={{ height: Math.max(6, (h / policy.shiftTargetHours) * 140), animationDelay: `${0.25 + i * 0.06}s` }}></i>
                </div>
              );
            })}
          </div>
          <div className="bars-x">
            {lastShifts.map((d) => <span key={d.date}><b style={{ color: "var(--text)", fontWeight: 500 }}>{d.date === wd ? "Today" : DOW[dowOf(d.date)]}</b><br />{fmtD(d.date)}</span>)}
          </div>
        </section>
      </div>
    </div>
  );
}
