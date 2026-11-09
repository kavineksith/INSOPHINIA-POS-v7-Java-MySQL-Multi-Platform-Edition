/**
 * Smoke E2E test: load the login page and verify the brand hero is visible.
 *
 * This test does NOT require a running backend — it just verifies that the
 * Vite preview server is up and serving the production index.html with the
 * expected brand assets.
 */
describe('Login page smoke test', () => {
  it('renders the INSOPHINIA brand hero on the login page', () => {
    cy.visit('/login');
    cy.contains('INSOPHINIA').should('be.visible');
    cy.contains('RETAIL OPERATIONS PLATFORM').should('be.visible');
  });

  it('shows the password input and the secure-login button', () => {
    cy.visit('/login');
    cy.get('input[autocomplete="username"]').should('be.visible');
    cy.get('input[autocomplete="current-password"]').should('be.visible');
    cy.contains('button', /sign in securely/i).should('be.visible');
  });

  it('redirects to /login when visiting /dashboard unauthenticated', () => {
    cy.visit('/dashboard');
    cy.url().should('include', '/login');
  });
});
