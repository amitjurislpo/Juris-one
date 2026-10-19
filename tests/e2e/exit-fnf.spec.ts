import { expect, test, type Page } from "@playwright/test";
import { expectLanded, login, sql } from "./helpers";

// One employee (JC-049) resigns; HR runs notice and clearance; Finance prepares and approves the full & final settlement.
// The whole case is removed again afterwards so the demo data stays as seeded.
const EMP = "JC-049";
const T0 = new Date().toISOString();
const toast = (page: Page) => page.locator(".toast").last();
const today = () => new Date().toLocaleDateString("en-CA", { timeZone: "Asia/Kolkata" });

async function cleanup() {
  const ids = (await sql<{ id: string }>(`SELECT x.id FROM employee_exits x JOIN employees e ON e.id = x.employee_id WHERE e.code = $1`, [EMP])).map((r) => r.id);
  for (const id of ids) {
    await sql(`DELETE FROM leave_ledger WHERE ref_id = $1`, [id]);
    await sql(`DELETE FROM fnf_settlements WHERE exit_id = $1`, [id]);
    await sql(`DELETE FROM exit_clearance_items WHERE exit_id = $1`, [id]);
    await sql(`DELETE FROM employee_exits WHERE id = $1`, [id]);
  }
  await sql(`DELETE FROM notifications WHERE created_at >= $1`, [T0]);
}

async function openExit(page: Page) {
  await page.goto("/exits");
  const link = page.locator("tbody tr", { hasText: EMP }).getByRole("link");
  await expect(link).toBeVisible();
  const href = await link.getAttribute("href");
  await page.goto(href!);
  return href!;
}

test.describe("exit & full-and-final settlement", () => {
  test.describe.configure({ mode: "serial" });
  test.beforeAll(cleanup);
  test.afterAll(cleanup);

  let exitPath = "";

  test("the employee resigns and sees the case in My exit", async ({ page }) => {
    await login(page, EMP);
    await expectLanded(page, "/home");
    await page.goto("/my-exit");
    await expect(page.getByText("You haven't resigned")).toBeVisible();
    await page.getByRole("button", { name: "Resign" }).click();
    const dlg = page.getByRole("dialog", { name: "Submit your resignation" });
    await dlg.getByLabel(/Reason/).fill("E2E: moving to another city");
    await dlg.getByLabel(/Requested last working day/).fill(today());
    await dlg.getByRole("button", { name: "Submit resignation" }).click();
    await expect(toast(page)).toBeVisible();
    await expect(page.locator(".card-h .chip").first()).toHaveText("Resigned");
    await expect(page.getByText("E2E: moving to another city")).toBeVisible();
    await expect(page.getByRole("button", { name: "Withdraw resignation" })).toBeVisible();
    // no money anywhere on the employee's page
    expect(await page.locator("main").innerText()).not.toMatch(/₹|Net payable/);
  });

  test("HR sees the exit, sets the last day, accepts it and clears the checklist; never sees amounts", async ({ page }) => {
    await login(page, "JC-012");
    await expectLanded(page, "/home");
    exitPath = await openExit(page);
    await expect(page.locator(".chip", { hasText: "Resigned" }).first()).toBeVisible();

    // accept needs a last working day
    await page.getByRole("button", { name: /Last day & notice/ }).click();
    const notice = page.getByRole("dialog", { name: "Last working day and notice" });
    await notice.getByLabel(/Last working day/).fill(today());
    await notice.getByLabel(/Notice days/).fill("0");
    await notice.getByLabel(/Why is the notice shortened/).fill("E2E: waived by agreement");
    await notice.getByRole("button", { name: "Save" }).click();
    await expect(toast(page)).toBeVisible();

    await page.getByRole("button", { name: /Accept & start notice/ }).click();
    await expect(page.locator(".chip", { hasText: "Serving notice" }).first()).toBeVisible();
    await page.getByRole("button", { name: "Start clearance" }).click();
    await expect(page.locator(".chip", { hasText: /^Clearance$/ }).first()).toBeVisible();

    // advancing with open items is refused
    await expect(page.getByRole("button", { name: /Clearance complete/ })).toBeVisible();
    await page.getByRole("button", { name: /Clearance complete/ }).click();
    await expect(toast(page)).toContainText("still open");

    const clearButtons = page.getByRole("button", { name: "Clear", exact: true });
    const n = await clearButtons.count();
    expect(n).toBeGreaterThan(0);
    for (let i = 0; i < n; i++) {
      await clearButtons.first().click();
      await page.getByRole("dialog").getByRole("button", { name: "Mark cleared" }).click();
      await expect(clearButtons).toHaveCount(n - i - 1);
    }
    await page.getByRole("button", { name: /Clearance complete/ }).click();
    await expect(page.locator(".chip", { hasText: "Settlement due" }).first()).toBeVisible();

    // HR: no settlement panel, no amounts, and the API refuses
    await expect(page.getByRole("heading", { name: /Full & final settlement/ })).toHaveCount(0);
    expect(await page.locator("main").innerText()).not.toMatch(/₹|Net payable|Total earnings/);
    const id = exitPath.split("/").pop();
    expect((await page.request.get(`/api/exits/${id}/settlement`)).status()).toBe(403);
    await page.goto("/exits");
    await expect(page.locator("tbody tr", { hasText: EMP }).locator(".chip", { hasText: "Settlement due" })).toBeVisible();
    expect(await page.locator("main").innerText()).not.toMatch(/₹/);
  });

  test("Finance drafts the settlement and the Finance manager approves it", async ({ page, browser }) => {
    // Finance executive: may draft, may not approve
    await login(page, "JC-018");
    await expectLanded(page, "/home");
    await page.goto(exitPath);
    const panel = page.locator("section", { has: page.getByRole("heading", { name: /Full & final settlement/ }) });
    await expect(panel.locator(".chip", { hasText: "Not started" })).toBeVisible();
    await expect(panel.getByRole("button", { name: "Approve settlement" })).toHaveCount(0);
    await panel.getByRole("button", { name: "Add line" }).first().click();
    await panel.getByLabel("Additions (bonus, arrears…) name").fill("E2E retention bonus");
    await panel.getByLabel("Additions (bonus, arrears…) amount in rupees").fill("5000");
    await panel.getByLabel(/^Note/).fill("E2E settlement");
    await panel.getByRole("button", { name: "Save & recompute" }).click();
    await expect(panel.locator(".chip", { hasText: "Draft" })).toBeVisible();
    await expect(panel.getByText("E2E retention bonus").first()).toBeVisible();
    await expect(panel.getByText(/Net payable|Recoverable/)).toBeVisible();
    expect((await page.request.put(`/api/exits/${exitPath.split("/").pop()}/settlement/status`, { data: { status: "APPROVED" }, headers: { Origin: "http://localhost:3006" } })).status()).toBe(403);

    // Finance manager approves
    const ctx = await browser.newContext();
    const fm = await ctx.newPage();
    await login(fm, "JC-004");
    await expectLanded(fm, "/home");
    await fm.goto(exitPath);
    const p2 = fm.locator("section", { has: fm.getByRole("heading", { name: /Full & final settlement/ }) });
    await expect(p2.locator(".chip", { hasText: "Draft" })).toBeVisible();
    await p2.getByRole("button", { name: "Approve settlement" }).click();
    await fm.getByRole("dialog").getByRole("button", { name: "Approve", exact: true }).click();
    await expect(p2.locator(".chip", { hasText: "Approved" })).toBeVisible();
    await expect(p2.getByRole("link", { name: /Statement \(PDF\)/ })).toBeVisible();
    const pdf = await fm.request.get(`/api/exits/${exitPath.split("/").pop()}/settlement/statement`);
    expect(pdf.status()).toBe(200);
    expect(pdf.headers()["content-type"]).toContain("pdf");
    expect((await pdf.body()).subarray(0, 4).toString()).toBe("%PDF");
    await ctx.close();
  });

  test("the employee sees the exit as settled, with no amounts; HR still sees none", async ({ page, browser }) => {
    await login(page, EMP);
    await page.goto("/my-exit");
    await expect(page.locator(".card-h .chip").first()).toHaveText("Settled");
    expect(await page.locator("main").innerText()).not.toMatch(/₹/);
    expect((await page.request.get(`/api/exits/${exitPath.split("/").pop()}/settlement`)).status()).toBe(403);

    const ctx = await browser.newContext();
    const hr = await ctx.newPage();
    await login(hr, "JC-012");
    await hr.goto(exitPath);
    await expect(hr.locator(".chip", { hasText: "Settled" }).first()).toBeVisible();
    expect(await hr.locator("main").innerText()).not.toMatch(/₹|Net payable/);
    await ctx.close();
  });
});
