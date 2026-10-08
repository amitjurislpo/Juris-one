@AGENTS.md

# JurisOne — project notes

- Source of truth: `references/REQUIREMENTS.md` (rules) and `references/Juris-People-Design-8.html` (design). Requirements win on conflict.
- Built in phases 0–6 (see `references/CLAUDE_CODE_PROMPT.md`). After each phase: migrate, seed, lint, typecheck, unit + e2e tests, summarise, then wait for the user's "continue".
- App runs on http://localhost:3005 (Next.js); the Spring Boot API (`backend/`, Java 17, Maven Wrapper) on http://localhost:8080; Postgres (docker-compose) on host port 5436 (5432/5433 are used by other projects).
- Next.js is the UI only: pages and server actions call the API through `src/lib/backend.ts`, and `/api/*` is rewritten to the API (`next.config.ts`). There is no Prisma/DB code in `src/` any more; `prisma/schema.prisma` is kept only as a readable reference.
- Schema changes: add a Flyway migration `backend/src/main/resources/db/migration/V<n>__name.sql` plus its reverse in `db/rollback/U<n>__name.sql`; JPA runs with `ddl-auto=validate`. `npm run db:migrate` / `db:seed` / `jobs` run the backend's one-shot commands.
- Every page: `requirePage(navId)`; every API endpoint: `auth.assertPerm(...)` / `auth.requirePage(...)` in the controller. HR must never receive salary/tax fields.
- Money = integer paise; dates = ISO strings / `date`; timestamps UTC. The API returns raw values; the pages format them with `src/lib/format.ts`.
- Backend tests: `npm run backend:test` (JUnit 5 + Testcontainers; Docker must be running). If VS Code's Java extension is open, don't run the e2e suite against `spring-boot:run` while editing Java — it recompiles `target/classes` under the running server; run a packaged jar instead.
- `globals.css` is the prototype CSS verbatim; put app-only CSS in the "JurisOne additions" block at the end.
- npm 11 blocks install scripts: approve new native packages with `npm install-scripts approve <pkg>`.
