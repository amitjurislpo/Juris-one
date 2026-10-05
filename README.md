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
- **Phases 1 to 6:** in progress. Pages that are not built yet show "Arrives in phase N".
