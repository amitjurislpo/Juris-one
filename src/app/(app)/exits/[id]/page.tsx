import type { Metadata } from "next";
import Link from "next/link";
import { PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { fmtDY } from "@/lib/format";
import { getT } from "@/lib/i18n/server";
import { DEPT_LABEL, EXIT_KIND, EXIT_STATUS, isOpen, type ExitStatus } from "../status";
import { AddItemButton, ClearanceRow, ExitActions, NoticeButton, SettlementPanel, type SettlementView } from "./ExitDetailClient";

export const metadata: Metadata = { title: "Exit" };

interface Detail {
  id: string; kind: string; status: ExitStatus; reason: string; requestedLastDay: string | null; lastWorkingDay: string | null; noticeStartDate: string | null;
  noticeDays: number; noticePolicyDays: number; noticeReason: string | null; hrComment: string | null; endedReason: string | null;
  employeeId: string; employeeCode: string; employeeName: string; designation: string; department: string | null; dateOfJoining: string; initiatedBy: string | null;
  clearance: { done: number; total: number }; settlementStatus: "DRAFT" | "APPROVED" | null; canManage: boolean;
  clearanceItems: { id: string; dept: string; title: string; cleared: boolean; remarks: string | null; clearedAt: string | null; clearedBy: string | null }[];
}

/** One exit: details and notice (HR), the clearance checklist, and the settlement (Finance and the owner only). */
export default async function ExitPage(props: PageProps<"/exits/[id]">) {
  const u = await requirePage("exits");
  const t = await getT();
  const { id } = await props.params;
  const d = await apiData<Detail>(`/api/exits/${encodeURIComponent(id)}`);
  const st = EXIT_STATUS[d.status];
  // amounts are fetched only for people allowed to see them; HR never asks
  const seesAmounts = u.perms.has("pay.view") || u.perms.has("pay.close") || u.perms.has("struct.manage") || u.roleKey === "owner";
  const settlement = seesAmounts ? await apiData<SettlementView>(`/api/exits/${encodeURIComponent(id)}/settlement`) : null;
  const canClearFinance = u.perms.has("pay.view");
  const clearing = ["NOTICE", "CLEARANCE"].includes(d.status);

  return (
    <div className="col">
      <PageHeader eyebrow={<><Link href="/exits" className="linkish">{t("Exits")}</Link> / {d.employeeCode}</>} title={d.employeeName}
        sub={<><span className={`chip ${st.cls}`}>{t(st.text)}</span>{" "}{d.designation}{d.department ? ` · ${d.department}` : ""} · {t("joined {date}", { date: fmtDY(d.dateOfJoining) })}</>}>
        {d.canManage && isOpen(d.status) && ["RESIGNED", "INITIATED", "NOTICE"].includes(d.status) && <NoticeButton id={d.id} lastWorkingDay={d.lastWorkingDay ?? d.requestedLastDay ?? ""} noticeDays={d.noticeDays} policyDays={d.noticePolicyDays} />}
        {d.canManage && <ExitActions id={d.id} status={d.status} name={d.employeeName} hasLastDay={!!d.lastWorkingDay} />}
      </PageHeader>

      <div className="grid2 enter" style={{ ["--i" as string]: 1, alignItems: "start" }}>
        <section className="card" style={{ padding: 18 }}>
          <div className="card-h"><h3>{EXIT_KIND[d.kind] ? t(EXIT_KIND[d.kind]) : d.kind}</h3></div>
          <div className="kv"><span className="muted">{t("Reason")}</span><b style={{ textAlign: "right", whiteSpace: "pre-wrap" }}>{d.reason || "—"}</b></div>
          {d.requestedLastDay && <div className="kv"><span className="muted">{t("Requested last day")}</span><b>{fmtDY(d.requestedLastDay)}</b></div>}
          <div className="kv"><span className="muted">{t("Last working day")}</span><b>{d.lastWorkingDay ? fmtDY(d.lastWorkingDay) : t("Not set")}</b></div>
          <div className="kv"><span className="muted">{t("Notice starts")}</span><b>{d.noticeStartDate ? fmtDY(d.noticeStartDate) : "—"}</b></div>
          <div className="kv"><span className="muted">{t("Notice period")}</span><b>{t("{n} days", { n: d.noticeDays })}{d.noticeDays < d.noticePolicyDays ? ` (${t("policy {n}", { n: d.noticePolicyDays })})` : ""}</b></div>
          {d.noticeReason && <div className="kv"><span className="muted">{t("Shortened because")}</span><b style={{ textAlign: "right" }}>{d.noticeReason}</b></div>}
          {d.hrComment && <div className="kv"><span className="muted">{t("HR note")}</span><b style={{ textAlign: "right" }}>{d.hrComment}</b></div>}
          {d.initiatedBy && d.kind !== "RESIGNATION" && <div className="kv"><span className="muted">{t("Started by")}</span><b>{d.initiatedBy}</b></div>}
          {d.endedReason && <div className="kv"><span className="muted">{t("Ended because")}</span><b style={{ textAlign: "right" }}>{d.endedReason}</b></div>}
        </section>

        <section className="card" style={{ padding: 18 }}>
          <div className="card-h"><h3>{t("Clearance")}</h3><small className="muted">{t("{done}/{total} cleared", { done: d.clearance.done, total: d.clearance.total })}</small>{d.canManage && isOpen(d.status) && d.status !== "SETTLED" && <AddItemButton id={d.id} />}</div>
          {d.clearanceItems.map((c) => (
            <ClearanceRow key={c.id} exitId={d.id} item={{ ...c, deptLabel: DEPT_LABEL[c.dept] ? t(DEPT_LABEL[c.dept]) : c.dept }} editable={clearing && (d.canManage || (canClearFinance && c.dept === "FINANCE"))} />
          ))}
          {!clearing && isOpen(d.status) && d.status !== "FNF_PENDING" && d.status !== "SETTLED" && <p className="faint" style={{ fontSize: 12, margin: "10px 0 0" }}>{t("Items can be cleared once the exit is accepted.")}</p>}
        </section>
      </div>

      {settlement && (
        <section className="card enter" style={{ ["--i" as string]: 2, padding: 18 }}>
          <SettlementPanel exitId={d.id} s={settlement} />
        </section>
      )}
    </div>
  );
}
