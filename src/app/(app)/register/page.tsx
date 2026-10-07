import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { ClearFilters, Pager, Pending, SearchBox, SegFilter, SelectFilter, SortTh } from "@/components/table/Table";
import { requirePage } from "@/lib/auth";
import { apiData, qs } from "@/lib/backend";
import { avc, fmtD, fmtH, initials, ymLabel } from "@/lib/format";
import { DayNav, ZonePicker } from "./RegisterControls";

export const metadata: Metadata = { title: "Attendance register" };

interface Clock { text: string; local: string | null }
interface DailyRow { id: string; code: string; name: string; department: string; region: "IN" | "US"; loc: string; shiftName: string; st: string; late: boolean; inn: Clock | null; out: Clock | null; h: number }
interface GridRow { id: string; code: string; name: string; department: string; region: "IN" | "US"; cells: { c: string; cls: string }[]; days: { date: string; label: string; workedMinutes: number }[]; P: number; A: number; L: number }
interface Reg {
  view: "day" | "month"; day: string; ym: string; tz: string; today: string; page: number; take: number; total: number;
  depts: { id: string; name: string }[]; zones: { v: string; l: string }[]; statuses: string[];
  rows: DailyRow[] & GridRow[]; sum?: { t: number; p: number; n: number; l: number; a: number };
  days?: { k: string; d: number; dow: string; wk: boolean }[]; months?: string[];
}

const CHIP: Record<string, string> = { Present: "ok", Working: "ok", "Half day": "warn", Absent: "bad", "On leave": "info", Holiday: "gold", "Not checked in": "warn" };

export default async function RegisterPage(props: PageProps<"/register">) {
  await requirePage("register");
  const sp = await props.searchParams;
  const p = await apiData<Reg>(`/api/register${qs(sp)}`);
  const depts = p.depts;
  const exportQs = new URLSearchParams(Object.entries(sp).flatMap(([k, v]) => (typeof v === "string" && k !== "page" ? [[k, v]] : []))).toString();
  const deptOptions = [{ v: "All", l: "All" }, ...depts.map((d) => ({ v: d.id, l: d.name }))];
  const teamOptions = [{ v: "All", l: "All" }, { v: "IN", l: "India" }, { v: "US", l: "US" }];

  return (
    <div className="col">
      <PageHeader eyebrow="People operations · Attendance" title="Attendance" em="register" sub="Everyone's check-ins across India and US teams — shown in the time zone you pick.">
        <a className="btn" href={`/api/attendance/register${exportQs ? `?${exportQs}` : ""}`} download>Export CSV</a>
        <SegFilter name="view" dflt="day" label="Register view" options={[{ v: "day", l: "Daily log" }, { v: "month", l: "Monthly grid" }]} />
      </PageHeader>
      {p.view === "day" ? <Daily p={p} deptOptions={deptOptions} teamOptions={teamOptions} /> : <Monthly p={p} deptOptions={deptOptions} teamOptions={teamOptions} months={p.months ?? []} />}
    </div>
  );
}

type Opts = { v: string; l: string }[];

function Daily({ p, deptOptions, teamOptions }: { p: Reg; deptOptions: Opts; teamOptions: Opts }) {
  // the API filters, sorts and returns this page of rows (50 per page)
  const page: DailyRow[] = p.rows;
  const sum = p.sum!;
  return (
    <>
      <div className="kpis">
        <div className="card kpi enter" style={{ ["--i" as string]: 1 }}><span className="spot"></span><span className="l"><i className="dot" style={{ color: "var(--ok)" }}></i> Present</span><span className="v"><span className="num">{sum.p}</span><span className="faint" style={{ fontSize: 16 }}> / {sum.t}</span></span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 2 }}><span className="spot"></span><span className="l"><i className="dot" style={{ color: "var(--warn)" }}></i> Not checked in</span><span className="v num">{sum.n}</span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 3 }}><span className="spot"></span><span className="l"><i className="dot" style={{ color: "var(--info)" }}></i> On leave</span><span className="v num">{sum.l}</span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 4 }}><span className="spot"></span><span className="l"><i className="dot" style={{ color: "var(--bad)" }}></i> Absent</span><span className="v num">{sum.a}</span></div>
      </div>
      <div className="toolbar enter" style={{ ["--i" as string]: 5 }}>
        <DayNav day={p.day} max={p.today} />
        <ZonePicker zones={p.zones} value={p.tz} />
        <SearchBox placeholder="Search employee" label="Search employee" />
        <SelectFilter name="region" label="Team" dflt="All" options={teamOptions} />
        <SelectFilter name="dept" label="Department" dflt="All" options={deptOptions} />
        <SelectFilter name="st" label="Status" dflt="All" options={p.statuses.map((s) => ({ v: s, l: s }))} />
      </div>
      <section className="card enter" style={{ ["--i" as string]: 6 }}>
        <Pending>
          <div className="table-wrap">
            <table className="t">
              <thead>
                <tr>
                  <SortTh k="name" dflt="name">Employee</SortTh>
                  <SortTh k="loc" dflt="name">Team</SortTh>
                  <SortTh k="st" dflt="name">Status</SortTh>
                  <SortTh k="inn" dflt="name">Check-in</SortTh>
                  <SortTh k="out" dflt="name">Check-out</SortTh>
                  <SortTh k="h" dflt="name" right>Worked</SortTh>
                </tr>
              </thead>
              <tbody>
                {page.map((r, i) => (
                  <tr key={r.id} style={{ animationDelay: `${i * 30}ms` }}>
                    <td><div className="person"><span className={`avatar ${avc(initials(r.name))}`}>{initials(r.name)}</span><div><b>{r.name}</b><small>{r.code} · {r.department} · {r.shiftName}</small></div></div></td>
                    <td><span className={`chip nodot ${r.region === "IN" ? "gold" : "info"}`}>{r.loc}</span></td>
                    <td><span className={`chip ${CHIP[r.st] ?? "neutral"}`}>{r.st}</span>{r.late && <span className="chip warn nodot" style={{ marginLeft: 6 }}>Late</span>}</td>
                    <td className="num"><b style={{ fontWeight: 500 }}>{r.inn?.text ?? "—"}</b>{r.inn?.local && <div className="faint" style={{ fontSize: 11.5 }}>{r.inn.local} local</div>}</td>
                    <td className="num"><b style={{ fontWeight: 500 }}>{r.out?.text ?? (r.st === "Working" ? "Working now" : "—")}</b>{r.out?.local && <div className="faint" style={{ fontSize: 11.5 }}>{r.out.local} local</div>}</td>
                    <td className="r num">{r.h ? fmtH(r.h) : "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!p.total && <div className="empty"><b>No one matches</b><span className="muted">Change the filters above.</span><ClearFilters /></div>}
          <Pager page={p.page} take={p.take} total={p.total} />
        </Pending>
      </section>
      <p className="faint enter" style={{ margin: 0, fontSize: 12.5 }}>(+1) / (−1) means the time falls on the next / previous calendar day in the selected zone — e.g. the India night shift seen from New York.</p>
    </>
  );
}

function Monthly({ p, deptOptions, teamOptions, months }: { p: Reg; deptOptions: Opts; teamOptions: Opts; months: string[] }) {
  const page: GridRow[] = p.rows;
  const days = p.days ?? [];
  return (
    <>
      <div className="toolbar enter" style={{ ["--i" as string]: 1 }}>
        <SelectFilter name="ym" label="Month" dflt={p.today.slice(0, 7)} options={months.map((m) => ({ v: m, l: ymLabel(m) }))} />
        <SearchBox placeholder="Search employee" label="Search employee" />
        <SelectFilter name="region" label="Team" dflt="All" options={teamOptions} />
        <SelectFilter name="dept" label="Department" dflt="All" options={deptOptions} />
        <span className="tcount">{p.total} people</span>
      </div>
      <section className="card enter" style={{ ["--i" as string]: 2 }}>
        <Pending>
          <div className="table-wrap">
            <table className="t reg">
              <thead>
                <tr>
                  <SortTh k="name" dflt="name" className="stick">Employee</SortTh>
                  {days.map((x) => <th key={x.k} className={`dcol${x.wk ? " wk" : ""}`}><span>{x.dow}</span><b>{x.d}</b></th>)}
                  <SortTh k="P" dflt="name" right>P</SortTh>
                  <SortTh k="A" dflt="name" right>A</SortTh>
                  <SortTh k="L" dflt="name" right>L</SortTh>
                </tr>
              </thead>
              <tbody>
                {page.map((r) => (
                  <tr key={r.id}>
                    <td className="stick"><b style={{ fontWeight: 600 }}>{r.name}</b><small className="faint" style={{ display: "block", fontSize: 11.5 }}>{r.department} · {r.region === "IN" ? "India" : "US"}</small></td>
                    {r.cells.map((c, j) => <td key={j} className="dcell"><span className={`ac ${c.cls}`} title={`${fmtD(r.days[j].date)} · ${r.days[j].label || "—"}${r.days[j].workedMinutes ? " · " + fmtH(r.days[j].workedMinutes / 60) : ""}`}>{c.c}</span></td>)}
                    <td className="r num" style={{ color: "var(--ok)", fontWeight: 600 }}>{r.P}</td>
                    <td className="r num" style={{ color: r.A ? "var(--bad)" : "var(--faint)" }}>{r.A}</td>
                    <td className="r num" style={{ color: "var(--info)" }}>{r.L}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!p.total && <div className="empty"><b>No one matches</b><span className="muted">Change the filters above.</span><ClearFilters /></div>}
          <Pager page={p.page} take={p.take} total={p.total} />
        </Pending>
      </section>
      <div className="legend enter" style={{ display: "flex", gap: 16, flexWrap: "wrap" }}>
        <span><span className="ac ok">P</span> Present</span><span><span className="ac warn">½</span> Half day</span><span><span className="ac bad">A</span> Absent / LOP</span>
        <span><span className="ac info">L</span> Leave</span><span><span className="ac gold">H</span> Holiday</span><span><span className="ac warn">–</span> Not checked in yet</span><span><span className="ac off"></span> Weekly off</span>
      </div>
    </>
  );
}
