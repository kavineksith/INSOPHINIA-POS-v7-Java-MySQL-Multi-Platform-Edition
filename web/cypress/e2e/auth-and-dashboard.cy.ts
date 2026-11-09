/**
 * Full E2E auth + dashboard smoke. Requires a running backend with demo seed
 * data (start backend with `app.seed.demo-data=true`).
 *
 * Run with: SEED_ADMIN_PASSWORD=TestMaster!Password123 npm run test:e2e
 *
 * The test exercises:
 *   1. Logging in via the UI (real form fill + submit)
 *   2. Forced password change (master_admin's mustChangePassword=true)
 *   3. Re-login with the new password
 *   4. Dashboard renders the KPI cards
 */
describe('Auth + dashboard flow (requires backend)', { browser: 'chrome' }, () => {
  const ADMIN_USER = 'master';
  const INITIAL_PASSWORD = Cypress.env('SEED_ADMIN_PASSWORD') || 'TestMaster!Password123';
  const NEW_PASSWORD = 'NewMaster!Password456';

  // Skip this spec when the backend isn't reachable. The login-smoke spec
  // covers the static-asset case and runs without a backend.
  before(function () {
    const skip = this.skip.bind(this);
    cy.request({
      url: `${Cypress.env('API_BASE_URL') || 'http://127.0.0.1:8080'}/api/system/health`,
      failOnStatusCode: false,
    }).then((r) => {
      if (r.status !== 200) {
        cy.task('log', 'Backend not reachable — skipping auth-flow spec.');
        skip();
      }
    });
  });

  it('signs in, changes password, and lands on the dashboard', () => {
    cy.visit('/login');
    cy.get('input[autocomplete="username"]').type(ADMIN_USER);
    cy.get('input[autocomplete="current-password"]').type(INITIAL_PASSWORD);
    cy.get('button[type="submit"]').click();

    // First login forces a password change.
    cy.url().should('include', '/change-password');
    cy.get('input[name="current"]').type(INITIAL_PASSWORD);
    cy.get('input[name="next"]').type(NEW_PASSWORD);
    cy.get('input[name="confirm"]').type(NEW_PASSWORD);
    cy.get('button[type="submit"]').click();

    // We get bounced back to /login.
    cy.url().should('include', '/login');

    // Log in with the new password.
    cy.get('input[autocomplete="username"]').type(ADMIN_USER);
    cy.get('input[autocomplete="current-password"]').type(NEW_PASSWORD);
    cy.get('button[type="submit"]').click();

    cy.url().should('include', '/dashboard');
    cy.contains(/today['\'s]* sales/i).should('be.visible');
  });
});
