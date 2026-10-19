"use client";

import { useEffect, useMemo, useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { Modal } from "@/components/ui/Modal";
import { addDays, dowOf } from "@/lib/attendance/engine";
import { fmtD, fmtDL, MONTHS } from "@/lib/format";
import { useT } from "@/lib/i18n/client";
import type { Preview } from "@/lib/api-types";
import { applyLeave, preview } from "./actions";

export interface TypeOpt { code: string; name: string; available: number | null; paid: boolean }
type Part = "FULL" | "FIRST_HALF" | "SECOND_HALF";

/** Prototype range picker: tap a start, then an end; weekends, holidays and past dates disabled. */
function RangePicker({ from, to, onPick, today, holidays, weeklyOffs }: { from: string; to: string; onPick: (a: string, b: string) => void; today: string; holidays: Record<string, string>; weeklyOffs: number[] }) {
  const t = useT();
  const [ym, setYm] = useState((from || today).slice(0, 7));
  const [picking, setPicking] = useState(false);
  const first = `${ym}-01`;
  const n = new Date(Date.UTC(Number(ym.slice(0, 4)), Number(ym.slice(5)), 0)).getUTCDate();
  const lead = (dowOf(first) + 6) % 7;
  const lo = from && to ? (from < to ? from : to) : from;
  const hi = from && to ? (from < to ? to : from) : from;
  const move = (k: number) => setYm(new Date(Date.UTC(Number(ym.slice(0, 4)), Number(ym.slice(5)) - 1 + k, 1)).toISOString().slice(0, 7));
  return (
    <div className="drp">
      <div className="drp-h">
        <button className="icon-btn" type="button" onClick={() => move(-1)} aria-label={t("Previous month")} disabled={ym <= today.slice(0, 7)}><Icon n="chevL" /></button>
        <b>{t(MONTHS[Number(ym.slice(5)) - 1])} {ym.slice(0, 4)}</b>
        <button className="icon-btn" type="button" onClick={() => move(1)} aria-label={t("Next month")}><Icon n="chevR" /></button>
      </div>
      <div className="drp-g">
        {["M", "T", "W", "T", "F", "S", "S"].map((w, i) => <span className="w" key={i}>{w}</span>)}
        {Array.from({ length: lead }, (_, i) => <span className="blank" key={`b${i}`}></span>)}
        {Array.from({ length: n }, (_, i) => {
          const k = addDays(first, i);
          const wk = weeklyOffs.includes(dowOf(k));
          const hol = holidays[k];
          const dis = k < today || wk || !!hol;
          const cls = [hol && "hol", k === today && "td", k === lo && "s", k === hi && "e", lo && hi && k > lo && k < hi && "in"].filter(Boolean).join(" ");
          return (
            <button key={k} type="button" className={cls || undefined} disabled={dis} title={hol || (wk ? t("Weekend") : undefined)} aria-label={`${fmtDL(k)}${hol ? `, ${hol}` : ""}`}
              aria-pressed={k === lo || k === hi}
              onClick={() => { if (!picking || !from) { onPick(k, k); setPicking(true); } else { onPick(from, k); setPicking(false); } }}>
              {i + 1}
            </button>
          );
        })}
      </div>
      <div className="faint" style={{ fontSize: 11.5, marginTop: 10, display: "flex", gap: 14, flexWrap: "wrap" }}>
        <span><span style={{ color: "var(--gold)" }}>●</span> {t("Holiday")}</span><span>{t("Weekends, holidays and past dates can't be picked")}</span>
      </div>
    </div>
  );
}

export function ApplyLeave({ types, today, holidays, weeklyOffs, onClose }: { types: TypeOpt[]; today: string; holidays: Record<string, string>; weeklyOffs: number[]; onClose: () => void }) {
  const toast = useToast();
  const t = useT();
  const [type, setType] = useState(types.find((x) => x.code === "CL")?.code ?? types[0].code);
  const [range, setRange] = useState({ from: "", to: "" });
  const [part, setPart] = useState<Part>("FULL");
  const [reason, setReason] = useState("");
  const [p, setP] = useState<Preview | null>(null);
  const [tried, setTried] = useState(false);
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const lo = range.from && range.to ? [range.from, range.to].sort()[0] : range.from;
  const hi = range.from && range.to ? [range.from, range.to].sort()[1] : range.from;
  const single = !!lo && lo === hi;
  const tName = types.find((x) => x.code === type)!.name;

  const query = useMemo(() => (lo ? { type, from: lo, to: hi, part: single ? part : ("FULL" as Part), reason } : null), [type, lo, hi, single, part, reason]);
  useEffect(() => {
    if (!query) return;
    let alive = true;
    const timer = setTimeout(() => preview(query).then((r) => { if (alive && r.ok) setP(r.data!); }), 120);
    return () => { alive = false; clearTimeout(timer); };
  }, [query]);
  const pv = query ? p : null;

  const submit = () => {
    setTried(true);
    setErr("");
    if (!query) return;
    start(async () => {
      const r = await applyLeave(query);
      if (r.ok) { toast(r.msg!); onClose(); } else setErr(r.error);
    });
  };

  return (
    <Modal title={t("Apply for leave")} eyebrow={t("New request")} onClose={onClose}
      footer={<>
        <span className="muted" style={{ fontSize: 13, display: "flex", gap: 8, alignItems: "center" }}><Icon n="shield" s={15} />{t("Sent to HR for approval")}</span>
        <div className="row" style={{ gap: 10 }}>
          <button className="btn btn-ghost" onClick={onClose}>{t("Cancel")}</button>
          <button className="btn btn-gold" onClick={submit} disabled={!lo || busy || !!pv?.error}><Icon n="ok" s={16} />{busy ? t("Sending…") : t("Apply leave")}</button>
        </div>
      </>}>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(320px, 1fr))", gap: 26 }}>
        <div className="col" style={{ gap: 20 }}>
          <div>
            <div className="field" style={{ marginBottom: 10 }}>{t("1 · Leave type")}</div>
            <div className="ltypes" role="radiogroup" aria-label={t("Leave type")}>
              {types.map((ty) => (
                <button key={ty.code} type="button" role="radio" aria-checked={type === ty.code} className={`ltype${type === ty.code ? " on" : ""}`} onClick={() => setType(ty.code)}>
                  <span className="tick"><Icon n="ok" s={12} /></span><b>{t(ty.name)}</b><small>{ty.available === null ? t("Unpaid") : t("{n} available", { n: ty.available })}</small>
                </button>
              ))}
            </div>
          </div>
          <div>
            <div className="field" style={{ marginBottom: 10 }}>{t("2 · Start and end date")} <span className="hint">{t("tap a start date, then an end date")}</span></div>
            <RangePicker from={range.from} to={range.to} onPick={(a, b) => setRange({ from: a, to: b })} today={today} holidays={holidays} weeklyOffs={weeklyOffs} />
          </div>
        </div>
        <div className="col" style={{ gap: 18 }}>
          <div className="grid2">
            <div className="field">{t("Start date")}<input readOnly value={lo ? fmtDL(lo) : t("Pick on calendar")} aria-label={t("Start date")} /></div>
            <div className="field">{t("End date")}<input readOnly value={hi ? fmtDL(hi) : "—"} aria-label={t("End date")} /></div>
          </div>
          {single && (
            <div className="field">{t("Duration")}
              <div className="seg" style={{ width: "100%" }} role="group" aria-label={t("Duration")}>
                {([["FULL", "Full day"], ["FIRST_HALF", "First half"], ["SECOND_HALF", "Second half"]] as [Part, string][]).map(([v, l]) => (
                  <button key={v} type="button" className={part === v ? "on" : undefined} aria-pressed={part === v} onClick={() => setPart(v)}>{t(l)}</button>
                ))}
              </div>
            </div>
          )}
          <label className="field">{t("3 · Reason")} <span className="hint">{t("optional")}</span><textarea value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} placeholder={t("e.g. Family function in Jaipur")} /></label>
          <div className="summary" aria-live="polite">
            <div><small>{t("Working days")}</small><b>{pv ? pv.days : 0}</b></div>
            <div><small>{t("Balance after")}</small><b>{pv?.balanceAfter ?? "—"}</b></div>
            <div><small>{t("Loss of pay")}</small><b style={{ color: pv?.lopDays ? "var(--bad)" : "var(--ok)" }}>{pv?.lopDays ? t("{n} d", { n: pv.lopDays }) : t("None")}</b></div>
          </div>
          {pv && pv.skipped.length > 0 && <p className="faint" style={{ margin: 0, fontSize: 12.5 }}>{t("Holidays skipped: {list}. Weekends are never counted.", { list: pv.skipped.map((s) => `${s.name} (${fmtD(s.date)})`).join(", ") })}</p>}
          {pv && pv.lopDays > 0 && type !== "LOP" && <p style={{ margin: 0, fontSize: 13, color: "var(--warn)", fontWeight: 600 }}>{t("You don't have enough {type} — {n} day(s) will be unpaid.", { type: t(tName).toLowerCase(), n: pv.lopDays })}</p>}
          {pv?.error && <p className="err" style={{ margin: 0 }}>{pv.error}</p>}
          {tried && !lo && <p className="err" style={{ margin: 0 }}>{t("Pick your dates on the calendar.")}</p>}
          {err && <p className="err" role="alert" style={{ margin: 0 }}>{err}</p>}
        </div>
      </div>
    </Modal>
  );
}
