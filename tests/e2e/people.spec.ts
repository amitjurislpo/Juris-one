import { expect, test, type Page } from "@playwright/test";
import { expectLanded, login, OWNER, sql } from "./helpers";

const NEW_EMAIL = "e2e.meera.joshi@jurislpo.com";
const DEPT = "E2E Intellectual Property";

async function cleanup() {
  await sql(`DELETE FROM notifications WHERE text LIKE '%Meera Joshi%'`);
  await sql(`DELETE FROM email_outbox WHERE "to" LIKE 'e2e.%'`);
  await sql(`DELETE FROM employees WHERE email LIKE 'e2e.%'`);
  await sql(`DELETE FROM departments WHERE name LIKE 'E2E %'`);
  await sql(`DELETE FROM roles WHERE name LIKE 'E2E %'`);
  await sql(`UPDATE employees SET portal_access = true WHERE code = 'JC-049'`);
  await sql(`DELETE FROM role_permissions WHERE permission_key = 'att.all' AND role_id = (SELECT id FROM roles WHERE key = 'employee')`);
  await sql(`UPDATE users SET failed_attempts = 0, locked_until = NULL`);
}

const toast = (page: Page) => page.locator(".toast").last();

test.describe("people operations", () => {
  test.beforeAll(cleanup);
  test.afterAll(cleanup);

  test("HR Manager adds an India employee in three steps; Finance is notified and an invite is sent", async ({ page }) => {
    await login(page, "JC-012");
    await expectLanded(page, "/home");
    await page.goto("/people");
    await page.getByRole("button", { name: "Add employee" }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog.getByText(/New hire · JC-\d{3}/)).toBeVisible();

    // step 1 — validation then fill
    await dialog.getByRole("button", { name: "Continue" }).click();
    await expect(dialog.getByText("First name is required")).toBeVisible();
    await expect(dialog.getByText("Last name is required")).toBeVisible();
    await dialog.getByLabel("First name *").fill("Meera");
    await dialog.getByLabel("Last name *").fill("Joshi");
    await dialog.getByLabel("Father’s name *").fill("Prakash Joshi");
    await dialog.getByLabel("Work email *").fill(NEW_EMAIL);
    // the number box takes digits only, up to the country's length
    await dialog.getByLabel("Phone number", { exact: true }).fill("98765 43210 99");
    await expect(dialog.getByLabel("Phone number", { exact: true })).toHaveValue("9876543210");
    await dialog.getByRole("button", { name: "Continue" }).click();

    // step 2 — PAN format enforced
    await dialog.getByLabel(/PAN/).fill("BAD");
    await dialog.getByLabel(/Aadhaar/).fill("2345 6789 0123");
    await dialog.getByRole("button", { name: "Continue" }).click();
    await expect(dialog.getByText("PAN looks like ABCDE1234F")).toBeVisible();
    await dialog.getByLabel(/PAN/).fill("ABCPJ1234K");
    await dialog.getByLabel("Address").fill("12 MG Road, Noida, Uttar Pradesh 201301");
    await dialog.getByRole("button", { name: "Continue" }).click();

    // step 3 — HR Manager may only assign HR Executive / Employee
    const roles = dialog.getByRole("radiogroup", { name: "Role" }).getByRole("radio");
    await expect(roles).toHaveCount(2);
    await expect(dialog.getByRole("radio", { name: /HR Executive/ })).toBeVisible();
    await expect(dialog.getByRole("radio", { name: /Finance/ })).toHaveCount(0);
    await dialog.getByLabel("Designation *").fill("Associate — IP");
    await dialog.getByLabel("Department *").selectOption({ label: "Technology" });
    await dialog.getByLabel(/Reporting manager/).selectOption({ label: "Nazneen Khan — Engineering Manager · Technology" });
    await dialog.getByRole("button", { name: "Create employee" }).click();
    await expect(toast(page)).toContainText("Meera Joshi added as JC-");
    await expect(toast(page)).toContainText("salary structure pending");

    const [e] = await sql<{ code: string; status: string; aadhaar_enc: string; aadhaar_last4: string; pan_last4: string }>(`SELECT code, status, aadhaar_enc, aadhaar_last4, pan_last4 FROM employees WHERE email = $1`, [NEW_EMAIL]);
    expect(e.status).toBe("PROBATION");
    expect(e.aadhaar_enc).not.toContain("234567890123");
    expect(e.aadhaar_last4).toBe("0123");
    const notes = await sql(`SELECT 1 FROM notifications n JOIN employees x ON x.id = n.employee_id WHERE x.code = 'JC-004' AND n.text LIKE '%Meera Joshi%salary structure pending%'`);
    expect(notes.length).toBe(1);
    const mails = await sql(`SELECT 1 FROM email_outbox WHERE "to" = $1 AND subject LIKE 'Welcome to JurisOne%'`, [NEW_EMAIL]);
    expect(mails.length).toBe(1);
    const auditRows = await sql(`SELECT 1 FROM audit_log WHERE action = 'employee.create' AND entity_id = (SELECT id::text FROM employees WHERE email = $1)`, [NEW_EMAIL]);
    expect(auditRows.length).toBe(1);

    // search finds them; drawer shows masked IDs and no salary for HR
    await page.getByLabel("Search people").fill("Meera");
    await expect(page.locator("tbody tr")).toHaveCount(1);
    await page.getByRole("button", { name: "Meera Joshi" }).click();
    const drawer = page.getByRole("dialog", { name: "Employee profile" });
    await expect(drawer.getByText("XXXX XXXX 0123")).toBeVisible();
    await expect(drawer.getByText("Salary and tax details are visible to Finance only.")).toBeVisible();
    await expect(drawer.getByText("Monthly gross")).toHaveCount(0);

    // mark as past employee
    await drawer.getByRole("button", { name: "Mark as past employee" }).click();
    await page.getByRole("button", { name: "Mark as exited" }).click();
    await expect(toast(page)).toContainText("marked as a past employee");
    const [x] = await sql<{ status: string; portal_access: boolean }>(`SELECT status, portal_access FROM employees WHERE email = $1`, [NEW_EMAIL]);
    expect(x).toEqual({ status: "EXITED", portal_access: false });
  });

  test("a manager with direct reports can't be exited until they're reassigned", async ({ page }) => {
    await login(page, "JC-012");
    await expectLanded(page, "/home");
    await page.goto("/people?q=Nazneen");
    await page.getByRole("button", { name: "Nazneen Khan" }).click();
    await page.getByRole("button", { name: "Mark as past employee" }).click();
    await page.getByRole("button", { name: "Mark as exited" }).click();
    await expect(toast(page)).toContainText("Assign them a new manager first");
  });

  test("access toggle: HR disables an employee; the owner (admin) isn't in the list at all", async ({ page }) => {
    await login(page, "JC-012");
    await expectLanded(page, "/home");
    await page.goto("/people?q=Naman");
    await page.getByRole("switch", { name: /portal access for Naman Tyagi/ }).click();
    await expect(toast(page)).toContainText("Naman Tyagi can no longer sign in");
    const [row] = await sql<{ portal_access: boolean }>(`SELECT portal_access FROM employees WHERE code = 'JC-049'`);
    expect(row.portal_access).toBe(false);
    const logged = await sql(`SELECT 1 FROM audit_log WHERE action = 'employee.access_toggle' AND entity_id = (SELECT id::text FROM employees WHERE code = 'JC-049')`);
    expect(logged.length).toBeGreaterThan(0);

    await page.goto("/people?q=Sana");
    await expect(page.getByRole("button", { name: "Sana Kapoor" })).toHaveCount(0); // (she may still show as someone's manager)
    // nor is the signed-in person
    await page.goto("/people?q=JC-012");
    await expect(page.locator("tbody tr")).toHaveCount(0);
  });

  test("filters, sorting and past employees", async ({ page }) => {
    await login(page, "JC-012");
    await expectLanded(page, "/home");
    await page.goto("/people");
    await expect(page.getByText("Vikas Rao")).toHaveCount(0); // Current by default
    await page.getByLabel("Status").selectOption("EXITED");
    await expect(page.getByRole("button", { name: "Vikas Rao" })).toBeVisible();
    await page.getByLabel("Status").selectOption("Current");
    await page.getByRole("button", { name: /^Employee/ }).click(); // sort by name (asc → desc)
    await expect(page).toHaveURL(/sort=name&dir=desc/);
    const first = await page.locator("tbody tr").first().locator(".person b").innerText();
    expect(first.localeCompare("S")).toBeGreaterThan(0);
  });

  test("Finance Manager adds into Finance only, as Finance Executive, and sees salary", async ({ page }) => {
    await login(page, "JC-004");
    await expectLanded(page, "/home");
    await page.goto("/people?q=Amit");
    await page.getByRole("button", { name: "Amit Jaspal" }).click();
    await expect(page.getByRole("dialog", { name: "Employee profile" }).getByText("₹1,50,000")).toBeVisible();
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: "Add employee" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("First name *").fill("E2E Finance");
    await dialog.getByLabel("Last name *").fill("Hire");
    await dialog.getByLabel("Father’s name *").fill("Some Father");
    await dialog.getByLabel("Work email *").fill("e2e.fin.hire@jurislpo.com");
    await dialog.getByLabel("Phone number", { exact: true }).fill("9876500000");
    await dialog.getByRole("button", { name: "Continue" }).click();
    await dialog.getByLabel(/PAN/).fill("ABCPF1234K");
    await dialog.getByLabel(/Aadhaar/).fill("2345 6789 0000");
    await dialog.getByRole("button", { name: "Continue" }).click();
    await expect(dialog.getByLabel("Department *")).toBeDisabled();
    await expect(dialog.getByLabel("Department *")).toHaveValue(/.+/);
    await expect(dialog.getByText("You can add people to your own department only")).toBeVisible();
    await expect(dialog.getByRole("radio")).toHaveCount(1);
    await expect(dialog.getByRole("radio", { name: /Finance Executive/ })).toBeVisible();
  });

  test("HR Executive can view people but not add or toggle", async ({ page }) => {
    await login(page, "JC-015");
    await expectLanded(page, "/home");
    await page.goto("/people");
    await expect(page.getByRole("button", { name: "Add employee" })).toHaveCount(0);
    await expect(page.getByRole("switch").first()).toBeDisabled();
  });

  test("export is permission-checked on the server", async ({ page, request }) => {
    await login(page, "JC-012");
    await expectLanded(page, "/home");
    const ok = await page.request.get("/api/people/export?status=All");
    expect(ok.status()).toBe(200);
    const csv = await ok.text();
    expect(csv).toContain("Employee ID,Name,Email");
    expect(csv).toContain("Vikas Rao");
    expect(csv).not.toMatch(/Aadhaar|PAN|gross/i);
    expect(csv).not.toMatch(/^JC-002,/m); // the owner is never exported (only named as a manager)
    expect((await request.get("/api/people/export")).status()).toBe(403); // no session
    await page.context().clearCookies();
    await login(page, "JC-074");
    await expectLanded(page, "/home");
    expect((await page.request.get("/api/people/export")).status()).toBe(403);
  });
});

test.describe("directory, departments, roles, team", () => {
  test.afterAll(cleanup);

  test("directory shows only name, email, designation and status", async ({ page }) => {
    await login(page, "JC-074");
    await expectLanded(page, "/home");
    await page.goto("/directory");
    await expect(page.locator("thead th")).toHaveText([/Name/, /Email/, /Designation/, /Status/]);
    await page.getByRole("button", { name: "Past employees" }).click();
    await expect(page.getByText("Vikas Rao")).toBeVisible();
    await expect(page.locator("tbody tr")).toHaveCount(1);
    const table = await page.locator("table").innerText();
    expect(table).not.toMatch(/JC-030|Litigation Support/);
    expect(table).toContain("vikas.rao@jurislpo.com");
  });

  test("departments: create, reject duplicate, delete", async ({ page }) => {
    await login(page, "JC-012");
    await expectLanded(page, "/home");
    await page.goto("/departments");
    await page.getByRole("button", { name: "New department" }).click();
    await page.getByLabel("Department name *").fill(DEPT);
    await page.getByRole("button", { name: "Create department" }).click();
    await expect(toast(page)).toContainText(`Department “${DEPT}” created`);
    await expect(page.getByRole("cell", { name: DEPT, exact: true })).toBeVisible();
    await page.getByRole("button", { name: "New department" }).click();
    await page.getByLabel("Department name *").fill(DEPT.toLowerCase());
    await page.getByRole("button", { name: "Create department" }).click();
    await expect(page.locator(".modal .err")).toContainText("already exists");
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: `Delete ${DEPT}` }).click();
    await page.getByRole("button", { name: "Delete", exact: true }).click();
    await expect(toast(page)).toContainText("removed");
    // departments with current employees have no delete button
    await expect(page.getByRole("button", { name: "Delete Technology" })).toHaveCount(0);
  });

  test("roles: the owner toggles a permission (effective immediately) and manages custom roles", async ({ page, browser }) => {
    await login(page, OWNER);
    await expectLanded(page, "/overview");
    await page.goto("/roles");
    await page.getByRole("button", { name: /^Employee \d/ }).click();
    const attAll = page.getByRole("switch", { name: "View everyone’s attendance (with time zones)" });
    await expect(attAll).toHaveAttribute("aria-checked", "false");
    await attAll.click();
    await expect(toast(page)).toContainText("granted");

    // an employee now sees the register — permissions are read per request
    const emp = await browser.newPage();
    await login(emp, "JC-058");
    await expectLanded(emp, "/home");
    await expect(emp.getByRole("link", { name: "Attendance register" })).toBeVisible();

    await attAll.click();
    await expect(toast(page)).toContainText("removed");
    await emp.reload();
    await expect(emp.getByRole("link", { name: "Attendance register" })).toHaveCount(0);
    await emp.close();

    // the Owner role is locked
    await page.getByRole("button", { name: /^Owner \d/ }).click();
    await expect(page.getByRole("switch").first()).toBeDisabled();

    // custom role: create from a copy, then delete
    await page.getByRole("button", { name: "New role" }).click();
    await page.getByLabel("Role name *").fill("E2E Payroll Auditor");
    await page.getByLabel("Start from").selectOption({ label: "Finance Executive" });
    await page.getByRole("button", { name: "Create role" }).click();
    await expect(toast(page)).toContainText("E2E Payroll Auditor");
    await expect(page.getByRole("switch", { name: "View & edit payroll" })).toHaveAttribute("aria-checked", "true");
    await page.getByRole("button", { name: "Delete role" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Delete role" }).click();
    await expect(toast(page)).toContainText("deleted");

    // policies matrix reflects the roles table
    await page.goto("/settings");
    await expect(page.locator("table.matrix thead th")).toHaveCount(7);
  });

  test("My team lists current direct reports only", async ({ page }) => {
    await login(page, "JC-035");
    await expectLanded(page, "/home");
    await page.goto("/team");
    await expect(page.locator("section table").first().locator("tbody tr")).toHaveCount(5);
    await expect(page.getByText("Vikas Rao")).toHaveCount(0);
    await expect(page.getByText("Who's away")).toBeVisible();
  });
});
