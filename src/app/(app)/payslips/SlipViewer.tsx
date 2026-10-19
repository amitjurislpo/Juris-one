"use client";

import { useState } from "react";
import { createPortal } from "react-dom";
import { Icon } from "@/components/Icon";
import { fmtDY, inr, ymLabel } from "@/lib/format";
import { useT } from "@/lib/i18n/client";
import type { PayslipSnapshot } from "@/lib/api-types";

export function SlipActions({ snap, code }: { snap: PayslipSnapshot; code: string }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const href = `/api/payslips/${snap.ym}/${code}`;
  return (
    <>
      <button className="btn btn-sm" onClick={() => setOpen(true)}><Icon n="eye" s={14} />{t("View")}</button>
      <a className="btn btn-sm btn-gold" href={href} download><Icon n="download" s={14} />PDF</a>
      {open && <SlipView snap={snap} href={href} onClose={() => setOpen(false)} />}
    </>
  );
}

/** In-browser payslip (prototype tpl-slip) rendered from the frozen snapshot. */
function SlipView({ snap: p, href, onClose }: { snap: PayslipSnapshot; href: string; onClose: () => void }) {
  const t = useT();
  const e = p.employee;
  return createPortal(
    <div className="overlay" onMouseDown={(ev) => ev.target === ev.currentTarget && onClose()} onKeyDown={(ev) => ev.key === "Escape" && onClose()}>
      <div className="slip-wrap" role="dialog" aria-modal="true" aria-label={t("Payslip {month}", { month: ymLabel(p.ym) })}>
        <div className="slip-bar">
          <b>Payslip-{e.code}-{p.ym}.pdf</b><span style={{ flex: 1 }}></span>
          <a className="btn btn-sm" href={href} download><Icon n="download" s={14} />{t("Download PDF")}</a>
          <button className="icon-btn" onClick={onClose} aria-label={t("Close")} autoFocus><Icon n="x" s={16} /></button>
        </div>
        <article className="slip">
          <header className="slip-h">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/brand/logo-ink.png" alt={p.company.name} style={{ width: 170, height: "auto" }} />
            <div style={{ textAlign: "right" }}><div className="slip-title">{t("PAYSLIP")}</div><div className="muted">{ymLabel(p.ym)}</div><div className="faint" style={{ fontSize: 11.5 }}>{p.company.name} · {p.company.address}</div></div>
          </header>
          <div className="slip-meta">
            <div><small>{t("Employee")}</small><b>{e.name}</b></div><div><small>{t("Employee ID")}</small><b>{e.code}</b></div><div><small>{t("Designation")}</small><b>{e.designation}</b></div>
            <div><small>{t("Department")}</small><b>{e.department}</b></div><div><small>{t("Date of joining")}</small><b>{fmtDY(e.doj)}</b></div><div><small>{t("Tax regime")}</small><b>{p.regime === "OLD" ? t("Old regime") : t("New regime")}</b></div>
            <div><small>PAN</small><b>{e.panLast4 ? `XXXXXX${e.panLast4}` : "—"}</b></div><div><small>{t("Bank account")}</small><b>{e.bankLast4 ? `${e.bankName ?? t("Bank")} XXXX${e.bankLast4}` : "—"}</b></div><div><small>{t("Pay date")}</small><b>{fmtDY(p.payDate)}</b></div>
          </div>
          <div className="slip-att">
            <div><small>{t("Days in month")}</small><b>{p.attendance.daysInMonth}</b></div><div><small>{t("Weekly offs")}</small><b>{p.attendance.weeklyOffs}</b></div><div><small>{t("Holidays")}</small><b>{p.attendance.holidays}</b></div>
            <div><small>{t("Present")}</small><b>{p.attendance.present}</b></div><div><small>{t("Loss of pay")}</small><b style={{ color: p.attendance.lopDays ? "var(--bad)" : undefined }}>{p.attendance.lopDays}</b></div><div><small>{t("Paid days")}</small><b>{p.attendance.paidDays}</b></div>
          </div>
          <div className="slip-tables">
            <table>
              <thead><tr><th>{t("Earnings")}</th><th className="r">{t("Rate")}</th><th className="r">{t("Earned")}</th></tr></thead>
              <tbody>{p.components.map((c) => <tr key={c.label}><td>{t(c.label)}</td><td className="r num faint">{c.rate ? inr(c.rate) : "—"}</td><td className="r num">{inr(c.earned)}</td></tr>)}</tbody>
              <tfoot><tr><td>{t("Gross earnings")}</td><td></td><td className="r num">{inr(p.grossEarned)}</td></tr></tfoot>
            </table>
            <table>
              <thead><tr><th>{t("Deductions")}</th><th className="r">{t("Amount")}</th></tr></thead>
              <tbody>{p.deductions.map((d) => <tr key={d.label}><td>{t(d.label)}</td><td className="r num">{inr(d.amount)}</td></tr>)}</tbody>
              <tfoot><tr><td>{t("Total deductions")}</td><td className="r num">{inr(p.totalDeductions)}</td></tr></tfoot>
            </table>
          </div>
          <div className="slip-net"><div><small>{t("Net pay")}</small><div style={{ fontSize: 13, opacity: 0.85 }}>{t("Rupees {words} only", { words: p.netWords })}</div></div><b className="display">{inr(p.net)}</b></div>
          <p className="faint" style={{ fontSize: 11.5, lineHeight: 1.6, margin: "16px 0 0" }}>
            {t("Earned = monthly rate × paid days ÷ days in month. PF is 12% of earned Basic (wage ceiling ₹15,000).")} {p.regime === "OLD" ? t("TDS is based on the old regime with verified investment proofs.") : t("TDS is based on the new regime.")}{p.note ? ` ${t("Note: {note}.", { note: p.note })}` : ""} {t("This is a system-generated payslip and does not require a signature.")}
          </p>
        </article>
      </div>
    </div>,
    document.body,
  );
}
