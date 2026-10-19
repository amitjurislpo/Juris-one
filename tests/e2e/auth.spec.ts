import { createHash } from "node:crypto";
import { expect, test } from "@playwright/test";
import { ADMIN_EMAIL, emailOf, expectLanded, login, loginWithCode, OWNER, sql } from "./helpers";

test.describe("authentication", () => {
  test.afterAll(async () => {
    await sql(`UPDATE users SET failed_attempts = 0, locked_until = NULL`);
    await sql(`UPDATE employees SET portal_access = true WHERE code = 'JC-072'`);
  });

  test("super admin lands on the platform console and runs only the platform company's own staff", async ({ page }) => {
    await login(page, ADMIN_EMAIL);
    await expectLanded(page, "/platform");
    const menu = page.getByRole("complementary", { name: "Main menu" });
    await expect(menu.getByRole("link", { name: "Businesses" })).toBeVisible();
    await expect(menu.getByRole("link", { name: "Plans" })).toBeVisible();
    await expect(menu.getByRole("link", { name: "Employees" })).toBeVisible(); // the platform company's staff
    await expect(page.getByRole("link", { name: "My attendance" })).toHaveCount(0);
    const people = await page.request.get("/api/people?status=All");
    expect(people.status()).toBe(200);
    expect(await people.text()).not.toContain("JC-0"); // never a customer's employees
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

  test("a wrong code is rejected without revealing anything", async ({ page }) => {
    await loginWithCode(page, "JC-049", "111111");
    await expect(page.locator(".err")).toHaveText("That code is wrong or has expired. Request a new one.");
    await expect(page).toHaveURL(/\/login/);
  });

  test("disabled access gets no code, and is told why by email", async ({ page }) => {
    await login(page, "JC-081");
    await expect(page.locator(".err")).toBeVisible(); // no code exists for this account
    const mails = await sql<{ text: string }>(`SELECT text FROM email_outbox WHERE "to" = $1 AND subject = 'Your JurisOne sign-in request'`, [await emailOf("JC-081")]);
    expect(mails.length).toBeGreaterThan(0);
    expect(mails[0].text).toContain("Your portal access is disabled. Please contact HR.");
  });

  test("past employee gets no code, and is told why by email", async ({ page }) => {
    await login(page, "JC-030");
    await expect(page.locator(".err")).toBeVisible();
    const mails = await sql<{ text: string }>(`SELECT text FROM email_outbox WHERE "to" = $1 AND subject = 'Your JurisOne sign-in request'`, [await emailOf("JC-030")]);
    expect(mails[0].text).toContain("past employee");
  });

  test("five wrong codes burn the code; a fresh code works", async ({ page }) => {
    const badTries = async () => Number((await sql<{ n: string }>(
      `SELECT count(*)::text AS n FROM login_history h JOIN employees e ON e.id = h.employee_id WHERE e.code = 'JC-058' AND NOT h.success AND h.reason = 'bad_otp'`,
    ))[0].n);
    await loginWithCode(page, "JC-058", "111111");
    await expect.poll(badTries).toBe(1);
    for (let i = 2; i <= 5; i++) {
      await page.getByLabel("Digit 1 of 6").fill("222222");
      await expect.poll(badTries).toBe(i); // one refused try at a time
    }
    await page.getByLabel("Digit 1 of 6").fill("000000"); // the right code, but the code is burnt after five wrong tries
    await expect.poll(badTries).toBe(6);
    await expect(page.locator(".err")).toContainText("wrong or has expired");
    await page.getByRole("button", { name: "Back" }).first().click();
    await page.getByRole("button", { name: "Send code" }).click();
    await page.getByLabel("Digit 1 of 6").fill("000000");
    await expectLanded(page, "/home");
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
    await page.goto(`/reset/${token}`);
    await expect(page.getByRole("heading", { name: "Link expired" })).toBeVisible();
  });
});
