# Owner's instructions

This file is for the project owner to write requirements and working rules in. It is loaded automatically at the start of
every Claude Code session (through `CLAUDE.md`), so whatever is written here is followed without being repeated.
Add new lines at the bottom of the right section. Hindi, Hinglish or English are all fine. If two lines disagree, the newer one wins.

## How to work with me (git and PRs)

- Create every pull request against the `dev` branch first, never `main`. New feature branches are cut from `dev`.
- Never merge a pull request yourself. Open it, share the link and stop.
- Commit and push only when asked. Do not push on your own.
- Never put `.idea/` (or other IDE folders) in a commit; they are git-ignored.
- Commit messages end with the Claude co-author line given by the harness.

## Communication

- Reply in Hindi/Hinglish (the owner writes in Hindi). Keep answers short: what changed, what was checked, what is left.
- Say plainly when something was not tested or could not be done. Do not claim a check that was not run.
- Ask before deleting things that cannot be recovered (files outside git, local data). Explain what and why.

## Project structure

- Two repositories: this one is the **backend** (`Juris-one`: Spring Boot API in `backend/`, Postgres, jobs) and `juris-one-frontend` is the **web app** (React + Vite). Frontend code lives only in the frontend repo; backend code only here.
- The frontend repo's browser tests (`scripts/e2e-env.mjs` there) build and start this API from a checkout next to it; keep `./mvnw -DskipTests package` and the `reset` / `jobs` commands working.

## Code and configuration rules

- The repo must stay clean: no unused files, no dead code, no leftover references to removed things (Next.js, Prisma, `.env`, exit/F&F, Form 16).
- Configuration is one file per environment: `backend/src/main/resources/application.properties` and `application-dev|sqe|uat|prd.properties`. Do not add `.env` files or separate templates. Secrets of sqe/uat/prd are `${ENV_VARS}` from the server and are never committed.
- Config files live under `src/` (resources), not in the repository root. Generated and uploaded files live outside the project (`jurisone.storage-dir`).
- The backend is a pure Java project: no Node, no `package.json`.
- Verify before saying "done": backend `./mvnw test` (in `backend/`); frontend `npm run typecheck`, `npm run lint`, `npm test`, `npm run build`, and `npm run test:e2e` for anything the UI or API contract touches.

## Naming and REST standards (mandatory for all new code; fix old code when you touch it)

**Names (both repos)**
- Names say what a thing is, in full words: `AttendancePolicy`, not `Pol`; `departmentId`, not `dept`; `month`, not `ym`. No cryptic abbreviations in class, type, function, field, variable, file, route, query-parameter or JSON names.
- Single-letter names only for a loop index (`i`, `j`), an exception or event (`e`), the translate function `t`, and a tiny one-expression lambda. Never for domain values.
- Booleans read as a question (`isActive`, `hasPhoto`, `canEdit`). Functions are verbs (`loadEmployees`, `confirmEmployee`); classes, records and types are nouns.
- Casing: classes, records, enums, React components and TypeScript types are `PascalCase`; methods, variables, fields, JSON fields and query parameters are `camelCase`; constants are `UPPER_SNAKE_CASE`; URL path segments are `kebab-case`; database tables and columns are `snake_case`.
- Java packages are lowercase and follow the layers already used (`controller`, `service`, `repository`, `entity`, `engine`, `security`). A file holds the one class it is named after.

**REST API (backend, and the frontend calls that use it)**
- A URL names a resource, never an action: plural nouns for collections (`/api/leave/requests`), the id in the path (`/api/people/{id}`), sub-resources for what belongs to it (`/api/people/{id}/photo`). No verbs in paths. An action that has no resource of its own is modelled as creating one (`POST /api/auth/otp-requests`, `POST /api/policies/{id}/acknowledgements`), or as setting a state (`PUT /api/people/{id}/status`).
- Methods: `GET` reads and never changes anything; `POST` creates (answer `201` with the new id); `PUT` sets or replaces and is idempotent; `PATCH` changes part of a resource; `DELETE` removes. Do not tunnel changes through `GET`.
- Collections take their filters, sorting and paging as camelCase query parameters (`status`, `departmentId`, `month`, `search`, `sortBy`, `page`), always descriptive. The current user's own collection is `/mine` everywhere.
- Status codes mean what they say: `200`, `201`, `204`, `400` bad input, `401` not signed in, `402` subscription, `403` not allowed, `404` not found, `409` conflict, `422` a business rule refused it, `429` too many requests. Errors use the one error shape the API already has (RFC 7807 problem details); never invent another.
- A new endpoint is added together with its integration test, its entry in `references/REQUIREMENTS.md` where it changes a rule, and (for a write) an audit entry.
- Screens that need several things at once (`/api/home`, `/api/overview`, `/api/team`, `/api/shell`) are view models, not resources. Keep them few; do not add more where a resource will do.
- Do not rename or remove an endpoint, parameter or JSON field without changing every caller (the other repo, the tests) in the same change.

**Backend specifics**
- Controllers only map HTTP to a service call and shape the response; rules live in `service` and `engine`. A request or response body is a `record` named after what it carries (`EmployeeInput`, `PayrollRow`), never `Dto1` or an abbreviation.
- Never edit a migration that has been applied; add a new `V<n>__<what_it_does>.sql` with its `U<n>` rollback.

## Documents: FRD and flow diagrams (versioned)

- Each product has a **Functional Requirements Document (FRD)** and a **flow diagram**, and the two together have one **connection flow diagram** (how JurisTrack and JurisOne talk to each other: sign-in token, gateway, roster, roles, audit). Files, in the backend repos' `references/` folder: `FRD-JurisOne.md`, `FLOW-JurisOne.md`, `FRD-JurisTrack.md`, `FLOW-JurisTrack.md`, `FLOW-JurisTrack-JurisOne-Connection.md`. The master copy of the connection diagram is in `juris-track-backend/references/`; the `Juris-one` backend keeps a copy with the same version.
- Diagrams are Mermaid inside the `.md` (they render on GitHub and in the IDE) and must be readable at a glance: who (role / system) does what, in order, with the decisions and the error paths. A new reader should understand how the flow works from the diagram alone.
- Every document starts with a **version table** (version, date, what changed, who asked). Whenever a requirement or a flow changes (new or changed endpoint, role, rule, screen flow, integration step), update the affected FRD and diagram **in the same pull request** as the code and raise the document version. Small change = minor (1.1 -> 1.2); a new area or a changed architecture = major (1.x -> 2.0). A pull request that changes behaviour without the document update is not finished.
- Document version is not the app version; do not mix them. Keep the old versions in the version table, not as extra files.
- When JurisTrack changes something that crosses over to JurisOne (the integration), update the connection diagram in both repos with the same version number.

## Requirements (add yours here)

- **Security: the application must be theft-proof.** No outsider or third-party application may get into the system, read its data or change it. Every new endpoint and every change to an old one is checked against this list (both repos):
  - **SQL injection:** never build SQL by joining text with user input. Use bound parameters only (`?` / named parameters); where a column, sort field or table name must vary, pick it from a fixed allow-list in code. Same for any other interpreter (shell commands, file paths, LDAP, templates).
  - **Input:** every request body, query parameter, path variable, header and uploaded file is validated on the server (type, length, format, allowed values); the browser's checks are only for comfort. Uploads: check type and size, store outside the web root under a generated name, never run or serve them as code.
  - **Output (XSS):** no `dangerouslySetInnerHTML` or raw HTML from data; React's escaping stays on. Responses carry the security headers (CSP, `X-Content-Type-Options`, `X-Frame-Options`/frame-ancestors, HSTS in upper environments).
  - **Who may do what:** the API checks permission and business (tenant) on every call, using the signed-in user, never an id the client sends. A user must not be able to read or change another business's or another person's data by changing an id in the URL (IDOR). Hidden buttons are not security.
  - **Third-party applications:** no API, database, storage or admin port is open to anyone but the app itself. Only our own web origin may call the API (CORS allow-list, no `*`). A third-party or service-to-service caller (for example JurisTrack) gets its own signed credential with the smallest permissions it needs, can be revoked, and every call it makes is audited. No shared or hard-coded secrets in code.
  - **Sessions and sign-in:** cookies are `HttpOnly`, `Secure` (upper environments) and `SameSite`; state-changing calls are protected against CSRF; sign-in codes are one-use, short-lived and rate-limited per account as well as per IP; failed attempts are logged and lock the account for a while.
  - **Secrets and personal data:** secrets come from the server environment only, never from git, logs or error messages. Sensitive fields (PAN, Aadhaar, bank account) stay encrypted at rest and are masked in lists, logs and audit entries. Errors shown to the user never reveal SQL, stack traces or internals.
  - **Dependencies and checks:** keep libraries updated; run the dependency audit (`npm audit`, Maven dependency check) before a release. A new endpoint that takes user input gets a test that sends hostile input (SQL, script, oversized, wrong tenant) and expects a refusal. Any security finding is fixed before other work, and every refusal or suspicious call lands in the audit log.
- 

## Decisions already taken

- Sign-in is by emailed code only (fixed code `000000` only in dev).
- The identity provider (IAM JCG / Sita) is not to be worked on until the owner says so.
- Exit / full & final settlement and Form 16 were removed on purpose.
- The FRD and flow diagrams are written right after the current pull requests are raised (this round of testing, commit and push comes first). When IAM JCG / Sita is connected later, update the FRDs and all three flow diagrams and release a new document version then.

## Local URLs (fixed: always start the apps on exactly these)

| App | Web | API |
|---|---|---|
| JurisOne | http://localhost:3005 | http://localhost:8080 |
| JurisTrack | http://localhost:3006 | http://localhost:8081 |

- Every time an application is run locally it must come up on these ports, never on any other (JurisTrack is **not** 3007/8082 any more). Postgres stays: JurisOne 5436, JurisTrack 5437. E2E ports are separate and unchanged.
- Hardcoded URLs are allowed in the local (`dev`) environment only. In every upper environment (sqe, uat, prd) URLs and ports come from the property file / server environment variables, never from code.
