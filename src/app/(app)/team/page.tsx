import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { ClearFilters, Pending, SearchBox, SortTh } from "@/components/table/Table";
import { requirePage } from "@/lib/auth";
import { db } from "@/lib/db";
import { avc, DOW, fmtD, initials, isoToDate, todayIn } from "@/lib/format";
import { listState } from "@/lib/list";
import { shiftLabel } from "@/lib/shifts";
import { todayStatuses } from "@/lib/team-status";

export const metadata: Metadata = { title: "My team" };

const SORTS = ["name", "id", "title", "st"] as const;

/** Anyone with active direct reports. Read-only — approvals stay with HR (REQUIREMENTS §5.13). */
export default async function TeamPage(props: PageProps<"/team">) {
  const u = await requirePage("team");
  const ls = listState(await props.searchParams, SORTS, "name");

  const team = await db.employee.findMany({
    where: { reportingManagerId: u.id, status: { not: "EXITED" } },
    select: { id: true, code: true, fullName: true, designation: true, timeZone: true, region: true, shift: true },
  });
  const status = await todayStatuses(team.map((m) => m.id));

  // Who's away · this week (Mon–Fri) in the manager's zone
  const today = todayIn(u.timeZone);
  const t = isoToDate(today);
  const mon = new Date(t.getTime() - ((t.getUTCDay() + 6) % 7) * 86400_000);
  const days = Array.from({ length: 5 }, (_, i) => new Date(mon.getTime() + i * 86400_000).toISOString().slice(0, 10));
  const [leaves, holidays] = await Promise.all([
    db.leaveRequest.findMany({ where: { employeeId: { in: team.map((m) => m.id) }, status: { in: ["APPROVED", "PENDING"] }, fromDate: { lte: isoToDate(days[4]) }, toDate: { gte: isoToDate(days[0]) } } }),
    db.holiday.findMany({ where: { date: { gte: isoToDate(days[0]), lte: isoToDate(days[4]) } } }),
  ]);

  const q = ls.q.toLowerCase();
  const rows = team
    .map((m) => ({ ...m, st: status.get(m.id)! }))
    .filter((m) => !q || `${m.fullName} ${m.code} ${m.designation}`.toLowerCase().includes(q));
  const get = { name: (m: (typeof rows)[number]) => m.fullName, id: (m: (typeof rows)[number]) => m.code, title: (m: (typeof rows)[number]) => m.designation, st: (m: (typeof rows)[number]) => m.st.label }[ls.sort];
  rows.sort((a, b) => get(a).localeCompare(get(b)) * (ls.dir === "asc" ? 1 : -1));

  const count = (f: (l: string) => boolean) => team.filter((m) => f(status.get(m.id)!.label)).length;
  const away = team.map((m) => ({
    m,
    cells: days.map((d) => {
      if (holidays.some((h) => h.date.toISOString().startsWith(d) && (h.region === "ALL" || h.region === m.region))) return "hol";
      const l = leaves.find((x) => x.employeeId === m.id && x.fromDate.toISOString().slice(0, 10) <= d && x.toDate.toISOString().slice(0, 10) >= d);
      return l ? (l.status === "APPROVED" ? "lv" : "pend") : "";
    }),
  }));

  return (
    <div className="col" style={{ gap: 24 }}>
      <PageHeader eyebrow="My team" title="Your" em="team" after=" today" sub="People who report to you. Approvals are handled by HR." />
      <div className="kpis">
        <div className="card kpi enter" style={{ ["--i" as string]: 1 }}><span className="spot"></span><span className="l">Team size</span><span className="v num">{team.length}</span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 2 }}><span className="spot"></span><span className="l"><i className="dot" style={{ color: "var(--ok)" }}></i> Checked in</span><span className="v num">{count((l) => l.startsWith("In since"))}</span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 3 }}><span className="spot"></span><span className="l"><i className="dot" style={{ color: "var(--info)" }}></i> On leave</span><span className="v num">{count((l) => l === "On leave")}</span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 4 }}><span className="spot"></span><span className="l"><i className="dot" style={{ color: "var(--warn)" }}></i> Not in yet</span><span className="v num">{count((l) => l === "Not in yet")}</span></div>
      </div>
      <div className="toolbar enter" style={{ ["--i" as string]: 5 }}>
        <SearchBox placeholder="Search your team" label="Search team" />
        <span className="tcount">{rows.length} of {team.length}</span>
      </div>
      <section className="card enter" style={{ ["--i" as string]: 5 }}>
        <Pending>
          <div className="table-wrap">
            <table className="t">
              <thead>
                <tr>
                  <SortTh k="name" dflt="name">Member</SortTh>
                  <SortTh k="id" dflt="name">ID</SortTh>
                  <SortTh k="title" dflt="name">Designation</SortTh>
                  <th>Shift</th>
                  <SortTh k="st" dflt="name">Today</SortTh>
                </tr>
              </thead>
              <tbody>
                {rows.map((m, i) => (
                  <tr key={m.id} style={{ animationDelay: `${i * 50}ms` }}>
                    <td><div className="person"><span className={`avatar ${avc(initials(m.fullName))}`}>{initials(m.fullName)}</span><div><b>{m.fullName}</b><small>{m.designation}</small></div></div></td>
                    <td className="muted num">{m.code}</td>
                    <td className="muted">{m.designation}</td>
                    <td className="muted">{shiftLabel(m.shift)}</td>
                    <td><span className={`chip ${m.st.tone}`}>{m.st.label}</span></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!rows.length && <div className="empty"><b>No one matches</b><span className="muted">Try another name.</span><ClearFilters /></div>}
        </Pending>
      </section>
      <section className="card pad enter" style={{ ["--i" as string]: 6 }}>
        <span className="spot"></span>
        <div className="card-h">
          <h3>Who&apos;s away · week of {fmtD(days[0])}</h3>
          <span className="row" style={{ gap: 12, fontSize: 12 }}><span className="chip info">Approved leave</span><span className="chip warn">Pending</span><span className="chip gold">Holiday</span></span>
        </div>
        <div className="table-wrap">
          <table className="t" style={{ minWidth: 640 }}>
            <thead><tr><th>Member</th>{days.map((d) => <th key={d} style={{ textAlign: "center" }}>{DOW[isoToDate(d).getUTCDay()]} {Number(d.slice(8))}</th>)}</tr></thead>
            <tbody>
              {away.map((r) => (
                <tr key={r.m.id}>
                  <td><b>{r.m.fullName}</b></td>
                  {r.cells.map((c, j) => (
                    <td key={j} style={{ textAlign: "center" }}>
                      {c ? <span className={`chip ${{ lv: "info", pend: "warn", hol: "gold" }[c]}`}>{{ lv: "Leave", pend: "Pending", hol: "Holiday" }[c]}</span> : <span className="faint">·</span>}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}
