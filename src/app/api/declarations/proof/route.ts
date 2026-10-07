import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { NextResponse, type NextRequest } from "next/server";
import { handler } from "@/lib/api";
import { assertPerm } from "@/lib/auth";
import { db } from "@/lib/db";
import { env } from "@/lib/env";
import { todayIn } from "@/lib/format";
import { getSetting } from "@/lib/settings";
import { DECLARABLE, ITEM_SECTION } from "@/lib/tax/catalogue";

const KINDS: { ext: string; mime: string; magic: number[] }[] = [
  { ext: "pdf", mime: "application/pdf", magic: [0x25, 0x50, 0x44, 0x46] },
  { ext: "png", mime: "image/png", magic: [0x89, 0x50, 0x4e, 0x47] },
  { ext: "jpg", mime: "image/jpeg", magic: [0xff, 0xd8, 0xff] },
];

/** Upload a proof for one declaration item (multipart: file, item). Owner only, within the window. */
export const POST = handler(async (req: NextRequest) => {
  const u = await assertPerm("self");
  const policy = await getSetting("tax.policy");
  const today = todayIn("Asia/Kolkata");
  if (today < policy.proofWindowFrom || today > policy.proofWindowUntil) return NextResponse.json({ error: "The proof window is closed." }, { status: 400 });
  const form = await req.formData();
  const itemKey = String(form.get("item") ?? "");
  const file = form.get("file");
  if (!DECLARABLE.includes(itemKey)) return NextResponse.json({ error: "Unknown item." }, { status: 400 });
  if (!(file instanceof File) || !file.size) return NextResponse.json({ error: "Choose a file." }, { status: 400 });
  if (file.size > policy.maxProofMb * 1024 * 1024) return NextResponse.json({ error: `Files up to ${policy.maxProofMb} MB.` }, { status: 400 });
  const bytes = Buffer.from(await file.arrayBuffer());
  const kind = KINDS.find((k) => k.magic.every((b, i) => bytes[i] === b)); // trust content, not the extension
  if (!kind) return NextResponse.json({ error: "Upload a PDF, JPG or PNG." }, { status: 400 });

  const key = { employeeId_fy_itemKey: { employeeId: u.id, fy: policy.fy, itemKey } };
  const cur = await db.declarationItem.findUnique({ where: key });
  if (cur?.status === "PENDING") return NextResponse.json({ error: "Waiting for Finance — replace it after they decide." }, { status: 400 });
  const dir = path.resolve(env().UPLOAD_DIR, "proofs", u.id);
  await mkdir(dir, { recursive: true });
  const abs = path.join(dir, `${randomUUID()}.${kind.ext}`);
  await writeFile(abs, bytes);
  const name = file.name.replace(/[^\w.\- ()]/g, "_").slice(0, 120) || `proof.${kind.ext}`;
  await db.declarationItem.upsert({
    where: key,
    create: { employeeId: u.id, fy: policy.fy, itemKey, section: ITEM_SECTION[itemKey], amount: 0, status: "DRAFT", proofPath: path.relative(process.cwd(), abs), proofName: name },
    update: { proofPath: path.relative(process.cwd(), abs), proofName: name, ...(cur?.status === "APPROVED" || cur?.status === "REJECTED" ? { status: "DRAFT", decidedById: null, decidedAt: null, rejectReason: null } : {}) },
  });
  return NextResponse.json({ ok: true, name });
});
