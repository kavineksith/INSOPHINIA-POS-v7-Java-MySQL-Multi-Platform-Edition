import { defineConfig } from 'cypress';

/**
 * Cypress E2E config — runs against a running Vite dev server (or the
 * production preview server). Test files live under `cypress/e2e/*.cy.ts`.
 *
 * Usage:
 *   npm run test:e2e         — headless Chrome
 *   npm run test:e2e:open    — interactive Cypress runner
 *
 * The base URL defaults to the Vite preview URL (http://127.0.0.1:4173).
 * Override with `CYPRESS_BASE_URL` env var to point at a deployed env.
 */
export default defineConfig({
  e2e: {
    baseUrl: process.env.CYPRESS_BASE_URL || 'http://127.0.0.1:4173',
    specPattern: 'cypress/e2e/**/*.cy.ts',
    supportFile: 'cypress/support/e2e.ts',
    viewportWidth: 1280,
    viewportHeight: 800,
    defaultCommandTimeout: 10000,
    video: false,
    screenshotsFolder: 'cypress/screenshots',
    fixturesFolder: 'cypress/fixtures',
  },
});
