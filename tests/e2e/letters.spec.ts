import { expect, test, type Page } from "@playwright/test";
import { expectLanded, login, sql } from "./helpers";

const T0 = new Date().toISOString();
const toast = (page: Page) => page.locator(".toast").last();
const ORIGIN = { Origin: "http://localhost:3006" };

async function cleanup() {
  // issued letters are protected by a trigger: lift it for the cleanup only (T0 is an ISO timestamp made here)
  await sql(`ALTER TABLE issued_letters DISABLE TRIGGER USER; DELETE FROM issued_letters WHERE issued_at >= '${T0}'; ALTER TABLE issued_letters ENABLE TRIGGER USER`);
  await sql(`DELETE FROM notifications WHERE created_at >= $1`, [T0]);
}

test.describe("HR letters", () => {
  test.beforeAll(cleanup);
  test.afterAll(cleanup);

  test("HR issues an employment-verification letter; the employee finds it and downloads the PDF", async ({ page, browser }) => {
    await login(page, "JC-012");
    await expectLanded(page, "/home");
    await page.goto("/letters");
    const tpl = page.getByLabel("Template");
    const emp = page.getByLabel("Employee");
    await tpl.selectOption({ label: "Employment verification letter" });
    await emp.selectOption({ label: "Siddharth Krishna · JC-072" });

    await page.getByRole("button", { name: "Preview" }).click();
    await expect(page.getByText("Preview, nothing is saved yet")).toBeVisible();
    await expect(page.getByText(/Siddharth Krishna \(employee ID JC-072\)/)).toBeVisible();

    await page.getByRole("button", { name: "Issue letter" }).click();
    await expect(toast(page)).toBeVisible();
    const row = page.locator("tbody tr", { hasText: "JC-072" }).filter({ hasText: /HRL\// });
    await expect(row).toBeVisible();
    const reference = (await row.locator("td.mono").innerText()).trim();
    expect(reference.length).toBeGreaterThan(3);
    await expect(row.getByRole("link", { name: /PDF/ })).toBeVisible();

    // the employee sees it under My letters and the PDF is a real PDF
    const ctx = await browser.newContext();
    const e = await ctx.newPage();
    await login(e, "JC-072");
    await expectLanded(e, "/home");
    await e.getByRole("complementary", { name: "Main menu" }).getByRole("link", { name: "My letters" }).click();
    await expectLanded(e, "/my-letters");
    await expect(e.getByText(reference)).toBeVisible();
    const href = await e.locator(".slip-card", { hasText: reference }).getByRole("link", { name: /PDF/ }).getAttribute("href");
    const res = await e.request.get(href!);
    expect(res.status()).toBe(200);
    expect(res.headers()["content-type"]).toContain("pdf");
    expect((await res.body()).subarray(0, 4).toString()).toBe("%PDF");
    // someone else's letter is not theirs to open
    const ctx2 = await browser.newContext();
    const other = await ctx2.newPage();
    await login(other, "JC-049");
    await expectLanded(other, "/home");
    expect((await other.request.get(href!)).status()).toBeGreaterThanOrEqual(400);
    await ctx.close();
    await ctx2.close();
  });

  test("HR cannot issue a letter that states salary", async ({ page }) => {
    await login(page, "JC-012");
    await expectLanded(page, "/home");
    await page.goto("/letters");
    const offer = page.getByLabel("Template").locator("option", { hasText: "Offer letter" });
    await expect(offer).toHaveText(/needs salary access/);
    await expect(offer).toHaveAttribute("disabled", "");

    // and the API refuses it as well
    const [x] = await sql<{ t: string; e: string }>(
      `SELECT t.id AS t, e.id AS e FROM letter_templates t JOIN employees e ON e.business_id = t.business_id WHERE t.name = 'Offer letter' AND e.code = 'JC-072'`);
    const r = await page.request.post("/api/letters", { data: { templateId: x.t, employeeId: x.e }, headers: ORIGIN });
    expect(r.status()).toBe(403);
    const issued = await sql(`SELECT 1 FROM issued_letters WHERE template_name = 'Offer letter' AND issued_at >= $1`, [T0]);
    expect(issued).toHaveLength(0);
  });

  test("the owner, who manages salary structures, can issue the offer letter", async ({ page }) => {
    await login(page, "JC-002");
    await expectLanded(page, "/overview");
    await page.goto("/letters");
    const offer = page.getByLabel("Template").locator("option", { hasText: "Offer letter" });
    await expect(offer).toBeEnabled();
  });

  test("an ordinary employee cannot open HR letters", async ({ page }) => {
    await login(page, "JC-049");
    await expectLanded(page, "/home");
    await page.goto("/letters");
    await expectLanded(page, "/home");
  });
});
