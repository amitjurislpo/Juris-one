"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { Drawer } from "@/components/ui/Modal";
import { useT } from "@/lib/i18n/client";
import { inr, ymLabel } from "@/lib/format";
import type { TaxProjection } from "@/lib/api-types";
import { overrideRegime, setPreviousEmployer, taxDetail } from "./actions";

const rp = (rupees: number) => inr(rupees * 100);
const pct = (r: number) => `${Math.round(r * 100)}%`;

/** A table row that opens the computation drawer. */
export function TaxRow({ id, name, i, children }: { id: string; name: string; i: number; children: React.ReactNode }) {
  const [open, setOpen] = useState(false);
  const tr = useT();
  return (
    <>
      <tr onClick={() => setOpen(true)} style={{ cursor: "pointer", animationDelay: `${i * 35}ms` }} tabIndex={0} onKeyDown={(e) => e.key === "Enter" && setOpen(true)} aria-label={tr("Tax computation for {name}", { name })}>{children}</tr>
      {open && <TaxDrawer id={id} onClose={() => setOpen(false)} />}
    </>
  );
}

function TaxDrawer({ id, onClose }: { id: string; onClose: () => void }) {
  const router = useRouter();
  const toast = useToast();
  const tr = useT();
  const [d, setD] = useState<{ month: string; tax: TaxProjection; name: string; code: string; title: string } | null>(null);
  const [prev, setPrev] = useState({ income: "", tds: "" });
  const [busy, start] = useTransition();
  const load = useCallback(() => taxDetail(id).then((r) => {
    if (r.ok) { setD(r.data!); setPrev({ income: String(r.data!.tax.prevIncome), tds: String(r.data!.tax.prevTds) }); }
    else toast(r.error, "bad");
  }), [id, toast]);
  useEffect(() => { void load(); }, [load]);

  const act = (p: Promise<{ ok: boolean; msg?: string; error?: string }>) => start(async () => {
    const r = await p;
    toast(r.ok ? r.msg! : r.error!, r.ok ? "ok" : "bad");
    await load();
    router.refresh();
  });

  const t = d?.tax;
  const cur = t?.current;
  const max = t ? Math.max(t.newRegime.total, t.oldRegime.total, 1) : 1;
  return (
    <Drawer label={tr("Tax computation")} width={560} onClose={onClose}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
        <div><span className="eyebrow">{d ? `${d.code} · ${d.title}` : tr("Loading…")}</span><h2 className="display" style={{ margin: "4px 0 0", fontSize: 30 }}>{d?.name ?? ""}</h2></div>
        <button className="icon-btn" onClick={onClose} aria-label={tr("Close")}><Icon n="x" s={16} /></button>
      </div>
      {t && cur && d && (
        <>
          <div className="field">{tr("Tax regime")} <span className="hint">{t.regimeOverridden ? tr("Employee's choice — currently set by Finance; Finance can override") : tr("Employee's choice; Finance can override")}</span>
            <div className="seg" style={{ width: "100%" }} role="group" aria-label={tr("Tax regime")}>
              {(["NEW", "OLD"] as const).map((v) => <button key={v} type="button" className={t.regime === v ? "on" : undefined} aria-pressed={t.regime === v} disabled={busy} onClick={() => t.regime !== v && act(overrideRegime(id, v))}>{v === "NEW" ? tr("New regime") : tr("Old regime")}</button>)}
            </div>
          </div>
          <div style={{ display: "flex", alignItems: "baseline", gap: 10 }}>
            <span className="display" style={{ fontSize: 44, lineHeight: 1 }}>{rp(t.monthly)}</span>
            <span className="muted">{tr(t.monthsLeft === 1 ? "TDS / month · {n} month left · {month}" : "TDS / month · {n} months left · {month}", { n: t.monthsLeft, month: ymLabel(d.month) })}</span>
          </div>
          {(["NEW", "OLD"] as const).map((v) => {
            const x = v === "NEW" ? t.newRegime : t.oldRegime;
            return (
              <div className="cmp" key={v}>
                <div style={{ display: "flex", justifyContent: "space-between", fontSize: 13 }}><span className="muted">{v === "NEW" ? tr("New regime · annual tax") : tr("Old regime · annual tax (verified proofs)")}</span><b className="num">{rp(x.total)}</b></div>
                <div className="track"><i style={{ width: `${(x.total / max) * 100}%`, background: t.regime === v ? "var(--accentgrad)" : "var(--line3)" }}></i></div>
              </div>
            );
          })}
          <div>
            <div className="kv"><span className="muted">{tr("Salary from Juris · {label}", { label: t.jurisLabel })}</span><b className="num">{rp(t.jurisIncome)}</b></div>
            {t.prevIncome > 0 && <div className="kv"><span className="muted">{tr("Previous employer (Form 12B)")}</span><b className="num">{rp(t.prevIncome)}</b></div>}
            <div className="kv"><span className="muted">{tr("Standard deduction")}</span><b className="num">− {rp(cur.std)}</b></div>
            {t.regime === "OLD" && t.deductions.lines.map(([k, v]) => <div className="kv" key={k}><span className="muted">{k}</span><b className="num">− {rp(v)}</b></div>)}
            <div className="kv"><span className="muted">{tr("Taxable income")}</span><b className="num">{rp(cur.taxable)}</b></div>
            <div className="kv"><span className="muted">{tr("Tax on slabs")}</span><b className="num">{rp(Math.round(cur.slabTax))}</b></div>
            {cur.rebate > 0 && <div className="kv"><span className="muted">{cur.base === 0 ? tr("Rebate (87A)") : tr("Marginal relief")}</span><b className="num">− {rp(Math.round(cur.rebate))}</b></div>}
            {cur.surcharge > 0 && <div className="kv"><span className="muted">{tr("Surcharge")}</span><b className="num">{rp(Math.round(cur.surcharge))}</b></div>}
            <div className="kv"><span className="muted">{tr("Health & education cess 4%")}</span><b className="num">{rp(Math.round(cur.cess))}</b></div>
            <div className="kv total"><span>{tr("Annual tax")}</span><b className="num">{rp(cur.total)}</b></div>
            {(t.prevTds > 0 || t.jurisTdsSoFar > 0) && <div className="kv"><span className="muted">{tr("TDS already deducted (previous employer {prev} · Juris {juris})", { prev: rp(t.prevTds), juris: rp(t.jurisTdsSoFar) })}</span><b className="num">− {rp(t.prevTds + t.jurisTdsSoFar)}</b></div>}
          </div>
          <div>
            <div className="eyebrow" style={{ marginBottom: 6 }}>{tr("Slab working")}</div>
            {cur.lines.map((s) => <div className="kv" key={s.from}><span className="muted">{rp(s.from)} – {s.to === null ? tr("above") : rp(s.to)} · {pct(s.rate)}</span><b className="num">{rp(Math.round(s.amount))}</b></div>)}
          </div>
          <div className="card pad" style={{ boxShadow: "none" }}>
            <div className="eyebrow" style={{ marginBottom: 8 }}>{tr("Previous employer this FY (Form 12B)")}</div>
            <div className="grid2">
              <label className="field">{tr("Income (₹)")}<input type="number" min={0} value={prev.income} onChange={(e) => setPrev({ ...prev, income: e.target.value })} /></label>
              <label className="field">{tr("TDS deducted (₹)")}<input type="number" min={0} value={prev.tds} onChange={(e) => setPrev({ ...prev, tds: e.target.value })} /></label>
            </div>
            <button className="btn btn-sm" style={{ marginTop: 10 }} disabled={busy} onClick={() => act(setPreviousEmployer({ employeeId: id, income: Math.round(Number(prev.income) || 0), tds: Math.round(Number(prev.tds) || 0) }))}>{tr("Save")}</button>
          </div>
        </>
      )}
    </Drawer>
  );
}
