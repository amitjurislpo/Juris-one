# Prompt for Claude Code

Put these three files in an empty project folder, then paste the prompt below into Claude Code:

- `REQUIREMENTS.md`
- `Juris-People-Design-8.html`
- the company logo PNGs

---

```
You are building "Juris One", a production HRMS + payroll portal for Juris Consultants.

Read these first, fully, before writing any code:
1. REQUIREMENTS.md — the source of truth for every rule (roles, permissions, attendance,
   leave, payroll, tax, data model). If the prototype and this file disagree, REQUIREMENTS.md wins.
2. Juris-People-Design-8.html — a working single-file prototype (Vue). Open it in a browser and
   log in with the demo IDs in REQUIREMENTS.md §8 (any password). Treat it as the design source of
   truth: layout, colours, fonts, components, copy, empty states and animations. Port its CSS tokens
   (light + dark theme, violet accent #6E56CF, Balkind display font, Geist / Geist Mono) — do not
   restyle it.

Stack (required): Next.js (App Router) + TypeScript, PostgreSQL 16, Prisma migrations,
argon2id password hashing, HTTP-only cookie sessions, zod validation, docker-compose for Postgres,
Playwright + Vitest for tests. Store timestamps as timestamptz (UTC) with an IANA time zone per
employee; store money as integer paise.

Non-negotiables:
- Permission-based access (REQUIREMENTS.md §3). Check permissions on the SERVER for every
  page, server action and API route — never rely on hiding UI.
- HR must never receive salary/tax data from the API. Admin has no attendance/leave/payslips.
- Disabled or exited employees cannot log in, and their sessions are revoked immediately.
- PAN / Aadhaar / UAN / phone / address encrypted at rest and masked in the UI.
- Closed payroll is an immutable snapshot; payslips are visible only after close.
- Payroll and tax maths are pure, unit-tested functions; tax slabs and caps live in DB tables per FY.
- Every table has search, filters and sortable columns.
- Audit-log every sensitive change.

Work in phases. At the end of each phase: run migrations, seed, lint, type-check and tests; give a
short summary; then wait for my "continue".
  Phase 0 — Project setup, docker-compose, Prisma schema for all tables in §6, seed data (§8),
            app shell (sidebar, header, theme, ⌘K palette), login/logout/invite/reset.
  Phase 1 — Roles & permissions page, departments, employees (3-step add/edit, profile drawer,
            access toggle, mark exited), directory, My team.
  Phase 2 — Attendance: check-in/out, shifts, day status job, late marks, My attendance,
            holidays calendar (India/US/All), attendance register with time-zone selector + export.
  Phase 3 — Leave (ledger, accrual, apply with range picker, cancel), regularization
            (to HR + Finance + Admin, first decision wins), Approvals page, notifications + email.
  Phase 4 — Salary structures (effective-dated), payroll run (edit, close, snapshot),
            payslip page + PDF with logo, payroll exports.
  Phase 5 — Tax engine (new/old regime, previous employer, monthly TDS), investment declarations
            with proof upload, Finance proof verification, Tax & TDS page.
  Phase 6 — Home/Overview dashboards, audit log viewer, responsive + accessibility pass,
            Playwright end-to-end tests for every demo role, README with setup/deploy steps.

If anything in the requirements is ambiguous or conflicts, ask me before guessing.
Items in REQUIREMENTS.md §9 are open — use the stated defaults and keep them configurable.
```

---

## Tips

- **Keep the context focused:** give Claude Code one phase at a time.
- **Check each phase yourself:** after every phase, log in with the demo IDs and test it.
- **Changing the requirements:** edit REQUIREMENTS.md first, then tell Claude "requirements updated, re-read §X".
