"use server";

import { revalidatePath } from "next/cache";
import { z } from "zod";
import { run, UserError, type ActionResult } from "@/lib/action";
import { audit } from "@/lib/audit";
import { assertPerm } from "@/lib/auth";
import { db } from "@/lib/db";
import { notify } from "@/lib/notify";
import { requestMeta } from "@/lib/request";
import { getSetting } from "@/lib/settings";
import { ITEM_LABEL, SECTION_OF_ITEM } from "@/lib/tax/catalogue";
import { reproject, taxPositions } from "@/lib/tax/position";

const refresh = () => ["/proofs", "/tax", "/declare", "/payroll"].forEach((p) => revalidatePath(p));

export interface ProofItem { id: string; key: string; label: string; sec: string; amount: number; status: string; proofName: string | null; hasProof: boolean; reason: string | null }
export interface ProofDetail { name: string; code: string; regime: "NEW" | "OLD"; metro: boolean; landlordPan: string | null; items: ProofItem[]; tdsNow: number | null; tdsIfAll: number | null; mine: boolean }

/** Drawer contents: items + TDS now vs. if everything pending is approved. */
export async function proofDetail(employeeId: string): Promise<ActionResult<ProofDetail>> {
  return run(async () => {
    const u = await assertPerm("proofs.verify");
    z.string().uuid().parse(employeeId);
    const { fy } = await getSetting("tax.policy");
    const [e, profile, items, pos] = await Promise.all([
      db.employee.findUniqueOrThrow({ where: { id: employeeId }, select: { fullName: true, code: true } }),
      db.taxProfile.findUnique({ where: { employeeId_fy: { employeeId, fy } } }),
      db.declarationItem.findMany({ where: { employeeId, fy, OR: [{ amount: { gt: 0 } }, { proofPath: { not: null } }] }, orderBy: { section: "asc" } }),
      taxPositions([employeeId]),
    ]);
    const row = pos.rows[0];
    let tdsNow: number | null = null, tdsIfAll: number | null = null;
    if (row) {
      // preview on the old regime — that's where proofs matter
      tdsNow = (await reproject(row, pos.month, { regime: "OLD", decl: "approved" })).monthly;
      tdsIfAll = (await reproject(row, pos.month, { regime: "OLD", decl: "submitted" })).monthly;
    }
    return {
      ok: true,
      data: {
        name: e.fullName, code: e.code, regime: profile?.regime ?? "NEW", metro: profile?.metro ?? false, landlordPan: profile?.landlordPan ?? null, tdsNow, tdsIfAll, mine: employeeId === u.id,
        items: items.map((i) => ({ id: i.id, key: i.itemKey, label: ITEM_LABEL[i.itemKey] ?? i.itemKey, sec: SECTION_OF_ITEM[i.itemKey] ?? i.section, amount: Number(i.amount), status: i.status, proofName: i.proofName, hasProof: !!i.proofPath, reason: i.rejectReason })),
      },
    };
  });
}

const decision = z.object({ id: z.string().uuid(), approve: z.boolean(), reason: z.string().trim().max(300).optional() });

/** Approve / reject one proof. Only approved amounts reduce TDS. */
export async function decideProof(i: z.input<typeof decision>): Promise<ActionResult> {
  return run(async () => {
    const u = await assertPerm("proofs.verify");
    const f = decision.parse(i);
    const item = await db.declarationItem.findUnique({ where: { id: f.id } });
    if (!item) throw new UserError("Item not found.");
    if (item.employeeId === u.id) throw new UserError("You can't verify your own proofs.");
    if (!f.approve && !f.reason) throw new UserError("Give a reason so the employee can fix it.");
    const { count } = await db.declarationItem.updateMany({
      where: { id: f.id, status: "PENDING" },
      data: { status: f.approve ? "APPROVED" : "REJECTED", decidedById: u.id, decidedAt: new Date(), rejectReason: f.approve ? null : f.reason },
    });
    if (!count) throw new UserError("This item was already decided.");
    await audit({ actorId: u.id, action: "proof.decision", entity: "declaration_item", entityId: f.id, before: { status: "PENDING" }, after: { status: f.approve ? "APPROVED" : "REJECTED", amount: Number(item.amount), reason: f.reason }, ip: (await requestMeta()).ip });
    await notify(item.employeeId, `${ITEM_LABEL[item.itemKey] ?? item.itemKey} proof ${f.approve ? "approved" : `rejected — ${f.reason}`}`, "/declare");
    refresh();
    return { ok: true, msg: `${ITEM_LABEL[item.itemKey]} ${f.approve ? "approved" : "rejected"}` };
  });
}

export async function approveAllPending(employeeId: string): Promise<ActionResult> {
  return run(async () => {
    const u = await assertPerm("proofs.verify");
    z.string().uuid().parse(employeeId);
    if (employeeId === u.id) throw new UserError("You can't verify your own proofs.");
    const { fy } = await getSetting("tax.policy");
    const pending = await db.declarationItem.findMany({ where: { employeeId, fy, status: "PENDING" } });
    if (!pending.length) throw new UserError("Nothing pending.");
    await db.$transaction(async (tx) => {
      await tx.declarationItem.updateMany({ where: { id: { in: pending.map((p) => p.id) }, status: "PENDING" }, data: { status: "APPROVED", decidedById: u.id, decidedAt: new Date(), rejectReason: null } });
      await audit({ actorId: u.id, action: "proof.decision", entity: "declaration_item", entityId: `${employeeId}:${fy}`, after: { approved: pending.map((p) => ({ key: p.itemKey, amount: Number(p.amount) })) }, ip: (await requestMeta()).ip }, tx);
    });
    await notify(employeeId, `${pending.length} investment proof${pending.length === 1 ? "" : "s"} approved — your TDS goes down from the next payroll`, "/declare");
    refresh();
    return { ok: true, msg: `${pending.length} proof${pending.length === 1 ? "" : "s"} approved` };
  });
}
