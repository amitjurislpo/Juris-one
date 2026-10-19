import Link from "next/link";
import { Icon } from "@/components/Icon";
import { avc, fmtD, initials, ymLabel } from "@/lib/format";
import { getT } from "@/lib/i18n/server";

const LCHIP: Record<string, string> = { PENDING: "warn", APPROVED: "ok", DECLINED: "bad", CANCELLED: "neutral" };
const LLABEL: Record<string, string> = { PENDING: "Pending", APPROVED: "Approved", DECLINED: "Declined", CANCELLED: "Cancelled" };

/** Home side column: cards that appear by permission (REQUIREMENTS §5.12). No salary figures. */
/** Permission-driven Home cards, from /api/home (salary is never included). */
export interface HomeCardsData {
  myLeaves: { id: string; type: string; from: string; to: string; days: number; status: string }[] | null;
  team: { id: string; name: string; title: string; st: { label: string; tone: "ok" | "info" | "warn" | "gold" | "neutral" } }[] | null;
  approvals: { leaveToo: boolean; count: number } | null;
  payroll: { month: string; status: "Closed" | "Approved" | "In review"; proofs: number | null; regs: number } | null;
  payslip: { month: string; ready: boolean } | null;
}

export async function HomeCards({ c, i0 }: { c: HomeCardsData; i0: number }) {
  const t = await getT();
  let i = i0;
  return (
    <>
      {c.myLeaves && (
        <section className="card pad enter" style={{ ["--i" as string]: i++ }}>
          <span className="spot"></span>
          <div className="card-h"><h3>{t("My leave requests")}</h3><Link className="btn btn-sm btn-ghost" href="/leave">{t("View all")}</Link></div>
          <div className="list">
            {!c.myLeaves.length && <div className="faint" style={{ fontSize: 13, padding: "6px 0" }}>{t("No leave requests yet.")}</div>}
            {c.myLeaves.map((l) => (
              <div className="it" key={l.id}>
                <span style={{ width: 32, height: 32, borderRadius: 9, display: "grid", placeItems: "center", background: "var(--hover)", color: "var(--muted)", flex: "none" }}><Icon n="cal" s={15} /></span>
                <div style={{ flex: 1, minWidth: 0 }}><b style={{ fontSize: 13.5, fontWeight: 600 }}>{t(l.type)}</b><div className="faint" style={{ fontSize: 12.5 }}>{fmtD(l.from)}{l.to !== l.from ? ` – ${fmtD(l.to)}` : ""} · {l.days}d</div></div>
                <span className={`chip ${LCHIP[l.status]}`}>{t(LLABEL[l.status])}</span>
              </div>
            ))}
          </div>
        </section>
      )}

      {c.team && (
        <section className="card pad enter" style={{ ["--i" as string]: i++ }}>
          <span className="spot"></span>
          <div className="card-h"><h3>{t("Your team right now")}</h3><Link className="btn btn-sm btn-ghost" href="/team">{t("Open")}</Link></div>
          <div className="list">
            {c.team.map((m) => (
              <div className="it" key={m.id}>
                <span className={`avatar ${avc(initials(m.name))}`}>{initials(m.name)}</span>
                <div style={{ flex: 1, minWidth: 0 }}><b style={{ fontSize: 13.5, fontWeight: 600 }}>{m.name}</b><div className="faint" style={{ fontSize: 12 }}>{m.title}</div></div>
                <span className={`chip ${m.st.tone}`}>{t(m.st.label)}</span>
              </div>
            ))}
          </div>
        </section>
      )}

      {c.approvals && (
        <section className="card pad enter" style={{ ["--i" as string]: i++ }}>
          <span className="spot"></span>
          <div className="card-h"><h3>{t("Approvals")}</h3><span className="chip gold nodot">{c.approvals.leaveToo ? t("Leave & attendance") : t("Attendance")}</span></div>
          <div style={{ display: "flex", alignItems: "baseline", gap: 10 }}><span className="num" style={{ fontSize: 48, fontWeight: 600, letterSpacing: "-.05em", lineHeight: 1 }}>{c.approvals.count}</span><span className="muted">{c.approvals.count === 1 ? t("request waiting") : t("requests waiting")}</span></div>
          <p className="muted" style={{ margin: "10px 0 16px", fontSize: 13.5 }}>{c.approvals.leaveToo ? t("Leave and regularization requests from every team.") : t("Attendance regularizations that affect payroll.")}</p>
          <Link className="btn btn-gold" style={{ width: "100%" }} href="/approvals">{t("Review now")}<Icon n="arrow" s={15} /></Link>
        </section>
      )}

      {c.payroll && (
        <section className="card pad enter" style={{ ["--i" as string]: i++ }}>
          <span className="spot"></span>
          <div className="card-h"><h3>{t("Payroll")} · {ymLabel(c.payroll.month).split(" ")[0]}</h3><span className={`chip ${c.payroll.status === "Closed" ? "ok" : "warn"}`}>{t(c.payroll.status)}</span></div>
          <p className="muted" style={{ margin: "0 0 16px", fontSize: 13.5 }}>
            {c.payroll.proofs !== null && `${t("{n} investment proof(s) waiting for verification.", { n: c.payroll.proofs })} `}
            {t("{n} regularization(s) may change LOP.", { n: c.payroll.regs })}
          </p>
          <div style={{ display: "flex", gap: 8 }}>
            <Link className="btn btn-gold" style={{ flex: 1 }} href="/payroll">{t("Open payroll")}</Link>
            {c.payroll.proofs !== null && <Link className="btn" href="/proofs">{t("Proofs")}</Link>}
          </div>
        </section>
      )}

      {c.payslip && (
        <section className="card pad enter" style={{ ["--i" as string]: i++, display: "flex", gap: 14, alignItems: "center" }}>
          <span className="spot"></span>
          <span className="kpi-ico" style={{ background: "var(--accentsoft)", color: "var(--accent)" }}><Icon n="doc" s={18} /></span>
          <div style={{ flex: 1 }}><h3 style={{ margin: 0, fontSize: 14, fontWeight: 600 }}>{ymLabel(c.payslip.month).split(" ")[0]} {t("payslip")}</h3><div className="faint" style={{ fontSize: 12.5 }}>{c.payslip.ready ? t("Ready to view and download") : t("Available once Finance closes payroll")}</div></div>
          <Link className="btn btn-sm" href="/payslips">{t("Open")}</Link>
        </section>
      )}
    </>
  );
}
