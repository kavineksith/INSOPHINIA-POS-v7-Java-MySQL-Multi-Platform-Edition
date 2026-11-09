// Cypress support file — runs before every spec.
// Adds the `cy.login(username, password)` custom command which performs the
// real login flow (POST /api/auth/login) and stores the JWT in sessionStorage,
// mirroring how the production app authenticates.

declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace Cypress {
    interface Chainable<Subject> {
      /** Log in via the REST API and persist the session. */
      login(username: string, password: string): Chainable<Cypress.Response>;
      /** Get the current auth token from sessionStorage. */
      authToken(): Chainable<string | null>;
    }
  }
}

Cypress.Commands.add('login', (username: string, password: string) => {
  return cy
    .request({
      method: 'POST',
      url: `${Cypress.env('API_BASE_URL') || 'http://127.0.0.1:8080'}/api/auth/login`,
      body: { username, password },
      failOnStatusCode: false,
    })
    .then((response) => {
      if (response.status !== 200) {
        throw new Error(`Login failed: ${response.status} ${JSON.stringify(response.body)}`);
      }
      const data = response.body.data || response.body;
      if (data.requires2fa) {
        throw new Error('Cypress login cannot handle 2FA — use a non-2FA test user');
      }
      sessionStorage.setItem('pos_token', data.token);
      sessionStorage.setItem('pos_user', JSON.stringify({
        userId: data.userId,
        username: data.username,
        role: data.role,
        fullName: data.fullName,
        permissions: data.permissions || [],
        mustChangePassword: data.mustChangePassword,
      }));
      return response;
    });
});

Cypress.Commands.add('authToken', () =>
  cy.wrap(sessionStorage.getItem('pos_token'))
);

// Keep sessionStorage across test reloads so login persists between specs.
beforeEach(() => {
  Cypress.session.clearCurrentSessionData();
});

export {};
