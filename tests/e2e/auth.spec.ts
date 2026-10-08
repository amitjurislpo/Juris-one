import { createHash } from "node:crypto";
import { expect, test } from "@playwright/test";
import { hashPassword } from "../../src/lib/password";
import { ADMIN_EMAIL, ADMIN_PW, DEMO_PW, emailOf, expectLanded, login, OWNER, sql } from "./helpers";

test.describe("authentication", () => {
  test.afterAll(async () => {
    await sql(`UPDATE users SET failed_attempts = 0, locked_until = NULL`);
    await sql(`UPDATE employees SET portal_access = true WHERE code = 'JC-072'`);
    // the reset test changes JC-090's password — put the demo one back
    await sql(`UPDATE users SET password_hash = $1 FROM employees e WHERE e.id = users.employee_id AND e.code = 'JC-090'`, [await hashPassword(DEMO_PW)]);
  });

  test("super admin signs in and lands on the platform console, outside any business", async ({ page }) => {
    await login(page, ADMIN_EMAIL, ADMIN_PW);
    await expectLanded(page, "/platform");
    const menu = page.getByRole("complementary", { name: "Main menu" });
    await expect(menu.getByRole("link", { name: "Businesses" })).toBeVisible();
    await expect(menu.getByRole("link", { name: "Plans" })).toBeVisible();
    await expect(menu.getByRole("link", { name: "Roles & permissions" })).toHaveCount(0);
    await expect(menu.getByRole("link", { name: "Employees" })).toHaveCount(0);
    expect((await page.request.get("/api/people")).status()).toBe(403); // no business data
  });

  test("the owner (business admin) lands on Overview", async ({ page }) => {
    await login(page, OWNER);
    await expectLanded(page, "/overview");
    await expect(page.locator(".crumbs b")).toHaveText("Overview");
    await expect(page.getByRole("complementary", { name: "Main menu" }).getByRole("link", { name: "Roles & permissions" })).toBeVisible();
    await expect(page.getByRole("link", { name: "My attendance" })).toHaveCount(0);
  });

  test("employee signs in by email and lands on Home", async ({ page }) => {
    await login(page, "jc-074");
    await expectLanded(page, "/home");
    await expect(page.getByRole("heading", { level: 1 })).toContainText("Amit");
    await expect(page.getByRole("link", { name: "Payroll" })).toHaveCount(0);
  });

  test("wrong password is rejected without revealing which part", async ({ page }) => {
    await login(page, "JC-049", "not-the-password");
    await expect(page.locator(".err")).toHaveText("That email and password don't match.");
    await expect(page.getByLabel("Email")).toHaveValue("naman.tyagi@jurislpo.com");
  });

  test("disabled access is blocked", async ({ page }) => {
    await login(page, "JC-081");
    await expect(page.locator(".err")).toHaveText("Your portal access is disabled. Please contact HR.");
  });

  test("past employee is blocked", async ({ page }) => {
    await login(page, "JC-030");
    await expect(page.locator(".err")).toHaveText("This account belongs to a past employee and can no longer sign in.");
  });

  test("account locks after 5 failures", async ({ page }) => {
    for (let i = 0; i < 5; i++) {
      await login(page, "JC-058", "bad-password-" + i);
      await expect(page.locator(".err")).toBeVisible();
    }
    await expect(page.locator(".err")).toContainText("locked");
    await login(page, "JC-058", DEMO_PW); // right password, still locked
    await expect(page.locator(".err")).toContainText("locked");
    const rows = await sql<{ n: string }>(
      `SELECT count(*)::text AS n FROM login_history h JOIN employees e ON e.id = h.employee_id WHERE e.code = 'JC-058' AND NOT h.success`,
    );
    expect(Number(rows[0].n)).toBeGreaterThanOrEqual(6);
  });

  test("disabling access ends an active session immediately", async ({ page }) => {
    await login(page, "JC-072");
    await expectLanded(page, "/home");
    await sql(`UPDATE employees SET portal_access = false WHERE code = 'JC-072'`);
    await page.goto("/attendance");
    await expect(page).toHaveURL(/\/login/);
  });

  test("sign out ends the session", async ({ page }) => {
    await login(page, "JC-015");
    await expectLanded(page, "/home");
    await page.getByRole("button", { name: /Pooja Nair/ }).click();
    await page.getByRole("menuitem", { name: "Sign out" }).click();
    await expect(page).toHaveURL(/\/login\?signedout=1/);
    await page.goto("/home");
    await expect(page).toHaveURL(/\/login/);
  });

  test("a reset link works exactly once", async ({ page }) => {
    await page.goto("/forgot");
    await page.getByLabel("Email").fill(await emailOf("JC-090"));
    await page.getByRole("button", { name: "Send reset link" }).click();
    await expect(page.getByRole("status")).toContainText("reset link is on its way");
    // tokens are stored hashed, so mint a known one for the test
    const token = "e2e-reset-token-" + Date.now();
    await sql(
      `INSERT INTO auth_tokens (id, token_hash, employee_id, purpose, expires_at)
       SELECT gen_random_uuid(), $1, id, 'RESET', now() + interval '1 hour' FROM employees WHERE code = 'JC-090'`,
      [createHash("sha256").update(token).digest("hex")],
    );
    await page.goto(`/reset/${token}`);
    await page.getByLabel(/New password/).fill("Fresh@Pass2026");
    await page.getByLabel("Confirm password").fill("Fresh@Pass2026");
    await page.getByRole("button", { name: "Update password" }).click();
    await expect(page).toHaveURL(/\/login\?reset=1/);
    await login(page, "JC-090", "Fresh@Pass2026");
    await expectLanded(page, "/home");
    await page.goto(`/reset/${token}`);
    await expect(page.getByRole("heading", { name: "Link expired" })).toBeVisible();
  });
});
