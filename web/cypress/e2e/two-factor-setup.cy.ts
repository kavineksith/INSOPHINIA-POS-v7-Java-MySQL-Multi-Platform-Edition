/// <reference types="cypress" />
import { TOTP } from 'otpauth';

/**
 * End-to-end test for the two-factor authentication lifecycle:
 *
 *   QR-code display → secret shown → backup codes saved → TOTP verified →
 *   2FA enabled → success panel → 2FA disabled.
 *
 * Requires a running backend with `app.seed.demo-data=true` so that the demo
 * admin1 user (with `mustChangePassword=false`) exists. The test reads the
 * demo credentials from Cypress env (which falls back to the same defaults
 * used by the seed).
 *
 * TOTP code generation runs entirely inside the Cypress spec (via the
 * `otpauth` package, browser-runnable). The QR image rendered by `qrcode.react`
 * is mocked out — the test reads the secret directly from the visible <code>
 * element in the manual-entry fallback path, so it never has to scan pixels.
 *
 * Run with:
 *   SEED_ADMIN_PASSWORD=TestMaster!Password123 \
 *   CYPRESS_DEMO_USER=admin1 \
 *   CYPRESS_DEMO_PASSWORD=TestMaster!Password123 \
 *   CYPRESS_API_BASE_URL=http://127.0.0.1:8080 \
 *   npm run test:e2e -- --spec cypress/e2e/two-factor-setup.cy.ts
 */

describe('Two-factor authentication lifecycle', { browser: 'chrome' }, () => {
  const API_BASE = Cypress.env('API_BASE_URL') || 'http://127.0.0.1:8080';
  const DEMO_USER = Cypress.env('DEMO_USER') || 'admin1';
  const DEMO_PASSWORD = Cypress.env('DEMO_PASSWORD') || Cypress.env('SEED_ADMIN_PASSWORD') || 'TestMaster!Password123';

  // Skip the entire spec when the backend is not reachable — this keeps the
  // suite portable for environments that only want to run the static-asset
  // login-smoke spec. We use Mocha's runtime `this.skip()` via a non-arrow
  // `before` function so we have access to the Mocha context (arrow functions
  // don't bind `this`). The skip call happens after a cy.request resolves;
  // because `cy.request` runs in Cypress's command queue (outside the
  // Mocha test context), we capture `this.skip` into a local closure and
  // invoke it from the .then() callback.
  before(function () {
    const skip = this.skip.bind(this);
    cy.request({ url: `${API_BASE}/api/system/health`, failOnStatusCode: false }).then((r) => {
      if (r.status !== 200) {
        cy.task('log', `Backend not reachable at ${API_BASE} — skipping 2FA spec.`);
        skip();
      }
    });
  });

  // Reset the demo user's 2FA state before the spec runs so the test is
  // re-runnable. We do this directly via the API: login, call /2fa/disable
  // (which is idempotent if 2FA isn't enabled), and clear the secret via
  // /2fa/setup → /2fa/disable round trip if needed. If a previous test run
  // left the user with 2FA enabled but a stale secret, the disable call
  // here flips it back to disabled so the spec's setup step can succeed.
  beforeEach(() => {
    cy.login(DEMO_USER, DEMO_PASSWORD).then((loginResp) => {
      const token = loginResp.body.data?.token || loginResp.body.token;
      if (!token) return;
      // Disable 2FA if currently enabled. Idempotent: returns 200 if disabled,
      // 400 if 2FA isn't enabled. We tolerate both.
      cy.request({
        method: 'POST',
        url: `${API_BASE}/api/auth/2fa/disable`,
        body: { password: DEMO_PASSWORD },
        headers: { Authorization: `Bearer ${token}` },
        failOnStatusCode: false,
      });
    });
  });

  it('sets up 2FA, verifies with a real TOTP code, then disables', () => {
    // 1. Visit /security. The auth session from cy.login() is persisted
    //    to sessionStorage by our custom command — the Vite app reads
    //    pos_token + pos_user from there, so no separate login UI dance.
    cy.visit('/security');

    // Verify the page rendered
    cy.contains('h2', 'Two-factor authentication').should('be.visible');
    cy.contains('button', /begin 2fa setup/i).should('be.visible').and('be.disabled');

    // 2. Enter the demo user's current password to begin setup.
    cy.get('input[placeholder="Your account password"]').type(DEMO_PASSWORD);
    cy.contains('button', /begin 2fa setup/i).should('not.be.disabled').click();

    // 3. The QR + secret + backup codes should now be visible.
    cy.get('canvas, svg').should('exist'); // qrcode.react renders SVG
    cy.get('code').should('be.visible').invoke('text').then((secret) => {
      expect(secret).to.match(/^[A-Z2-7]{16,}$/i, 'secret should be a Base32 string');

      // 4. Save the backup codes so we can assert on them later.
      cy.contains('Backup codes').should('be.visible');
      cy.get('.modal-card, section.panel, body')
        .find('div[style*="monospace"], div[style*="grid"]')
        .contains(/[A-Z0-9]{6,}/i)
        .should('be.visible');

      // 5. Generate a real, currently-valid 6-digit TOTP code from the secret
      //    using the otpauth library. The backend's TotpService uses the same
      //    algorithm (SHA1, 6 digits, 30s period).
      const totp = new TOTP({
        issuer: 'INSOPHINIA POS',
        label: DEMO_USER,
        algorithm: 'SHA1',
        digits: 6,
        period: 30,
        secret: TOTP.decodeSecret(secret),
      });
      const code = totp.generate();
      expect(code).to.match(/^\d{6}$/, 'generated TOTP code should be 6 digits');

      // 6. Tick the "I've saved my backup codes" checkbox.
      cy.contains('label', /I've saved my backup codes/i).find('input[type="checkbox"]').check();

      // 7. Enter the 6-digit code into the verification field.
      cy.get('input[placeholder="123456"]').clear().type(code);

      // 8. Click "Confirm and enable 2FA".
      cy.contains('button', /confirm and enable 2fa/i).should('not.be.disabled').click();

      // 9. The success panel should appear, confirming 2FA is enabled.
      cy.contains(/two-factor authentication is enabled/i).should('be.visible');

      // 10. Close the success panel to return to the idle-2FA-enabled state.
      cy.contains('button', /close/i).click();

      // 11. The "2FA is active on your account" panel and Disable button
      //     should now be visible.
      cy.contains(/2FA is active on your account/i).should('be.visible');
      cy.contains('button', /disable 2fa/i).should('be.visible').click();

      // 12. The disable form appears — enter password to confirm.
      cy.get('input[placeholder="Account password"]').should('be.visible').type(DEMO_PASSWORD);
      cy.contains('button', /confirm disable/i).click();

      // 13. Verify the success notice appears. The 2FA badge in the panel
      //     header should now show "Disabled".
      cy.contains(/two-factor authentication has been disabled/i).should('be.visible');
    });
  });

  it('prevents enabling 2FA with an invalid verification code', () => {
    cy.visit('/security');
    cy.get('input[placeholder="Your account password"]').type(DEMO_PASSWORD);
    cy.contains('button', /begin 2fa setup/i).click();

    // Read the secret (we don't actually need it for this test)
    cy.get('code').should('be.visible');

    // Tick the backup-codes checkbox (the UI requires this before enabling)
    cy.contains('label', /I've saved my backup codes/i).find('input[type="checkbox"]').check();

    // Enter an obviously-wrong code. 000000 has a 1-in-1,000,000 chance of
    // being valid; if this ever flaps, regenerate the secret or wait.
    cy.get('input[placeholder="123456"]').clear().type('000000');
    cy.contains('button', /confirm and enable 2fa/i).click();

    // The backend returns 401, which the Vite api helper surfaces as an error
    // message in the Notice component.
    cy.contains(/invalid verification code|verification failed|invalid code/i, { matchCase: false })
      .should('be.visible');
  });

  it('refuses to begin setup with a wrong password', () => {
    cy.visit('/security');
    cy.get('input[placeholder="Your account password"]').type('WrongPassword!123');
    cy.contains('button', /begin 2fa setup/i).click();

    // The backend returns 401 "Password is incorrect"; the Vite UI surfaces
    // this via the Notice component.
    cy.contains(/password is incorrect|invalid password|unauthorized/i, { matchCase: false })
      .should('be.visible');

    // The setup panel should still be visible — we should NOT have moved to
    // the QR step.
    cy.get('input[placeholder="123456"]').should('not.exist');
  });
});
