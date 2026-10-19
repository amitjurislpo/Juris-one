"use client";

import { useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { PageHeader } from "@/components/PageHeader";
import { ConfirmDialog, Modal } from "@/components/ui/Modal";
import { useToast } from "@/components/shell/Toasts";
import { useT } from "@/lib/i18n/client";
import { avc, initials } from "@/lib/format";
import { PERMISSION_GROUPS, PERMISSION_LABEL } from "@/lib/permissions";
import { createRole, deleteRole, setAssignable, setPermission } from "./actions";

export interface RoleView {
  id: string;
  key: string;
  name: string;
  builtin: boolean;
  locked: boolean;
  perms: string[];
  assign: string[];
  members: { id: string; name: string }[];
  totalEver: number;
}

export function RolesClient({ roles, initialSel }: { roles: RoleView[]; initialSel: string }) {
  const toast = useToast();
  const t = useT();
  const [sel, setSel] = useState(initialSel);
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState("");
  const [from, setFrom] = useState(roles.find((r) => r.key === "employee")?.id ?? roles[0].id);
  const [tried, setTried] = useState(false);
  const [busy, start] = useTransition();
  const [confirmDel, setConfirmDel] = useState(false);

  const role = roles.find((r) => r.id === sel) ?? roles[0];
  const locked = role.locked;

  const act = (p: Promise<{ ok: boolean; msg?: string; error?: string }>, bad = false) =>
    start(async () => {
      const r = await p;
      if (r.ok) toast(r.msg ?? t("Saved"), bad ? "bad" : "ok");
      else toast(r.error ?? t("Failed"), "bad");
    });

  return (
    <div className="col">
      <PageHeader eyebrow={t("Administration")} title={t("Roles &")} em={t("permissions")} sub={t("A person's role decides what they see after signing in. Changes apply on their next click.")}>
        <button className="btn btn-gold btn-lg" onClick={() => setAdding(true)}><Icon n="plus" s={16} />{t("New role")}</button>
      </PageHeader>
      <div className="role-grid">
        <div className="role-list enter" style={{ ["--i" as string]: 1 }}>
          {roles.map((r) => (
            <button key={r.id} type="button" className={`role-pick${sel === r.id ? " on" : ""}`} onClick={() => setSel(r.id)} aria-pressed={sel === r.id}>
              <span className="kpi-ico" style={{ background: sel === r.id ? "var(--accent)" : "var(--accentsoft)", color: sel === r.id ? "#fff" : "var(--accent)" }}><Icon n="key" s={15} /></span>
              <span style={{ flex: 1, minWidth: 0, textAlign: "left" }}>
                <b>{r.name}</b>
                <small>{t(r.members.length === 1 ? "{n} person · {p} permissions" : "{n} people · {p} permissions", { n: r.members.length, p: r.perms.length })}</small>
              </span>
              {!r.builtin && <span className="chip info nodot">{t("Custom")}</span>}
            </button>
          ))}
        </div>
        <section className="card pad enter" style={{ ["--i" as string]: 2, minWidth: 0, opacity: busy ? 0.75 : 1 }} key={role.id}>
          <span className="spot"></span>
          <div className="card-h">
            <div>
              <h3 className="display" style={{ fontSize: 28, margin: 0 }}>{role.name}</h3>
              <span className="faint" style={{ fontSize: 12.5 }}>{locked ? t("Full access · cannot be changed") : t("Toggle what this role can do")}</span>
            </div>
            {!role.builtin && (
              <button className="btn btn-sm btn-danger" disabled={role.totalEver > 0 || busy} title={role.totalEver ? t("Move people (including past employees) to another role first") : ""}
                onClick={() => setConfirmDel(true)}>
                <Icon n="trash" s={14} />{t("Delete role")}
              </button>
            )}
          </div>
          {PERMISSION_GROUPS.map(([g, ps]) => (
            <div key={g} className="perm-g">
              <div className="eyebrow">{t(g)}</div>
              {ps.map((p) => {
                const has = role.perms.includes(p);
                return (
                  <div key={p} className={`perm${has ? "" : " off"}`}>
                    <span id={`perm-${p}`}>{t(PERMISSION_LABEL[p])}</span>
                    <button type="button" className="switch" role="switch" aria-checked={has} aria-labelledby={`perm-${p}`} disabled={locked || busy} onClick={() => act(setPermission(role.id, p, !has), has)}><i></i></button>
                  </div>
                );
              })}
            </div>
          ))}
          <div className="perm-g">
            <div className="eyebrow">{t("Can add employees with these roles")}</div>
            <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
              {roles.map((r) => {
                const on = role.assign.includes(r.id);
                return <button key={r.id} type="button" className={`pill${on ? " on" : ""}`} aria-pressed={on} disabled={locked || busy} onClick={() => act(setAssignable(role.id, r.id, !on))}>{r.name}</button>;
              })}
            </div>
            <p className="faint" style={{ margin: 0, fontSize: 12.5 }}>{t("e.g. HR Manager adds HR Executives; Finance Manager adds Finance Executives into Finance only.")}</p>
          </div>
          <div className="perm-g">
            <div className="eyebrow">{t("People with this role")}</div>
            <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
              {role.members.map((m) => (
                <span key={m.id} className="chip neutral nodot" style={{ height: 30, gap: 8 }}>
                  <span className={`avatar ${avc(initials(m.name))}`} style={{ width: 20, height: 20, fontSize: 9 }}>{initials(m.name)}</span>{m.name}
                </span>
              ))}
              {!role.members.length && <span className="faint">{t("No one yet — assign this role from People → Add employee.")}</span>}
            </div>
          </div>
        </section>
      </div>

      {confirmDel && (
        <ConfirmDialog title={t("Delete role")} danger confirm={t("Delete role")} busy={busy} onClose={() => setConfirmDel(false)}
          body={<>{t("Delete {name}? This can't be undone.", { name: role.name })}</>}
          onConfirm={() => { act(deleteRole(role.id), true); setConfirmDel(false); setSel(roles.find((r) => r.key === "employee")!.id); }} />
      )}

      {adding && (
        <Modal title={t("New role")} eyebrow={t("Administration")} onClose={() => setAdding(false)} size="sm"
          footer={<>
            <button className="btn btn-ghost" onClick={() => setAdding(false)}>{t("Cancel")}</button>
            <button className="btn btn-gold" disabled={busy} onClick={() => {
              setTried(true);
              if (!name.trim()) return;
              start(async () => {
                const r = await createRole({ name, fromRoleId: from });
                if (r.ok) { toast(r.msg!); setAdding(false); setName(""); setTried(false); if (r.data) setSel(r.data.id); }
                else toast(r.error, "bad");
              });
            }}>{t("Create role")}</button>
          </>}>
          <div className="col" style={{ gap: 14 }}>
            <label className="field">{t("Role name *")}<input value={name} onChange={(e) => setName(e.target.value)} placeholder={t("e.g. Payroll Auditor")} maxLength={60} autoFocus />{tried && !name.trim() && <span className="err">{t("Name is required")}</span>}</label>
            <label className="field">{t("Start from")}<select value={from} onChange={(e) => setFrom(e.target.value)}>{roles.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}</select><span className="hint">{t("Copies its permissions, except Administration.")}</span></label>
          </div>
        </Modal>
      )}
    </div>
  );
}
