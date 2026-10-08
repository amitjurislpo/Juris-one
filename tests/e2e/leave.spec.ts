import { expect, test, type Page } from "@playwright/test";
import { OWNER, expectLanded, login, sql } from "./helpers";

const toast = (page: Page) => page.locator(".toast").last();
const T0 = new Date().toISOString();

/** Put seeded demo requests back exactly as they were. */
async function restore() {
  await sql(`UPDATE leave_requests SET status = 'PENDING', decided_by = NULL, decided_at = NULL WHERE decided_at >= $1`, [T0]);
  await sql(`UPDATE regularization_requests SET status = 'PENDING', decided_by = NULL, decided_at = NULL, decided_role = NULL WHERE decided_at >= $1`, [T0]);
  await sql(`DELETE FROM leave_requests WHERE applied_at >= $1`, [T0]);
  await sql(`DELETE FROM leave_ledger WHERE at >= $1 AND reason <> 'ACCRUAL'`, [T0]);
  await sql(`DELETE FROM punches WHERE created_at >= $1`, [T0]);
  await sql(`DELETE FROM notifications WHERE created_at >= $1`, [T0]);
}

async function ledger(code: string, type: string) {
  const [r] = await sql<{ s: string | null }>(`SELECT sum(l.delta)::text s FROM leave_ledger l JOIN employees e ON e.id = l.employee_id WHERE e.code = $1 AND l.leave_type = $2`, [code, type]);
  return Number(r.s ?? 0);
}

async function pickRange(page: Page, from: number, to: number) {
  const picker = page.locator(".drp");
  await picker.getByRole("button", { name: "Next month" }).click(); // November 2026
  await expect(picker.locator(".drp-h b")).toHaveText("November 2026");
  await picker.locator(".drp-g button", { hasText: new RegExp(`^${from}$`) }).click();
  await picker.locator(".drp-g button", { hasText: new RegExp(`^${to}$`) }).click();
}

test.describe("leave & approvals", () => {
  test.beforeAll(restore);
  test.afterAll(restore);

  test("apply: holidays skipped, live summary, max stretch, then cancel", async ({ page }) => {
    await login(page, "JC-072");
    await expectLanded(page, "/home");
    await page.goto("/leave?apply=1");
    const dialog = page.getByRole("dialog", { name: "Apply for leave" });
    await expect(dialog).toBeVisible();
    // Fri 6 → Tue 10 Nov: Sat/Sun skipped, Mon 9 is Govardhan Puja → 2 working days
    await pickRange(page, 6, 10);
    await expect(dialog.locator(".summary div").first()).toContainText("2");
    await expect(dialog.getByText(/Holidays skipped: Govardhan Puja/)).toBeVisible();
    // casual leave is max 3 days at a stretch
    await dialog.locator(".drp-g button", { hasText: /^16$/ }).click();
    await dialog.locator(".drp-g button", { hasText: /^20$/ }).click();
    await expect(dialog.getByText("Casual leave can be taken for at most 3 days at a stretch.")).toBeVisible();
    await expect(dialog.getByRole("button", { name: "Apply leave" })).toBeDisabled();
    await dialog.locator(".drp-g button", { hasText: /^11$/ }).click();
    await dialog.locator(".drp-g button", { hasText: /^12$/ }).click();
    await dialog.getByLabel(/Reason/).fill("E2E family visit");
    await dialog.getByRole("button", { name: "Apply leave" }).click();
    await expect(toast(page)).toContainText("Casual leave for 2 days (11 Nov – 12 Nov) sent to HR");
    const row = page.locator("tbody tr", { hasText: "E2E family visit" });
    await expect(row.locator(".chip")).toHaveText("Pending");
    await row.getByRole("button", { name: "Cancel" }).click();
    await page.getByRole("button", { name: "Cancel request" }).click();
    await expect(toast(page)).toContainText("cancelled");
    await expect(row.locator(".chip")).toHaveText("Cancelled");
  });

  test("short balance turns the rest into loss of pay", async ({ page }) => {
    await login(page, "JC-074"); // ~1.77 CL accrued, 1 held by a pending request
    await expectLanded(page, "/home");
    await page.goto("/leave?apply=1");
    const dialog = page.getByRole("dialog", { name: "Apply for leave" });
    await pickRange(page, 3, 4);
    await expect(dialog.getByText(/You don't have enough casual leave — 1\.5 day\(s\) will be unpaid/)).toBeVisible();
  });

  test("HR approves leave: ledger, notification and email; nobody decides their own", async ({ page }) => {
    const before = await ledger("JC-049", "CL");
    await login(page, "JC-015"); // HR Executive
    await expectLanded(page, "/home");
    await page.goto("/approvals?tab=leave");
    const card = page.locator("article.req", { hasText: "Naman Tyagi" });
    await expect(card).toContainText("21 Oct – 22 Oct · 2 days");
    await expect(card).toContainText(/Balance [\d.]+ → [\d.]+/);
    await card.getByRole("button", { name: /Approve/ }).click();
    await expect(toast(page)).toContainText("Approved Naman Tyagi’s casual leave");
    await expect(page.getByText("Decided · last 30 days")).toBeVisible();
    expect(await ledger("JC-049", "CL")).toBeCloseTo(before - 2, 2);
    const mail = await sql(`SELECT 1 FROM email_outbox WHERE "to" = 'naman.tyagi@jurislpo.com' AND subject LIKE 'Casual leave%approved' AND created_at >= $1`, [T0]);
    expect(mail.length).toBe(1);

    await page.context().clearCookies();
    await login(page, "JC-049");
    await expectLanded(page, "/home");
    await page.getByRole("button", { name: /Notifications/ }).click();
    await expect(page.locator(".notif")).toContainText("Casual leave (21 Oct – 22 Oct) approved by Pooja Nair");
    await page.goto("/attendance?m=2026-10&d=2026-10-21");
    await expect(page.locator(".chip", { hasText: "Casual leave" }).first()).toBeVisible();
  });

  test("regularization: Finance approves a missed check-out; the first decision wins", async ({ page, browser }) => {
    await login(page, "JC-018"); // Finance Executive — attendance queue only
    await expectLanded(page, "/home");
    await page.goto("/approvals");
    await expect(page.getByRole("button", { name: /^Leave/ })).toHaveCount(0);
    const card = page.locator("article.req", { hasText: "Siddharth Krishna" });
    await expect(card).toContainText("asks to set check-out to 4:36 AM");

    // HR has the same request open in another browser
    const hr = await browser.newPage();
    await login(hr, "JC-012");
    await expectLanded(hr, "/home");
    await hr.goto("/approvals?tab=att");
    const hrCard = hr.locator("article.req", { hasText: "Siddharth Krishna" });
    await expect(hrCard).toBeVisible();

    await card.getByRole("button", { name: /Approve/ }).click();
    await expect(toast(page)).toContainText("Approved Siddharth Krishna");
    const punches = await sql(`SELECT 1 FROM punches WHERE source = 'REGULARIZATION' AND created_at >= $1`, [T0]);
    expect(punches.length).toBe(1);

    await hrCard.getByRole("button", { name: "Decline" }).click();
    await hr.getByRole("dialog").getByRole("button", { name: "Decline" }).click();
    await expect(hr.locator(".toast").last()).toContainText("already decided");
    await hr.close();
    const [r] = await sql<{ status: string; decided_role: string }>(`SELECT status, decided_role FROM regularization_requests WHERE kind = 'MISSED_CHECKOUT' AND work_date = '2026-10-01'`);
    expect(r).toEqual({ status: "APPROVED", decided_role: "Finance Executive" });
  });

  test("holiday work earns a comp-off; Admin can approve too", async ({ page }) => {
    const before = await ledger("JC-049", "CO");
    await login(page, OWNER);
    await expectLanded(page, "/overview");
    await page.goto("/approvals?tab=att");
    const card = page.locator("article.req", { hasText: "Holiday work" });
    await expect(card).toContainText("Worked 8h 40m on Gandhi Jayanti");
    await card.getByRole("button", { name: /Approve/ }).click();
    await expect(toast(page)).toContainText("1 comp-off credited");
    expect(await ledger("JC-049", "CO")).toBe(before + 1);
  });

  test("approvals are permission-gated", async ({ page }) => {
    await login(page, "JC-074");
    await expectLanded(page, "/home");
    await page.goto("/approvals");
    await expectLanded(page, "/home");
  });
});
