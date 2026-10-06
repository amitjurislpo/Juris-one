"use client";

import { useState } from "react";
import { createPortal } from "react-dom";
import { Icon } from "@/components/Icon";
import { fmtDY, inr, ymLabel } from "@/lib/format";
import type { PayslipSnapshot } from "@/lib/payroll/service";

export function SlipActions({ snap, code }: { snap: PayslipSnapshot; code: string }) {
  const [open, setOpen] = useState(false);
  const href = `/api/payslips/${snap.ym}/${code}`;
  return (
    <>
      <button className="btn btn-sm" onClick={() => setOpen(true)}><Icon n="eye" s={14} />View</button>
      <a className="btn btn-sm btn-gold" href={href} download><Icon n="download" s={14} />PDF</a>
      {open && <SlipView snap={snap} href={href} onClose={() => setOpen(false)} />}
    </>
  );
}

/** In-browser payslip (prototype tpl-slip) rendered from the frozen snapshot. */
function SlipView({ snap: p, href, onClose }: { snap: PayslipSnapshot; href: string; onClose: () => void }) {
  const e = p.employee;
  return createPortal(
    <div className="overlay" onMouseDown={(ev) => ev.target === ev.currentTarget && onClose()} onKeyDown={(ev) => ev.key === "Escape" && onClose()}>
      <div className="slip-wrap" role="dialog" aria-modal="true" aria-label={`Payslip ${ymLabel(p.ym)}`}>
        <div className="slip-bar">
          <b>Payslip-{e.code}-{p.ym}.pdf</b><span style={{ flex: 1 }}></span>
          <a className="btn btn-sm" href={href} download><Icon n="download" s={14} />Download PDF</a>
          <button className="icon-btn" onClick={onClose} aria-label="Close" autoFocus><Icon n="x" s={16} /></button>
        </div>
        <article className="slip">
          <header className="slip-h">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/brand/logo-ink.png" alt={p.company.name} style={{ width: 170, height: "auto" }} />
            <div style={{ textAlign: "right" }}><div className="slip-title">PAYSLIP</div><div className="muted">{ymLabel(p.ym)}</div><div className="faint" style={{ fontSize: 11.5 }}>{p.company.name} · {p.company.address}</div></div>
          </header>
          <div className="slip-meta">
            <div><small>Employee</small><b>{e.name}</b></div><div><small>Employee ID</small><b>{e.code}</b></div><div><small>Designation</small><b>{e.designation}</b></div>
            <div><small>Department</small><b>{e.department}</b></div><div><small>Date of joining</small><b>{fmtDY(e.doj)}</b></div><div><small>Tax regime</small><b>{p.regime === "OLD" ? "Old regime" : "New regime"}</b></div>
            <div><small>PAN</small><b>{e.panLast4 ? `XXXXXX${e.panLast4}` : "—"}</b></div><div><small>Bank account</small><b>{e.bankLast4 ? `${e.bankName ?? "Bank"} XXXX${e.bankLast4}` : "—"}</b></div><div><small>Pay date</small><b>{fmtDY(p.payDate)}</b></div>
          </div>
          <div className="slip-att">
            <div><small>Days in month</small><b>{p.attendance.daysInMonth}</b></div><div><small>Weekly offs</small><b>{p.attendance.weeklyOffs}</b></div><div><small>Holidays</small><b>{p.attendance.holidays}</b></div>
            <div><small>Present</small><b>{p.attendance.present}</b></div><div><small>Loss of pay</small><b style={{ color: p.attendance.lopDays ? "var(--bad)" : undefined }}>{p.attendance.lopDays}</b></div><div><small>Paid days</small><b>{p.attendance.paidDays}</b></div>
          </div>
          <div className="slip-tables">
            <table>
              <thead><tr><th>Earnings</th><th className="r">Rate</th><th className="r">Earned</th></tr></thead>
              <tbody>{p.components.map((c) => <tr key={c.label}><td>{c.label}</td><td className="r num faint">{c.rate ? inr(c.rate) : "—"}</td><td className="r num">{inr(c.earned)}</td></tr>)}</tbody>
              <tfoot><tr><td>Gross earnings</td><td></td><td className="r num">{inr(p.grossEarned)}</td></tr></tfoot>
            </table>
            <table>
              <thead><tr><th>Deductions</th><th className="r">Amount</th></tr></thead>
              <tbody>{p.deductions.map((d) => <tr key={d.label}><td>{d.label}</td><td className="r num">{inr(d.amount)}</td></tr>)}</tbody>
              <tfoot><tr><td>Total deductions</td><td className="r num">{inr(p.totalDeductions)}</td></tr></tfoot>
            </table>
          </div>
          <div className="slip-net"><div><small>Net pay</small><div style={{ fontSize: 13, opacity: 0.85 }}>Rupees {p.netWords} only</div></div><b className="display">{inr(p.net)}</b></div>
          <p className="faint" style={{ fontSize: 11.5, lineHeight: 1.6, margin: "16px 0 0" }}>
            Earned = monthly rate × paid days ÷ days in month. PF is 12% of earned Basic (wage ceiling ₹15,000). TDS is based on the {p.regime === "OLD" ? "old regime with verified investment proofs" : "new regime"}.{p.note ? ` Note: ${p.note}.` : ""} This is a system-generated payslip and does not require a signature.
          </p>
        </article>
      </div>
    </div>,
    document.body,
  );
}
