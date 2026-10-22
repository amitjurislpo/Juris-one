@INSTRUCTIONS.md

# JurisOne — backend notes

- Source of truth: `references/REQUIREMENTS.md` (rules) and `references/Juris-People-Design-8.html` (design). Requirements win on conflict.
- This repo is the **backend** (Spring Boot API in `backend/`, Java 17, Maven Wrapper; Postgres via docker-compose). The web app lives in its own repo, `juris-one-frontend` (React + Vite, https://github.com/amitjurislpo/juris-one-frontend). It proxies `/api/*` to this API, so the API and the app share one origin in every environment.
- API on http://localhost:8080; the app on http://localhost:3005 (`jurisone.app-url` must match it: the API's Origin check and CORS allow only that origin); Postgres on host port 5436 (5432/5433 are used by other projects).
- Configuration: one file per environment, `backend/src/main/resources/application-<env>.properties` (dev, sqe, uat, prd) on top of `application.properties`. dev has local-only values in the file; the secrets of sqe/uat/prd are `${ENV_VARS}` provided by the server. No `.env` files. `scripts/env.mjs` reads the same files for docker-compose and the helper scripts.
- Schema changes: add a Flyway migration `backend/src/main/resources/db/migration/V<n>__name.sql` plus its reverse in `backend/src/main/resources/db/rollback/U<n>__name.sql`; JPA runs with `ddl-auto=validate`. The backend's one-shot commands (`migrate`, `seed`, `reset`, `jobs`) run as `./mvnw spring-boot:run -Dspring-boot.run.arguments=<command>` in `backend/`.
- Multi-business (SAAS_PLAN.md): every business-owned table has `business_id` + row-level security; `TenantContext` (set from the signed-in user) is applied to each DB connection by `TenantDataSource`. New business-owned tables need `business_id DEFAULT jurisone_tenant_id()` and a `tenant_isolation` policy; switch `TenantContext` only outside a transaction. Sign-in is by email only.
- Every API endpoint: `auth.assertPerm(...)` / `auth.requirePage(...)` in the controller. HR must never receive salary/tax fields.
- REST: nouns in paths, ids in the path, GET never writes, POST creates (201), PUT sets, PATCH partial, DELETE removes; success bodies are `{ ok, message?, data? }`, errors are `application/problem+json` with a `code` (see `references/MIGRATION_PLAN.md` §14).
- Money = integer paise; dates = ISO strings / `date`; timestamps UTC. The API returns raw values; the app formats them.
- Backend tests: `./mvnw test` in `backend/` (JUnit 5 + Testcontainers; Docker must be running). The browser (Playwright) tests live in the frontend repo; its `scripts/e2e-env.mjs` builds and starts this API (database `<db>_e2e` reset + demo seed + one jobs run, API :8081 from a copied jar in the temp folder) — dev data is never touched.
- This is a pure Java project: no Node, no `package.json`. Uploaded and generated files (payslips, proofs, policy PDFs, onboarding documents) live outside the project in `jurisone.storage-dir` (dev: `Documents/JurisOne`).
