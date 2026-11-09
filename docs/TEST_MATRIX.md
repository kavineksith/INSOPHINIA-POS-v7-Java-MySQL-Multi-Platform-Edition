# Cross-application test matrix

| Layer | Framework | Minimum scenarios |
|---|---|---|
| Spring API unit | JUnit 5, Mockito | validation, auth/roles, pricing/tax/discounts, inventory invariants, email normalization, token expiry, enum mappings, error responses |
| Spring API integration | Spring Boot Test, MockMvc, Flyway + isolated MySQL | login, CRUD, checkout transaction, stock decrement, reports, migration from empty DB, uniqueness constraints |
| Vite UI unit | Vitest/Jest + React Testing Library | form validation, Redux cart reducers, Context auth state, loading/error states, keyboard access |
| Vite UI browser | Cypress | login/logout, POS cart, discounts, payment, receipts, customer CRUD, responsive viewport, API error handling |
| the API the current version unit | Jest | customer email schema, normalized email, duplicate email, verification token valid/expired/consumed, seed idempotency |
| the API browser | Cypress | customer create/update, verification workflow, responsive POS flows |
| Desktop unit | JUnit 5, Mockito | session expiry, API client errors, receipt formatting, barcode input, local setup validation |
| Desktop integration | JUnit 5 | login, API connectivity, checkout/receipt scenario, reconnect after API outage |
| Cross-env | scripts/CI local | env values required, no hardcoded secrets, dev/test DB separation, migrations and rollback restore rehearsal |

Never run destructive integration tests against production. Use unique test database names and disposable fixtures. A framework is only considered covered after tests are implemented and executed; this matrix is a plan, not a claim of passing tests.
