"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { ConfirmDialog, Modal } from "@/components/ui/Modal";
import type { AssetAssignment, AssetRow } from "@/lib/api-types";
import { ASSET_STATUS, ASSET_TYPES, CONDITIONS } from "@/lib/assets";
import { fmtDY } from "@/lib/format";
import { assignAsset, createAsset, deleteAsset, returnAsset, updateAsset, type AssetInput } from "./actions";

const EMPTY: AssetInput = { tag: "", type: "LAPTOP", make: "", model: "", serial: "", purchaseDate: "", status: "AVAILABLE", notes: "" };
type Person = { id: string; code: string; name: string };

/** Create / edit form for one asset. */
function AssetForm({ asset, onClose }: { asset?: AssetRow; onClose: () => void }) {
  const toast = useToast();
  const router = useRouter();
  const [f, setF] = useState<AssetInput>(asset ? {
    tag: asset.tag, type: asset.type, make: asset.make ?? "", model: asset.model ?? "", serial: asset.serial ?? "", purchaseDate: asset.purchaseDate ?? "",
    status: asset.status === "ASSIGNED" ? undefined : asset.status, notes: asset.notes ?? "",
  } : EMPTY);
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const set = (k: keyof AssetInput, v: string) => { setF({ ...f, [k]: v }); setErr(""); };
  const save = () => start(async () => {
    const r = asset ? await updateAsset(asset.id, f) : await createAsset(f);
    if (r.ok) { toast(r.msg ?? "Asset saved"); onClose(); router.refresh(); } else setErr(r.error);
  });
  return (
    <Modal title={asset ? `Edit ${asset.tag}` : "Add asset"} eyebrow="Asset register" onClose={onClose}
      footer={<><button className="btn btn-ghost" onClick={onClose}>Cancel</button><button className="btn btn-gold" disabled={busy} onClick={save}>{busy ? "Saving…" : "Save"}</button></>}>
      <div className="col" style={{ gap: 14 }}>
        <div className="grid2">
          <label className="field">Asset tag *<input value={f.tag} maxLength={40} autoFocus onChange={(e) => set("tag", e.target.value)} placeholder="e.g. JC-LT-014" /></label>
          <label className="field">Type<select value={f.type} onChange={(e) => set("type", e.target.value)}>{Object.entries(ASSET_TYPES).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
          <label className="field">Make<input value={f.make} maxLength={80} onChange={(e) => set("make", e.target.value)} placeholder="e.g. Dell" /></label>
          <label className="field">Model<input value={f.model} maxLength={80} onChange={(e) => set("model", e.target.value)} placeholder="e.g. Latitude 5440" /></label>
          <label className="field">Serial number<input value={f.serial} maxLength={80} onChange={(e) => set("serial", e.target.value)} /></label>
          <label className="field">Purchase date<input type="date" value={f.purchaseDate} onChange={(e) => set("purchaseDate", e.target.value)} /></label>
        </div>
        {asset?.status !== "ASSIGNED" && (
          <label className="field">Status
            <select value={f.status ?? "AVAILABLE"} onChange={(e) => set("status", e.target.value)}>
              <option value="AVAILABLE">Available</option><option value="IN_REPAIR">In repair</option><option value="RETIRED">Retired</option>
            </select>
          </label>
        )}
        <label className="field">Notes<textarea value={f.notes} rows={3} maxLength={500} onChange={(e) => set("notes", e.target.value)} /></label>
        {err && <div className="err" role="alert">{err}</div>}
      </div>
    </Modal>
  );
}

export function AddAssetButton() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button className="btn btn-gold btn-lg" onClick={() => setOpen(true)}><Icon n="plus" s={16} />Add asset</button>
      {open && <AssetForm onClose={() => setOpen(false)} />}
    </>
  );
}

function AssignDialog({ asset, people, today, onClose }: { asset: AssetRow; people: Person[]; today: string; onClose: () => void }) {
  const toast = useToast();
  const router = useRouter();
  const [f, setF] = useState({ employeeId: "", assignedOn: today, notes: "" });
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const save = () => start(async () => {
    const r = await assignAsset(asset.id, f);
    if (r.ok) { toast(r.msg ?? "Assigned"); onClose(); router.refresh(); } else setErr(r.error);
  });
  return (
    <Modal title={`Assign ${asset.tag}`} eyebrow="The employee is notified" size="sm" onClose={onClose}
      footer={<><button className="btn btn-ghost" onClick={onClose}>Cancel</button><button className="btn btn-gold" disabled={busy || !f.employeeId} onClick={save}>{busy ? "Assigning…" : "Assign"}</button></>}>
      <div className="col" style={{ gap: 14 }}>
        <label className="field">Employee *
          <select value={f.employeeId} onChange={(e) => { setF({ ...f, employeeId: e.target.value }); setErr(""); }}>
            <option value="">Choose…</option>
            {people.map((p) => <option key={p.id} value={p.id}>{p.name} ({p.code})</option>)}
          </select>
        </label>
        <label className="field">Assigned on<input type="date" value={f.assignedOn} onChange={(e) => setF({ ...f, assignedOn: e.target.value })} /></label>
        <label className="field">Notes <span className="hint">accessories handed over, condition…</span><textarea rows={3} maxLength={500} value={f.notes} onChange={(e) => setF({ ...f, notes: e.target.value })} /></label>
        {err && <div className="err" role="alert">{err}</div>}
      </div>
    </Modal>
  );
}

function ReturnDialog({ asset, today, onClose }: { asset: AssetRow; today: string; onClose: () => void }) {
  const toast = useToast();
  const router = useRouter();
  const [f, setF] = useState({ condition: "GOOD", returnedOn: today, notes: "" });
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const save = () => start(async () => {
    const r = await returnAsset(asset.id, f);
    if (r.ok) { toast(r.msg ?? "Returned"); onClose(); router.refresh(); } else setErr(r.error);
  });
  return (
    <Modal title={`Take back ${asset.tag}`} eyebrow={`From ${asset.holderName ?? "the employee"}`} size="sm" onClose={onClose}
      footer={<><button className="btn btn-ghost" onClick={onClose}>Cancel</button><button className="btn btn-gold" disabled={busy} onClick={save}>{busy ? "Saving…" : "Mark returned"}</button></>}>
      <div className="col" style={{ gap: 14 }}>
        <label className="field">Condition<select value={f.condition} onChange={(e) => setF({ ...f, condition: e.target.value })}>{Object.entries(CONDITIONS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
        <label className="field">Returned on<input type="date" value={f.returnedOn} onChange={(e) => { setF({ ...f, returnedOn: e.target.value }); setErr(""); }} /></label>
        <label className="field">Condition notes<textarea rows={3} maxLength={500} value={f.notes} onChange={(e) => setF({ ...f, notes: e.target.value })} /></label>
        {err && <div className="err" role="alert">{err}</div>}
      </div>
    </Modal>
  );
}

function HistoryDialog({ asset, onClose }: { asset: AssetRow; onClose: () => void }) {
  const [rows, setRows] = useState<AssetAssignment[] | null>(null);
  useEffect(() => {
    let live = true;
    fetch(`/api/assets/${asset.id}`).then((r) => r.json()).then((j) => { if (live) setRows(j.data?.history ?? []); }).catch(() => { if (live) setRows([]); });
    return () => { live = false; };
  }, [asset.id]);
  return (
    <Modal title={`${asset.tag} history`} eyebrow="Who held it" onClose={onClose} footer={<button className="btn" onClick={onClose}>Close</button>}>
      {rows === null ? <p className="muted">Loading…</p> : !rows.length ? <p className="muted">This asset has never been assigned.</p> : (
        <div className="table-wrap">
          <table className="t">
            <thead><tr><th>Employee</th><th>From</th><th>Returned</th><th>Condition</th></tr></thead>
            <tbody>
              {rows.map((h) => (
                <tr key={h.id}>
                  <td><b>{h.employeeName}</b><br /><small className="muted">{h.employeeCode}</small></td>
                  <td>{fmtDY(h.assignedOn)}{h.assignNotes && <><br /><small className="muted">{h.assignNotes}</small></>}</td>
                  <td>{h.returnedOn ? fmtDY(h.returnedOn) : <span className="chip info">Holding now</span>}</td>
                  <td>{h.returnCondition ? CONDITIONS[h.returnCondition].split(" ")[0] : "—"}{h.returnNotes && <><br /><small className="muted">{h.returnNotes}</small></>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Modal>
  );
}

type Dialog = { kind: "edit" | "assign" | "return" | "history" | "delete"; asset: AssetRow } | null;

export function AssetsTable({ rows, people, today }: { rows: AssetRow[]; people: Person[]; today: string }) {
  const toast = useToast();
  const router = useRouter();
  const [dlg, setDlg] = useState<Dialog>(null);
  const [filter, setFilter] = useState("ALL");
  const [q, setQ] = useState("");
  const [busy, start] = useTransition();
  const close = () => setDlg(null);
  const shown = rows.filter((r) => (filter === "ALL" || r.status === filter)
    && (!q.trim() || [r.tag, r.make, r.model, r.serial, r.holderName, r.holderCode].some((x) => x?.toLowerCase().includes(q.trim().toLowerCase()))));
  return (
    <>
      <div className="toolbar enter" style={{ ["--i" as string]: 1 }}>
        <div className="seg" role="tablist" aria-label="Status">
          {["ALL", "AVAILABLE", "ASSIGNED", "IN_REPAIR", "RETIRED"].map((s) => (
            <button key={s} role="tab" aria-selected={filter === s} className={filter === s ? "on" : ""} onClick={() => setFilter(s)}>{s === "ALL" ? "All" : ASSET_STATUS[s].text}</button>
          ))}
        </div>
        <input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search tag, model, person…" aria-label="Search assets" style={{ maxWidth: 260 }} />
        <span className="tcount">{shown.length} asset{shown.length === 1 ? "" : "s"}</span>
      </div>
      <section className="card enter" style={{ ["--i" as string]: 2 }}>
        <div className="table-wrap">
          <table className="t">
            <thead><tr><th>Asset</th><th>Tag</th><th>Status</th><th>Held by</th><th className="hide-sm">Serial</th><th className="hide-sm">Purchased</th><th></th></tr></thead>
            <tbody>
              {shown.map((r) => (
                <tr key={r.id}>
                  <td><div className="person"><span className="kpi-ico" style={{ width: 34, height: 34, background: "var(--accentsoft)", color: "var(--accent)" }}><Icon n="layers" s={16} /></span>
                    <div><b>{[r.make, r.model].filter(Boolean).join(" ") || ASSET_TYPES[r.type]}</b><small>{ASSET_TYPES[r.type] ?? r.type}</small></div></div></td>
                  <td className="num">{r.tag}</td>
                  <td><span className={`chip ${ASSET_STATUS[r.status].cls}`}>{ASSET_STATUS[r.status].text}</span></td>
                  <td>{r.holderName ? <><b>{r.holderName}</b><br /><small className="muted">since {fmtDY(r.heldSince!)}</small></> : <span className="muted">—</span>}</td>
                  <td className="muted hide-sm">{r.serial ?? "—"}</td>
                  <td className="muted hide-sm">{r.purchaseDate ? fmtDY(r.purchaseDate) : "—"}</td>
                  <td style={{ whiteSpace: "nowrap", textAlign: "right" }}>
                    {r.status === "AVAILABLE" && <button className="btn btn-sm btn-gold" onClick={() => setDlg({ kind: "assign", asset: r })}>Assign</button>}
                    {r.status === "ASSIGNED" && <button className="btn btn-sm" onClick={() => setDlg({ kind: "return", asset: r })}>Take back</button>}
                    {" "}<button className="btn btn-sm btn-ghost" onClick={() => setDlg({ kind: "history", asset: r })}>History</button>
                    <button className="btn btn-sm btn-ghost" aria-label={`Edit ${r.tag}`} onClick={() => setDlg({ kind: "edit", asset: r })}><Icon n="edit" s={14} /></button>
                    <button className="btn btn-sm btn-ghost" aria-label={`Delete ${r.tag}`} onClick={() => setDlg({ kind: "delete", asset: r })}><Icon n="trash" s={14} /></button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!shown.length && <div className="empty"><b className="display">{rows.length ? "No matching assets" : "No assets yet"}</b><span className="muted">{rows.length ? "Try another filter." : "Add your laptops, phones and other equipment to start tracking them."}</span></div>}
      </section>
      {dlg?.kind === "edit" && <AssetForm asset={dlg.asset} onClose={close} />}
      {dlg?.kind === "assign" && <AssignDialog asset={dlg.asset} people={people} today={today} onClose={close} />}
      {dlg?.kind === "return" && <ReturnDialog asset={dlg.asset} today={today} onClose={close} />}
      {dlg?.kind === "history" && <HistoryDialog asset={dlg.asset} onClose={close} />}
      {dlg?.kind === "delete" && (
        <ConfirmDialog title="Delete asset" danger confirm="Delete" busy={busy} onClose={close}
          body={<>Delete <b>{dlg.asset.tag}</b>? Only assets that were never assigned can be deleted — mark the others as retired instead.</>}
          onConfirm={() => start(async () => {
            const r = await deleteAsset(dlg.asset.id);
            toast(r.ok ? r.msg ?? "Deleted" : r.error, r.ok ? undefined : "bad");
            close();
            if (r.ok) router.refresh();
          })} />
      )}
    </>
  );
}
