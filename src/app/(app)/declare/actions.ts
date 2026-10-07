"use server";

import { revalidatePath } from "next/cache";
import { z } from "zod";
import { run, UserError, type ActionResult } from "@/lib/action";
import { audit } from "@/lib/audit";
import { assertPerm } from "@/lib/auth";
import { db } from "@/lib/db";
import { fmtDY, todayIn } from "@/lib/format";
import { PAN_RE } from "@/lib/people-rules";
import { requestMeta } from "@/lib/request";
import { getSetting } from "@/lib/settings";
import { DECLARABLE, ITEM_LABEL, ITEM_SECTION } from "@/lib/tax/catalogue";

const refresh = () => ["/declare", "/proofs", "/tax"].forEach((p) => revalidatePath(p));

async function me() {
  const u = await assertPerm("self");
  if (u.region !== "IN") throw new UserError("Tax declarations are for the India payroll.");
  const policy = await getSetting("tax.policy");
  const today = todayIn("Asia/Kolkata");
  const profile = await db.taxProfile.upsert({ where: { employeeId_fy: { employeeId: u.id, fy: policy.fy } }, create: { employeeId: u.id, fy: policy.fy }, update: {} });
  return { u, policy, today, profile };
}

function windowOpen(policy: { proofWindowFrom: string; proofWindowUntil: string }, today: string) {
  if (today < policy.proofWindowFrom) throw new UserError(`Declarations open on ${fmtDY(policy.proofWindowFrom)}.`);
  if (today > policy.proofWindowUntil) throw new UserError(`Declarations closed on ${fmtDY(policy.proofWindowUntil)}. Ask Finance for changes.`);
}

export async function setRegime(regime: "NEW" | "OLD"): Promise<ActionResult> {
  return run(async () => {
    const { u, policy, today, profile } = await me();
    z.enum(["NEW", "OLD"]).parse(regime);
    if (profile.regimeOverridden) throw new UserError("Finance has set your regime. Contact Finance to change it.");
    if (today > policy.regimeChangeUntil) throw new UserError(`The regime could be changed until ${fmtDY(policy.regimeChangeUntil)}.`);
    if (profile.regime === regime) return { ok: true };
    await db.taxProfile.update({ where: { employeeId_fy: { employeeId: u.id, fy: policy.fy } }, data: { regime } });
    await audit({ actorId: u.id, action: "tax.regime_change", entity: "tax_profile", entityId: `${u.id}:${policy.fy}`, before: { regime: profile.regime }, after: { regime, by: "employee" }, ip: (await requestMeta()).ip });
    refresh();
    return { ok: true, msg: `You're on the ${regime === "NEW" ? "new" : "old"} regime — TDS updates from the next payroll` };
  });
}

const profileSchema = z.object({ metro: z.boolean(), parentsSenior: z.boolean(), landlordPan: z.string().trim().toUpperCase().max(10) });

export async function saveProfile(i: z.input<typeof profileSchema>): Promise<ActionResult> {
  return run(async () => {
    const { u, policy, today } = await me();
    windowOpen(policy, today);
    const f = profileSchema.parse(i);
    if (f.landlordPan && !PAN_RE.test(f.landlordPan)) throw new UserError("Landlord PAN looks like ABCDE1234F.");
    await db.taxProfile.update({ where: { employeeId_fy: { employeeId: u.id, fy: policy.fy } }, data: { metro: f.metro, parentsSenior: f.parentsSenior, landlordPan: f.landlordPan || null } });
    refresh();
    return { ok: true };
  });
}

const itemSchema = z.object({ key: z.string().refine((k) => DECLARABLE.includes(k), "Unknown item"), amount: z.number().int("Whole rupees only").min(0).max(100_000_000) });

/** Saves a declared amount (₹). Changing an approved or rejected amount sends it back to Draft. */
export async function saveItem(i: z.input<typeof itemSchema>): Promise<ActionResult> {
  return run(async () => {
    const { u, policy, today } = await me();
    windowOpen(policy, today);
    const f = itemSchema.parse(i);
    const key = { employeeId_fy_itemKey: { employeeId: u.id, fy: policy.fy, itemKey: f.key } };
    const cur = await db.declarationItem.findUnique({ where: key });
    const amount = BigInt(f.amount * 100);
    if (cur && cur.amount === amount) return { ok: true };
    if (cur?.status === "PENDING") throw new UserError("This item is waiting for Finance — you can change it after they decide.");
    await db.declarationItem.upsert({
      where: key,
      create: { employeeId: u.id, fy: policy.fy, itemKey: f.key, section: ITEM_SECTION[f.key], amount, status: "DRAFT" },
      update: { amount, status: "DRAFT", decidedById: null, decidedAt: null, rejectReason: null },
    });
    if (cur && cur.status === "APPROVED") await audit({ actorId: u.id, action: "declaration.reopened", entity: "declaration_item", entityId: cur.id, before: { amount: Number(cur.amount), status: "APPROVED" }, after: { amount: Number(amount), status: "DRAFT" } });
    refresh();
    return { ok: true, msg: cur?.status === "APPROVED" ? `${ITEM_LABEL[f.key]} changed — back to draft until you resubmit` : undefined };
  });
}

/** Sends every draft with an amount for verification; each needs a proof. */
export async function submitForVerification(): Promise<ActionResult> {
  return run(async () => {
    const { u, policy, today, profile } = await me();
    windowOpen(policy, today);
    if (profile.regime !== "OLD") throw new UserError("Declarations only matter on the old regime.");
    const drafts = await db.declarationItem.findMany({ where: { employeeId: u.id, fy: policy.fy, status: "DRAFT", amount: { gt: 0 } } });
    if (!drafts.length) throw new UserError("Nothing new to submit — enter an amount and attach its proof first.");
    const noProof = drafts.filter((d) => !d.proofPath);
    if (noProof.length) throw new UserError(`Attach a proof for: ${noProof.map((d) => ITEM_LABEL[d.itemKey]).join(", ")}.`);
    const rent = drafts.find((d) => d.itemKey === "rent");
    if (rent && Number(rent.amount) > 100_000_00 && !profile.landlordPan) throw new UserError("Rent is above ₹1,00,000 a year — add your landlord's PAN.");
    await db.declarationItem.updateMany({ where: { id: { in: drafts.map((d) => d.id) } }, data: { status: "PENDING" } });
    await audit({ actorId: u.id, action: "declaration.submit", entity: "declaration_item", entityId: `${u.id}:${policy.fy}`, after: { items: drafts.map((d) => ({ key: d.itemKey, amount: Number(d.amount) })) }, ip: (await requestMeta()).ip });
    refresh();
    return { ok: true, msg: `${drafts.length} item${drafts.length === 1 ? "" : "s"} sent to Finance for verification` };
  });
}
