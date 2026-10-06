import "server-only";
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { audit } from "../audit";
import { db } from "../db";
import { sendEmail } from "../email";
import { env } from "../env";
import { todayIn, ymLabel } from "../format";
import { getSetting } from "../settings";
import { payslipPdf } from "./pdf";
import { computeRun, runStatus, toSnapshot } from "./service";

/**
 * Close & publish (REQUIREMENTS §5.8): snapshots every payslip as immutable JSON + PDF.
 * Later changes to structures, regimes or attendance never change a closed payslip.
 */
export async function closeRun(ym: string, actorId: string | null, opts: { notify?: boolean; payDate?: string } = {}) {
  const { run, attendanceLocked } = await runStatus(ym);
  if (!attendanceLocked) throw new Error("Attendance for this month isn't locked yet.");
  if (run?.status === "CLOSED") throw new Error("This payroll is already closed.");
  const rows = await computeRun(ym);
  const company = await getSetting("company");
  const payDate = opts.payDate ?? todayIn("Asia/Kolkata");
  const emps = await db.employee.findMany({ where: { id: { in: rows.map((r) => r.employeeId) } }, select: { id: true, panLast4: true, uanLast4: true, bankName: true, bankAccountLast4: true, email: true, fullName: true } });
  const dir = path.resolve(env().UPLOAD_DIR, "payslips", ym);
  await mkdir(dir, { recursive: true });

  const files: { employeeId: string; pdfPath: string; snapshot: ReturnType<typeof toSnapshot> }[] = [];
  for (const r of rows) {
    const e = emps.find((x) => x.id === r.employeeId)!;
    const snapshot = toSnapshot(r, ym, e, company, payDate);
    const pdfPath = path.join(dir, `${r.code}.pdf`);
    await writeFile(pdfPath, await payslipPdf(snapshot));
    files.push({ employeeId: r.employeeId, pdfPath, snapshot });
  }

  const closed = await db.$transaction(async (tx) => {
    const now = new Date();
    const runRow = run ?? (await tx.payrollRun.create({ data: { month: ym } }));
    // atomic: only an open run can close
    const { count } = await tx.payrollRun.updateMany({
      where: { id: runRow.id, status: "OPEN" },
      data: { status: "CLOSED", stage: "CLOSED", closedById: actorId, closedAt: now, payDate: new Date(`${payDate}T00:00:00Z`), attendanceLockedAt: runRow.attendanceLockedAt ?? now, computedAt: now, approvedById: runRow.approvedById ?? actorId, approvedAt: runRow.approvedAt ?? now },
    });
    if (!count) throw new Error("This payroll is already closed.");
    await tx.payslip.createMany({ data: files.map((f) => ({ runId: runRow.id, employeeId: f.employeeId, snapshot: JSON.parse(JSON.stringify(f.snapshot)), pdfPath: path.relative(process.cwd(), f.pdfPath) })) });
    await audit({ actorId, action: "payroll.close", entity: "payroll_run", entityId: runRow.id, after: { month: ym, payslips: files.length, net: files.reduce((a, f) => a + f.snapshot.net, 0) } }, tx);
    if (opts.notify !== false) {
      await tx.notification.createMany({ data: files.map((f) => ({ employeeId: f.employeeId, text: `Your ${ymLabel(ym)} payslip is ready`, link: "/payslips" })) });
    }
    return runRow;
  });

  if (opts.notify !== false) {
    for (const e of emps) {
      await sendEmail({
        to: e.email,
        subject: `Your ${ymLabel(ym)} payslip is ready`,
        text: `Hi ${e.fullName},\n\nYour payslip for ${ymLabel(ym)} has been published. View or download it in JurisOne: ${env().APP_URL}/payslips`,
      });
    }
  }
  return { runId: closed.id, payslips: files.length };
}
