import { readFile } from "node:fs/promises";
import path from "node:path";
import type { NextRequest } from "next/server";
import { handler } from "@/lib/api";
import { assertPerm, ForbiddenError } from "@/lib/auth";
import { db } from "@/lib/db";
import { payslipPdf } from "@/lib/payroll/pdf";
import type { PayslipSnapshot } from "@/lib/payroll/service";

/** Payslip PDF of a closed month: your own (`self`), or anyone's with `pay.view`. */
export const GET = handler(async (_req: NextRequest, ctx: RouteContext<"/api/payslips/[month]/[code]">) => {
  const u = await assertPerm();
  const { month, code } = await ctx.params;
  if (!/^\d{4}-\d{2}$/.test(month) || !/^JC-\d+$/.test(code)) return new Response("Not found", { status: 404 });
  const own = code === u.code && u.perms.has("self");
  if (!own && !u.perms.has("pay.view")) throw new ForbiddenError();
  const slip = await db.payslip.findFirst({ where: { run: { month, status: "CLOSED" }, employee: { code } } });
  if (!slip) return new Response("Not found", { status: 404 });
  let bytes: Uint8Array;
  try {
    if (!slip.pdfPath) throw new Error("no file");
    const p = path.resolve(process.cwd(), slip.pdfPath);
    if (!p.startsWith(path.resolve(process.cwd()))) throw new Error("bad path");
    bytes = await readFile(p);
  } catch {
    bytes = await payslipPdf(slip.snapshot as unknown as PayslipSnapshot); // regenerate from the frozen snapshot
  }
  return new Response(Buffer.from(bytes), {
    headers: { "Content-Type": "application/pdf", "Content-Disposition": `attachment; filename="Payslip-${code}-${month}.pdf"`, "Cache-Control": "private, no-store" },
  });
});
