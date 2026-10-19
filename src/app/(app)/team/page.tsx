import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { ClearFilters, Pending, SearchBox, SortTh } from "@/components/table/Table";
import { getT } from "@/lib/i18n/server";
import { requirePage } from "@/lib/auth";
import { apiData, qs } from "@/lib/backend";
import { avc, DOW, fmtD, initials, isoToDate } from "@/lib/format";

export const metadata: Metadata = { title: "My team" };

type TodayStatus = { label: string; tone: "ok" | "info" | "warn" | "gold" | "neutral" };
type Team = {
  size: number; counts: { in: number; leave: number; notIn: number };
  rows: { id: string; code: string; fullName: string; designation: string; shift: string; st: TodayStatus }[];
  days: string[]; away: { id: string; fullName: string; cells: ("" | "lv" | "pend" | "hol")[] }[];
};

/** Anyone with active direct reports. Read-only — approvals stay with HR (REQUIREMENTS §5.13). */
export default async function TeamPage(props: PageProps<"/team">) {
  await requirePage("team");
  const t = await getT();
  const { size, counts, rows, days, away } = await apiData<Team>(`/api/team${qs(await props.searchParams)}`);

  return (
    <div className="col" style={{ gap: 24 }}>
      <PageHeader eyebrow={t("My team")} title={t("Your")} em={t("team")} after={` ${t("today")}`} sub={t("People who report to you. Approvals are handled by HR.")} />
      <div className="kpis">
        <div className="card kpi enter" style={{ ["--i" as string]: 1 }}><span className="spot"></span><span className="l">{t("Team size")}</span><span className="v num">{size}</span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 2 }}><span className="spot"></span><span className="l"><i className="dot" style={{ color: "var(--ok)" }}></i> {t("Checked in")}</span><span className="v num">{counts.in}</span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 3 }}><span className="spot"></span><span className="l"><i className="dot" style={{ color: "var(--info)" }}></i> {t("On leave")}</span><span className="v num">{counts.leave}</span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 4 }}><span className="spot"></span><span className="l"><i className="dot" style={{ color: "var(--warn)" }}></i> {t("Not in yet")}</span><span className="v num">{counts.notIn}</span></div>
      </div>
      <div className="toolbar enter" style={{ ["--i" as string]: 5 }}>
        <SearchBox placeholder={t("Search your team")} label={t("Search team")} />
        <span className="tcount">{t("{n} of {total}", { n: rows.length, total: size })}</span>
      </div>
      <section className="card enter" style={{ ["--i" as string]: 5 }}>
        <Pending>
          <div className="table-wrap">
            <table className="t">
              <thead>
                <tr>
                  <SortTh k="name" dflt="name">{t("Member")}</SortTh>
                  <SortTh k="id" dflt="name">{t("ID")}</SortTh>
                  <SortTh k="title" dflt="name">{t("Designation")}</SortTh>
                  <th>{t("Shift")}</th>
                  <SortTh k="st" dflt="name">{t("Today")}</SortTh>
                </tr>
              </thead>
              <tbody>
                {rows.map((m, i) => (
                  <tr key={m.id} style={{ animationDelay: `${i * 50}ms` }}>
                    <td><div className="person"><span className={`avatar ${avc(initials(m.fullName))}`}>{initials(m.fullName)}</span><div><b>{m.fullName}</b><small>{m.designation}</small></div></div></td>
                    <td className="muted num">{m.code}</td>
                    <td className="muted">{m.designation}</td>
                    <td className="muted">{m.shift}</td>
                    <td><span className={`chip ${m.st.tone}`}>{t(m.st.label)}</span></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!rows.length && <div className="empty"><b>{t("No one matches")}</b><span className="muted">{t("Try another name.")}</span><ClearFilters /></div>}
        </Pending>
      </section>
      <section className="card pad enter" style={{ ["--i" as string]: 6 }}>
        <span className="spot"></span>
        <div className="card-h">
          <h3>{t("Who's away · week of {date}", { date: fmtD(days[0]) })}</h3>
          <span className="row" style={{ gap: 12, fontSize: 12 }}><span className="chip info">{t("Approved leave")}</span><span className="chip warn">{t("Pending")}</span><span className="chip gold">{t("Holiday")}</span></span>
        </div>
        <div className="table-wrap">
          <table className="t" style={{ minWidth: 640 }}>
            <thead><tr><th>{t("Member")}</th>{days.map((d) => <th key={d} style={{ textAlign: "center" }}>{t(DOW[isoToDate(d).getUTCDay()])} {Number(d.slice(8))}</th>)}</tr></thead>
            <tbody>
              {away.map((r) => (
                <tr key={r.id}>
                  <td><b>{r.fullName}</b></td>
                  {r.cells.map((c, j) => (
                    <td key={j} style={{ textAlign: "center" }}>
                      {c ? <span className={`chip ${{ lv: "info", pend: "warn", hol: "gold" }[c]}`}>{{ lv: t("Leave"), pend: t("Pending"), hol: t("Holiday") }[c]}</span> : <span className="faint">·</span>}
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
