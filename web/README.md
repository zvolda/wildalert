# WildAlert web

The hunter-facing web app. React + TypeScript, built with Vite, styled with Tailwind CSS.

Right now it is one screen: **register a hunter and show them their personal inbound address**
(ROADMAP Phase 8, first task). No login, no phone verification, no dashboard yet.

This directory is **not** part of the Gradle build. `./gradlew build` neither builds nor needs it.

## Run it

The app needs `user-account` answering on port 8081, because registering actually registers.

```bash
# 1. Backend, from the repo root (NOT from a worktree -- see the gotcha below)
docker compose up -d postgres
./gradlew :services:user-account:bootRun     # :8081

# 2. This app
cd web
npm install
npm run dev                                   # http://localhost:5173
```

| Command | Does |
|---|---|
| `npm run dev` | Dev server with hot reload on :5173 |
| `npm run build` | Type-check (`tsc --noEmit`) then produce `dist/` |
| `npm run preview` | Serve the built `dist/` locally, to check the real build |
| `npm test` | Unit tests once |
| `npm run test:watch` | Unit tests, watching |

## How it talks to the API

The browser calls a **same-origin** `/api/hunters`, and Vite's dev server proxies it to
`http://localhost:8081` (`server.proxy` in `vite.config.ts`; override the target with
`VITE_API_PROXY_TARGET` in `.env.local`).

That is why this slice needed no CORS configuration and no change to any Kotlin service. In
production the same trick is played by **Spring Cloud Gateway**, which will serve these built
files *and* proxy `/api/**` to the private `user-account` with a Google ID token — one origin,
so again no CORS. That gateway does not exist yet; it is the next slice.

**`user-account` is private on Cloud Run** (`Require authentication`), so this app cannot be
pointed at the deployed service. Local only, for now.

## Gotchas

- **Run the backend from the main checkout, not from a git worktree.** `user-account`'s
  `application.yml` imports the repo-root `.env` for database credentials, and `.env` is
  git-ignored — so it does not exist in a fresh worktree and `bootRun` dies on `${DATABASE_URL}`.
  The dev proxy only needs *something* listening on 8081, so backend in the main checkout and UI
  in a worktree is a fine combination.
- **Tailwind here is v4.** There is deliberately no `tailwind.config.js` and no PostCSS config:
  v4 configures itself in CSS (`@import "tailwindcss"` plus `@theme` in `src/styles.css`) and is
  wired up by the `@tailwindcss/vite` plugin. A tutorial that tells you to create a config file
  is describing v3.
- **Tests run in a plain Node environment**, with no jsdom and no DOM-testing library. The logic
  worth testing (validation, error parsing) is kept in pure modules precisely so that stays true.
  Testing components would mean adding those dependencies — worth it later, not for one form.
- **`npm install` does not work on the Oracle corporate network** — its web gateway re-signs TLS
  and withholds the issuing intermediate, so Node cannot build a chain. Run `npm install` from
  outside that network (once; `node_modules/` and `package-lock.json` then persist and everything
  else is local). **No TLS bypass** — see the note in the repo root `AGENTS.md`.

## Layout

```
src/
  main.tsx                 mounts React
  App.tsx                  the one bit of state: registered hunter or not
  RegisterForm.tsx          the form, field errors, submit state
  InboundAddressPanel.tsx   success: the address, copy button, setup help
  api.ts                   POST /api/hunters
  problemDetail.ts         RFC 9457 error body -> message + field errors   (pure, tested)
  validation.ts            email + E.164 checks mirroring the server       (pure, tested)
  types.ts                 the wire contract, mirroring HunterDtos.kt
  styles.css               Tailwind entry + theme tokens
```

`types.ts` and `validation.ts` duplicate things that live in Kotlin
(`HunterDtos.kt`). That duplication is deliberate — there is no shared schema — and each copy
carries a comment naming the other, so the pair is findable when one changes.
