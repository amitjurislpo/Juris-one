# JurisOne

> **The web app moved.** This repository is the backend (Spring Boot API, database, scheduled jobs). The user interface is a React app in its own
> repository, [juris-one-frontend](https://github.com/amitjurislpo/juris-one-frontend); it serves the pages and proxies `/api/*` to this API.
> The pages, the UI code and the browser tests live there too.

HRMS and payroll portal for Juris Consultants. It covers attendance, leave, people records, payroll, TDS and payslips.

- **Rules:** [references/REQUIREMENTS.md](references/REQUIREMENTS.md) is the source of truth.
- **Design:** [references/Juris-People-Design-8.html](references/Juris-People-Design-8.html) is the clickable prototype. Its CSS is ported verbatim into the frontend repo (`src/globals.css`).
- **Backend migration:** [references/MIGRATION_PLAN.md](references/MIGRATION_PLAN.md) records how the API was built (a Spring Boot service), the decisions taken and the parity checklist.

## Stack

| Part | Technology | Port |
|---|---|---|
| Frontend | React single-page app (Vite, TypeScript) in its own repo, `juris-one-frontend` — it serves the pages and proxies `/api/*` to the API | 3005 |
| Backend | Java 17, Spring Boot 3.5 (Web, Security, Validation, Data JPA, JDBC), Flyway, Maven Wrapper — in `backend/` | 8080 |
| Database | PostgreSQL 16 in docker-compose | 5436 on the host |
| Tests | JUnit 5 + Testcontainers (backend); the frontend repo has Vitest and the Playwright end-to-end tests | |

The browser only talks to the web app's origin, which forwards `/api/*` to the API, so the API is never exposed on its own. Signing in sets two HTTP-only cookies: a 5-minute JWT access token (`jo_at`) and a rotating refresh token (`jo_rt`) that the web app uses to renew it silently; passwords are argon2id. Every endpoint, with its method and status codes, is listed in [MIGRATION_PLAN.md §14](references/MIGRATION_PLAN.md#14-rest-api-after-the-migration).

## Quick start (development)

Prerequisites: JDK 17+ and Docker Desktop. Maven comes with the wrapper (`backend/mvnw`, `backend\mvnw.cmd` on Windows).

```bash
docker compose up -d db                                            # Postgres 16 on 127.0.0.1:5436
cd backend
./mvnw spring-boot:run -Dspring-boot.run.arguments=migrate         # create / update the schema (Flyway)
./mvnw spring-boot:run -Dspring-boot.run.arguments=seed            # shared tables and the super admin (jurisone.seed.demo=true adds a demo business)
./mvnw spring-boot:run                                             # API on http://localhost:8080 (leave it running)
```

(PowerShell: `cd backend; .\mvnw.cmd spring-boot:run`. The default Spring profile is `dev`.)

Then start the web app from the `juris-one-frontend` repo (`npm install`, `npm run dev` → http://localhost:3005); see its README.

### Sign in

Everyone signs in with their **email** and a 6-digit code emailed to it (there are no passwords). Employee IDs repeat from one business to the next, so they are not a sign-in name. In `dev` the code is always `000000`.

| Who | Email |
|---|---|
| Super admin (platform) | `jurisone.seed.super-admin-email` (in `application-<env>.properties`) |
| Demo business owner — sub admin (only with `jurisone.seed.demo=true`) | `sana.kapoor@jurislpo.com` (JC-002) |
| Demo business employees (only with `jurisone.seed.demo=true`) | `firstname.lastname@jurislpo.com` (JC-004 … JC-091) |

## Commands

Run these in `backend/` with the Maven Wrapper (`./mvnw`, or `.\mvnw.cmd` on Windows):

| Command | What it does |
|---|---|
| `./mvnw spring-boot:run` | Spring Boot API (profile `dev`; add `-Dspring-boot.run.profiles=sqe` for another environment) |
| `./mvnw test` | Backend unit + API tests (JUnit 5; Testcontainers starts its own Postgres, so Docker must be running) |
| `./mvnw -DskipTests package` | Build the jar `target/jurisone-backend-*.jar` |
| `./mvnw spring-boot:run -Dspring-boot.run.arguments=migrate` | Apply pending Flyway migrations and exit |
| `./mvnw spring-boot:run -Dspring-boot.run.arguments=seed` | Idempotent seed: shared tables (shifts, leave types, tax) and the super admin. `jurisone.seed.demo=true` also creates the demo business. |
| `./mvnw spring-boot:run -Dspring-boot.run.arguments=reset` | **Development only:** empties the database, migrates, seeds and clears stored payslips / proofs. Refuses unless `jurisone.app-url` is localhost or `jurisone.allow-db-reset=true`. |
| `./mvnw spring-boot:run -Dspring-boot.run.arguments=jobs` | Run the scheduled jobs once (for cron; with a jar: `java -jar ... jobs`) |
| `docker compose up -d db` / `docker compose up -d --build backend` | Postgres / the API in Docker |
| `scripts/backup.sh` / `scripts/restore.sh` | Postgres backup (keeps 14 days) / restore — see *Deploying* |

## Environments

Four environments. **Each one is a single file**, `backend/src/main/resources/application-<env>.properties`, on top of the shared
`application.properties`: ports, database, public URL, email, storage and secrets (nothing is shared between environments).

| Env | File | Web / API / DB ports | Database | Sign-in code | Demo data |
|---|---|---|---|---|---|
| `dev` | `application-dev.properties` | 3005 / 8080 / 5436 | `jurisone` | fixed `000000` | optional |
| `sqe` | `application-sqe.properties` | 3015 / 8090 / 5446 | `jurisone_sqe` | random, emailed | yes |
| `uat` | `application-uat.properties` | 3025 / 8100 / 5456 | `jurisone_uat` | random, emailed | no |
| `prd` | `application-prd.properties` | 3000 / 8200 / 5466 | `jurisone_prd` | random, emailed | no |

- **Secrets.** `dev` has local-only values written in its file (nothing in it is a real secret). `sqe`, `uat` and `prd` never have secrets in
  git: their files contain `${PLACEHOLDERS}` that you provide as **environment variables on the server**: `DB_PASSWORD`,
  `PII_ENCRYPTION_KEY` (`openssl rand -base64 32`), `JWT_SECRET` (`openssl rand -base64 48`), `SMTP_USER`,
  `SMTP_PASS`. Use a different PII key and JWT secret in every environment.
  Any setting can also be overridden by an environment variable, e.g. `JURISONE_MAIL_HOST=smtp.corp.example`.
- Pick the environment with the Spring profile: `SPRING_PROFILES_ACTIVE=sqe` or `-Dspring-boot.run.profiles=sqe` (default `dev`).
- Docker: the database and the API run per environment by setting variables for `docker compose` (see the top of `docker-compose.yml`): `COMPOSE_PROJECT_NAME`, `APP_ENV`, `DB_PORT`, `BACKEND_PORT`, `POSTGRES_DB`, `POSTGRES_PASSWORD` and the secrets above. Each project name gets its own containers and volumes, so environments run side by side.
- **Sign-in is by emailed code only** (no password option). Only `dev` may use the fixed code `000000` (`jurisone.otp.fixed-code`); `sqe`, `uat` and `prd` send real codes, so they need `jurisone.mail.*`. The identity provider (IAM JCG) replaces this later.
- **Guard:** `sqe`, `uat` and `prd` refuse to start with a fixed sign-in code or without a mail host; `uat` and `prd` also with demo data, an `http://` `jurisone.app-url` or a secret that is empty or still a `<placeholder>`.

## Deploying

JurisOne is a web app (static files from `juris-one-frontend`), a Spring Boot API and Postgres 16. Any Linux VPS or VM works (REQUIREMENTS §9.4 leaves hosting open).

1. **Postgres 16.** A managed instance, or the compose service with the variables for prd (`COMPOSE_PROJECT_NAME=jurisone-prd DB_PORT=5466 POSTGRES_DB=jurisone_prd POSTGRES_PASSWORD=… docker compose up -d db`; the port is bound to localhost only).
2. **Environment.** Edit `backend/src/main/resources/application-prd.properties` for your site (`jurisone.app-url` — the public https URL, the only origin the API accepts —, the database address, `jurisone.mail.host`, the super admin's email) and set the secrets as environment variables of the API process (systemd `Environment=` / a secret manager):
   - `DB_PASSWORD`
   - `PII_ENCRYPTION_KEY` — generate once and keep it in your password manager. Losing it makes PII unreadable.
   - `JWT_SECRET`
   - `SMTP_USER`, `SMTP_PASS` so sign-in codes and decisions are emailed.
   - Demo data is off in `prd` (`jurisone.seed.demo=false`), so no demo people are created.
3. **API.** Either in Docker:
   ```bash
   # with the prd variables and secrets set (see Environments):
   docker compose up -d --build backend      # migrates the schema on start
   docker compose run --rm backend seed      # roles, policies, tax tables, super admin (idempotent)
   ```
   or on the host (JDK 17+):
   ```bash
   cd backend && ./mvnw -B -DskipTests package && cd ..
   export SPRING_PROFILES_ACTIVE=prd                       # plus the secrets above
   java -jar backend/target/jurisone-backend-*.jar seed    # once; migrates, seeds and exits
   java -jar backend/target/jurisone-backend-*.jar         # port 8200 (server.port); keep it running with systemd
   ```
4. **Frontend.** Build and host the `juris-one-frontend` repo (`npm ci`, `npm run build` → static files in `dist/`). Serve them from a web server on the same origin as the API and send `/api/*` to the API (an nginx example is in that repo's README).
5. **HTTPS.** Put Caddy or nginx in front, terminate TLS there, serve the web app and proxy `/api/` to the API (`server.port`, 8200 in prd). Keep that port itself private. Session cookies are `Secure` in production, so the app must be served over https.
6. **Jobs.** Leave `jurisone.jobs-enabled=true` for a single API instance. With several instances either keep it on (an advisory lock lets only one run) or set it to `false` everywhere and run `java -jar … jobs` from cron every 10 minutes.
7. **Files.** `jurisone.storage-dir` (prd: `${STORAGE_DIR:/var/lib/jurisone/prd}`; dev: your `Documents/JurisOne`) holds payslip PDFs, investment proofs, policy PDFs and onboarding documents, **outside the project**. Put it on persistent disk and include it in backups. In Docker it is the `jurisone_files` volume (`/app/data/files`).

### Backups (daily)

```bash
# crontab -e   (02:15 every night; keeps 14 days in $BACKUP_DIR, default ~/Documents/JurisOne/backups)
15 2 * * *  DATABASE_URL='postgresql://user:password@host:5466/jurisone_prd' /srv/jurisone/scripts/backup.sh >> /var/log/jurisone-backup.log 2>&1
```

- Needs `pg_dump` 16+ on the server, or set `PG_DOCKER=jurisone-db` to run it inside the compose container.
- Copy the backups folder and the files folder (`jurisone.storage-dir`) to off-site storage (S3, Azure Blob, another host).
- Restore with `DATABASE_URL=… ./scripts/restore.sh <file.dump>` (stop the app first; it asks for confirmation). In dev: `DATABASE_URL=postgresql://jurisone:jurisone_dev_pw@localhost:5436/jurisone ./scripts/backup.sh`.
- Test a restore into a scratch database every few months.

### Upgrading and rolling back

- Deploy: pull, rebuild the API and run it (Flyway applies new migrations on start), then build and deploy the frontend (see its repo).
- **Take a backup before deploying a release with new migrations.**
- Migrations live in `backend/src/main/resources/db/migration/`. Each has a reverse script in `backend/src/main/resources/db/rollback/` (`V<n>__x.sql` ↔ `U<n>__x.sql`). To roll one back: run its `U` script with `psql`, delete its row from `flyway_schema_history`, then deploy the previous release.
- Databases created by the earlier Prisma version are baselined automatically: Flyway checks that all four Prisma migrations are applied, records them as version 1 and leaves the schema untouched.

## How it is put together

```
backend/                           Spring Boot API (Maven)
  src/main/java/com/jurislpo/jurisone/
    controller/                    REST controllers (one per area: auth, people, payroll, platform...)
    service/                       business logic, one service per area (plus mail, audit, jobs, seed)
    repository/                    data access: SQL queries, one repository per area
    exception/                     API exceptions, problem+json responses, the global handler
    common/                        shared types: ApiResult envelope, RequestMeta, list paging/sorting, validation
    entity/                        JPA entities for every table (schema validated, never generated)
    config/                        security chain, Flyway guard, Jackson, environment guard
    security/                      JWT filter, subscription / policy gates, origin check, Auth.requirePage / assertPerm
    tenancy/                       TenantContext + the DataSource that applies row-level security per business
    engine/                        pure rules: attendance, leave, payroll, tax, permissions, nav, formatting
    crypto/                        AES-256-GCM PII encryption, tokens, masking
    cli/                           migrate / seed / reset / jobs commands
  src/main/resources/db/migration/ Flyway migrations (V1 = the original schema)
  src/test/java/                   JUnit 5: engine tests + API tests against Testcontainers Postgres
```

### Security model

**Businesses are kept apart by the database.** JurisOne hosts many businesses in one database. Every business-owned row carries `business_id`, and PostgreSQL row-level security lets a request see and change only its own business's rows: the API sets the business of the signed-in user on every database connection (and switches to the restricted `jurisone_tenant` role, because superusers skip row-level security). Sign-in, the super admin and the scheduled jobs run without a business; the jobs then process one business at a time. Employee codes, department names, holidays, roles, policies and payroll months are unique per business; emails are unique across the platform.

**Roles come from the super admin.** Platform → role templates is a catalogue of roles (name, permissions, which roles each may assign). Creating a business picks its roles (Owner, HR Manager, Finance Manager and Employee are always added; others are optional) and each business gets a copy; the super admin can add, remove or re-sync a business's roles later (`/api/platform/role-templates`, `/api/platform/businesses/{id}/roles`). A business owner can look at roles but not change them.

**Subscriptions decide who can use JurisOne.** The super admin (Platform → Businesses, Plans) creates each business with its sub admin, the business owner, who has the `owner` role and runs everything in it but can't make another owner. Billing is manual: the super admin assigns a plan for a period (price, dates, notes) and renews it. A plan can cap the number of employees and include modules such as Juris Track.
- **Covered today:** everything works.
- **Last 7 days:** the owner sees a banner on every page and gets a bell notification and an email 7 days and 1 day before the end, and on the day after it ends.
- **Not started yet, or no subscription at all:** nobody in the business can sign in (`402 SUBSCRIPTION_INACTIVE`, "starts on <date>" / "no active subscription"). A session that already exists is refused on every API except sign-out, `/api/me` and the shell.
- **Ended (or cancelled after it ran; no grace period):** read-only. Everyone can sign in and view all their data, and the owner can export it, but every write (POST/PUT/PATCH/DELETE) answers `402 SUBSCRIPTION_INACTIVE` from the API itself; only sign-out / session refresh and marking notifications read stay writable. The web app hides every action. Pending policy acceptances are not asked (accepting is a write), and a candidate's onboarding link stops working. `GET /api/me` returns `readOnly: true`.
- **Suspended business:** nobody in it can sign in, and existing sign-ins end at once.
- **Juris Track:** a business has it when its plan includes the module or the super admin grants it on its own (complimentary, optionally with no end date, or paid with a price and an end date; revoking keeps the record). With it, everyone in the business sees *Juris Track* in the menu, which for now shows "Coming soon". Without it, there is no menu item and both `/juris-track` and `GET /api/juris-track` answer 404.
- **Employee export:** the owner can download every employee as Excel, before and after expiry. Identity numbers are masked unless the owner confirms the full export. Both downloads are audited.

**Company policies must be accepted, and every acceptance is a legal record.** HR managers and owners (`policies.manage`) write a policy (text and/or a PDF) as a draft and publish it. Each publish is a new, numbered version that can never be changed.
- While the current version of a published policy that needs acceptance hasn't been accepted (and the business's subscription hasn't ended), the user sees only the acceptance screen, and the API answers `428 POLICY_ACK_REQUIRED` to everything except sign-in, `/api/me`, the shell, notifications and reading and accepting the policies. Owners aren't asked. A new version asks everyone again.
- Each acceptance stores a snapshot: policy title and version, SHA-256 of the text and of the PDF, the employee's ID, name and email, the time (UTC), IP address and browser. Database triggers reject any UPDATE, DELETE or TRUNCATE on `policy_versions` and `policy_acknowledgements`.
- HR sees who has and hasn't accepted the current version, can send reminders, and can download the evidence (every acceptance of every version) as CSV or as a printable PDF with the full fingerprints. Both downloads are audited.
- Everyone can read the current policies under *My policies*. Published policies are archived, not deleted.

**Notifications stay until they are read.** The bell, the *Notifications* card on Home (and on the owner's Overview) and the `/notifications` page list unread notifications first. Opening one marks it read; each can be marked read or unread, or all at once. A policy notification stays unread until the policy is accepted (a newer version replaces it). API: `GET /api/notifications?unread=&limit=&page=`, `PATCH /api/notifications/{id}` `{read}`, `PATCH /api/notifications` `{read: true}`.

**Onboarding by link.** HR (`people.add`) starts an onboarding with the candidate's name and **personal** email; the candidate gets a one-time link (`/onboard/{token}`, valid 7 days; only its SHA-256 is stored; a new link replaces the old one). Through it they fill in their details (stored as encrypted JSON) and upload documents (PDF / JPG / PNG, checked by content, up to 10 MB). Required by default: ID proof, address proof, PAN, Aadhaar and the last 3 salary slips; each business can change this default (*Default documents* on the Onboarding page) and HR can still change the list per onboarding. HR verifies or rejects each document, can send the onboarding back with a comment (a new link is emailed) and marks it verified when every required document is verified. **Add employee** then creates the employee from the candidate's data (identity, contact, bank) with the work email, job and manager HR confirms; verified documents go on the employee's record (`GET /api/people/{id}/documents`). Portal access stays **off**; turning it on sends the "set your password" invite to the work email. The link doesn't work while the business is suspended or its subscription isn't active. Statuses: invited → in progress → submitted → (sent back) → verified → added, or cancelled; every step is audited.

**Employees list and My profile.** *Employees* (`people.view`) never lists the signed-in person or the business owner (admin); HR sees other HR. The CSV export and the owner's Excel export leave the owner out. Everyone in a business has *My profile*: their job record (read-only) and the personal details they edit themselves (father's name, date of birth, current and permanent address, emergency contact). Name, work email, phone, IDs, bank and job details are changed by HR. Names are first / middle / last (first and last required); phone numbers carry a country code and must have that country's length; PAN and Aadhaar are optional; the work location is optional (India when not set) and a free-text *joining office* records where someone joins.

**Permissions are checked by the API.**
- Every page of the web app is guarded by its nav permission, and the API checks the same permission again for the data it returns.
- Every write goes through an API endpoint that calls `assertPerm(...)`.
- HR roles never receive salary or tax fields from the API.
- Hiding UI is cosmetic only.

**Sessions are re-validated on every request.**
- A session that is revoked or expired is rejected.
- So is the session of an employee whose access is disabled or who has exited.
- Disabling access or marking someone exited revokes their sessions immediately.

**Sign-in codes, tokens and sessions:**
- There are no passwords: a sign-in code is emailed, works once, expires in 10 minutes and is stored only as a hash.
- Access tokens are JWTs (HS256, `jurisone.security.jwt-secret`) that expire after 5 minutes; every request also re-checks the session and the employee, so revocation is immediate.
- Refresh tokens are 256-bit random values that rotate on every use; only their SHA-256 is stored, and replaying an old one revokes the session.

**Login protection:**
- Failed attempts are limited per IP.
- An account locks after 5 failures (configurable).
- Every login attempt is stored in `login_history`.

**PII:**
- PAN, Aadhaar, UAN, phone, address and bank account are encrypted at rest with `jurisone.security.pii-encryption-key`.
- Only the last 4 characters are stored in clear, for masked display.
- **Back the key up. Losing it makes that data unrecoverable.**

**CSRF and CORS:** every API write must come from `jurisone.app-url` (Origin check), and CORS allows only that origin.

**Errors:** every API error is `application/problem+json` (RFC 7807): `status`, `title`, `detail` (the message shown to the user), `instance` and a stable `code` (`VALIDATION_FAILED`, `UNAUTHENTICATED`, `TOKEN_EXPIRED`, `FORBIDDEN`, `NOT_FOUND`, `CONFLICT`, `BUSINESS_RULE`, …), plus `errors` with field messages for validation.

**Secrets:** no password, key or secret is stored in the code, README or the env templates; everything comes from the environment.

**Audit:** sensitive changes are written to `audit_log` (who, what, when, before → after). On top of that every write request and every sensitive read is recorded (actor, business, path, status, duration, IP; never bodies or secrets), so no step is lost: `AuditTrailFilter` writes it on its own connection even when the request fails or rolls back. Page views come from `POST /api/audit/page-views`. The table is append-only (trigger). The owner reads it at `/api/audit`, the super admin (all businesses) at `/api/platform/audit`; both have `/export` (CSV).

### Money and time

**Money:**
- Stored as integer paise.
- Monthly amounts are `INTEGER`; annual amounts are `BIGINT`.

**Timestamps and dates:**
- Timestamps are `timestamptz` in UTC.
- Calendar dates use the `date` type.
- Each employee has an IANA time zone, and conversion happens only for display.

### Attendance model

**The engine.** `engine/AttendanceEngine.java` contains pure, unit-tested functions. They decide each day's status from punches, the shift, holidays, leave, regularizations, the policy and the current time.

**Work dates.**
- A day is a *work date* in the employee's own time zone.
- A night shift belongs to the date it started on.
- Each punch belongs to the shift window that contains it. A window opens 4 hours before shift start and lasts 24 hours.

**One source for every screen.** `service/AttendanceService.java` loads the data and runs the engine. Home, My attendance, My team, the register and payroll all use it.

**Locking.** When a month locks, its days are frozen into `attendance_days` with `locked = true`. From then on, every screen and payroll reads the frozen rows.

**Go-live date.** Days before the go-live date (Policies → Attendance, default 1 Apr 2026) show as *Not tracked*.

### Leave model

**The ledger.** Every leave balance is the sum of `leave_ledger` rows. Each row is one of: accrual, used, cancelled, encashed, expired, adjustment or comp-off credit. The rules are pure functions in `engine/LeaveRules.java`.

**Accrual.**
- Credited at the start of each month of the leave year: CL 1, EL 1.5.
- Pro-rata in the month someone joins.
- Any months that were missed are backfilled.

**Applying.**
- The same preview runs in the apply form and on the server: no past dates, max stretch (CL 3), overlaps, the joining and exit dates, and locked months.
- Days not covered by the balance become **LOP**. Paid days are used from the start of the range, so any shortfall falls on the last days.
- The attendance engine receives a *paid fraction* for each date.

**Approving.**
- HR approves; Admin can too. Approving posts a `USED` row to the ledger.
- The LOP split is recalculated at approval time, because the balance may have changed since the request was made.
- Nobody can decide their own request.
- When two approvers act at once, the first decision wins. This is enforced with an atomic `UPDATE … WHERE status = 'PENDING'`.

**Regularization.** HR, Finance and Admin all see each request, and the first decision wins.

| Request | When approved |
|---|---|
| Absent | The day becomes paid. |
| Late check-in | The late mark is removed. |
| Missed check-out | A check-out punch is added. |
| Holiday work | A comp-off is credited: 1 day for 8h+, ½ day for 4h+. It expires after 60 days. |

Approval is blocked once the month is locked. After that, Finance adjusts LOP in payroll instead.

**Year end.** Casual leave lapses. Earned leave carries forward up to 30 days.

### Payroll and tax model

**Pure engines.** Payroll and tax maths are pure, unit-tested functions:
- `engine/PayrollEngine.java` works in integer paise.
- `engine/TaxEngine.java` works in rupees, because slabs, rebates and caps are stored per FY in `tax_years` / `tax_sections`.

**Golden test.** JC-074 for September 2026 gets 22/30 paid days and ₹14,926 monthly TDS. It's covered by the unit tests and checked again through the live API in the e2e tests.

**Salary structures.** They're effective-dated and history is only added to. Payroll uses the structure in force on the last day of the month. A new revision can't start inside an already-closed payroll month; differences go into the next month as arrears.

**A payroll run.** Attendance supplies the LOP: absences, unpaid leave and ½ day per 3 late marks. While the run is open, Finance (`pay.view`) can override LOP and add bonus/arrears, other deductions and a note. Each edit is audited.

**TDS.** The annual tax is projected from four parts:
- closed payslips this FY
- earlier months with no payslip, estimated from the structure then in force
- this month
- the current structure × the remaining months

Previous-employer income is added, then the standard deduction and any approved old-regime deductions are subtracted. TDS already paid is taken off, and the rest is spread over the remaining months.

**Closing.** *Approve & close* needs `pay.close`, and attendance must be locked. Closing snapshots every payslip as immutable JSON plus a PDF in `<storage-dir>/payslips/…`. Later changes never touch a closed payslip.

**Exports.**
- Payroll register, PT summary and PF ECR.
- Bank transfer file: decrypts account numbers, so it needs `pay.view`.
- TDS summary: decrypts PANs, so it needs `pay.view` and `tax.view`.
- Every export is audited.

### Tax declarations and proofs

**Choosing a regime.**
- The new regime is the default.
- Employees choose their regime on *Tax declarations* until the date in `tax.policy.regimeChangeUntil`.
- Finance can override it on the Tax page. An overridden regime is locked for the employee.

**Declaring on the old regime.**
- Employees declare amounts per item: HRA, 80C, 80CCD(1B), 80D, 24(b), 80E, 80G and 80TTA.
- Each item needs a proof. Uploads must be PDF, JPG or PNG, checked by file signature rather than extension, and at most 5 MB.
- Items move Draft → Pending → Approved / Rejected (with a reason). Changing an approved amount sends it back to Draft.

**Verifying (Finance).**
- Finance (`proofs.verify`) sees each employee's proofs with a preview of "TDS now vs if everything is approved".
- Nobody can verify their own proofs.

**What counts for TDS.**
- Only approved amounts reduce TDS.
- The employee's regime comparison counts drafts too, so they can see what they'd save.

**Tax page (`tax.view`).**
- One row per employee, plus a drawer with the full computation and slab working.
- Finance enters previous-employer income and TDS (Form 12B) there.
- Export to CSV.

**Storage.** Proof files are stored under `<storage-dir>/proofs/<employee>/`. Only the employee and Finance can open them.

### Dashboards and audit log

**Home (`self`).** Greeting with a live clock, the check-in card, KPIs, the 16-week heatmap, the last seven shifts and upcoming holidays. Cards are added by permission (`service/DashboardService.java#homeCards`):

| Card | Shown to |
|---|---|
| My leave requests | plain employees (no approvals, payroll or team) |
| Your team right now | anyone with active direct reports |
| Approvals | `appr.leave`, or `appr.reg` without `pay.view` |
| Payroll status | `pay.view` (Finance) |
| Payslip ready | India payroll |

Salary is never shown on Home; an e2e test checks that no ₹ amount appears there for any role.

**Overview (Admin, `roles.manage`).** Headcount (India / US / access off), checked in now, pending approvals, proofs to verify, the open payroll month's status and net payout, people by department, the HR and Finance queues and shortcuts.

**Audit log (`roles.manage`).** Every entry in `audit_log`, newest first, with search (person, action, record ID), event-group / actor / period filters, sorting and server-side paging. Clicking an entry shows who, what, when, the IP and a before → after table. Salary, payroll and tax entries hide their amounts from viewers without a Finance permission.

### Scheduled jobs

`service/JobsService.java` contains these jobs:

| Job | What it does |
|---|---|
| Absence marking | Once shift end + buffer has passed, a working day with no check-in and no leave is recorded as Absent (LOP). |
| Monthly lock | Attendance freezes on the 1st of the next month at 10:00 IST (configurable). |
| Status | Employees move from Onboarding to Probation to Active (probation months are configurable). |
| Leave accrual | Credits each month's leave. It runs only once per employee, leave type and month. |
| Comp-off expiry | Expires unused comp-off once its 60 days are up. The oldest credits are used first. |
| Year end | Casual leave lapses and earned leave above the carry-forward cap is removed. Runs once a year. |
| Subscription warnings | Tells each business's owners (bell + email) 7 days and 1 day before the subscription ends, and once it has ended. Each warning is sent once. |

**How they run:**
- **In the API (default):** with `jurisone.jobs-enabled=true` they run every 10 minutes.
- **From cron:** set `jurisone.jobs-enabled=false` and run the `jobs` command from cron (`java -jar … jobs`).

**Safe to repeat.** A Postgres advisory lock stops two API instances from running the jobs at the same time. The `job_runs` table records each month that has been locked, so running a job again doesn't redo work.

### Email

**Without SMTP (dev):** leave `jurisone.mail.host` empty. Emails are written to the `email_outbox` table and printed in the API log, so you can copy sign-in codes and links from there.

**With SMTP:** set the `jurisone.mail.*` settings (`SMTP_USER` / `SMTP_PASS` as environment variables).

### Fonts

The display font is Balkind, which needs a commercial licence (REQUIREMENTS §9.6). Until the licensed file is added, the app falls back to Bodoni Moda.

To add the licensed font:
1. Put the file in `public/fonts/`.
2. Add a `url()` for it to the `@font-face` rule in `globals.css`.

### Table conventions

List pages keep search, filters, sorting and paging in the URL (`?search=&sort=&sortDirection=&page=`). The API runs the query, so lists stay fast as the company grows. The shared pieces are `common/ListParams.java` here and the table components in the frontend repo.
