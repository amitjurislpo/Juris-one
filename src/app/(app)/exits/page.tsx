import type { Metadata } from "next";
import Link from "next/link";
import { Icon } from "@/components/Icon";
import { PageHeader } from "@/components/PageHeader";
import { can, requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { fmtDY } from "@/lib/format";
import { getT } from "@/lib/i18n/server";
import { ExitPolicyButton, StartExitButton } from "./ExitsClient";
import { EXIT_KIND, EXIT_STATUS, type ExitStatus } from "./status";

export const metadata: Metadata = { title: "Exits" };

interface Row {
  id: string; employeeCode: string; employeeName: string; designation: string; department: string | null; kind: string; status: ExitStatus;
  lastWorkingDay: string | null; noticeDays: number; clearance: { done: number; total: number }; settlementStatus: "DRAFT" | "APPROVED" | null;
}

const TABS = [["open", "In progress"], ["ended", "Ended"], ["all", "All"]] as const;

/** HR and Finance: employees who are leaving, with their notice, clearance and settlement. No amounts here. */
export default async function ExitsPage(props: PageProps<"/exits">) {
  const u = await requirePage("exits");
  const t = await getT();
  const sp = await props.searchParams;
  const tab = TABS.some(([k]) => k === sp.status) ? (sp.status as string) : "open";
  const q = typeof sp.q === "string" ? sp.q : "";
  const params = new URLSearchParams({ ...(tab === "all" ? {} : { status: tab }), ...(q ? { q } : {}) });
  const rows = await apiData<Row[]>(`/api/exits?${params}`);
  const manage = can(u, "exit.manage");
  const href = (s: string) => `/exits?${new URLSearchParams({ status: s, ...(q ? { q } : {}) })}`;

  return (
    <div className="col">
      <PageHeader eyebrow={t("People ops")} title={t("Employee")} em={t("exits")}
        sub={t("Resignations and terminations: notice period, clearance, then the full and final settlement. HR runs the process; Finance prepares and approves the settlement.")}>
        {manage && <ExitPolicyButton />}
        {manage && <StartExitButton />}
      </PageHeader>
      <div className="toolbar enter" style={{ ["--i" as string]: 1 }}>
        <div className="seg" role="tablist" aria-label={t("Show")}>
          {TABS.map(([k, l]) => <Link key={k} role="tab" aria-selected={tab === k} className={tab === k ? "on" : ""} href={href(k)}>{t(l)}</Link>)}
        </div>
        <form action="/exits" className="search">
          <Icon n="search" s={15} />
          <input name="q" defaultValue={q} placeholder={t("Search name or ID")} aria-label={t("Search")} />
          {tab !== "open" && <input type="hidden" name="status" value={tab} />}
        </form>
        <span className="tcount">{rows.length === 1 ? t("1 exit") : t("{n} exits", { n: rows.length })}</span>
      </div>
      <section className="card enter" style={{ ["--i" as string]: 2 }}>
        <div className="table-wrap">
          <table className="t">
            <thead><tr><th>{t("Employee")}</th><th className="hide-sm">{t("Type")}</th><th>{t("Status")}</th><th>{t("Last working day")}</th><th className="hide-sm">{t("Clearance")}</th><th className="hide-sm">{t("Settlement")}</th></tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td>
                    <Link href={`/exits/${r.id}`} className="person" style={{ textDecoration: "none", color: "inherit" }}>
                      <span className="kpi-ico" style={{ width: 34, height: 34, background: "var(--accentsoft)", color: "var(--accent)" }}><Icon n="user" s={16} /></span>
                      <div><b>{r.employeeName}</b><small>{r.employeeCode} · {r.department ?? r.designation}</small></div>
                    </Link>
                  </td>
                  <td className="hide-sm">{EXIT_KIND[r.kind] ? t(EXIT_KIND[r.kind]) : r.kind}</td>
                  <td><span className={`chip ${EXIT_STATUS[r.status].cls}`}>{t(EXIT_STATUS[r.status].text)}</span></td>
                  <td>{r.lastWorkingDay ? fmtDY(r.lastWorkingDay) : <span className="muted">{t("Not set")}</span>}</td>
                  <td className="num hide-sm">{r.clearance.done}/{r.clearance.total}</td>
                  <td className="hide-sm">{r.settlementStatus === "APPROVED" ? <span className="chip ok">{t("Approved")}</span> : r.settlementStatus === "DRAFT" ? <span className="chip warn">{t("Draft")}</span> : <span className="muted">—</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!rows.length && <div className="empty"><b className="display">{tab === "open" ? t("No exits in progress") : t("No exits found")}</b><span className="muted">{manage ? t("An exit starts when an employee resigns or when you start one with “Start an exit”.") : t("An exit starts when an employee resigns.")}</span></div>}
      </section>
    </div>
  );
}
