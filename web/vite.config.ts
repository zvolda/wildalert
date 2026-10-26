// defineConfig comes from vitest/config (it is Vite's own, plus the `test` key); loadEnv is
// imported from vite directly rather than relying on vitest re-exporting it.
import { defineConfig } from 'vitest/config'
import { loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// `vitest/config` re-exports Vite's own defineConfig and adds the `test` key, so one file
// configures both the dev server and the test runner.
export default defineConfig(({ mode }) => {
  // '' as the prefix loads every variable, not just the VITE_-prefixed ones. This value is only
  // used by the dev server (Node side) and never reaches the browser bundle.
  const env = loadEnv(mode, '.', '')
  const apiTarget = env.VITE_API_PROXY_TARGET || 'http://localhost:8081'

  return {
    plugins: [react(), tailwindcss()],

    server: {
      port: 5173,
      proxy: {
        // The browser calls a same-origin '/api/...' and Vite forwards it to user-account.
        // That is what keeps this slice free of CORS config and of any backend change: as far
        // as the browser is concerned there is only one origin. In production the same job is
        // done by Spring Cloud Gateway, which will serve these built files and proxy /api too.
        '/api': {
          target: apiTarget,
          changeOrigin: true,
        },
      },
    },

    test: {
      // Plain Node: everything worth unit-testing here is pure (validation, error parsing), so
      // there is no jsdom and no DOM-testing library to install or keep up to date.
      environment: 'node',
      include: ['src/**/*.test.ts'],
    },
  }
})
