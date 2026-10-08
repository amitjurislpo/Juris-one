"use client";

import { useEffect, useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { ClearFilters, SortTh } from "@/components/table/Table";
import { ConfirmDialog, Drawer } from "@/components/ui/Modal";
import { avc, fmtDY, inr, initials } from "@/lib/format";
import { EmployeeForm } from "./EmployeeForm";
import { getProfile, markExited, resendInvite, revealContact, toggleAccess, type Profile } from "./actions";

export interface PersonRow {
  id: string;
  code: string;
  name: string;
  title: string;
  dept: string;
  manager: string;
  loc: string;
  india: boolean;
  role: string;
  status: string;
  access: boolean;
  doj: string;
  canToggle: boolean;
}

const STATUS_LABEL: Record<string, string> = { ACTIVE: "Active", PROBATION: "Probation", ONBOARDING: "Onboarding", EXITED: "Past employee" };
export const stChip = (s: string) => (s === "ACTIVE" ? "ok" : s === "EXITED" ? "neutral" : s === "ONBOARDING" ? "info" : "warn");

function AccessSwitch({ id, name, on, disabled, onDone }: { id: string; name: string; on: boolean; disabled: boolean; onDone?: (v: boolean) => void }) {
  const toast = useToast();
  const [busy, start] = useTransition();
  const [val, setVal] = useState(on);
  const [lastOn, setLastOn] = useState(on);
  if (lastOn !== on) { setLastOn(on); setVal(on); }
  return (
    <span style={{ display: "inline-flex", alignItems: "center", whiteSpace: "nowrap" }}>
      <button className="switch" role="switch" aria-checked={val} disabled={disabled || busy} aria-label={`${val ? "Disable" : "Enable"} portal access for ${name}`}
        onClick={() => start(async () => {
          const next = !val;
          setVal(next);
          const r = await toggleAccess(id, next);
          if (r.ok) { toast(r.msg!, next ? "ok" : "bad"); onDone?.(next); }
          else { setVal(!next); toast(r.error, "bad"); }
        })}><i></i></button>
      <span className="sw-l">{val ? "Enabled" : "Disabled"}</span>
    </span>
  );
}

export function PeopleTable({ rows }: { rows: PersonRow[] }) {
  const [open, setOpen] = useState<string | null>(null);
  const [editId, setEditId] = useState<string | null>(null);
  return (
    <>
      <div className="table-wrap">
        <table className="t">
          <thead>
            <tr>
              <SortTh k="name" dflt="name">Employee</SortTh>
              <SortTh k="dept" dflt="name">Department</SortTh>
              <SortTh k="manager" dflt="name">Reports to</SortTh>
              <SortTh k="loc" dflt="name">Location</SortTh>
              <SortTh k="role" dflt="name">Role</SortTh>
              <SortTh k="status" dflt="name">Status</SortTh>
              <SortTh k="access" dflt="name">Portal access</SortTh>
            </tr>
          </thead>
          <tbody>
            {rows.map((e, i) => (
              <tr key={e.id} onClick={() => setOpen(e.id)} style={{ cursor: "pointer", animationDelay: `${i * 30}ms` }} className={e.status === "EXITED" ? "dim" : undefined}>
                <td>
                  <div className="person">
                    <span className={`avatar ${avc(initials(e.name))}`}>{initials(e.name)}</span>
                    <div>
                      <b><button className="linkish" style={{ color: "inherit", fontWeight: 600 }} onClick={(ev) => { ev.stopPropagation(); setOpen(e.id); }}>{e.name}</button></b>
                      <small>{e.code} · {e.title}</small>
                    </div>
                  </div>
                </td>
                <td>{e.dept}</td>
                <td className="muted">{e.manager}</td>
                <td><span className={`chip nodot ${e.india ? "gold" : "info"}`}>{e.loc}</span></td>
                <td className="muted">{e.role}</td>
                <td><span className={`chip ${stChip(e.status)}`}>{STATUS_LABEL[e.status]}</span></td>
                <td onClick={(ev) => ev.stopPropagation()}><AccessSwitch id={e.id} name={e.name} on={e.access} disabled={!e.canToggle} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {!rows.length && <div className="empty"><b>No one matches</b><span className="muted">Try a different search or filter.</span><ClearFilters /></div>}
      {open && <ProfileDrawer id={open} onClose={() => setOpen(null)} onEdit={(id) => { setOpen(null); setEditId(id); }} />}
      {editId && <EmployeeForm editId={editId} onClose={() => setEditId(null)} />}
    </>
  );
}

export function AddEmployeeButton() {
  const [adding, setAdding] = useState(false);
  return (
    <>
      <button className="btn btn-gold btn-lg" onClick={() => setAdding(true)}><Icon n="plus" s={16} />Add employee</button>
      {adding && <EmployeeForm onClose={() => setAdding(false)} />}
    </>
  );
}

function ProfileDrawer({ id, onClose, onEdit }: { id: string; onClose: () => void; onEdit: (id: string) => void }) {
  const toast = useToast();
  const [p, setP] = useState<Profile | null>(null);
  const [err, setErr] = useState("");
  const [contact, setContact] = useState<{ phone: string; address: string } | null>(null);
  const [exitOpen, setExitOpen] = useState(false);
  const [exitDate, setExitDate] = useState(() => new Date().toLocaleDateString("en-CA"));
  const [busy, start] = useTransition();

  useEffect(() => {
    let alive = true;
    getProfile(id).then((r) => {
      if (!alive) return;
      if (r.ok) setP(r.data!);
      else setErr(r.error);
    });
    return () => { alive = false; };
  }, [id]);

  const kv = (k: string, v: React.ReactNode, mono = false) => <div className="kv"><span className="muted">{k}</span><b className={mono ? "num" : undefined}>{v}</b></div>;

  return (
    <Drawer label="Employee profile" onClose={onClose} width={520}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
        <span className={`avatar lg ${p ? avc(initials(p.name)) : ""}`}>{p ? initials(p.name) : ""}</span>
        <button className="icon-btn" onClick={onClose} aria-label="Close"><Icon n="x" s={16} /></button>
      </div>
      {err && <div className="err">{err}</div>}
      {!p && !err && <div className="faint">Loading…</div>}
      {p && (
        <>
          <div>
            <span className="eyebrow">{p.code} · {STATUS_LABEL[p.status]} · {p.role}</span>
            <h2 className="display" style={{ margin: "6px 0 2px", fontSize: 32 }}>{p.name}</h2>
            <span className="muted">{p.designation} · {p.department}</span>
          </div>
          <div className="access-row">
            <div><b>Portal access</b><div className="faint" style={{ fontSize: 12.5 }}>{p.status === "EXITED" ? "Removed on exit" : p.access ? (p.invitePending ? "Invite sent — password not set yet" : "Can sign in to JurisOne") : "Sign-in is blocked"}</div></div>
            <AccessSwitch id={p.id} name={p.name} on={p.access} disabled={!p.canToggle} onDone={(v) => setP({ ...p, access: v })} />
          </div>
          <div>
            <div className="eyebrow" style={{ margin: "4px 0 4px" }}>Personal</div>
            {kv("Father’s name", p.father)}
            {kv("Official email", p.email)}
            {kv("Phone", contact?.phone ?? p.phone, true)}
            {kv("Address", contact?.address ?? p.address)}
            {p.canEdit && !contact && (
              <button className="linkish" style={{ fontSize: 12.5, marginTop: 6 }} disabled={busy}
                onClick={() => start(async () => { const r = await revealContact(p.id); if (r.ok) setContact(r.data!); else toast(r.error, "bad"); })}>
                Show full phone & address (logged)
              </button>
            )}
            {p.india && (
              <>
                <div className="eyebrow" style={{ margin: "14px 0 4px" }}>Statutory</div>
                {kv("PAN", p.pan, true)}
                {kv("Aadhaar", p.aadhaar, true)}
                {kv("UAN (PF)", p.uan, true)}
              </>
            )}
            <div className="eyebrow" style={{ margin: "14px 0 4px" }}>Job</div>
            {kv("Reporting manager", p.manager)}
            {kv("Direct reports", p.reports.length ? p.reports.join(", ") : "—")}
            {kv("Date of joining", fmtDY(p.doj))}
            {p.exit && kv("Last working day", fmtDY(p.exit))}
            {kv("Location · shift", `${p.location} · ${p.shift}`)}
            {p.gross !== null && kv("Monthly gross", p.gross ? inr(p.gross) : "Not set", true)}
          </div>
          {p.gross === null && (
            <div style={{ display: "flex", gap: 12, alignItems: "center", padding: 14, borderRadius: 12, border: "1px dashed var(--line2)", background: "var(--sunken)" }}>
              <Icon n="lock" s={20} />
              <div><b style={{ fontWeight: 600 }}>Compensation</b><div className="muted" style={{ fontSize: 13 }}>Salary and tax details are visible to Finance only.</div></div>
            </div>
          )}
          {p.canEdit && (
            <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
              <button className="btn btn-gold" onClick={() => onEdit(p.id)}><Icon n="edit" s={15} />Edit details</button>
              {p.invitePending && p.access && (
                <button className="btn" disabled={busy} onClick={() => start(async () => { const r = await resendInvite(p.id); toast(r.ok ? r.msg! : r.error, r.ok ? "ok" : "bad"); })}>Resend invite</button>
              )}
              <button className="btn btn-danger" onClick={() => setExitOpen(true)}>Mark as past employee</button>
            </div>
          )}
        </>
      )}
      {exitOpen && p && (
        <ConfirmDialog title="Mark as past employee" danger confirm="Mark as exited" busy={busy} onClose={() => setExitOpen(false)}
          body={<><b>{p.name}</b> will lose portal access immediately and will be signed out everywhere.</>}
          onConfirm={() => start(async () => {
            const r = await markExited(p.id, exitDate);
            if (r.ok) { toast(r.msg!, "bad"); setExitOpen(false); onClose(); }
            else toast(r.error, "bad");
          })}>
          <label className="field" style={{ marginTop: 14 }}>Last working day *<input type="date" value={exitDate} min={p.doj} onChange={(e) => setExitDate(e.target.value)} /></label>
        </ConfirmDialog>
      )}
    </Drawer>
  );
}
