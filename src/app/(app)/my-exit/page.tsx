import type { Metadata } from "next";
import { Icon } from "@/components/Icon";
import { PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { fmtDY, todayIn } from "@/lib/format";
import { DEPT_LABEL, EXIT_KIND, EXIT_STATUS, type ExitStatus } from "../exits/status";
import { ResignButton, WithdrawButton } from "./MyExitClient";

export const metadata: Metadata = { title: "My exit" };

interface Mine {
  noticeDays: number; canResign: boolean; canWithdraw: boolean;
  exit: null | {
    id: string; kind: string; status: ExitStatus; reason: string; requestedLastDay: string | null; lastWorkingDay: string | null; noticeStartDate: string | null;
    noticeDays: number; noticePolicyDays: number; noticeReason: string | null; hrComment: string | null; endedReason: string | null;
  };
  clearance: { id: string; dept: string; title: string; cleared: boolean; remarks: string | null }[];
}

const STEPS: [ExitStatus[], string][] = [
  [["RESIGNED", "INITIATED"], "Submitted"], [["NOTICE"], "Notice period"], [["CLEARANCE"], "Clearance"], [["FNF_PENDING"], "Full & final"], [["SETTLED", "CLOSED"], "Settled"],
];

/** The employee's own exit: resign, withdraw, and follow the status. Never shows any amount. */
export default async function MyExitPage() {
  const u = await requirePage("my-exit");
  const d = await apiData<Mine>("/api/exits/me");
  const x = d.exit;
  const today = todayIn(u.timeZone);
  const active = x ? STEPS.findIndex(([s]) => s.includes(x.status)) : -1;
  const ended = x && ["WITHDRAWN", "CANCELLED"].includes(x.status);

  return (
    <div className="col">
      <PageHeader eyebrow="My workspace" title="My" em="exit"
        sub="Resign, follow your notice period and clearance, and see when your full and final settlement is done. The settlement amounts come from Finance.">
        {d.canResign && <ResignButton noticeDays={d.noticeDays} today={today} />}
        {d.canWithdraw && <WithdrawButton />}
      </PageHeader>

      {!x && (
        <div className="empty enter" style={{ ["--i" as string]: 1 }}>
          <b className="display">You haven&apos;t resigned</b>
          <span className="muted">If you decide to leave, use “Resign”. Your notice period is {d.noticeDays} days unless HR agrees otherwise.</span>
        </div>
      )}

      {x && (
        <>
          <section className="card enter" style={{ ["--i" as string]: 1, padding: 18 }}>
            <div className="card-h">
              <h3>{EXIT_KIND[x.kind] ?? x.kind}</h3>
              <span className={`chip ${EXIT_STATUS[x.status].cls}`}>{EXIT_STATUS[x.status].text}</span>
            </div>
            {!ended && (
              <ol style={{ display: "flex", gap: 8, flexWrap: "wrap", listStyle: "none", padding: 0, margin: "4px 0 14px" }} aria-label="Progress">
                {STEPS.map(([, label], i) => (
                  <li key={label} className={`chip ${i < active ? "ok" : i === active ? "info" : "neutral"}`} aria-current={i === active ? "step" : undefined}>{label}</li>
                ))}
              </ol>
            )}
            <div className="kv"><span className="muted">Reason</span><b style={{ textAlign: "right", whiteSpace: "pre-wrap" }}>{x.reason || "—"}</b></div>
            {x.requestedLastDay && <div className="kv"><span className="muted">Requested last day</span><b>{fmtDY(x.requestedLastDay)}</b></div>}
            <div className="kv"><span className="muted">Last working day</span><b>{x.lastWorkingDay ? fmtDY(x.lastWorkingDay) : "HR will confirm"}</b></div>
            <div className="kv"><span className="muted">Notice period</span><b>{x.noticeDays} days{x.noticeDays < x.noticePolicyDays ? ` (shortened from ${x.noticePolicyDays})` : ""}</b></div>
            {x.noticeReason && <div className="kv"><span className="muted">Why the notice differs</span><b style={{ textAlign: "right" }}>{x.noticeReason}</b></div>}
            {x.hrComment && <div className="kv"><span className="muted">Note from HR</span><b style={{ textAlign: "right" }}>{x.hrComment}</b></div>}
            {x.endedReason && <div className="kv"><span className="muted">{x.status === "WITHDRAWN" ? "Your note" : "Why it was cancelled"}</span><b style={{ textAlign: "right" }}>{x.endedReason}</b></div>}
            {x.status === "CLOSED" && <p className="muted" style={{ margin: "10px 0 0" }}>Your exit is complete. Your portal access has been removed.</p>}
          </section>

          {!ended && (
            <section className="card enter" style={{ ["--i" as string]: 2, padding: 18 }}>
              <div className="card-h"><h3>Clearance</h3><small className="muted">{d.clearance.filter((c) => c.cleared).length}/{d.clearance.length} cleared</small></div>
              {d.clearance.map((c) => (
                <div key={c.id} className="kv">
                  <span><Icon n={c.cleared ? "check" : "clock"} s={14} /> {c.title} <small className="muted">· {DEPT_LABEL[c.dept] ?? c.dept}</small>{c.remarks && <><br /><small className="muted">{c.remarks}</small></>}</span>
                  <span className={`chip ${c.cleared ? "ok" : "neutral"}`}>{c.cleared ? "Cleared" : "Pending"}</span>
                </div>
              ))}
            </section>
          )}
        </>
      )}
    </div>
  );
}
