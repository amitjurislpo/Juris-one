import { expect, test, type Page } from "@playwright/test";
import { expectLanded, login, sql } from "./helpers";

const toast = (page: Page) => page.locator(".toast").last();
const T0 = new Date().toISOString();
const SEP = "2026-09";

async function cleanup() {
  await sql(`DELETE FROM payslips WHERE run_id IN (SELECT id FROM payroll_runs WHERE month = $1)`, [SEP]);
  await sql(`DELETE FROM payroll_adjustments WHERE run_id IN (SELECT id FROM payroll_runs WHERE month = $1)`, [SEP]);
  await sql(`DELETE FROM payroll_runs WHERE month = $1`, [SEP]);
  await sql(`DELETE FROM salary_structures WHERE created_at >= $1`, [T0]);
  await sql(`DELETE FROM notifications WHERE created_at >= $1`, [T0]);
}

test.describe("payroll", () => {
  test.beforeAll(cleanup);
  test.afterAll(cleanup);

  test("salary structures: add a revision; closed months are protected", async ({ page }) => {
    await login(page, "JC-004");
    await expectLanded(page, "/home");
    await page.goto("/structures?q=Pooja");
    const row = page.locator("tbody tr").first();
    await expect(row).toContainText("₹52,000");
    await row.getByRole("button", { name: "Revise" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("Effective from").fill("2026-08-15");
    await dialog.getByLabel(/Target gross/).fill("60000");
    await dialog.getByRole("button", { name: "Split" }).click();
    await expect(dialog.getByLabel("Basic (₹)")).toHaveValue("30000");
    await expect(dialog.locator(".summary")).toContainText("₹60,000");
    await dialog.getByRole("button", { name: "Save structure" }).click();
    await expect(dialog.locator(".err")).toContainText("Payroll is closed up to August 2026");
    await dialog.getByLabel("Effective from").fill("2026-11-01");
    await dialog.getByRole("button", { name: "Save structure" }).click();
    await expect(toast(page)).toContainText("Salary structure saved for Pooja Nair");
    await expect(row.getByRole("button", { name: "2 revisions" })).toBeVisible();
    const logged = await sql(`SELECT 1 FROM audit_log WHERE action = 'salary.structure_change' AND at >= $1`, [T0]);
    expect(logged.length).toBe(1);
  });

  test("HR never sees salary pages or exports", async ({ page }) => {
    await login(page, "JC-012");
    await expectLanded(page, "/home");
    for (const p of ["/payroll", "/structures"]) {
      await page.goto(p);
      await expectLanded(page, "/home");
    }
    expect((await page.request.get(`/api/payroll/export?m=${SEP}&type=bank`)).status()).toBe(403);
    expect((await page.request.get(`/api/payslips/2026-08/JC-049`)).status()).toBe(403);
  });

  test("Finance Executive reviews and edits but can't close", async ({ page }) => {
    await login(page, "JC-018");
    await expectLanded(page, "/home");
    await page.goto("/payroll");
    await expect(page.getByRole("heading", { level: 1 })).toContainText("September");
    await expect(page.getByText("Closing needs Finance Manager")).toBeVisible();
    // golden case: JC-074 — 22/30 paid days, TDS ₹14,926
    const amit = page.locator("tbody tr", { hasText: "Amit Jaspal" });
    await expect(amit.locator("td").nth(3)).toHaveText("22/30");
    await expect(amit.locator("td").nth(7)).toHaveText("₹14,926");
    await expect(amit.locator("td").nth(8)).toHaveText("₹93,074");
    // edit: Diwali bonus for Naman
    const naman = page.locator("tbody tr", { hasText: "Naman Tyagi" });
    await naman.getByRole("button", { name: /Edit payroll/ }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("Bonus / arrears (₹)").fill("5000");
    await dialog.getByLabel(/Note/).fill("Diwali bonus");
    await dialog.getByRole("button", { name: "Save changes" }).click();
    await expect(toast(page)).toContainText("Saved payroll changes for Naman Tyagi");
    await expect(naman).toContainText("edited");
    await expect(naman.locator("td").nth(4)).toHaveText("₹5,000");
    const logged = await sql(`SELECT 1 FROM audit_log WHERE action = 'payroll.edit' AND at >= $1`, [T0]);
    expect(logged.length).toBe(1);
    // hide amounts
    await page.getByRole("button", { name: "Hide amounts" }).click();
    await expect(page.locator(".kpi-accent .v")).toHaveText("₹ ••,•••");
    await page.getByRole("button", { name: "Show amounts" }).click();
  });

  test("Finance Manager approves & closes; payslips are frozen and published", async ({ page }) => {
    await login(page, "JC-004");
    await expectLanded(page, "/home");
    await page.goto("/payroll");
    await page.getByRole("button", { name: "Approve & close" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Approve & close" }).click();
    // closing renders a PDF per payslip — slow on a busy dev server
    await expect(toast(page)).toContainText("September 2026 payroll closed — 11 payslips published", { timeout: 30_000 });
    await expect(page.getByRole("button", { name: "Closed ✓" })).toBeVisible();
    await expect(page.locator("tbody tr").first()).toContainText("Locked");
    const [{ n }] = await sql<{ n: string }>(`SELECT count(*)::text n FROM payslips p JOIN payroll_runs r ON r.id = p.run_id WHERE r.month = $1`, [SEP]);
    expect(n).toBe("11");
    const [snap] = await sql<{ net: number; tds: number }>(`SELECT (snapshot->>'net')::int net, (snapshot->>'tds')::int tds FROM payslips p JOIN employees e ON e.id = p.employee_id JOIN payroll_runs r ON r.id = p.run_id WHERE r.month = $1 AND e.code = 'JC-074'`, [SEP]);
    expect(snap).toEqual({ net: 9_307_400, tds: 1_492_600 });

    // a later structure change does not touch the closed payslip
    await sql(`UPDATE salary_structures SET basic = basic + 100000 WHERE employee_id = (SELECT id FROM employees WHERE code = 'JC-074')`);
    const [again] = await sql<{ net: number }>(`SELECT (snapshot->>'net')::int net FROM payslips p JOIN employees e ON e.id = p.employee_id JOIN payroll_runs r ON r.id = p.run_id WHERE r.month = $1 AND e.code = 'JC-074'`, [SEP]);
    expect(again.net).toBe(9_307_400);
    await sql(`UPDATE salary_structures SET basic = basic - 100000 WHERE employee_id = (SELECT id FROM employees WHERE code = 'JC-074')`);

    // exports
    const bank = await page.request.get(`/api/payroll/export?m=${SEP}&type=bank`);
    expect(bank.status()).toBe(200);
    expect(await bank.text()).toContain("93074.00");
    expect((await page.request.get(`/api/payroll/export?m=${SEP}&type=ecr`)).status()).toBe(200);
  });

  test("employee sees and downloads only their own payslip", async ({ page }) => {
    await login(page, "JC-074");
    await expectLanded(page, "/home");
    await page.goto("/payslips");
    const sep = page.locator(".slip-card", { hasText: "September 2026" });
    await expect(sep).toContainText("22 paid days");
    await sep.getByRole("button", { name: "View" }).click();
    const slip = page.getByRole("dialog", { name: "Payslip September 2026" });
    await expect(slip).toContainText("₹93,074");
    await expect(slip).toContainText("Rupees Ninety Three Thousand Seventy Four only");
    await expect(slip).toContainText("XXXXXX");
    await slip.getByRole("button", { name: "Close" }).click();
    const pdf = await page.request.get("/api/payslips/2026-09/JC-074");
    expect(pdf.status()).toBe(200);
    expect(pdf.headers()["content-type"]).toBe("application/pdf");
    expect((await pdf.body()).subarray(0, 4).toString()).toBe("%PDF");
    expect((await page.request.get("/api/payslips/2026-09/JC-049")).status()).toBe(403);

    await page.context().clearCookies();
    await login(page, "JC-090"); // US
    await expectLanded(page, "/home");
    await page.goto("/payslips");
    await expect(page.getByText("US team pay statements come from the US payroll provider.")).toBeVisible();
  });
});
