"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import type { PolicyToRead } from "@/lib/api-types";
import { fmtDY } from "@/lib/format";
import { useT } from "@/lib/i18n/client";
import { acknowledgePolicy } from "../policies/actions";
import { PolicyText } from "../my-policies/PolicyText";

/** One policy at a time: read it (and its PDF), tick the box, accept. */
export function AcknowledgeClient({ policy, left, name }: { policy: PolicyToRead; left: number; name: string }) {
  const toast = useToast();
  const t = useT();
  const router = useRouter();
  const [agree, setAgree] = useState(false);
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const accept = () => start(async () => {
    const r = await acknowledgePolicy(policy.id, policy.version);
    if (r.ok) { toast(r.msg!); setAgree(false); router.refresh(); } else { setErr(r.error); router.refresh(); }
  });

  return (
    <div role="dialog" aria-modal="true" aria-labelledby="ack-title" style={{ position: "fixed", inset: 0, zIndex: 150, background: "rgba(10,10,20,.55)", backdropFilter: "blur(3px)", display: "grid", placeItems: "center", padding: 16 }}>
      <section className="card" style={{ width: "min(760px, 100%)", maxHeight: "calc(100vh - 32px)", display: "flex", flexDirection: "column", boxShadow: "var(--shadow2)" }}>
        <header style={{ padding: "20px 24px 12px", borderBottom: "1px solid var(--line)" }}>
          <span className="eyebrow">{t("Before you continue")}{left > 1 ? ` · ${t("{n} policies to accept", { n: left })}` : ""}</span>
          <h1 id="ack-title" className="display" style={{ fontSize: 28, margin: "4px 0 2px" }}>{policy.title}</h1>
          <small className="muted">{t("Version {v} · published {date}", { v: policy.version, date: fmtDY(policy.publishedAt.slice(0, 10)) })}</small>
        </header>
        <div style={{ padding: "16px 24px", overflowY: "auto", flex: 1 }}>
          {policy.body ? <PolicyText body={policy.body} /> : <p className="muted">{t("This policy is in the attached PDF.")}</p>}
          {policy.fileName && (
            <a className="btn btn-sm" style={{ marginTop: 12 }} href={`/api/policies/${policy.id}/versions/${policy.version}/file`} target="_blank" rel="noopener">
              <Icon n="doc" s={14} />{t("Open {file}", { file: policy.fileName })}
            </a>
          )}
        </div>
        <footer style={{ padding: "14px 24px 20px", borderTop: "1px solid var(--line)", display: "flex", flexDirection: "column", gap: 12 }}>
          <label style={{ display: "flex", gap: 10, alignItems: "flex-start", fontSize: 13.5 }}>
            <input type="checkbox" checked={agree} onChange={(e) => { setAgree(e.target.checked); setErr(""); }} style={{ marginTop: 3 }} />
            <span>{t("I, {name}, have read and understood “{title}” (version {v}) and I accept it.", { name, title: policy.title, v: policy.version })}</span>
          </label>
          {err && <div className="err" role="alert">{err}</div>}
          <div style={{ display: "flex", gap: 8, justifyContent: "space-between", alignItems: "center", flexWrap: "wrap" }}>
            <small className="muted">{t("Your acceptance is recorded with the date, time and your device for the company's records.")}</small>
            <button className="btn btn-gold" disabled={!agree || busy} onClick={accept}><Icon n="check" s={15} />{busy ? t("Saving…") : t("I accept")}</button>
          </div>
        </footer>
      </section>
    </div>
  );
}
