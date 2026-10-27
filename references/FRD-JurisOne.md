# JurisOne: Functional Requirements Document (FRD)

| Version | Date | What changed | Requested by |
|---|---|---|---|
| 1.0 | 2026-10-23 | Initial version | Owner |
| 2.0 | 2026-10-27 | Section 12 rewritten: the employee onboarding wizard replaces onboarding by link (FR-ONB-1 to FR-ONB-6) | Owner |
| 3.0 | 2026-10-27 | Onboarding needs HR approval: statuses CHANGES_REQUESTED and APPROVED replace REOPENED, hard lock of the portal until approved, add-employee no longer asks for onboarding by default (FR-ONB-1, FR-ONB-6, FR-ONB-7, FR-ONB-8) | Owner |

The document version is not the app version. Update this file in the same pull request as any change in behaviour, and raise the version (small change 1.1, new area 2.0).
Companion documents: `FLOW-JurisOne.md` (diagrams), `FLOW-JurisTrack-JurisOne-Connection.md` (JurisTrack link, master copy in the `juris-track-backend` repo).

Sources: `REQUIREMENTS.md`, `SAAS_PLAN.md`, `MIGRATION_PLAN.md`, the three `TASK_MANAGEMENT_*` files, and the code of both repos (controllers, `engine/Permissions`, `engine/Nav`, `service/`, `db/migration` V1 to V20; web app `src/pages`, `src/routes.tsx`). Where code and requirements disagree, see section 17 (Open points).

---

## 1. Purpose and scope

JurisOne is a multi-business HR portal (HRMS) for Indian companies with some US staff. One installation serves many **businesses** (customers). Each business has its own people, roles, policies and data; businesses never see each other.

**In scope:** people and departments, directory, attendance and regularization, holidays, leave, salary structures, payroll and payslips, tax (TDS) and investment declarations, company policies with acknowledgement, announcements, assets, HR letters, onboarding by link, tasks and projects, notifications, audit log, roles and permissions, subscriptions and the platform console, Hindi and English. JurisTrack is a placeholder (section 15).

**Out of scope (on purpose):** exit and full-and-final settlement, Form 16 (removed by the owner); the identity provider IAM JCG / Sita (not to be worked on until the owner says so); US payroll (US staff are tracked for attendance and leave only); payment gateway (subscriptions are manual); email alerts for tasks (bell only for now); JurisTrack features themselves.

**Who reads this:** the owner (what the product does), new developers and testers (testable statements, FR-ids).

## 2. Architecture in brief

- Web app: React + Vite single page app. The browser talks only to its own origin; `/api/*` is proxied to the API.
- API: Spring Boot, PostgreSQL 16 with row-level security per business, Flyway migrations V1 to V20 (each with a rollback script).
- Jobs run inside the API every 10 minutes (and once 15 seconds after start), one instance at a time (database advisory lock), business by business.
- Email (sign-in code, decisions, payslips, letters, subscription warnings). Files (proofs, policy PDFs, onboarding attachments, task attachments, payslip and letter PDFs) are kept in the storage folder.
- The API enforces all permissions. Hiding things in the web app is cosmetic.
- Success answers are `{ok, message?, data?}`. Errors are RFC 7807 problem details (`status`, `code`, `detail`, `errors[]`, `traceId`).

## 3. Users and roles

| Level | Who | What they do |
|---|---|---|
| Platform | **Super admin** (permission `platform.manage`, belongs to no business) | Creates and suspends businesses, owners (sub admins), plans, subscriptions, module grants, role templates; reads the platform audit log. Cannot see any business's people, payroll or tax. |
| Business | **Owner** (a.k.a. sub admin, Admin; role key `owner`) | Runs the business. Has every permission except `self` (no attendance, leave or payslips of their own). Can view roles but not change them. |
| Business | HR Manager, HR Executive, Finance Manager, Finance Executive, Employee, plus any template the super admin adds | As in the matrix below. |
| Anyone | **Manager** (a relationship, not a role) | Anyone with active direct reports gets My team and Team tasks. Managers do not approve anything; HR does. |

### 3.1 Permission keys

| Key | Meaning |
|---|---|
| `self` | Own attendance, leave, payslips, tax declarations |
| `dir` | Employee directory |
| `att.all` | View everyone's attendance (register) |
| `appr.leave` / `appr.reg` | Decide leave / attendance regularization |
| `people.view` / `people.add` | View employee records / add and edit employees |
| `access.toggle` | Enable or disable portal access |
| `dept.manage` / `holidays.manage` | Departments / holiday calendar |
| `letters.manage` | Letter templates and issuing HR letters |
| `pay.view` / `pay.close` | View and edit payroll / approve, close and publish |
| `struct.manage` | Salary structures, salary figures |
| `tax.view` / `proofs.verify` | Tax and TDS of all employees, regime override / verify investment proofs |
| `policies.manage` / `assets.manage` / `announcements.manage` | Company policies / asset register / announcements |
| `roles.manage` | Overview, roles page, settings, audit log (read access) |
| `tasks.manage` | Task settings and every project and task of the business (Owner only) |
| `projects.create` | Create task projects |
| `business.manage` | Subscription page and employee export (Owner role only) |
| `platform.manage` | Platform console (super admin only; never in a business role) |

### 3.2 Permission matrix (default role templates)

Y = has it. Using tasks needs no permission; only `projects.create` and `tasks.manage` exist for tasks.

| Permission | Owner | HR Manager | HR Executive | Finance Manager | Finance Executive | Employee |
|---|---|---|---|---|---|---|
| self | - | Y | Y | Y | Y | Y |
| dir | Y | Y | Y | Y | Y | Y |
| att.all | Y | Y | Y | - | - | - |
| appr.leave | Y | Y | Y | - | - | - |
| appr.reg | Y | Y | Y | Y | Y | - |
| people.view | Y | Y | Y | Y | - | - |
| people.add | Y | Y | - | Y | - | - |
| access.toggle | Y | Y | - | Y | - | - |
| dept.manage | Y | Y | - | Y | - | - |
| holidays.manage | Y | Y | - | - | - | - |
| letters.manage | Y | Y | - | - | - | - |
| pay.view | Y | - | - | Y | Y | - |
| pay.close | Y | - | - | Y | - | - |
| struct.manage | Y | - | - | Y | - | - |
| tax.view | Y | - | - | Y | Y | - |
| proofs.verify | Y | - | - | Y | Y | - |
| policies.manage | Y | Y | - | - | - | - |
| assets.manage | Y | Y | - | - | - | - |
| announcements.manage | Y | Y | - | - | - | - |
| roles.manage | Y | - | - | - | - | - |
| tasks.manage | Y | - | - | - | - | - |
| projects.create | Y | Y | - | - | - | - |
| business.manage | Y | - | - | - | - | - |
| **Can add people with role** | all roles below Owner | HR Executive, Employee | none | Finance Executive (Finance department only) | none | none |

### 3.3 Role rules

- **FR-ROLE-1** Permissions are checked on the server for every API call and page. Salary, payroll and tax permissions can never be put into an HR role (`HR roles can't hold salary, payroll or tax permissions`).
- **FR-ROLE-2** Roles are copies of **role templates** kept by the super admin. A business gets Owner, HR Manager, Finance Manager and Employee always (mandatory); HR Executive, Finance Executive and extra templates are optional and can be added or removed later (a role can be removed only when nobody, past employees included, holds it).
- **FR-ROLE-3** Changing a template does not change existing businesses until the super admin syncs a business role (`template-syncs`). The Owner template is locked.
- **FR-ROLE-4** The Owner can view roles; every change to roles or permissions answers 403 "Roles are set up for your business by JurisOne." Only the super admin changes them. Built-in roles cannot be deleted; a role in use cannot be deleted.
- **FR-ROLE-5** A user can edit an employee only with `people.add` and when the target's role is in their assignable list (Owner edits anyone). Nobody edits their own record or role. Only the Owner and HR may change an existing employee's role or confirm probation.
- **FR-ROLE-6** The landing page after sign-in is Home (anyone with `self`), Overview (Owner), the platform console (super admin) or Directory.

## 4. Cross-cutting requirements

### 4.1 Sign-in by emailed code, session (module AUTH)

- **FR-AUTH-1** Sign-in is by official email plus a 6-digit code emailed to it. There are no passwords and no role picker. The code is valid 10 minutes, works once, allows 5 wrong tries, and a new code cancels the older one. Only a salted hash is stored. The fixed code `000000` exists only in the `dev` profile.
- **FR-AUTH-2** Requesting a code (`POST /api/auth/otp-requests`) always gives the same answer, so nobody can learn who has an account. Limits: 10 requests per IP and 5 per email per 15 minutes (429 beyond).
- **FR-AUTH-3** Sign-in (`POST /api/auth/session`, email, code, "keep me signed in", optional `next`) is refused with these messages: disabled access ("Your portal access is disabled. Please contact HR."), past employee ("This account belongs to a past employee and can no longer sign in."), business suspended, subscription not started or none (402 `SUBSCRIPTION_INACTIVE`), wrong or expired code (401). No code is sent to a blocked person; an email tells them why.
- **FR-AUTH-4** After 30 failed sign-ins from one IP within 15 minutes, further attempts answer 429.
- **FR-AUTH-5** Success sets two HTTP-only cookies: `jo_at` (JWT access token, 5 minutes) and `jo_rt` (opaque refresh token, rotated on every use, stored hashed). The session lasts 12 hours, or 30 days with "keep me signed in".
- **FR-AUTH-6** Every request re-checks the session row and the employee (portal access, not exited). Disabling access, marking an employee past, or suspending the business ends sessions at once; the next call is 401 and refresh fails.
- **FR-AUTH-7** The web app answers a 401 with one silent refresh (`POST /api/auth/session-refreshes`) and one retry; if that fails it clears its cache and goes to `/login?next=...`. A used refresh token presented again revokes the whole session (30-second grace for parallel requests).
- **FR-AUTH-8** Sign-out (`DELETE /api/auth/session`) always answers 200, ends the session and clears the cookies.
- **FR-AUTH-9** Every sign-in step (code requested, success, each refusal, sign-out) is audited and recorded in the login history (time, IP, device).
- **FR-AUTH-10** Writes must carry an Origin that matches the host (CSRF guard, with SameSite=Lax cookies).

### 4.2 Subscription and read-only mode (module SUB)

- **FR-SUB-1** A plan has a name, price, period in months, maximum active employees and modules. A subscription (business, plan, start, end, status active or cancelled, notes) is assigned or renewed manually by the super admin; history is kept. Price defaults to the plan's; the end date defaults to the plan period.
- **FR-SUB-2** State today (India date): ACTIVE, UPCOMING (starts later), EXPIRED (ended), NONE. UPCOMING and NONE: nobody in the business can sign in, and existing sessions get 402 on everything except sign-out, refresh, `/api/me`, `/api/shell`.
- **FR-SUB-3** EXPIRED = **read-only**. Users can sign in and read everything, and the Owner can export. Every write answers 402 `SUBSCRIPTION_INACTIVE` except sign-out and refresh, marking notifications read and recording page views. The web app hides actions and shows the "Subscription ended" page (`/paused`). Blocking starts the day after `ends_on`, with no grace period.
- **FR-SUB-4** Adding or re-enabling an employee beyond the plan limit is refused with "Your plan (X) allows up to N employees. Contact JurisOne to upgrade."
- **FR-SUB-5** The job sends the Owners a bell notification and an email 7 days before, 1 day before (also on the end day) and after the end; once per end date.
- **FR-SUB-6** The Owner sees the Subscription page (plan, dates, days left, employee count) and downloads all employees as Excel (`GET /api/business/employees/export`). Identity numbers are masked unless `?identity=full` is used, which is audited (`business.employee_export`). The export works before and after expiry.
- **FR-SUB-7** The super admin and the platform company are never blocked. A suspended business: nobody can sign in, active sessions end; reactivating allows sign-in again with a fresh code.
- **FR-SUB-8** Modules (`JURIS_TRACK`) come from the plan or from a direct grant by the super admin (complimentary, or paid with price and end date; no overlapping grants; a grant can be revoked, kept for the record). Tasks are **not** a module: free for every plan.

### 4.3 Tenancy (business isolation)

- **FR-TEN-1** Every business-owned table carries `business_id` with PostgreSQL row-level security. The business id comes from the authenticated principal, never from the request. Business A cannot read or write business B's rows even with an unfiltered query (proved by `TenantIsolationTest`).
- **FR-TEN-2** Uniqueness is per business: department name, holiday (date, region), role key, employee code, project key and name. The work email is globally unique (it is the sign-in name).
- **FR-TEN-3** Global tables: tax years, shifts, subscription plans, role templates. System mode (no business) is used for sign-in, the super admin and the jobs.
- **FR-TEN-4** Each business has its own employee ID prefix of 2 to 5 capital letters (for example `AB-007`); it cannot change once the business has people. Settings (attendance, payroll, tax, probation, onboarding documents) are per business; defaults apply until saved.

### 4.4 Audit log (module AUD)

- **FR-AUD-1** Business events record who, what, when, before and after for: sign-in and out, access toggle, employee create, edit, confirm, exit, photo, contact reveal, export; role and permission changes; leave and regularization requests and decisions; departments and holidays; salary structure changes; payroll edit, approve, close, export; tax regime change, previous employer, declarations, proof decisions; policies, announcements, assets, letters and templates; onboarding steps; project, task, workflow, label events (filter group "tasks").
- **FR-AUD-2** In addition, one row is written for **every write request** (POST, PUT, PATCH, DELETE) and for every sensitive read (exports, downloads, PDFs, contact reveal, employee profiles, the audit log itself), whatever the outcome (success, 4xx, 401, 402, 403, 428, 500). Fields: actor (empty if not signed in), business, method, path (secrets masked), status, duration, IP, user agent, request id (`X-Request-Id`). No bodies, cookies, codes or tokens.
- **FR-AUD-3** The row is written on its own connection in a `finally`, so a rolled-back transaction cannot remove it. A failed write is logged as ERROR and counted (`jurisone.audit.write.failures`).
- **FR-AUD-4** The log is append-only (a database trigger refuses update and delete). The web app also reports each page view (`POST /api/audit/page-views`).
- **FR-AUD-5** The Owner sees the business log (`/audit`, filters and CSV export). The super admin sees every business and the platform (`/platform/audit`, filter by business, CSV). Filter groups: auth, people, roles (also policies, assets, announcements), time, org, payroll, tax, letters, tasks, requests, pages.

### 4.5 Notifications (module NOT)

- **FR-NOT-1** The bell and the `/notifications` page list unread first; mark one or all as read. Notifications about policies stay until the policy is accepted and cannot be marked read or unread by hand. The notification card also appears on Home.
- **FR-NOT-2** Bell texts are stored in English with the data filled in. Email is sent only for: sign-in code, leave decision, regularization decision, payslip published, letter issued, subscription warnings, and a note to a person who is blocked from receiving a code. Tasks send **no** email.
- **FR-NOT-3** (see 4.6) The web app shows bell texts in the reader's language.
- **FR-NOT-4** Marking notifications read is still allowed in read-only mode.

### 4.6 Language

- **FR-LANG-1** The app is available in English and Hindi (switcher; choice kept in the `jo_lang` cookie for a year; default English). English text is the translation key; every new string needs its Hindi in `messages/hi.ts` (a unit test checks it).
- **FR-LANG-2** Fixed API messages are translated in the browser; unknown or dynamic messages stay in English.
- **FR-LANG-3** Notification texts in the bell and on `/notifications` are translated into Hindi by matching each stored English sentence against a template (`hi-notifications.ts`) and filling the Hindi sentence with the data. Names, task keys, titles and project names stay as written; enumerations (status, leave type, role, regularization kind, month) go through the dictionary. A text no template matches is shown unchanged. Emails and PDFs stay in English.

### 4.7 Data conventions

- **FR-DATA-1** Money is stored and sent as integer **paise**; screens format it as rupees. No floats for money.
- **FR-DATA-2** Dates are ISO strings (`YYYY-MM-DD`); timestamps are UTC (`timestamptz`) and shown in the employee's own time zone (IANA name). The API returns raw values and the web app formats them.
- **FR-DATA-3** PAN, Aadhaar, UAN, phone and address are encrypted at rest and shown masked (`XXXX XXXX 1234`) except where a rule says otherwise (contact reveal is audited).
- **FR-DATA-4** Tables search, filter and sort by clicking headers; large lists are paged on the server.
- **FR-DATA-5** Names of API paths, parameters and JSON fields follow `camelCase` / `kebab-case` conventions; URLs name resources, not actions.

## 5. People, departments, directory

**Purpose:** employee records, organisation and a company-wide directory.
**Screens:** Employees (`/employees`), profile drawer, Add/Edit form (3 steps), Departments, Directory, My profile, My team.

- **FR-PEO-1** Add or edit (`people.add`, role in the creator's assignable list). Step 1 Personal: name (first, middle, last), father's name, email, phone (validated per country code, for example +91 takes 10 digits starting 6 to 9), work location India-IST / US-ET / US-CT / US-PT (sets time zone and region), optional photo. Step 2 Identity (India only): PAN `ABCDE1234F`, Aadhaar 12 digits, optional UAN 12 digits, address. Step 3 Job: designation, department, reporting manager, joining date, shift (India Night 7:30 PM to 4:30 AM, India Day 9:30 AM to 6:30 PM, US Day 9:00 AM to 6:00 PM local), role.
- **FR-PEO-2** The employee ID is generated as the business prefix plus the next number. A Finance Manager can add only to the Finance department.
- **FR-PEO-3** Rules: the email must be unused; a manager must be a current employee, not oneself, and must not create a reporting loop; the department must exist.
- **FR-PEO-4** Status: ONBOARDING (joining in the future), PROBATION, ACTIVE, EXITED. The daily job moves Onboarding to Probation on the joining date and to Active after the probation period (default 6 months). "Make permanent" (`PUT /api/people/{id}/status` `{"status":"ACTIVE"}`, Owner or HR with `people.add`, never oneself) does it earlier (`employee.confirm`).
- **FR-PEO-5** Portal access (`PUT /api/people/{id}/access`, `access.toggle`): not oneself, only the Owner toggles an Owner, exited people stay off. Turning access off ends their sessions.
- **FR-PEO-6** Mark as past employee needs a last working day not before the joining date; refused while the person still has direct reports ("Assign them a new manager first"); removes access (`employee.exit`).
- **FR-PEO-7** When a new India employee is added, Finance gets a bell notice "salary structure pending". A new employee signs in with the code emailed to the official email; there is no invite or password step.
- **FR-PEO-8** HR never receives salary; the monthly gross shows only with `struct.manage`. Full phone and address appear only to those who may edit the person; each reveal is audited (`employee.pii_reveal`).
- **FR-PEO-9** The list has search (name, ID, email, designation, manager), filters (department, status including Past employees, access on/off), sorting, a portal-access switch per row, and CSV export (`employee.export`).
- **FR-PEO-10** Profile photo: JPEG, PNG or WebP up to 400 KB, type checked from the file's bytes. The employee sets or removes their own (`/api/profile/photo`); HR and the Owner do it for anyone they may edit, never for a past employee. Anyone signed in to the business can read it (cached one hour). Audited without image bytes.
- **FR-DEP-1** Departments (`dept.manage`): unique name, optional head (current employee), description (300 characters). Deleting is allowed only with no current employees. List with search, sort, headcount, created-by.
- **FR-DIR-1** Directory (`dir`): search any employee, past or present; shows only name, official email, designation and Active or Past employee; filters department and Active/Past.
- **FR-PRO-1** My profile: the employee edits their own allowed fields (`profile.self_update`) and photo, and sees their onboarding files.
- **FR-TEAM-1** My team (anyone with active direct reports): table with today's status, KPIs (size, checked in, on leave, not in yet), "who's away" week view. Managers approve nothing.

**Data kept:** employees (encrypted PII plus last-4 digits), users (last login), sessions, login history, departments, role links.
**Notifications:** salary structure pending (Finance). **Audit:** employee.create, edit, confirm, exit, access_toggle, photo_set, photo_remove, pii_reveal, export, status_auto; department.delete.
**Errors:** 403 "You can't edit this person."; "That manager already reports (directly or indirectly) to this person."; "<email> already uses that email."; "You can add people to your own department only."

## 6. Attendance and holidays

**Screens:** Home (check-in card), My attendance, Attendance register, Holiday calendar.

- **FR-ATT-1** Check in and check out (`POST /api/attendance/punches`, `self`): several pairs a day; worked time is the sum. Refused: already checked in, not checked in, before the joining date, a repeated tap within a few seconds. Every punch is audited.
- **FR-ATT-2** Day status per employee per day, in the employee's time zone: Present, Absent (LOP), Leave, Holiday, Weekly off, Before joining, After exit. A night shift belongs to the date it started. Weekly offs default to Saturday and Sunday (configurable).
- **FR-ATT-3** Policy (per business, default): full day 8 hours or more, half day 4 to 8 hours, grace 15 minutes after shift start; a check-in after grace is a **late mark**; 3 late marks in a month = half-day LOP; a working day with no check-in and no approved leave becomes Absent (LOP) once shift end plus 120 minutes has passed (daily job, last 7 days).
- **FR-ATT-4** A month **locks** on the 1st of the next month at 10:00 (configurable). After the lock only payroll adjustments by Finance change LOP; regularizations, leave in that month and holiday changes are refused.
- **FR-ATT-5** My attendance shows a month calendar with a status, hours and LATE tag per day, KPIs (present, absent, leave, holidays and weekly offs, hours), day detail, upcoming holidays for the employee's region, and "Request regularization" on absent or late days.
- **FR-ATT-6** Register (`att.all`): daily log (no future dates, previous/next day) with a "Show times in" selector (IST, ET, CT, PT, UTC, each employee's local time; a time on another calendar day shows (+1)/(-1)), KPIs (present, not checked in, on leave, absent), monthly grid (P, A, L, H, not in yet, weekly off) with totals, filters (team India/US, department, status), search, sort, export (`attendance.export`).
- **FR-REG-1** Regularization request (`POST /api/attendance/regularizations`, `self`): kinds ABSENT, LATE_CHECKIN, MISSED_CHECKOUT (needs the real check-out time), HOLIDAY_WORK (4 hours or more on a holiday or weekly off, credits a comp-off). Date, proposed times, reason up to 500 characters. Night-shift check-outs roll to the next day.
- **FR-REG-2** Refused when: the day is in the future; the month is locked; the day does not fit the kind ("This day isn't marked as loss of pay.", "There's no late mark on this day.", ...); one is already pending or done for that day and kind. The employee can withdraw a pending request.
- **FR-REG-3** The request goes to everyone with `appr.reg` (HR, Finance, Owner). The **first decision is final**; role, person and time are stored; you cannot decide your own request; a locked month cannot be approved ("decline this and adjust LOP in payroll instead").
- **FR-REG-4** Approval effects: absent day becomes paid (LOP removed); late mark removed; missed check-out punch added; holiday work credits a comp-off (1 day for full-day hours, 0.5 for half-day hours, none below; expires after 60 days; refused if under half a day).
- **FR-HOL-1** Holiday calendar (`holidays.manage`): date, name, applies to India / US / Everyone. Past holidays cannot be deleted; a locked month cannot change; one holiday per date and team. Employees see their region's plus Everyone's; holidays are never counted as leave or LOP.

**Data:** punches, attendance days (cached, locked rows never rewritten), regularization requests, holidays, job runs.
**Notifications:** decision to the employee (bell and email). **Audit:** attendance.punch, regularization.request / cancel / approve / decline, attendance.month_locked, attendance.export, holiday.create / delete.

## 7. Leave

**Screens:** Leave and holidays, Approvals (Leave tab).

- **FR-LEV-1** Types (defaults, configurable): Casual (CL) 12 a year, 1 per month, no carry forward, at most 3 days at a stretch; Earned (EL) 18 a year, 1.5 per month, carry forward up to 30, encashment 15 a year after 12 months; Comp-off (CO) from approved holiday work, expires in 60 days; Leave without pay (unpaid).
- **FR-LEV-2** Balances come only from a **leave ledger** (accrual, used, cancelled, encashed, expired, adjustment). Monthly accrual is credited by the job, pro-rata in the joining month, with backfill. Comp-off expiry uses the oldest credits first. On 1 January CL lapses and EL carries forward up to its cap.
- **FR-LEV-3** Apply (`POST /api/leave/requests`; preview `GET /api/leave/previews`): type, date range, Full day / First half / Second half for a single day, reason up to 500 characters. Weekly offs and holidays are skipped. The preview shows working days, balance after, and days that become LOP if the balance is short. Pending requests hold their days.
- **FR-LEV-4** Refusals: unknown type; Earned leave during probation; start in the past ("request a regularization"); before joining; beyond the last working day; more than 62 days; only weekends or holidays; longer than the type's stretch; the month is locked; overlap with another pending or approved request.
- **FR-LEV-5** The employee can cancel a pending request. HR (`appr.leave`, the Owner also) approves or declines, with an optional note up to 300 characters; not one's own; once only ("Someone else decided this request a moment ago"). Approval posts the used days to the ledger and recomputes the LOP split from the balance at that moment.
- **FR-LEV-6** The Approvals page has a Leave tab and an Attendance tab, each shown only with that permission, with balance impact, overlaps in the team, and a "Decided" list.

**Statuses:** PENDING, APPROVED, DECLINED, CANCELLED. **Notifications:** decision (bell and email). **Audit:** leave.apply, cancel, approve, decline.

## 8. Salary structures, payroll, payslips

**Screens:** Salary structures, Payroll, Payslips (own), payslip PDF.

- **FR-STR-1** Structure (`struct.manage`): monthly Basic, HRA, LTA, Special allowance, effective-from date, reason (Joining, Appraisal, Promotion, Correction). A "Split" helper takes a gross: Basic 50%, HRA 20%, LTA 5%, Special the rest (rupee-rounded). **India employees only.**
- **FR-STR-2** History is only added to. Not before the joining date; not for past employees; not on a date that already has a revision; not at or before the last closed month ("pay any difference as arrears"). Payroll uses the structure effective on the last day of the month.
- **FR-PAY-1** One run per business per month: attendance lock, compute, review and edit, approve, close and publish. Computation is live until closed.
- **FR-PAY-2** Per employee: `eligible days` = from the later of joining date and the 1st to the earlier of exit date and month end. `LOP days` = unregularized absences + LOP leave + half-days from late marks (or Finance's override). `paid days` = eligible - LOP. `earned component` = monthly rate x paid days / days in month, rounded to whole rupees. `gross earned` = components + bonus. `PF` = 12% x min(earned Basic, 15,000). `Professional tax` = 200 a month by default (when anything is earned). `TDS` from section 10. `Net` = gross - PF - PT - TDS - other deductions.
- **FR-PAY-3** `pay.view` can edit LOP days (0 to 31, steps of 0.5), bonus or arrears, other deduction (whole rupees, up to 10 crore) and a note (200 characters) per India employee while the run is open; each edit and reset is audited (`payroll.edit`). A closed month refuses edits ("Corrections go into the next month as arrears").
- **FR-PAY-4** Only `pay.close` approves and closes (`PUT /api/payroll/{month}/status` `{"status":"CLOSED"}`); a Finance Executive sees "Closing needs Finance Manager". Closing is refused before the attendance lock ("Attendance for this month locks on the 1st of next month at 10:00 AM. Close after that.") and when already closed. Approve and close happen in one call and write `payroll.approve` then `payroll.close`.
- **FR-PAY-5** Closing **snapshots every payslip** (immutable JSON plus PDF) and publishes them; later changes to structure, regime or attendance never change a closed payslip. Employees are told by bell and email.
- **FR-PAY-6** KPIs: employees, gross earned, LOP days, TDS to deposit, net payout. Exports (`pay.view`, audited): bank transfer file, PF ECR, professional tax, TDS summary (also needs `tax.view`), register. CSV.
- **FR-SLIP-1** A month appears in Payslips only after it is closed; open months show "Processing". View in the browser and download PDF: company logo, name and address; employee name, ID, designation, department, joining date, masked PAN and bank account, regime, pay date; days in month, weekly offs, holidays, present, LOP and paid days; earnings; PF, PT, TDS, other; gross, deductions, net in figures and words (Indian system). Own payslip with `self`; anyone's with `pay.view`.
- **FR-SLIP-2** US employees are not on this payroll: "US team pay statements come from the US payroll provider."

**Data:** salary_structures, payroll_runs, payroll_adjustments, payslips (snapshot, PDF path, published_at). **Audit:** salary.structure_change, payroll.edit / approve / close / export.

## 9. Policies (company policies, acknowledgement gate)

**Screens:** Company policies (manage), Policy detail, My policies, Accept company policies (`/acknowledge`).

- **FR-POL-1** `policies.manage` creates a draft (title, rich text up to 100,000 characters, optional PDF up to the size limit, "requires acknowledgement" on by default), edits it, deletes it if never published, and publishes it.
- **FR-POL-2** Each publish is an **immutable version** (title, SHA-256 of text and of file). A published policy is never edited; a change creates the next version. Publishing needs text or a PDF, and something changed since the last version. Archive takes it off everyone's list (versions and acknowledgements kept); publishing again restores it.
- **FR-POL-3** Publishing notifies every person who must acknowledge it (bell, stays until accepted). Everyone except Owners and the super admin must accept the current version of every published policy that requires it. HR can send reminders to those who have not.
- **FR-POL-4** **Gate:** while a user has a pending policy, every business API answers **428** `POLICY_ACK_REQUIRED`, except sign-in and out, `/api/me`, `/api/shell`, notifications, page views, and reading and accepting the policies. The web app shows only the acknowledgement screen. A business in read-only mode is never asked.
- **FR-POL-5** Accept (`POST /api/policies/{id}/acknowledgements` with the version): idempotent; 409 if a newer version exists. Each acknowledgement is a legal record: policy id and version, title, content hashes, employee name, email and code, time (UTC), IP, user agent. The table is append-only (database trigger).
- **FR-POL-6** HR and Owner see who has and has not accepted and export the evidence as CSV or PDF (audited). Every employee sees all current policies and when they accepted each.

**Audit:** policy.create, update, delete, attach, detach, publish, archive, acknowledge, remind, evidence_export.

## 10. Tax (TDS), declarations, proofs

**Screens:** Tax declarations (employee), Investment proofs (Finance), Tax and TDS (Finance).

- **FR-TAX-1** Tax rules are data per financial year (slabs, standard deduction, rebate, cess, surcharge, section caps). Defaults FY 2026-27 (Finance must verify): **New regime** standard deduction 75,000, full rebate up to taxable 12,00,000 with marginal relief just above, slabs 0 to 4L nil, 4 to 8L 5%, 8 to 12L 10%, 12 to 16L 15%, 16 to 20L 20%, 20 to 24L 25%, above 24L 30%. **Old regime** standard deduction 50,000, rebate up to 5,00,000, slabs 0 to 2.5L nil, 2.5 to 5L 5%, 5 to 10L 20%, above 10L 30%. Cess 4%; surcharge above 50L.
- **FR-TAX-2** Annual taxable income = projected salary (actuals so far + current structure x remaining months) + previous employer income - standard deduction - (old regime) approved deductions. **Monthly TDS** = (annual tax - TDS already deducted this year, including previous employer) / remaining months including the current one, never negative.
- **FR-TAX-3** The employee picks the New (default) or Old regime on the declaration page with a live comparison, until the regime-change date (default 31 Jan 2027). If Finance overrode the regime, the employee cannot change it ("Finance has set your regime").
- **FR-TAX-4** Old regime items: HRA (rent, metro or not, landlord PAN when rent exceeds 1,00,000 a year; exemption = least of HRA received, rent - 10% of Basic, 50% metro or 40% of Basic); 80C (cap 1,50,000: EPF automatic, PPF, ELSS, life insurance, Sukanya, tuition, home loan principal, NSC/FD); 80CCD(1B) NPS cap 50,000; 80D self/family 25,000, parents 25,000 or 50,000 if senior; 24(b) interest cap 2,00,000; 80E no cap; 80G; 80TTA cap 10,000. Section labels are configurable.
- **FR-TAX-5** Item statuses: Draft, Pending, Approved, Rejected (with reason). Changing an approved or rejected amount sends it back to Draft (`declaration.reopened`); a Pending item cannot be changed until Finance decides. Whole rupees only.
- **FR-TAX-6** Submit (`POST /api/declarations/submissions`): only on the old regime; each draft with an amount needs a proof; landlord PAN if rent is over 1,00,000. Proof upload: PDF, JPG or PNG up to the limit (default 5 MB), inside the proof window (default 1 Apr 2026 to 31 Jan 2027), owner only.
- **FR-TAX-7** Finance (`proofs.verify`) approves or rejects an item (a reason is mandatory on reject) or approves all pending; not one's own proofs; a decided item cannot be decided again. The drawer shows TDS now versus TDS if everything pending is approved. **Only approved amounts reduce TDS.** The employee is told (bell).
- **FR-TAX-8** Tax page (`tax.view`): one row per employee (regime, projected income, deductions, taxable, annual tax, monthly TDS, pending proofs) with a computation drawer and new-vs-old comparison; override regime (employee notified); enter previous-employer income and TDS (TDS cannot exceed income); search, regime filter, sort, export.
- **FR-TAX-9** Declarations are for India employees only.

**Audit:** tax.regime_change, tax.previous_employer, tax.export, declaration.save, submit, proof_upload, reopened, proof.decision.

## 11. Announcements, assets, HR letters

- **FR-ANN-1** `announcements.manage` posts an announcement (title, text up to 10,000 characters, audience All / India / US, pinned, optional expiry not in the past). Posting publishes at once and rings the bell of everyone it is for. Edit in place (no new notice); unpublish hides it; publishing again notifies again. Employees see active ones (published, not expired, for their region), pinned first, newest first.
- **FR-AST-1** `assets.manage` keeps the register: unique tag, type (laptop, desktop, phone, tablet, monitor, accessory, other), make, model, serial, purchase date, notes. Settable statuses: AVAILABLE, IN_REPAIR, RETIRED; ASSIGNED only by assigning.
- **FR-AST-2** Assign only an AVAILABLE asset to an active employee with a date; the employee is notified. Return with a date not before the assignment and a condition: damaged moves it to in repair, lost to retired. An assigned asset must be returned before its status changes. An asset that was ever assigned cannot be deleted (retire it). Each employee sees current and past assets (My assets).
- **FR-LET-1** `letters.manage` keeps letter templates (name unique, type OFFER, APPOINTMENT, EXPERIENCE, RELIEVING, EMPLOYMENT_VERIFICATION, text 20 to 20,000 characters, merge fields, version, active) and issues a letter to an employee: fields are filled, a preview is available, the result is stored as an **immutable record** (text, SHA-256, PDF, issuer, time, reference `HRL/<FY>/<nnnn>`), the employee is told (bell, email with the PDF).
- **FR-LET-2** A template that uses a salary field (CTC) can be previewed, issued or opened only by someone with `struct.manage`; HR never sees salary. Issuing is refused for a switched-off template, for a CTC letter when the person has no structure, and for a last working day before the joining date. Employees read their own letters (My letters).

**Audit:** announcement.*, asset.create / update / delete / assign / return, letter.issue, letter_template.create / update.

## 12. Employee onboarding wizard

**Screens:** the six-step wizard and Information Summary (the new employee); onboarding status in the Employees list and a read-only view of the submitted data and files (HR).

- **FR-ONB-1** When HR adds an employee with the add-employee form, onboarding is off by default (status NOT_REQUIRED, immediate access); `isOnboardingRequired: true` sets NOT_STARTED. Employees that existed before the wizard are NOT_REQUIRED. Statuses: NOT_REQUIRED, NOT_STARTED, IN_PROGRESS, SUBMITTED (waiting for HR), CHANGES_REQUESTED (HR sent it back), APPROVED. `isRequired` is true in NOT_STARTED, IN_PROGRESS, SUBMITTED and CHANGES_REQUESTED. The shell and `/api/me` carry `onboarding: {status, isRequired}`; while `isRequired` is true the app sends the employee to the wizard (or, when SUBMITTED, to a read-only waiting page) after the emailed-code sign-in.
- **FR-ONB-2** Six steps: personal (with addresses, emergency contact, education, courses), previous employment, accounts & statutory (bank, PF, PAN, Aadhaar, passport), family (guardian for minors), nominations (EPF, EPS, ESI, gratuity), attachments. Each step is validated by the server (field errors keyed by JSON path, 422, nothing saved when invalid) and saved on its own; the employee resumes at the saved step.
- **FR-ONB-3** Nominations: a single nominee is 100 %, several add up to 100 %, a member appears once per block, EPS and ESI have one nominee. Removing a family member removes the nominations that point at him.
- **FR-ONB-4** Bank account number, PAN, Aadhaar and UAN are stored encrypted and returned masked; the employee reads the full values only through the secrets endpoint (audited). HR sees them masked, with no way to reveal them.
- **FR-ONB-5** Upload slots follow the entered data (permanent and present address, one per qualification, one per previous employment, bank, PAN, Aadhaar, passport when a passport number was entered). Allowed pdf, doc, docx, jpg, png, up to 10 MB, checked by extension and content; stored under `onboarding-forms/<YYYY-MM>/<employeeCode>/<slot>/`. Removing an education or employment removes its files from disk too.
- **FR-ONB-6** Submission checks every step and every mandatory slot (422 lists all that is missing), sets SUBMITTED and makes the data read-only: every write answers 409 until HR decides. A submission from CHANGES_REQUESTED is a resubmission and clears HR's comment and flags. Everyone with `people.add` gets a bell notification. HR (`people.view`) reads the data and downloads the files. Step saves, submission, HR decisions and secret reads are audited. The earlier pre-hire onboarding by link (candidate token, HR document review) no longer exists.

- **FR-ONB-7** HR approval: only HR with `people.add` decides, and only on a SUBMITTED onboarding (409 otherwise, 404 when the employee has no onboarding or belongs to another business). `POST /api/people/{id}/onboarding/approvals` sets APPROVED and opens the portal. `POST /api/people/{id}/onboarding/rejections` takes an overall comment and at least one flag `{section, comment}` (sections PERSONAL, ADDRESS, QUALIFICATIONS, EMPLOYMENT, ACCOUNTS, FAMILY, NOMINATIONS, ATTACHMENTS, each once) and sets CHANGES_REQUESTED; the employee edits and submits again. The employee gets a bell notification and an email for both decisions (the email lists the flagged sections with HR's comments). Both are audited (`onboarding.approved`, `onboarding.changes_requested`). The earlier "reopen" action no longer exists.
- **FR-ONB-8** Hard lock: while the onboarding is NOT_STARTED, IN_PROGRESS, SUBMITTED or CHANGES_REQUESTED the server answers 403 (problem type and title `onboarding-pending`) on every `/api/**` call of that employee except sign-in, sign-out and refresh, `/api/me`, `/api/shell`, `/api/onboarding/**`, reading `/api/notifications`, the own profile photo and the page-view log. The super admin and employees with no onboarding row (or NOT_REQUIRED, APPROVED) are never locked.

## 13. Tasks and projects

**Screens:** My tasks (list or board), Team tasks, Projects, Project (List and Board), Project settings, Task page (`/tasks/{key}`), Task settings. Free for every business; read-only after the subscription ends.

### 13.1 Projects and access

- **FR-PRJ-1** A project has a key (2 to 6 capital letters or digits, first a letter, unique, trimmed and upper-cased), a unique name, description, colour, optional department, and visibility **PRIVATE** (members only) or **BUSINESS** (company-wide: everyone reads and comments). Creating needs `projects.create` (or `tasks.manage`); the creator is the owner.
- **FR-PRJ-2** Project roles: OWNER (everything including members, steps, labels, archive), MEMBER (create and edit tasks, delete tasks they reported), VIEWER (read, comment, follow). A project keeps at least one owner. The key cannot change once the project has tasks. Changes carry a `version` (409 if someone else changed it).
- **FR-PRJ-3** A project with tasks is **archived** (read-only; writes answer 422) instead of deleted; an empty project is soft-deleted. Nothing is hard-deleted by the app (tasks, comments, attachments, projects).
- **FR-PRJ-4** A non-member sees a company-wide project as a viewer; a private project does not exist for them (**404**, no leak). The direct assignee can work on the task; the reporter, followers and the manager of the direct assignee (direct reports, plus one more level) read and comment, also inside private projects (that task only).
- **FR-PRJ-5** The **Owner** (`tasks.manage`) sees every project and task, personal ones included, and acts as project owner everywhere. Opening a private project or task they are not part of writes `project.private_access` / `task.private_access` (no notification). This is the only exception to the 404 rule; HR and Finance have no special view.
- **FR-PRJ-6** Each project has its own workflow steps copied from the business default (To do, In progress, In review, Done, Cancelled). Each step is in a category (to do, in progress, done); at least one to-do and one done step, at most 12, exactly one starting step (a to-do one), unique names. Deleting a step with tasks needs a target step; the starting step cannot be deleted. Task settings (`tasks.manage`) edit the default set, which affects personal tasks and new projects only. Changing a step to or from the done category stamps or clears `completedAt`.
- **FR-PRJ-7** Labels: business labels (Task settings) and project labels, at most 50 per set, at most 20 on a task.

### 13.2 Tasks

- **FR-TASK-1** Fields: title (200 characters), description (plain text with `@mentions`, 20,000), type (task, issue, request, milestone), step, priority (urgent, high, medium, low, none), labels, start date, **due date with optional due time**, an optional extra reminder, estimate in minutes, parent, followers (up to 50), checklist (up to 100 items).
- **FR-TASK-2** A task in a project gets the next number (`HR-12`); the key is found case-insensitively and a moved task keeps answering to its old key. A **personal task** has no project and no number.
- **FR-TASK-3** **One direct assignee** (does the work) and **one optional indirect assignee** ("accountable", must be someone else). Both see the task in My tasks, are notified and follow it. A direct assignee who is not a project member is added as member; the indirect one is not. The create form suggests the assignee's reporting manager as accountable but never sets it.
- **FR-TASK-4** Rights: project OWNER all; MEMBER edits all fields and deletes own reported tasks; VIEWER reads and comments; the direct assignee changes status, checklist and files; the **indirect assignee** comments, changes step and priority and **reassigns the direct assignee**, may step down, but edits no other field and cannot delete (unless project owner). Each field needs its own right (403).
- **FR-TASK-5** Changes need the version the client read (409 on conflict); a field sent with its current value is not a change. Sub-tasks: one level, same project, up to 100 per task. Moving to another project: only a task without parent and sub-tasks; it lands in the first step of the same category; old project labels are dropped; `projectId: null` makes it personal.
- **FR-TASK-6** Board: cards in server-calculated order, open ones plus those done in the last 14 days (cap 500 cards, with true counts). A move into another column is a status change: bumps the version, writes history and notifies followers.
- **FR-TASK-7** **Due time and time zone:** the task time zone is the direct assignee's, else the indirect assignee's, else the reporter's. A date-only task is due at the end of that day and overdue from the next day; a timed task is overdue from its due time. Done and deleted tasks are never overdue. Clearing the due date clears the time and the extra reminder.
- **FR-TASK-8** Comments (5,000 characters, `@mentions`); the author edits; the author, a project owner or the Owner deletes ("Comment deleted" remains). Commenters and mentioned people follow the task; people who cannot see the task are neither notified nor given access.
- **FR-TASK-9** Attachments: PDF, PNG, JPG, `.docx`, `.xlsx`, `.pptx`, text, CSV, checked by first bytes; up to 10 MB each and 20 per task; downloads are audited; removed files stay stored.
- **FR-TASK-10** Every change is written to the task's history (activity) and to the audit log (task.create, update, move, delete, watcher_add / remove, checklist_change, comment_add / edit / delete, attachment_add / delete; project.*; workflow_status.*; task_label.*).
- **FR-TASK-11** Views: **My tasks** (open tasks as direct or indirect assignee, or reported or followed, grouped overdue, today, this week, later, no date; list or board); **Team tasks** (anyone with reports; direct reports, one more level with a switch; per-person summary open, overdue, done in last 7 days); **Projects**. All lists share filters (project, step, category, assignee, accountable, reporter, priority, type, label, due, dates, search including task keys) and sorting. The sidebar badge on My tasks counts my open tasks that are overdue or due today.

### 13.3 Task notifications and reminders (bell only)

- **FR-TNOT-1** Bell notices for: assigned, made accountable, mentioned, comment or step change on a followed task, added to a project. One request sends at most one notice per person (mention beats assignment beats comment beats status); the person who acted is never notified; people who left or have no portal access are not notified.
- **FR-TNOT-2** Daily reminders at **09:00 in the task time zone**, to both assignees, once per due date (a new date re-arms them): due tomorrow, due today (timed tasks), overdue (date-only tasks from 09:00 the next day; timed tasks right after their time).
- **FR-TNOT-3** **Extra reminder** (optional per task, needs a due date): `reminderBeforeMinutes` = 15 minutes, 60 (1 hour), 240 (4 hours) or 1440 (1 day) before the due moment; any other value is refused with 400 "Choose 15 minutes, 1 hour, 4 hours or 1 day before the due time."; without a due date 400 "Choose a due date first." The due moment is the due time, or 00:00 of the next day for a date-only task. The bell text is "{key} is due in 1 hour (due 10 Mar 2031 17:00): {title}" to both assignees, once per due moment, checked every 10 minutes, in the task time zone. Not sent for done or deleted tasks, never after the due moment; changing the due date, time or offset sends it again. It is in addition to the 09:00 reminders, which are unchanged. Changing it needs the same right as the dates and writes a DATES_CHANGED history row.
- **FR-TNOT-4** These bell texts are shown in Hindi to Hindi readers (FR-LANG-3), including the extra reminder.

## 14. Home, Overview, Settings, Platform console

- **FR-HOME-1** Home (`self`): greeting with live clock, check-in card, KPIs (present this month, average day, leave available, next holiday), 16-week heatmap and last 7 shifts, upcoming holidays, notification card, plus cards by permission (my leave requests, your team right now, approvals count, payroll status for Finance, payslip ready). Salary is never shown on Home.
- **FR-HOME-2** Overview (Owner): headcount (India/US, access off), checked in now, pending approvals, proofs to verify, payroll status and net payout, people by department, HR and Finance queues, shortcuts.
- **FR-SET-1** Settings (`roles.manage`): attendance policy (hours, grace, late marks, weekly offs, absence buffer, lock day and time, go-live date, which cannot change once a month is locked; half-day hours must be less than full-day hours), payroll (PF rate and ceiling, professional tax), tax windows, probation months, onboarding documents. Changes are audited (`policy.attendance_change`).
- **FR-PLAT-1** Super admin console: businesses (create with defaults, optional first subscription and first owner; edit; suspend or reactivate; short name lowercase letters, numbers, dashes; ID prefix; time zone; GSTIN format; contact phone `+91 8888788888`), owners (add; turn access on or off, which ends sessions), roles per business (add, remove, sync), plans (create, edit), subscriptions (assign, change dates or cancel), module grants, role templates (create, edit, delete; locked and mandatory ones are protected), platform audit log.
- **FR-CMD-1** Shell: sidebar groups (My workspace, Tasks, Team, People ops, Finance, Administration, Platform) built from permissions and modules; profile menu; light and dark theme (light default); command palette; responsive layout (sidebar becomes a drawer on phone).

## 15. JurisTrack integration (placeholder)

- **FR-JT-1** A sidebar entry **Juris Track** (nav id `juris-track`, route `/juris-track`) is shown to users with `self`, `dir` or `roles.manage` **only** when the business's plan includes module `JURIS_TRACK` or the super admin granted it (complimentary or paid), and its subscription covers today (an ended one keeps it view-only).
- **FR-JT-2** Today the page and `GET /api/juris-track` answer "Coming soon". For a business without the module the page and the API answer **404**, so it cannot be reached by URL.
- **FR-JT-3** Planned (design in `juris-track-backend/references/INTEGRATION.md`; **not built in JurisOne yet**): JurisOne acts as gateway, checks session, plan or grant and permission, signs a short-lived (about 5 minutes) single-use JWT (`iss=jurisone`, `aud=juristrack`, `sub`, email, name, business id and name, `managerId`, `trackRole`, `jti`) and forwards `/api/juris-track/**` to the JurisTrack API; it also pushes the employee roster and keeps requests audited with a shared request id. The diagram is in `FLOW-JurisTrack-JurisOne-Connection.md`.

## 16. Non-functional requirements

| Area | Requirement |
|---|---|
| Security | Server-side permission check on every API; row-level security per business; PII encrypted at rest; HTTPS only in production; CSRF origin check; rate limits on code requests and sign-in; secrets never logged; uploaded files type-checked by their bytes. |
| Accuracy | Payroll and tax maths are pure functions with unit tests, integers in paise. Golden case: JC-074 Amit Jaspal, Sep 2026 (joined 8 Sep, 1 LOP, Basic 75,000, HRA 30,000, LTA 7,500, Special 37,500, previous employer income 7.5L and TDS 38,000, new regime) gives monthly TDS about 14,926 and 22 of 30 paid days. |
| Time | Every date and time is computed in the employee's zone; the register can show other zones. |
| Performance | Pages load in under 2 seconds for 500 employees, with server-side paging; task lists and boards stay fast with many tasks (500-card cap per board). |
| Availability and data | Daily PostgreSQL backup (the storage folder is included); migrations are reversible (rollback scripts U1 to U20); jobs are idempotent and run once at a time. |
| Usability | Works on a phone; WCAG AA contrast; keyboard accessible; English and Hindi. |
| Quality gates | Typecheck, lint, unit tests, build, `knip` (no unused files, exports, dependencies) and end-to-end tests for behaviour changes; each new endpoint has an integration test and, for writes, an audit entry. |

## 17. Open points (code and requirements disagree, or something is unclear)

1. **Account lockout is not enforced.** REQUIREMENTS 4 and `auth.policy` (5 failures, lock 15 minutes) ask for a lock after repeated failures. The sign-in code only checks `lockedUntil`; nothing sets it or counts `failedAttempts`. Protection today is 5 tries per emailed code plus the per-IP limits.
2. **Permission catalogue in REQUIREMENTS 3.1 is out of date.** The code also has `policies.manage`, `assets.manage`, `announcements.manage`, `letters.manage`, `business.manage`, `platform.manage`; the HR Manager template holds the first four. This FRD follows the code.
3. **Technology section in REQUIREMENTS 2 is out of date.** It says Next.js, Prisma, pg-boss and zod; the product is React + Vite and Spring Boot with Flyway (MIGRATION_PLAN). Also SAAS_PLAN 2 describes password sign-in and `proxy.ts`; passwords were dropped (V16) and sign-in is by code only.
4. **Expiry wording differs.** SAAS_PLAN says that after expiry only the employee export works; REQUIREMENTS and the code give a read-only product (all reads and exports work, every write is 402). The subscription-ended warning text in `JobsService` still says "only the employee export works until it is renewed". This FRD follows the code (read-only).
5. **Leftovers of removed features.** `SettingsService.Company` documents `tan` / `pan` "for Form 16", and the audit filter has an `exit` group; Form 16 and exit/F&F were removed on purpose. Marking an employee as past (`employee.exit`) is still a feature.
6. **Approve and close are one step.** REQUIREMENTS 5.8 lists Approve then Close and publish as stages; the API does both in one call.
7. **Owner and `self`.** The Owner has no `self`, so cannot see own payslips or leave. This matches REQUIREMENTS 3.2.
8. **Probation EL rule and comp-off expiry** are configurable in data (`leave_types`); the numbers in FR-LEV-1 are the seeded defaults.
9. **JurisTrack manager role.** INTEGRATION.md expects a "JurisTrack manager" grant in JurisOne sent as `trackRole`; no such grant exists in JurisOne yet, nor the gateway or roster push.
10. **Open business questions** from REQUIREMENTS 9 remain: professional-tax state, ESI/gratuity/LWF, biometric or IP restriction on check-in, SSO, US payroll, the Balkind font licence.
11. **Tax section names** change from 1 April 2026 (Income-tax Act 2025); labels are configurable but Finance or the CA must verify the seeded numbers.
12. **Email for tasks** is a later phase; per-person notification preferences and a daily digest are not built.
