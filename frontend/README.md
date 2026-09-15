# thispatch

## Tech stack

- **Framework**: React + Vite + TypeScript
- **Styling**: Tailwind CSS, `clsx` + `tailwind-merge` (see [src/lib/cn.ts](src/lib/cn.ts))
- **State**: Zustand ([src/store](src/store))
- **Server state**: TanStack Query ([src/lib/queryClient.ts](src/lib/queryClient.ts))
- **Routing**: React Router ([src/router](src/router))
- **HTTP**: Axios ([src/lib/axios.ts](src/lib/axios.ts))
- **API mocking**: MSW ([src/mocks](src/mocks)) — starts automatically in dev via `main.tsx`
- **E2E tests**: Playwright ([e2e](e2e), `playwright.config.ts`)
- **Lint/format**: ESLint + Prettier
- **Package manager**: pnpm
- **Performance**: `web-vitals` (logged to console in dev, [src/lib/reportWebVitals.ts](src/lib/reportWebVitals.ts)) + Lighthouse CI (`lighthouserc.json`)
- **CI/CD**: Jenkins ([Jenkinsfile](Jenkinsfile))
- **Deploy**: Docker + Nginx ([Dockerfile](Dockerfile), [nginx.conf](nginx.conf)) targeting AWS

## Scripts

```bash
pnpm dev            # start dev server (http://localhost:5173)
pnpm build          # type-check + production build
pnpm preview        # preview the production build

pnpm lint           # eslint
pnpm lint:fix       # eslint --fix
pnpm format         # prettier --write
pnpm format:check   # prettier --check

pnpm test:e2e       # run Playwright tests (auto-starts dev server)
pnpm test:e2e:ui    # run Playwright tests in UI mode
```

## API mocking (MSW)

Add handlers in [src/mocks/handlers.ts](src/mocks/handlers.ts). The worker starts
automatically when running `pnpm dev`; disable per-request via `onUnhandledRequest`
in [src/main.tsx](src/main.tsx) if you need to hit a real backend for some calls.

## Docker

```bash
docker build -t thispatch-frontend .
docker run -p 8080:80 thispatch-frontend
```
