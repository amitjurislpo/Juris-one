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

## Requirements (add yours here)

- 

## Decisions already taken

- Sign-in is by emailed code only (fixed code `000000` only in dev).
- The identity provider (IAM JCG / Sita) is not to be worked on until the owner says so.
- Exit / full & final settlement and Form 16 were removed on purpose.
