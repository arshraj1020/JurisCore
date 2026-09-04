# JurisCore — frontend

The web client for the JurisCore API. React 18, TypeScript, Vite, React Router, TanStack
Query, React Hook Form + Zod, Tailwind. No component library, no state-management library,
no date library — the dependency list is short on purpose.

## Running it

```bash
npm install
npm run dev      # http://localhost:3000
```

Start the API separately first — `mvn spring-boot:run` in `juriscore-app`, or the Docker
Compose stack — then the dev server. The app calls the API with relative paths and Vite
proxies `/api` (plus `/actuator` and the OpenAPI paths) to `http://localhost:8080`, so
requests are same-origin and CORS is not involved at all. Point the proxy elsewhere with
`VITE_API_PROXY_TARGET=http://localhost:9090 npm run dev`.

Without that proxy the browser sends `/api/v1/...` to Vite, which answers 404 with an
empty body — and a 404 is indistinguishable from a missing record at the fetch layer, so
the form reports "That record does not exist." while the backend sits idle.
`src/test/devProxy.test.ts` exists to stop that returning.

The port is pinned to 3000 (`strictPort`) so that a browser reaching the backend directly
— which is what a deployed frontend does — matches the backend's default CORS allow-list,
`http://localhost:3000`.

| Script | What it does |
|---|---|
| `npm run dev` | Vite dev server on port 3000 |
| `npm run build` | Typecheck, then a production build into `dist/` |
| `npm run typecheck` | `tsc -b` with no emit |
| `npm run lint` | ESLint over the whole tree |
| `npm test` | Vitest, once |
| `npm run test:watch` | Vitest in watch mode |
| `npm run verify` | typecheck → lint → test → build. This is the gate. |

Two further checks live in `scripts/` and are deliberately outside `verify`, because they
need a browser that is not a project dependency. Install one with
`npm i --no-save playwright && npx playwright install chromium` (or point `CHROMIUM_PATH`
at one you already have), then:

```bash
npm run build
node scripts/shots.mjs            # screenshots every page at 390 / 834 / 1280 / 1680 into .shots/
node scripts/a11y.mjs             # accessible names, labels, heading order, duplicate ids, contrast
```

`scripts/fixtures.mjs` holds the sample firm both scripts render against.

Configuration is one variable, documented in `.env.example`. Only `VITE_`-prefixed
variables reach the browser and everything that does is public — nothing secret belongs in
that file.

## The visual language

Restrained on purpose. White and near-white surfaces, hairline borders rather than drop
shadows, one desaturated indigo for the brand, and the status colours — green, amber, red
— reserved for state so they are the loudest thing on a screen somebody looks at all day.
No gradients, no glass, no hero sections, and one border radius.

The palette lives in `tailwind.config.js`. Two rules about it are load-bearing: `ink-500`
and `ink-600` are the only greys text is set in, both clearing 4.5:1 against every surface
the interface puts them on; `ink-400` and lighter are for icons, dividers and placeholders
and are never used for reading. `scripts/a11y.mjs` checks this, and it currently reports no
failures across all twenty-three pages.

Typography is the system stack — a webfont would be a network dependency for no gain here.
Tables run at 13px with tight row padding because they are scanned for a matter number,
not read; money and counts are `tabular-nums` wherever they are compared down a column.

## How it is laid out

```
src/
  app/          shell, navigation, route guards
  components/ui primitives, icons, tabs, tables, dialogs, async states
  features/     one folder per domain area; each owns its api.ts and its pages
  lib/
    api/        the HTTP client, error model, query keys, list hooks
    auth/       token storage, session context, the role capability map
    lifecycle   the backend's state machines, transcribed
    money       exact decimal arithmetic for the invoice draft preview
    format      dates, money and quantities for display
  types/api.ts  every DTO the backend exposes
  test/         MSW server, render helpers, fixtures
```

`src/types/api.ts` is transcribed from the backend's response classes rather than
generated. Two things about the API shape drive a lot of the code above it: the backend
runs with `spring.jackson.default-property-inclusion: non_null`, so an absent field means
absent rather than `null`; and `BigDecimal` serialises as a string, which is why money
stays a string all the way to the formatter.

## Things worth knowing before changing something

**Money is never a JavaScript number.** Totals, balances and line amounts arrive as
strings and are formatted as strings. The one place arithmetic happens is
`src/lib/money.ts`, in `bigint`, for the *estimate* under a draft invoice — the server
computes the figures that are stored and billed, and it is the only authority on them.

**Role checks are UX, not security.** `src/lib/auth/roles.ts` mirrors the backend's
`@PreAuthorize` annotations so the interface does not offer buttons that will 403. The
backend enforces every one of them regardless of what this file says.

**Lifecycle rules are a copy.** `src/lib/lifecycle.ts` transcribes the server's status
policies so a dropdown never lists a transition that will be refused. If the two ever
disagree, the backend is right and that file is the bug.

**The access token lives in memory only.** The refresh token is in `localStorage`, which
is a real trade-off recorded in `src/lib/auth/tokenStorage.ts` — an httpOnly cookie would
be a backend change and was not made. A 401 triggers exactly one refresh no matter how
many requests failed at once; see the single-flight promise in `src/lib/api/client.ts`.

**Document bytes never go through the Spring API.** Register, PUT to the presigned URL
with no `Authorization` header, then tell the backend it landed. `CaseDocumentsTab` is the
only place that does this, and an expired link restarts at registration rather than
replaying a dead signature.

**A table above `md`, records below it.** `DataTable` renders a real table on wide screens
and a list of stacked records on narrow ones — not a table that shrinks, because a
seven-column table at 375px is unusable however it is styled. Rows whose actions would
squeeze the content give those actions their own line below `sm`.

**Tabs follow the ARIA pattern.** Only the selected tab is in the tab order, arrow keys and
Home/End move between them, and the selected tab scrolls itself into view on a narrow
screen. That keyboard model is why `Tabs` is a component rather than a row of buttons.

## Tests

88 tests across fourteen files, run with `npm test`. They cover the things that are actually
easy to get wrong: exact decimal arithmetic and HALF_UP rounding, the refresh single-flight
under a burst of parallel 401s, the presigned upload contract including the absent
Authorization header and the expired-link retry, lifecycle and role gating on the invoice
page, the auth redirect that must not fire while the session is still being restored, and
the open-redirect check on notification action paths — plus the tab keyboard model and the
form-field wiring that `Field` exists to guarantee.

MSW backs every test with `onUnhandledRequest: 'error'`, so a request nobody wrote a
handler for fails the suite rather than passing quietly for the wrong reason.
