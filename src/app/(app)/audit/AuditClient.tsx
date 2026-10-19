"use client";

import { useState } from "react";
import { Icon } from "@/components/Icon";
import { Drawer } from "@/components/ui/Modal";
import { useT } from "@/lib/i18n/client";
import type { AuditRow as Row } from "@/lib/audit-labels";

const show = (v: unknown) => (v === undefined || v === null ? "—" : typeof v === "object" ? JSON.stringify(v) : String(v));

/** before → after, one line per field; changed fields are highlighted. */
function Changes({ before, after }: { before: unknown; after: unknown }) {
  const t = useT();
  const obj = (v: unknown) => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : v == null ? {} : { value: v });
  const b = obj(before), a = obj(after);
  const keys = [...new Set([...Object.keys(b), ...Object.keys(a)])];
  if (!keys.length) return <p className="faint" style={{ margin: 0, fontSize: 13 }}>{t("No field changes recorded for this event.")}</p>;
  return (
    <div className="table-wrap">
      <table className="t audit-diff">
        <thead><tr><th>{t("Field")}</th><th>{t("Before")}</th><th>{t("After")}</th></tr></thead>
        <tbody>
          {keys.map((k) => {
            const x = show(b[k]), y = show(a[k]);
            return (
              <tr key={k} className={x !== y ? "changed" : undefined}>
                <td className="muted">{k}</td>
                <td className="mono">{x}</td>
                <td className="mono">{y}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

export function AuditRow({ row, i, when, label, children }: { row: Row; i: number; when: string; label: string; children: React.ReactNode }) {
  const [open, setOpen] = useState(false);
  const t = useT();
  return (
    <>
      <tr onClick={() => setOpen(true)} onKeyDown={(e) => (e.key === "Enter" || e.key === " ") && (e.preventDefault(), setOpen(true))} tabIndex={0}
        style={{ cursor: "pointer", animationDelay: `${Math.min(i, 20) * 25}ms` }} aria-label={t("{label} by {actor}, {when}", { label, actor: row.actor, when })}>
        {children}
      </tr>
      {open && (
        <Drawer label={t("Audit entry")} width={560} onClose={() => setOpen(false)}>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
            <div><span className="eyebrow">{when}</span><h2 className="display" style={{ margin: "4px 0 0", fontSize: 28 }}>{label}</h2></div>
            <button className="icon-btn" onClick={() => setOpen(false)} aria-label={t("Close")}><Icon n="x" s={16} /></button>
          </div>
          <div>
            <div className="kv"><span className="muted">{t("Who")}</span><b>{row.actor}{row.actorCode ? ` · ${row.actorCode}` : ""}</b></div>
            <div className="kv"><span className="muted">{t("What")}</span><b className="mono" style={{ fontSize: 12.5 }}>{row.action}</b></div>
            <div className="kv"><span className="muted">{t("Record")}</span><b>{row.subject ?? row.entity.replace(/_/g, " ")}{row.entityId && !row.subject ? <span className="faint mono" style={{ fontSize: 11.5, display: "block" }}>{row.entityId}</span> : null}</b></div>
            <div className="kv"><span className="muted">{t("IP address")}</span><b className="mono">{row.ip ?? "—"}</b></div>
          </div>
          <div>
            <div className="eyebrow" style={{ marginBottom: 8 }}>{t("Before → after")}</div>
            {row.redacted ? (
              <div style={{ display: "flex", gap: 12, alignItems: "center", padding: 14, borderRadius: 12, border: "1px dashed var(--line2)", background: "var(--sunken)" }}>
                <Icon n="lock" s={20} />
                <div><b style={{ fontWeight: 600 }}>{t("Salary & tax details hidden")}</b><div className="muted" style={{ fontSize: 13 }}>{t("Only roles with Finance permissions can see the amounts in this entry.")}</div></div>
              </div>
            ) : <Changes before={row.before} after={row.after} />}
          </div>
        </Drawer>
      )}
    </>
  );
}
