import { expect, test, type Page } from "@playwright/test";
import { OWNER, expectLanded, login, sql } from "./helpers";

const toast = (page: Page) => page.locator(".toast").last();
const T0 = new Date().toISOString();

type PunchRow = { id: string; employee_id: string; at: Date; kind: string; source: string };
let removed: PunchRow[] = [];

test.describe("attendance", () => {
  test.beforeAll(async () => {
    // Make 5 Oct 2026 an unregularized absence for JC-074 (open month) — restored afterwards.
    removed = await sql<PunchRow>(`SELECT p.id, p.employee_id, p.at, p.kind, p.source FROM punches p JOIN employees e ON e.id = p.employee_id
      WHERE e.code = 'JC-074' AND p.at >= '2026-10-05T13:00:00Z' AND p.at < '2026-10-06T06:00:00Z'`);
    if (removed.length) await sql(`DELETE FROM punches WHERE id = ANY($1::uuid[])`, [removed.map((r) => r.id)]);
  });
  test.afterAll(async () => {
    await sql(`DELETE FROM punches WHERE created_at >= $1`, [T0]); // punches made by these tests
    for (const r of removed) await sql(`INSERT INTO punches (id, employee_id, at, kind, source) VALUES ($1, $2, $3, $4, $5) ON CONFLICT DO NOTHING`, [r.id, r.employee_id, r.at, r.kind, r.source]);
    await sql(`DELETE FROM regularization_requests WHERE created_at >= $1`, [T0]);
    await sql(`DELETE FROM holidays WHERE name LIKE 'E2E %'`);
    await sql(`UPDATE settings SET value = jsonb_set(value, '{lateMarksPerHalfDayLop}', '3') WHERE key = 'attendance.policy'`);
  });

  test("check in and check out from Home", async ({ page }) => {
    await login(page, "JC-058");
    await expectLanded(page, "/home");
    await expect(page.getByText("Night shift · 7:30 PM – 4:30 AM IST")).toBeVisible();
    await page.getByRole("button", { name: "Check in" }).click();
    await expect(toast(page)).toContainText("Checked in");
    await expect(page.locator(".timecard .chip")).toHaveText("Checked in");
    await expect(page.locator(".timeline")).toContainText("Checked in");
    await expect(page.locator(".timeline")).toContainText("Web");

    // a stale second tab can't record a duplicate check-in
    const again = await sql<{ n: string }>(`SELECT count(*)::text n FROM punches p JOIN employees e ON e.id = p.employee_id WHERE e.code = 'JC-058' AND p.created_at >= $1`, [T0]);
    expect(again[0].n).toBe("1");

    // pretend the check-in was 2 hours ago, then check out
    await sql(`UPDATE punches SET at = at - interval '2 hours' WHERE created_at >= $1 AND employee_id = (SELECT id FROM employees WHERE code = 'JC-058')`, [T0]);
    await page.reload();
    await page.getByRole("button", { name: "Check out" }).click();
    await expect(toast(page)).toContainText("Checked out — 2h");
    await expect(page.locator(".timecard .chip")).toHaveText("Checked out");
    const rows = await sql<{ kind: string; ip: string | null }>(`SELECT kind, ip FROM punches WHERE created_at >= $1 AND employee_id = (SELECT id FROM employees WHERE code = 'JC-058') ORDER BY at`, [T0]);
    expect(rows.map((r) => r.kind)).toEqual(["IN", "OUT"]);
  });

  test("Admin has no check-in or attendance pages", async ({ page }) => {
    await login(page, OWNER);
    await expectLanded(page, "/overview");
    await page.goto("/attendance");
    await expectLanded(page, "/overview");
  });

  test("My attendance: locked September shows the golden-case figures", async ({ page }) => {
    await login(page, "JC-074");
    await expectLanded(page, "/home");
    await page.goto("/attendance?m=2026-09");
    await expect(page.getByRole("heading", { level: 1 })).toContainText("September");
    const kpi = (label: string) => page.locator(".kpi", { hasText: label }).locator(".v");
    await expect(kpi("Absent · loss of pay")).toHaveText("1");
    await expect(kpi("Present")).toHaveText("15");
    await page.getByRole("button", { name: /Tue, 22 Sep 2026/ }).click();
    await expect(page.getByText("Unpaid leave", { exact: true })).toBeVisible(); // the unapplied absence was booked as leave without pay
    // a late day could be regularized in an open month; September is locked
    await page.getByRole("button", { name: /Thu, 17 Sep 2026.*late/ }).click();
    await expect(page.getByText(/this month is locked/)).toBeVisible();
    await expect(page.getByRole("button", { name: "Request regularization" })).toHaveCount(0);
    await page.getByRole("button", { name: /Mon, 7 Sep 2026/ }).click();
    await expect(page.locator(".chip", { hasText: "Before joining" })).toBeVisible();
  });

  test("My attendance: request and withdraw a regularization in an open month", async ({ page }) => {
    await login(page, "JC-074");
    await expectLanded(page, "/home");
    await page.goto("/attendance?m=2026-10&d=2026-10-05");
    await expect(page.locator(".chip", { hasText: "Absent · LOP" }).first()).toBeVisible();
    await page.getByRole("button", { name: "Request regularization" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel(/Actual check-in/).fill("19:35");
    await dialog.getByLabel(/What happened/).fill("Biometric outage — worked from client site");
    await dialog.getByRole("button", { name: "Send for approval" }).click();
    await expect(toast(page)).toContainText("Regularization sent");
    await expect(page.getByText(/regularization pending — waiting for HR, Finance or Admin/)).toBeVisible();
    const [r] = await sql<{ kind: string; status: string; proposed_in: Date }>(`SELECT kind, status, proposed_in FROM regularization_requests WHERE created_at >= $1`, [T0]);
    expect(r).toMatchObject({ kind: "ABSENT", status: "PENDING" });
    expect(new Date(r.proposed_in).toISOString()).toBe("2026-10-05T14:05:00.000Z");
    await page.getByRole("button", { name: "Withdraw request" }).click();
    await expect(toast(page)).toContainText("withdrawn");
    await expect(page.getByRole("button", { name: "Request regularization" })).toBeVisible();
  });

  test("holidays: HR adds by region; duplicates blocked; India and US see their own", async ({ page }) => {
    await login(page, "JC-012");
    await expectLanded(page, "/home");
    await page.goto("/holidays");
    await page.getByLabel("Date *").fill("2026-12-31");
    await page.getByLabel("Holiday name *").fill("E2E New Year's Eve");
    await page.getByRole("button", { name: "US team" }).click();
    await page.getByRole("button", { name: "Add to calendar" }).click();
    await expect(toast(page)).toContainText("E2E New Year's Eve added for US team");
    await page.getByLabel("Date *").fill("2026-12-31");
    await page.getByLabel("Holiday name *").fill("E2E Clash");
    await page.getByRole("button", { name: "Everyone" }).click();
    await page.getByRole("button", { name: "Add to calendar" }).click();
    await expect(page.locator(".card .err")).toContainText("already a holiday");
    await expect(page.getByRole("cell", { name: "E2E New Year's Eve", exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: /Remove Republic Day/ })).toHaveCount(0); // past → no delete

    await page.context().clearCookies();
    await login(page, "JC-090"); // US, New York
    await expectLanded(page, "/home");
    await page.goto("/attendance");
    await expect(page.getByText("Thanksgiving Day")).toBeVisible();
    await expect(page.getByText("Dussehra")).toHaveCount(0);
    await page.context().clearCookies();
    await login(page, "JC-049"); // India
    await expectLanded(page, "/home");
    await page.goto("/attendance");
    await expect(page.getByText("Dussehra").first()).toBeVisible(); // today is Dussehra: calendar cell + holiday card
    await expect(page.getByText("Thanksgiving Day")).toHaveCount(0);
  });

  test("register: time zones, (+1) marks, grid, export and access", async ({ page }) => {
    await login(page, "JC-012");
    await expectLanded(page, "/home");
    await page.goto("/register?day=2026-09-22&q=Amit");
    await expect(page.locator("tbody tr").first()).toContainText("On leave"); // leave without pay
    await page.goto("/register?day=2026-09-16&q=Kavya");
    const row = page.locator("tbody tr").first();
    await expect(row).toContainText("Present");
    await expect(row.locator("td").nth(4)).toContainText("(+1)"); // night-shift check-out, IST view
    await page.getByLabel("Show times in").selectOption("America/New_York");
    await expect(row.locator("td").nth(3)).toContainText("EDT");
    await expect(row.locator("td").nth(3)).toContainText("local");
    await page.goto("/register?view=month&ym=2026-09&q=Amit");
    const grid = page.locator("table.reg tbody tr").first();
    await expect(grid.locator("td").nth(22)).toHaveText("L"); // 22 Sep: leave without pay
    const exp = await page.request.get("/api/attendance/register?view=month&ym=2026-09");
    expect(exp.status()).toBe(200);
    expect(await exp.text()).toContain("Amit Jaspal");

    await page.context().clearCookies();
    await login(page, "JC-074");
    await expectLanded(page, "/home");
    await page.goto("/register");
    await expectLanded(page, "/home");
    expect((await page.request.get("/api/attendance/register")).status()).toBe(403);
  });

  test("policies: Admin edits the attendance policy", async ({ page }) => {
    await login(page, OWNER);
    await expectLanded(page, "/overview");
    await page.goto("/settings?tab=att");
    await expect(page.getByLabel("Attendance go-live")).toBeDisabled();
    await page.getByLabel("Late marks per ½ day LOP").fill("4");
    await page.getByRole("button", { name: "Save policy" }).click();
    await expect(toast(page)).toContainText("Attendance policy saved");
    await expect(page.getByText("4 in a month = ½ day loss of pay")).toBeVisible();
    const logged = await sql(`SELECT 1 FROM audit_log WHERE action = 'policy.attendance_change' AND at >= $1`, [T0]);
    expect(logged.length).toBe(1);
  });
});
