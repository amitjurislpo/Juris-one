import { readFile } from "node:fs/promises";
import path from "node:path";
import type { NextRequest } from "next/server";
import { handler } from "@/lib/api";
import { assertPerm, ForbiddenError } from "@/lib/auth";
import { db } from "@/lib/db";

const MIME: Record<string, string> = { ".pdf": "application/pdf", ".png": "image/png", ".jpg": "image/jpeg" };

/** View a proof: the employee who uploaded it, or Finance with `proofs.verify`. */
export const GET = handler(async (_req: NextRequest, ctx: RouteContext<"/api/declarations/proof/[id]">) => {
  const u = await assertPerm();
  const { id } = await ctx.params;
  if (!/^[0-9a-f-]{36}$/.test(id)) return new Response("Not found", { status: 404 });
  const item = await db.declarationItem.findUnique({ where: { id } });
  if (!item?.proofPath) return new Response("Not found", { status: 404 });
  if (item.employeeId !== u.id && !u.perms.has("proofs.verify")) throw new ForbiddenError();
  const root = path.resolve(process.cwd());
  const abs = path.resolve(root, item.proofPath);
  if (!abs.startsWith(root)) return new Response("Not found", { status: 404 });
  const bytes = await readFile(abs).catch(() => null);
  if (!bytes) return new Response("The file is missing.", { status: 404 });
  return new Response(bytes, {
    headers: {
      "Content-Type": MIME[path.extname(abs)] ?? "application/octet-stream",
      "Content-Disposition": `inline; filename="${(item.proofName ?? "proof").replace(/"/g, "")}"`,
      "Cache-Control": "private, no-store",
      "X-Content-Type-Options": "nosniff",
    },
  });
});
