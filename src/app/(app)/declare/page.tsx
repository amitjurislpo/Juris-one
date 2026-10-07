import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { db } from "@/lib/db";
import { fmtDY, todayIn } from "@/lib/format";
import { getSetting } from "@/lib/settings";
import { reproject, taxPositions } from "@/lib/tax/position";
import { DeclareClient, type ItemView } from "./DeclareClient";

export const metadata: Metadata = { title: "Tax declarations" };

export default async function DeclarePage() {
  const u = await requirePage("declare");
  const policy = await getSetting("tax.policy");
  if (u.region !== "IN") {
    return <div className="col"><PageHeader eyebrow={`Tax year ${policy.fy}`} title="Tax" em="declarations" /><div className="empty"><b className="display">Not applicable</b><span className="muted">Tax declarations are for the India payroll. US team taxes are handled by the US payroll provider.</span></div></div>;
  }
  const today = todayIn("Asia/Kolkata");
  const [profile, items, pos] = await Promise.all([
    db.taxProfile.findUnique({ where: { employeeId_fy: { employeeId: u.id, fy: policy.fy } } }),
    db.declarationItem.findMany({ where: { employeeId: u.id, fy: policy.fy } }),
    taxPositions([u.id]),
  ]);
  const row = pos.rows[0];
  if (!row) {
    return <div className="col"><PageHeader eyebrow={`Tax year ${policy.fy}`} title="Tax" em="declarations" /><div className="empty"><b className="display">No salary structure yet</b><span className="muted">Finance hasn&apos;t set up your salary structure yet — tax estimates appear once it&apos;s in place.</span></div></div>;
  }
  // live comparison uses everything you've entered (drafts too); TDS itself uses approved proofs only
  const what = await reproject(row, pos.month, { decl: "all" });
  const regime = profile?.regime ?? "NEW";
  const view: ItemView[] = items.map((i) => ({ id: i.id, key: i.itemKey, amount: Number(i.amount) / 100, status: i.status, proofName: i.proofName, reason: i.rejectReason }));
  return (
    <div className="col">
      <PageHeader eyebrow={`Tax year ${policy.fy}`} title="Tax" em="declarations" sub="Choose your tax regime. On the old regime, declare investments and upload proofs — Finance verifies them before they reduce your TDS." />
      <DeclareClient
        regime={regime}
        overridden={!!profile?.regimeOverridden}
        canChangeRegime={today <= policy.regimeChangeUntil}
        windowOpen={today >= policy.proofWindowFrom && today <= policy.proofWindowUntil}
        windowText={`Declarations and proofs are open until ${fmtDY(policy.proofWindowUntil)}; the regime can be changed until ${fmtDY(policy.regimeChangeUntil)}.`}
        newTax={what.newRegime.total}
        oldTax={what.oldRegime.total}
        monthlyTds={row.tax.monthly}
        epf={what.pfAnnual}
        profile={{ metro: profile?.metro ?? false, parentsSenior: profile?.parentsSenior ?? false, landlordPan: profile?.landlordPan ?? "" }}
        items={view}
      />
    </div>
  );
}
