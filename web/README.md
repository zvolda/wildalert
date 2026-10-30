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

The browser calls same-origin `/api/...` paths, and Vite's dev server proxies them to
`http://localhost:8081` (`server.proxy` in `vite.config.ts`; override the target with
`VITE_API_PROXY_TARGET` in `.env.local`).

That is why this app needed no CORS configuration and no change to any Kotlin service.

In production the same trick is played by **`services/gateway`** (Spring Cloud Gateway, servlet
flavour), which serves these built files *and* forwards the four browser-facing calls to the
private `user-account` with a Google ID token — one origin, so again no CORS. Its Docker image
builds this app and copies `dist/` into the jar, so the page and the API it calls can never be
different versions of each other.

The session cookie passes straight through the gateway in both directions; it is `HttpOnly`, so
nothing in this app reads it — the browser attaches it to these same-origin requests by itself.

**`user-account` is private on Cloud Run** (`Require authentication`), so this app can never be
pointed straight at the deployed service — only at the gateway, or at a local user-account.

Note the gateway's route list is deliberately **exact method+path pairs, never a wildcard**:
`POST /api/hunters`, `POST` and `DELETE /api/sessions`, `GET /api/me`. `user-account`'s lookup
endpoints (`by-email`, `by-inbound-address`, `GET /api/hunters/{id}`) all return a hunter's phone
number and are *not* proxied, so don't expect them to work from the browser — a hunter reads their
own record through `/api/me`, which user-account authenticates from the session cookie.

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
  App.tsx                  three screens: loading / anonymous / dashboard
  RegisterForm.tsx          register, field errors, submit state
  LoginForm.tsx             sign in
  Dashboard.tsx             credit panel + the address panel + sign out
  InboundAddressPanel.tsx   the address, copy button, setup help
  Field.tsx                 label + control + hint/error, shared by both forms
  api.ts                   register / login / logout / me
  credit.ts                haléře -> "25.00 CZK" and "about N alerts"       (pure, tested)
  problemDetail.ts         RFC 9457 error body -> message + field errors   (pure, tested)
  validation.ts            email, E.164 and password rules, mirroring the server (pure, tested)
  types.ts                 the wire contract, mirroring HunterDtos.kt
  styles.css               Tailwind entry + theme tokens
```

**There is no router, deliberately.** The session cookie does a URL's job: `App.tsx` asks
`GET /api/me` on load, so a refresh, a reopened tab or a visit tomorrow all land on the dashboard.
Adding routes would mean a new npm dependency (see the `npm install` gotcha above) *and* an SPA
fallback in the gateway, which does not exist — any unknown path there is a 404 today.

`types.ts` and `validation.ts` duplicate things that live in Kotlin
(`HunterDtos.kt`). That duplication is deliberate — there is no shared schema — and each copy
carries a comment naming the other, so the pair is findable when one changes.
