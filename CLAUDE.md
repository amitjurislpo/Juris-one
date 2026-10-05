@AGENTS.md

# JurisOne — project notes

- Source of truth: `references/REQUIREMENTS.md` (rules) and `references/Juris-People-Design-8.html` (design). Requirements win on conflict.
- Built in phases 0–6 (see `references/CLAUDE_CODE_PROMPT.md`). After each phase: migrate, seed, lint, typecheck, unit + e2e tests, summarise, then wait for the user's "continue".
- App runs on http://localhost:3005; Postgres (docker-compose) on host port 5436 (5432/5433 are used by other projects).
- Prisma 7: client generated to `src/generated/prisma` (import from `@/generated/prisma/client`), datasource URL in `prisma.config.ts`. `migrate dev` does not auto-generate — run `npm run db:generate`. Add a `down.sql` beside each new migration (`prisma migrate diff --from-schema prisma/schema.prisma --to-schema-datamodel <prev> --script`, or hand-write).
- Every page: `requirePage(navId)`; every server action / route handler: `assertPerm(...)`. HR must never receive salary/tax fields.
- Money = integer paise; dates = ISO strings / `@db.Date`; timestamps UTC. Helpers in `src/lib/format.ts`.
- `globals.css` is the prototype CSS verbatim; put app-only CSS in the "JurisOne additions" block at the end.
- npm 11 blocks install scripts: approve new native packages with `npm install-scripts approve <pkg>`.
