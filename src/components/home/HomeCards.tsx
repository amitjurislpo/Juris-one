import Link from "next/link";
import { Icon } from "@/components/Icon";
import type { homeCards } from "@/lib/dashboard";
import { avc, fmtD, initials, ymLabel } from "@/lib/format";

const LCHIP: Record<string, string> = { PENDING: "warn", APPROVED: "ok", DECLINED: "bad", CANCELLED: "neutral" };
const LLABEL: Record<string, string> = { PENDING: "Pending", APPROVED: "Approved", DECLINED: "Declined", CANCELLED: "Cancelled" };

/** Home side column: cards that appear by permission (REQUIREMENTS §5.12). No salary figures. */
export function HomeCards({ c, i0 }: { c: Awaited<ReturnType<typeof homeCards>>; i0: number }) {
  let i = i0;
  return (
    <>
      {c.myLeaves && (
        <section className="card pad enter" style={{ ["--i" as string]: i++ }}>
          <span className="spot"></span>
          <div className="card-h"><h3>My leave requests</h3><Link className="btn btn-sm btn-ghost" href="/leave">View all</Link></div>
          <div className="list">
            {!c.myLeaves.length && <div className="faint" style={{ fontSize: 13, padding: "6px 0" }}>No leave requests yet.</div>}
            {c.myLeaves.map((l) => (
              <div className="it" key={l.id}>
                <span style={{ width: 32, height: 32, borderRadius: 9, display: "grid", placeItems: "center", background: "var(--hover)", color: "var(--muted)", flex: "none" }}><Icon n="cal" s={15} /></span>
                <div style={{ flex: 1, minWidth: 0 }}><b style={{ fontSize: 13.5, fontWeight: 600 }}>{l.type}</b><div className="faint" style={{ fontSize: 12.5 }}>{fmtD(l.from)}{l.to !== l.from ? ` – ${fmtD(l.to)}` : ""} · {l.days}d</div></div>
                <span className={`chip ${LCHIP[l.status]}`}>{LLABEL[l.status]}</span>
              </div>
            ))}
          </div>
        </section>
      )}

      {c.team && (
        <section className="card pad enter" style={{ ["--i" as string]: i++ }}>
          <span className="spot"></span>
          <div className="card-h"><h3>Your team right now</h3><Link className="btn btn-sm btn-ghost" href="/team">Open</Link></div>
          <div className="list">
            {c.team.map((m) => (
              <div className="it" key={m.id}>
                <span className={`avatar ${avc(initials(m.name))}`}>{initials(m.name)}</span>
                <div style={{ flex: 1, minWidth: 0 }}><b style={{ fontSize: 13.5, fontWeight: 600 }}>{m.name}</b><div className="faint" style={{ fontSize: 12 }}>{m.title}</div></div>
                <span className={`chip ${m.st.tone}`}>{m.st.label}</span>
              </div>
            ))}
          </div>
        </section>
      )}

      {c.approvals && (
        <section className="card pad enter" style={{ ["--i" as string]: i++ }}>
          <span className="spot"></span>
          <div className="card-h"><h3>Approvals</h3><span className="chip gold nodot">{c.approvals.leaveToo ? "Leave & attendance" : "Attendance"}</span></div>
          <div style={{ display: "flex", alignItems: "baseline", gap: 10 }}><span className="num" style={{ fontSize: 48, fontWeight: 600, letterSpacing: "-.05em", lineHeight: 1 }}>{c.approvals.count}</span><span className="muted">{c.approvals.count === 1 ? "request" : "requests"} waiting</span></div>
          <p className="muted" style={{ margin: "10px 0 16px", fontSize: 13.5 }}>{c.approvals.leaveToo ? "Leave and regularization requests from every team." : "Attendance regularizations that affect payroll."}</p>
          <Link className="btn btn-gold" style={{ width: "100%" }} href="/approvals">Review now<Icon n="arrow" s={15} /></Link>
        </section>
      )}

      {c.payroll && (
        <section className="card pad enter" style={{ ["--i" as string]: i++ }}>
          <span className="spot"></span>
          <div className="card-h"><h3>Payroll · {ymLabel(c.payroll.month).split(" ")[0]}</h3><span className={`chip ${c.payroll.status === "Closed" ? "ok" : "warn"}`}>{c.payroll.status}</span></div>
          <p className="muted" style={{ margin: "0 0 16px", fontSize: 13.5 }}>
            {c.payroll.proofs !== null && `${c.payroll.proofs} investment proof${c.payroll.proofs === 1 ? "" : "s"} waiting for verification. `}
            {c.payroll.regs} regularization{c.payroll.regs === 1 ? "" : "s"} may change LOP.
          </p>
          <div style={{ display: "flex", gap: 8 }}>
            <Link className="btn btn-gold" style={{ flex: 1 }} href="/payroll">Open payroll</Link>
            {c.payroll.proofs !== null && <Link className="btn" href="/proofs">Proofs</Link>}
          </div>
        </section>
      )}

      {c.payslip && (
        <section className="card pad enter" style={{ ["--i" as string]: i++, display: "flex", gap: 14, alignItems: "center" }}>
          <span className="spot"></span>
          <span className="kpi-ico" style={{ background: "var(--accentsoft)", color: "var(--accent)" }}><Icon n="doc" s={18} /></span>
          <div style={{ flex: 1 }}><h3 style={{ margin: 0, fontSize: 14, fontWeight: 600 }}>{ymLabel(c.payslip.month).split(" ")[0]} payslip</h3><div className="faint" style={{ fontSize: 12.5 }}>{c.payslip.ready ? "Ready to view and download" : "Available once Finance closes payroll"}</div></div>
          <Link className="btn btn-sm" href="/payslips">Open</Link>
        </section>
      )}
    </>
  );
}
