import { expect, test } from "@playwright/test";
import { expectLanded, login } from "./helpers";

test.describe("server-side page permissions", () => {
  test("employee cannot open finance or admin pages by URL", async ({ page }) => {
    await login(page, "JC-049");
    await expectLanded(page, "/home");
    for (const path of ["/payroll", "/roles", "/employees", "/register", "/overview"]) {
      await page.goto(path);
      await expectLanded(page, "/home");
    }
  });

  test("HR manager never gets finance pages", async ({ page }) => {
    await login(page, "JC-012");
    await expectLanded(page, "/home");
    await expect(page.getByRole("link", { name: "Employees", exact: true })).toBeVisible();
    for (const name of ["Payroll", "Salary structures", "Tax & TDS", "Investment proofs"]) await expect(page.getByRole("link", { name })).toHaveCount(0);
    await page.goto("/structures");
    await expectLanded(page, "/home");
  });

  test("manager with reports sees My team; others do not", async ({ page }) => {
    await login(page, "JC-035");
    await expectLanded(page, "/home");
    await expect(page.getByRole("link", { name: "My team" })).toBeVisible();
    await page.context().clearCookies();
    await login(page, "JC-058");
    await expectLanded(page, "/home");
    await expect(page.getByRole("link", { name: "My team" })).toHaveCount(0);
  });

  test("finance manager sees the finance group", async ({ page }) => {
    await login(page, "JC-004");
    await expectLanded(page, "/home");
    for (const name of ["Payroll", "Salary structures", "Tax & TDS", "Investment proofs", "Approvals"]) await expect(page.getByRole("complementary", { name: "Main menu" }).getByRole("link", { name })).toBeVisible();
    await expect(page.getByRole("link", { name: "Attendance register" })).toHaveCount(0);
  });
});

test.describe("shell", () => {
  test("command palette navigates and the theme persists", async ({ page }) => {
    await login(page, "JC-074");
    await expectLanded(page, "/home");
    await page.keyboard.press("Control+k");
    await page.getByLabel("Command", { exact: true }).fill("direct");
    await page.keyboard.press("Enter");
    await expectLanded(page, "/directory");
    await page.getByRole("button", { name: "Switch to dark theme" }).click();
    await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
    await page.reload();
    await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
    await page.getByRole("button", { name: "Switch to light theme" }).click();
    await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  });
});
