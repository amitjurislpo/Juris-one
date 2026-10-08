# JurisOne SaaS — multi-business plan

Status: **approved 2026-10-08** (with the changes in §0.1). Branch `feature/saas-multi-business` (from `feature/spring-boot-migration`).
Builds on the Next.js UI + Spring Boot API (`backend/`). Everything below is new scope; existing rules in `REQUIREMENTS.md` still apply inside each business unless this plan changes them.

## 0. Decisions taken (2026-10-08)

| # | Decision |
|---|---|
| S-D1 | **JWT in HTTP-only cookies.** Access token **5 min** + refresh token (rotating, stored hashed, revocable). |
| S-D2 | **Start fresh.** The demo company and its data are removed; the seed creates only the super admin (and sample plans). |
| S-D3 | **Manual subscriptions.** The super admin creates plans and assigns / renews them per business. No payment gateway now. |
| S-D4 | **Sign in with email only.** Employee IDs stay as display codes inside a business. |

### 0.1 Changes agreed on review (2026-10-08)

1. **The sub admin is the business's admin.** There is no separate Admin role inside a business; the sub admin (owner) can assign every other role (HR, Finance, Employee, custom roles) but not the owner role itself.
2. **Expiry warnings** also appear as notifications on the sub admin's dashboard (plus emails), 7 days and 1 day before the end date and on the day it ends.
3. **After expiry** the sub admin and the employees can still sign in, but every other action is blocked (page and API): they see the "subscription ended" screen.
4. **Employee export (Excel `.xlsx`)** — the sub admin can export employee details at any time, before and after expiry. After expiry it is the only action left.
5. **Juris Track can be complimentary or paid.** The super admin can grant it to any business on its own (complimentary, or paid with a price and dates), independent of the plan.
6. **Policy acknowledgements are a legal record.** Each acknowledgement stores an immutable snapshot (policy version, title, content hash, file hash, the employee's name / email / code, time, IP, user agent). Rows can't be updated or deleted (enforced by the database), and HR / the owner can export the evidence.
7. **Access token: 5 minutes.** It is renewed silently while the user is active (each renewal also re-checks the user); an idle user's token simply expires after 5 minutes.
8. **Passwords:** the super admin's password stays as it is. Sub admins created for testing / the demo seed sign in with their email and the password `admin@test.com`; the super admin can set an initial password when creating a sub admin (or send an invite).
9. Defaults in §7 accepted: required documents (ID proof, address proof, PAN, Aadhaar, last 3 salary slips), per-business employee ID prefix (e.g. `AB-001`), blocked from the day after `ends_on` with no grace period, policies only per business.

## 1. What changes for users

### 1.1 Roles

| Level | Who | Can |
|---|---|---|
| Platform | **Super admin** (`users.is_super_admin`, no business) | businesses (create / edit / suspend), sub admins, subscription plans, assign / renew subscriptions, platform audit log. Cannot see any business's employees, payroll or tax. |
| Business | **Sub admin** = the business's admin (built-in role `owner`, one or more per business) | everything Admin has today **plus** the business profile, the subscription page (read-only) and the employee Excel export. Assigns any role except owner. Cannot be disabled by anyone below them. |
| Business | HR Manager / Executive, Finance Manager / Executive, Employee, custom roles | as in REQUIREMENTS §3, but **per business**: roles, permissions and custom roles are scoped to the business. HR (and the owner) add employees. |

### 1.2 Subscriptions

- **Plan:** name, price (₹/period), period in months, max active employees, modules (`JURIS_TRACK` for now), active flag.
- **Subscription:** business, plan, start date, end date, status (active / expired / cancelled), notes. History is kept; renewing adds a new row.
- **Access rule:** a business can be used only while a subscription covers today (IST). After that, users can still sign in, but every page shows "Your subscription has ended — contact your administrator" and every business API answers **402 Payment Required** (`SUBSCRIPTION_INACTIVE`) — except sign-in / sign-out, `GET /api/me`, the shell, the subscription page and the owner's employee export. The super admin is never blocked.
- **Warnings:** 7 days and 1 day before the end date and on expiry, owners get an email and a dashboard notification.
- **Employee export:** the owner can download all employee details as Excel (`.xlsx`) at any time (identity numbers masked unless the owner explicitly includes them, which is audited).
- **Max employees:** adding / enabling an employee beyond the plan limit is refused with a clear message.

### 1.3 Juris Track

- Plans can include the module `JURIS_TRACK`, **or** the super admin grants it to a business directly: complimentary (free) or paid (price, start and end dates). A grant is independent of the plan.
- **Included:** the sidebar shows *Juris Track* and `/juris-track` renders a "Coming soon" page.
- **Not included:** no menu item, and `/juris-track` (page) and `/api/juris-track/**` (API) answer **404**, so it can't be reached by URL either.

### 1.4 Policies and acknowledgement

- The owner and HR (new permission `policies.manage`) publish **policies**: title, body (rich text) and an optional PDF, a version, and "requires acknowledgement" (on by default).
- Publishing notifies every active employee of the business.
- **Login gate:** after sign-in, if a published policy needs acknowledgement and the user hasn't acknowledged that version, the user sees **only** the acknowledgement screen (read the policy → "I have read and accept"). All other business APIs answer **428 Precondition Required** (`POLICY_ACK_REQUIRED`) until done. Acknowledgements store time, IP and policy version.
- A new version of a policy needs a fresh acknowledgement.
- **Legal record:** each acknowledgement is stored with an immutable snapshot — policy id and version, title, SHA-256 of the body and of the attached file, the employee's name, email and code, time (UTC), IP and user agent. A database trigger rejects any UPDATE or DELETE on the acknowledgements table; published policy versions are never edited (a change creates a new version). HR / the owner can export the evidence (CSV / PDF).
- **My policies:** every employee can read all current policies and see when they acknowledged each one. HR / Admin see who has and hasn't acknowledged (with reminders).

### 1.5 Notification box

- A dedicated **Notifications** card on Home (and a full `/notifications` page) lists unread notifications first. Items stay there until the user opens / marks them read; items that need acknowledgement (policies) stay until acknowledged.
- Mark one as read, mark all as read; the bell in the header keeps its count.

### 1.6 Onboarding by link

1. HR (`people.add`) starts onboarding: candidate name, **personal email**, planned role / department / joining date.
2. The candidate gets an email with a one-time link (`/onboard/{token}`, valid 7 days, resendable).
3. The candidate fills a guided form: personal details, identity (PAN, Aadhaar, UAN — India), address, bank account, previous employer, emergency contact, and uploads documents (ID proof, address proof, PAN card, education certificates, last 3 salary slips, relieving letter — HR can mark which are required). Progress is saved; the candidate submits when done.
4. HR reviews: verifies each document, can send it back with comments ("please re-upload").
5. When everything is verified, HR clicks **Add employee**: the employee record is created from the onboarding data (work email, employee ID, role, department, shift, manager, joining date). PII stays encrypted; uploaded documents move to the employee's record.
6. Portal access starts **disabled**. HR enables it → the employee gets the "set your password" invite on their **work** email → they sign in (and acknowledge policies first).

Statuses: `INVITED → IN_PROGRESS → SUBMITTED → CHANGES_REQUESTED → VERIFIED → ADDED` (or `CANCELLED`). Every step is audited.

## 2. Security (Spring Security + JWT)

- **Sign in** `POST /api/auth/session` (email + password) → sets two HTTP-only, Secure (in prod), SameSite=Lax cookies:
  - `jo_at` — JWT access token, HS256 signed with `JWT_SECRET` (env, ≥ 32 bytes, no default), **5 min**, renewed silently while the user is active. Claims: `sub` (user id), `bid` (business id or none for super admin), `role` scope (`PLATFORM` / `BUSINESS`), `tv` (token version), `iat`, `exp`, `jti`.
  - `jo_rt` — opaque refresh token (path `/api/auth`), 12 h, or 30 days with "remember me"; stored as SHA-256 in `refresh_tokens`, **rotated** on each use; reuse of an old one revokes the whole family.
- `POST /api/auth/session/refresh` rotates; `DELETE /api/auth/session` revokes the refresh token and clears both cookies.
- **Immediate revocation:** each request validates the JWT signature and expiry, then does one indexed lookup of the user (`status`, `portal_access`, `token_version`, business `status`). Disabling access, marking exited, changing role or password, or suspending a business bumps `token_version` → the next request is rejected (401) and refresh fails.
- **Authorization:** a `JwtAuthenticationFilter` builds the principal with authorities = permission keys (`PERM_pay.view`, …) + `ROLE_SUPER_ADMIN` / `ROLE_OWNER`; controllers use `@PreAuthorize` plus the existing row-level rules in services. Tenant isolation is enforced in the data layer (§3.2), not only in controllers.
- **CSRF:** cookies are SameSite=Lax and every write still needs the Origin check; CORS allows only `APP_URL`.
- **Errors:** one `@RestControllerAdvice` returning RFC 7807 `application/problem+json` with our fields:
  `{ "type", "title", "status", "detail", "code", "errors": [{ "field", "message" }], "traceId" }` —
  400 validation, 401 unauthenticated, 402 subscription inactive, 403 forbidden, 404 not found / other tenant, 409 conflict, 422 business rule, 428 policy acknowledgement required, 429 throttled, 500 (no stack traces). The Next.js actions keep showing `detail` as today.
- Login protection (rate limits, lockout, login history) and argon2id passwords stay as they are.

## 3. Data model (Flyway V2+)

### 3.1 New tables

| Table | Key columns |
|---|---|
| `businesses` | id, name, legal_name, code (unique slug), gstin, address, contact email/phone, time zone, status (ACTIVE / SUSPENDED), created_by, created_at |
| `subscription_plans` | id, name, price_paise, period_months, max_employees, modules (text[]), active, created_at |
| `business_subscriptions` | id, business_id, plan_id, starts_on, ends_on, status, notes, created_by, created_at |
| `policies` | id, business_id, title, body, file_path, version, requires_ack, status (DRAFT / PUBLISHED / ARCHIVED), published_at, created_by |
| `policy_versions` | policy_id, version, title, body, body_sha256, file_path, file_sha256, published_at, published_by — immutable |
| `policy_acknowledgements` | id, policy_id, version, employee_id, employee name / email / code snapshot, body_sha256, file_sha256, acknowledged_at, ip, user_agent — append-only (trigger blocks UPDATE / DELETE), unique (policy_id, version, employee_id) |
| `business_modules` | id, business_id, module (`JURIS_TRACK`), source (PLAN / COMPLIMENTARY / PAID), price_paise, starts_on, ends_on, granted_by, notes |
| `onboardings` | id, business_id, candidate_name, personal_email, planned role / department / manager / joining date, token_hash, token_expires_at, status, form data (encrypted JSON for PII), submitted_at, verified_by, employee_id (after add) |
| `onboarding_documents` | id, onboarding_id, kind, file_path, file_name, status (PENDING / VERIFIED / REJECTED), comment, verified_by, verified_at |
| `refresh_tokens` | id (sha-256), user_id, family_id, expires_at, revoked_at, replaced_by, created_at, ip, user_agent |

### 3.2 Tenancy

- Shared database, **`business_id` on every business-owned table**: employees, roles (+ role permissions / assignable roles via role), departments, holidays, settings (policy settings per business), leave types, payroll runs (unique month **per business**), audit log (null = platform event), notifications (via employee).
- Uniqueness becomes per business: department name, holiday (date, region), role key, employee code. **Work email stays globally unique** (email-only sign-in).
- Global tables stay global: tax years / sections (India FY rules), shifts (templates), subscription plans.
- Every query takes the business id from the authenticated principal (`TenantContext`), never from the request. An API test per area proves that a user of business A gets 404 for business B's ids.
- `users.is_super_admin` users have no business; `employees` stay one-per-person inside a business.

### 3.3 Start fresh

- V2 adds the tables and columns. A dev reset command (`npm run db:reset`, dev only) drops and recreates the schema; the seed creates the super admin (from `SUPER_ADMIN_EMAIL` / `SUPER_ADMIN_PASSWORD`; an existing password is never changed) and two sample plans (Standard; Standard + Juris Track) that the super admin can edit.
- The current demo data and its seed are removed. An optional `SEED_DEMO=true` creates one sample business with a few people, for development and the e2e tests only.

## 4. API (REST, per §14 conventions of MIGRATION_PLAN)

| Area | Endpoints |
|---|---|
| Platform (super admin) | `GET/POST /api/platform/businesses` · `GET/PUT /api/platform/businesses/{id}` · `PUT /api/platform/businesses/{id}/status` · `GET/POST /api/platform/businesses/{id}/owners` (create sub admin → invite email) · `DELETE /api/platform/businesses/{id}/owners/{userId}` · `GET/POST /api/platform/plans` · `PUT /api/platform/plans/{id}` · `GET/POST /api/platform/businesses/{id}/subscriptions` · `PUT /api/platform/subscriptions/{id}` |
| Business | `GET/PUT /api/business` (profile, owner) · `GET /api/business/subscription` · `GET /api/business/employees/export` (`.xlsx`, owner) |
| Modules (super admin) | `GET/POST /api/platform/businesses/{id}/modules` (complimentary / paid grant) · `PUT /api/platform/modules/{id}` · `DELETE /api/platform/modules/{id}` |
| Auth | `POST /api/auth/session` · `POST /api/auth/session/refresh` · `DELETE /api/auth/session` · password reset / invite as today |
| Policies | `GET/POST /api/policies` · `GET/PUT/DELETE /api/policies/{id}` · `PUT /api/policies/{id}/status` (publish / archive) · `GET /api/policies/pending` (mine, needing ack) · `POST /api/policies/{id}/acknowledgements` · `GET /api/policies/{id}/acknowledgements` (HR) |
| Notifications | `GET /api/notifications?unread=` · `PATCH /api/notifications/{id}` (read) · `PATCH /api/notifications` (all read) |
| Onboarding (HR) | `GET/POST /api/onboardings` · `GET/PUT /api/onboardings/{id}` · `POST /api/onboardings/{id}/invitations` (resend) · `PUT /api/onboardings/{id}/documents/{docId}/status` · `PUT /api/onboardings/{id}/status` (request changes / verified / cancelled) · `POST /api/onboardings/{id}/employee` (add employee) |
| Onboarding (candidate, token) | `GET /api/onboarding/{token}` · `PUT /api/onboarding/{token}` (save form) · `POST /api/onboarding/{token}/documents` (multipart) · `DELETE /api/onboarding/{token}/documents/{docId}` · `POST /api/onboarding/{token}/submission` |
| Juris Track | `GET /api/juris-track` (404 without the module) |

## 5. Frontend (same design system as the prototype)

- **Super admin console** (`/platform/...`): businesses list (search, filters, status, plan, expiry), business detail (profile, owners, subscription history), plans list / editor. The super admin lands here, not on Home.
- **Business:** business profile and subscription page (owner); Policies (manage + acknowledgement status); My policies; Onboarding list + review screen; Notifications page and the Home notification card; Juris Track "Coming soon"; subscription-ended page; the acknowledgement gate.
- **Public:** `/onboard/{token}` multi-step candidate form with document uploads.
- Sidebar items come from permissions **and** plan modules.

## 6. Phases (each: migration, tests, lint, typecheck, e2e, commit; wait for "continue")

| Phase | Scope |
|---|---|
| S1 | JWT security (access + refresh cookies, token version, filter chain, problem+json errors) replacing the opaque session; existing flows keep working. |
| S2 | Tenancy: `businesses`, `business_id` everywhere, TenantContext, per-business uniqueness, isolation tests; start-fresh reset + new seed. |
| S3 | Super admin console: businesses, sub admins (owner role, initial password or invite), plans, subscriptions, access rule (402, sign-in still allowed), expiry emails + dashboard notifications, max employees, owner employee export (.xlsx). |
| S4 | Juris Track: plan module or complimentary / paid grant; menu + page + API gate; Coming soon page. |
| S5 | Policies (immutable versions) + acknowledgement gate (428) + legal acknowledgement record + My policies + HR report and evidence export. |
| S6 | Notification box (Home card, page, per-item read). |
| S7 | Onboarding by link: candidate form, documents, HR review, add employee, enable access. |
| S8 | Rewrite the Playwright suite for the multi-business model (super admin → business → roles → onboarding → policies → payroll), README and parity notes. |

## 7. Defaults confirmed

1. Required onboarding documents: ID proof, address proof, PAN, Aadhaar, last 3 salary slips; others optional; HR can change the list per business.
2. Employee IDs: each business sets its own prefix (e.g. `AB-001`).
3. Expiry: blocked from the day after `ends_on`, no grace period.
4. Policies: published only by each business (no platform-wide policies).
