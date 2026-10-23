# Juris People — Product Requirements

> **Working name:** Juris People (HRMS + payroll portal for Juris Consultants).
> To rename, replace "Juris People" everywhere in this file.
> **Design reference:** `Juris-People-Design-8.html`, the clickable prototype. It sets the look, flows and copy. This document sets the rules. If the two disagree, this document wins.

---

## 1. What we are building

One web portal for **Juris Consultants** that covers:

- attendance (check-in / check-out, time tracking, shifts)
- leave and holidays
- employee records and departments
- roles and permissions
- monthly payroll calculated from attendance
- income tax / TDS and investment declarations
- payslip PDFs

There is **one** portal for **one** company. There is no company switcher and no role picker on login. What a user sees comes from the role on their employee record.

Users are in two regions:

- **India team**: IST. Day and night shifts. Paid through this portal's payroll.
- **US team**: ET, CT or PT. Day shift. Attendance and leave are tracked here, but US employees are **not** on Indian payroll.

---

## 2. Tech stack

| Layer | Choice |
|---|---|
| Database | **PostgreSQL 16** (required) |
| App | Next.js (App Router) + TypeScript, server actions / route handlers |
| ORM / migrations | Prisma (or Drizzle). Every schema change goes through a migration. |
| Auth | Email + emailed one-time code sign-in (no passwords). HTTP-only session cookies. |
| Styling | Port the prototype's CSS tokens (light default + dark theme, violet accent `#6E56CF`). Fonts: **Balkind** for display headings (needs a commercial licence; the prototype uses the demo file), **Geist** for UI text, **Geist Mono** for numbers. |
| PDF | Server-side payslip PDF (e.g. pdf-lib or Playwright print), with the company logo |
| Files | Investment proofs on S3-compatible storage (local disk in dev) |
| Jobs | Scheduled jobs (pg-boss or cron): daily absence marking, monthly leave accrual, attendance lock |
| Dev | `docker-compose` for Postgres, `.env.example`, seed script with demo data |
| Tests | Unit tests for payroll and tax maths. Playwright end-to-end tests per role. |

Rules that apply to the whole stack:

- **Store every timestamp as `timestamptz` (UTC).** Each employee has an IANA time zone (e.g. `Asia/Kolkata`, `America/New_York`). Convert only when displaying.
- **Store all money as integer paise** (or `numeric(12,2)`). Never use floats.

---

## 3. Roles and permissions

### 3.1 Permission keys

Access is **permission-based**, not hard-coded by role name. Every API endpoint and every page checks permissions **on the server**. Hiding things in the UI is not enough.

| Key | Meaning |
|---|---|
| `self` | Own attendance, leave, payslips and tax declarations |
| `dir` | Employee directory |
| `att.all` | View everyone's attendance (attendance register, time-zone toggle) |
| `appr.leave` | Approve or decline leave |
| `appr.reg` | Approve or decline attendance regularization |
| `people.view` | View employee records |
| `people.add` | Add and edit employees |
| `access.toggle` | Enable or disable an employee's portal access |
| `dept.manage` | Create departments |
| `holidays.manage` | Manage the holiday calendar |
| `pay.view` | View and edit payroll (LOP, bonus, other deductions) |
| `pay.close` | Approve, close payroll and publish payslips |
| `struct.manage` | Salary structures; see salary figures |
| `tax.view` | Tax and TDS for all employees, override regime |
| `proofs.verify` | Verify investment proofs |
| `roles.manage` | Roles, permissions, policies |
| `tasks.manage` | Task settings (default steps, company labels) and **every project and task** of the business, private ones included (the Owner only; opening something private is audited) |
| `projects.create` | Create task projects (Owner and HR Manager by default). Using tasks needs no permission |

### 3.2 Default roles (role templates, set by the super admin)

The super admin keeps a catalogue of **role templates** (Platform → role templates; the table below is what is seeded). When a business is created the super admin picks its roles: Owner (Admin), HR Manager, Finance Manager and Employee are mandatory and always added, the rest (HR Executive, Finance Executive and any template the super admin adds) are optional; roles can also be added to or removed from a business later. A business's role is a **copy** of its template taken at that moment: changing a template doesn't alter existing businesses until the super admin syncs the role. The business owner (sub admin) can **view** roles but can't create, edit or delete them or toggle their permissions: only the super admin can (the API answers 403 "Roles are set up for your business by JurisOne."). Salary, payroll and tax permissions can't go into an HR template.

| Role | Permissions | Can add employees with role |
|---|---|---|
| **Admin** | Everything **except `self`**: no attendance, leave or payslip of their own | All roles |
| **HR Manager** | self, dir, att.all, appr.leave, appr.reg, people.view, people.add, access.toggle, dept.manage, holidays.manage, projects.create | HR Executive, Employee |
| **HR Executive** | self, dir, att.all, appr.leave, appr.reg, people.view | none |
| **Finance Manager** | self, dir, appr.reg, people.view, people.add, access.toggle, dept.manage, pay.view, pay.close, struct.manage, tax.view, proofs.verify | Finance Executive (into **Finance dept only**) |
| **Finance Executive** | self, dir, appr.reg, pay.view, tax.view, proofs.verify (**cannot close payroll**) | none |
| **Employee** | self, dir | none |

`tasks.manage` is on the Owner (Admin) template only; `projects.create` on the Owner and HR Manager templates (Finance Manager and the other roles do not create projects, but anyone can be made a project owner).

### 3.3 Rules

- **Hierarchy:** Admin adds the HR Manager and the Finance Manager. The HR Manager adds HR Executives and Employees. The Finance Manager adds Finance Executives.
- **No salary for HR:** HR roles never see salary, CTC, payslip amounts or tax of other employees. Only roles with `struct.manage` / `pay.view` / `tax.view` (Finance) and Admin can.
- **Roles page (Admin):** read-only for the business owner. The super admin can, from the platform console or this page, toggle permissions, create roles from a copy, set which roles each role may assign, and delete a role only when nobody has it; the Admin role is locked.
- **Manager is a relationship, not a role.** Anyone who has active direct reports (via `reporting_manager_id`) automatically gets a **My team** page.
- **Editing employees:** a user can edit an employee only if they have `people.add` **and** the target's role is in their assignable list (Admin can edit anyone). Nobody edits their own record or role.
- **Access toggle:**
  - needs `access.toggle`
  - you cannot toggle yourself
  - a non-Admin cannot toggle an Admin
  - toggling is logged in the audit log
- **The policies page** shows a read-only permission matrix generated from the roles table.

---

## 4. Authentication

- Login uses the official email plus a 6-digit code emailed to it (no passwords). Email is the primary sign-in identity for every user. There is no role selector.
- **Blocked logins:**
  - Disabled access shows: "Your portal access is disabled. Please contact HR."
  - A past (exited) employee shows: "This account belongs to a past employee and can no longer sign in."
  - **Subscription not started, or none:** nobody in the business can sign in ("Your organisation's subscription starts on <date>…" / "…has no active subscription…").
  - **Business suspended by the super admin:** nobody in it can sign in (the admin included) and every active session ends at once; reactivating the business lets everyone sign in again (with a fresh code).
  - **Subscription expired:** users can sign in and view all their data; the owner can export. Every change is refused by the API (402) and the screens hide the actions.
- **Session revocation:** disabling access or marking an employee exited **ends their active sessions immediately**.
- **New employee onboarding:** there is no invite or password step; once portal access is on, the new employee signs in with the code emailed to their official email.
- **Login protection:** rate-limit login attempts, lock the account after repeated failures, and keep a login history (time, IP, device).
- **After login, the landing page is:**
  - **Home** for anyone with `self`
  - **Overview** for Admin

---

## 5. Modules

### 5.1 Departments

- Only these are seeded: **Human Resources** and **Finance**, created by Admin. Every other department is created by users with `dept.manage` (HR, Finance, Admin).
- **Fields:** name (unique), head (optional employee), description, created_by, created_at.
- A department can be deleted only when it has no current employees.
- The list page has search, sort, created-by and headcount.

### 5.2 Employees (People)

**Add/edit form: 3 steps.**

1. **Personal:**
   - required: full name, father's name, email, phone
   - work location: India-IST / US-ET / US-CT / US-PT, which sets the time zone
   - **Phone numbers (employee, emergency contact and the business contact phone) are validated per country code:** the country code is picked first and only a valid national number for it can be entered (for example +91 takes exactly 10 digits starting 6-9). The business contact phone is stored as `+91 8888788888`.
   - optional **photo** (see Profile photos); the employee can also set it from My profile.
2. **Identity & address** (India only):
   - required: PAN (format `ABCDE1234F`), Aadhaar (12 digits)
   - optional: UAN (12 digits)
   - address (both regions)
   - US employees skip the Indian IDs.
3. **Job & access:**
   - designation, department (from the departments table), reporting manager, date of joining
   - shift: India Night 7:30 PM–4:30 AM IST, India Day 9:30 AM–6:30 PM IST, US Day 9:00 AM–6:00 PM local
   - role: only from the creator's assignable roles. A Finance Manager's department is locked to Finance.

**System-generated fields:**

- Employee ID is generated as `JC-###` (next number).
- Status:
  - Onboarding if joining is in the future
  - Probation, then Active
  - Exited, which needs a last working day
  - **Probation → permanent:** automatic after the probation period (default 6 months), or earlier when the business admin (owner) or HR clicks **Make permanent** on the employee's profile (`PUT /api/people/{id}/status` with `{"status": "ACTIVE"}`, needs `people.add`; audited as `employee.confirm`). Nobody else can change an employee's status.
  - **Role changes:** only the business admin and HR may change an existing employee's role; anyone else who can edit the person sees just the current role, and the API refuses a change.

**Sensitive data:**

- **PAN, Aadhaar, UAN, phone and address are PII.** Encrypt them at rest (application-level or pgcrypto) and show only masked values in the UI (`XXXX XXXX 1234`).
- Follow UIDAI guidance for Aadhaar: store encrypted, display only the last 4 digits, restrict access to `people.view`.

**People list** (`people.view`):

- search (name, ID, email, designation, manager)
- filters (department, status incl. "Past employees", access enabled/disabled)
- sortable columns
- portal access switch per row

**Profile drawer:**

- shows all details, direct reports, location and shift
- shows monthly gross **only** to `struct.manage`
- actions: Edit, Mark as past employee (sets exit date, removes access)

When a new India employee is added, Finance is notified: "salary structure pending".

### 5.3 Directory (`dir`, everyone)

- Search any employee, past or present.
- Show **only**: name, official email, designation, and status as **Active** or **Past employee**.
- Has search, a department filter, an Active/Past filter and sorting.

**Profile photos.** Every employee may have a photo (JPEG, PNG or WebP, at most 400 KB; the type is checked from the image's own bytes). The employee sets or removes their own (`PUT`/`DELETE /api/profile/photo`); HR and the business admin can do it for anyone they may edit (`PUT`/`DELETE /api/people/{id}/photo`, never an exited employee). Anyone signed in to the business can read it (`GET /api/people/{id}/photo`, cached for an hour with an ETag). Lists carry `photo` (a version number, null = no photo) so screens show the image in the circle and fall back to initials. Both changes are audited (`employee.photo_set`, `employee.photo_remove`, no image bytes in the log).

### 5.4 Attendance

**Check-in / check-out (`self`):**

- one big button on Home
- live timer, progress against a 9-hour shift, and a punch log for the day (time, action, source)
- multiple check-in/out pairs per day are allowed; worked hours = sum of the pairs

**Day status** (computed per employee per day, in the employee's own time zone):

- Present / Absent (LOP) / Leave / Holiday / Weekly off / Before joining / After exit
- Weekly off is Saturday and Sunday (configurable).
- A night shift belongs to the date on which it **started**.

**Policy** (configurable in Policies):

- Full day = 8h or more; half day = 4–8h.
- Grace period: 15 minutes after shift start (7:45 PM night / 9:45 AM day / local for US).
- Every check-in after grace is a **late mark**. **3 late marks in a month = ½ day LOP.**
- A day job at shift end + buffer marks a working day with no check-in and no approved leave as **Absent (LOP)**.
- Attendance for a month **locks on the 1st of the next month at 10:00 AM**. After the lock, only Finance can change LOP through payroll adjustments.

**My attendance page:**

- month calendar with a status per day, hours and a LATE tag
- monthly KPIs: present, absent, leave, holidays/weekly offs, hours
- day detail showing first check-in, last check-out (in the employee's time zone), worked hours and "counts as"
- a "Request regularization" button on absent or late days
- an **Upcoming holidays** section for the employee's region

**Regularization:**

- **Request types:** absent, late check-in, missed check-out, holiday work (credits a comp-off).
- **Fields:** date, type, proposed times, reason (optional).
- **Routing:** the request goes to **HR, Finance and Admin together**. Anyone with `appr.reg` can decide, and **the first decision is final**. Store who decided and when.
- **Effect of approval:**
  - an absent day becomes paid, which removes the LOP from payroll
  - a late mark is removed
  - holiday work adds a comp-off balance
- Requests are only allowed before that month's attendance lock.

**Attendance register** (`att.all`: HR, Admin):

- **Daily log:**
  - date picker (cannot pick a future date) with previous/next day buttons
  - columns: employee, team (India/US), status, check-in, check-out, worked, late tag
  - **"Show times in" selector:** IST · ET · CT · PT · UTC · each employee's local time. When a time falls on another calendar day in the chosen zone, show (+1)/(−1). Also show the employee's local time underneath.
  - KPIs: present, not checked in, on leave, absent
- **Monthly grid:** employees × days with codes P / A / L / H / – (not in yet) / hatched weekly off, plus totals P, A, L.
- Search, team (India/US), department and status filters, sortable columns.
- **Export to Excel/CSV.**

### 5.5 Holidays

- **Who manages it:** HR (`holidays.manage`) works on a month calendar. Tapping a date pre-fills the add form.
- **Fields:** date, name, applies to: **India team / US team / Everyone**, added_by.
- Past holidays cannot be deleted.
- Each employee sees only the holidays for their own region plus "Everyone":
  - on Home (next holiday, countdown)
  - on Leave & holidays (full list)
  - under My attendance (Upcoming holidays)
- Holidays are never counted as leave or LOP and are excluded from leave-day counts.

### 5.6 Leave

**Types and default policy** (configurable):

| Type | Policy |
|---|---|
| Casual leave (CL) | 12/yr, 1 accrues per month, no carry forward, max 3 at a stretch |
| Earned leave (EL) | 18/yr, 1.5 per month, carry forward up to 30, encash 15/yr after 12 months |
| Comp-off (CO) | Earned from approved holiday/weekend work of 4h+, expires in 60 days |
| Leave without pay (LOP) | Unpaid |

**Earned leave is not available during probation** (or before joining): the apply form shows it locked ("Not during probation") and the API refuses it; it becomes available once the employee is permanent. If nothing can be applied for, the Apply button explains why instead of opening the form.

New joiners accrue pro-rata from the date of joining. All balance changes go through a **leave ledger** (accrual, used, cancelled, encashed, expired, adjustment).

**Apply leave:**

1. Pick the leave type, with the balance shown.
2. Pick the start and end date on a calendar range picker. Weekends, holidays and past dates are disabled.
3. For a single day, choose Full day / First half / Second half.
4. Add a reason (optional).

The form shows working days, balance after, and how many days become **LOP** if the balance is short. Requests go to **HR** (`appr.leave`; Admin also can). The employee can cancel a pending request. Leave tables have status filters and sorting.

**Approvals page:**

- separate tabs, Leave and Attendance, each shown only if the user has that permission
- cards show balance impact and overlapping requests in the same team
- Approve / Decline buttons
- a "Decided" list showing who decided

### 5.7 Salary structures (`struct.manage`, Finance)

- Monthly components: **Basic, HRA, LTA, Special allowance**, plus an effective-from date and a reason (Joining / Appraisal / Promotion / Correction).
- A "Split" helper takes a target gross: Basic 50%, HRA 20%, LTA 5%, Special = the rest.
- **Entered once, used every month until a new revision.** Revisions are effective-dated rows. History is never edited, only added to. Payroll for a month uses the structure effective on the last day of that month.
- The list shows employees without a structure and has search, a department filter, a set/not-set filter and sorting.
- **India employees only.**

### 5.8 Payroll (`pay.view` to edit, `pay.close` to close)

**Run stages:** Lock attendance → Compute → Review & edit → Approve → Close & publish. There is one run per month.

**Per-employee calculation:**

```
days_in_month   = calendar days
eligible_days   = days from max(DOJ, 1st) to min(exit date, month end)
LOP days        = absences not regularized + LOP leave + half-days from late marks (+ Finance adjustment)
paid_days       = eligible_days − LOP days
earned_component = monthly_rate × paid_days ÷ days_in_month   (for Basic, HRA, LTA, Special)
gross_earned    = Σ earned components + bonus/arrears
PF (employee)   = 12% × min(earned Basic, 15,000)
Professional tax = by state slab (configurable; default ₹200)
TDS             = see §5.9
net pay         = gross_earned − PF − PT − TDS − other deductions
```

**Rules:**

- Finance can edit LOP days, bonus/arrears, other deduction and a note per employee while the run is open. Each edit is logged.
- Only `pay.close` (Finance Manager / Admin) can close. A Finance Executive sees "Closing needs Finance Manager".
- **Closing snapshots every payslip** (immutable JSON + PDF). Later changes to structure, regime or attendance never change a closed payslip. Corrections go into the next month as arrears.
- KPIs: employees, gross earned, LOP days, TDS to deposit, net payout. Amounts can be hidden.
- **Exports:** bank transfer file, PF ECR, PT and TDS summaries (CSV/Excel).

### 5.9 Tax & TDS (`tax.view`)

**Regime:**

- Each employee picks the **New or Old regime**. The default is New.
- Finance can override the regime per employee.

**Tax rules are data, not code:** keep slabs, standard deduction, rebate, cess, surcharge and section caps in tables per **financial year**. Defaults to seed (FY 2026-27, Finance/CA must verify):

- **New regime:**
  - standard deduction ₹75,000
  - full rebate when taxable income is ≤ ₹12,00,000, with marginal relief just above it
  - slabs: 0–4L nil · 4–8L 5% · 8–12L 10% · 12–16L 15% · 16–20L 20% · 20–24L 25% · >24L 30%
- **Old regime:**
  - standard deduction ₹50,000
  - rebate when taxable income is ≤ ₹5,00,000
  - slabs: 0–2.5L nil · 2.5–5L 5% · 5–10L 20% · >10L 30%
- **Both:** Health & education cess 4%, plus surcharge above ₹50L.
- **Section names:** from 1 April 2026 the Income-tax Act, 2025 applies and section numbering may differ. Keep section labels configurable.

**Annual taxable income** = projected salary for the year (actuals so far + current structure × remaining months) + previous employer income (Form 12B) − standard deduction − (old regime) approved deductions.

**Monthly TDS** = (annual tax − TDS already deducted this FY, including previous employer) ÷ remaining months, including the current month.

**Tax page:**

- one row per employee: regime, projected income, deductions, taxable, annual tax, monthly TDS, pending proofs
- a drawer with the full computation, slab working, and a new-vs-old comparison
- search, regime filter and sorting

### 5.10 Investment declarations (employee) & proofs (Finance)

**Employee side** (`self`):

- Pick the regime, with a live comparison of annual tax under each.
- If on the old regime, declare amounts and upload a proof per item:
  - **HRA:** annual rent. Landlord PAN is needed if rent is above ₹1L/yr. Metro or non-metro. Exemption = least of (HRA received, rent − 10% of Basic, 50% metro / 40% of Basic).
  - **80C (cap ₹1.5L):** EPF (automatic from payroll), PPF, ELSS, life insurance, **Sukanya Samriddhi**, children's tuition, home loan principal, NSC / 5-yr FD.
  - **80CCD(1B) NPS:** cap ₹50,000.
  - **80D medical:** self/family ₹25,000; parents ₹25,000, or ₹50,000 if senior citizens.
  - **24(b) home loan interest:** cap ₹2,00,000.
  - **80E education loan interest:** no cap.
  - **80G donations:** eligible amount.
  - **80TTA savings interest:** cap ₹10,000.
- Item statuses: Draft → Pending → Approved / Rejected (with a reason). Changing an approved amount sends it back to Draft.
- Proof window and declaration-lock dates are configurable.

**Finance side** (`proofs.verify`):

- list of employees with declarations, with search, a To verify / Verified filter and sorting
- a review drawer per item showing the proof file, amount, and Approve / Reject
- "Approve all pending"
- a preview of TDS now vs. if everything is approved

**Only approved amounts reduce TDS.**

### 5.11 Payslips (everyone with `self`, India payroll)

- A month appears **only after Finance closes that month**. Open months show "Processing".
- View in the browser and download as PDF.
- **PDF contents:**
  - company logo, company name and address
  - employee name, ID, designation, department, date of joining, PAN (masked), bank account (masked), tax regime, pay date
  - **days in month, weekly offs, holidays, present days, LOP days, paid days**
  - earnings: Basic, HRA, LTA, Special, Bonus (rate and earned)
  - deductions: PF, PT, TDS, other
  - gross, total deductions, **net pay in figures and words**
- US employees see: "US team pay statements come from the US payroll provider."

### 5.12 Home / Overview

**Home** (`self`):

- greeting with a live clock
- check-in card
- KPIs: present this month, average day, leave available, next holiday
- 16-week attendance heatmap and last 7 shifts
- upcoming holidays

Cards that appear by permission:

- **My leave requests** (plain employees)
- **Your team right now** (if the user has reports)
- **Approvals count** (approvers)
- **Payroll status** (Finance)
- **Payslip ready** (India)

**Salary is never shown on Home.**

**Overview** (Admin):

- KPIs: headcount (India/US, access off), checked in now, pending approvals, proofs to verify
- payroll status and net payout
- people by department
- HR and Finance queues
- shortcuts

### 5.13 My team (anyone with active direct reports)

- team table: name, ID, designation, shift, today's status, with search and sorting
- KPIs: size, checked in, on leave, not in yet
- a "Who's away" week view of approved/pending leave and holidays
- managers **do not approve** anything; HR does

### 5.14 Notifications & shell

**In-app notifications** (bell), driven by permission:

- requests waiting
- proofs to verify
- payroll status
- own leave decisions
- payslip ready
- next holiday

- tasks (§5.17.6): assigned to me, made accountable, mentioned, a comment or status change on a task I follow, due tomorrow, due today (timed tasks), overdue, added to a project

**Email** for: sign-in code, leave decision, regularization decision, payslip published. Tasks send **no email** yet (bell only).

**Shell:**

- sidebar grouped as My workspace / Tasks / Team / People ops / Finance / Administration (the **Tasks** group: My tasks, Team tasks, Projects, Task settings)
- badges for pending items; **My tasks** shows the number of my open tasks (direct or indirect assignee) that are overdue or due today, each in its task time zone
- profile menu with My profile, My payslips, theme toggle, command menu, Sign out
- **⌘K command palette** (actions + navigation)
- light/dark theme (light default)

### 5.15 Tables (global rule)

**Every table has search, filters relevant to its data, and click-to-sort column headers** (↑/↓). Large lists are paginated server-side. People, Attendance register, Payroll, Tax and Proofs have an export option.

### 5.16 Audit log

Record **who, what, when, before → after** for these events:

- login
- access toggle
- employee create/edit/exit
- role/permission change
- leave/regularization decision
- holiday/department change
- salary structure change
- payroll edit/close
- regime change
- proof decision
- tasks: project create/update/archive/restore/delete, members, workflow steps, labels, task create/update/move/delete, followers, checklist, comments (length only, never the text), attachments; and the Owner opening a private project or task they are not part of (`project.private_access`, `task.private_access`). Filter group "tasks".

Admin can view the audit log with filters.

**No step is lost (every portal, every role, super admin included).** On top of the events above, the API records one row for **every write request** (POST / PUT / PATCH / DELETE) and for every **sensitive read** (exports, downloads, PDFs, revealing contact details, employee profiles, the audit log itself), whatever its outcome: success, a business-rule refusal, 401, 402, 403, 428 or a server error. The row has the actor (empty when not signed in), the business, method, path (secrets in a path are masked), status, duration in milliseconds, IP, user agent and a request id (also sent back as `X-Request-Id`), with the time to the microsecond. It is written on its own connection in a `finally`, so a rolled-back transaction or a failing handler cannot take it away; a failed audit write is logged as an ERROR and counted (`jurisone.audit.write.failures`). Request bodies, cookies, codes and tokens are never stored. Sign-in attempts (success and failure, the email masked), code requests and sign-out are recorded. The web app also reports each **page view** (`POST /api/audit/page-views`). The log is **append-only** (a database trigger refuses changes and deletes).

The business admin sees their own business's log (`/audit`); the **super admin** sees every business and the platform itself (`/platform/audit`, filter by business). Both can be exported as CSV.

### 5.17 Tasks (every business, no plan module)

#### 5.17.1 Projects, members and visibility

- A **project** (key `HR`, `PAY`: 2–6 capital letters/digits starting with a letter, unique in the business; name unique) is the only container. It is **private** (members only) or **company-wide** (everyone reads it and may comment). The creator is its owner. Members are `OWNER` (everything incl. members, steps, labels, archive), `MEMBER` (create and edit tasks, delete tasks they reported) or `VIEWER` (read, comment, follow). A project keeps at least one owner. A key cannot change once the project has tasks.
- Creating a project needs `projects.create` (or `tasks.manage`). A project with tasks is **archived** (read-only, 422 for writes) instead of deleted; an empty project is soft-deleted.
- **Who sees what:** project members as above; a non-member sees a company-wide project like a viewer and nothing of a private one (**404**, so its existence does not leak). The direct assignee can work on the task (status, checklist, files); the reporter, followers and the **manager of the direct assignee** (direct reports, and one more level) read and comment, also inside private projects (that task only).
- **The Owner (`tasks.manage`) sees every project and task**, private ones and personal tasks included, and acts as project owner everywhere; opening a private project or task they are not part of writes `project.private_access` / `task.private_access` to the audit log (no notification). This is the only exception to the 404 rule. HR and Finance have no special view of tasks.

#### 5.17.2 Workflow

- Each project has its own **steps** (statuses), copied from the business default set when it is created: **To do, In progress, In review, Done, Cancelled**. Each step is in one category: to do, in progress, done. At least one to-do and one done step, at most 12, exactly one starting step (a to-do one), unique names. Deleting a step with tasks needs a step to move them to. Task settings (`tasks.manage`) edits the default set, which affects personal tasks and new projects only.

#### 5.17.3 Tasks

- Fields: title (≤200), description (plain text with `@mentions`, ≤20,000), type (task, issue, request, milestone), step, priority (urgent, high, medium, low, none), labels (≤20, company or project labels), start date, **due date + optional due time**, estimate in minutes, parent task, followers, checklist (≤100 items).
- A task in a project gets the next number (`HR-12`); its key is found case-insensitively, and a task that moved project keeps answering to its old key. A **personal task** has no project and no number.
- **One direct assignee** (does the work) and **one optional indirect assignee** ("accountable": oversight; must be someone else). Both see the task in My tasks, both are notified, both follow it. Setting a direct assignee who is not a project member adds them as a member; the indirect assignee is not added. The indirect assignee may comment, change the step and priority and **reassign the direct assignee**, may step down, but edits no other field and cannot delete the task (unless a project owner). The create form suggests the assignee's reporting manager as accountable (never set automatically).
- **Due time and time zone:** the task time zone is the direct assignee's (else the indirect assignee's, else the reporter's). A date-only task is due at the **end of that day** and overdue from the next day; a timed task is overdue from its due time. Clearing the due date clears the time.
- Sub-tasks are one level deep, in the same project (≤100 per task). Changes need the version the client read (409 when someone else changed the task); each field needs its own right (403).
- **Soft delete always:** tasks (with their sub-tasks), comments, attachments and projects are never hard-deleted by the app. Every change is written to the task's **history** and the audit log.

#### 5.17.4 Comments, mentions, followers, attachments

- Comments (≤5,000) with `@mentions`; the author edits, the author, a project owner or the Owner deletes ("Comment deleted" stays). Commenters and mentioned people follow the task; people who cannot see the task are neither notified nor given access. Anyone who sees a task may follow it.
- Files on tasks: PDF, PNG, JPG, Word/Excel/PowerPoint (`.docx/.xlsx/.pptx`), text and CSV, checked by their first bytes; ≤10 MB each, ≤20 per task; stored under `<storage-dir>/tasks/`; downloads are in the audit trail; removed files stay stored.

#### 5.17.5 Views

- **My tasks:** my open tasks as direct or indirect assignee (or reported / followed), grouped by due (overdue, today, this week, later, no date), list or board.
- **Team tasks** (anyone with reports): tasks whose direct assignee reports to me, direct reports by default and one more level with a switch, with a per-person summary (open, overdue, done in the last 7 days).
- **Projects:** list, and each project's List and Board (cards in server-calculated order; open cards and the ones done in the last 14 days).
- Every task list has the same filters (project, step, category, assignee, accountable, reporter, priority, type, label, due, dates, search incl. task keys) and sorting.

#### 5.17.6 Bell notifications and reminders (no email yet)

- Assigned, made accountable, mentioned, comment and step change on a followed task, added to a project. One request sends at most one notification per person; the person who acted is never notified.
- Reminders at **09:00 in the task time zone**: due tomorrow, due today (timed tasks), overdue (date-only tasks from 09:00 of the next day, timed tasks right after their time), to both assignees, once per due date (a new date re-arms them). People who have left or have no portal access are not notified. Email alerts are a later phase.

#### 5.17.7 Availability

- Tasks are part of **every plan** (not a module) and free for every business. After the subscription ends they are read-only like everything else (402 for writes).
---

## 6. Data model (PostgreSQL), minimum tables

```
departments(id, name unique, head_employee_id, description, created_by, created_at)
roles(id, key, name, is_builtin)
role_permissions(role_id, permission_key)
role_assignable_roles(role_id, assignable_role_id)
employees(id, code 'JC-###' unique, full_name, father_name, email unique, phone_enc, address_enc,
          pan_enc, aadhaar_enc, uan_enc, pan_last4, aadhaar_last4, uan_last4,
          department_id, designation, reporting_manager_id → employees, role_id,
          date_of_joining, exit_date, status, time_zone, region (IN/US), shift_id, portal_access bool)
users(employee_id, failed_attempts, locked_until, last_login_at)
sessions(id, employee_id, created_at, expires_at, ip, user_agent, revoked_at)
shifts(id, name, start_local, end_local, grace_minutes, region)
punches(id, employee_id, at timestamptz, kind in/out, source web/mobile, ip)
attendance_days(employee_id, work_date, status, first_in, last_out, worked_minutes, late bool, locked bool)
regularization_requests(id, employee_id, work_date, kind, proposed_in, proposed_out, reason,
          status, decided_by, decided_at, decided_role)
holidays(id, date, name, region IN/US/ALL, created_by)
leave_types(code, name, annual_quota, accrual_per_month, carry_forward_cap, expiry_days, ...)
leave_ledger(id, employee_id, leave_type, delta, reason, ref_id, at)
leave_requests(id, employee_id, leave_type, from_date, to_date, part, days, reason, status,
          decided_by, decided_at, applied_at)
salary_structures(id, employee_id, effective_from, basic, hra, lta, special, reason, created_by)
payroll_runs(id, month, status OPEN/CLOSED, closed_by, closed_at)
payroll_adjustments(run_id, employee_id, lop_days, bonus, other_deduction, note, updated_by)
payslips(run_id, employee_id, snapshot jsonb, pdf_path, published_at)
tax_years(fy, regime, std_deduction, rebate_limit, cess_rate, slabs jsonb, surcharge jsonb)
tax_profiles(employee_id, fy, regime, metro, prev_employer_income, prev_employer_tds)
declaration_items(id, employee_id, fy, section, item_key, amount, proof_path, status,
          decided_by, decided_at, reject_reason)
notifications(id, employee_id, text, link, read_at, created_at)
audit_log(id, actor_id, action, entity, entity_id, before jsonb, after jsonb, at, ip)
settings(key, value jsonb)   -- policies: grace, half-day hours, lock day, PT slab, weekly offs
projects(id, key, name, description, visibility PRIVATE/BUSINESS, status ACTIVE/ARCHIVED, color, department_id,
          next_task_number, version, created_by, archived_at, deleted_at)
project_members(project_id, employee_id, role OWNER/MEMBER/VIEWER)
task_statuses(id, project_id (null = default set), name, category TODO/IN_PROGRESS/DONE, position, color, is_initial)
tasks(id, project_id, number, previous_keys[], parent_task_id, title, description, type, status_id, priority,
          assignee_id, indirect_assignee_id, reporter_id, start_date, due_date, due_time, estimate_minutes, position,
          completed_at, due_soon/due_today/overdue_notified_for, version, deleted_at, search_vector)
task_labels(id, project_id (null = company label), name, color)    task_label_links(task_id, label_id)
task_watchers(task_id, employee_id)    task_checklist_items(id, task_id, text, is_done, position)
task_comments(id, task_id, author_id, body, mentioned_employee_ids[], edited_at, deleted_at)
task_attachments(id, task_id, file_path, file_name, content_type, size_bytes, sha256, uploaded_by, deleted_at)
task_activity(id, task_id, project_id, actor_id, kind, before jsonb, after jsonb, occurred_at)
```
Every task table has `business_id` and row-level security like the other business tables.

---

## 7. Non-functional requirements

- **Security:**
  - server-side permission checks on every API
  - row-level rules (employees see only their own data unless they hold a permission)
  - CSRF protection, input validation (zod)
  - PII encrypted at rest, HTTPS only
- **Accuracy:** money as integers. Payroll and tax logic is pure functions with unit tests. Golden test: JC-074 Amit Jaspal for Sep 2026 (joined 8 Sep, 1 LOP, structure Basic 75,000 / HRA 30,000 / LTA 7,500 / Special 37,500, previous employer ₹7.5L income / ₹38,000 TDS, new regime) gives monthly TDS ≈ ₹14,926 and 22/30 paid days.
- **Time zones:** every date and time is computed in the employee's zone. Register views can convert to any zone.
- **Responsive:** works on phone (sidebar becomes a drawer). Meets WCAG AA contrast. Keyboard accessible.
- **Performance:** pages load in under 2s for 500 employees, with server-side pagination.
- **Backups:** daily Postgres backup. Migrations are reversible.

---

## 8. Seed / demo data

| ID | Name | Role | Notes |
|---|---|---|---|
| JC-002 | Sana Kapoor | Admin | |
| JC-004 | Arjun Mehta | Finance Manager | |
| JC-012 | Riya Malhotra | HR Manager | |
| JC-015 | Pooja Nair | HR Executive | reports to Riya |
| JC-018 | Rohan Gupta | Finance Executive | reports to Arjun |
| JC-035 | Nazneen Khan | Employee (has team) | Technology |
| JC-049 / 058 / 072 / 074 / 081 | Naman, Kavya, Siddharth, Amit, Farhan | Employee | report to Nazneen; JC-081 access disabled |
| JC-030 | Vikas Rao | Employee | Exited 31 Aug 2026 |
| JC-090 / 091 | Emily Carter / Daniel Brooks | Employee | US · New York / Los Angeles |

Seed the 2026 holidays (India + US + Christmas for everyone), the departments and the salary structures as in the prototype.

Tasks (dated relative to the seed day): **People operations** (`PEO`, company-wide, owner Riya, member Pooja: 8 tasks, two overdue, one with a checklist and a comment thread with a mention, one with Arjun as accountable), **Website relaunch** (`WEB`, private, owner Nazneen with her reports: 10 tasks across all steps, 2 sub-tasks, labels frontend/content, one due today 17:00 and one overdue since yesterday 11:00), **Payroll October** (`PAY`, private, owner Arjun, member Rohan: 4 tasks; HR does not see them, the Owner does) and 2 personal tasks of Siddharth.

---

## 9. Open questions (decide before the related phase)

1. The Professional Tax state (Uttar Pradesh has no PT; Maharashtra/Karnataka do). Do we need ESI, employer PF in CTC, gratuity, or LWF?
2. Leave encashment and LOP rules at full & final settlement.
3. Is a biometric device or IP/geo restriction needed for check-in?
4. Tasks: should timed tasks also get a reminder shortly before their due time (for example 1 hour before)? Default until decided: no, only the 09:00 reminders.
4. Email provider and hosting (VPS / AWS / Azure). Is SSO (Google/Microsoft) needed?
5. Will US payroll ever be in scope?
6. Buying the Balkind font licence.
