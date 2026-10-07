"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { ConfirmDialog, Drawer } from "@/components/ui/Modal";
import { inr } from "@/lib/format";
import { approveAllPending, decideProof, proofDetail, type ProofDetail, type ProofItem } from "./actions";

const CHIP: Record<string, string> = { DRAFT: "neutral", PENDING: "warn", APPROVED: "ok", REJECTED: "bad" };
const LABEL: Record<string, string> = { DRAFT: "Draft", PENDING: "Pending", APPROVED: "Approved", REJECTED: "Rejected" };

export function ReviewButton({ employeeId, name, gold }: { employeeId: string; name: string; gold: boolean }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button className={`btn btn-sm${gold ? " btn-gold" : ""}`} onClick={() => setOpen(true)} aria-label={`Review ${name}`}>Review</button>
      {open && <ReviewDrawer employeeId={employeeId} onClose={() => setOpen(false)} />}
    </>
  );
}

function ReviewDrawer({ employeeId, onClose }: { employeeId: string; onClose: () => void }) {
  const router = useRouter();
  const toast = useToast();
  const [d, setD] = useState<ProofDetail | null>(null);
  const [busy, start] = useTransition();
  const [rejecting, setRejecting] = useState<ProofItem | null>(null);
  const [reason, setReason] = useState("");
  const load = useCallback(() => proofDetail(employeeId).then((r) => { if (r.ok) setD(r.data!); else toast(r.error, "bad"); }), [employeeId, toast]);
  useEffect(() => { void load(); }, [load]);

  const act = (p: Promise<{ ok: boolean; msg?: string; error?: string }>) => start(async () => {
    const r = await p;
    toast(r.ok ? r.msg! : r.error!, r.ok ? "ok" : "bad");
    await load();
    router.refresh();
  });

  return (
    <Drawer label="Review proofs" width={580} onClose={onClose}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
        <div>
          <span className="eyebrow">{d ? `${d.code} · ${d.regime === "OLD" ? "Old regime" : "New regime"}${d.metro ? " · metro" : ""}${d.landlordPan ? ` · landlord PAN ${d.landlordPan}` : ""}` : "Loading…"}</span>
          <h2 className="display" style={{ margin: "4px 0 0", fontSize: 30 }}>{d?.name ?? ""}</h2>
        </div>
        <button className="icon-btn" onClick={onClose} aria-label="Close"><Icon n="x" s={16} /></button>
      </div>
      {d && (
        <>
          <div className="summary">
            <div><small>TDS / month now</small><b>{d.tdsNow === null ? "—" : inr(d.tdsNow * 100)}</b></div>
            <div><small>If all approved</small><b style={{ color: "var(--ok)" }}>{d.tdsIfAll === null ? "—" : inr(d.tdsIfAll * 100)}</b></div>
          </div>
          {d.regime === "NEW" && <p className="faint" style={{ margin: 0, fontSize: 12.5 }}>This employee is on the new regime — proofs only count if they switch to the old regime. The preview assumes the old regime.</p>}
          {d.mine && <p className="err" style={{ margin: 0 }}>These are your own declarations — someone else in Finance must verify them.</p>}
          <div className="list">
            {d.items.map((x) => (
              <div className="it" key={x.id} style={{ flexWrap: "wrap" }}>
                <span className="chip gold nodot" style={{ minWidth: 76, justifyContent: "center" }}>{x.sec}</span>
                <div style={{ flex: "1 1 180px", minWidth: 0 }}>
                  <b style={{ fontWeight: 500 }}>{x.label}</b>
                  <div className="faint" style={{ fontSize: 12, display: "flex", gap: 6, alignItems: "center" }}>
                    <Icon n="doc" s={12} />{x.hasProof ? <a className="linkish" style={{ fontSize: 12 }} href={`/api/declarations/proof/${x.id}`} target="_blank" rel="noopener">{x.proofName ?? "Open proof"}</a> : "No proof attached"}
                  </div>
                  {x.reason && <div style={{ fontSize: 12, color: "var(--bad)" }}>Rejected: {x.reason}</div>}
                </div>
                <b className="num">{inr(x.amount)}</b>
                {x.status === "PENDING" && !d.mine ? (
                  <>
                    <button className="btn btn-sm btn-danger" disabled={busy} onClick={() => { setReason(""); setRejecting(x); }}>Reject</button>
                    <button className="btn btn-sm btn-gold" disabled={busy} onClick={() => act(decideProof({ id: x.id, approve: true }))} aria-label={`Approve ${x.label}`}>Approve</button>
                  </>
                ) : (
                  <span className={`chip ${CHIP[x.status]}`}>{LABEL[x.status]}</span>
                )}
              </div>
            ))}
            {!d.items.length && <div className="faint">Nothing declared.</div>}
          </div>
          <button className="btn btn-gold" disabled={busy || d.mine || !d.items.some((x) => x.status === "PENDING")} onClick={() => act(approveAllPending(employeeId))}><Icon n="ok" s={15} />Approve all pending</button>
        </>
      )}
      {rejecting && (
        <ConfirmDialog title="Reject proof" danger confirm="Reject" busy={busy} onClose={() => setRejecting(null)} body={<>Reject <b>{rejecting.label}</b> ({inr(rejecting.amount)})? The employee can fix and resubmit.</>}
          onConfirm={() => { act(decideProof({ id: rejecting.id, approve: false, reason })); setRejecting(null); }}>
          <label className="field" style={{ marginTop: 14 }}>Reason *<input value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} placeholder="e.g. Receipt is for last year" autoFocus /></label>
        </ConfirmDialog>
      )}
    </Drawer>
  );
}
