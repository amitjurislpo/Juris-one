# JurisOne

HRMS and payroll portal for Juris Consultants. It covers attendance, leave, people records, payroll, TDS and payslips.

- **Rules:** [references/REQUIREMENTS.md](references/REQUIREMENTS.md) is the source of truth.
- **Design:** [references/Juris-People-Design-8.html](references/Juris-People-Design-8.html) is the clickable prototype. Its CSS is ported verbatim into `src/app/globals.css`.

## Stack

- Next.js 16 (App Router) with TypeScript
- PostgreSQL 16 in docker-compose
- Prisma 7, using the `pg` driver adapter
- argon2id password hashing
- HTTP-only cookie sessions
- zod validation
- Vitest and Playwright for tests

## Quick start (development)

Prerequisites: Node 20.9+ (tested on 24) and Docker Desktop.

```bash
npm install                 # also runs `prisma generate`
cp .env.example .env        # then set PII_ENCRYPTION_KEY (command is in the file)
npm run db:up               # Postgres 16 on localhost:5436
npm run db:deploy           # apply migrations
npm run db:seed             # roles, policies, tax tables, super admin and demo data
npm run dev                 # http://localhost:3005
```

### Sign in

| Who | Login | Password |
|---|---|---|
| Super admin | `admin@jurislpo.com` | `admin@jurislpo` |
| Demo employees (REQUIREMENTS §8) | `JC-002`, `JC-004`, `JC-012`, `JC-015`, `JC-018`, `JC-035`, `JC-049`, `JC-058`, `JC-072`, `JC-074`, `JC-090`, `JC-091` | value of `SEED_DEMO_PASSWORD` (default `Juris@2026`) |
| Blocked on purpose | `JC-081` (access disabled), `JC-030` (past employee) | same |

You can sign in with either the employee ID or the official email.

**Change the super admin password after first login in any shared environment.**

## Scripts

| Script | What it does |
|---|---|
| `npm run dev` / `build` / `start` | Next.js on port 3005 |
| `npm run lint` / `typecheck` | ESLint / route typegen + `tsc --noEmit` |
| `npm test` | Vitest unit tests (`tests/unit`) |
| `npm run test:e2e` | Playwright end-to-end tests (`tests/e2e`). Needs the seeded DB; reuses a running dev server. |
| `npm run db:migrate` | Create and apply a new migration in dev |
| `npm run db:deploy` | Apply pending migrations (CI / prod) |
| `npm run db:seed` | Idempotent seed. `SEED_DEMO=false` seeds only the core data. |
| `npm run db:reset` | Drop, re-migrate and re-seed (dev only) |
| `npm run jobs` | Run the scheduled jobs once (absence marking, monthly lock, status changes) |
| `scripts/backup.sh` / `scripts/restore.sh` | Postgres backup (keeps 14 days) / restore — see *Deploying* |

## Deploying

JurisOne is a single Next.js server plus Postgres 16. Any Linux VPS or VM works (REQUIREMENTS §9.4 leaves hosting open).

1. **Postgres 16.** A managed instance, or `docker compose up -d` with a strong `POSTGRES_PASSWORD` and the port not exposed publicly.
2. **Environment.** Copy `.env.example` to `.env` and set:
   - `DATABASE_URL`, `APP_URL` (the public https URL)
   - `PII_ENCRYPTION_KEY` — generate once, store it in your password manager. Losing it makes PII unreadable.
   - `SUPER_ADMIN_PASSWORD` — then change it after the first sign-in.
   - `SEED_DEMO=false` and empty `SEED_DEMO_PASSWORD` for a real company (no demo people).
   - `SMTP_*` so invites, resets and decisions are emailed.
3. **Build and migrate.**
   ```bash
   npm ci
   npm run db:deploy       # apply migrations
   npm run db:seed         # roles, policies, tax tables, super admin (idempotent)
   npm run build
   npm run start           # port 3005; keep it running with systemd or pm2
   ```
4. **HTTPS.** Put Caddy or nginx in front, terminate TLS there and proxy to `localhost:3005`. Session cookies are `Secure` in production, so the app must be served over https.
5. **Jobs.** Leave `JOBS_ENABLED=true` for a single instance. With several instances either keep it on (an advisory lock lets only one run) or set it to `false` everywhere and run `npm run jobs` from cron every 10 minutes.
6. **Files.** `UPLOAD_DIR` (default `./storage`) holds payslip PDFs and investment proofs. Put it on persistent disk and include it in backups.

### Backups (daily)

```bash
# crontab -e   (02:15 every night; keeps 14 days in storage/backups)
15 2 * * *  cd /srv/jurisone && ./scripts/backup.sh >> storage/backups/backup.log 2>&1
```

- Needs `pg_dump` 16+ on the server, or set `PG_DOCKER=jurisone-db` to run it inside the compose container.
- Copy `storage/backups/` and `storage/` to off-site storage (S3, Azure Blob, another host).
- Restore with `./scripts/restore.sh <file.dump>` (stop the app first; it asks for confirmation).
- Test a restore into a scratch database every few months.

### Upgrading and rolling back

- Deploy: pull, `npm ci`, `npm run db:deploy`, `npm run build`, restart.
- **Take a backup before `db:deploy`.**
- Every migration in `prisma/migrations/` has a `down.sql`. To roll one back: run its `down.sql` with `psql`, delete its row from `_prisma_migrations`, then deploy the previous release.

## How it is put together

```
prisma/schema.prisma        every table in REQUIREMENTS §6, plus auth support tables
prisma/migrations/*/        migration.sql, plus a down.sql to reverse it
prisma/seed.ts              core and demo seed
src/proxy.ts                fast redirect for signed-out visitors (not the security boundary)
src/lib/auth.ts             getCurrentUser / requirePage / assertPerm: the server-side permission checks
src/lib/session.ts          session create, revoke, cookie
src/lib/crypto.ts           AES-256-GCM encryption for PII
src/lib/nav.ts              sidebar and route permission table
src/lib/permissions.ts      permission catalogue
src/app/(auth)/             login, forgot, invite/[token], reset/[token], plus server actions
src/app/(app)/              signed-in pages; layout.tsx renders the shell
src/components/shell/       sidebar, header, notifications, ⌘K palette, theme, toasts
```

### Security model

**Permissions are checked on the server.**
- Every page calls `requirePage(<nav id>)`.
- Every server action or route handler calls `assertPerm(...)`.
- Hiding UI is cosmetic only.

**Sessions are re-validated on every request.**
- A session that is revoked or expired is rejected.
- So is the session of an employee whose access is disabled or who has exited.
- Disabling access or marking someone exited revokes their sessions immediately.

**Passwords and sessions:**
- Passwords are hashed with argon2id.
- Session tokens are 256-bit random values. Only their SHA-256 is stored.

**Login protection:**
- Failed attempts are limited per IP.
- An account locks after 5 failures (configurable).
- Every login attempt is stored in `login_history`.

**PII:**
- PAN, Aadhaar, UAN, phone, address and bank account are encrypted at rest with `PII_ENCRYPTION_KEY`.
- Only the last 4 characters are stored in clear, for masked display.
- **Back the key up. Losing it makes that data unrecoverable.**

**CSRF:**
- Server actions use Next's built-in Origin check.
- Route handlers use `src/lib/api.ts#handler`.

**Audit:** sensitive changes are written to `audit_log` (who, what, when, before → after).

### Money and time

**Money:**
- Stored as integer paise.
- Monthly amounts are `Int`; annual amounts are `BigInt`.

**Timestamps and dates:**
- Timestamps are `timestamptz` in UTC.
- Calendar dates use the `date` type.
- Each employee has an IANA time zone, and conversion happens only for display.

### Attendance model

**The engine.** `src/lib/attendance/engine.ts` contains pure, unit-tested functions. They decide each day's status from punches, the shift, holidays, leave, regularizations, the policy and the current time.

**Work dates.**
- A day is a *work date* in the employee's own time zone.
- A night shift belongs to the date it started on.
- Each punch belongs to the shift window that contains it. A window opens 4 hours before shift start and lasts 24 hours.

**One source for every screen.** `src/lib/attendance/service.ts` loads the data and runs the engine. Home, My attendance, My team, the register and payroll all use it.

**Locking.** When a month locks, its days are frozen into `attendance_days` with `locked = true`. From then on, every screen and payroll reads the frozen rows.

**Go-live date.** Days before the go-live date (Policies → Attendance, default 1 Apr 2026) show as *Not tracked*.

### Leave model

**The ledger.** Every leave balance is the sum of `leave_ledger` rows. Each row is one of: accrual, used, cancelled, encashed, expired, adjustment or comp-off credit. The rules are pure functions in `src/lib/leave/rules.ts`.

**Accrual.**
- Credited at the start of each month of the leave year: CL 1, EL 1.5.
- Pro-rata in the month someone joins.
- Any months that were missed are backfilled.

**Applying.**
- `previewRequest` runs the same checks in the apply form and on the server: no past dates, max stretch (CL 3), overlaps, the joining and exit dates, and locked months.
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
- `src/lib/payroll/engine.ts` works in integer paise.
- `src/lib/tax/engine.ts` works in rupees, because slabs, rebates and caps are stored per FY in `tax_years` / `tax_sections`.

**Golden test.** JC-074 for September 2026 gets 22/30 paid days and ₹14,926 monthly TDS. It's covered by the unit tests and checked again through the live service in the e2e tests.

**Salary structures.** They're effective-dated and history is only added to. Payroll uses the structure in force on the last day of the month. A new revision can't start inside an already-closed payroll month; differences go into the next month as arrears.

**A payroll run.** Attendance supplies the LOP: absences, unpaid leave and ½ day per 3 late marks. While the run is open, Finance (`pay.view`) can override LOP and add bonus/arrears, other deductions and a note. Each edit is audited.

**TDS.** The annual tax is projected from four parts:
- closed payslips this FY
- earlier months with no payslip, estimated from the structure then in force
- this month
- the current structure × the remaining months

Previous-employer income is added, then the standard deduction and any approved old-regime deductions are subtracted. TDS already paid is taken off, and the rest is spread over the remaining months.

**Closing.** *Approve & close* needs `pay.close`, and attendance must be locked. Closing snapshots every payslip as immutable JSON plus a PDF in `storage/payslips/<month>/`. Later changes never touch a closed payslip.

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

**Storage.** Proof files are stored under `storage/proofs/<employee>/`. Only the employee and Finance can open them.

### Dashboards and audit log

**Home (`self`).** Greeting with a live clock, the check-in card, KPIs, the 16-week heatmap, the last seven shifts and upcoming holidays. Cards are added by permission (`src/lib/dashboard.ts#homeCards`):

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

`src/lib/jobs.ts` contains these jobs:

| Job | What it does |
|---|---|
| Absence marking | Once shift end + buffer has passed, a working day with no check-in and no leave is recorded as Absent (LOP). |
| Monthly lock | Attendance freezes on the 1st of the next month at 10:00 IST (configurable). |
| Status | Employees move from Onboarding to Probation to Active (probation months are configurable). |
| Leave accrual | Credits each month's leave. It runs only once per employee, leave type and month. |
| Comp-off expiry | Expires unused comp-off once its 60 days are up. The oldest credits are used first. |
| Year end | Casual leave lapses and earned leave above the carry-forward cap is removed. Runs once a year. |

**How they run:**
- **In the app (default):** with `JOBS_ENABLED=true` they run every 10 minutes, started by `src/instrumentation.ts`.
- **From cron:** set `JOBS_ENABLED=false` and run `npm run jobs`.

**Safe to repeat.** A Postgres advisory lock stops two app instances from running the jobs at the same time. The `job_runs` table records each month that has been locked, so running a job again doesn't redo work.

### Email

**Without SMTP (dev):** leave `SMTP_HOST` empty. Emails are written to the `email_outbox` table and printed in the dev-server log, so you can copy invite and reset links from there.

**With SMTP:** set the `SMTP_*` variables.

### Fonts

The display font is Balkind, which needs a commercial licence (REQUIREMENTS §9.6). Until the licensed file is added, the app falls back to Bodoni Moda.

To add the licensed font:
1. Put the file in `public/fonts/`.
2. Add a `url()` for it to the `@font-face` rule in `globals.css`.

## Build status

- **Phase 0 (done):** setup, schema, seed, app shell, and login / logout / invite / reset.
- **Phase 1 (done):** roles & permissions, policies matrix, departments, people (3-step add/edit, profile drawer, access toggle, mark exited, CSV export), directory and My team.
- **Phase 2 (done):** check-in/out, attendance engine (shifts, night shifts, late marks, half days, absences), My attendance with regularization requests, holiday calendar, attendance register (time zones, daily log, monthly grid, CSV export), scheduled jobs, editable attendance policy.
- **Phase 3 (done):** leave ledger and accrual, apply leave (range picker, holidays skipped, LOP when short), cancel, regularization decisions, Approvals page (first decision wins), in-app notifications and decision emails, comp-off expiry and year-end jobs.
- **Phase 4 (done):** salary structures (effective-dated, Split helper, history), payroll run (attendance LOP, Finance edits, approve & close with immutable snapshots), payslips page + PDF with logo, payroll exports (bank, PF ECR, PT, TDS, register). Demo payroll closed for Apr–Aug 2026; September is open for review.
- **Phase 5 (done):** tax declarations (regime choice with live comparison, old-regime items, proof upload), Finance proof verification with TDS preview, Tax & TDS page (computation drawer, slab working, regime override, previous employer), tax export.
- **Phase 6 (done):** Home cards by permission, company Overview, audit log viewer (filters, before → after), responsive + keyboard pass (skip link, focus rings, phone drawer), end-to-end tests for every demo role, deploy/backup docs.

### Table conventions

List pages keep search, filters, sorting and paging in the URL (`?q=&sort=&dir=&page=`). The server runs the query, so lists stay fast as the company grows. The shared pieces are `src/lib/list.ts` and `src/components/table/`.
