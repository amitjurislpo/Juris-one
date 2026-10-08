import { expect, test, type Page } from "@playwright/test";
import { OWNER, expectLanded, login } from "./helpers";

const h3 = (page: Page, name: string | RegExp) => page.getByRole("heading", { level: 3, name });

/** REQUIREMENTS §8 demo IDs → where they land and which Home cards they get (§5.12). */
const ROLES: { id: string; who: string; cards: (string | RegExp)[]; not: (string | RegExp)[] }[] = [
  { id: "JC-004", who: "Finance Manager", cards: [/^Payroll ·/, /payslip$/], not: ["Approvals", "My leave requests"] },
  { id: "JC-018", who: "Finance Executive", cards: [/^Payroll ·/, /payslip$/], not: ["Approvals", "My leave requests"] },
  { id: "JC-012", who: "HR Manager", cards: ["Approvals", "Your team right now", /payslip$/], not: [/^Payroll ·/, "My leave requests"] },
  { id: "JC-015", who: "HR Executive", cards: ["Approvals", /payslip$/], not: [/^Payroll ·/, "My leave requests"] },
  { id: "JC-035", who: "Employee with a team", cards: ["Your team right now", /payslip$/], not: ["Approvals", /^Payroll ·/, "My leave requests"] },
  { id: "JC-049", who: "Employee", cards: ["My leave requests", /payslip$/], not: ["Approvals", /^Payroll ·/, "Your team right now"] },
  { id: "JC-090", who: "US employee", cards: ["My leave requests"], not: [/payslip$/, /^Payroll ·/, "Approvals"] },
];

test.describe("home & overview for every demo role", () => {
  for (const r of ROLES) {
    test(`${r.who} (${r.id}) lands on Home with the right cards and no salary`, async ({ page }) => {
      await login(page, r.id);
      await expectLanded(page, "/home");
      await expect(h3(page, "Attendance activity")).toBeVisible();
      await expect(h3(page, "Upcoming holidays")).toBeVisible();
      for (const c of r.cards) await expect(h3(page, c)).toBeVisible();
      for (const c of r.not) await expect(h3(page, c)).toHaveCount(0);
      // Salary is never shown on Home.
      await expect(page.locator("main")).not.toContainText("₹");
      // Admin-only pages stay closed.
      await page.goto("/overview");
      await expectLanded(page, "/home");
      await page.goto("/audit");
      await expectLanded(page, "/home");
    });
  }

  test("Admin (JC-002) lands on the company overview", async ({ page }) => {
    await login(page, "JC-002");
    await expectLanded(page, "/overview");
    await expect(page.getByText("Headcount")).toBeVisible();
    await expect(page.getByText("Checked in now")).toBeVisible();
    for (const c of [/^Payroll ·/, "People by department", "HR queue", "Finance queue", "Shortcuts"]) await expect(h3(page, c)).toBeVisible();
    await expect(page.getByText("net payout")).toBeVisible();
    // Admin has no attendance, leave or payslips of their own.
    await page.goto("/home");
    await expectLanded(page, "/overview");
    await page.locator("main").getByRole("link", { name: "Audit log" }).click();
    await expectLanded(page, "/audit");
  });
});

test.describe("audit log", () => {
  test("Admin filters the log and opens before → after", async ({ page }) => {
    await login(page, OWNER);
    await expectLanded(page, "/overview");
    await page.goto("/audit");
    await expect(page.getByRole("heading", { level: 1 })).toContainText("Audit");
    await page.getByLabel("Event").selectOption("auth");
    await expect(page).toHaveURL(/ev=auth/);
    const rows = page.locator("table.t tbody tr");
    await expect(rows.first()).toContainText("Auth · login");
    for (const t of await rows.allTextContents()) expect(t).toMatch(/Auth ·/);

    await page.getByLabel("Event").selectOption("people");
    await expect(page).toHaveURL(/ev=people/);
    await page.getByLabel("Period").selectOption("All");
    await expect(rows.first()).toContainText("Employee ·");
    await rows.first().click();
    const drawer = page.getByRole("dialog", { name: "Audit entry" });
    await expect(drawer).toBeVisible();
    await expect(drawer.getByText("Before → after")).toBeVisible();
    await expect(drawer.getByText("Who")).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(drawer).toHaveCount(0);

    // sorting by action
    await page.getByRole("button", { name: /^What/ }).click();
    await expect(page).toHaveURL(/sort=action/);
    await page.getByLabel("Search the audit log").fill("zz-no-such-thing");
    await expect(page.getByText("No entries")).toBeVisible();
  });
});

test.describe("responsive & keyboard", () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test("phone: sidebar is a drawer, pages don't scroll sideways", async ({ page }) => {
    await login(page, "JC-074");
    await expectLanded(page, "/home");
    const menu = page.getByRole("complementary", { name: "Main menu" });
    await expect(menu).not.toBeInViewport();
    await page.getByRole("button", { name: "Open menu" }).click();
    await expect(menu).toBeInViewport();
    await menu.getByRole("link", { name: "Leave & holidays" }).click();
    await expectLanded(page, "/leave");
    await expect(menu).not.toBeInViewport();

    for (const path of ["/home", "/leave", "/attendance", "/payslips", "/declare", "/directory"]) {
      await page.goto(path);
      await expect(page.locator("main h1")).toBeVisible();
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      expect(overflow, `${path} scrolls sideways`).toBeLessThanOrEqual(1);
    }
  });

  test("keyboard: skip link jumps to the page content", async ({ page }) => {
    await login(page, "JC-049");
    await expectLanded(page, "/home");
    // fresh load: after the sign-in redirect Chrome keeps its Tab start point where the form was
    await page.goto("/home", { waitUntil: "networkidle" });
    await page.keyboard.press("Tab");
    const skip = page.getByRole("link", { name: "Skip to content" });
    await expect(skip).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page.locator("main")).toBeFocused();
  });
});
