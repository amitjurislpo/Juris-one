# JurisOne — Spring Boot backend migration plan

Status: **DRAFT — awaiting approval.** No application code has been written. Branch: `feature/spring-boot-migration` (from `feature/phase-6-dashboards` @ `1e5c2ab`).

Sources read: `CLAUDE.md`, `AGENTS.md`, `references/REQUIREMENTS.md`, `references/CLAUDE_CODE_PROMPT.md`, the full `src/`, `prisma/`, `scripts/` and `tests/` trees.

## 0. Summary

| Item | Count | Where |
|---|---|---|
| Server actions (all mutations except files/exports) | 43 in 16 files | §2 |
| Route handlers (CSV/PDF/file/upload) | 7 | §1a |
| Page data reads with no API today → new GET endpoints | 34 | §3 |
| Prisma models → JPA entities | 30 (8 composite keys) | §6 |
| Postgres enums | 17 (native types) | §6.1 |
| Background jobs | 6, one runner, advisory lock | §1c |
| Raw SQL statements | 5 | §8.12 |
| Vitest unit tests to port to JUnit 5 | 8 files, 70 cases (`vitest run` count) | §5 |
| Playwright e2e tests that must pass unchanged | 58 in 8 files | §11 |

### 0.1 Corrections to the task brief

The brief describes the stack as "Tailwind/shadcn + Auth.js" and the app as a CRM. The code says otherwise. This matters for the plan:

- **No Auth.js.** Authentication is custom: an opaque random session token in the `jo_session` cookie, stored as sha256 in the `sessions` table and re-validated against the DB on every request (§1b). Invite/reset tokens, lockout and login history are custom too.
- **No Tailwind/shadcn.** Styling is the prototype CSS ported verbatim into `globals.css`. Nothing about styling changes in this migration anyway.
- It is an HRMS + payroll portal (attendance, leave, payroll, TDS), not a CRM.

### 0.2 What the app looks like today (relevant for the cut-over)

- Pages are **React Server Components that query Prisma directly** and call `src/lib/*` services. There is no read API at all; every read needs a new endpoint (§3).
- Mutations are **Next server actions** (POST to the page URL, always HTTP 200 with `ActionResult`), plus 7 route handlers under `/api/*`.
- Playwright calls `/api/*` on **port 3005** with the browser session (e.g. `page.request.get("/api/payroll/export?…")`) and expects **403 (not 401)** when signed out. Tests also read and write the DB directly with `pg`.

---

---

## 1. Inventory — routes, auth, jobs, files, email, settings

Source: branch `feature/spring-boot-migration` @ 1e5c2ab (Next.js 16.3.8, Prisma 7, `@node-rs/argon2` ^2.2.1, `pdf-lib` ^1.17.1, `nodemailer` ^10). Read-only audit; no project files changed.

Only 7 route handlers exist. **All other mutations are Next server actions** (`src/app/**/actions.ts`, wrapped by `src/lib/action.ts` `run()` → `{ok:true,msg?,data?} | {ok:false,error}`); they are out of scope here but must become REST endpoints too (auth actions are covered in 1b).

---

### 1a. Route handlers

### Common wrapper — `src/lib/api.ts` `handler(fn)`
| Concern | Current behaviour | Spring equivalent |
|---|---|---|
| CSRF | `assertSameOrigin`: for any method other than GET/HEAD/OPTIONS, `Origin` header must exist and `new URL(origin).host === (X-Forwarded-Host ?? Host)`; else ForbiddenError "Cross-site request blocked." → 403 | `OncePerRequestFilter` `SameOriginFilter` (or Spring Security `CsrfFilter` disabled + custom `RequestMatcher`); keep exact host comparison incl. `X-Forwarded-Host` (`server.forward-headers-strategy=framework`) |
| Errors | `ForbiddenError` → 403 `{"error": msg}`; `ZodError` → 400 `{"error": first issue msg ?? "Invalid input"}`; other → 500 `{"error":"Something went wrong."}` (logged) | `@RestControllerAdvice ApiExceptionHandler`: `ForbiddenException`→403, `MethodArgumentNotValidException`/`ConstraintViolationException`→400, fallback 500; same JSON shape `{error}` |
| Auth | Each handler calls `assertPerm(...)` (see 1b). Unauthenticated → **403** `{"error":"Your session has ended. Please sign in again."}` (not 401). Missing perm → 403 `{"error":"You don't have permission to do that."}` | `@PreAuthorize("hasAuthority('att.all')")` + custom `AuthenticationEntryPoint` returning 403 with same message to preserve client behaviour |
| Proxy | `src/proxy.ts` skips everything under `/api/` (no redirect) | API paths excluded from the page-login redirect |

### CSV helper — `src/lib/csv.ts`
- Body = UTF-8 **BOM (EF BB BF)** + rows joined by `\r\n`, trailing `\r\n`. Cells: null→""; if value starts with `= + - @ \t \r` prefix `'` (formula-injection guard); quote with `"` (doubling `"`) if it contains `" , \n \r`.
- Headers: `Content-Type: text/csv; charset=utf-8`, `Content-Disposition: attachment; filename="<name>"`, `Cache-Control: no-store`.
- Note: the `'` guard also hits negative numbers (e.g. `-5` → `'-5`). Reproduce exactly.
- Spring: `CsvWriter` utility (hand-rolled, ~20 lines; or Apache Commons CSV with `QuoteMode.MINIMAL` + custom escape for the `'` prefix) + `ResponseEntity<byte[]>` with the headers above.

### Endpoints
| # | Method & path | Params | Permission / row rules | Response | Status codes | Audit |
|---|---|---|---|---|---|---|
| 1 | `GET /api/attendance/register` | query: `view`=day\|month (def day); `day` YYYY-MM-DD (invalid or > today IST → today IST); `ym` YYYY-MM (invalid/future → current); `tz` ∈ Asia/Kolkata, America/New_York, America/Chicago, America/Los_Angeles, UTC, local (def = viewer tz if in list else Asia/Kolkata); `region` All\|IN\|US; `dept` uuid\|All; `st` ∈ All, Present, Working, Not checked in, Absent, On leave, Holiday, Weekly off, Half day (day view only; Present also matches Working); `q` (name/code/designation ILIKE); `sort` (day: name,loc,st,inn,out,h; month: name,P,A,L) / `dir` asc\|desc. Paging is ignored in export (all rows). Strings truncated to 200 chars. | `att.all`. Employee set: joined ≤ range end and (no exit or exit ≥ range start). | CSV. **Day** file `jurisone-attendance-<day>.csv`, columns: `Date, Employee ID, Name, Department, Team, Shift, Status, Late, Check-in (<tzName>), Check-out (<tzName>), Check-in (local), Check-out (local), Worked hours` where tzName = `employee local` if tz=local else the IANA id; Team = India\|US; Late = Yes\|""; times formatted `en-US` `h:mm AM TZ` with "GMT+5:30"→"IST" and suffix ` (+1)`/` (−1)` (U+2212) when local date differs from row day; local cols fall back to the main text; hours `toFixed(2)` or "". **Month** file `jurisone-attendance-<ym>.csv`, columns: `Employee ID, Name, Department, Team, <each date YYYY-MM-DD of month>, Present, Absent / LOP, Leave`; cell codes P, ½, A, L, H, "" (weekly off → `WO`), pending w/o punches & live → `–`, future → "" | 200; 403 | `attendance.export` entity `attendance`, after `{view, day, ym, filters: <all query params>}` (no IP) |
| 2 | `POST /api/declarations/proof` | multipart: `item` (must be in `DECLARABLE` catalogue), `file` | `self`. Owner only (writes for current user). Today (IST) must be within `tax.policy.proofWindowFrom..proofWindowUntil`. Size ≤ `tax.policy.maxProofMb`×1024×1024 (default 5 MB). Magic bytes: PDF `25 50 44 46`, PNG `89 50 4E 47`, JPG `FF D8 FF` (extension ignored). If existing item status=PENDING → reject. | JSON `{"ok":true,"name":"<sanitised>"}`. Writes `UPLOAD_DIR/proofs/<employeeId>/<uuid>.<ext>`; upsert `declaration_items` (key employeeId+fy+itemKey): create with amount 0, status DRAFT, section from `ITEM_SECTION`; update sets proofPath/proofName and, if APPROVED/REJECTED, resets to DRAFT and clears decidedBy/At/rejectReason. proofName = original name with `[^\w.\- ()]`→`_`, max 120, fallback `proof.<ext>`. Old file is **not deleted**. | 200; 400 `{"error": "The proof window is closed." \| "Unknown item." \| "Choose a file." \| "Files up to N MB." \| "Upload a PDF, JPG or PNG." \| "Waiting for Finance — replace it after they decide."}`; 403 (incl. missing/foreign Origin) | **none** (gap) |
| 3 | `GET /api/declarations/proof/{id}` | path `id` must match `^[0-9a-f-]{36}$` | any signed-in user; row rule: `item.employeeId == me` OR `proofs.verify`. Path traversal guard: resolved path must start with cwd. | Raw bytes; `Content-Type` by extension (.pdf/.png/.jpg else octet-stream); `Content-Disposition: inline; filename="<proofName without ">"`; `Cache-Control: private, no-store`; `X-Content-Type-Options: nosniff` | 200; 404 text "Not found" (bad id / no item / no path / outside root); 404 "The file is missing."; 403 | none |
| 4 | `GET /api/payroll/export?m=YYYY-MM&type=bank\|ecr\|pt\|tds\|register` | `m` regex `^\d{4}-\d{2}$`; `type` in list | `pay.view`; `type=tds` additionally needs `tax.view` (else 403). Data: if run CLOSED → payslip snapshots; else live `computeRun(month)` (IN employees with a salary structure). Filename suffix `-draft` when not closed. Decrypts PII (bank account, UAN, PAN). | CSV; money = paise/100 `toFixed(2)` unless noted. **bank** `bank-transfer-<m>[-draft].csv`: `Employee ID, Beneficiary name, Account number, IFSC, Bank, Amount (INR), Narration` (rows net>0; narration `Salary <m>`). **ecr** `pf-ecr-<m>…`: `UAN, Member name, Gross wages, EPF wages, EPS wages, EDLI wages, EPF contribution (EE), EPS contribution (ER), EPF-EPS difference (ER), NCP days, Refund of advances` — whole rupees `Math.round(x/100)`; w = min(basicEarned, 1,500,000 paise); eps = round(w×0.0833); diff = pf−eps; NCP = lop days; refund 0. **pt** `professional-tax-<m>…`: `Employee ID, Name, Gross earned, Professional tax`. **tds** `tds-<m>…`: `Employee ID, Name, PAN, Gross earned, TDS deducted`. **register** (default) `payroll-register-<m>…`: `Employee ID, Name, Days in month, Paid days, LOP days, Gross earned, PF, PT, TDS, Net pay` | 200; 400 text "Bad request"; 403 | `payroll.export` entity `payroll_run`, entityId = month, after `{type, rows, closed}`, **ip** recorded |
| 5 | `GET /api/payslips/{month}/{code}` | `month` `^\d{4}-\d{2}$`, `code` `^JC-\d+$` (else 404) | own (`code == me.code` AND `self`) OR `pay.view`; only payslips of CLOSED runs | PDF: reads `payslips.pdf_path` (relative to cwd, must stay inside cwd); on any failure **regenerates** from `payslips.snapshot`. `Content-Type: application/pdf`, `Content-Disposition: attachment; filename="Payslip-<code>-<month>.pdf"`, `Cache-Control: private, no-store` | 200; 404 "Not found"; 403 | none |
| 6 | `GET /api/people/export` | query as People page: `status` Current(def, ≠EXITED)\|ACTIVE\|PROBATION\|ONBOARDING\|EXITED\|All; `access` All\|Enabled\|Disabled; `dept` uuid\|All; `q` (fullName/code/email/designation/manager name ILIKE); `sort` name,code,dept,manager,loc,role,status,access,doj; `dir` | `people.view`; max 20,000 rows; no salary, no identity numbers | CSV `jurisone-people-<today in viewer tz>.csv`: `Employee ID, Name, Email, Designation, Department, Reporting manager, Manager ID, Location, Time zone, Shift, Role, Status, Portal access, Date of joining, Last working day` (Location = `locationOfTz(tz).l`; Status = enum value e.g. ACTIVE; access Enabled\|Disabled; dates ISO) | 200; 403 | `employee.export` entity `employee`, after `{rows, filters}` |
| 7 | `GET /api/tax/export` | `q` (name/code), `reg` All\|NEW\|OLD, `sort` name,reg,inc,taxable,tax,tds,pend, `dir` | `tax.view`; rows = IN payroll employees for the open payroll month (first month after last CLOSED run, capped at current month) | CSV `tax-tds-<fy>-<month>.csv`: `Employee ID, Name, Regime (Old\|New), Projected income, Deductions (incl. standard), Taxable income, Annual tax, Monthly TDS, Pending proofs` — **amounts are raw paise integers** (not rupees; inconsistent with payroll export — confirm before porting) | 200; 403 | `tax.export` entity `tax`, entityId = fy, after `{rows, month}` |

Global response headers (`next.config.ts`): `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: same-origin`, `Permissions-Policy: camera=(), microphone=(), geolocation=()`, prod `Strict-Transport-Security: max-age=63072000; includeSubDomains`; `/invite/*` and `/reset/*` → `Referrer-Policy: no-referrer`, `Cache-Control: no-store`. `X-Powered-By` off. → Spring Security `headers()` DSL.

### Proposed Spring controllers
| Controller | Endpoints | Services |
|---|---|---|
| `AttendanceExportController` | #1 | `AttendanceRegisterService.dailyLog/monthlyGrid`, `RegisterParams` record |
| `DeclarationProofController` | #2, #3 | `ProofStorageService`, `DeclarationService`, `FileTypeSniffer` |
| `PayrollExportController` | #4 | `PayrollService.computeRun/closedRows`, `PiiCrypto` |
| `PayslipController` | #5 | `PayslipPdfService`, `FileStorageService` |
| `PeopleExportController` | #6 | `PeopleQueryService` (JPA Specification / jOOQ) |
| `TaxExportController` | #7 | `TaxPositionService` |
| shared | | `AuditService.log(actorId, action, entity, entityId, before, after, ip)` (jsonb), `CsvWriter` |

---

### 1b. Request pipeline & authentication (current)

**No Auth.js / NextAuth.** Fully custom: opaque random session token in a cookie, sha256-hashed row in `sessions`, re-validated against DB on every request.

### Pipeline
| Stage | File | Behaviour |
|---|---|---|
| Edge "proxy" (middleware) | `src/proxy.ts` | Matcher: all paths except `_next/static`, `_next/image`, `brand/`, `fonts/`, `icon.svg`, `favicon.ico`, `robots.txt`. Public: `/login`, `/forgot`, `/invite/*`, `/reset/*`, and all `/api/*`. Otherwise if cookie `jo_session` **absent** → 302 `/login?next=<path+search>` (no `next` when path is `/`). Presence-only check. |
| `getCurrentUser()` | `src/lib/auth.ts` (React `cache` per request) | cookie → sha256 hex → `sessions.id`; null if missing, `revoked_at` set, `expires_at <= now`, employee `portal_access=false`, status EXITED, or no `users` row. Loads role permissions (`role_permissions.permission_key`), `is_super_admin`, region, timeZone, `hasReports` (= count of non-EXITED direct reports > 0). Calls `touchSession`. |
| `requireUser()` | auth.ts | pages: null → redirect `/login` |
| `requirePage(navId)` | auth.ts | NAV entry must pass `navAllowed(item, perms, hasReports)` else redirect `landingPath(u)` |
| `landingPath(u)` | auth.ts | `self` → `/home`; else `roles.manage` → `/overview`; else first allowed nav `/<id>`; else `/no-access` (login redirect uses `/directory` as last fallback instead) |
| `assertPerm(...perms)` | auth.ts | null user → ForbiddenError("Your session has ended. Please sign in again."); if perms given, needs **any** of them (OR) → else ForbiddenError(); no perms = signed-in only |
| `requestMeta()` | `src/lib/request.ts` | ip = first entry of `X-Forwarded-For` (trimmed) else `X-Real-IP` else null; userAgent truncated to 400 chars |

Permissions (16): `self, dir, att.all, appr.leave, appr.reg, people.view, people.add, access.toggle, dept.manage, holidays.manage, pay.view, pay.close, struct.manage, tax.view, proofs.verify, roles.manage`. Salary perms (HR must never hold): `pay.view, pay.close, struct.manage, tax.view, proofs.verify`. Super admin = `users.is_super_admin` (no implicit perm bypass in `can()`).

### Session — `src/lib/session.ts`
| Item | Value |
|---|---|
| Cookie name | **`jo_session`** |
| Token | `randomBytes(32).toString("base64url")` (43 chars, no padding) |
| Stored id | `sessions.id = sha256(token)` lowercase hex (64 chars) |
| Attributes | `HttpOnly; SameSite=Lax; Path=/; Secure` only when NODE_ENV=production; `Expires=expiresAt` only when "remember me" (else browser-session cookie) |
| TTL | remember: `SESSION_TTL_DAYS` (default 30) days; not remember: 12 h. **Absolute, not sliding** — `expires_at` never extended |
| Touch | `last_seen_at` updated at most every 5 min (errors swallowed) |
| Columns | id, employee_id, created_at, expires_at, last_seen_at, ip, user_agent, revoked_at, revoked_reason |
| Revoke | logout → `revoked_reason='logout'` + delete cookie; `revokeAllSessions(emp, reason)` reasons: `access_disabled`, `exited`, `password_reset`, `invite_accepted` |

### Password — `src/lib/password.ts` (`@node-rs/argon2`)
| Param | Value |
|---|---|
| algorithm | argon2id (`algorithm: 2`), version 0x13 (19) |
| memoryCost | **19456 KiB** (19 MiB) |
| timeCost | **2** |
| parallelism | **1** |
| salt / hash length | library defaults: 16-byte salt, 32-byte output |
| Stored format (PHC) | `$argon2id$v=19$m=19456,t=2,p=1$<salt b64 no-pad, 22 chars>$<hash b64 no-pad, 43 chars>` — verified sample: `$argon2id$v=19$m=19456,t=2,p=1$ZtWNOmlTP11OPCqbfw5PjA$ii1HCa1mk3HdyBGmLfWImMjxTzVbWPqIHeWpA5WPTIs` (standard alphabet, no secret/AD) |
| Null hash | `users.password_hash` NULL until invite accepted → verify returns false |
| Timing | unknown identifier verifies against a cached dummy hash |
| Policy | 10–200 chars, ≥1 letter and ≥1 non-letter. Message: "At least 10 characters, with a letter and a number or symbol." |

**Java:** Spring Security `Argon2PasswordEncoder(16, 32, 1, 19456, 2)` (saltLength, hashLength, parallelism, memory KiB, iterations) — its `Argon2EncodingUtils.decode` parses the PHC string (type, `v=19`, `m,t,p`, unpadded std-Base64 salt/hash) and re-derives using params *from the hash*, so existing hashes verify. Do **not** use `defaultsForSpringSecurity_v5_8()` (m=16384) for new hashes, or set `upgradeEncoding` deliberately. Requires `org.bouncycastle:bcprov-jdk18on` (it uses `Argon2BytesGenerator`). Alternative: `de.mkammerer:argon2-jvm` (native libargon2, also PHC-compatible). Add a golden-hash unit test with the sample above (password must be known — generate one with the Node lib for the test).

### Tokens — `src/lib/auth-tokens.ts` (`auth_tokens`)
| Item | Value |
|---|---|
| Token | 32 random bytes base64url; stored `token_hash = sha256 hex` (unique) |
| Purposes | `INVITE` (TTL `auth.policy.inviteTtlHours`=72 h), `RESET` (`resetTtlMinutes`=60 min) |
| Issue | in one tx: mark all unused tokens of same employee+purpose `used_at=now`, insert new → only newest link works |
| Links | `${APP_URL}/invite/<token>`, `${APP_URL}/reset/<token>` |
| Usable | exists, purpose matches, `used_at` null, `expires_at > now`, employee not EXITED |

### Flows (server actions in `src/app/(auth)/actions.ts`)
| Flow | Steps |
|---|---|
| **Login** (`identifier`, `password`, `remember`="on", `next`) | zod: identifier trim 1–200, password 1–200. IP limiter key `login:<ip>`: if ≥ `ipMaxAttempts` (30) failed hits in window → "Too many failed sign-in attempts. Try again in N minute(s)." Only *failed* attempts hit the IP counter (window `ipWindowMinutes`=15). Lookup: contains `@` → `employees.email = lower(identifier)`, else `code = upper(identifier)`. Unknown/no user → dummy verify, IP hit, history `unknown_identifier`, generic error. `locked_until > now` → history `locked`, locked message. Bad password → IP hit, `failed_attempts+1`; if ≥ `maxFailedAttempts` (5): set `failed_attempts=0, locked_until=now+lockMinutes(15)`, history `bad_password_locked`, audit `auth.locked` (entity user, after `{lockMinutes}`); else history `bad_password` or `not_activated` (null hash → "This account isn't activated yet…"). Correct password but EXITED → history `exited`; portal disabled → `access_disabled` (status revealed only after correct password). Success → `failed_attempts=0, locked_until=null, last_login_at=now`, createSession, history `ok`, audit `auth.login` (entity session, after `{userAgent}`, ip). Redirect `next` if starts with `/` and not `//`, else `/home` (self) / `/overview` (roles.manage) / `/directory`. |
| **login_history** | every attempt: employee_id (nullable), identifier (≤200), success, reason ∈ {ok, unknown_identifier, locked, bad_password, bad_password_locked, not_activated, exited, access_disabled}, ip, user_agent, at |
| **Logout** | revoke current session (`logout`), delete cookie, audit `auth.logout`, redirect `/login?signedout=1` |
| **Forgot** | identifier lower-cased; limiter `forgot:<ip>` 5 per 15 min (counts every call); if employee has users row, not EXITED, portal enabled → `sendPasswordReset` + audit `auth.reset_requested`. Always returns "If that account exists and is active, a reset link is on its way to the official email." |
| **Invite / reset accept** (`token, purpose, password, confirm`) | `/invite/[token]`, `/reset/[token]` pages call `findUsableToken` to show form or "Link expired". Submit: zod token 10–200; password rules; confirm match; token usable; argon2 hash; tx: token `used_at=now`, upsert users (`password_hash`, `password_set_at`, reset failed/lock), revoke all sessions (`password_reset`/`invite_accepted`), audit `auth.password_reset`/`auth.invite_accepted`. Redirect `/login?reset=1` or `?activated=1`. |
| **Invite issue** | on employee create (users row created with null hash, then `sendInvite`) and `resendInvite` (people.add, not if password already set) → audit `employee.invite_sent` |

### Rate limiting — `src/lib/rate-limit.ts`
In-memory fixed window `Map<key,{n, reset}>`, single-instance only, pruned when >10,000 keys. `limited(key,max)` / `hit(key,windowMs)` / `rateLimit(key,max,windowMs)`. **Java:** Bucket4j + Caffeine (or a Postgres table for multi-instance); keep "only failed logins count" semantics. Per-account lockout already lives in DB.

### PII crypto — `src/lib/crypto.ts`
| Item | Value |
|---|---|
| Algorithm | AES-256-GCM, no AAD |
| Key | env `PII_ENCRYPTION_KEY`, standard Base64 → must decode to exactly 32 bytes |
| IV | 12 random bytes |
| Tag | 16 bytes (Node default) |
| Layout | `"v1." + b64url(iv) + "." + b64url(tag) + "." + b64url(ciphertext)` — base64url **without padding** (iv 16 chars, tag 22 chars). Live sample prefix: `v1.21rqHVRZdLDTuC23.3mMxvHp2FHOmx-3HgCtk…` |
| Plaintext | UTF-8; null/"" → stored NULL |
| Fields | `pan_enc, aadhaar_enc, uan_enc, phone_enc, address_enc, bank_account_enc` (+ `*_last4` clear columns, last 4 alphanumerics) |
| Other | `sha256(s)` hex; `randomToken(n)` base64url |

**Java (`PiiCrypto` component):** `Cipher.getInstance("AES/GCM/NoPadding")`, `GCMParameterSpec(128, iv)`. Decrypt: split on `.`, require `v1`, `Base64.getUrlDecoder()` (accepts no padding) each part, then `cipher.doFinal(concat(ct, tag))` — Java expects tag **appended** to ciphertext. Encrypt: output of `doFinal` = ct‖tag; split last 16 bytes as tag; encode with `Base64.getUrlEncoder().withoutPadding()`. Key via `Base64.getDecoder()` (standard alphabet). Golden test against a DB row.

### Masking — `src/lib/mask.ts`
Aadhaar `XXXX XXXX l4`, PAN `XXXXXXl4`, UAN `XXXX XXXX l4`, phone `•••• •• l4`, bank `XXXX XXXX l4`, missing → `—`. → `MaskUtil` static methods.

### Env — `src/lib/env.ts` (+ `.env.example`, scripts)
| Var | Default / rule | Spring property |
|---|---|---|
| DATABASE_URL | required (`postgresql://jurisone:…@localhost:5436/jurisone?schema=public`) | `spring.datasource.url=jdbc:postgresql://…` |
| APP_NAME | JurisOne | `app.name` |
| APP_URL | http://localhost:3005 (url) | `app.url` (link building) |
| PII_ENCRYPTION_KEY | required, base64 of 32 bytes | `app.pii-key` (fail-fast validation) |
| SESSION_TTL_DAYS | 30 | `app.session.ttl-days` |
| SMTP_HOST / SMTP_PORT / SMTP_USER / SMTP_PASS | "" / 587 / "" / "" | `spring.mail.*`; `secure` (implicit TLS) when port 465 |
| MAIL_FROM | `JurisOne <no-reply@jurislpo.com>` | `app.mail.from` |
| UPLOAD_DIR | ./storage | `app.upload-dir` |
| NODE_ENV | development | Spring profiles (cookie `Secure` in prod) |
| JOBS_ENABLED | (not in zod) `"false"` disables in-process scheduler | `app.jobs.enabled` + `@ConditionalOnProperty` |
| PORT | 3005 | `server.port` |
| SUPER_ADMIN_EMAIL / SUPER_ADMIN_PASSWORD / SEED_DEMO_PASSWORD | seed only | seed runner (CommandLineRunner, dev profile) |

### Spring auth design (proposal)
- `SessionAuthenticationFilter` (OncePerRequestFilter, before `AnonymousAuthenticationFilter`): read `jo_session`, sha256, load session+employee+perms, apply same validity rules, `touch` every 5 min, set `JoAuthentication` (principal `CurrentUser` record, authorities = permission keys). Stateless `SessionCreationPolicy.STATELESS` — do not use Spring Session/JSESSIONID; keep the `sessions` table.
- `AuthController`: `POST /api/auth/login`, `POST /api/auth/logout`, `POST /api/auth/forgot`, `GET /api/auth/tokens/{purpose}/{token}` (validity + name/code), `POST /api/auth/set-password`. `AuthService`, `SessionService`, `AuthTokenService`, `LoginRateLimiter`, `LoginHistoryRepository`.
- `NavGuard` / `PermissionEvaluator` for page-level rules (`navAllowed`, `landingPath`) if the frontend stays server-rendered; expose `GET /api/me` returning perms + landing path.

---

### 1c. Background jobs

### Runner
| Item | Current | Spring |
|---|---|---|
| Trigger | `src/instrumentation.ts` → `startScheduler()` when `NEXT_RUNTIME=nodejs` and `JOBS_ENABLED != "false"` | `@EnableScheduling` + `@ConditionalOnProperty("app.jobs.enabled", matchIfMissing=true)` |
| Schedule | `setInterval` every **10 min**, plus once **15 s after boot**; guarded by global for hot reload | `@Scheduled(fixedDelay=600000, initialDelay=15000)` |
| Manual/cron | `npm run jobs` → `scripts/run-jobs.ts` runs `runJobs()` once, prints JSON | `--jobs.run-once` ApplicationRunner, or actuator/admin endpoint |
| Concurrency | `SELECT pg_try_advisory_lock(74202)`; if false → `{skipped:true}`; `pg_advisory_unlock(74202)` in finally (session-level lock) | `JobsRunner` acquires on **one dedicated connection** (`JdbcTemplate` with `ConnectionCallback` or `DataSourceUtils`), since session-level advisory locks are per connection — lock and unlock must use the same connection. Alt: `pg_try_advisory_xact_lock` inside a single tx, or ShedLock |
| Other lock id | `pg_advisory_xact_lock(74201)` serialises employee code allocation (people create) | same in `EmployeeService.create` |
| Logging | `[jobs] status N · days N · locked …` | SLF4J |

### Jobs (order executed in `runJobs`)
| # | Function | Does | Idempotency | Audit |
|---|---|---|---|---|
| 1 | `jobEmployeeStatus` | ONBOARDING→PROBATION on DOJ, PROBATION→ACTIVE after `employee.policy.probationMonths` (6), via `derivedStatus`, today in IST | state-based (only changes if different) | `employee.status_auto` (actor null, before/after status) |
| 2 | `jobLockMonths` | For each ym from trackingStart month while `lockInstant(ym) <= now` (day `lockDayOfNextMonth`=1 of next month at `lockTime` 10:00 IST): materialize all days with `lock:true` | `job_runs` (name `attendance.lock`, key `YYYY-MM`, detail `{employees, rows}`), unique(name,key) | `attendance.month_locked` entityId ym |
| 3 | `jobFinalizeDays` | Persist finished days for last 7 days (from max(today−7, trackingStart) to today IST) for non-exited or exited within 10 days → absent marking | upsert in `materialize` (state-based) | none |
| 4 | `jobLeaveYearEnd` | For prev calendar year (once): per active leave type with `needsBalance`, code≠CO, balance at 31 Dec → `yearEndLapse(balance, carryForwardCap)` ledger EXPIRED rows, refId `year_end:<yyyy>` | `job_runs` (`leave.year_end`, key prev year); skipped if prev < tracking start year | none |
| 5 | `jobLeaveAccrual` | For months 1..current of current year, each active type with `accrual_per_month>0`, each non-exited TRACKED employee joined ≤ month end → ledger ACCRUAL `refId accrual:<code>:<YYYY-MM>`, note `Accrual <ym>`, pro-rata by `accrualFor` | dedupe by existing (employeeId, refId) | none |
| 6 | `jobCompOffExpiry` | Comp-off credits (`COMP_OFF_CREDIT`) expire FIFO after `expires_on` (60 days), net of USED/CANCELLED/ADJUSTMENT; writes EXPIRED rows with refId = credit id | computed from existing EXPIRED rows per credit | none |

`job_runs` columns: id, name, key, ran_at, ok (default true), detail jsonb; unique (name,key). Also used by seed (`seed|requests`, `seed|punches`).

Spring: `JobsRunner` (orchestrator, advisory lock) + `EmployeeStatusJob`, `AttendanceLockJob`, `AttendanceFinalizeJob`, `LeaveYearEndJob`, `LeaveAccrualJob`, `CompOffExpiryJob` (each `@Transactional` service method taking `Instant now` for testability); `JobRunRepository`. All "today" computations in `ZoneId.of("Asia/Kolkata")`.

---

### 1d. File storage

Root `UPLOAD_DIR` (default `./storage`), resolved against process cwd. **DB stores paths relative to cwd, with OS separators — current data contains Windows backslashes**, e.g. `storage\payslips\2026-04\JC-004.pdf`, `storage\proofs\<uuid>\rent-demo.pdf`. Java must normalise (`replace('\\','/')`) or a migration should rewrite `pdf_path`/`proof_path` to `/` and/or store paths relative to UPLOAD_DIR.

| Kind | Write | Read | Rules |
|---|---|---|---|
| Proof uploads | `POST /api/declarations/proof` → `UPLOAD_DIR/proofs/<employeeId>/<randomUUID>.<pdf\|png\|jpg>`; `declaration_items.proof_path` (relative), `proof_name` (sanitised original) | `GET /api/declarations/proof/{id}` | ≤ `tax.policy.maxProofMb` MB (5); magic bytes PDF `%PDF` / PNG `\x89PNG` / JPEG `FF D8 FF`; within proof window; not while PENDING; previous file left on disk (orphans) |
| Seed proofs | `prisma/seed.ts seedProofFiles()` for items with `proof_name` but no path → `UPLOAD_DIR/proofs/<employeeId>/<itemKey>-demo.pdf`, a tiny hand-written PDF (`%PDF-1.4 … % Demo proof: <itemKey>, <amount INR> … %%EOF`) | same | dev data only |
| Payslip PDFs | `closeRun(ym)` (`src/lib/payroll/close.ts`, called by Close action and seed for 2026-04..08) → `UPLOAD_DIR/payslips/<YYYY-MM>/<code>.pdf`, written **before** the DB tx; `payslips.pdf_path` relative | `GET /api/payslips/{m}/{code}`; regenerated from `snapshot` if file missing/unreadable | snapshot jsonb is the source of truth (immutable) |
| Logo | — | `public/brand/logo-ink.png` (cached in memory) | decorative; failures ignored |

### Payslip PDF — `src/lib/payroll/pdf.ts` (pdf-lib)
A4 (210×297 mm), coordinates in mm from top (`pt = mm×72/25.4`, `y_pdf = H − y`), margins M=16. Fonts: Standard-14 **Helvetica** / **Helvetica-Bold** (WinAnsi → "₹" not drawable, amounts as `Rs. <en-IN grouped whole rupees>`). Colours: INK (20,20,26), MUTED (110,112,125), FAINT (130,132,145), LINE (230,230,236), ACCENT (110,86,207), LOP red (207,58,64), attendance band bg (244,244,247). Metadata: Title `Payslip <code> <Month YYYY>`, Author company name, Creator `JurisOne`.
Layout: logo 46 mm wide at (16,16); right-aligned "PAYSLIP" 18pt bold, month label 11pt, `<company> · <address>` 8.5pt; accent rule 2.2pt; 3×3 key/value grid (labels 7.5pt uppercase FAINT, values 10.5 bold): Employee/Employee ID/Designation; Department/Date of joining/Tax regime; PAN `XXXXXX<l4>`/Bank account `<bank> XXXX<l4>`/Pay date; attendance band (Days in month, Weekly offs, Holidays, Present, Loss of pay, Paid days); two-column Earnings (label, RATE, EARNED) / Deductions (label, AMOUNT) tables with 8 mm rows and hairlines; Gross earnings / Total deductions totals; accent NET PAY box (24 mm) with "Rupees <netWords> only" wrapped at 120 mm and amount 20pt; footer notes 8pt (formula note, regime note, optional `Note:`, "system-generated" line). Date formats `d Mon yyyy`, month `Month yyyy`.

**Java:** Apache PDFBox 3.x (`PDType1Font(Standard14Fonts.FontName.HELVETICA[_BOLD])`, `PDImageXObject.createFromFile`, `getStringWidth/1000*size` for right-align) — a 1:1 port of the coordinate code reproduces the layout. Indian digit grouping: Java `NumberFormat` cannot do 2-digit grouping (DecimalFormat supports one grouping size) → use ICU4J `NumberFormat.getInstance(new ULocale("en_IN"))` or a small custom formatter. Alternative (layout from HTML): OpenHTMLtoPDF, but it won't be pixel-identical. Service: `PayslipPdfService.render(PayslipSnapshot)`; `FileStorageService` (resolve under root, traversal check with `Path.normalize().startsWith(root)`).

---

### 1e. Email & notifications

### Transport — `src/lib/email.ts sendEmail({to, subject, text, html?})`
1. Insert `email_outbox` (status default `QUEUED`).
2. No `SMTP_HOST` → log to console, set `LOGGED` + `sent_at`.
3. Else nodemailer `{host, port, secure: port==465, auth if SMTP_USER}` from `MAIL_FROM`; success → `SENT` + `sent_at`; error → `FAILED` + `error` (≤1000 chars), not rethrown. **Synchronous**, no retry/worker (outbox is a log, not a queue).
Enum `EmailStatus`: QUEUED, SENT, FAILED, LOGGED.

Spring: `EmailService` with `JavaMailSender` (`spring.mail.properties.mail.smtp.ssl.enable=true` for 465, STARTTLS otherwise), `EmailOutboxRepository`; consider `@Async`/`@TransactionalEventListener(AFTER_COMMIT)` or an outbox poller `@Scheduled` that retries QUEUED/FAILED. Templates: Thymeleaf (`templates/mail/*.html` + `.txt`).

### Templates (HTML shell: 520px card, #F6F6F8 bg, purple "J" badge #6E56CF, footer "Juris Consultants · this is an automated message."; button + "Or paste this link: <href>"; HTML-escaped interpolations)
| Email | Trigger | Subject | Text body |
|---|---|---|---|
| Invite | employee create; resend invite | `Welcome to JurisOne — set your password` | `Hi {name},\n\nYour JurisOne account ({code}) is ready. Set your password with this one-time link (valid {hours} hours):\n{link}\n\nSign in afterwards with your employee ID or official email.` HTML title `Welcome, {name}`, button "Set my password" |
| Reset | forgot | `Reset your JurisOne password` | `Hi {name},\n\nUse this one-time link to choose a new password (valid {minutes} minutes):\n{link}\n\nIf you didn't ask for this, you can ignore this email.` HTML title "Reset your password", button "Choose a new password" |
| Leave decision | approvals action (`notifyDecision`) | `{what} approved` / `{what} declined` where what = `<leave type name> (<span>)` | `Hi {name},\n\nYour {what lower} was {verb} by {by}.[\n\nNote: {note}]\n\nOpen JurisOne for details.` |
| Regularization decision | approvals action | same template; what = `<KIND label> for <d Mon>`; by = `<name> (<role name>)` | same |
| Payslip published | `closeRun` (unless `notify:false`, e.g. seed), after tx commit, one per employee | `Your {Month YYYY} payslip is ready` | `Hi {name},\n\nYour payslip for {Month YYYY} has been published. View or download it in JurisOne: {APP_URL}/payslips` (text only, no HTML) |

### In-app notifications — `src/lib/notify.ts`, table `notifications` (id, employee_id, text ≤300, link, read_at, created_at)
| Source | Text | Link |
|---|---|---|
| `notifyDecision` (leave / regularization) | `{what} approved\|declined by {by}` + email | `/leave` or `/attendance?m=YYYY-MM&d=YYYY-MM-DD` |
| Proof decision | `{item label} proof approved` / `… proof rejected — {reason}`; bulk: `N investment proof(s) approved — your TDS goes down from the next payroll` | `/declare` |
| Tax regime set by Finance | `Finance set your tax regime to the new\|old regime` | `/declare` |
| New India employee → all active `struct.manage` holders | `{name} ({code}) joined — salary structure pending` (auto-marked read when structure saved) | `/structures` |
| Payroll close | `Your {Month YYYY} payslip is ready` | `/payslips` |
Mark read: `markNotificationsRead()` server action — all own unread → `read_at=now`.

### Shell data — `src/lib/shell-data.ts`
Sidebar groups from allowed NAV (group fallback "Workspace" if no `self` perm, else "My workspace"); badges: `approvals` = pending leave (if appr.leave) + pending regularizations (if appr.reg), excluding own; `proofs` = PENDING declaration items excluding own (if proofs.verify). Bell notes: approvals summary, proofs summary, last 8 notifications from 14 days (time formatted `en-GB` in user tz), next holiday (region ALL or user's region, date ≥ today in user tz) `"{name} holiday on {Ddd, d Mon yyyy}"`; `unread` count. → `GET /api/shell` in `ShellController` / `NotificationController` (`GET /api/notifications`, `POST /api/notifications/read`).

---

### 1f. Settings

`getSetting(key)`: row in `settings` (key PK, value jsonb, updated_at) **shallow-merged over** the code default (`{...default, ...row.value}`); nested objects (e.g. `professionalTax`) replaced wholesale. All 6 keys currently have DB rows.

| Key | Default JSON |
|---|---|
| `attendance.policy` | `{"fullDayHours":8,"halfDayHours":4,"graceMinutes":15,"lateMarksPerHalfDayLop":3,"weeklyOffs":[0,6],"absenceJobBufferMinutes":120,"lockDayOfNextMonth":1,"lockTime":"10:00","shiftTargetHours":9,"trackingStartDate":"2026-04-01"}` (lockTime IST; weeklyOffs 0=Sun) |
| `employee.policy` | `{"probationMonths":6}` |
| `auth.policy` | `{"maxFailedAttempts":5,"lockMinutes":15,"ipWindowMinutes":15,"ipMaxAttempts":30,"inviteTtlHours":72,"resetTtlMinutes":60}` |
| `payroll.policy` | `{"pfRate":0.12,"pfWageCeiling":15000,"professionalTax":{"state":"Default","monthly":200}}` (rupees) |
| `tax.policy` | `{"fy":"2026-27","regimeChangeUntil":"2027-01-31","proofWindowFrom":"2026-04-01","proofWindowUntil":"2027-01-31","maxProofMb":5}` |
| `company` | `{"name":"Juris Consultants","address":"[Registered office address]"}` |

Spring: `SettingsService.get(Class<T>)` with typed records (`AttendancePolicy`, `EmployeePolicy`, `AuthPolicy`, `PayrollPolicy`, `TaxPolicy`, `CompanySettings`); defaults as record constants; merge via Jackson `ObjectMapper.readerForUpdating(defaultCopy).readValue(row.value)` (Jackson replaces nested values unless `@JsonMerge` is set, which matches the current shallow merge; simplest is `ObjectNode.setAll(rowValue)` on a copy of the default node, then `treeToValue`). Optional Caffeine cache with eviction on update.


---

## 2. Server actions → Spring Boot endpoints

Source: every `"use server"` file in `src/` (16 files, **43 exported actions**). Verified by `grep -rl '"use server"' src` — no other server-action files exist. Route handlers under `src/app/api/**` (proof upload/download, exports, payslip PDF) are out of scope here but are referenced where actions depend on them.

### 2.0 Common contract

#### 2.0.1 `ActionResult<T>` (src/lib/action.ts)

```ts
type ActionResult<T = undefined> = { ok: true; msg?: string; data?: T } | { ok: false; error: string };
```

`run(fn)` wraps every action body except the 4 auth actions and `markNotificationsRead`:

| Thrown inside `run` | Today (always HTTP 200, server-action POST) | Proposed Spring mapping |
|---|---|---|
| — (success) | `{ok:true, msg?, data?}` | **200** `{ok:true,msg?,data?}`; **201** for creates (optional, keep the body the same) |
| `ForbiddenError` from `assertPerm`, user signed out / session invalid | `{ok:false, error:"Your session has ended. Please sign in again."}` | **401** with the same body |
| `ForbiddenError` from `assertPerm`, permission missing | `{ok:false, error:"You don't have permission to do that."}` | **403** |
| `UserError` (business rule) | `{ok:false, error:e.message}` (exact text, shown to the user) | **422** (a 404 or 409 can be used where noted, but the body must stay `{ok:false,error}` with the same text) |
| `ZodError` | `{ok:false, error: issues[0].message ?? "Some details are invalid."}` (first issue only; default zod messages for un-messaged rules, e.g. "Invalid uuid") | **400** with `{ok:false,error}`; use `MethodArgumentNotValidException` handler → first `FieldError.defaultMessage` |
| Prisma `P2002` unique violation | `{ok:false, error:"That already exists."}` | **409** (`DataIntegrityViolationException` with unique-violation SQLState `23505`) |
| anything else | logged; `{ok:false, error:"Something went wrong. Please try again."}` | **500** with the same body |

Note: rows below that say "row-level denial" are **`UserError`, not `ForbiddenError`**, so they currently surface as the generic `ok:false`. Keeping 422 preserves the text. Mapping them to 403 is cleaner REST, but the frontend must not depend on the status alone.

Implementation: `@RestControllerAdvice` with `UserException(String msg, HttpStatus status=422)`, `ForbiddenException`, `UnauthenticatedException`, plus handlers for `ConstraintViolationException`, `MethodArgumentNotValidException` and `HttpMessageNotReadableException` (400). Every response is wrapped as `ApiResult<T>{boolean ok; String msg; T data; String error}`, with nulls omitted (`@JsonInclude(NON_NULL)`).

#### 2.0.2 Cross-cutting behaviours to reproduce

| Concern | Today | Spring |
|---|---|---|
| Auth on every action | `assertPerm(...perms)` = **any-of** check; session re-validated against DB per request (session not revoked/expired; employee `portalAccess` and not `EXITED`; `users` row exists). `touchSession` updates `last_seen_at` at most every 5 min (errors swallowed). | `OncePerRequestFilter` that reads cookie `jo_session`, computes `sha256(token)` hex and looks up `sessions.id`. Then `@PreAuthorize("hasAnyAuthority('x','y')")`. Permissions are loaded from `role_permissions` on every request (no caching, because permission edits take effect immediately). |
| Session cookie | `jo_session` = random 32 bytes base64url; DB stores **sha256 hex** as `sessions.id`. Options: `httpOnly`, `SameSite=Lax`, `Secure` in prod, `path=/`; `Expires` only if "remember" (TTL `SESSION_TTL_DAYS` env), otherwise a browser-session cookie with a 12 h DB expiry. | `ResponseCookie.from("jo_session",...).httpOnly(true).sameSite("Lax").secure(prod).path("/")`, with `.maxAge` only when remember is set. |
| Audit | `audit({actorId, action, entity, entityId, before, after, ip}, tx?)` → `audit_log`. JSON is serialised with **BigInt → string**. Usually inside the same transaction. | `AuditService.log(...)` with `@Transactional(propagation=MANDATORY)` when called in a transaction. Jackson writes `Long` as a number, so to keep parity with existing rows serialise BigInt-origin values as strings, or accept the difference and document it. |
| IP | `x-forwarded-for` first hop → `x-real-ip` → null; UA truncated to 400 chars. | `RequestMetaResolver` with the same precedence. Do not use `request.getRemoteAddr()` behind a proxy. |
| Notifications | `notify()` → `notifications` row (text sliced to 300 chars, optional link). `notifyDecision()` = bell + email. | `NotificationService` |
| Email | every mail goes to `email_outbox` first. No `SMTP_HOST` → status `LOGGED` (console). Send OK → `SENT`. Failure → `FAILED` with the error (1000 chars), **never thrown**. Sent synchronously after commit. | `MailService` (JavaMailSender). Keep the outbox. Ideally `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`. |
| Cache invalidation | `revalidatePath(...)` lists per action (below). | **No backend equivalent.** The frontend must refetch or invalidate its query keys. The paths are listed so the client knows which views go stale. |
| Money | integer **paise**. Monthly figures are `Int` (salary_structures, payroll_adjustments); annual figures are `BigInt` (declaration_items.amount, tax_profiles.prev_employer_*). Inputs arrive in **rupees** and are ×100 on the server. | `int`/`long` → use `long` everywhere. |
| Decimals | `Decimal(5,2)` leave days/lop, ledger delta `Decimal(6,2)`, adjustment lopDays `Decimal(5,2)` nullable. Code uses `Number(...)`. | `BigDecimal` in entities; `round2` semantics = `setScale(2, HALF_UP)`. Check the JS `Math.round`-based round2 for parity on .xx5 values. |
| Dates | ISO `YYYY-MM-DD` strings ↔ `@db.Date`. "today" is computed in **Asia/Kolkata** (`todayIn(IST)`) or in the employee's tz for attendance. | `LocalDate` + explicit `ZoneId`. |

### 2.1 Auth — `src/app/(auth)/actions.ts` (4)

These use **`FormState {error?, ok?, identifier?}`**, not ActionResult. They are not wrapped in `run`, and on success they **`redirect()`** (throws NEXT_REDIRECT). The REST replacement returns JSON with a `redirectTo`, and the SPA navigates.

| Action | Input | Output / exact messages | Perms & rules | DB / side effects | Spring |
|---|---|---|---|---|---|
| `loginAction(_prev, FormData)` | `identifier` trim 1–200 ("Enter your employee ID or email."), `password` 1–200 ("Enter your password."), `remember` = checkbox `"on"`, `next` (optional redirect path) | Errors: `"That employee ID / email and password don't match."`; `"Too many failed sign-in attempts. Try again in {m} minute(s)."`; `"This account is locked after repeated failed sign-ins. Try again in {m} minute(s), or reset your password."`; `"Too many failed attempts — this account is locked for {lockMinutes} minutes."`; `"This account isn't activated yet. Use the invite link in your email, or choose “Forgot?” to get a new one."`; `"This account belongs to a past employee and can no longer sign in."`; `"Your portal access is disabled. Please contact HR."`. Always echoes `identifier` (≤200). Success → redirect to `next` if it starts with `/` and not `//`, else `/home` (has `self`) → `/overview` (has `roles.manage`) → `/directory`. `{m}` = ceil(ms/60000), min 1. | public. **IP rate limit**: in-memory fixed window, key `login:{ip}`; only *failed* attempts count (`hit`); limit `auth.policy.ipMaxAttempts` (30) per `ipWindowMinutes` (15). Identifier with `@` → email lookup (lowercased), else `code` uppercased. Unknown user → verify against a **dummy argon2 hash** (timing equaliser). Lock check comes **before** the password check. A wrong password increments `users.failed_attempts`; at ≥`maxFailedAttempts` (5) it sets `locked_until = now + lockMinutes` (15) **and resets the counter to 0**. EXITED / portal-disabled messages are shown **only after a correct password** (no enumeration). | R `employees`+`users`+`role_permissions`, `settings`. W `users` (failed_attempts/locked_until, or reset + `last_login_at`), `login_history` (reason: `unknown_identifier`, `locked`, `bad_password`, `bad_password_locked`, `not_activated`, `exited`, `access_disabled`, `ok`), `sessions` (create). Audit `auth.locked`, `auth.login` (entity `session`). Sets cookie. No transaction. | `POST /api/auth/login` body `{identifier,password,remember,next?}` → 200 `{ok:true,data:{redirectTo}}` + Set-Cookie; 401 `{ok:false,error,identifier}`; 429 for the IP limit. `AuthController` / `AuthService`, `LoginRateLimiter` (Bucket4j/Redis if multi-instance) |
| `logoutAction()` | none | redirect `/login?signedout=1` | any (works signed-out) | `sessions` updateMany `revoked_at`, `revoked_reason='logout'` where not revoked; delete cookie. Audit `auth.logout` (only if a user was resolved; resolved **before** the revoke). | `POST /api/auth/logout` → 200 `{ok:true}` + clearing Set-Cookie. `AuthController` / `SessionService` |
| `forgotAction(_prev, FormData)` | `identifier` (trim, lowercased) | `"Enter your employee ID or official email."`; `"Too many requests. Try again in {m} minute(s)."`; always `ok: "If that account exists and is active, a reset link is on its way to the official email."` | public. Rate limit `forgot:{ip}` **5 per 15 min, counts every call**. Sends only if the user row exists, is not EXITED, and has portalAccess. | `auth_tokens`: in one transaction, mark all unused RESET tokens of the employee used, then create a new one (`token_hash = sha256(token)`, TTL `resetTtlMinutes` 60). Email (reset, link `${APP_URL}/reset/{token}`) → `email_outbox`. Audit `auth.reset_requested`. | `POST /api/auth/password/forgot` `{identifier}` → always 200 `{ok:true,msg}`; 429 when rate-limited. `PasswordController` / `PasswordResetService`, `AuthTokenService` |
| `setPasswordAction(_prev, FormData)` | `token` 10–200, `purpose` `INVITE`/`RESET`, `password`, `confirm` (any schema failure → "This link is invalid.") | `"This link is invalid."`; password rules: `"Use at least 10 characters."`, `"That password is too long."` (>200), `"Include at least one letter and one number or symbol."`; `"The two passwords don't match."`; `"This link has expired or was already used. Ask HR for a new invite, or use “Forgot?” on the sign-in page."` Success → redirect `/login?reset=1` or `/login?activated=1`. | public. Token valid if: found by sha256, purpose matches, `used_at` null, not expired, employee not EXITED. | **Transaction**: `auth_tokens.used_at=now`; `users` upsert (hash, `password_set_at`, failed_attempts=0, locked_until=null); revoke all sessions (`password_reset` / `invite_accepted`); audit `auth.password_reset` / `auth.invite_accepted`. **argon2id** (`@node-rs/argon2`, algorithm 2): `m=19456 KiB, t=2, p=1`, PHC string. Hash computed **before** the transaction. Not race-proof: the token is consumed with `update`, not `updateMany ... where used_at is null`. | `POST /api/auth/password/set` `{token,purpose,password,confirm}` → 200 `{ok:true,data:{redirectTo}}`. Spring Security `Argon2PasswordEncoder(16 /*salt*/, 32 /*hash*/, 1, 19456, 2)`. It reads/writes the PHC `$argon2id$v=19$m=19456,t=2,p=1$...` format, so existing hashes verify (check the salt/hash lengths match: node-rs defaults are salt 16 and hash 32). Make the token consumption atomic (`UPDATE ... WHERE used_at IS NULL`). |

### 2.2 People — `src/app/(app)/people/actions.ts` (7)

Row-level rules (`src/lib/people-rules.ts`, actor from `loadActor`: assignable role ids from `role_assignable_roles`, own department, id of the `employee` role):
- **canEdit**: needs `people.add`; never self; never a super admin (`users.is_super_admin`); Admin (role key `admin`) edits anyone, otherwise the target's role must be in the actor's assignable set.
- **canToggleAccess**: needs `access.toggle`; never self, super admin or EXITED; only Admin toggles an Admin.
- **canAddEmployees**: `people.add` and (Admin or assignable non-empty).
- **canAssignRole**: keeping the current role is OK, or the role is in the assignable set. Admin may also assign any role except `admin`.
- **lockedDepartment**: non-Admin actors who can't assign the `employee` role are locked to their own department.
- **createsCycle**: walks the `reporting_manager_id` chain (limit 10,000).

Common revalidate: `/people /directory /team /departments`.

| Action | Input | Output / messages | Perms & rules | DB / side effects | Spring |
|---|---|---|---|---|---|
| `getProfile(id)` | `id` uuid | `data: Profile {id, code, name, father, email, phone(masked), address(masked "•••••, {last part}" / "On file" / "—"), pan/aadhaar/uan (masked; "—" if not India), designation, department, manager, reports[], doj, exit, location label, india, shift label, role, status, access, invitePending (= no password hash), gross (paise, **only with `struct.manage`, else null**), canEdit (canEdit && not EXITED), canToggle}`. Error: `"That employee no longer exists."` | `people.view`. HR must never get salary: `gross` is computed only if the actor has `struct.manage`. | R `employees` (+department, manager, role, shift, user, non-exited directReports), `salary_structures` (latest with effective_from ≤ today IST; gross = basic+hra+lta+special; 0 if none). **Decrypts `address_enc`** (AES-256-GCM `v1.iv.tag.ct` base64url, key `PII_ENCRYPTION_KEY`) only to mask it. | `GET /api/people/{id}` → `PeopleController` / `EmployeeProfileService`. Use a separate DTO, or `@JsonView`, so `gross` is never serialised without `struct.manage`. |
| `revealContact(id)` | `id` uuid | `data:{phone, address}` (decrypted, "—" if empty). Error: `"You can't view this person's contact details."` | `people.add` + canEdit(target) | R `employees.phone_enc/address_enc`. Audit `employee.pii_reveal` `{fields:["phone","address"]}` on **every** reveal. | `POST /api/people/{id}/contact/reveal` (POST because it is audited; no caching). `PeopleController` / `EmployeePiiService` (`PiiCrypto` AES/GCM/NoPadding, 12-byte IV, 128-bit tag) |
| `toggleAccess(id, on)` | `id` uuid, `on` boolean | msg `"Portal access enabled for {name}"` / `"{name} can no longer sign in"`. Error `"You can't change portal access for this person."` | `access.toggle` + canToggleAccess | **Transaction**: `employees.portal_access`; if off, revoke all sessions (`access_disabled`); audit `employee.access_toggle` (before/after portalAccess). | `PUT /api/people/{id}/access` `{on}` → `PeopleController` / `EmployeeAccessService` |
| `markExited(id, exitDate)` | `id` uuid, `exitDate` YYYY-MM-DD (else `"Pick the last working day."`) | msg `"{name} marked as a past employee · access removed"`. Errors: `"You can't change this person's record."`; `"Already a past employee."`; `"The last working day can't be before the joining date."`; `"{name} still has {n} direct report(s). Assign them a new manager first."` (exact pluralisation: `report` / `reports`) | `people.add` + canEdit | **Transaction**: employee status=EXITED, exit_date, portal_access=false; `departments.head_employee_id=null` where they are head; revoke sessions (`exited`); audit `employee.exit`. | `POST /api/people/{id}/exit` `{exitDate}` → `PeopleController` / `EmployeeLifecycleService` |
| `resendInvite(id)` | `id` uuid | msg `"Invite sent to their official email"`. Errors: `"You can't send an invite for this person."` (also when EXITED); `"This person has already set a password."` | `people.add` + canEdit + not EXITED | Invalidates older INVITE tokens and creates a new one (TTL `inviteTtlHours` 72, link `${APP_URL}/invite/{token}`); email → outbox; audit `employee.invite_sent`. | `POST /api/people/{id}/invite` → `PeopleController` / `InviteService` |
| `getFormOptions(editId?)` | optional uuid | `data: FormOptions {nextCode, departments[{id,name,headId}], lockedDepartmentId, managers[{id,name,title}], roles[{id,name,perms(count)}], defaults: EmployeeInput, editing: {code,name,last4{phone,pan,aadhaar,uan},hasAddress,hadManager} | null}`. Errors: `"You can't edit this person."`; `"You can't add employees."` | `people.add`; edit → canEdit and not EXITED; add → canAddEmployees. Roles filtered by canAssignRole, Admin (non-admin roles), or the current role. | R departments (non-archived, locked filter), non-exited employees (excluding self on edit), roles + perm counts, shifts. `nextCode` = `JC-` + (max numeric suffix + 1), zero-padded to 3 — **preview only, not locked**. New-hire defaults: first department not HR/Finance; manager = dept head (or actor if dept-locked); doj = today IST; shift IN_NIGHT; role = `employee` if allowed. PII is blank on edit (blank = keep). | `GET /api/people/form-options?editId=` → `PeopleController` / `EmployeeFormService` |
| `saveEmployee(input, editId?)` | `EmployeeInput` (employee-schema.ts): `fullName` 2–120 "Full name is required"; `fatherName` 2–120 "Father’s name is required"; `email` lowercased, ≤200, "Enter a valid email"; `phone` ≤30; `location` ∈ IN_IST/US_ET/US_CT/US_PT; `pan` upper ≤10; `aadhaar` ≤20; `uan` ≤20; `address` ≤500; `designation` 2–120 "Designation is required"; `departmentId` uuid "Pick a department"; `managerId` uuid or ""; `doj` "Pick the date of joining"; `shift` IN_NIGHT/IN_DAY/US_DAY; `roleId` uuid "Pick a role". Then `stepErrors` steps 1→3, first message thrown: "Enter a valid phone number" (10–15 digits; required on add, optional on edit), India only: "PAN looks like ABCDE1234F" (`^[A-Z]{5}[0-9]{4}[A-Z]$`), "Aadhaar has 12 digits" (`^[2-9][0-9]{11}$`), "UAN has 12 digits"; "Pick a reporting manager" (required on add, or on edit if they had one). | `data:{id, code}`. msg edit: `"Saved changes for {fullName}"`; add: `"{fullName} added as {code} · {roleName}[ · salary structure pending with Finance] · invite sent"`. Errors: `"You can't edit this person."`, `"You can't add employees."`, `"Pick a role."`, `"You can't give people the {role} role."`, `"Pick a department."` (missing/archived), `"You can add people to your own department only."`, `"Pick a current employee as reporting manager."`, `"Someone can't report to themselves."`, `"That manager already reports (directly or indirectly) to this person."`, `"{code} already uses that email."`; P2002 → `"That already exists."` | `people.add` + canEdit / canAddEmployees + canAssignRole (or Admin & non-admin role) + lockedDepartment + cycle check (edit only). | Shift: India → `US_DAY` coerced to `IN_NIGHT`; US → always `US_DAY`. PII: each non-blank field is encrypted (`*_enc`) and `*_last4` (last 4 alphanumerics) set; Aadhaar/UAN digits-only; switching to US **nulls** PAN/Aadhaar/UAN. **Edit (transaction)**: update employee; status recomputed only if ONBOARDING/PROBATION (`doj > today IST` → ONBOARDING else PROBATION); audit `employee.edit` (before/after + `piiChanged` names). **Add (transaction)**: `SELECT pg_advisory_xact_lock(74201)` → compute next `JC-NNN` with the regex query → create employee (portal_access=true, created_by) → create `users` row (no password) → audit `employee.create` (no ciphertext; `piiProvided`) → if India: notifications to every non-exited, access-enabled holder of `struct.manage`: `"{fullName} ({code}) joined — salary structure pending"` link `/structures`. **After commit**: `sendInvite` (token + email). | `POST /api/people` (create) and `PUT /api/people/{id}` (edit) → `PeopleController` / `EmployeeService`. Keep the advisory lock via native query `SELECT pg_advisory_xact_lock(74201)` inside `@Transactional`, or use a DB sequence. Send the invite in `AFTER_COMMIT`. Bean Validation + a custom `EmployeeStepValidator` to reproduce messages and order. |

### 2.3 Roles — `src/app/(app)/roles/actions.ts` (4)

All need `roles.manage`. Revalidate `/roles /settings`. `editableRole` errors: `"That role no longer exists."`, `"{role} has full access and can't be changed."` (`roles.is_locked`).

| Action | Input | Output / messages | Rules | DB / side effects | Spring |
|---|---|---|---|---|---|
| `setPermission(roleId, perm, on)` | uuid, permission key (one of 16), bool | msg `"{role}: granted “{label}”"` / `"…removed “{label}”"`. `"Unknown permission."` | role must be editable (not locked). **No guard stops HR roles getting salary permissions** (SALARY_PERMISSIONS is defined but not enforced here). | `role_permissions` upsert/deleteMany (no transaction); audit `role.permission_change` {permissions sorted before/after}. | `PUT /api/roles/{roleId}/permissions/{perm}` `{on}` → `RoleController` / `RoleService` |
| `setAssignable(roleId, assignableRoleId, on)` | uuid, uuid, bool | msg `"{role} can now add {target}s"` / `"… can no longer add {target}s"`; `"That role no longer exists."` | source role editable | `role_assignable_roles` upsert/delete; audit `role.assignable_change` `{[targetName]: !on}` → `{[targetName]: on}`. | `PUT /api/roles/{roleId}/assignable/{assignableRoleId}` `{on}` |
| `createRole({name, fromRoleId})` | name trim 2–60 ("Role name is required." / "Keep the name under 60 characters."), fromRoleId uuid | `data:{id}`, msg `"Role “{name}” created — adjust its permissions"`. `"A role with that name already exists."` (case-insensitive), `"Pick a role to start from."` | copies the source permissions **except `roles.manage`** | key = `custom_{slug≤30}_{Date.now() base36}`; **transaction**: role (is_builtin=false) + perms; audit `role.create` {name, copiedFrom, permissions}. | `POST /api/roles` |
| `deleteRole(roleId)` | uuid | msg `"Role “{name}” deleted"`. `"That role no longer exists."`, `"Built-in roles can't be deleted."`, `"Move everyone (including past employees) to another role first."` | not builtin; zero employees (incl. EXITED) | **Transaction**: delete role (cascade role_permissions / assignable); audit `role.delete`. | `DELETE /api/roles/{roleId}` |

### 2.4 Departments — `departments/actions.ts` (2)

| Action | Input | Output / messages | Perms | DB / side effects | Spring |
|---|---|---|---|---|---|
| `createDepartment({name, headId?, description?})` | name trim 2–80 ("Department name is required." / "Keep the name under 80 characters."), headId uuid or "", description ≤300 ("Keep the description under 300 characters.") | msg `"Department “{name}” created"`. `"This department already exists."`, `"Pick a current employee as head."` | `dept.manage` | Case-insensitive name match. If an **archived** match exists it is **restored** (archived_at=null; head, description, created_by and **created_at reset**) → audit `department.restore`, else create → `department.create`. Transaction. Revalidate `/departments`. | `POST /api/departments` → `DepartmentController` / `DepartmentService` |
| `deleteDepartment(id)` | uuid | msg `"Department “{name}” removed"`. `"That department no longer exists."`, `"{name} still has {n} current employee(s). Move them first."` (`employee`/`employees`) | `dept.manage` | If past employees reference it → **archive** (archived_at, head=null), else hard delete. Audit `department.delete` (after `{archived:true}` when archived). Transaction. | `DELETE /api/departments/{id}` |

### 2.5 Holidays — `holidays/actions.ts` (2)

Revalidate `/holidays /home /attendance /register /settings /team`.

| Action | Input | Output / messages | Perms & rules | DB / side effects | Spring |
|---|---|---|---|---|---|
| `addHoliday({date, name, region})` | date YYYY-MM-DD "Pick a date", name 2–80 "Enter the holiday name", region IN/US/ALL | msg `"{name} added for {everyone|India team|US team} · {fmtDL(date)}"`. `"That month's attendance is already locked."`, `"There's already a holiday on this date for this team ({existing})."` | `holidays.manage`; month not locked (`isMonthLocked` = after lockDayOfNextMonth/lockTime IST); clash: ALL vs any region, region vs same or ALL. | create `holidays`; audit `holiday.create` (after = input) — **not in one transaction**. | `POST /api/holidays` → `HolidayController` / `HolidayService` |
| `deleteHoliday(id)` | uuid | msg `"{name} removed from the calendar"`. `"That holiday no longer exists."`, `"Past holidays can't be deleted."` (date < today IST) | `holidays.manage` | delete; audit `holiday.delete`. | `DELETE /api/holidays/{id}` |

### 2.6 Attendance — `attendance/actions.ts` (2) (+ `punch` under §2.15)

| Action | Input | Output / messages | Perms & rules | DB / side effects | Spring |
|---|---|---|---|---|---|
| `requestRegularization({date, kind, proposedIn, proposedOut, reason})` | date YYYY-MM-DD; kind ABSENT / LATE_CHECKIN / MISSED_CHECKOUT / HOLIDAY_WORK; proposedIn/Out `HH:MM` or ""; reason ≤500 ("Keep the reason under 500 characters.") | msg `"Regularization sent — HR, Finance or Admin can approve it"`. Errors: `"Attendance isn't tracked for your account."`, `"You can't regularize a future day."`, `"Attendance for this month is locked. Ask Finance to adjust LOP in payroll."`, per kind: `"This day isn't marked as loss of pay."`, `"There's no late mark on this day."`, `"There's no missing check-out on this day."`, `"You need 4h+ of work on a holiday or weekly off for a comp-off."`; `"You already have a pending request for this day."` / `"This day is already regularized."`; `"Enter the time you actually checked out."` | `self`; date ≤ current work date (shift-aware); fit check uses the attendance engine for that day (ABSENT: lopDays>0 and not LEAVE; MISSED_CHECKOUT: missedCheckout, or punches present & not live & no lastOut). | Proposed times are local to the employee tz → UTC instants. Out ≤ in (or ≤ firstIn) rolls to the next day; with no anchor, `hm < 12:00` on a night shift (start ≥ 12:00) rolls to the next day. Create `regularization_requests` (status PENDING); audit `regularization.request`. Revalidate `/attendance`. | `POST /api/attendance/regularizations` → `RegularizationController` / `RegularizationService` (+ ported `AttendanceEngine`) |
| `cancelRegularization(id)` | uuid | msg `"Request withdrawn"`. `"Request not found."` (also when not own), `"Only pending requests can be withdrawn."` | `self`, own only | update status=CANCELLED (**read-then-update, not atomic** — unlike cancelLeave); audit `regularization.cancel` (no IP). | `POST /api/attendance/regularizations/{id}/cancel` (or `DELETE`). Make it `UPDATE ... WHERE id=? AND employee_id=? AND status='PENDING'`. |

### 2.7 Leave — `leave/actions.ts` (3)

Input schema `ApplyInput`: `type` string 1–10 (leave_types.code), `from`/`to` YYYY-MM-DD ("Pick your dates on the calendar."), `part` FULL/FIRST_HALF/SECOND_HALF, `reason` ≤500 ("Keep the reason under 500 characters."), default "". `me()` error: `"Leave isn't tracked for your account."`

`previewRequest` rules and messages (shared with decideLeave). from/to are swapped if reversed. Messages: `"Pick a leave type."` (missing/inactive); `"Leave can't start in the past. For a missed day, request a regularization."` (skipped when deciding); `"Leave can't start before your joining date."`; `"Leave can't go beyond your last working day."`; `"Apply for at most two months at a time."` (>62 days); `"Those dates are all weekends or holidays — nothing to apply for."`; `"{type} can be taken for at most {n} days at a stretch."`; `"Attendance for that month is locked."`; `"You already have a {pending|approved} request from {from} to {to}."` Balance: available = balance − pending held (excluding the request being decided), then the split into paid/LOP.

| Action | Output | Perms | DB / side effects | Spring |
|---|---|---|---|---|
| `preview(i)` | `data: Preview {from,to,dates[],skipped[{date,name}],days,available,paidDays,lopDays,balanceAfter|null,error|null}`. **Business errors come back inside `data.error` with ok:true.** | `self` | R leave_types, holidays, leave_ledger/balances, leave_requests, settings | `POST /api/leave/requests/preview` (or `GET` with query) → `LeaveController` / `LeaveService.preview` |
| `applyLeave(i)` | msg `"{typeName} for {days} day(s) ({span}) sent to HR[ · {lop} day(s) unpaid]"` (`day`/`days`; span `fmtD(from)` or `from – to`). Errors: preview error text. | `self` | part forced to FULL if more than one date. Create `leave_requests` (days, lop_days Decimal); audit `leave.apply`. **No transaction / overlap lock** — two concurrent applies can both pass the overlap check. Revalidate `/leave /approvals /home`. | `POST /api/leave/requests`. Consider `SELECT ... FOR UPDATE` on the employee row or an advisory lock per employee. |
| `cancelLeave(id)` | msg `"Leave request cancelled"`; `"Only your pending requests can be cancelled."` | `self`, own | **Atomic** `UPDATE leave_requests SET status='CANCELLED' WHERE id AND employee_id=me AND status='PENDING'`; count 0 → error. Audit `leave.cancel` (no IP). | `POST /api/leave/requests/{id}/cancel` |

### 2.8 Approvals — `approvals/actions.ts` (2)

Input for both: `{id: uuid, approve: boolean, note?: string ≤300 trimmed}`. Revalidate `/approvals /leave /attendance /home /register /team`. Both use **first decision wins**: an atomic `updateMany WHERE id AND status='PENDING'` inside the transaction. Count 0 → `"Someone else decided this request a moment ago."` rolls the transaction back. `notifyDecision` (bell + email via outbox) runs **after commit, outside the transaction**.

| Action | Output / messages | Perms & rules | DB / side effects | Spring |
|---|---|---|---|---|
| `decideLeave(i)` | msg `"{Approved|Declined} {employee}’s {type lower}"` (curly ’). Errors: `"Request not found."`, `"You can't decide your own leave."`, `"This request was already decided."`, + previewRequest errors on approve (re-run with `forDecision`, own request excluded). | `appr.leave`; not own | Approve: recompute paidDays/lopDays (balance may have changed); **transaction**: status APPROVED/DECLINED, decided_by/at, lop_days; `postUsage` → `leave_ledger` row `delta=-paidDays, reason USED, ref_id` (only if type needsBalance && paid && paidDays>0); audit `leave.approve` / `leave.decline` {paidDays, lopDays, note}. Bell `"{type} ({span}) approved|declined by {actorName}"` link `/leave`; email subject `"{what} approved|declined"`. Note: the decision note is **not stored on the request**, only in audit and email. | `POST /api/approvals/leave/{id}/decision` `{approve, note}` → `ApprovalController` / `LeaveApprovalService` |
| `decideRegularization(i)` | msg `"{Approved|Declined} {employee}’s {kind label lower}[ · {n} comp-off credited]"`. Kind labels: "Absence regularization", "Late check-in regularization", "Missed check-out regularization", "Comp-off request". Errors: `"Request not found."`, `"You can't decide your own request."`, `"This request was already decided."`, `"That month's attendance is locked — decline this and adjust LOP in payroll instead."` (approve only), `"Less than a half day was worked — no comp-off is due."`, `"The request has no check-out time."` | `appr.reg` (HR, Finance and Admin all get it; the first decision is final); not own | **Transaction**: status, decided_by/at, `decided_role = actor role name`; MISSED_CHECKOUT approve → `punches` (IN at proposed_in if set, OUT at proposed_out, source REGULARIZATION); HOLIDAY_WORK → `leave_ledger` CO credit `delta=compOff` (0.5/1 from worked minutes vs half/full-day hours), reason `COMP_OFF_CREDIT`, `expires_on = date + leave_types[CO].expiry_days ?? 60`, note "Worked on {date}"; audit `regularization.approve` / `.decline`. Bell + email: `"{kind} for {fmtD(date)}"` by `"{name} ({role})"`, link `/attendance?m=YYYY-MM&d=YYYY-MM-DD`. (ABSENT/LATE approvals have no row writes: the engine reads APPROVED requests.) | `POST /api/approvals/regularizations/{id}/decision` → `ApprovalController` / `RegularizationApprovalService` |

### 2.9 Settings — `settings/actions.ts` (1)

| Action | Input | Output / messages | Perms | DB / side effects | Spring |
|---|---|---|---|---|---|
| `updateAttendancePolicy(input)` | `fullDayHours` 1–14, `halfDayHours` 0.5–12, `graceMinutes` int 0–120, `lateMarksPerHalfDayLop` int 1–31, `weeklyOffs` int[0..6] max 6, `absenceJobBufferMinutes` int 0–720, `lockDayOfNextMonth` int 1–28, `lockTime` `HH:MM`, `trackingStartDate` YYYY-MM-DD; refine `"Half-day hours must be less than full-day hours."` | msg `"Attendance policy saved"`. `"The go-live date can't change once a month has been locked."` (when `job_runs` has any `attendance.lock`) | `roles.manage` | **Transaction**: `settings` upsert key `attendance.policy` (merged over the stored value so `shiftTargetHours` is kept); **`shifts.grace_minutes` updated for ALL shifts**; audit `policy.attendance_change` (before/after). Revalidate `/settings /home /attendance /register /team`. | `PUT /api/settings/attendance-policy` → `SettingsController` / `AttendancePolicyService`. JSONB column → `@JdbcTypeCode(SqlTypes.JSON)`. |

### 2.10 Structures — `structures/actions.ts` (1)

| Action | Input | Output / messages | Perms | DB / side effects | Spring |
|---|---|---|---|---|---|
| `addStructure(i)` | `employeeId` uuid, `effectiveFrom` YYYY-MM-DD "Pick the effective date", `reason` JOINING/APPRAISAL/PROMOTION/CORRECTION, `basic` int 1..1e8 "Basic is required", `hra`/`lta`/`special` int 0..1e8 — **rupees** | msg `"Salary structure saved for {name} · effective {fmtDY}"`. Errors: `"Employee not found."`, `"Salary structures are for the India payroll only."`, `"This person is a past employee."`, `"The structure can't start before the joining date."`, `"Payroll is closed up to {Mon YYYY}. Start the revision after that and pay any difference as arrears."`, `"There's already a revision effective {fmtDY}. History can't be edited — pick another date."` | `struct.manage` | ×100 → paise into **`Int` (int4)** columns. **Bug: max 1e8 rupees ×100 = 1e10 overflows int4** → generic 500. Use `long`/`bigint`, or cap at about ₹2.1 crore/month. **Transaction**: insert `salary_structures` (append-only); audit `salary.structure_change` (before = previous revision); marks unread notifications read where text starts with `{fullName}` and contains `"salary structure pending"` (string-match coupling). Revalidate `/structures /payroll /tax /people`. | `POST /api/salary-structures` (or `POST /api/people/{id}/salary-structures`) → `SalaryStructureController` / `SalaryStructureService` |

### 2.11 Payroll — `payroll/actions.ts` (3)

`openRun(month)`: `"{Mon YYYY} payroll is closed. Corrections go into the next month as arrears."` if CLOSED, otherwise returns the run or **creates the `payroll_runs` row lazily**. Revalidate `/payroll /payslips /home /overview /tax`. `month` = `YYYY-MM`.

| Action | Input | Output / messages | Perms | DB / side effects | Spring |
|---|---|---|---|---|---|
| `saveAdjustment(i)` | `month`, `employeeId` uuid, `lopDays` 0–31 multipleOf 0.5 **nullable** (null = use attendance), `bonus` int 0–1e8 ₹, `otherDeduction` int 0–1e8 ₹, `note` ≤200 | msg `"Saved payroll changes for {name}"`. `"Only India payroll employees can be edited."` | `pay.view` | **Transaction**: upsert `payroll_adjustments` (PK run_id+employee_id; ×100 paise into int4, same overflow risk); set run `stage=REVIEW`, clear approved_at/by; audit `payroll.edit` entityId `"{month}:{employeeId}"` (lopDays Decimal→Number). | `PUT /api/payroll/runs/{month}/adjustments/{employeeId}` → `PayrollController` / `PayrollAdjustmentService` |
| `resetAdjustment(month, employeeId)` | ym, uuid | msg `"Back to attendance figures"` (even if nothing to delete) | `pay.view` | deleteMany adjustment; audit `payroll.edit` `{reset:true}` only if a row was deleted (no IP, **no transaction, stage not reset to REVIEW** — inconsistent with save). | `DELETE /api/payroll/runs/{month}/adjustments/{employeeId}` |
| `approveAndClose(month)` | ym | msg `"{Mon YYYY} payroll closed — {n} payslips published"`. Errors: `"Attendance for this month locks on the 1st of next month at 10:00 AM. Close after that."` (hard-coded text even if the policy differs), `"Already closed."`, closeRun errors re-thrown as UserError: `"Attendance for this month isn't locked yet."`, `"This payroll is already closed."` | `pay.close` | (1) run created if missing; `stage=APPROVED`, approved_by/at; audit `payroll.approve` — **committed separately, not rolled back if close fails**. (2) `closeRun`: computeRun → for each row build a snapshot, **write PDF to `{UPLOAD_DIR}/payslips/{YYYY-MM}/{code}.pdf` before the DB transaction** (orphan files on failure; reruns overwrite). **Transaction**: atomic `UPDATE payroll_runs SET status='CLOSED',stage='CLOSED',... WHERE id AND status='OPEN'` (count 0 → "This payroll is already closed."); insert `payslips` (snapshot JSON, pdf_path relative to cwd); audit `payroll.close` {month, payslips, net}; notifications `"Your {Mon YYYY} payslip is ready"` → `/payslips`. **After commit**: one email per employee (sequential, outbox). | `POST /api/payroll/runs/{month}/close` → `PayrollController` / `PayrollCloseService` (+ `PayslipPdfRenderer`, `FileStorage`). Consider an async job + 202 for large runs. |

### 2.12 Declarations — `declare/actions.ts` (4)

`me()`: `assertPerm("self")`; `"Tax declarations are for the India payroll."` if region≠IN; reads `tax.policy`; **upserts `tax_profiles` (employee, fy)** as a side effect of every call. `windowOpen`: `"Declarations open on {fmtDY(from)}."` / `"Declarations closed on {fmtDY(until)}. Ask Finance for changes."` (today IST vs `proofWindowFrom/Until`). Revalidate `/declare /proofs /tax`.

| Action | Input | Output / messages | Rules | DB / side effects | Spring |
|---|---|---|---|---|---|
| `setRegime(regime)` | `NEW`/`OLD` | `{ok:true}` with no msg if unchanged; else `"You're on the {new|old} regime — TDS updates from the next payroll"`. `"Finance has set your regime. Contact Finance to change it."`, `"The regime could be changed until {fmtDY}."` | not `regime_overridden`; today ≤ `regimeChangeUntil` (no window check) | update `tax_profiles.regime`; audit `tax.regime_change` {by:"employee"}. | `PUT /api/me/tax/regime` `{regime}` → `DeclarationController` / `TaxDeclarationService` |
| `saveProfile(i)` | `metro` bool, `parentsSenior` bool, `landlordPan` upper ≤10 | `{ok:true}` (no msg). `"Landlord PAN looks like ABCDE1234F."` | window open | update tax_profiles (metro, parents_senior, landlord_pan or null). No audit. | `PUT /api/me/tax/profile` |
| `saveItem(i)` | `key` ∈ DECLARABLE ("Unknown item"), `amount` int ("Whole rupees only") 0–1e8 ₹ | `{ok:true}`. If it was APPROVED: msg `"{label} changed — back to draft until you resubmit"`. `"This item is waiting for Finance — you can change it after they decide."` | window open; unchanged amount → no-op; PENDING locked | `amount = BigInt(₹×100)`; upsert `declaration_items` (unique employee+fy+item_key; section from catalogue) → status DRAFT, decision fields cleared. Audit `declaration.reopened` **only if it was APPROVED** (no IP). | `PUT /api/me/tax/declarations/{itemKey}` `{amount}` |
| `submitForVerification()` | none | msg `"{n} item(s) sent to Finance for verification"`. `"Declarations only matter on the old regime."`, `"Nothing new to submit — enter an amount and attach its proof first."`, `"Attach a proof for: {labels, comma-joined}."`, `"Rent is above ₹1,00,000 a year — add your landlord's PAN."` (rent > 1,00,00,000 paise) | window open; regime OLD; every DRAFT with amount>0 needs `proof_path` (uploaded via route `POST /api/declarations/proof`, out of scope) | updateMany status=PENDING by ids (no status guard, no transaction); audit `declaration.submit` {items}. | `POST /api/me/tax/declarations/submit` |

### 2.13 Proofs — `proofs/actions.ts` (3)

All need `proofs.verify`. Revalidate `/proofs /tax /declare /payroll`.

| Action | Input | Output / messages | Rules | DB / side effects | Spring |
|---|---|---|---|---|---|
| `proofDetail(employeeId)` | uuid | `data: ProofDetail {name, code, regime (default NEW), metro, landlordPan, items[{id,key,label,sec,amount(paise Number),status,proofName,hasProof,reason}], tdsNow, tdsIfAll (monthly TDS on the OLD regime, approved vs submitted declarations; null if no structure), mine}`. Missing employee → `findUniqueOrThrow` → generic 500 (should be 404). | — (viewing your own is allowed; `mine` flag) | R employees, tax_profiles, declaration_items (amount>0 or has proof), taxPositions/computeRun (open payroll month) + reproject. | `GET /api/proofs/{employeeId}` → `ProofController` / `ProofVerificationService` |
| `decideProof({id, approve, reason?})` | uuid, bool, reason ≤300 | msg `"{label} approved|rejected"`. `"Item not found."`, `"You can't verify your own proofs."`, `"Give a reason so the employee can fix it."` (reject without reason), `"This item was already decided."` | not own; **atomic** `UPDATE declaration_items ... WHERE id AND status='PENDING'` | status, decided_by/at, reject_reason; audit `proof.decision` and notification `"{label} proof approved"` / `"{label} proof rejected — {reason}"` → `/declare` — **audit and notify are outside any transaction**. | `POST /api/proofs/items/{id}/decision` |
| `approveAllPending(employeeId)` | uuid | msg `"{n} proof(s) approved"`. `"You can't verify your own proofs."`, `"Nothing pending."` | not own; current FY | **Transaction**: updateMany ids AND status PENDING → APPROVED; audit `proof.decision` {approved[]} entityId `"{emp}:{fy}"`. After: notification `"{n} investment proof(s) approved — your TDS goes down from the next payroll"`. (n = count read before the update, not the rows actually updated.) | `POST /api/proofs/{employeeId}/approve-all` |

### 2.14 Tax — `tax/actions.ts` (3)

All need `tax.view`. Revalidate `/tax /payroll /declare /proofs`.

| Action | Input | Output / messages | DB / side effects | Spring |
|---|---|---|---|---|
| `taxDetail(employeeId)` | uuid | `data:{month, tax: TaxProjection, name, code, title}`. `"No salary structure — nothing to compute yet."` | read-only; computeRun for the open payroll month | `GET /api/tax/{employeeId}` → `TaxController` / `TaxProjectionService` |
| `overrideRegime(employeeId, regime)` | uuid, NEW/OLD | msg `"Regime set to {new|old} — TDS updates from this payroll"` | upsert tax_profiles `regime`, `regime_overridden=true` (no existence/region check; a bad id → FK error → 500); audit `tax.regime_change` {by:"finance_override"}; if changed, notify `"Finance set your tax regime to the {new|old} regime"` → `/declare`. Not transactional. | `PUT /api/tax/{employeeId}/regime` |
| `setPreviousEmployer({employeeId, income, tds})` | uuid, ints 0..1e9 ₹ | msg `"Previous employer figures saved"`. `"TDS can't be more than the income."` | upsert `prev_employer_income/tds` as **BigInt paise**; audit `tax.previous_employer` (Number values). | `PUT /api/tax/{employeeId}/previous-employer` |

### 2.15 Home / Notifications — `home/actions.ts`, `notification-actions.ts` (2)

| Action | Input | Output / messages | Perms & rules | DB / side effects | Spring |
|---|---|---|---|---|---|
| `punch(expect)` (home/actions.ts) | `"IN"`/`"OUT"` = what the button showed (guards against double-clicks and stale tabs) | `data:{kind}`. IN msg `"Checked in. Have a productive shift."`; OUT msg `"Checked out — {fmtH(hours)} recorded today"` (worked minutes over punches in the same work date, looking back 40 h). Errors: `"Attendance isn't tracked for your account."`, `"You can check in from your joining date."` (today in the employee tz < doj), `"You're already checked in."`, `"You're not checked in."`, `"Just recorded — give it a few seconds."` (<30 s since the last punch) | `self`. Current state = kind of the last punch ever. | insert `punches` (source WEB, ip). **No audit. No lock**, so concurrent double-submits within ms can both pass. Revalidate `/home /attendance`. | `POST /api/attendance/punches` `{expect}` → `AttendanceController` / `PunchService`. Add `SELECT ... FOR UPDATE` on the employee row or an advisory lock per employee. |
| `markNotificationsRead()` | none | **returns `void`** (not ActionResult); silently no-op if signed out | any signed-in user (no assertPerm) | `UPDATE notifications SET read_at=now WHERE employee_id=me AND read_at IS NULL`. | `POST /api/me/notifications/read-all` → 204. `NotificationController` / `NotificationService` |

### 2.16 Count summary

| Module | Actions |
|---|---|
| Auth | 4 |
| People | 7 |
| Roles | 4 |
| Departments | 2 |
| Holidays | 2 |
| Attendance | 2 |
| Leave | 3 |
| Approvals | 2 |
| Settings | 1 |
| Structures | 1 |
| Payroll | 3 |
| Declarations | 4 |
| Proofs | 3 |
| Tax | 3 |
| Home/Notifications | 2 |
| **Total** | **43** |

### 2.17 Tricky behaviours / defects to decide on during the port

1. **Atomic first-decision-wins** (`UPDATE ... WHERE status='PENDING'` + count check): decideLeave, decideRegularization, cancelLeave, decideProof, approveAllPending, closeRun. Port as JPQL/native `@Modifying` updates that return the row count, **not** as load-modify-save with JPA (or add `@Version` optimistic locking).
2. **Not atomic** today (fix or replicate knowingly): cancelRegularization, submitForVerification, setPasswordAction token consumption, applyLeave overlap check, punch.
3. **Advisory lock** `pg_advisory_xact_lock(74201)` serialises employee-code allocation in saveEmployee.
4. **Int overflow**: structures/adjustments accept up to ₹1e8 but store paise in int4. Values above about ₹2.147 crore fail with a 500.
5. **Partial commits**: approveAndClose commits APPROVED + audit before closeRun; closeRun writes PDFs to disk before its transaction. Emails/notifyDecision/sendInvite run after commit and never fail the action.
6. **Rate limiting is in-memory per process** (login IP and forgot). It must move to Redis/Postgres for multi-instance Spring.
7. **Business errors inside a success body**: `preview` returns `ok:true` with `data.error`.
8. Hard-coded copy that ignores policy: approveAndClose's "locks on the 1st of next month at 10:00 AM".
9. Audit rows without IP: cancelRegularization, cancelLeave, resetAdjustment, saveItem (reopened), createRole, deleteRole, payroll.approve, payroll.close.
10. HR salary isolation is enforced only by permission checks (`getProfile.gross`). `setPermission` does **not** stop an HR role from being granted `SALARY_PERMISSIONS`.
11. String-coupled notification cleanup in addStructure (`startsWith(fullName)` + `contains("salary structure pending")`).
12. BigInt fields (`declaration_items.amount`, `tax_profiles.prev_employer_*`) → Java `long`. Audit JSON currently stores some as Number via `Number(...)`, and any raw BigInt as a **string**.


---

Read-only audit of `feature/spring-boot-migration` @ 1e5c2ab. All paths are relative to `C:\JurisLPO\JurisOne`.

## 3. Page data reads → new GET endpoints

### 3.0 Conventions every endpoint must keep

| Topic | Rule (as implemented today) | Java equivalent |
|---|---|---|
| Auth gate (pages) | `requirePage(navId)` → `navAllowed(item, perms, hasReports)`: any one of `item.perm`, plus `needsReports` for `team`. Signed out → 302 `/login`; forbidden → 302 to `landingPath(u)` | Return **401** / **403** as JSON. The Next client redirects. Re-use one `NavAccess.allowed(navId, user)` from the ported `nav.ts` |
| Current user | `getCurrentUser()` re-validates the session on every request. It returns null if the session is revoked or expired, if `portalAccess=false`, if the employee is EXITED, or if there is no `user` row. It also calls `touchSession` | `SessionAuthFilter` → `CurrentUser {id, code, name, email, designation, roleId, roleKey, roleName, perms:Set, isSuperAdmin, region IN/US, timeZone, hasReports}` |
| List params | `listState(sp, sorts, dflt, pageSize=25, dfltDir="asc")` takes `q` (trimmed), `sort` (must be in the allowed list, else the default), `dir` (`asc`/`desc`), and `page` (clamped to 1..10000). It sets `skip=(page-1)*size` and `take=size`. Every string param is cut to 200 chars, and an array param uses its first value | `ListState.from(params, allowedSorts, dflt, size, dfltDir)`. Invalid values **fall back silently** (no 400) |
| `pick(sp,k,allowed,dflt)` | Returns the default when the value is not in the allowed set | Same. Enum params never 400 |
| UUID filters | `dept`/`actor` are applied only when they match `/^[0-9a-f-]{36}$/`. Anything else counts as "All" | Same |
| Money | Integer **paise**. Monthly figures are `Int`, annual figures and declaration amounts are `BigInt`. **Exceptions:** the tax engine and TaxProjection use **rupees** (float, rounded), and the `declare` page sends item amounts in rupees (`amount/100`) | `long` paise. Tax DTO fields stay in rupees (document per field) |
| Dates | Calendar dates are ISO `YYYY-MM-DD` strings (`@db.Date` = UTC midnight). Instants are UTC ISO strings | `LocalDate` → `"yyyy-MM-dd"`, `Instant` → ISO-8601 Z |
| Server-formatted strings | Many pages build display strings on the server, using the **viewer's or employee's tz**: `fmtTime` (en-US `h:mm AM`), `fmtDateTime` (en-GB), `fmtD`/`fmtDL`, the approval `impact`/`detail`, and the shell notes | Preferred: the API returns raw values (instant + tz, numbers) and the Next client formats them with the existing `format.ts`. Where a string is kept (notes, approval detail), port the formatter exactly |
| HR salary rule | HR roles never hold `pay.view, pay.close, struct.manage, tax.view, proofs.verify` (`SALARY_PERMISSIONS`). Pages that hide pay: `people` (`gross` only with `struct.manage`), `audit` (redacts payroll/tax payloads), `home` (never salary) | Enforce in the service layer, not with `@JsonView` only |
| PII | Only the last 4 characters are stored in clear. Masks are in `src/lib/mask.ts`. Address is AES-encrypted and masked to `•••••, <last part>` | Port `mask.ts` + `crypto.ts`. Never serialise `*Enc` columns |

### 3.1 Page-by-page

Legend for **Perm**: this is the `requirePage` nav id → the permissions from `nav.ts` (any one is enough).

| # | Route (file) | Nav id → perm | URL params (allowed values · default · page size) | Data computed → JSON shape | lib services used | Proposed endpoint → controller / service |
|---|---|---|---|---|---|---|
| 0 | `/` (`src/app/page.tsx`) | signed-in | — | Redirects to `landingPath(u)` or `/login` | `getCurrentUser`, `landingPath` | **GET /api/auth/me** → `AuthController.me` / `AuthService.currentUser` → `{user:CurrentUser, landing:"/home"\|"/overview"\|"/<first nav>"\|"/no-access"}` (401 if signed out) |
| 1 | `(app)/layout.tsx` shell | `requireUser` only | cookie `jo_theme` (light/dark), which stays client-side | `me {code,name,designation,roleName,department\|"—",manager\|"—",doj ISO,shift: shiftLabel()\|null,isAdminLike(!self)}`, `groups [{g:NavGroup, items[{id,label,icon}]}]` (group = `n.group ?? (isAdminLike?"Workspace":"My workspace")`), `badges {approvals: pendingLeave+pendingReg, proofs}` (all counts exclude your own requests and depend on perms), `notes [{t,s,href?}]` (the approvals and proofs notes, then up to 8 notifications from the last 14 days with `s=fmtDateTime(createdAt,u.tz)`, then the next holiday for region IN/US+ALL), `unread` (count of `readAt==null`), `landing`, `canSelf` | `requireUser`, `shellData`, `allowedNav`, `shiftLabel`, `landingPath`, `todayIn`, `fmtDL` | **GET /api/me/shell** → `ShellController` / `ShellService.shellData(u)` |
| 2 | `/home` | home → `self` | none (the `?apply=1` link goes to `/leave`) | If the employee is not tracked (no `self` in role/shift): greeting only. Otherwise: **TimeCard** `{on:boolean (last punch IN), openSince ISO\|null, closedMinutes (workedMinutes of today's closed pairs), shiftText "Night shift · 7:30 PM – 4:30 AM IST", ticks[4] (start→end split into 3 equal intervals, as fmtTime strings), targetHours=policy.shiftTargetHours, log[{t,l:"Checked in/out",d:"Web/Mobile/Regularization"}]}`. **KPIs:** month = days from `monthStart` to the work date (`wd`); present = PRESENT/HALF_DAY, or today with punches; `lastShifts` = last 7 days ≤ wd with work or = wd, excluding HOLIDAY/WEEKLY_OFF; `avgH` over the finished ones; `spark` SVG path (120×28); `leaveAvail` = Σ ledger delta for CL+EL; `nextIn` = daysBetween(wd, next holiday). **Heatmap** of 112 cells (16 weeks, Monday first, from `monday-15w`): `{date, cls: l1<8.8h,l2<9.0,l3<9.2,l4 \| hol/abs/lv/na/"" (+" now"), title}`. `hols` (4 upcoming), `cards` = homeCards | `loadAttEmployees`, `attendancePolicy`, `currentWorkDate`, `attendanceFor`, `workedMinutes`, `atLocal`, `addDays`, `dowOf`, `fmtTime`, `upcomingHolidays`, `homeCards`, `leaveLedger.groupBy` | **GET /api/me/home** → `HomeController` / `HomeService` (+ `DashboardService.homeCards`). Suggest returning raw days and letting the client bucket the heatmap and sparkline |
| 3 | `/overview` | overview → `roles.manage` | none | `overviewData()`: `{head, india, us, accessOff, deptCount, inNow (todayStatuses label starts "In since"), pending{leave,reg,proofs} (everyone's), payroll{month,status:"Closed"\|"Approved"\|"In review",lastClosed, net paise (Σ payrollView rows), people, noStructure}, byDept[{name,n}] sorted n desc then name, +"No department"}`. The page also uses `allowedNav(u)` filtered to group≠Team for quick links, and `can(pay.view/struct.manage/tax.view)` | `overviewData`, `pendingCounts`, `payrollState`, `payrollMonths`, `payrollView` (**runs computeRun for the whole company, which is expensive**), `todayStatuses` | **GET /api/overview** → `OverviewController` / `DashboardService.overview()`. Add caching: computeRun on every hit is heavy |
| 4 | `/attendance` | attendance → `self` | `m` = `YYYY-MM`, default the current work-date month, clamped to `[max(trackingStart, doj) month, current month]`. `d` = selected day (`YYYY-MM-DD`, honoured only if it is inside `m`) | Untracked → empty state. `summary` = `summarize(non-future days)`: `{present, absent, leave, holidays, weeklyOffs, workedMinutes, lateMarks, lateLopDays, lopDays}`. `days: CalDay[] {date,status,label,late,lateWaived,hours (=min/60),today,future,firstIn/lastOut (fmtTime in emp tz)\|null,live,missedCheckout (flag \|\| punches && !live && !lastOut),offDayWork,lopDays,leaveDays,regularized}`. `regs: RegView[] {id,date,kind,status,by\|null,reason}` for the month. `initialSel`, `locked = isMonthLocked(ym)`, `prev`/`next` months (nav disabled outside the bounds), `hols` (6 upcoming) `[{id,date,name,region}]` | `loadAttEmployees`, `attendancePolicy`, `currentWorkDate`, `attendanceFor`, `summarize`, `isMonthLocked`, `upcomingHolidays`, `addDays` | **GET /api/me/attendance?m=&d=** → `MyAttendanceController` / `AttendanceService.month(u, ym)` |
| 5 | `/leave` | leave → `self` | `tab` ∈ requests\|holidays (default requests). `st` ∈ All\|PENDING\|APPROVED\|DECLINED\|CANCELLED (All). `sort` ∈ type,from,days,applied,status (**applied**). `dir` default **desc**. Page size 50 but **not paginated** (all rows, sorted in memory). `apply=1` auto-opens the form | `balances(u.id)`: `[{code,name,balance,pending,paid,needsBalance,annual,maxStretch}]`. `rings[4]`: CL avail/annual\|12, EL avail/annual\|18, CO avail/5, LOP `lopYear`/5, where avail = round2(balance−pending). `lopYear` = summarize(attendance from max(Jan 1, trackingStart, doj) to today).lopDays. `reqs[{id,type name,from,to,days (Decimal→number),part,reason,appliedAt,status,decidedBy}]`. `hols` (this year, region+ALL). `holidayMap {iso:name}` for dates ≥ today. `types[{code,name,paid,available\|null}]`. `today`, `weeklyOffs` | `balances`, `loadAttEmployees`, `attendancePolicy`, `currentWorkDate`, `attendanceFor`, `summarize`, `isoInZone` | **GET /api/me/leave?st&sort&dir** → `MyLeaveController` / `LeaveService.overview`. **GET /api/me/holidays?year=** (tab 2 + date-picker map) → `HolidayService.forEmployee` |
| 6 | `/payslips` | payslips → `self` | none | `months[] sorted desc`: `{ym, closed:false}` "Processing" entries (only if India and has a structure: open runs + previous month if it has no slip) and `{ym, closed:true, snap: PayslipSnapshot (paise), paidOn ISO\|null}`. `code = u.code` (for the PDF link). US users get an empty list | Prisma direct; `todayIn("Asia/Kolkata")`; snapshot JSON as stored | **GET /api/me/payslips** → `MyPayslipController` / `PayslipService.listMine`. The PDF already exists: `GET /api/payslips/[month]/[code]` |
| 7 | `/declare` | declare → `self` | none | Non-IN → "Not applicable". No structure → empty state. Otherwise: `{regime NEW\|OLD, overridden, canChangeRegime (todayIST ≤ regimeChangeUntil), windowOpen (proofWindowFrom ≤ today ≤ proofWindowUntil), windowText, newTax/oldTax (₹, reproject decl:"all"), monthlyTds (₹, row.tax.monthly, approved only), epf (₹ pfAnnual), profile{metro,parentsSenior,landlordPan}, items[{id,key,amount **rupees** (=paise/100),status,proofName,reason}]}`, eyebrow `fy` | `getSetting("tax.policy")`, `taxPositions([u.id])` → `openPayrollMonth` + `computeRun`, `reproject` → `projectTax` | **GET /api/me/tax-declaration** → `MyTaxController` / `TaxDeclarationService.view(u)` |
| 8 | `/directory` | directory → `dir` | `q` (fullName/email/designation, case-insensitive contains). `sort` ∈ name,email,title,st (name). `dir` asc. `page`, size **25**. `st` ∈ All\|Active\|Past (All). `dept` uuid\|All | `rows[{id,fullName,email,designation,status}]` (**no PII, no code**), `total`, `nActive` (non-EXITED count), `depts[{id,name}]` (not archived) | Prisma + `listState`/`pick`/`str` | **GET /api/directory** → `DirectoryController` / `DirectoryService.search` (Spring `Page<DirectoryRow>`) |
| 9 | `/team` | team → `self` + **needsReports** | `q` (name/code/designation, in memory). `sort` ∈ name,id,title,st (name). `dir`. **No paging** | `rows[{id,code,fullName,designation,timeZone,region,shift,st:{label,tone ok\|info\|warn\|gold\|neutral}}]`, the KPI counts derived from the labels, `days[5]` (Mon–Fri of the current week in **the manager's tz**), `away[{m, cells[5]: "hol"\|"lv"\|"pend"\|""}]` | `todayStatuses` (→ `loadAttEmployees`, `currentWorkDate`, `attendanceFor`, `fmtTime`), `listState`, `shiftLabel` | **GET /api/team** → `TeamController` / `TeamService.today(u)`. Server re-checks `hasReports` |
| 10 | `/register` | register → `att.all` | `registerParams`: `view` ∈ day\|month (day). `day` YYYY-MM-DD ≤ today IST (default today IST). `ym` ≤ current month. `tz` ∈ Asia/Kolkata, America/New_York, America/Chicago, America/Los_Angeles, UTC, `local` (default: viewer tz if in the list, else IST). `region` ∈ All\|IN\|US. `dept` uuid. `st` ∈ All, Present, Working, Not checked in, Absent, On leave, Holiday, Weekly off, Half day (day view only). Sorts: day = name,loc,st,inn,out,h; month = name,P,A,L. Size **50**, paged **in memory** (`rows.slice(skip, skip+take)`) | **Daily:** `rows[{e:AttEmployee, d:DayView, st (dayStatusLabel), inn/out: Clock{text "9:41 PM IST (+1)", local\|null}, h}]`, `sum{t,p,n,l,a}` (before the status filter), total. **Monthly:** `days[{k,d,dow letter,wk}]`, `rows[{e, cells[{c:"P"\|"½"\|"A"\|"L"\|"H"\|"–"\|"", cls}], P,A,L (summarize)}]`, plus per-cell tooltip data (label, workedMinutes). `months` = `registerMonths(trackingStart, today)` desc. `depts` options | `registerParams`, `dailyLog`, `monthlyGrid`, `registerMonths`, `dayStatusLabel`, `clock`, `cellCode`, `attendanceFor`, `summarize`, `attendancePolicy` | **GET /api/attendance/register/daily** and **GET /api/attendance/register/monthly** → `AttendanceRegisterController` / `RegisterService`. CSV export already exists: `GET /api/attendance/register` |
| 11 | `/approvals` | approvals → `appr.leave` \| `appr.reg` | `tab` ∈ leave\|att (default leave if `appr.leave`, else att). `q` = employee name contains. No sort/paging | **leaveCards** (PENDING, not mine, by fromDate asc): `{id,name,dept,type,span,days,part label,reason,impact,overlap}`. Here `available = round2(balance − pending + thisPaid)` (the request's own hold is added back), and `impact` is "Unpaid leave" \| "Balance a → b" \| "Only X available — Y day(s) become loss of pay" with Y = days − floor(max(0,avail)·2)/2. `overlap` = count of other employees' PENDING/APPROVED requests in the same department that overlap the dates. **regCards** (PENDING, not mine, by workDate asc): `{id,name,dept,kind label,date,detail,reason}`. `detail` comes from `attendanceFor(emp, date, date)` + fmtTime in **the employee's tz**. **decided** (last 30 days, ≤15 per kind, filtered by tab): `{id,at,who,what,ok,by ("name (role)")}`. Counts `leaveCount`/`regCount` (unfiltered), tabs | `balances` (**N+1 per card**), `loadAttEmployees`, `attendanceFor` (**N+1**), `fmtTime`, `fmtH`, `fmtD` | **GET /api/approvals?tab&q** → `ApprovalsController` / `ApprovalQueryService`. Batch the balances/attendance calls in Java |
| 12 | `/people` | people → `people.view` | `peopleQuery`: `q` (fullName, code, email, designation, manager name). `sort` ∈ name,code,dept,manager,loc,role,status,access,doj (name; ties by fullName asc). `dir`. `page`, size **25**. `status` ∈ Current\|ACTIVE\|PROBATION\|ONBOARDING\|EXITED\|All (**Current** = not EXITED). `access` ∈ All\|Enabled\|Disabled. `dept` uuid | `rows: PersonRow[] {id,code,name,title,dept,manager,loc (locationOfTz label),india,role,status,access,doj,canToggle}`, `total`, `depts`, KPIs `{cur,us,off,past}` (groupBy status/access/region), `seePay` (struct.manage → subtitle only), `canAdd = canAddEmployees(actor)`. **No salary/PII in the list.** The drawer uses `getProfile` (masked PII, `gross` paise only with struct.manage) | `peopleQuery`, `listState`, `loadActor`, `canToggleAccess`, `canAddEmployees`, `locationOfTz` | **GET /api/people** → `PeopleController.list` / `PeopleQueryService`. Also convert the read actions: **GET /api/people/{id}** (getProfile), **GET /api/people/form-options?editId=** (getFormOptions). revealContact stays **POST** (audited). CSV already exists: `GET /api/people/export` |
| 13 | `/departments` | departments → `dept.manage` | `q` (name/head/by/note, in memory). `sort` ∈ name,head,n,by,on (name). `dir`. **No paging** | `rows[{id,name,note,head,n (non-EXITED employees),by,on (isoInZone(createdAt, viewer tz))}]` (delete shown only when n=0). `heads[{id,name,title}]` (all non-EXITED, for the create dialog). `by = u.name` | `listState`, `isoInZone` | **GET /api/departments** → `DepartmentController.list` / `DepartmentService`. **GET /api/lookups/employees?active=true** for heads |
| 14 | `/holidays` | holidays → `holidays.manage` | `q` (name). `sort` ∈ k(date),n,region,by (k). `dir`. `region` ∈ All\|IN\|US (IN/US also include ALL). **No paging** | `items[{id,k:ISO,n,region IN\|US\|ALL,by}]` (all, for the planner), `rows` (filtered+sorted), `today = todayIn(viewer tz)` (past rows dimmed) | `listState`, `pick`, `todayIn` | **GET /api/holidays?region&q&sort&dir** → `HolidayController.list` / `HolidayService` |
| 15 | `/payroll` | payroll → `pay.view` | `m` must be in `payrollMonths(todayIST).months` (last 6), default `dflt`. `filterPayroll`: `q` (name/code/dept). `show` ∈ All\|LOP\|Edited\|NEW\|OLD. `sort` ∈ name,gross,lop,paid,earned,tds,net (name). `dir`. Size 100 but **not paginated** | `months[]`, `month`, `run`, `closed`, `attendanceLocked`, `rows: PayView[] {employeeId,code,name,department,regime,monthlyGross,lop,attendanceLop,paid,days,bonus,earned,pfPt,tds,other,net (all paise),edited,note}`. **Live** (`computeRun`) when the month is open, **snapshot** when it is closed. `tot{n,earned,lop,tds,net}` is over **unfiltered** rows. `stages[5]`, `missing[{fullName}]` (IN, active, joined ≤ last day, no structure ≤ last day). `canClose = pay.close`, `canStruct = struct.manage`, `closedAt` | `payrollMonths`, `payrollView` → `runStatus`, `closedRows`, `computeRun`; `filterPayroll` | **GET /api/payroll/months**, **GET /api/payroll/{ym}?q&show&sort&dir** → `PayrollController` / `PayrollViewService`. Exports already exist: `GET /api/payroll/export?m&type=bank\|ecr\|pt\|tds\|register` |
| 16 | `/structures` | structures → `struct.manage` | `q` (name/code/title). `sort` ∈ name,basic,g,since (name). `dir`. Size 100, **not paginated**. `set` ∈ All\|Set\|Missing. `dept` uuid | IN, non-EXITED employees: `{id,code,name,title,doj,revs: Rev[] {from ISO,basic,hra,lta,special (paise/month),reason} desc, cur (latest from ≤ today IST, else the upcoming one), g = Σcur paise}`. `depts`. `minFrom` = 1st of the month after the last CLOSED run (revisions can't start earlier) | `listState`, `pick`, `todayIn` | **GET /api/salary-structures** → `SalaryStructureController.list` / `SalaryStructureService` |
| 17 | `/tax` | tax → `tax.view` | `taxRows`: `q` (name/code). `reg` ∈ All\|NEW\|OLD. `sort` ∈ name,reg,inc,taxable,tax,tds,pend (name). Size 200, **not paginated** | `{month (open payroll month), fy, all, rows[{id,code,name,title,reg,inc,ded (std+deductions),taxable,tax,tds — **rupees**, pend (PENDING declaration count),overridden}]}`. Totals are computed client-side. The drawer uses the `taxDetail` action → `TaxProjection` | `taxRows` → `taxPositions` → `openPayrollMonth` + `computeRun` + `projectTax` | **GET /api/tax?q&reg&sort&dir** → `TaxController.list` / `TaxQueryService`. **GET /api/tax/{employeeId}** (replaces the taxDetail action). CSV already exists: `GET /api/tax/export` |
| 18 | `/proofs` | proofs → `proofs.verify` | `q` (name/code/dept). `st` ∈ All\|Pending\|Verified. `sort` ∈ name,reg,dec,pend (**pend**). `dir` **desc**. Size 100, **not paginated** | Employees with any FY item where amount>0 or a proof exists: `{id,name,code,dept,reg,dec (Σ amount **paise**, BigInt→number),pend,appr}`. "Verified" = pend=0 && appr>0 | `getSetting("tax.policy")`, `listState` | **GET /api/proofs** → `ProofController.list` / `ProofQueryService`. **GET /api/proofs/{employeeId}** (replaces the proofDetail action: items amount paise, `tdsNow`/`tdsIfAll` ₹ via `reproject` OLD approved/submitted). The proof file already exists: `GET /api/declarations/proof/[id]` |
| 19 | `/roles` | roles → `roles.manage` | `role` = role id to preselect (default the `hrm` role, else the first) | `roles: RoleView[] {id,key,name,builtin,locked,perms[],assign[] (assignable role ids),members[{id,name}] (non-EXITED),totalEver}`, sorted in ORDER admin,hrm,hre,fm,fe,employee then custom roles by createdAt. Uses the `PERMISSIONS`/`PERMISSION_GROUPS` catalogue client-side | Prisma | **GET /api/roles** → `RoleController.list` / `RoleService`. **GET /api/permissions** (static catalogue, optional) |
| 20 | `/settings` | settings → `roles.manage` | `tab` ∈ access\|leave\|hol\|att (access) | **access:** the permission × role matrix (`roles[{id,key,name,perms[]}]`, same ORDER). **leave:** active leave types `{code,name,annualQuota,accrualPerMonth,carryForwardCap,maxStretch,encashPerYear,encashAfterMonths,expiryDays}` (Decimal → number) + attendance policy (cards for LOP/CO). **hol:** holidays of the current **UTC** year. **att:** `attendance.policy` (fullDayHours, halfDayHours, graceMinutes (from shifts[0] if present), lateMarksPerHalfDayLop, weeklyOffs, absenceJobBufferMinutes, lockDayOfNextMonth, lockTime, trackingStartDate), `shifts[]` (+ grace end time), `locked = jobRun(name="attendance.lock").count>0` | `getSetting`, `PERMISSIONS` | **GET /api/settings/access-matrix**, **GET /api/settings/leave-policy**, **GET /api/settings/holidays?year=**, **GET /api/settings/attendance-policy** → `PolicyController` / `SettingsService` |
| 21 | `/audit` | audit → `roles.manage` | `auditRows`: `q` (action/entity/entityId/actor name/actor code). `ev` ∈ All\|auth\|people\|roles\|time\|org\|payroll\|tax (action prefix groups). `actor` uuid. `days` ∈ 7\|30\|90\|365\|All (**30**). `sort` ∈ at,actor,action (**at**). `dir` **desc**. Size **50**, DB-paged | `{total, rows: AuditRow[] {id,at ISO,actor ("System" if null),actorCode,action,entity,entityId,subject ("Name (CODE)" for employee/user entities),ip,before,after,redacted}, actors[{v:id,l:"Name (CODE)"}]}`. **Redaction:** if the viewer lacks every one of pay.view/struct.manage/tax.view/proofs.verify, actions starting `salary. payroll. tax. declaration. proof.` get `before/after=null` and `redacted=true`. `actionLabel()` is a pure helper | `auditRows`, `actionLabel`, `listState` | **GET /api/audit** → `AuditController` / `AuditQueryService` |
| 22 | `/login` (`(auth)/login`) | public (redirects to landing if signed in) | `signedout`, `reset`, `activated` (notice text), `next` (post-login path) | `notice`, `next`. No DB reads apart from the current user | `getCurrentUser`, `landingPath` | Uses **GET /api/auth/me** (row 0). Login is already a POST action |
| 23 | `/forgot` | public | — | Static form | — | none (POST forgot already planned) |
| 24 | `/invite/[token]`, `/reset/[token]` (`TokenPage`) | public | path `token`. Purpose INVITE\|RESET | `findUsableToken(token, purpose)` → `{name: employee.fullName, code, rules: PASSWORD_RULES}` or "Link expired" | `findUsableToken`, `PASSWORD_RULES` | **GET /api/auth/tokens/{token}?purpose=INVITE\|RESET** → `AuthTokenController` / `AuthTokenService.findUsable` → 200 `{name,code,passwordRules}` or 404/410. It must not consume the token |
| — | Shared lookups | varies | — | `departments[{id,name}]` (not archived, by name) is used by directory, people, register and structures | — | **GET /api/lookups/departments** (any signed-in user with one of dir/people.view/att.all/struct.manage) |
| — | Read-type server actions | — | — | `leave/preview` (Preview: dates, skipped, days, available, paidDays, lopDays, balanceAfter, error) | `previewRequest` | **GET /api/me/leave/preview?type&from&to&part** |

**New GET endpoints: 34.** Page reads: shell, auth/me, home, overview, my attendance, my leave, my holidays, my payslips, my tax-declaration, directory, team, register daily, register monthly, approvals, people, departments, holidays, payroll months, payroll/{ym}, salary-structures, tax, proofs, roles, 4 × settings, audit, auth token, lookups/departments, lookups/employees. Ex-actions turned into GETs: people/{id}, people/form-options, tax/{id}, proofs/{id}, leave/preview. There are 6 existing GET route handlers (CSV/PDF/proof downloads) that only need porting.

**Hot spots to fix while porting**
- `computeRun` (the full payroll + tax projection for every IN employee) runs on `/overview`, `/payroll`, `/tax`, `/declare` (for 1 employee) and in the proofs/tax drawers. Inside it, `structureOn` and `projectTax` run 6 queries **per employee**. Cache per (month, data version) or batch-load.
- `/approvals` calls `balances()` and `attendanceFor()` once per card (N+1).
- `/departments`, `/holidays`, `/team`, `/proofs`, `/structures`, `/payroll`, `/tax` and `/leave` filter and sort **in memory**. Keep that semantics (string compare `>`/`<`, which is not locale-aware, except `/team`, which uses `localeCompare`), or move it to SQL and accept small ordering differences.

## 4. Business logic modules to port

### 4.1 Pure modules (port first; JUnit-test them 1:1)

| Module | Exported functions / consts | Purpose | Tricky numeric / time details |
|---|---|---|---|
| `src/lib/attendance/engine.ts` | types `Status, ShiftDef, Policy, Punch, DayInput, DayResult, MonthSummary`; `localParts, addDays, dowOf, atLocal, WINDOW_LEAD_MIN=240, workDateOf, shiftWindow, workedMinutes, computeDay, summarize` | Work-date attribution, worked time and day status for one day (PRESENT, HALF_DAY, ABSENT, LEAVE, HOLIDAY, WEEKLY_OFF, BEFORE_JOINING, AFTER_EXIT, PENDING, NOT_TRACKED), plus the month summary | `localParts` uses `Intl.DateTimeFormat("en-CA", h23)` per tz (→ `ZonedDateTime.ofInstant`). `atLocal` uses `TZDate(y,m-1,d,h,mi,tz)`. **DST gap/overlap:** match date-fns/tz (gap → shift forward, overlap → earlier offset; verify with the DST test). The punch window opens 240 min before shift start and lasts 24 h; `minutes < anchor` → previous date. A night shift crosses midnight when `end <= start`. `workedMinutes` = floor(ms/60000), IN→OUT pairs; a duplicate IN is ignored while one is open; an open IN counts up to `now` only while the day is live. `dayOver = now ≥ shiftEnd + absenceJobBufferMinutes`. Late = firstIn > start+grace. Half-day leave: the unworked other half adds +0.5 LOP unless an absence regularization exists. `leaveSplit` rounds to 2 dp. `summarize`: HALF_DAY = 0.5 present; LEAVE with ≥ half-day hours = +0.5 present; `lateLopDays = floor(lateMarks/lateMarksPerHalfDayLop)*0.5`. Comparisons use ISO string ordering |
| `src/lib/leave/rules.ts` | `Part, workingDays, requestDays, lopSplit, paidFractions, round2, accrualFor, compOffDays, Credit, compOffExpiries, yearEndLapse` | Leave maths: working days, LOP split, per-date paid fractions, accrual, comp-off FIFO expiry, year-end lapse | `round2 = Math.round(n*100)/100` (JS rounds half toward +∞; use `Math.round`-equivalent, **not** `BigDecimal HALF_EVEN`). `lopSplit` pays floor(available·2)/2 (half-day steps). `paidFractions` uses up paid days from the start, so any shortfall lands on the last days. Unit is 0.5 when there is 1 date and a half part. `accrualFor` is pro-rata `(perMonth·daysLeft/dim)` in the joining month |
| `src/lib/payroll/engine.ts` | `Structure, grossOf, PayInput, PayLine, PayResult, computePay, eligibleDays, daysInMonth, splitGross, rupeesInWords` | Monthly pay in **paise** | Earned per component = `round(rate·paidDays/dim/100)*100` (whole rupees). LOP is clamped to [0, eligible] and may be 0.5. PF = `round(pfRate · min(earnedBasic, ceiling))` (ceiling in paise = ₹15,000×100). PT only if gross > 0. `splitGross` = 50/20/5% rounded to the rupee, with special = remainder. `rupeesInWords` uses the Indian crore/lakh/thousand system and expects **rupees** (the caller divides net by 100). `eligibleDays` is inclusive. Use `long` |
| `src/lib/tax/engine.ts` | `Slab, Surcharge, TaxYearConfig, SlabLine, TaxResult, slabTax, computeTax, Caps, Declared, NO_DECLARATIONS, Deductions, oldRegimeDeductions, fyOf, monthlyTds` | Income tax in **rupees (float)**: slabs, rebate (87A) with new-regime marginal relief, surcharge with marginal relief, 4% cess; old-regime deductions; FY index; monthly TDS | `taxable = max(0, round(gross − std − deductions))`; slab amounts are fractional; `total = round(base+surcharge+cess)`. Surcharge marginal relief uses the tier threshold and the previous tier rate. HRA = least of (HRA, rent − 10% basic, 50/40% basic). Each deduction line is rounded separately and the total is `round(Σ unrounded)`. `fyOf("2026-09")` → `{fy:"2026-27", index:5, remainingInclCurrent:7}`. `monthlyTds = max(0, round((annual − paid)/remaining))`. Slabs/surcharge come from JSON. Use `double` + `Math.round` to stay bit-compatible, or BigDecimal with HALF_UP and re-verify the golden case |
| `src/lib/format.ts` | `MONTHS, MON3, DOW, isoInZone, todayIn, dateToIso, isoToDate, fmtD, fmtDY, fmtDL, ymLabel, fmtDateTime, daysBetween, inr, fmtH, initials, avc` | Date and money formatting (shared with the client, so the TS copy stays in Next) | `inr(paise)` = `"₹" + round(paise/100)` with **en-IN grouping** (`1,23,456`). `fmtH` = `"Xh MMm"` (minutes rounded). `fmtDateTime` = en-GB `"6 Oct, 9:41 pm"` in tz. `daysBetween` rounds day differences. `avc` = hash `(h*31+c)%997` over UTF-16 code points (`for…of` = code points). Java needs only `isoInZone/todayIn/dateToIso/isoToDate/daysBetween`, plus `fmtD/fmtDL/fmtDateTime/ymLabel` for server-built strings (notes, emails, CSV) |
| `src/lib/people-rules.ts` | `Actor, Target, isAdmin, canAddEmployees, canEditEmployee, canToggleAccess, canAssignRole, lockedDepartment, initialStatus, derivedStatus, createsCycle, LOCATIONS, LocationCode, locationOfTz, PAN_RE, digits, validAadhaar, validUan, validPhone` | Who may add/edit/toggle whom; status transitions; reporting-loop check; location ↔ tz; ID validation | `derivedStatus`: probation ends at `Date.UTC(y, m-1+months, d)` (JS month overflow, e.g. 31 Aug + 6 → 3 Mar; **`LocalDate.plusMonths` clamps to 28 Feb → mismatch**). `createsCycle` loop cap 10,000. Aadhaar `/^[2-9][0-9]{11}$/` on digits only |
| `src/lib/nav.ts` | `NavId, NavGroup, NavItem, NAV, PAGE_TITLES, navAllowed, landingFor` | Route access table and landing page | Order matters (sidebar order, first allowed = fallback landing). `landingFor`/`landingPath` differ: `auth.landingPath` falls back to the first allowed nav, else `/no-access` |
| `src/lib/permissions.ts` | `PERMISSIONS, Permission, PERMISSION_KEYS, PERMISSION_LABEL, PERMISSION_GROUPS, SALARY_PERMISSIONS, isPermission` | Catalogue of 16 permission keys | Java `enum Permission` with `key()` such as `"att.all"` (dots). `SALARY_PERMISSIONS` drives the HR guard in roles/actions |
| `src/lib/shifts.ts` | `shiftLabel, SHIFT_OPTIONS` | Display label for a shift | 12-h conversion `((h+11)%12)+1`. The suffix is " local" for US_DAY, else " IST" |
| `src/lib/list.ts` | `SearchParams, ListState, str, pick, listState` | URL list-state parsing | 200-char cap, first value of an array param, page clamped to 1..10000, `parseInt` lenient (`"3abc"` → 3) |
| `src/lib/mask.ts` | `maskAadhaar, maskPan, maskUan, maskPhone, maskBank` | PII masks | `"—"` when there is no last4 |
| `src/lib/tax/catalogue.ts` | `DECL, ITEM_SECTION, ITEM_LABEL, SECTION_OF_ITEM, DECLARABLE` | Declaration items and sections | `medParents` → `80D_PARENTS`. `epf` is auto (not declarable) |
| `src/lib/settings-defaults.ts` | `DEFAULT_SETTINGS` | Policy defaults | `getSetting` does a **shallow** merge of the DB JSON over the defaults. pfWageCeiling and PT are in **rupees** and are ×100 in computeRun |
| `src/lib/audit-view.ts` (pure part) | `AUDIT_GROUPS, AUDIT_SORTS, AUDIT_PERIODS, actionLabel` | Event groups, label helper | `"employee.access_toggle"` → `"Employee · access toggle"` |
| `src/lib/attendance/register.ts` (pure part) | `ZONES, DAY_STATUSES, dayStatusLabel, clock, CODE, cellCode, registerMonths` | Register wording, cross-zone clock with (+1)/(−1), grid codes | `clock` uses en-US `h:mm AM` + `timeZoneName:"short"`, with "GMT+5:30" replaced by "IST" (Java short names differ: map them explicitly). The minus in "(−1)" is U+2212 |

### 4.2 DB-backed service modules

| Module | Exported functions | Purpose | Tricky details |
|---|---|---|---|
| `src/lib/attendance/service.ts` | `TRACKED, AttEmployee, loadAttEmployees, attendancePolicy, lockInstant, isMonthLocked, DayView, attendanceFor, materialize, currentWorkDate, fmtTime` | Loads tracked employees (role has `self`), computes day views for a range (**locked `attendance_days` rows override live computation**) and persists or locks days | Punches are loaded from `from−36h` to `to+60h` and attributed by `workDateOf`. Holidays are loaded across the full span of every overlapping leave so paid fractions start at the leave's first working day. Paid days = `days − lopDays` for paid types (Decimal → number). Locked row: `late = late && !lateWaived`. DEFAULT_SHIFT IN_DAY 09:30–18:30 grace 15. `lockInstant` = day N of the next month at lockTime **IST**. `fmtTime` = en-US `h:mm AM` in tz |
| `src/lib/attendance/register.ts` | `registerParams, RegisterParams, dailyLog, monthlyGrid` (+ the pure ones above) | Register list contracts | The default `day`/`ym` comes from **IST** today. The employee filter includes people who joined ≤ to and did not exit before from. Missing in/out sorts last (`9e15`) |
| `src/lib/leave/service.ts` | `Balance, balances, holidayMap, Preview, previewRequest, postUsage` | Ledger balances (+ pending holds), apply-form preview and validation, usage posting | `balance = round2(Σ ledger.delta)`, `pending = round2(Σ days − Σ lopDays)` of PENDING requests. `previewRequest` swaps from/to if reversed, excludes its own hold when deciding, and runs the error checks in a fixed order (past, before DOJ, after exit, >62 days, no working days, maxStretch, month locked, overlap) |
| `src/lib/payroll/service.ts` | `structureOn, PayRow, PayslipSnapshot, computeRun, toSnapshot, closedRows, runStatus` | Live payroll for a month: eligibility, weekly-off/holiday counts, attendance LOP, adjustments, two-pass pay (tds=0 → projectTax → tds) | `now` for attendance is forced to ≥ month end + 2 days so every day is "over". An adjustment's `lopDays` (Decimal, nullable) overrides attendance LOP. `present = max(0, elig − wo − hol − lop)`. Settings ₹ → paise ×100. `tax.monthly` is ₹, so TDS paise = `monthly*100`. Snapshot `netWords = rupeesInWords(net/100)`. The snapshot JSON schema (version 1) must stay byte-compatible for closed payslips |
| `src/lib/payroll/view.ts` | `PAY_SORTS, PAY_FILTERS, PayView, payrollView, filterPayroll, payrollMonths` | Payroll table rows (live or snapshot), filter/sort, month switcher | Snapshot `monthlyGross` = Σ rate of components with rate≠0 (bonus has rate 0). `edited`: closed = bonus>0 \|\| other>0 \|\| note; live = adjusted \|\| note. The months list is the last 6 of (runs ∪ {first open month}) |
| `src/lib/payroll/close.ts` | `closeRun` | Snapshots, PDFs, atomic OPEN→CLOSED, payslips, audit, notifications, emails | Optimistic `updateMany where status=OPEN` → count check. PDF files go under `UPLOAD_DIR/payslips/<ym>/<code>.pdf` |
| `src/lib/payroll/pdf.ts` | `payslipPdf` | Payslip PDF | Port with OpenPDF/iText; reuse `inr` grouping |
| `src/lib/tax/service.ts` | `taxConfig, DeclMode, declarations, MonthFigures, TaxProjection, projectTax` | FY tax projection per employee | Converts **paise → rupees** (`/100`, float). Earlier FY months without a closed slip are estimated pro-rata `elig/D` from the structure then in force, with each part `Math.round`ed in paise and PF estimated as `round(0.12·min(basic, 1_500_000))` (**hard-coded 12% / ₹15k**, unlike computeRun, which uses settings). Annual = closed slips + estimate + this month + structure×(remaining−1). Declarations: BigInt paise → ₹. Modes: approved, submitted (+PENDING), all (+DRAFT). Requires 2 `tax_years` rows, else throws |
| `src/lib/tax/position.ts` | `openPayrollMonth, reproject, taxPositions` | The month tax is projected for (first month after the last closed run, capped at the current month; if there are no runs, the previous month); re-projection with other declarations or regime | Month math through `Date.UTC(y, m, 1)` (month overflow) |
| `src/lib/tax/rows.ts` | `TAX_SORTS, taxRows` | Tax page/export rows | All figures in ₹ from TaxProjection |
| `src/lib/holidays.ts` | `upcomingHolidays(region, fromIso, take=6)` | Next holidays for region + ALL | — |
| `src/lib/team-status.ts` | `TodayStatus, todayStatuses(ids, now)` | "Right now" label/tone per employee on **their own** current work date | A single batched `attendanceFor` from the min to the max work date. Priority: openSince → LEAVE → HOLIDAY → WEEKLY_OFF → has punches ("Checked out") → ABSENT → "Not in yet" |
| `src/lib/dashboard.ts` | `payrollState, pendingCounts, overviewData, homeCards` | Overview and home cards | Home: `approver = appr.leave \|\| (appr.reg && !pay.view)`; `showMyLeave = !finance && !appr.reg && !hasReports`; payslip card for IN only. Overview `noStructure` uses a JS Date for `lastDay` (UTC) |
| `src/lib/audit-view.ts` | `auditRows` | Audit list with redaction | `subject` is resolved only for entity `employee`/`user` with a uuid id |
| `src/lib/shell-data.ts` | `ShellNote, shellData` | Sidebar groups, badges, bell | Notifications from the last 14 days, max 8. The next holiday is from `todayIn(u.timeZone)` |
| `src/lib/list.ts`, `src/lib/people-query.ts` | `listState/pick/str`; `PEOPLE_SORTS, PEOPLE_STATUS, PEOPLE_ACCESS, peopleQuery` | List contracts (shared by the page and the CSV export) | Secondary sort is fullName asc for every key except name/code/doj. Prisma `mode:"insensitive"` → `ILIKE` |
| `src/lib/shifts.ts`, `src/lib/settings.ts`, `src/lib/actor.ts` | `shiftLabel`; `getSetting`; `loadActor, loadTarget` | Settings merge, people-rules inputs | `getSetting` does a shallow merge (nested `professionalTax` is replaced as a whole) |
| Also (out of the requested list, needed for parity) | `crypto.ts` (PII AES-GCM, token hash), `password.ts` (argon2id + rules), `auth-tokens.ts`, `session.ts`, `rate-limit.ts`, `jobs.ts`/`scheduler.ts` (absence, lock, accrual, comp-off expiry, status jobs), `csv.ts` (formula neutralising), `employee-schema.ts` (zod) | — | argon2id parameters must match to keep existing hashes valid. The AES key and format must match to decrypt existing `*Enc` columns |

## 5. Unit tests to port (Vitest → JUnit 5)

| File | describe → test cases | Java target |
|---|---|---|
| `tests/unit/attendance-engine.test.ts` | **night shift attribution:** assigns a 2 AM check-out to the shift that started the evening before · day shift: an early 6 AM check-in is the same day · night window ends the next morning. **worked time:** sums multiple pairs · counts an open check-in up to now while live. **day status:** present when 8h+ is worked · late after the 15-minute grace · a regularized late mark is waived · half day between 4h and 8h; absent below 4h · no check-in: pending until shift end + buffer, then absent (LOP) · an approved absence regularization makes the day paid · missed check-out is flagged · in progress while checked in today · holidays and weekly offs are never leave or LOP · holiday work of 4h+ is a comp-off candidate · leave: paid full day, unpaid LOP leave, and half-day leave · before joining, after exit and before go-live · future days are not judged. **US time zones:** uses the employee's local shift, including DST. **month summary:** 3 late marks = ½ day LOP | `AttendanceEngineTest` (`@Nested` per describe) |
| `tests/unit/leave-rules.test.ts` | **working days:** skips weekends and the employee's holidays · half day applies only to a single date. **loss of pay when the balance is short:** pays what the balance covers, in half-day steps · leave without pay is all LOP · shortfall lands on the last days. **accrual:** full month after joining, pro-rata in the joining month, none before. **comp-off:** credits a full or half day by hours worked · expires the unused part of old credits first-in-first-out. **year end:** casual leave lapses, earned leave carries up to 30 | `LeaveRulesTest` |
| `tests/unit/payroll-tax.test.ts` | **golden case — JC-074 Amit Jaspal, September 2026:** joined 8 Sep with 1 LOP → 22 of 30 paid days (elig 23, gross 11,000,000 paise, PF 180,000) · monthly TDS ≈ ₹14,926 on the new regime with ₹7.5L / ₹38,000 from the previous employer (taxable 1,685,000; total 142,480). **tax engine:** new regime: full rebate up to ₹12L taxable, marginal relief just above · old regime: rebate up to ₹5L, slabs above · surcharge above ₹50L with marginal relief · old-regime deductions: HRA least-of, 80C cap incl. PF, 80D parents · monthly TDS is never negative. **payroll helpers:** split: Basic 50 · HRA 20 · LTA 5 · Special rest · exit mid-month limits eligible days · net pay in words (Indian system) | `PayrollEngineTest`, `TaxEngineTest` (FY 2026-27 NEW/OLD slab fixtures copied verbatim) |
| `tests/unit/people-rules.test.ts` | **editing employees:** HR Manager edits HR Executives and Employees only · Admin edits anyone except the super admin; nobody edits themselves · needs people.add. **access toggle:** never yourself, never super admin, never exited · only Admin can toggle an Admin · needs access.toggle. **roles and departments:** assigns only allowed roles, but keeping the current role is fine · locks the Finance Manager to Finance; HR and Admin choose freely. **status and reporting lines:** new hires are Onboarding until they join, then Probation · becomes Active after the probation months · detects reporting loops. **form validation:** requires PAN and Aadhaar for India on create · skips Indian IDs for the US team and keeps blanks on edit · validates Aadhaar shape. **csv export:** neutralises formulas and quotes commas | `PeopleRulesTest`, `EmployeeInputValidationTest` (Bean Validation), `CsvTest` |
| `tests/unit/nav.test.ts` | **navigation access:** lands Admin on Overview and everyone with self on Home · gives Admin no self-service pages · never shows finance pages to HR · shows My team only with active direct reports · lets Finance Executive approve regularizations but not leave | `NavAccessTest` (+ the seeded role→permission matrix) |
| `tests/unit/format.test.ts` | **formatting:** formats paise as Indian rupees · formats ISO dates without time-zone drift · resolves the calendar date in the employee's zone | `FormatTest` (only the server-side helpers) |
| `tests/unit/crypto.test.ts` | **PII encryption:** round-trips and never stores plaintext · uses a fresh IV each time · rejects tampered ciphertext · treats empty values as null · masks to the last four only · hashes tokens deterministically | `CryptoTest`. Add a **cross-compat** test: decrypt a ciphertext produced by Node |
| `tests/unit/password.test.ts` | **passwords:** hashes with argon2id and verifies · enforces the policy | `PasswordTest`. Add: verify a hash produced by Node argon2 |

Totals: 8 files; the audit lists 69 named cases, `vitest run` reports 70; the one-case difference is reconciled in M1, and every case `vitest run` executes gets a JUnit equivalent. Also recommended: golden tests for `computeRun`/`projectTax` (Testcontainers Postgres + seed), with the Node output recorded as fixtures so the Java results can be diffed number for number.


---

Sources audited (read-only): `prisma/schema.prisma` (698 lines, 17 enums, 30 models), `prisma/migrations/*/migration.sql` + `down.sql` (4 migrations), `src/**` grep for raw SQL and Prisma-specific query features. Target: PostgreSQL 16 (`postgres:16-alpine`, docker-compose port 5436), `spring.jpa.hibernate.ddl-auto=validate`, and the schema stays exactly as it is.

---

## 6. Prisma schema → JPA entities

### 6.0 Global conventions (apply to every entity)

| Concern | Decision |
|---|---|
| Schema | `public`. All table and column names are lower snake_case, so no quoting is needed. The **exceptions** are the PG enum type names, which are mixed-case and quoted (`"EmployeeStatus"`), and the column `email_outbox."to"` (reserved word, see 8.9). |
| Column names | Put an explicit `@Column(name = "...")` on every field. Do **not** rely on `CamelCaseToUnderscoresNamingStrategy`, because several FK columns drop the `_id` suffix: `createdById → created_by`, `decidedById → decided_by`, `approvedById → approved_by`, `closedById → closed_by`, `updatedById → updated_by`. The default strategy would produce `created_by_id` and fail `validate`. |
| `String` (no `@db.*`) | PG `TEXT`. Use Java `String` with `@Column(columnDefinition = "text")`. This is optional, but it keeps `validate` robust. **No length limits exist in the DB.** |
| `@db.Uuid` | PG `UUID`. Use `java.util.UUID`. Hibernate 6 maps it natively to `SqlTypes.UUID`. |
| `DateTime @db.Timestamptz(6)` | PG `TIMESTAMPTZ(6)`. Use `java.time.Instant`. Set `hibernate.jdbc.time_zone=UTC`. |
| `DateTime @db.Date` | PG `DATE`. Use `java.time.LocalDate`. |
| `Int` | PG `INTEGER`. Use `int` (NOT NULL) or `Integer` (nullable). Monthly money is in paise. |
| `BigInt` | PG `BIGINT`. Use `long` / `Long`. Annual money is in paise. |
| `Decimal @db.Decimal(p,s)` | PG `DECIMAL(p,s)`. Use `java.math.BigDecimal` with `@Column(precision=p, scale=s)`. |
| `Json` | PG **`JSONB`**. Use `@JdbcTypeCode(SqlTypes.JSON)` on a `JsonNode` (or `Map<String,Object>` / a typed record). |
| `Boolean` | PG `BOOLEAN`. Use `boolean` / `Boolean`. |
| `@default(now())` | The DB has `DEFAULT CURRENT_TIMESTAMP`. In JPA, use `@CreationTimestamp` (app clock) so the entity is populated after persist. The DB default stays as a safety net. |
| `@updatedAt` | **There is no DB default** (`updated_at TIMESTAMPTZ(6) NOT NULL`). Prisma sets it client-side. In JPA, use `@UpdateTimestamp`, which Hibernate sets on both insert and update. This is mandatory or inserts fail. |
| `@default(uuid())` | **This is client-side only.** No migration contains `DEFAULT gen_random_uuid()`, and every `id UUID NOT NULL` has no default. Use `@Id @GeneratedValue @UuidGenerator` (Hibernate generates a random v4 UUID in the JVM before insert, which matches Prisma). See 6.5. |
| Scalar `@default(x)` (bool/int/enum/decimal) | These are real DB defaults. However, Hibernate inserts **every** column, so a `null` Java field overrides the default and violates NOT NULL. Initialise the Java field to the same default (for example `private EmployeeStatus status = EmployeeStatus.ONBOARDING;`) or use `@DynamicInsert`. Field initialisers are the recommended approach. |
| FK `ON DELETE` | This lives in the DB (`ON DELETE CASCADE / SET NULL / RESTRICT` and `ON UPDATE CASCADE` on all FKs). **Hibernate must not emulate it**: no `CascadeType.REMOVE`, no `orphanRemoval` on inverse collections. Optionally annotate `@OnDelete(action = OnDeleteAction.CASCADE / SET_NULL)` on the `@ManyToOne` side, which is documentational under `validate` and tells Hibernate the DB does it. |
| Associations | All are `@ManyToOne(fetch = LAZY)` + `@JoinColumn(name=...)`. Optionally add a read-only mirror `@Column(name="x_id", insertable=false, updatable=false) UUID xId` so services can use ids without loading the relation, which mirrors how the TS code uses `employeeId` scalars everywhere. |
| `@@index` / `@@unique` | `validate` does **not** check indexes or unique constraints. Declaring them in `@Table(indexes=…, uniqueConstraints=…)` is documentational only (it is DB-only in practice). Use the exact Prisma-generated names listed below so any future `ddl` diff stays clean. |

### 6.1 Enums (17). All are native PG enum types, created by `CREATE TYPE "<Name>" AS ENUM (...)`

**Storage decision:** every enum column is a **native PostgreSQL named enum** whose type name is the quoted, mixed-case Prisma enum name. Map each one with:

```java
@Enumerated(EnumType.STRING)
@JdbcTypeCode(SqlTypes.NAMED_ENUM)          // Hibernate 6.2+: binds as PG enum (Types.OTHER), not varchar
@Column(name = "status", nullable = false, columnDefinition = "\"EmployeeStatus\"")
private EmployeeStatus status = EmployeeStatus.ONBOARDING;
```

- Keep the **Java enum simple name identical to the PG type name** (for example `EmployeeStatus`). `NAMED_ENUM` derives the type name from the Java class.
- Keep the **constant names identical** to the PG labels (all UPPER_SNAKE, and every one is a legal Java identifier: `IN`, `OUT`, `NEW`, `OLD`, `ALL` are fine).
- `columnDefinition` with the quoted name makes `validate` compare against the real type. This needs a spike test (8.10). pgjdbc reports mixed-case type names quoted.
- **Do not** use plain `@Enumerated(STRING)` without `NAMED_ENUM`. That binds `varchar`, and PG rejects it ("column is of type "EmployeeStatus" but expression is of type character varying"). The only fallback is the JDBC URL flag `?stringtype=unspecified`, which is not recommended because it is global.

| Prisma enum → Java enum | PG type | Values (in DB order) | Used by (table.column) |
|---|---|---|---|
| `EmployeeStatus` | `"EmployeeStatus"` | ONBOARDING, PROBATION, ACTIVE, EXITED | employees.status |
| `Region` | `"Region"` | IN, US | shifts.region, employees.region |
| `HolidayRegion` | `"HolidayRegion"` | IN, US, ALL | holidays.region |
| `PunchKind` | `"PunchKind"` | IN, OUT | punches.kind |
| `PunchSource` | `"PunchSource"` | WEB, MOBILE, REGULARIZATION | punches.source |
| `DayStatus` | `"DayStatus"` | PRESENT, HALF_DAY, ABSENT, LEAVE, HOLIDAY, WEEKLY_OFF, BEFORE_JOINING, AFTER_EXIT, PENDING, NOT_TRACKED (the last was added by `ALTER TYPE … ADD VALUE` in migration 3) | attendance_days.status |
| `RegularizationKind` | `"RegularizationKind"` | ABSENT, LATE_CHECKIN, MISSED_CHECKOUT, HOLIDAY_WORK | regularization_requests.kind |
| `RequestStatus` | `"RequestStatus"` | PENDING, APPROVED, DECLINED, CANCELLED | regularization_requests.status, leave_requests.status |
| `LeavePart` | `"LeavePart"` | FULL, FIRST_HALF, SECOND_HALF | leave_requests.part |
| `LedgerReason` | `"LedgerReason"` | ACCRUAL, USED, CANCELLED, ENCASHED, EXPIRED, ADJUSTMENT, CARRY_FORWARD, COMP_OFF_CREDIT | leave_ledger.reason |
| `PayrollStatus` | `"PayrollStatus"` | OPEN, CLOSED | payroll_runs.status |
| `PayrollStage` | `"PayrollStage"` | LOCK_ATTENDANCE, COMPUTE, REVIEW, APPROVED, CLOSED | payroll_runs.stage |
| `StructureReason` | `"StructureReason"` | JOINING, APPRAISAL, PROMOTION, CORRECTION | salary_structures.reason |
| `TaxRegime` | `"TaxRegime"` | NEW, OLD | tax_years.regime (**part of PK**), tax_profiles.regime |
| `DeclarationStatus` | `"DeclarationStatus"` | DRAFT, PENDING, APPROVED, REJECTED | declaration_items.status |
| `AuthTokenPurpose` | `"AuthTokenPurpose"` | INVITE, RESET | auth_tokens.purpose |
| `EmailStatus` | `"EmailStatus"` | QUEUED, SENT, FAILED, LOGGED | email_outbox.status |

Ordering note: PG enums sort by declaration order, not alphabetically. Never use `EnumType.ORDINAL`. JPQL `ORDER BY status` sorts by PG enum order, which is the same as Prisma.

### 6.2 Entities (30)

Legend: **N** = NOT NULL, **null** = nullable. "DB default" = present in migration SQL. "Client" = Prisma client fills it in and the DB has no default.

---

#### 1. `Department` → table `departments`

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| id | id | UUID PK | `@id @default(uuid()) @db.Uuid` (client) | `UUID` `@Id @GeneratedValue @UuidGenerator` | N |
| name | name | TEXT | `@unique` | `String` | N |
| headEmployeeId | head_employee_id | UUID | `@db.Uuid` FK | `UUID` (FK via `head`) | null |
| description | description | TEXT | | `String` | null |
| createdById | created_by | UUID | `@db.Uuid` FK | `UUID` (FK via `createdBy`) | null |
| createdAt | created_at | TIMESTAMPTZ(6) | `@default(now())` (DB default) | `Instant` `@CreationTimestamp` | N |
| archivedAt | archived_at | TIMESTAMPTZ(6) | added in migration 2 | `Instant` | null |

Relations:
- `head`: `@ManyToOne Employee` with `@JoinColumn("head_employee_id")`, relation `"DepartmentHead"`, **ON DELETE SET NULL**.
- `createdBy`: `@ManyToOne Employee` with `@JoinColumn("created_by")`, relation `"DepartmentCreatedBy"`, **SET NULL**.
- Inverse: `employees` is `@OneToMany(mappedBy="department")`, relation `"EmployeeDepartment"`. It is used for `_count` of non-exited employees.

Unique: `departments_name_key (name)`. This is case-sensitive in the DB; the app checks case-insensitive duplicates separately (8.6).

#### 2. `Role` → `roles`

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| id | id | UUID PK | `@default(uuid())` (client) | `UUID` @UuidGenerator | N |
| key | key | TEXT | `@unique` | `String` | N |
| name | name | TEXT | `@unique` | `String` | N |
| isBuiltin | is_builtin | BOOLEAN | `@default(false)` | `boolean` = false | N |
| isLocked | is_locked | BOOLEAN | `@default(false)` | `boolean` = false | N |
| createdAt | created_at | TIMESTAMPTZ(6) | `@default(now())` | `Instant` @CreationTimestamp | N |

Inverse relations:
- `permissions` is `@OneToMany(mappedBy="role") List<RolePermission>`.
- `assignable` is `@OneToMany(mappedBy="role") List<RoleAssignableRole>`, relation `"RoleAssigner"`.
- `assignedBy` is `@OneToMany(mappedBy="assignableRole")`, relation `"RoleAssignee"`.
- `employees` is `@OneToMany(mappedBy="role")`.

Unique: `roles_key_key (key)` and `roles_name_key (name)`.

#### 3. `RolePermission` → `role_permissions` (composite PK, no surrogate id)

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| roleId | role_id | UUID | PK part, FK | `UUID` (in `@EmbeddedId RolePermissionId` / `@IdClass`) | N |
| permissionKey | permission_key | TEXT | PK part | `String` | N |

- PK: `role_permissions_pkey (role_id, permission_key)`. Map it as `@EmbeddedId RolePermissionId(roleId, permissionKey)` plus `@MapsId("roleId") @ManyToOne Role role`.
- FK: `role_id → roles.id` **ON DELETE CASCADE**.
- The id is app-assigned; there is no generator.

#### 4. `RoleAssignableRole` → `role_assignable_roles` (composite PK)

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| roleId | role_id | UUID | PK part, FK | `UUID` | N |
| assignableRoleId | assignable_role_id | UUID | PK part, FK | `UUID` | N |

- PK: `(role_id, assignable_role_id)`. Map it as `@EmbeddedId RoleAssignableRoleId` with two `@MapsId @ManyToOne Role` fields.
- `role`: relation `"RoleAssigner"`, FK `role_id`, **CASCADE**.
- `assignableRole`: relation `"RoleAssignee"`, FK `assignable_role_id`, **CASCADE**.

#### 5. `Shift` → `shifts`

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| id | id | UUID PK | `@default(uuid())` (client) | `UUID` @UuidGenerator | N |
| code | code | TEXT | `@unique` | `String` | N |
| name | name | TEXT | | `String` | N |
| startLocal | start_local | TEXT | `"19:30"` local-time string | `String` (keep as string. Do **not** map to `LocalTime`, because the column is TEXT) | N |
| endLocal | end_local | TEXT | | `String` | N |
| graceMinutes | grace_minutes | INTEGER | `@default(15)` | `int` = 15 | N |
| region | region | `"Region"` | enum | `Region` (NAMED_ENUM) | N |
| crossesMidnight | crosses_midnight | BOOLEAN | `@default(false)` | `boolean` = false | N |

- Inverse: `employees`.
- Unique: `shifts_code_key (code)`.

#### 6. `Employee` → `employees`

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| id | id | UUID PK | `@default(uuid())` (client) | `UUID` @UuidGenerator | N |
| code | code | TEXT | `@unique` (`JC-###`, allocated by raw SQL under an advisory lock, see 8.12) | `String` | N |
| fullName | full_name | TEXT | | `String` | N |
| fatherName | father_name | TEXT | | `String` | null |
| email | email | TEXT | `@unique`, stored lower-case | `String` | N |
| phoneEnc | phone_enc | TEXT | AES-256-GCM ciphertext | `String` | null |
| phoneLast4 | phone_last4 | TEXT | | `String` | null |
| addressEnc | address_enc | TEXT | ciphertext | `String` | null |
| panEnc | pan_enc | TEXT | ciphertext | `String` | null |
| panLast4 | pan_last4 | TEXT | | `String` | null |
| aadhaarEnc | aadhaar_enc | TEXT | ciphertext | `String` | null |
| aadhaarLast4 | aadhaar_last4 | TEXT | | `String` | null |
| uanEnc | uan_enc | TEXT | ciphertext | `String` | null |
| uanLast4 | uan_last4 | TEXT | | `String` | null |
| bankAccountEnc | bank_account_enc | TEXT | ciphertext | `String` | null |
| bankAccountLast4 | bank_account_last4 | TEXT | | `String` | null |
| bankIfsc | bank_ifsc | TEXT | | `String` | null |
| bankName | bank_name | TEXT | | `String` | null |
| departmentId | department_id | UUID | FK | `UUID` (via `department`) | null |
| designation | designation | TEXT | | `String` | N |
| reportingManagerId | reporting_manager_id | UUID | FK (self) | `UUID` (via `reportingManager`) | null |
| roleId | role_id | UUID | FK | `UUID` (via `role`) | N |
| dateOfJoining | date_of_joining | DATE | `@db.Date` | `LocalDate` | N |
| exitDate | exit_date | DATE | `@db.Date` | `LocalDate` | null |
| status | status | `"EmployeeStatus"` | `@default(ONBOARDING)` | `EmployeeStatus` = ONBOARDING | N |
| timeZone | time_zone | TEXT | IANA zone id | `String` (convert to `ZoneId` in the service) | N |
| region | region | `"Region"` | | `Region` | N |
| shiftId | shift_id | UUID | FK | `UUID` (via `shift`) | null |
| portalAccess | portal_access | BOOLEAN | `@default(true)` | `boolean` = true | N |
| createdById | created_by | UUID | FK (self) | `UUID` (via `createdBy`) | null |
| createdAt | created_at | TIMESTAMPTZ(6) | `@default(now())` | `Instant` @CreationTimestamp | N |
| updatedAt | updated_at | TIMESTAMPTZ(6) | **`@updatedAt` (no DB default)** | `Instant` **@UpdateTimestamp** | N |

Owning relations (`@ManyToOne LAZY`):

| Field | FK column | Target | Prisma relation name | ON DELETE |
|---|---|---|---|---|
| department | department_id | Department | `EmployeeDepartment` | **RESTRICT** |
| reportingManager | reporting_manager_id | Employee | `Reports` | SET NULL |
| role | role_id | Role | (unnamed) | **RESTRICT** |
| shift | shift_id | Shift | (unnamed) | SET NULL |
| createdBy | created_by | Employee | `EmployeeCreatedBy` | SET NULL |

Inverse relations on Employee (Prisma name, then the `mappedBy` field on the child). Map only those that are queried. The ones marked ★ are used in relation filters or `_count` in `src/`.

| Inverse field | Child entity.field | Relation name |
|---|---|---|
| directReports ★ | Employee.reportingManager | `Reports` |
| created | Employee.createdBy | `EmployeeCreatedBy` |
| headOf | Department.head | `DepartmentHead` |
| departmentsMade | Department.createdBy | `DepartmentCreatedBy` |
| user | User.employee (`@OneToOne`) | (unnamed) |
| sessions | Session.employee | (unnamed) |
| loginHistory | LoginHistory.employee | (unnamed) |
| authTokens | AuthToken.employee | (unnamed) |
| punches | Punch.employee | (unnamed) |
| attendanceDays | AttendanceDay.employee | (unnamed) |
| regularizations | RegularizationRequest.employee | `RegEmployee` |
| regDecisions | RegularizationRequest.decidedBy | `RegDecider` |
| holidaysAdded | Holiday.createdBy | (unnamed) |
| leaveLedger | LeaveLedger.employee | `LedgerEmployee` |
| leaveRequests | LeaveRequest.employee | `LeaveEmployee` |
| leaveDecisions | LeaveRequest.decidedBy | `LeaveDecider` |
| salaryStructures ★ | SalaryStructure.employee | `StructureEmployee` |
| structuresCreated | SalaryStructure.createdBy | `StructureCreatedBy` |
| payrollAdjustments | PayrollAdjustment.employee | `AdjEmployee` |
| adjustmentsMade | PayrollAdjustment.updatedBy | `AdjUpdatedBy` |
| payslips | Payslip.employee | (unnamed) |
| taxProfiles | TaxProfile.employee | (unnamed) |
| declarationItems ★ | DeclarationItem.employee | `DeclEmployee` |
| declDecisions | DeclarationItem.decidedBy | `DeclDecider` |
| notifications | Notification.employee | (unnamed) |
| auditEntries ★ | AuditLog.actor | (unnamed) |
| runsClosed | PayrollRun.closedBy | `RunClosedBy` |
| runsApproved | PayrollRun.approvedBy | `RunApprovedBy` |

Unique and indexes (all DB-only): `employees_code_key (code)` UNIQUE, `employees_email_key (email)` UNIQUE, `employees_department_id_idx`, `employees_reporting_manager_id_idx`, `employees_role_id_idx`, `employees_status_idx`.

The entity has 33 columns. Keep all `*Enc` fields out of any HR-facing DTO (CLAUDE.md rule).

#### 7. `User` → `users` (shared PK with employees)

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| employeeId | employee_id | UUID PK + FK | `@id` (**no default; it is the employee's id**) | `UUID` `@Id` + `@MapsId @OneToOne(fetch=LAZY) Employee employee` | N |
| passwordHash | password_hash | TEXT | argon2id, null until invite accepted | `String` | null |
| failedAttempts | failed_attempts | INTEGER | `@default(0)` | `int` = 0 | N |
| lockedUntil | locked_until | TIMESTAMPTZ(6) | | `Instant` | null |
| lastLoginAt | last_login_at | TIMESTAMPTZ(6) | | `Instant` | null |
| passwordSetAt | password_set_at | TIMESTAMPTZ(6) | | `Instant` | null |
| isSuperAdmin | is_super_admin | BOOLEAN | `@default(false)` | `boolean` = false | N |

- FK `employee_id → employees.id` **CASCADE**.
- Name the entity `UserAccount` (or `AppUser`) to avoid clashing with Spring Security `User`. Keep `@Table(name="users")`.

#### 8. `Session` → `sessions`

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| id | id | **TEXT** PK | `@id` (no default). Value is sha256(token) hex, **app-assigned** | `String` `@Id` (no generator) | N |
| employeeId | employee_id | UUID | FK | `UUID` (via `employee`) | N |
| createdAt | created_at | TIMESTAMPTZ(6) | `@default(now())` | `Instant` @CreationTimestamp | N |
| expiresAt | expires_at | TIMESTAMPTZ(6) | | `Instant` | N |
| lastSeenAt | last_seen_at | TIMESTAMPTZ(6) | `@default(now())` | `Instant` (initialise to `Instant.now()`) | N |
| ip | ip | TEXT | | `String` | null |
| userAgent | user_agent | TEXT | | `String` | null |
| revokedAt | revoked_at | TIMESTAMPTZ(6) | | `Instant` | null |
| revokedReason | revoked_reason | TEXT | | `String` | null |

- FK `employee_id` **CASCADE**.
- Index `sessions_employee_id_idx`.
- Because the id is assigned (not generated), `save()` calls `merge` and issues an extra SELECT. Implement `Persistable<String>` with an `isNew` flag, or use `EntityManager.persist`.
- Name the entity `UserSession` to avoid clashing with `org.hibernate.Session`.

#### 9. `LoginHistory` → `login_history`

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| id | id | UUID PK | `@default(uuid())` (client) | `UUID` @UuidGenerator | N |
| employeeId | employee_id | UUID | FK | `UUID` (via `employee`) | null |
| identifier | identifier | TEXT | | `String` | N |
| success | success | BOOLEAN | (no default) | `boolean` | N |
| reason | reason | TEXT | | `String` | null |
| ip | ip | TEXT | | `String` | null |
| userAgent | user_agent | TEXT | | `String` | null |
| at | at | TIMESTAMPTZ(6) | `@default(now())` | `Instant` @CreationTimestamp | N |

- FK `employee_id` **SET NULL**.
- Indexes: `login_history_employee_id_at_idx (employee_id, at)` and `login_history_ip_at_idx (ip, at)`. The second is used by rate limiting.

#### 10. `AuthToken` → `auth_tokens`

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| id | id | UUID PK | `@default(uuid())` (client) | `UUID` @UuidGenerator | N |
| tokenHash | token_hash | TEXT | `@unique` | `String` | N |
| employeeId | employee_id | UUID | FK | `UUID` (via `employee`) | N |
| purpose | purpose | `"AuthTokenPurpose"` | enum | `AuthTokenPurpose` | N |
| expiresAt | expires_at | TIMESTAMPTZ(6) | | `Instant` | N |
| usedAt | used_at | TIMESTAMPTZ(6) | | `Instant` | null |
| createdAt | created_at | TIMESTAMPTZ(6) | `@default(now())` | `Instant` @CreationTimestamp | N |

- FK **CASCADE**.
- Unique `auth_tokens_token_hash_key`.
- Index `auth_tokens_employee_id_purpose_idx (employee_id, purpose)`.

#### 11. `Punch` → `punches`

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| id | id | UUID PK | `@default(uuid())` (client) | `UUID` @UuidGenerator | N |
| employeeId | employee_id | UUID | FK | `UUID` | N |
| at | at | TIMESTAMPTZ(6) | (no default) | `Instant` | N |
| kind | kind | `"PunchKind"` | | `PunchKind` | N |
| source | source | `"PunchSource"` | `@default(WEB)` | `PunchSource` = WEB | N |
| ip | ip | TEXT | | `String` | null |
| createdAt | created_at | TIMESTAMPTZ(6) | `@default(now())` | `Instant` @CreationTimestamp | N |

- FK **CASCADE**.
- Index `punches_employee_id_at_idx (employee_id, at)`.

#### 12. `AttendanceDay` → `attendance_days` (composite PK)

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| employeeId | employee_id | UUID | PK part, FK | `UUID` (in `AttendanceDayId`) | N |
| workDate | work_date | DATE | PK part, `@db.Date` | `LocalDate` (in `AttendanceDayId`) | N |
| status | status | `"DayStatus"` | (no default) | `DayStatus` | N |
| firstIn | first_in | TIMESTAMPTZ(6) | | `Instant` | null |
| lastOut | last_out | TIMESTAMPTZ(6) | | `Instant` | null |
| workedMinutes | worked_minutes | INTEGER | `@default(0)` | `int` = 0 | N |
| late | late | BOOLEAN | `@default(false)` | `boolean` | N |
| lateWaived | late_waived | BOOLEAN | `@default(false)` | `boolean` | N |
| regularized | regularized | BOOLEAN | `@default(false)` | `boolean` | N |
| missedCheckout | missed_checkout | BOOLEAN | `@default(false)` (migration 3) | `boolean` | N |
| lopDays | lop_days | **DECIMAL(3,1)** | `@default(0)` (migration 3) | `BigDecimal` = 0, `precision=3, scale=1` | N |
| leaveDays | leave_days | **DECIMAL(3,1)** | `@default(0)` (migration 3) | `BigDecimal` = 0, `precision=3, scale=1` | N |
| label | label | TEXT | (migration 3) | `String` | null |
| locked | locked | BOOLEAN | `@default(false)` | `boolean` | N |
| updatedAt | updated_at | TIMESTAMPTZ(6) | **`@updatedAt`, no DB default** | `Instant` @UpdateTimestamp | N |

- PK `attendance_days_pkey (employee_id, work_date)`. Map it as `@EmbeddedId AttendanceDayId(UUID employeeId, LocalDate workDate)` plus `@MapsId("employeeId") @ManyToOne Employee`.
- FK **CASCADE**.
- Index `attendance_days_work_date_idx`.
- DECIMAL(3,1) holds at most 99.9, and one decimal place is stored. Writing 0.25 rounds to 0.3 in PG; see 8.4.

#### 13. `RegularizationRequest` → `regularization_requests`

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| id | id | UUID PK | `@default(uuid())` (client) | `UUID` @UuidGenerator | N |
| employeeId | employee_id | UUID | FK | `UUID` | N |
| workDate | work_date | DATE | | `LocalDate` | N |
| kind | kind | `"RegularizationKind"` | | `RegularizationKind` | N |
| proposedIn | proposed_in | TIMESTAMPTZ(6) | | `Instant` | null |
| proposedOut | proposed_out | TIMESTAMPTZ(6) | | `Instant` | null |
| reason | reason | TEXT | | `String` | null |
| status | status | `"RequestStatus"` | `@default(PENDING)` | `RequestStatus` = PENDING | N |
| decidedById | decided_by | UUID | FK | `UUID` | null |
| decidedAt | decided_at | TIMESTAMPTZ(6) | | `Instant` | null |
| decidedRole | decided_role | TEXT | | `String` | null |
| createdAt | created_at | TIMESTAMPTZ(6) | `@default(now())` | `Instant` @CreationTimestamp | N |

- Relations:
  - `employee`: relation `"RegEmployee"`, FK `employee_id`, **CASCADE**.
  - `decidedBy`: relation `"RegDecider"`, FK `decided_by`, **SET NULL**.
- Indexes: `regularization_requests_status_idx` and `regularization_requests_employee_id_work_date_idx`.

#### 14. `Holiday` → `holidays`

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| id | id | UUID PK | `@default(uuid())` (client) | `UUID` @UuidGenerator | N |
| date | date | DATE | | `LocalDate` | N |
| name | name | TEXT | | `String` | N |
| region | region | `"HolidayRegion"` | | `HolidayRegion` | N |
| createdById | created_by | UUID | FK | `UUID` | null |
| createdAt | created_at | TIMESTAMPTZ(6) | `@default(now())` | `Instant` @CreationTimestamp | N |

- FK `created_by` **SET NULL**.
- Unique `holidays_date_region_key (date, region)` → `@UniqueConstraint(name="holidays_date_region_key", columnNames={"date","region"})`.

#### 15. `LeaveType` → `leave_types` (natural TEXT PK)

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| code | code | TEXT PK | `@id` (CL/EL/CO/LOP, **app-assigned**) | `String` `@Id` | N |
| name | name | TEXT | | `String` | N |
| annualQuota | annual_quota | DECIMAL(5,2) | `@default(0)` | `BigDecimal` = 0 | N |
| accrualPerMonth | accrual_per_month | DECIMAL(5,2) | `@default(0)` | `BigDecimal` | N |
| carryForwardCap | carry_forward_cap | DECIMAL(5,2) | `@default(0)` | `BigDecimal` | N |
| maxStretch | max_stretch | DECIMAL(5,2) | | `BigDecimal` | null |
| encashPerYear | encash_per_year | DECIMAL(5,2) | `@default(0)` | `BigDecimal` | N |
| encashAfterMonths | encash_after_months | INTEGER | `@default(0)` | `int` | N |
| expiryDays | expiry_days | INTEGER | | `Integer` | null |
| paid | paid | BOOLEAN | `@default(true)` | `boolean` = true | N |
| needsBalance | needs_balance | BOOLEAN | `@default(true)` | `boolean` = true | N |
| sortOrder | sort_order | INTEGER | `@default(0)` | `int` | N |
| active | active | BOOLEAN | `@default(true)` | `boolean` = true | N |

- Inverse relations: `ledger` and `requests`.
- Inbound FKs are **RESTRICT** (Prisma's implicit default for a required relation without `onDelete`).

#### 16. `LeaveLedger` → `leave_ledger`

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| id | id | UUID PK | `@default(uuid())` (client) | `UUID` @UuidGenerator | N |
| employeeId | employee_id | UUID | FK | `UUID` | N |
| leaveType | leave_type | TEXT | FK → leave_types.code | `String` (via `type`) | N |
| delta | delta | DECIMAL(6,2) | (signed) | `BigDecimal` `precision=6, scale=2` | N |
| reason | reason | `"LedgerReason"` | | `LedgerReason` | N |
| refId | ref_id | TEXT | free-form (not an FK) | `String` | null |
| note | note | TEXT | | `String` | null |
| expiresOn | expires_on | DATE | comp-off credits | `LocalDate` | null |
| at | at | TIMESTAMPTZ(6) | `@default(now())` | `Instant` @CreationTimestamp | N |

- Relations:
  - `employee`: relation `"LedgerEmployee"`, **CASCADE**.
  - `type`: `@ManyToOne LeaveType` with `@JoinColumn("leave_type")`, **RESTRICT**.
- Index `leave_ledger_employee_id_leave_type_idx`.

#### 17. `LeaveRequest` → `leave_requests`

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| id | id | UUID PK | `@default(uuid())` (client) | `UUID` @UuidGenerator | N |
| employeeId | employee_id | UUID | FK | `UUID` | N |
| leaveType | leave_type | TEXT | FK → leave_types.code | `String` | N |
| fromDate | from_date | DATE | | `LocalDate` | N |
| toDate | to_date | DATE | | `LocalDate` | N |
| part | part | `"LeavePart"` | `@default(FULL)` | `LeavePart` = FULL | N |
| days | days | DECIMAL(5,2) | | `BigDecimal` | N |
| lopDays | lop_days | DECIMAL(5,2) | `@default(0)` | `BigDecimal` = 0 | N |
| reason | reason | TEXT | | `String` | null |
| status | status | `"RequestStatus"` | `@default(PENDING)` | `RequestStatus` = PENDING | N |
| decidedById | decided_by | UUID | FK | `UUID` | null |
| decidedAt | decided_at | TIMESTAMPTZ(6) | | `Instant` | null |
| appliedAt | applied_at | TIMESTAMPTZ(6) | `@default(now())` | `Instant` @CreationTimestamp | N |

- Relations:
  - `employee`: relation `"LeaveEmployee"`, **CASCADE**.
  - `decidedBy`: relation `"LeaveDecider"`, FK `decided_by`, **SET NULL**.
  - `type`: FK `leave_type`, **RESTRICT**.
- Indexes: `leave_requests_status_idx` and `leave_requests_employee_id_from_date_idx`.

#### 18. `SalaryStructure` → `salary_structures`

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| id | id | UUID PK | `@default(uuid())` (client) | `UUID` @UuidGenerator | N |
| employeeId | employee_id | UUID | FK | `UUID` | N |
| effectiveFrom | effective_from | DATE | | `LocalDate` | N |
| basic | basic | INTEGER | monthly paise | `int` | N |
| hra | hra | INTEGER | paise | `int` | N |
| lta | lta | INTEGER | paise | `int` | N |
| special | special | INTEGER | paise | `int` | N |
| reason | reason | `"StructureReason"` | | `StructureReason` | N |
| createdById | created_by | UUID | FK | `UUID` | null |
| createdAt | created_at | TIMESTAMPTZ(6) | `@default(now())` | `Instant` @CreationTimestamp | N |

- Relations:
  - `employee`: relation `"StructureEmployee"`, **CASCADE**.
  - `createdBy`: relation `"StructureCreatedBy"`, FK `created_by`, **SET NULL**.
- Unique `salary_structures_employee_id_effective_from_key (employee_id, effective_from)`.
- Salary entity: it must never be serialised to the HR role.

#### 19. `PayrollRun` → `payroll_runs`

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| id | id | UUID PK | `@default(uuid())` (client) | `UUID` @UuidGenerator | N |
| month | month | TEXT | `@unique`, `"2026-09"` | `String` (convert to `YearMonth` in the service) | N |
| status | status | `"PayrollStatus"` | `@default(OPEN)` | `PayrollStatus` = OPEN | N |
| stage | stage | `"PayrollStage"` | `@default(LOCK_ATTENDANCE)` | `PayrollStage` = LOCK_ATTENDANCE | N |
| attendanceLockedAt | attendance_locked_at | TIMESTAMPTZ(6) | | `Instant` | null |
| computedAt | computed_at | TIMESTAMPTZ(6) | | `Instant` | null |
| approvedById | approved_by | UUID | FK | `UUID` | null |
| approvedAt | approved_at | TIMESTAMPTZ(6) | | `Instant` | null |
| closedById | closed_by | UUID | FK | `UUID` | null |
| closedAt | closed_at | TIMESTAMPTZ(6) | | `Instant` | null |
| payDate | pay_date | DATE | | `LocalDate` | null |
| createdAt | created_at | TIMESTAMPTZ(6) | `@default(now())` | `Instant` @CreationTimestamp | N |

- Relations:
  - `approvedBy`: relation `"RunApprovedBy"`, FK `approved_by`, **SET NULL**.
  - `closedBy`: relation `"RunClosedBy"`, FK `closed_by`, **SET NULL**.
  - Inverse: `adjustments` and `payslips`.
- Unique `payroll_runs_month_key`.

#### 20. `PayrollAdjustment` → `payroll_adjustments` (composite PK)

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| runId | run_id | UUID | PK part, FK | `UUID` (in `PayrollAdjustmentId`) | N |
| employeeId | employee_id | UUID | PK part, FK | `UUID` (in `PayrollAdjustmentId`) | N |
| lopDays | lop_days | DECIMAL(5,2) | null means "use attendance" | `BigDecimal` | **null (semantic)** |
| bonus | bonus | INTEGER | `@default(0)`, paise | `int` = 0 | N |
| otherDeduction | other_deduction | INTEGER | `@default(0)`, paise | `int` = 0 | N |
| note | note | TEXT | | `String` | null |
| updatedById | updated_by | UUID | FK | `UUID` | null |
| updatedAt | updated_at | TIMESTAMPTZ(6) | **`@updatedAt`, no DB default** | `Instant` @UpdateTimestamp | N |

- PK `(run_id, employee_id)`.
- Relations:
  - `run`: `@MapsId("runId")`, FK `run_id`, **CASCADE**.
  - `employee`: `@MapsId("employeeId")`, relation `"AdjEmployee"`, **CASCADE**.
  - `updatedBy`: relation `"AdjUpdatedBy"`, FK `updated_by`, **SET NULL**.

#### 21. `Payslip` → `payslips` (composite PK)

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| runId | run_id | UUID | PK part, FK | `UUID` | N |
| employeeId | employee_id | UUID | PK part, FK | `UUID` | N |
| snapshot | snapshot | **JSONB** | `Json`, immutable once closed | `JsonNode` (or a `PayslipSnapshot` record) `@JdbcTypeCode(SqlTypes.JSON)` | N |
| pdfPath | pdf_path | TEXT | | `String` | null |
| publishedAt | published_at | TIMESTAMPTZ(6) | `@default(now())` | `Instant` @CreationTimestamp | N |

- PK `(run_id, employee_id)`.
- Relations:
  - `run`: FK, **RESTRICT**.
  - `employee`: FK, **RESTRICT**.
- Make the entity `@Immutable` after close, or mark `snapshot` `updatable=false`.

#### 22. `TaxYear` → `tax_years` (composite PK including an enum)

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| fy | fy | TEXT | PK part, `"2026-27"` | `String` (in `TaxYearId`) | N |
| regime | regime | `"TaxRegime"` | PK part | `TaxRegime` (in `TaxYearId`, with NAMED_ENUM on the embeddable field) | N |
| stdDeduction | std_deduction | INTEGER | **rupees** (not paise) | `int` | N |
| rebateLimit | rebate_limit | INTEGER | rupees | `int` | N |
| marginalRelief | marginal_relief | BOOLEAN | `@default(false)` | `boolean` | N |
| cessRate | cess_rate | DECIMAL(5,4) | | `BigDecimal` `precision=5, scale=4` | N |
| slabs | slabs | JSONB | `[{upto: number\|null, rate}]` | `List<Slab>` / `JsonNode` `@JdbcTypeCode(SqlTypes.JSON)` | N |
| surcharge | surcharge | JSONB | `[{above, rate}]` | `List<Surcharge>` / `JsonNode` `@JdbcTypeCode(SqlTypes.JSON)` | N |

- PK `(fy, regime)`.
- There are no FKs.

#### 23. `TaxSection` → `tax_sections` (composite PK)

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| fy | fy | TEXT | PK part | `String` | N |
| key | key | TEXT | PK part (HRA, 80C, …) | `String` | N |
| label | label | TEXT | | `String` | N |
| cap | cap | INTEGER | rupees, null means no cap | `Integer` | null |
| sortOrder | sort_order | INTEGER | `@default(0)` | `int` | N |

- PK `(fy, key)`.
- There are no FKs. In particular, `fy` is **not** an FK to tax_years.

#### 24. `TaxProfile` → `tax_profiles` (composite PK)

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| employeeId | employee_id | UUID | PK part, FK | `UUID` (in `TaxProfileId`) | N |
| fy | fy | TEXT | PK part | `String` (in `TaxProfileId`) | N |
| regime | regime | `"TaxRegime"` | `@default(NEW)` | `TaxRegime` = NEW | N |
| regimeOverridden | regime_overridden | BOOLEAN | `@default(false)` | `boolean` | N |
| metro | metro | BOOLEAN | `@default(false)` | `boolean` | N |
| parentsSenior | parents_senior | BOOLEAN | `@default(false)` (migration 4) | `boolean` | N |
| prevEmployerIncome | prev_employer_income | **BIGINT** | `@default(0)`, paise | `long` = 0 | N |
| prevEmployerTds | prev_employer_tds | **BIGINT** | `@default(0)`, paise | `long` = 0 | N |
| landlordPan | landlord_pan | TEXT | | `String` | null |
| updatedAt | updated_at | TIMESTAMPTZ(6) | **`@updatedAt`, no DB default** | `Instant` @UpdateTimestamp | N |

- PK `(employee_id, fy)`.
- `employee` uses `@MapsId("employeeId")`, **CASCADE**.

#### 25. `DeclarationItem` → `declaration_items`

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| id | id | UUID PK | `@default(uuid())` (client) | `UUID` @UuidGenerator | N |
| employeeId | employee_id | UUID | FK | `UUID` | N |
| fy | fy | TEXT | | `String` | N |
| section | section | TEXT | | `String` | N |
| itemKey | item_key | TEXT | | `String` | N |
| amount | amount | **BIGINT** | `@default(0)`, paise | `long` = 0 | N |
| proofPath | proof_path | TEXT | | `String` | null |
| proofName | proof_name | TEXT | | `String` | null |
| status | status | `"DeclarationStatus"` | `@default(DRAFT)` | `DeclarationStatus` = DRAFT | N |
| decidedById | decided_by | UUID | FK | `UUID` | null |
| decidedAt | decided_at | TIMESTAMPTZ(6) | | `Instant` | null |
| rejectReason | reject_reason | TEXT | | `String` | null |
| updatedAt | updated_at | TIMESTAMPTZ(6) | **`@updatedAt`, no DB default** | `Instant` @UpdateTimestamp | N |

- Relations:
  - `employee`: relation `"DeclEmployee"`, **CASCADE**.
  - `decidedBy`: relation `"DeclDecider"`, FK `decided_by`, **SET NULL**.
- Unique `declaration_items_employee_id_fy_item_key_key (employee_id, fy, item_key)`. This is the upsert key.
- Index `declaration_items_status_idx`.
- There is no `created_at` column.

#### 26. `Notification` → `notifications`

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| id | id | UUID PK | `@default(uuid())` (client) | `UUID` @UuidGenerator | N |
| employeeId | employee_id | UUID | FK | `UUID` | N |
| text | text | TEXT | | `String` | N |
| link | link | TEXT | | `String` | null |
| readAt | read_at | TIMESTAMPTZ(6) | | `Instant` | null |
| createdAt | created_at | TIMESTAMPTZ(6) | `@default(now())` | `Instant` @CreationTimestamp | N |

- FK **CASCADE**.
- Index `notifications_employee_id_read_at_idx (employee_id, read_at)`.

#### 27. `AuditLog` → `audit_log`

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| id | id | UUID PK | `@default(uuid())` (client) | `UUID` @UuidGenerator | N |
| actorId | actor_id | UUID | FK | `UUID` | null |
| action | action | TEXT | for example `auth.login` | `String` | N |
| entity | entity | TEXT | | `String` | N |
| entityId | entity_id | **TEXT** (not uuid) | | `String` | null |
| before | before | JSONB | `Json?` | `JsonNode` `@JdbcTypeCode(SqlTypes.JSON)` | null |
| after | after | JSONB | `Json?` | `JsonNode` `@JdbcTypeCode(SqlTypes.JSON)` | null |
| at | at | TIMESTAMPTZ(6) | `@default(now())` | `Instant` @CreationTimestamp | N |
| ip | ip | TEXT | | `String` | null |

- `actor` is `@ManyToOne Employee` with FK `actor_id`, **SET NULL**.
- Indexes: `audit_log_at_idx`, `audit_log_entity_entity_id_idx (entity, entity_id)`, `audit_log_actor_id_idx`.
- Recommend `@Immutable`, because the log is append-only.

#### 28. `JobRun` → `job_runs` (added in migration 3)

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| id | id | UUID PK | `@default(uuid())` (client) | `UUID` @UuidGenerator | N |
| name | name | TEXT | | `String` | N |
| key | key | TEXT | for example `2026-09` | `String` | N |
| ranAt | ran_at | TIMESTAMPTZ(6) | `@default(now())` | `Instant` @CreationTimestamp | N |
| ok | ok | BOOLEAN | `@default(true)` | `boolean` = true | N |
| detail | detail | JSONB | `Json?` | `JsonNode` `@JdbcTypeCode(SqlTypes.JSON)` | null |

- Unique `job_runs_name_key_key (name, key)`. This is the idempotency key, and the app relies on the unique violation.

#### 29. `Setting` → `settings`

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| key | key | TEXT PK | `@id`, **app-assigned** (for example `attendance.policy`) | `String` `@Id` | N |
| value | value | JSONB | `Json` | `JsonNode` `@JdbcTypeCode(SqlTypes.JSON)` | N |
| updatedAt | updated_at | TIMESTAMPTZ(6) | **`@updatedAt`, no DB default** | `Instant` @UpdateTimestamp | N |

#### 30. `EmailOutbox` → `email_outbox`

| Prisma field | Column | PG type | Prisma attrs | Java type | Null |
|---|---|---|---|---|---|
| id | id | UUID PK | `@default(uuid())` (client) | `UUID` @UuidGenerator | N |
| to | **"to"** (reserved) | TEXT | | `String` with `@Column(name = "\"to\"")` | N |
| subject | subject | TEXT | | `String` | N |
| text | text | TEXT | | `String` | N |
| html | html | TEXT | | `String` | null |
| status | status | `"EmailStatus"` | `@default(QUEUED)` | `EmailStatus` = QUEUED | N |
| error | error | TEXT | | `String` | null |
| createdAt | created_at | TIMESTAMPTZ(6) | `@default(now())` | `Instant` @CreationTimestamp | N |
| sentAt | sent_at | TIMESTAMPTZ(6) | | `Instant` | null |

- Index `email_outbox_status_idx`.

### 6.3 Composite keys summary (8 tables)

| Table | PK columns | Recommended mapping |
|---|---|---|
| role_permissions | role_id, permission_key | `@EmbeddedId` + `@MapsId("roleId")` |
| role_assignable_roles | role_id, assignable_role_id | `@EmbeddedId` + 2×`@MapsId` (same target `Role`) |
| attendance_days | employee_id, work_date | `@EmbeddedId(UUID, LocalDate)` + `@MapsId("employeeId")` |
| payroll_adjustments | run_id, employee_id | `@EmbeddedId` + 2×`@MapsId` |
| payslips | run_id, employee_id | `@EmbeddedId` + 2×`@MapsId` |
| tax_years | fy, regime | `@EmbeddedId(String, TaxRegime)` with the NAMED_ENUM annotations inside the embeddable |
| tax_sections | fy, key | `@EmbeddedId(String, String)` |
| tax_profiles | employee_id, fy | `@EmbeddedId` + `@MapsId("employeeId")` |

Shared PK: `users.employee_id` uses `@Id` + `@MapsId @OneToOne`. Natural TEXT PKs: `sessions.id`, `leave_types.code`, `settings.key`.

All embeddable id classes must implement `Serializable`, `equals` and `hashCode`. Because these ids are always assigned, Spring Data `save()` will `merge` (an extra SELECT). Implement `Persistable` or use repository custom `persist()` for hot paths such as the attendance_days upsert.

### 6.4 FK / ON DELETE matrix (38 FKs, all `ON UPDATE CASCADE`)

| ON DELETE | FKs |
|---|---|
| **CASCADE** (17) | role_permissions.role_id; role_assignable_roles.role_id; role_assignable_roles.assignable_role_id; users.employee_id; sessions.employee_id; auth_tokens.employee_id; punches.employee_id; attendance_days.employee_id; regularization_requests.employee_id; leave_ledger.employee_id; leave_requests.employee_id; salary_structures.employee_id; payroll_adjustments.run_id; payroll_adjustments.employee_id; tax_profiles.employee_id; declaration_items.employee_id; notifications.employee_id |
| **SET NULL** (15) | departments.head_employee_id; departments.created_by; employees.reporting_manager_id; employees.shift_id; employees.created_by; login_history.employee_id; regularization_requests.decided_by; holidays.created_by; leave_requests.decided_by; salary_structures.created_by; payroll_runs.approved_by; payroll_runs.closed_by; payroll_adjustments.updated_by; declaration_items.decided_by; audit_log.actor_id |
| **RESTRICT** (6) | employees.department_id; employees.role_id; leave_ledger.leave_type; leave_requests.leave_type; payslips.run_id; payslips.employee_id. leave_type FKs are Prisma's implicit default (no `onDelete` given). |

Employees are never hard-deleted in the app (exit sets `status=EXITED`). The RESTRICT on payslips makes a hard delete of anyone with a payslip fail regardless.

### 6.5 Id generation finding

- **No table has a DB-side id default.** `grep gen_random_uuid|uuid_generate` over all migrations returns 0 hits. Every `id UUID NOT NULL` has no `DEFAULT`, and `pgcrypto`/`uuid-ossp` are not installed.
- All 21 `@default(uuid())` ids are therefore **app-generated** by the Prisma client as random v4 UUIDs.
- In JPA use `@Id @GeneratedValue @UuidGenerator` (Hibernate 6.2+; default style RANDOM = v4), or assign `UUID.randomUUID()` in a `@PrePersist`/constructor. **Do not** use `GenerationType.IDENTITY` or `SEQUENCE` (no sequences exist).
- `@UuidGenerator(style = TIME)` (v7-like) is acceptable for new rows. Ordering is irrelevant, because no code sorts by id.
- 9 PKs have no generator at all: 8 composite tables + `users`.
- 3 natural TEXT PKs (`sessions.id` = sha256 hex, `leave_types.code`, `settings.key`) are app-assigned.
- Because ids are generated before insert, Hibernate JDBC batching works (`hibernate.jdbc.batch_size=50`, `order_inserts=true`). This replaces Prisma `createMany`.
- The only other `randomUUID()` in `src/` is the proof-upload filename (`src/app/api/declarations/proof/route.ts:40`). It is not a DB id.

---

## 7. Flyway baseline

### 7.1 Current state

- Prisma tracks applied migrations in **`public._prisma_migrations`**. Its columns are `id, checksum, finished_at, migration_name, logs, rolled_back_at, started_at, applied_steps_count`. Flyway ignores it, and `ddl-auto=validate` ignores it too because no entity maps to it.
- Applied migrations, in order:

| # | Prisma migration dir | Contents | Has down.sql |
|---|---|---|---|
| 1 | `20261005220955_init` | 17 `CREATE TYPE … AS ENUM`, 29 `CREATE TABLE`, 32 indexes (11 unique + 21 plain), 38 FKs (17 CASCADE, 15 SET NULL, 6 RESTRICT) | yes. It drops FKs, tables and types, and is Prisma-generated with the `"public".` prefix |
| 2 | `20261006134356_department_archive` | `ALTER TABLE departments ADD COLUMN archived_at TIMESTAMPTZ(6)` | yes (hand-written) |
| 3 | `20261006143825_attendance_engine` | `ALTER TYPE "DayStatus" ADD VALUE 'NOT_TRACKED'`; attendance_days ADD `label`, `leave_days DECIMAL(3,1)`, `lop_days DECIMAL(3,1)`, `missed_checkout`; `CREATE TABLE job_runs` + unique index | yes. It rebuilds the DayStatus type and **deletes NOT_TRACKED rows** |
| 4 | `20261006193828_tax_parents_senior` | `ALTER TABLE tax_profiles ADD COLUMN parents_senior BOOLEAN NOT NULL DEFAULT false` | yes |

`migration_lock.toml` has `provider = "postgresql"`.

### 7.2 Creating `V1__baseline.sql`

Location: `src/main/resources/db/migration/V1__baseline.sql`.

**Preferred approach: fold the final state into a single clean DDL.** Do not just concatenate the four files. That would work, but it would keep the `ALTER TYPE … ADD VALUE` step, which cannot run inside a transaction block on PG < 12 and is clumsy to keep. Generate the DDL in one of two ways:

1. Use `npx prisma migrate diff --from-empty --to-schema prisma/schema.prisma --script > V1__baseline.sql`. These are the Prisma 7 flag names; Prisma 6 used `--to-schema-datamodel`. This produces the exact same DDL and constraint/index names that Prisma created, with `NOT_TRACKED` inline in `CREATE TYPE "DayStatus"` and the added columns inline.
2. Cross-check with `pg_dump --schema-only --no-owner --no-privileges --no-comments --exclude-table=_prisma_migrations -n public jurisone > dump.sql` against a DB that has had all 4 migrations applied.
   - Diff the two outputs. Expect only cosmetic differences, such as column order: in a live DB, columns added by ALTER sit at the **end** of the table (for example `departments.archived_at`, `attendance_days.label/leave_days/lop_days/missed_checkout`, `tax_profiles.parents_senior`), whereas prisma diff may place them in schema order.
   - Column order does not matter for JPA. For byte-for-byte parity of fresh and existing DBs, prefer the **pg_dump** output, cleaned of `SET` statements, `OWNER TO` and `SELECT pg_catalog.set_config`.

Strip anything not in the schema: seed data and `_prisma_migrations`. Seed data (roles, leave types, shifts, tax years, settings) currently lives in `prisma/seed.ts` and must be ported separately, for example to a Spring `ApplicationRunner` or Flyway `R__seed_reference_data.sql` for reference data only. This is out of scope here.

Quick-and-safe alternative: concatenate `migration.sql` 1→4 verbatim into V1. PG 16 allows `ALTER TYPE ADD VALUE` inside a transaction, provided the new value is not used in the same transaction, and V1 never uses it. This guarantees an identical result to Prisma's history, but it is less readable.

### 7.3 Flyway configuration (existing DBs are not re-migrated)

```properties
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
spring.flyway.baseline-on-migrate=true
spring.flyway.baseline-version=1
spring.flyway.baseline-description=Prisma baseline (20261005220955_init..20261006193828_tax_parents_senior)
spring.flyway.validate-on-migrate=true
spring.flyway.out-of-order=false
spring.flyway.clean-disabled=true
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.properties.hibernate.jdbc.time_zone=UTC
```

Behaviour:
- **Existing DB** (tables present, no `flyway_schema_history`): `baselineOnMigrate` creates `flyway_schema_history` with a single BASELINE row at version 1 and **skips V1**. Later V2+ run normally.
- **Fresh DB** (empty schema): there is no baseline, so V1 runs and creates everything.
- **Guard (recommended):** add `db/callback/beforeBaseline.sql`, or a Java `Callback`, that aborts unless the DB is exactly at Prisma migration 4:

  ```sql
  DO $$ BEGIN
    IF (SELECT count(*) FROM _prisma_migrations WHERE finished_at IS NOT NULL AND rolled_back_at IS NULL) <> 4
       OR NOT EXISTS (SELECT 1 FROM _prisma_migrations WHERE migration_name = '20261006193828_tax_parents_senior' AND finished_at IS NOT NULL)
    THEN RAISE EXCEPTION 'DB is not at Prisma migration 20261006193828_tax_parents_senior; refusing to baseline';
    END IF;
  END $$;
  ```

  Without the guard, a DB that is behind (for example missing `parents_senior`) would be baselined silently and then fail `ddl-auto=validate`, or worse, it would pass validation if the column is unmapped.
- **Keep `_prisma_migrations`** while the Next.js app can still run against the same DB, for rollback to Prisma. Drop it later in a dedicated `V<n>__drop_prisma_migrations.sql` once the cut-over is final.
- **Single owner of DDL:** from the cut-over date, schema changes go **only** through Flyway. Freeze `prisma migrate dev/deploy` (remove the `db:migrate` / `db:deploy` npm scripts, or document the freeze). If both apps must coexist, mirror every Flyway migration into `prisma/schema.prisma` (with `prisma db pull`) but never run `prisma migrate`.

### 7.4 Continuing the `down.sql` convention

Flyway Community has no undo. Undo migrations (`U<n>__*.sql`) are Teams/Enterprise only. Keep the existing practice by hand:
- For each `V<n>__<desc>.sql` in `db/migration/`, add `db/rollback/U<n>__<desc>.sql`.
  - This must be **outside** the Flyway `locations`, so it is never auto-applied.
  - Use the `U` prefix so the files remain compatible with Flyway Teams if adopted later.
- `U1__baseline.sql` = the existing `init/down.sql`, adjusted for the final state: also drop `job_runs` and `departments.archived_at` / `parents_senior` (these go away with their tables anyway). In practice it is "drop all tables + 17 types", and is useful only for dev resets.
- Port the three existing incremental `down.sql` files as **documentation** of the pre-baseline history (`db/rollback/prisma-history/`). They are not runnable against Flyway.
- Rollback procedure:
  1. Run `U<n>` manually: `psql -f`, or Flyway `executeInTransaction` via a one-off.
  2. Then `DELETE FROM flyway_schema_history WHERE version = '<n>'` (or `flyway repair`).
  3. Document this in the README.
- Generation tip for V/U pairs:
  - Diff the schema with `pg_dump` before/after.
  - Or keep `prisma/schema.prisma` in sync and use `prisma migrate diff --from-schema <new> --to-schema <prev> --script`. This is the same trick CLAUDE.md documents for `down.sql`.
  - Hibernate's `SchemaManagementTool` can emit a script against the entities for a starting point, but never as the source of truth.
- Enum value removals need the type-rebuild pattern used in `attendance_engine/down.sql`: rename the type, create the new one, `ALTER COLUMN … USING ::text::`, then drop the old type.
- Enum value additions (`ALTER TYPE … ADD VALUE`) are fine in Flyway on PG 16. If a migration both adds and uses a value, split it into two migrations, or set `-- flyway:executeInTransaction=false` in the script header.

---

## 8. Schema-mapping risks

### 8.1 `@updatedAt` is client-side only (5 + 2 tables)
`employees`, `attendance_days`, `payroll_adjustments`, `tax_profiles`, `declaration_items` and `settings` have `updated_at TIMESTAMPTZ(6) NOT NULL` with **no default and no trigger**. Every entity needs `@UpdateTimestamp`.
- Any **bulk JPQL/native `UPDATE`** bypasses it and must set `updated_at = now()` explicitly.
- Prisma `updateMany` does bump `@updatedAt`, so the semantics must be preserved. For example, `auth_tokens.updateMany` in `src/lib/auth-tokens.ts` touches no updatedAt table, but any port of an `updateMany` on the six tables above must set it.
- An INSERT through native SQL (for example an `ON CONFLICT` upsert, 8.8) must supply `updated_at` or it fails NOT NULL.

### 8.2 `@default(uuid())` is client-side only
See 6.5. Native-SQL inserts (seed scripts, upserts, `INSERT … SELECT`) must supply `id`, for example with `gen_random_uuid()`, which is built into PG 13+ core and needs no extension. Optionally add `DEFAULT gen_random_uuid()` in a V2 migration as a safety net. That is a schema change, but a harmless one: `validate` does not check defaults.

### 8.3 Scalar defaults vs Hibernate full-column inserts
Hibernate writes every mapped column. A `null` in a wrapper field overrides the DB default and violates NOT NULL. This affects every `@default(...)` column listed above, for example `employees.status`, `portal_access`, `punches.source`, `leave_requests.part/status/lop_days`, `payroll_runs.status/stage`, `tax_profiles.*` and the `*.created_at` columns. Mitigate with field initialisers (or primitives) plus `@CreationTimestamp`, or with `@DynamicInsert`.

### 8.4 Decimal precision and arithmetic
- Columns: DECIMAL(3,1) on attendance_days.lop_days/leave_days (max 99.9, one decimal); (5,2) on leave quotas, leave_requests.days/lop_days and payroll_adjustments.lop_days; (6,2) on leave_ledger.delta; (5,4) on tax_years.cess_rate.
- Prisma returns `Decimal.js` and the TS code immediately converts with `Number(...)` and rounds via `round2` (`src/lib/leave/service.ts`, `src/lib/jobs.ts:121`, `src/app/(app)/home/page.tsx:63`).
- In Java, keep values as `BigDecimal` with an explicit `setScale(2, HALF_UP)`. Port `round2` semantics exactly: JS `Math.round` is half-up toward +∞, which differs from `HALF_UP` for negatives such as -0.125. Test negative ledger deltas.
- Compare with `compareTo`, not `equals` (`0.0` and `0.00` are not equal).
- PG silently rounds values whose scale is too large. Values outside precision raise `numeric field overflow`. Prisma had the same DB behaviour, but JS `Number` hid scale.
- Jackson serialises `BigDecimal` as a JSON number, and possibly as `1E+1` unless `WRITE_BIGDECIMAL_AS_PLAIN` is enabled. The current front-end receives plain JS numbers, so enable that feature.

### 8.5 BigInt (paise) and JSON serialisation
- `tax_profiles.prev_employer_income/prev_employer_tds` and `declaration_items.amount` are BIGINT. Prisma returns JS `bigint`, and the code converts with `BigInt(x*100)` (`declare/actions.ts:69`, `tax/actions.ts:55`) or `Number(...)` (`src/lib/tax/service.ts:6`, `format.ts:44`).
- `JSON.stringify` cannot serialise bigint, so `src/lib/audit.ts:17` stringifies bigints in audit `before/after` payloads. **Existing audit JSON therefore contains these values as strings.** Java `long` serialises as a number, so the audit history will mix types. Readers of `audit_log.before/after` must accept both.
- `BigInt(f.amount * 100)` throws in JS on non-integer floats (for example 0.1*100 = 10.000000000000002). Java should parse with `new BigDecimal(str).movePointRight(2).longValueExact()`.
- `long` is far beyond any realistic paise value, so overflow is not a concern. The JS-client 2^53 limit is also irrelevant at this scale.

### 8.6 Case-insensitive search (`mode: "insensitive"`) → ILIKE, 11 sites

| File:line | Usage | JPA equivalent |
|---|---|---|
| `src/app/(app)/approvals/page.tsx:24` | `employee.fullName contains q` | `lower(e.fullName) like lower(:q)` with `%…%` / native `ILIKE` |
| `src/app/(app)/departments/actions.ts:21` | `name equals` (duplicate check) | `lower(d.name) = lower(:name)` / `existsByNameIgnoreCase` |
| `src/app/(app)/directory/page.tsx:27` | `contains` over several fields | `ILIKE` / `lower() like` |
| `src/app/(app)/roles/actions.ts:66` | role `name equals` (duplicate check) | `existsByNameIgnoreCase` |
| `src/lib/attendance/register.ts:47` | `contains` | as above |
| `src/lib/audit-view.ts:53-57` | action / entity / entityId / actor.fullName / actor.code `contains` (5 terms, OR) | JPQL with `left join a.actor` + 5×`lower() like` |
| `src/lib/people-query.ts:22` | `contains` | as above |

- Prisma `contains` **does not escape `%` and `_`** in user input; verify on the current version. Decide on explicit escaping (`like :q escape '\'`) in Java, and document any behaviour change.
- The unique indexes on `departments.name` and `roles.name` are case-**sensitive**. The case-insensitive uniqueness is enforced only in app code, so it is racy, and the Java port inherits that race. Optionally add a V2 `CREATE UNIQUE INDEX … (lower(name))`.
- There are no trigram/`citext` indexes. ILIKE `%q%` scans the table, which matches the current behaviour.

### 8.7 Relation filters, `_count` and `groupBy` (no direct JPA equivalent)

| Prisma feature | Sites | JPA translation |
|---|---|---|
| `some` | `people/actions.ts:352` (role.permissions some permissionKey='struct.manage'); `proofs/page.tsx:23` (declarationItems some {fy, OR amount>0 / proofPath not null}); `lib/attendance/service.ts:10` `TRACKED` (role.permissions some 'self'); `lib/audit-view.ts:68` (auditEntries some {}); `lib/payroll/service.ts:64` (salaryStructures some effectiveFrom ≤ last) | `EXISTS (select 1 from RolePermission rp where rp.role = e.role and rp.id.permissionKey = :k)`. With Spring Data Specifications, use `cb.exists(subquery)`. `TRACKED` is reused widely, so make it one reusable `Specification<Employee>`. |
| `none` | `payroll/page.tsx:27`, `lib/dashboard.ts:44` (salaryStructures none effectiveFrom ≤ lastDay) | `NOT EXISTS (…)` |
| `_count` with filtered relation | `departments/page.tsx:22` (employees where status≠EXITED); `people/actions.ts:150` & `lib/auth.ts:47` (directReports where status≠EXITED); `people/actions.ts:213`, `roles/actions.ts:87`, `roles/page.tsx:17` (permissions / employees count) | Correlated subquery in a JPQL constructor/DTO projection (`select new …(d, (select count(e) from Employee e where e.department = d and e.status <> 'EXITED'))`), or `@Formula` on the entity. Avoid `size()` on lazy collections, which causes N+1. `lib/auth.ts:47` runs on **every request** (session load), so make it a single query. |
| `groupBy` + `_sum` / `_count` | `home/page.tsx:62` (leaveLedger by leaveType sum delta); `lib/jobs.ts:119` (by employeeId sum delta); `lib/leave/service.ts:16-17` (ledger sum delta; requests sum days, lopDays where PENDING); `people/page.tsx:30` (employee by status, portalAccess, region `_count`); `lib/tax/rows.ts:15` (declarationItem by employeeId `_count` where PENDING) | JPQL `select l.leaveType, sum(l.delta) … group by l.leaveType` into a record/interface projection. Note that `sum()` over DECIMAL returns `BigDecimal` and `count()` returns `Long`, and both are `null` when there are no rows; the TS code handles this with `?? 0`. |
| `$transaction(async tx => …)` interactive | 15 sites (approvals×2, departments×2, payroll, people×4, proofs, roles×2, settings, structures, auth, payroll/close) | `@Transactional` service methods. Prisma's default interactive-tx **timeout is 5 s and isolation is READ COMMITTED**. Spring has no timeout by default, and the PG default is the same READ COMMITTED. |
| `$transaction([...])` batch | `lib/auth-tokens.ts:11` (updateMany + create) | `@Transactional` + `@Modifying` JPQL update + `save`. |
| `createMany` | `lib/jobs.ts:86` (leave_ledger), `lib/payroll/close.ts:46` (payslips), `:49` and `people/actions.ts:355` (notifications); 19 more in `prisma/seed.ts` | `saveAll` with JDBC batching enabled (6.5). |

### 8.8 `upsert` (11 sites in `src/`, 19 in `prisma/seed.ts`)
Sites: `declare/actions.ts:22,72`; `payroll/actions.ts:44`; `roles/actions.ts:34,50`; `settings/actions.ts:38`; `tax/actions.ts:37,56`; `(auth)/actions.ts:156`; `api/declarations/proof/route.ts:43`; `lib/attendance/service.ts:147`.

- Prisma 4.6+ executes these as native `INSERT … ON CONFLICT DO UPDATE` when the `where` targets one unique key with no nested writes, which covers all of these. That makes them atomic.
- JPA has no upsert. A `findById` followed by `save` races, because two concurrent requests both insert and one gets a unique violation.
- Use one of these:
  - a native `@Modifying @Query("INSERT … ON CONFLICT (…) DO UPDATE SET …")`. This must include `updated_at` and `id` (8.1/8.2) and quote enum casts (`CAST(:status AS "DeclarationStatus")`).
  - find-or-create with retry on `DataIntegrityViolationException`.
- `attendance_days` upsert (`lib/attendance/service.ts:147`) is on the hot path (recompute per day), so prefer native ON CONFLICT there.

### 8.9 Reserved / keyword column names
- `email_outbox."to"`: `TO` is a reserved word in PostgreSQL and needs quoting (`@Column(name="\"to\"")`), or set `hibernate.auto_quote_keyword=true`.
- Other keyword-ish names that are non-reserved in PG but listed in Hibernate's/ANSI keyword sets are fine unquoted: `key` (roles, settings, tax_sections, job_runs), `text`, `at`, `before`, `after`, `value`, `month`, `date`, `name`, `section`, `label`, `cap`, `action`, `entity`, `kind`, `late`, `locked`, `active`, `paid`, `ok`, `detail`. If `auto_quote_keyword` is on, Hibernate quotes some of them, which is harmless because they are lowercase.
- Table `users` is fine. Do not let an entity default to `User`, because `user` is reserved.

### 8.10 Native enum + `ddl-auto=validate`
- Hibernate's validator compares the column's JDBC type/name with the mapping. For PG enums, pgjdbc reports `Types.VARCHAR`/`OTHER` with type name `"EmployeeStatus"` (quoted because it is mixed-case).
- Known friction exists in Hibernate 6.2–6.5 (wrong column type errors for named enums). **Spike this first** with one entity, for example `EmailOutbox`.
- Fallbacks, in order:
  1. Set `columnDefinition = "\"EmployeeStatus\""` (the validator accepts a matching column definition).
  2. Use a custom `@JdbcType(PostgreSQLEnumJdbcType.class)`.
  3. Use the JDBC URL flag `stringtype=unspecified` + plain `@Enumerated(STRING)`.
- **Never** rename the PG types to lowercase; that would change the schema.
- Native queries must cast parameters, as in `CAST(:s AS "RequestStatus")`, because PG will not implicitly cast `varchar` to the enum.

### 8.11 Json columns (6 columns)
`payslips.snapshot`, `tax_years.slabs`, `tax_years.surcharge`, `audit_log.before/after`, `job_runs.detail` and `settings.value` are all **JSONB**.
- Mapping: `@JdbcTypeCode(SqlTypes.JSON)` uses Hibernate's Jackson `FormatMapper`. Configure `hibernate.type.json_format_mapper` to Spring's `ObjectMapper`, so that `JavaTimeModule` and plain BigDecimal apply.
- **JSONB normalises key order and drops duplicate keys.** Existing payslip snapshots round-trip with possibly different key order. That is harmless unless something hashes the raw text.
- Dirty checking: mutable `JsonNode`/`Map` values are compared by value through Hibernate's JSON type, but in-place mutation of an `ObjectNode` may not be detected. Treat JSON fields as immutable and reassign them.
- Prisma distinguishes `DbNull` from `JsonNull`, but the code uses neither (grep finds 0 hits). `audit.ts` turns `null`/`undefined` into "omit", which gives SQL NULL. In Java, map a `null` field to SQL NULL, never to JSON `null`, to stay consistent.
- `snapshot` values contain numbers that were JS doubles, including money in paise as integers. Deserialise into `long`/`BigDecimal`, not `double`.
- `tax_years.slabs` contains `upto: null` for the top slab, so the Java record must use a nullable `Long upto`.

### 8.12 Raw SQL found in `src/` (5 statements in 2 files; none in `prisma/`, `scripts/` or `e2e/`)

| # | Location | Statement | Java port / risk |
|---|---|---|---|
| 1 | `src/app/(app)/people/actions.ts:193` (`nextCode()`, used for the form preview) | `SELECT max(substring(code from 4)::int) AS n FROM employees WHERE code ~ '^JC-[0-9]+$'` | Native `@Query`. It returns `Integer`/null. Postgres-specific `~` regex and `::int`. |
| 2 | `src/app/(app)/people/actions.ts:343` (in tx) | `SELECT pg_advisory_xact_lock(74201)` via `$executeRaw` | Native query inside the same `@Transactional` method. Lock key **74201** must be preserved if old and new apps ever run concurrently. |
| 3 | `src/app/(app)/people/actions.ts:344` (in tx) | the same `max(substring(code from 4)::int)` query | Same as #1, but it must run on the **same connection/transaction** as #2. |
| 4 | `src/lib/jobs.ts:134` | `SELECT pg_try_advisory_lock(74202) AS ok` (**session-level** lock) | **Latent bug:** with a pooled client, #4 and #5 may run on **different connections**. The unlock then returns false and the lock leaks on the first connection until it is recycled. In Java, use `pg_try_advisory_xact_lock(74202)` inside one `@Transactional` job runner, or ShedLock / `@SchedulerLock` (which needs a `shedlock` table, i.e. a V2 migration). |
| 5 | `src/lib/jobs.ts:145` | `SELECT pg_advisory_unlock(74202)` | See #4. |

There is no `$queryRawUnsafe`, `$executeRawUnsafe`, `Prisma.sql` or `Prisma.raw`. All raw calls use tagged templates with no interpolated parameters, so there is no injection risk to carry over.

### 8.13 Dates and time zones
- `@db.Date` values are JS `Date` objects at UTC midnight in Prisma (helpers `isoToDate` and friends in `src/lib/format.ts`). `LocalDate` removes that ambiguity, but every TS helper that does `new Date(iso)` arithmetic must be ported to `LocalDate`/`ZonedDateTime` with the employee's `time_zone` (IANA string column, `employees.time_zone`).
- `attendance_days.work_date` is the shift-start date in the employee's zone. Do not derive it from the JVM default zone.
- Run the JVM with `-Duser.timezone=UTC` and set `hibernate.jdbc.time_zone=UTC`. The container DB already uses `TZ: UTC`.
- TIMESTAMPTZ(6) has microsecond precision. Java `Instant.now()` has nanosecond precision on JDK 17+, and PG rounds on write, so an entity re-read after save can differ from the in-memory value. Truncate to micros (`Instant.now().truncatedTo(MICROS)`) where timestamps are compared, for example session `expires_at` and token `expires_at`.

### 8.14 Other Prisma behaviours to preserve
- **Implicit `select`/`include` projections:** Prisma never loads relations unless asked. JPA lazy loading plus open-session-in-view can cause N+1 queries and accidental serialisation of salary fields to HR. Set `spring.jpa.open-in-view=false` and return DTOs, never entities, especially for `SalaryStructure`, `Payslip`, `TaxProfile`, `DeclarationItem` and the `employees.*_enc` columns.
- **Unique-violation handling:** Prisma error `P2002` is mapped generically to "That already exists." in `src/lib/action.ts:21`; this matters for unique keys such as `job_runs (name, key)`, `employees.email`, `holidays (date, region)` and `salary_structures (employee_id, effective_from)`. In Spring it surfaces as `DataIntegrityViolationException` with `ConstraintViolationException.getConstraintName()`. Constraint names are the Prisma-generated ones listed above (for example `job_runs_name_key_key`), so map them by name.
- **`findUnique` on compound keys** (`employeeId_fy`, `roleId_permissionKey`, `roleId_assignableRoleId`, `runId_employeeId`, `employeeId_workDate`) becomes `findById(new XxxId(...))`.
- **Count queries:** 18 `.count(` calls, which map directly to `countBy…` or JPQL `count`.
- **Seed (`prisma/seed.ts`):** it has 19 upsert/createMany calls and is not schema. It must be re-implemented, idempotent, in Java or SQL, or kept as a TS script for dev only.


---

## 9. Target architecture

```
Browser ──► Next.js :3005 ──────────────► Spring Boot :8080 ──► PostgreSQL :5436
            • pages (RSC) fetch the API     • /api/** REST         (schema unchanged,
              server-side, forwarding the   • session filter        Flyway baseline)
              jo_session cookie             • jobs (@Scheduled)
            • server actions = thin         • files in UPLOAD_DIR
              proxies to the API            • SMTP / email_outbox
            • next.config rewrites
              /api/* → :8080
```

1. **Same origin for the browser.** `next.config.ts` rewrites `/api/:path*` to `${BACKEND_URL}/api/:path*`. The browser, the downloads, and Playwright's `page.request.get("/api/…")` all keep hitting port 3005, so the e2e tests stay unchanged and the `SameSite=Lax` cookie keeps working. CORS on Spring is still configured to allow only `FRONTEND_ORIGIN` (default `http://localhost:3005`), with credentials, for any direct browser call.
2. **Pages stay server-rendered.** Each `page.tsx` swaps its Prisma/service calls for one `api.get("/api/…")` call made on the server with the incoming `jo_session` cookie forwarded. The JSON is shaped exactly like the props the client components receive today, so **client components do not change**.
3. **Server actions become thin proxies.** Each `actions.ts` keeps its exported function names and signatures. Its body becomes `return api.post("/api/…", input)` plus the existing `revalidatePath(...)` calls. Client components, forms and toasts are untouched, and cache invalidation keeps working. The auth actions also copy Spring's `Set-Cookie` into `cookies()` and keep their `redirect()`.
4. **Route handlers** (`src/app/api/**/route.ts`) are deleted in the final cleanup; the rewrite then serves the same paths from Spring with identical status codes, headers, CSV bytes and PDFs.
5. **Strangler, module by module.** Until a module is switched, its pages and actions keep using Prisma. Both backends share one DB, so a half-migrated app works at every commit.
6. **Jobs run in one place.** The Spring jobs module ships disabled (`JOBS_ENABLED=false`). When the attendance and leave modules are switched, Spring's jobs are enabled and Next's `instrumentation.ts` runner is disabled in the same commit.

### 9.1 Backend layout (`/backend`)

```
backend/
  mvnw, mvnw.cmd, .mvn/wrapper/       Maven Wrapper
  pom.xml                              Spring Boot 3.x, Java 17
  Dockerfile                           multi-stage (eclipse-temurin:17)
  src/main/java/com/jurislpo/jurisone/
    config/      SecurityConfig, CorsConfig, JacksonConfig, JobsConfig, StorageConfig
    security/    SessionAuthFilter, CurrentUser, OriginCheckFilter, Perm (constants)
    common/      ApiResult, ApiExceptionHandler, UserException, ListState, Csv, RequestMeta
    crypto/      PiiCrypto (AES-256-GCM, v1 format), Masks, TokenHash
    domain/      <module>/{entity, repository, service, controller, dto}
                 auth, people, roles, departments, holidays, attendance, leave,
                 approvals, settings, payroll, tax, declarations, proofs, dashboard,
                 audit, notifications, email, jobs
    engine/      AttendanceEngine, LeaveRules, PayrollEngine, TaxEngine, Format, NavAccess, PeopleRules (pure, no Spring)
  src/main/resources/
    application.yml                   env-driven, no secrets
    db/migration/V1__baseline.sql     Flyway (§7)
  src/test/java/...                   JUnit 5 + Testcontainers (postgres:16-alpine)
db/rollback/                          hand-written U<n>__*.sql (Flyway Community has no undo)
```

Dependencies: spring-boot-starter-{web, data-jpa, security, validation, mail, actuator}, flyway-core + flyway-database-postgresql, postgresql, bcprov (for `Argon2PasswordEncoder`), Apache PDFBox 3 (payslip PDF), Jackson JSR-310, Testcontainers (postgresql, junit-jupiter). No Lombok (plain records/classes keep the code readable).

### 9.2 Type mapping rules

| DB | Java | Notes |
|---|---|---|
| `timestamptz(6)` | `Instant` | Truncate to micros on write (`Instant.truncatedTo(MICROS)`) so round-trips compare equal |
| `date` | `LocalDate` | Never via `java.util.Date` |
| `int` paise (monthly) | `int` in entity, `long` in arithmetic | Overflow guard exactly as today (see §12 decision D1) |
| `bigint` paise (annual) | `long` | JSON: number in API responses; **string** inside audit JSON, matching existing rows |
| `numeric(p,s)` | `BigDecimal` | `round2` ported to match JS `Math.round` (half toward +∞) |
| `jsonb` | `JsonNode` via `@JdbcTypeCode(SqlTypes.JSON)` | payslip snapshots must stay field-compatible |
| PG enums | Java enum, `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` | spike first (§8.10) |
| tax engine values | `double` rupees | **The current tax engine works in float rupees.** Ported as-is (same rounding points) to keep TDS identical; money stored and returned is still integer paise. |

---

## 10. Authentication & authorisation

### 10.1 Recommendation: keep the existing DB-backed session cookie (not JWT)

**Recommended:** Spring Security with a custom `SessionAuthFilter` that reads the existing `jo_session` cookie, hashes it (sha256 hex) and looks it up in the existing `sessions` table. `SessionCreationPolicy.STATELESS`, so there is no `JSESSIONID` and no Spring Session tables.

Why not JWT in an httpOnly cookie:

1. **Immediate revocation is a hard requirement.** Disabling access or marking an employee exited must end their sessions at once (REQUIREMENTS §4, e2e `auth.spec.ts` "disabling access ends an active session immediately"). A JWT stays valid until it expires unless we add a DB denylist, at which point it is a session table with extra steps.
2. **Permission edits take effect immediately.** Today the role's permissions are re-read on every request; a JWT carrying permissions would go stale.
3. **Zero-downtime cut-over.** Users stay signed in when the backend switches, because Spring reads the same cookie and the same rows. Existing "remember me" sessions keep working.
4. **No new secret to manage or rotate.** JWT needs a signing key; the opaque token does not.
5. The cost (one indexed lookup per request) already exists today.

### 10.2 Mapping

| Today (Next) | Spring Boot |
|---|---|
| `getCurrentUser()` re-validates: session not revoked/expired, employee `portal_access`, not `EXITED`, `users` row exists; `touchSession` every 5 min | `SessionAuthFilter` (before `AnonymousAuthenticationFilter`) builds `CurrentUser` with the same checks; invalid → anonymous |
| permission keys from `role_permissions` | `GrantedAuthority` per key (`self`, `pay.view`, …) |
| `assertPerm(a, b)` (any-of) | `@PreAuthorize("hasAnyAuthority('a','b')")` on the controller method |
| `requirePage(navId)` (incl. `needsReports` for My team) | `@PreAuthorize("@nav.allowed('team')")` → ported `NavAccess` (§4) |
| Row-level rules (edit only assignable roles, never yourself, own payslip only, nobody decides own request, HR never gets salary fields) | In services, ported 1:1 from `people-rules.ts` etc.; never only in the UI |
| signed-out → page redirect / route handler **403** / action `{ok:false,"Your session has ended…"}` | Route-handler paths: **403** `{error}` (e2e expects 403). New JSON endpoints: 401 `{ok:false,error}`; the Next proxy turns it back into the same redirect/`ActionResult` as today |
| `api.ts` Origin check on non-GET route handlers | `OriginCheckFilter` on all non-GET `/api/**` (same rule: `Origin` host = `X-Forwarded-Host` or `Host`). Spring's token CSRF stays disabled because `SameSite=Lax` + Origin check is the current, tested defence |
| argon2id `m=19456,t=2,p=1`, 16-byte salt, 32-byte hash, PHC string | `Argon2PasswordEncoder(16, 32, 1, 19456, 2)` (Bouncy Castle). Verifies existing hashes; new hashes use the same parameters. Cross-compat test with a Node-made hash. |
| login IP limiter, forgot limiter (in-memory) | Same in-memory limiter (single instance, same as today). See §12 D6. |
| lockout 5 fails / 15 min, `login_history` with 8 reasons | Ported exactly, configurable via `auth.policy` setting |

---

## 11. Migration order

Each module is one or more commits on `feature/spring-boot-migration`. **Per module:** build backend (`./mvnw verify`, JUnit + Testcontainers), switch that module's pages/actions in Next to the API, run lint + typecheck + Vitest + the relevant Playwright specs (the full suite at M3, M6, M8 and M10), then commit. Nothing is pushed until you ask. Old Next server code is removed only in M10.

| # | Module | Backend work | Frontend switch | Gate |
|---|---|---|---|---|
| M0 | **Skeleton** | Maven wrapper, Boot app, `application.yml` (env only), Flyway `V1__baseline.sql` + baseline config, all 30 entities, `ddl-auto=validate` green against the live DB and a Testcontainers DB, `ApiResult` + exception advice, CORS, health endpoint, Dockerfile, `backend` service in `docker-compose.yml` | `next.config.ts` rewrite (initially for no paths), `src/lib/backend.ts` fetch helper | entity validation + Flyway tests |
| M1 | **Pure engines** | Port format, nav, permissions, people-rules, attendance engine, leave rules, payroll engine, tax engine + all 70 Vitest cases to JUnit; Node-vs-Java fixture tests for the golden case | none | JUnit = Vitest results |
| M2 | **Platform & auth** | PiiCrypto, passwords, sessions, `SessionAuthFilter`, Origin filter, login/logout/forgot/invite/reset, token lookup, `/api/me`, shell data, settings service, audit service, email + outbox, notifications | (auth) pages + actions, `(app)/layout.tsx` shell | `auth.spec.ts`, `access.spec.ts` |
| M3 | **Org & people** | roles, policies (settings), departments, holidays, directory, people (list, profile, PII reveal, create/edit, access toggle, exit, invite) + people CSV export | those pages/actions; `/api/people/export` rewrite | `people.spec.ts`, full suite |
| M4 | **Attendance** | punch, My attendance, regularization request/cancel, register (daily/monthly, time zones) + CSV export, attendance jobs (status, month lock, absence) | home check-in, attendance, register; jobs moved to Spring | `attendance.spec.ts` |
| M5 | **Leave & approvals** | leave apply/preview/cancel, ledger, approvals (first decision wins), leave jobs (accrual, comp-off expiry, year end), decision notifications + emails | leave, approvals, notifications bell | `leave.spec.ts` |
| M6 | **Payroll** | salary structures, payroll run (compute, edits, approve, close + PDFBox payslips), payslips + PDF route, payroll exports | structures, payroll, payslips | `payroll.spec.ts`, full suite |
| M7 | **Tax** | tax projection, declarations, proof upload/download, proof verification, Tax & TDS page, tax export | declare, proofs, tax | `tax.spec.ts` |
| M8 | **Dashboards & audit** | home cards, overview, My team, audit log | home, overview, team, audit | `dashboards.spec.ts`, full suite |
| M9 | **Seed & ops** | per §12 D3; README, `.env.example`, compose and backup docs updated; secrets removed | — | fresh `docker compose up` + seed + full suite |
| M10 | **Cleanup** | — | delete `src/app/api/**`, Prisma client usage, server-only `src/lib` services and jobs runner from Next; keep only the API client, formatters and UI | lint, typecheck, Vitest (UI-side), full Playwright, final parity checklist (§13) |

---

## 12. Risks and decisions needed before I start

### 12.1 Decisions (please answer; my default is in bold)

| # | Question | Default |
|---|---|---|
| D1 | The audit found **existing defects**: salary/adjustment amounts above ~₹2.1 crore overflow the `int` paise columns (generic 500); HR roles can be granted salary permissions on the Roles page; the payroll-close message hard-codes "1st at 10:00 AM"; the tax CSV exports raw paise; proof upload writes no audit row; some checks are non-atomic (regularization cancel, declaration submit, token consumption, leave overlap, punch). Replicate or fix? | **Replicate exactly** (your "no rule changes" rule) and list them in §13 as known issues; fix later only if you ask. |
| D2 | The jobs runner's advisory lock is session-level through a pool, so it can leak (invisible to users). | **Fix in Java** with `pg_try_advisory_xact_lock` in one transaction: same behaviour, no leak. |
| D3 | **Seed data.** `prisma/seed.ts` (roles, policies, tax tables, super admin, demo data; e2e depends on it) uses Prisma. After M10 there is no Prisma client. | **Port the seed to a Spring `seed` command** (`./mvnw spring-boot:run -Dspring-boot.run.arguments=--seed`, idempotent, same data) and keep `prisma/schema.prisma` only as reference until you say delete it. |
| D4 | **Secrets.** `.env.example` and README contain the super admin password and docker-compose has the dev DB password; `tests/e2e/helpers.ts` falls back to the admin password when the env var is missing. Your rule is "no hardcoded passwords anywhere". | Replace with placeholders/`${VAR}` everywhere **except** the e2e helper (tests must stay unchanged); the e2e run sets the env vars instead. **The password is already in git history and on GitHub — rotate it.** |
| D5 | **Playwright config.** `playwright.config.ts` starts only `npm run dev`. | Add the backend to its `webServer` list (config change only; no test file changes). Or I start the backend separately and leave the config alone. |
| D6 | Rate limiters are in-memory, as today (fine for one instance). | **Keep in-memory**; note that multiple instances would need a shared store. |
| D7 | HTTP codes for the **new** JSON endpoints (none exist today). | 200/201 success; 400 validation; 401 signed out; 403 permission; 404 not found; 409 duplicate; 422 business rule; body always `{ok, msg?, data?, error?}` with today's exact messages. The 7 existing routes keep their current codes (403 when signed out). |
| D8 | Server-formatted display strings (time, dates, approval details, notes) depend on JS `Intl`. | API returns **raw values**; Next keeps formatting with the existing `format.ts`. The few strings that must come from the server (notification text, emails, PDFs) are ported with tests. |

### 12.2 Technical risks

| Risk | Mitigation |
|---|---|
| Tax/payroll numbers drift (float rupees, `Math.round` half-up vs Java HALF_EVEN/HALF_UP on negatives, `Date.UTC` month overflow vs `plusMonths` clamping) | Port the arithmetic literally (no "improvements"); golden fixtures generated from the **Node** code for every seeded employee and month, compared number-for-number in JUnit |
| Time zones / DST / night shifts (`Intl`, `TZDate`) | `ZonedDateTime` with IANA ids; engine tests include the US DST cases; fixture comparison of `attendanceFor` per employee for Apr–Oct 2026 |
| Payslip snapshot JSON and PDF layout | Snapshots built from the same fields in the same order; PDFBox renders the same coordinates/fonts; PDF tests compare extracted text with the Node output |
| Native PG enums + `ddl-auto=validate` | Spike on one entity in M0 before writing the rest; fallbacks in §8.10 |
| Hibernate writes every column on insert; Prisma omits defaults; `@updatedAt` is client-side | Java field initialisers equal DB/Prisma defaults; `@UpdateTimestamp` on the 6 tables |
| CSV byte parity (BOM, CRLF, formula-escaping, header names) | `Csv` util ported from `csv.ts`; tests compare whole files |
| Stored file paths contain Windows backslashes | Resolve paths with `Path.of` after normalising `\` to `/`; no DB rewrite (schema/data untouched) |
| `computeRun` is heavy (≈6 queries per employee) and is used by 5 pages | Same results, batched queries in Java; measured against the 2 s target |
| Size of the work | ~43 actions + 34 reads + 7 file routes + jobs + PDF + seed. Many commits; I'll report after each module |

---

## 13. Parity checklist

Each row is ticked only when the Spring module is live, its JUnit tests pass and the listed Playwright specs pass **unchanged** against it. Final state: every page, action and file route is served by Spring Boot; the Next.js API routes, Prisma client and server modules are deleted.

| Area | Behaviour | JUnit | Playwright | Status |
|---|---|---|---|---|
| Auth | login by ID or email, wrong password, lockout after 5, disabled / exited blocked, sign out, invite + reset links once, session ends when access is disabled | `AuthFlowTest`, `PasswordsTest` | `auth.spec.ts` | ☑ M2 |
| Access | page + API permissions, HR never gets finance pages, My team only with reports, ⌘K + theme | `NavTest`, `AuthFlowTest` | `access.spec.ts` | ☑ M2 |
| People | list/search/filter/sort/page, add (3 steps) + edit, PII encrypted + masked + reveal audited, access toggle, mark exited, CSV export | `PeopleRulesTest`, `CryptoTest`, `OrgAndPeopleApiTest` | `people.spec.ts` | ☑ M3 |
| Roles / departments / holidays / policies | permission toggles, custom roles, assignable roles, departments, holidays by region, attendance policy | `OrgAndPeopleApiTest` | `people.spec.ts`, `attendance.spec.ts` | ☑ M3 |
| Attendance | check-in/out, night shifts, late marks, half days, absences, month lock, register (tz, export), regularization request/withdraw | `AttendanceEngineTest`, `AttendanceApiTest` | `attendance.spec.ts` | ☑ M4 |
| Leave & approvals | apply (holidays skipped, max stretch, LOP when short), cancel, approvals first decision wins, comp-off, accrual, year end, notifications + emails | `LeaveRulesTest`, `LeaveApiTest` | `leave.spec.ts` | ☑ M5 |
| Payroll | structures (effective-dated, closed months protected), run edits, approve & close, frozen snapshots, payslips + PDF, exports, golden case JC-074 (22/30 days, ₹14,926 TDS, ₹93,074 net) | `PayrollTaxTest`, `PayslipPdfTest` | `payroll.spec.ts` | ☑ M6 |
| Tax | regime choice + comparison, declarations, proof upload (type/size), verification + TDS preview, Tax & TDS drawer, override, Form 12B, export | `PayrollTaxTest`, `TaxApiTest` | `tax.spec.ts` | ☑ M7 |
| Dashboards & audit | Home cards per role (no salary), Overview, My team, audit log filters + before → after + redaction | `DashboardApiTest` | `dashboards.spec.ts` | ☑ M8 |
| Jobs | status changes, month lock, absence marking, accrual, comp-off expiry, year end; idempotent; single runner (advisory lock); `npm run jobs` for cron | `AttendanceApiTest#jobsAreIdempotentAndLockMonths` | (covered by attendance/leave specs) | ☑ M4 / M9 |
| Seed | `npm run db:seed` (Java) produces the same data as `prisma/seed.ts` did — all 30 tables identical after normalising ids and run-time timestamps, incl. the 55 payslip snapshots and 3,193 punches; no default admin password | `SeedServiceTest` + one-off table diff | full suite | ☑ M9 |
| Data | existing passwords verify, existing PII decrypts, existing sessions stay valid, existing payslip PDFs open, Flyway baseline leaves the schema untouched | `CryptoTest`, `PasswordsTest`, `ExistingPrismaDatabaseTest`, `FreshDatabaseSchemaTest` | full suite | ☑ M1 / M2 |

## 14. REST API (after the migration)

Every endpoint lives under `/api` on the Spring Boot API. The browser reaches it through the Next.js `/api/*` rewrite; pages and server actions call it server-side through `src/lib/backend.ts`. Permissions are checked in every controller.

### 14.1 Conventions

| Rule | Applied as |
|---|---|
| Nouns, not verbs, in paths | `/api/leave/requests`, `/api/attendance/punches`, `/api/declarations/submissions`, `/api/auth/session` |
| The resource id is in the path, never only in the body | `PUT /api/tax/{employeeId}/regime`, `PUT /api/payroll/{month}/adjustments/{employeeId}` |
| `GET` reads and never changes data (audited reads are the only side effect) | `GET /api/leave/preview?type=&from=&to=&part=`, `GET /api/people/{id}/contact` |
| `POST` creates → **201 Created** | person, role, department, holiday, leave request, regularization, punch, salary structure, invitation, declaration submission, session, password-reset request |
| `PUT` sets a value or a state (idempotent) | access on/off, regime, profile, declared amount, decisions, payroll status, a granted permission |
| `PATCH` changes part of a resource | `PATCH /api/notifications {"read": true}` |
| `DELETE` removes or withdraws | session (sign out), leave request, regularization, department, holiday, role, revoked permission, payroll adjustment |
| Status codes | 200 · 201 · 400 invalid input · 401 signed out · 403 not allowed · 404 not found · 405 wrong method (with `Allow`) · 409 duplicate · 410 used/expired link · 415 · 422 business rule · 429 throttled · 500 |
| Body | success: `{ "ok": true, "msg"?, "data"? }`; errors: `application/problem+json` (`status`, `title`, `detail`, `instance`, `code`, `errors`?) — `detail` keeps the exact texts the app showed before the migration |

The 7 file routes the browser opens directly keep their original paths, methods and status codes. They are linked from pages, may be bookmarked and are covered by the unchanged Playwright tests: `GET /api/people/export`, `GET /api/attendance/register` (CSV), `GET /api/payroll/export`, `GET /api/payslips/{month}/{code}` (PDF), `POST /api/declarations/proof` (multipart upload), `GET /api/declarations/proof/{id}`, `GET /api/tax/export`.

### 14.2 Endpoints

| Area | Endpoints |
|---|---|
| Auth | `POST /api/auth/session` (sign in, 201 + cookies) · `POST /api/auth/session/refresh` · `DELETE /api/auth/session` (sign out) · `POST /api/auth/otp-requests` (email a sign-in code). Passwords, reset and invite links were removed (V16). |
| Me & shell | `GET /api/me` · `GET /api/shell` · `PATCH /api/notifications` |
| Roles | `GET /api/roles` · `POST /api/roles` · `DELETE /api/roles/{id}` · `PUT`/`DELETE /api/roles/{id}/permissions/{perm}` · `PUT`/`DELETE /api/roles/{id}/assignable-roles/{roleId}` |
| Policies | `GET /api/settings/access` · `GET /api/settings/leave` · `GET /api/settings/holidays?year=` · `GET`/`PUT /api/settings/attendance` |
| Departments & holidays | `GET`/`POST /api/departments` · `DELETE /api/departments/{id}` · `GET`/`POST /api/holidays` · `DELETE /api/holidays/{id}` |
| People | `GET /api/people` · `POST /api/people` · `GET`/`PUT /api/people/{id}` · `GET /api/people/{id}/contact` · `PUT /api/people/{id}/access` · `PUT /api/people/{id}/exit` · `GET /api/people/form-options` · `GET /api/directory` · `GET /api/people/export` |
| Attendance | `GET /api/attendance/me` · `POST /api/attendance/punches` · `POST /api/attendance/regularizations` · `DELETE /api/attendance/regularizations/{id}` · `PUT /api/attendance/regularizations/{id}/decision` · `GET /api/register` · `GET /api/attendance/register` (CSV) |
| Leave & approvals | `GET /api/leave` · `GET /api/leave/preview` · `POST /api/leave/requests` · `DELETE /api/leave/requests/{id}` · `PUT /api/leave/requests/{id}/decision` · `GET /api/approvals` |
| Payroll | `GET /api/payroll?m=` · `PUT`/`DELETE /api/payroll/{month}/adjustments/{employeeId}` · `PUT /api/payroll/{month}/status` · `GET`/`POST /api/structures` · `GET /api/payslips` · `GET /api/payslips/{month}/{code}` · `GET /api/payroll/export` |
| Tax | `GET /api/declarations` · `PUT /api/declarations/regime` · `PUT /api/declarations/profile` · `PUT /api/declarations/items/{key}` · `POST /api/declarations/submissions` · `POST /api/declarations/proof` · `GET /api/declarations/proof/{id}` · `GET /api/proofs` · `GET /api/proofs/{employeeId}` · `PUT /api/proofs/items/{id}/decision` · `PUT /api/proofs/{employeeId}/decision` · `GET /api/tax` · `GET /api/tax/{employeeId}` · `PUT /api/tax/{employeeId}/regime` · `PUT /api/tax/{employeeId}/previous-employer` · `GET /api/tax/export` |
| Dashboards | `GET /api/home` · `GET /api/overview` · `GET /api/team` · `GET /api/audit` |
| Health | `GET /actuator/health` |

Every Next.js server action maps to exactly one of these endpoints (`src/app/**/actions.ts`), and every page reads its data from one `GET` (see each `page.tsx`). `RestEndpointsTest` checks the conventions; the area API tests check each endpoint's rules and messages.
