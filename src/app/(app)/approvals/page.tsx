import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { SearchBox, SegFilter } from "@/components/table/Table";
import { requirePage } from "@/lib/auth";
import { apiData, qs } from "@/lib/backend";
import { avc, initials } from "@/lib/format";
import { DecideButtons } from "./ApprovalsClient";

export const metadata: Metadata = { title: "Approvals" };


export default async function ApprovalsPage(props: PageProps<"/approvals">) {
  await requirePage("approvals");
  const d = await apiData<{
    tab: "leave" | "att"; q: string; canLeave: boolean; canReg: boolean; leaveCount: number; regCount: number;
    leaveCards: { id: string; name: string; dept: string; type: string; span: string; days: number; part: string; reason: string | null; impact: string; overlap: number }[];
    regCards: { id: string; name: string; dept: string; kind: string; date: string; detail: string; reason: string | null }[];
    decided: { id: string; when: string; who: string; what: string; ok: boolean; by: string }[];
  }>(`/api/approvals${qs(await props.searchParams)}`);
  const { tab, q, canLeave, canReg, leaveCount, regCount, leaveCards, regCards, decided } = d;

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
            {decided.map((x) => (
              <div className="it" key={x.id}>
                <span style={{ flex: 1 }}><b>{x.who}</b> <span className="muted">· {x.what}</span><div className="faint" style={{ fontSize: 12 }}>{x.when}</div></span>
                <span className={`chip ${x.ok ? "ok" : "bad"}`}>{x.ok ? "Approved" : "Declined"} · {x.by}</span>
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}
