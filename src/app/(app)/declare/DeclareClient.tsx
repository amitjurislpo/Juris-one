"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { inr } from "@/lib/format";
import { DECL } from "@/lib/tax/catalogue";
import { saveItem, saveProfile, setRegime, submitForVerification } from "./actions";

export interface ItemView { id: string; key: string; amount: number; status: string; proofName: string | null; reason: string | null }

const CHIP: Record<string, string> = { DRAFT: "neutral", PENDING: "warn", APPROVED: "ok", REJECTED: "bad" };
const LABEL: Record<string, string> = { DRAFT: "Draft", PENDING: "Pending", APPROVED: "Approved", REJECTED: "Rejected" };
const r = (rupees: number) => inr(rupees * 100);

export function DeclareClient(p: {
  regime: "NEW" | "OLD"; overridden: boolean; canChangeRegime: boolean; windowOpen: boolean; windowText: string;
  newTax: number; oldTax: number; monthlyTds: number; epf: number;
  profile: { metro: boolean; parentsSenior: boolean; landlordPan: string }; items: ItemView[];
}) {
  const router = useRouter();
  const toast = useToast();
  const [busy, start] = useTransition();
  const [openSec, setOpenSec] = useState("HRA");
  const [draft, setDraft] = useState<Record<string, string>>(() => Object.fromEntries(p.items.map((i) => [i.key, i.amount ? String(i.amount) : ""])));
  const [prof, setProf] = useState(p.profile);
  const [uploading, setUploading] = useState<string | null>(null);
  const item = (k: string) => p.items.find((i) => i.key === k);
  const better = p.newTax <= p.oldTax ? "NEW" : "OLD";
  const counts = { pending: p.items.filter((i) => i.status === "PENDING").length, approved: p.items.filter((i) => i.status === "APPROVED").length, rejected: p.items.filter((i) => i.status === "REJECTED").length };
  const secTotal = (s: (typeof DECL)[number]) => s.items.reduce((a, it) => a + (it.auto ? p.epf : Number(draft[it.k] || 0)), 0);
  const locked = !p.windowOpen;

  const act = (fn: () => Promise<{ ok: boolean; msg?: string; error?: string }>) => start(async () => {
    const res = await fn();
    if (!res.ok) toast(res.error!, "bad");
    else if (res.msg) toast(res.msg);
    router.refresh();
  });

  const upload = async (key: string, file: File) => {
    setUploading(key);
    const fd = new FormData();
    fd.set("item", key);
    fd.set("file", file);
    try {
      const res = await fetch("/api/declarations/proof", { method: "POST", body: fd });
      const j = await res.json();
      if (!res.ok) toast(j.error ?? "Upload failed", "bad");
      else toast(`Attached ${j.name}`);
      router.refresh();
    } finally { setUploading(null); }
  };

  const regimeCard = (v: "NEW" | "OLD", title: string, blurb: string, tax: number) => (
    <button type="button" className={`card regime${p.regime === v ? " on" : ""}`} aria-pressed={p.regime === v} disabled={busy || p.overridden || !p.canChangeRegime}
      onClick={() => act(() => setRegime(v))}>
      <span className="spot"></span>
      <span style={{ display: "flex", justifyContent: "space-between", alignItems: "center", width: "100%" }}><b>{title}</b>{better === v && <span className="chip ok nodot">Saves more</span>}</span>
      <span className="muted" style={{ fontSize: 13 }}>{blurb}</span>
      <span className="display" style={{ fontSize: 32 }}>{r(tax)}<span className="faint" style={{ fontSize: 13, fontFamily: "var(--font-geist)" }}> / year est.</span></span>
    </button>
  );

  return (
    <>
      <div className="regimes enter" style={{ ["--i" as string]: 1 }}>
        {regimeCard("NEW", "New regime", "Lower slab rates, ₹75,000 standard deduction, no investment proofs needed.", p.newTax)}
        {regimeCard("OLD", "Old regime", "Claim HRA, 80C, 80D, home loan and more — proofs required.", p.oldTax)}
      </div>
      <p className="faint" style={{ margin: 0, fontSize: 12.5 }}>
        {p.overridden ? "Finance has set your regime. " : ""}TDS this month: <b className="num">{r(p.monthlyTds)}</b> (approved proofs only). {p.windowText}
      </p>

      {p.regime === "NEW" ? (
        <section className="card pad enter" style={{ ["--i" as string]: 2, display: "flex", gap: 16, alignItems: "center" }}>
          <span className="spot"></span>
          <span className="kpi-ico" style={{ background: "var(--okbg)", color: "var(--ok)" }}><Icon n="check" s={18} /></span>
          <div><b>You&apos;re on the new regime</b><div className="muted" style={{ fontSize: 13.5, marginTop: 2 }}>Nothing to declare. Your TDS uses the ₹75,000 standard deduction and new-regime slabs.</div></div>
        </section>
      ) : (
        <>
          <div className="row enter" style={{ ["--i" as string]: 2, alignItems: "center" }}>
            <span className="chip warn">{counts.pending} pending</span><span className="chip ok">{counts.approved} approved</span>{counts.rejected > 0 && <span className="chip bad">{counts.rejected} rejected</span>}
            <span style={{ flex: 1 }}></span>
            <button className="btn btn-gold" disabled={busy || locked} onClick={() => act(submitForVerification)}><Icon n="upload" s={15} />Submit for verification</button>
          </div>
          {DECL.map((s, si) => (
            <section key={s.sec} className="card enter decl" style={{ ["--i" as string]: si + 3 }}>
              <span className="spot"></span>
              <button type="button" className="decl-h" onClick={() => setOpenSec(openSec === s.sec ? "" : s.sec)} aria-expanded={openSec === s.sec}>
                <span className="chip gold nodot" style={{ minWidth: 86, justifyContent: "center" }}>{s.sec}</span>
                <span style={{ flex: 1, textAlign: "left" }}><b>{s.title}</b>{s.cap && <span className="faint" style={{ fontSize: 12.5 }}> · limit {r(s.cap)}</span>}</span>
                <b className="num">{r(secTotal(s))}</b>
                <Icon n={openSec === s.sec ? "chevD" : "chevR"} s={16} />
              </button>
              {openSec === s.sec && (
                <div className="decl-b">
                  <p className="faint" style={{ margin: "0 0 12px", fontSize: 12.5 }}>{s.note}</p>
                  {s.sec === "HRA" && (
                    <div className="row" style={{ gap: 16, alignItems: "center", marginBottom: 10 }}>
                      <label style={{ display: "flex", gap: 8, alignItems: "center", fontSize: 13.5 }}>
                        <input type="checkbox" checked={prof.metro} disabled={locked} style={{ accentColor: "var(--accent)" }} onChange={(e) => { const n = { ...prof, metro: e.target.checked }; setProf(n); act(() => saveProfile(n)); }} />
                        I live in a metro city (Delhi, Mumbai, Kolkata, Chennai)
                      </label>
                      <label className="field" style={{ flexDirection: "row", alignItems: "center", gap: 8 }}>Landlord PAN
                        <input value={prof.landlordPan} maxLength={10} disabled={locked} placeholder="needed above ₹1,00,000/yr" style={{ width: 210, textTransform: "uppercase" }} onChange={(e) => setProf({ ...prof, landlordPan: e.target.value.toUpperCase() })} onBlur={() => act(() => saveProfile(prof))} />
                      </label>
                    </div>
                  )}
                  {s.sec === "80D" && (
                    <label style={{ display: "flex", gap: 8, alignItems: "center", fontSize: 13.5, marginBottom: 10 }}>
                      <input type="checkbox" checked={prof.parentsSenior} disabled={locked} style={{ accentColor: "var(--accent)" }} onChange={(e) => { const n = { ...prof, parentsSenior: e.target.checked }; setProf(n); act(() => saveProfile(n)); }} />
                      My parents are senior citizens (limit ₹50,000)
                    </label>
                  )}
                  {s.items.map((it) => {
                    const cur = item(it.k);
                    return (
                      <div className="decl-row" key={it.k}>
                        <span style={{ flex: "1 1 220px" }}>
                          <b style={{ fontWeight: 500 }}>{it.label}</b>
                          {it.hint && <span className="faint" style={{ display: "block", fontSize: 12 }}>{it.hint}</span>}
                          {cur?.status === "REJECTED" && cur.reason && <span style={{ display: "block", fontSize: 12, color: "var(--bad)" }}>Rejected: {cur.reason}</span>}
                        </span>
                        {it.auto ? (
                          <><b className="num">{r(p.epf)}</b><span className="chip ok nodot">From payroll</span></>
                        ) : (
                          <>
                            <label className="money"><span>₹</span>
                              <input type="number" min={0} step={1} value={draft[it.k] ?? ""} placeholder="0" aria-label={`${it.label} amount`} disabled={locked || cur?.status === "PENDING"}
                                onChange={(e) => setDraft({ ...draft, [it.k]: e.target.value })}
                                onBlur={() => { const v = Math.round(Number(draft[it.k] || 0)); if (v !== (cur?.amount ?? 0)) act(() => saveItem({ key: it.k, amount: v })); }} />
                            </label>
                            <label className={`btn btn-sm${locked || cur?.status === "PENDING" ? " disabled" : ""}`} style={{ cursor: "pointer" }} title={cur?.proofName ?? undefined}>
                              <Icon n="upload" s={14} />{uploading === it.k ? "Uploading…" : cur?.proofName ? "Replace" : "Proof"}
                              <input type="file" accept=".pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png" style={{ display: "none" }} disabled={locked || cur?.status === "PENDING"} aria-label={`Upload proof for ${it.label}`}
                                onChange={(e) => { const file = e.target.files?.[0]; if (file) void upload(it.k, file); e.target.value = ""; }} />
                            </label>
                            {cur?.proofName && <a className="linkish" style={{ fontSize: 12.5 }} href={`/api/declarations/proof/${cur.id}`} target="_blank" rel="noopener">{cur.proofName}</a>}
                            <span className={`chip ${cur ? CHIP[cur.status] : "neutral"}`} style={{ minWidth: 84, justifyContent: "center" }}>{cur && (cur.amount || cur.proofName) ? LABEL[cur.status] : "Not declared"}</span>
                          </>
                        )}
                      </div>
                    );
                  })}
                </div>
              )}
            </section>
          ))}
        </>
      )}
    </>
  );
}
