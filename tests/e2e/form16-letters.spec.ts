import { expect, test, type Page } from "@playwright/test";
import { expectLanded, login, sql } from "./helpers";

const T0 = new Date().toISOString();
const toast = (page: Page) => page.locator(".toast").last();
const ORIGIN = { Origin: "http://localhost:3006" };

async function cleanup() {
  await sql(`DELETE FROM issued_letters WHERE issued_at >= $1`, [T0]);
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
    const emp = page.getByLabel("Employee", { exact: true });
    await tpl.selectOption({ label: "Employment verification letter" });
    await emp.selectOption({ label: "Siddharth Krishna · JC-072" });

    await page.getByRole("button", { name: "Preview" }).click();
    await expect(page.getByText("Preview, nothing is saved yet")).toBeVisible();
    await expect(page.getByText(/Siddharth Krishna \(employee ID JC-072\)/)).toBeVisible();

    await page.getByRole("button", { name: "Issue letter" }).click();
    await expect(toast(page)).toBeVisible();
    const row = page.locator("tbody tr", { hasText: "JC-072" }).filter({ hasText: "Employment verification" });
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
    const other = await ctx.newPage();
    await login(other, "JC-049");
    expect((await other.request.get(href!)).status()).toBeGreaterThanOrEqual(400);
    await ctx.close();
  });

  test("HR cannot issue a letter that states salary", async ({ page }) => {
    await login(page, "JC-012");
    await page.goto("/letters");
    const offer = page.getByLabel("Template").locator("option", { hasText: "Offer letter" });
    await expect(offer).toHaveText(/needs salary access/);
    await expect(offer).toBeDisabled();

    // and the API refuses it as well
    const [t] = await sql<{ id: string }>(`SELECT id FROM letter_templates WHERE name = 'Offer letter' AND business_id IS NOT NULL LIMIT 1`);
    const [e] = await sql<{ id: string }>(`SELECT id FROM employees WHERE code = 'JC-072'`);
    const r = await page.request.post("/api/letters", { data: { templateId: t.id, employeeId: e.id }, headers: ORIGIN });
    expect(r.status()).toBe(403);
    const issued = await sql(`SELECT 1 FROM issued_letters WHERE template_name = 'Offer letter' AND issued_at >= $1`, [T0]);
    expect(issued).toHaveLength(0);
  });

  test("the owner, who manages salary structures, can issue the offer letter", async ({ page }) => {
    await login(page, "JC-002");
    await page.goto("/letters");
    const offer = page.getByLabel("Template").locator("option", { hasText: "Offer letter" });
    await expect(offer).toBeEnabled();
  });

  test("an ordinary employee cannot open HR letters", async ({ page }) => {
    await login(page, "JC-049");
    await page.goto("/letters");
    await expectLanded(page, "/home");
  });
});

test.describe("Form 16", () => {
  test("the employee's Form 16 page loads (nothing published yet)", async ({ page }) => {
    await login(page, "JC-072");
    await expectLanded(page, "/home");
    await page.getByRole("complementary", { name: "Main menu" }).getByRole("link", { name: "Form 16" }).click();
    await expectLanded(page, "/form16");
    await expect(page.getByRole("heading", { level: 1 })).toContainText("Form 16");
    await expect(page.getByText("No Form 16 yet")).toBeVisible();
  });

  test("Finance sees which payroll months are still open and cannot publish", async ({ page }) => {
    await login(page, "JC-004");
    await expectLanded(page, "/home");
    await page.getByRole("complementary", { name: "Main menu" }).getByRole("link", { name: "Form 16 & 24Q" }).click();
    await expectLanded(page, "/form16-admin");
    await expect(page.getByRole("heading", { level: 1 })).toContainText("Form 16");
    // the demo business has not closed a whole financial year
    await expect(page.locator(".chip", { hasText: /\d+ not closed/ })).toBeVisible();
    await expect(page.getByText(/can't be published yet\. Still open or missing:/)).toBeVisible();
    await expect(page.getByRole("button", { name: /Publish/ })).toHaveCount(0);
    await expect(page.getByRole("heading", { name: "Deductor details" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Form 24Q working file" })).toBeVisible();

    // the API says the same, and a quarter with open months has no working file
    const fy = (await page.getByRole("group", { name: "Financial year" }).locator("a.on").innerText()).replace("FY ", "").trim();
    const pub = await page.request.post(`/api/form16/${fy}/forms`, { headers: ORIGIN });
    expect(pub.status()).toBe(422);
    expect(await pub.text()).toContain("needs all 12 months of payroll closed");
    const q4 = await page.request.get(`/api/form16/${fy}/24q?quarter=Q4`);
    expect(q4.status()).toBe(422);
    expect(await q4.text()).toContain("needs its payroll months closed");
  });

  test("an ordinary employee cannot open the Finance Form 16 page or publish", async ({ page }) => {
    await login(page, "JC-072");
    await page.goto("/form16-admin");
    await expectLanded(page, "/home");
    expect((await page.request.post("/api/form16/2026-27/forms", { headers: ORIGIN })).status()).toBe(403);
  });
});
