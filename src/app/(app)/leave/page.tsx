import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { ClearFilters, Pending, SegFilter, SortTh } from "@/components/table/Table";
import { summarize } from "@/lib/attendance/engine";
import { attendanceFor, attendancePolicy, currentWorkDate, loadAttEmployees } from "@/lib/attendance/service";
import { requirePage } from "@/lib/auth";
import { db } from "@/lib/db";
import { dateToIso, fmtD, fmtDL, isoInZone, isoToDate, MON3 } from "@/lib/format";
import { listState, pick } from "@/lib/list";
import { balances } from "@/lib/leave/service";
import { ApplyButton, CancelLeave } from "./LeaveClient";

export const metadata: Metadata = { title: "Leave & holidays" };

const STATUS = ["All", "PENDING", "APPROVED", "DECLINED", "CANCELLED"] as const;
const SLABEL: Record<string, string> = { PENDING: "Pending", APPROVED: "Approved", DECLINED: "Declined", CANCELLED: "Cancelled" };
const SCHIP: Record<string, string> = { PENDING: "warn", APPROVED: "ok", DECLINED: "bad", CANCELLED: "neutral" };
const PART: Record<string, string> = { FULL: "Full day", FIRST_HALF: "First half", SECOND_HALF: "Second half" };
const SORTS = ["type", "from", "days", "applied", "status"] as const;

function Ring({ value, max, color, i }: { value: number; max: number; color: string; i: number }) {
  const C = 207;
  const frac = Math.min(1, Math.max(0.03, max ? value / max : 0));
  return (
    <div className="ring">
      <svg width="78" height="78" viewBox="0 0 78 78" aria-hidden="true">
        <circle cx="39" cy="39" r="33" stroke="var(--line)" strokeWidth="6" fill="none"></circle>
        <circle cx="39" cy="39" r="33" stroke={color} strokeWidth="6" strokeLinecap="round" fill="none" transform="rotate(-90 39 39)" className="ring-arc" style={{ strokeDasharray: C, strokeDashoffset: C * (1 - frac), animationDelay: `${i * 0.12}s` }}></circle>
      </svg>
      <b>{Number.isInteger(value) ? value : value.toFixed(2)}</b>
    </div>
  );
}

export default async function LeavePage(props: PageProps<"/leave">) {
  const u = await requirePage("leave");
  const sp = await props.searchParams;
  const tab = pick(sp, "tab", ["requests", "holidays"] as const, "requests");
  const st = pick(sp, "st", STATUS, "All");
  const ls = listState(sp, SORTS, "applied", 50, "desc");
  const [emp] = await loadAttEmployees({ id: u.id });
  const policy = await attendancePolicy();
  const today = emp ? currentWorkDate(emp) : isoInZone(new Date(), u.timeZone);
  const year = today.slice(0, 4);

  const [bals, reqs, hols, lopYear] = await Promise.all([
    balances(u.id),
    db.leaveRequest.findMany({ where: { employeeId: u.id, ...(st !== "All" ? { status: st } : {}) }, include: { type: true, decidedBy: { select: { fullName: true } } } }),
    db.holiday.findMany({ where: { region: { in: ["ALL", u.region] }, date: { gte: isoToDate(`${year}-01-01`), lte: isoToDate(`${year}-12-31`) } }, orderBy: { date: "asc" } }),
    emp ? attendanceFor([emp], [`${year}-01-01`, policy.trackingStart, emp.doj].sort().at(-1)!, today).then((m) => summarize(m.get(emp.id)!.filter((x) => !x.future), policy).lopDays) : Promise.resolve(0),
  ]);
  const get = { type: (r: (typeof reqs)[number]) => r.type.name, from: (r: (typeof reqs)[number]) => r.fromDate.getTime(), days: (r: (typeof reqs)[number]) => Number(r.days), applied: (r: (typeof reqs)[number]) => r.appliedAt.getTime(), status: (r: (typeof reqs)[number]) => r.status }[ls.sort];
  reqs.sort((a, b) => { const x = get(a), y = get(b); return (x > y ? 1 : x < y ? -1 : 0) * (ls.dir === "asc" ? 1 : -1); });

  const bal = (c: string) => bals.find((b) => b.code === c);
  const avail = (c: string) => { const b = bal(c); return b ? Math.round((b.balance - b.pending) * 100) / 100 : 0; };
  const rings: [string, number, number, string, string][] = [
    ["Casual leave", avail("CL"), bal("CL")?.annual || 12, "#6E56CF", "Accrues 1 a month"],
    ["Earned leave", avail("EL"), bal("EL")?.annual || 18, "#17834F", "Carry up to 30 · encashable"],
    ["Comp-off", avail("CO"), 5, "#0A6BC2", "For holiday work · 60 days"],
    ["Loss of pay", lopYear, 5, "#CF3A40", `Taken in ${year}`],
  ];
  const holidayMap = Object.fromEntries((await db.holiday.findMany({ where: { region: { in: ["ALL", u.region] }, date: { gte: isoToDate(today) } } })).map((h) => [dateToIso(h.date), h.name]));
  const types = bals.map((b) => ({ code: b.code, name: b.name, paid: b.paid, available: b.needsBalance ? avail(b.code) : null }));

  return (
    <div className="col" style={{ gap: 24 }}>
      <PageHeader eyebrow={`Leave · ${year}`} title="Time away," em="well planned" sub="Apply in three steps — leave type, dates, and an optional reason.">
        {emp && <ApplyButton types={types} today={today} holidays={holidayMap} weeklyOffs={policy.weeklyOffs} autoOpen={sp.apply === "1"} />}
      </PageHeader>
      <div className="kpis">
        {rings.map(([name, v, max, color, note], i) => (
          <div className="card bal lift enter" key={name} style={{ ["--i" as string]: i + 1 }}>
            <span className="spot" aria-hidden="true"></span>
            <Ring value={v} max={max} color={color} i={i} />
            <div><b style={{ fontSize: 15 }}>{name}</b><div className="faint" style={{ fontSize: 12.5, marginTop: 4 }}>{note}</div></div>
          </div>
        ))}
      </div>
      <SegFilter name="tab" dflt="requests" label="Leave section" options={[{ v: "requests", l: "My requests" }, { v: "holidays", l: "Holiday calendar" }]} />
      {tab === "requests" ? (
        <>
          <div className="toolbar enter" style={{ ["--i" as string]: 6 }}>
            <SegFilter name="st" dflt="All" label="Status" options={STATUS.map((s) => ({ v: s, l: s === "All" ? "All" : SLABEL[s] }))} />
            <span className="tcount">{reqs.length} requests</span>
          </div>
          <section className="card enter" style={{ ["--i" as string]: 6 }}>
            <Pending>
              <div className="table-wrap">
                <table className="t">
                  <thead><tr>
                    <SortTh k="type" dflt="applied" dfltDir="desc">Type</SortTh>
                    <SortTh k="from" dflt="applied" dfltDir="desc">Dates</SortTh>
                    <SortTh k="days" dflt="applied" dfltDir="desc">Days</SortTh>
                    <SortTh k="applied" dflt="applied" dfltDir="desc">Applied</SortTh>
                    <th>Reason</th>
                    <SortTh k="status" dflt="applied" dfltDir="desc">Status</SortTh>
                    <th><span className="sr-only">Actions</span></th>
                  </tr></thead>
                  <tbody>
                    {reqs.map((l, i) => {
                      const f = dateToIso(l.fromDate), t = dateToIso(l.toDate);
                      return (
                        <tr key={l.id} style={{ animationDelay: `${i * 50}ms` }}>
                          <td><b>{l.type.name}</b></td>
                          <td>{fmtD(f)}{t !== f ? ` – ${fmtD(t)}` : ""}<div className="faint" style={{ fontSize: 12 }}>{PART[l.part]}{Number(l.lopDays) > 0 && l.leaveType !== "LOP" ? ` · ${Number(l.lopDays)}d unpaid` : ""}</div></td>
                          <td className="num">{Number(l.days)}</td>
                          <td className="muted">{fmtD(isoInZone(l.appliedAt, u.timeZone))}</td>
                          <td className="muted" style={{ maxWidth: 240 }}>{l.reason || "—"}</td>
                          <td><span className={`chip ${SCHIP[l.status]}`}>{SLABEL[l.status]}</span>{l.decidedBy && <div className="faint" style={{ fontSize: 11.5, marginTop: 3 }}>by {l.decidedBy.fullName}</div>}</td>
                          <td className="r">{l.status === "PENDING" && <CancelLeave id={l.id} />}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
              {!reqs.length && <div className="empty"><b>No requests</b><span className="muted">{st === "All" ? "You haven't applied for leave yet." : "Nothing with this status."}</span>{st !== "All" && <ClearFilters />}</div>}
            </Pending>
          </section>
        </>
      ) : (
        <section className="kpis enter" style={{ ["--i" as string]: 6 }}>
          {hols.map((h, i) => {
            const k = dateToIso(h.date);
            const past = k < today;
            return (
              <div className="card pad lift" key={h.id} style={{ opacity: past ? 0.5 : 1, animation: `enter .6s ${i * 50}ms both`, display: "flex", gap: 14, alignItems: "center" }}>
                <span className="spot" aria-hidden="true"></span>
                <span className="hol-date"><b>{h.date.getUTCDate()}</b><small>{MON3[h.date.getUTCMonth()].toUpperCase()}</small></span>
                <div><b>{h.name}</b><div className="faint" style={{ fontSize: 12.5 }}>{fmtDL(k)}{past ? " · passed" : ""}</div></div>
              </div>
            );
          })}
          {!hols.length && <div className="empty"><b>No holidays yet</b><span className="muted">HR hasn&apos;t published this year&apos;s calendar.</span></div>}
        </section>
      )}
    </div>
  );
}
