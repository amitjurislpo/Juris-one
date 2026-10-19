import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { SearchBox, SegFilter } from "@/components/table/Table";
import { getT } from "@/lib/i18n/server";
import { requirePage } from "@/lib/auth";
import { apiData, qs } from "@/lib/backend";
import { avc, initials } from "@/lib/format";
import { DecideButtons } from "./ApprovalsClient";

export const metadata: Metadata = { title: "Approvals" };


export default async function ApprovalsPage(props: PageProps<"/approvals">) {
  await requirePage("approvals");
  const t = await getT();
  const d = await apiData<{
    tab: "leave" | "att"; q: string; canLeave: boolean; canReg: boolean; leaveCount: number; regCount: number;
    leaveCards: { id: string; name: string; dept: string; type: string; span: string; days: number; part: string; reason: string | null; impact: string; overlap: number }[];
    regCards: { id: string; name: string; dept: string; kind: string; date: string; detail: string; reason: string | null }[];
    decided: { id: string; when: string; who: string; what: string; ok: boolean; by: string }[];
  }>(`/api/approvals${qs(await props.searchParams)}`);
  const { tab, q, canLeave, canReg, leaveCount, regCount, leaveCards, regCards, decided } = d;

  const total = leaveCount + regCount;
  const tabs = [...(canLeave ? [{ v: "leave", l: t("Leave · {n}", { n: leaveCount }) }] : []), ...(canReg ? [{ v: "att", l: t("Attendance · {n}", { n: regCount }) }] : [])];

  return (
    <div className="col" style={{ gap: 24 }}>
      <PageHeader eyebrow={t("Approvals")} title={<><span className="num">{total}</span> {total === 1 ? t("request") : t("requests")}</>} em={t("awaiting you")}
        sub={tab === "att" ? t("Regularizations go to HR, Finance and Admin — the first decision counts and updates LOP in payroll.") : t("Approving leave updates the employee's balance and calendar instantly.")}>
        {tabs.length > 1 && <SegFilter name="tab" dflt={canLeave ? "leave" : "att"} label={t("Queue")} options={tabs} />}
      </PageHeader>
      <div className="toolbar enter"><SearchBox placeholder={t("Search by employee")} label={t("Search requests")} /></div>
      <div className="col" style={{ gap: 14 }}>
        {tab === "leave" && leaveCards.map((l) => (
          <article className="card req enter" key={l.id}>
            <span className="spot" aria-hidden="true"></span>
            <span className={`avatar lg ${avc(initials(l.name))}`}>{initials(l.name)}</span>
            <div className="body">
              <div style={{ display: "flex", flexWrap: "wrap", gap: 10, alignItems: "center" }}><b style={{ fontSize: 16 }}>{l.name}</b><span className="chip info">{t(l.type)}</span><span className="faint" style={{ fontSize: 12.5 }}>{l.dept}</span></div>
              <span style={{ fontSize: 14.5 }}>{l.span} · {l.days === 1 ? t("{n} day", { n: l.days }) : t("{n} days", { n: l.days })} <span className="faint">({t(l.part)})</span></span>
              <span className="muted" style={{ fontSize: 13.5 }}>{l.reason ? `“${l.reason}”` : t("No reason given")}</span>
              <span className="faint" style={{ fontSize: 12.5 }}>{l.impact}</span>
              {l.overlap > 0 && <span className="chip warn" style={{ alignSelf: "flex-start" }}>{l.overlap > 1 ? t("{n} overlapping requests in {dept}", { n: l.overlap, dept: l.dept }) : t("{n} overlapping request in {dept}", { n: l.overlap, dept: l.dept })}</span>}
            </div>
            <DecideButtons id={l.id} kind="leave" who={l.name} />
          </article>
        ))}
        {tab === "att" && regCards.map((r) => (
          <article className="card req enter" key={r.id}>
            <span className="spot" aria-hidden="true"></span>
            <span className={`avatar lg ${avc(initials(r.name))}`}>{initials(r.name)}</span>
            <div className="body">
              <div style={{ display: "flex", flexWrap: "wrap", gap: 10, alignItems: "center" }}><b style={{ fontSize: 16 }}>{r.name}</b><span className="chip gold">{t(r.kind)}</span><span className="faint" style={{ fontSize: 12.5 }}>{t("{dept} · sent to HR, Finance & Admin", { dept: r.dept })}</span></div>
              <span style={{ fontSize: 14.5 }}>{r.date} · {r.detail}</span>
              <span className="muted" style={{ fontSize: 13.5 }}>{r.reason ? `“${r.reason}”` : t("No reason given")}</span>
            </div>
            <DecideButtons id={r.id} kind="reg" who={r.name} />
          </article>
        ))}
        {((tab === "leave" && !leaveCards.length) || (tab === "att" && !regCards.length)) && <div className="empty enter"><b>{t("All clear")}</b><span className="muted">{q ? t("No pending request matches that name.") : t("Nothing waiting in this queue.")}</span></div>}
      </div>
      {decided.length > 0 && (
        <section className="card pad enter">
          <span className="spot" aria-hidden="true"></span>
          <div className="card-h"><h3>{t("Decided · last 30 days")}</h3></div>
          <div className="list">
            {decided.map((x) => (
              <div className="it" key={x.id}>
                <span style={{ flex: 1 }}><b>{x.who}</b> <span className="muted">· {x.what}</span><div className="faint" style={{ fontSize: 12 }}>{x.when}</div></span>
                <span className={`chip ${x.ok ? "ok" : "bad"}`}>{x.ok ? t("Approved") : t("Declined")} · {x.by}</span>
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}
