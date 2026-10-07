import { expect, test, type Page } from "@playwright/test";
import { expectLanded, login, sql } from "./helpers";

const toast = (page: Page) => page.locator(".toast").last();
const T0 = new Date().toISOString();
const PDF = Buffer.from("%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n");

async function restore() {
  await sql(`UPDATE declaration_items SET status = 'PENDING', decided_by = NULL, decided_at = NULL, reject_reason = NULL WHERE employee_id = (SELECT id FROM employees WHERE code = 'JC-058')`);
  await sql(`DELETE FROM declaration_items WHERE employee_id = (SELECT id FROM employees WHERE code = 'JC-072')`);
  await sql(`DELETE FROM tax_profiles WHERE employee_id = (SELECT id FROM employees WHERE code = 'JC-072')`);
  await sql(`DELETE FROM notifications WHERE created_at >= $1`, [T0]);
}

test.describe("tax & declarations", () => {
  test.beforeAll(restore);
  test.afterAll(restore);

  test("employee: old regime, declare, proof required, upload, submit", async ({ page }) => {
    await login(page, "JC-072");
    await expectLanded(page, "/home");
    await page.goto("/declare");
    await expect(page.getByRole("button", { name: /New regime/ })).toHaveAttribute("aria-pressed", "true");
    await page.getByRole("button", { name: /Old regime/ }).click();
    await expect(toast(page)).toContainText("You're on the old regime");
    await page.getByRole("button", { name: /Section 80C/ }).click();
    const ppf = page.getByLabel("Public provident fund (PPF) amount");
    await ppf.fill("50000");
    await ppf.blur();
    await expect(page.locator(".decl-row", { hasText: "Public provident fund" }).locator(".chip")).toHaveText("Draft");
    await page.getByRole("button", { name: "Submit for verification" }).click();
    await expect(toast(page)).toContainText("Attach a proof for: Public provident fund (PPF)");
    // content is checked, not the extension
    await page.getByLabel("Upload proof for Public provident fund (PPF)").setInputFiles({ name: "fake.pdf", mimeType: "application/pdf", buffer: Buffer.from("not really a pdf") });
    await expect(toast(page)).toContainText("Upload a PDF, JPG or PNG", { timeout: 30_000 }); // first hit compiles the route
    await page.getByLabel("Upload proof for Public provident fund (PPF)").setInputFiles({ name: "ppf-statement.pdf", mimeType: "application/pdf", buffer: PDF });
    await expect(toast(page)).toContainText("Attached ppf-statement.pdf");
    await page.getByRole("button", { name: "Submit for verification" }).click();
    await expect(toast(page)).toContainText("1 item sent to Finance for verification");
    await expect(page.locator(".decl-row", { hasText: "Public provident fund" }).locator(".chip")).toHaveText("Pending");
    await expect(ppf).toBeDisabled();
  });

  test("Finance verifies proofs with a TDS preview", async ({ page }) => {
    await login(page, "JC-018");
    await expectLanded(page, "/home");
    await page.goto("/proofs");
    const row = page.locator("tbody tr", { hasText: "Kavya Iyer" });
    await expect(row).toContainText("4 to verify");
    await row.getByRole("button", { name: "Review Kavya Iyer" }).click();
    const drawer = page.getByRole("dialog", { name: "Review proofs" });
    const now = await drawer.locator(".summary div").first().locator("b").innerText();
    const ifAll = await drawer.locator(".summary div").nth(1).locator("b").innerText();
    expect(Number(ifAll.replace(/\D/g, ""))).toBeLessThan(Number(now.replace(/\D/g, "")));
    const lic = drawer.locator(".it", { hasText: "Life insurance premium" });
    await lic.getByRole("button", { name: "Reject" }).click();
    await page.getByLabel("Reason *").fill("Premium receipt is for FY 2025-26");
    await page.getByRole("dialog", { name: "Reject proof" }).getByRole("button", { name: "Reject" }).click();
    await expect(lic.locator(".chip").last()).toHaveText("Rejected");
    await drawer.getByRole("button", { name: "Approve all pending" }).click();
    await expect(toast(page)).toContainText("3 proofs approved");
    const logged = await sql(`SELECT 1 FROM audit_log WHERE action = 'proof.decision' AND at >= $1`, [T0]);
    expect(logged.length).toBe(2);
    const note = await sql(`SELECT 1 FROM notifications n JOIN employees e ON e.id = n.employee_id WHERE e.code = 'JC-058' AND n.text LIKE '%rejected — Premium receipt%'`);
    expect(note.length).toBe(1);
  });

  test("Tax & TDS: golden case, computation drawer, regime override", async ({ page }) => {
    await login(page, "JC-004");
    await expectLanded(page, "/home");
    await page.goto("/tax");
    const amit = page.locator("tbody tr", { hasText: "Amit Jaspal" });
    await expect(amit.locator("td").nth(6)).toHaveText("₹14,926");
    await amit.click();
    const drawer = page.getByRole("dialog", { name: "Tax computation" });
    await expect(drawer).toContainText("Previous employer (Form 12B)");
    await expect(drawer).toContainText("₹7,50,000");
    await expect(drawer).toContainText("Slab working");
    await expect(drawer.locator(".kv.total")).toContainText("₹1,42,480");
    await drawer.getByRole("button", { name: "Close" }).click();

    await page.locator("tbody tr", { hasText: "Siddharth Krishna" }).click();
    const td = page.getByRole("dialog", { name: "Tax computation" });
    await expect(td.getByRole("button", { name: "New regime" })).toBeVisible();
    const onNew = (await td.getByRole("button", { name: "New regime" }).getAttribute("aria-pressed")) === "true";
    await td.getByRole("button", { name: onNew ? "Old regime" : "New regime" }).click(); // override to the other regime
    await expect(toast(page)).toContainText("Regime set to");
    await page.goto("/tax?q=Siddharth");
    await expect(page.locator("tbody tr").first()).toContainText("set by Finance");
    const exp = await page.request.get("/api/tax/export");
    expect(exp.status()).toBe(200);
    expect(await exp.text()).toContain("Amit Jaspal");

    await page.context().clearCookies();
    await login(page, "JC-072");
    await expectLanded(page, "/home");
    await page.goto("/declare");
    await expect(page.getByText(/Finance has set your regime/)).toBeVisible();
    await expect(page.getByRole("button", { name: /New regime/ })).toBeDisabled();
    await expect(page.getByRole("button", { name: /Old regime/ })).toBeDisabled();
  });

  test("HR and others can't reach tax data or other people's proofs", async ({ page }) => {
    const [item] = await sql<{ id: string }>(`SELECT d.id FROM declaration_items d JOIN employees e ON e.id = d.employee_id WHERE e.code = 'JC-058' AND d.proof_path IS NOT NULL LIMIT 1`);
    await login(page, "JC-012");
    await expectLanded(page, "/home");
    for (const p of ["/tax", "/proofs"]) {
      await page.goto(p);
      await expectLanded(page, "/home");
    }
    expect((await page.request.get("/api/tax/export")).status()).toBe(403);
    if (item) expect((await page.request.get(`/api/declarations/proof/${item.id}`)).status()).toBe(403);
  });
});
