import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import Verify2fa from '../pages/Verify2fa';

// Mock the api helper so the test never makes a real HTTP call.
// vi.mock is hoisted to the top of the file, so the factory cannot reference
// outer-scope variables. Use vi.hoisted to declare the mock in the hoisted
// scope, then bind vi.fn() to it.
const { acceptToken, apiMock } = vi.hoisted(() => ({
  acceptToken: vi.fn(),
  apiMock: vi.fn(),
}));

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ acceptToken }),
}));

vi.mock('../lib/api', () => ({
  api: apiMock,
}));

function renderVerify2fa() {
  return render(
    <MemoryRouter initialEntries={['/verify-2fa']}>
      <Verify2fa />
    </MemoryRouter>
  );
}

describe('Verify2fa page', () => {
  beforeEach(() => {
    sessionStorage.clear();
    sessionStorage.setItem('pos_pending_2fa', 'fake-pending-token');
    acceptToken.mockReset();
    apiMock.mockReset();
  });

  it('redirects to /login when no pending 2FA token is in sessionStorage', () => {
    sessionStorage.removeItem('pos_pending_2fa');
    renderVerify2fa();
    // The component returns null in useEffect before the redirect fires.
    expect(apiMock).not.toHaveBeenCalled();
  });

  it('renders 6 OTP input cells', () => {
    renderVerify2fa();
    const inputs = screen.getAllByRole('textbox');
    expect(inputs).toHaveLength(6);
  });

  it('toggles to backup-code mode when the toggle button is clicked', async () => {
    const user = userEvent.setup();
    renderVerify2fa();
    const toggle = screen.getByRole('button', { name: /use a backup code instead/i });
    await user.click(toggle);
    expect(screen.getByPlaceholderText('ABCD-EFGH')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /use authenticator app/i })).toBeInTheDocument();
  });

  it('submits the 6-digit code to /api/auth/2fa/verify and calls acceptToken on success', async () => {
    const user = userEvent.setup();
    apiMock.mockResolvedValueOnce({
      data: {
        token: 'real-jwt',
        userId: '11111111-1111-1111-1111-111111111111',
        username: 'cashier1',
        role: 'cashier',
        fullName: 'Cashier One',
        permissions: ['view:billing', 'manage:billing'],
        mustChangePassword: false,
      },
    });
    renderVerify2fa();

    const inputs = screen.getAllByRole('textbox');
    for (let i = 0; i < 6; i++) {
      await user.type(inputs[i], String(i + 1));
    }
    await user.click(screen.getByRole('button', { name: /verify and continue/i }));

    expect(apiMock).toHaveBeenCalledTimes(1);
    const [path, init] = apiMock.mock.calls[0];
    expect(path).toBe('/api/auth/2fa/verify');
    expect(JSON.parse(init.body)).toEqual({ pendingToken: 'fake-pending-token', code: '123456' });
    expect(acceptToken).toHaveBeenCalledTimes(1);
    // After a successful verify, the pending token must be cleared.
    expect(sessionStorage.getItem('pos_pending_2fa')).toBeNull();
  });

  it('shows the error returned by the API', async () => {
    const user = userEvent.setup();
    apiMock.mockRejectedValueOnce(new Error('Invalid code'));
    renderVerify2fa();

    const inputs = screen.getAllByRole('textbox');
    for (let i = 0; i < 6; i++) await user.type(inputs[i], '9');
    await user.click(screen.getByRole('button', { name: /verify and continue/i }));

    expect(await screen.findByText('Invalid code')).toBeInTheDocument();
    expect(sessionStorage.getItem('pos_pending_2fa')).toBe('fake-pending-token');
  });
});
