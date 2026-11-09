import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import SystemPage from './SystemPage';

// Mock AuthContext
const { useAuthMock } = vi.hoisted(() => ({ useAuthMock: vi.fn() }));
vi.mock('../context/AuthContext', () => ({ useAuth: () => useAuthMock() }));

// Mock api
const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }));
vi.mock('../lib/api', () => ({
  api: apiMock,
  apiUrl: (p: string) => p,
  getToken: () => 'test-token',
  dateTime: (v: string) => new Date(v).toLocaleString(),
}));

// Mock QRCodeSVG so we don't drag the actual QR renderer into the test bundle.
vi.mock('qrcode.react', () => ({
  QRCodeSVG: ({ value }: { value: string }) => <div data-testid="qr-mock" data-value={value} />,
}));

function renderSecurityPage() {
  return render(
    <MemoryRouter>
      <SystemPage security />
    </MemoryRouter>
  );
}

describe('SystemPage (security mode) — 2FA setup flow', () => {
  beforeEach(() => {
    useAuthMock.mockReset();
    apiMock.mockReset();
    useAuthMock.mockReturnValue({
      user: {
        userId: '1', username: 'admin', role: 'master_admin',
        fullName: 'Admin', permissions: [], mustChangePassword: false,
      },
    });
    // Initial parallel load: sessions, security-events, system status
    apiMock
      .mockResolvedValueOnce({ data: [] }) // sessions
      .mockResolvedValueOnce({ data: [] }) // security-events
      .mockResolvedValueOnce({ data: { locked: false, lockReason: null, lockedAt: null, lastCheckinAt: '2026-10-18T10:00:00Z', deadmanDays: 72 } });
  });

  it('shows the 2FA setup panel with a disabled "Begin 2FA setup" button until password is entered', async () => {
    renderSecurityPage();
    await waitFor(() => {
      expect(screen.getByText('Two-factor authentication')).toBeInTheDocument();
    });
    const begin = screen.getByRole('button', { name: /begin 2fa setup/i });
    expect(begin).toBeDisabled();
  });

  it('enables the begin button after password input', async () => {
    const user = userEvent.setup();
    renderSecurityPage();
    await waitFor(() => expect(screen.getByRole('button', { name: /begin 2fa setup/i })).toBeInTheDocument());

    await user.type(screen.getByPlaceholderText('Your account password'), 'MyPassword123!');
    expect(screen.getByRole('button', { name: /begin 2fa setup/i })).not.toBeDisabled();
  });

  it('calls /api/auth/2fa/setup on submit and shows QR + backup codes', async () => {
    const user = userEvent.setup();
    apiMock.mockResolvedValueOnce({
      data: {
        secret: 'JBSWY3DPEHPK3PXP',
        otpauthUrl: 'otpauth://totp/INSOPHINIA:admin?secret=JBSWY3DPEHPK3PXP&issuer=INSOPHINIA',
        backupCodes: ['ABCD1234', 'EFGH5678', 'IJKL9012'],
      },
    });
    renderSecurityPage();
    await waitFor(() => expect(screen.getByRole('button', { name: /begin 2fa setup/i })).toBeInTheDocument());

    await user.type(screen.getByPlaceholderText('Your account password'), 'MyPassword123!');
    await user.click(screen.getByRole('button', { name: /begin 2fa setup/i }));

    await waitFor(() => {
      // Verify the setup endpoint was hit
      const setupCall = apiMock.mock.calls.find(([path]) => path === '/api/auth/2fa/setup');
      expect(setupCall).toBeDefined();
      // QR mock renders with the otpauth URL
      expect(screen.getByTestId('qr-mock')).toHaveAttribute('data-value', 'otpauth://totp/INSOPHINIA:admin?secret=JBSWY3DPEHPK3PXP&issuer=INSOPHINIA');
      // Secret is shown for manual entry
      expect(screen.getByText('JBSWY3DPEHPK3PXP')).toBeInTheDocument();
      // All backup codes are rendered
      expect(screen.getByText('ABCD1234')).toBeInTheDocument();
      expect(screen.getByText('EFGH5678')).toBeInTheDocument();
      expect(screen.getByText('IJKL9012')).toBeInTheDocument();
    });
  });

  it('requires backup codes to be acknowledged before the confirm button enables', async () => {
    const user = userEvent.setup();
    apiMock.mockResolvedValueOnce({
      data: {
        secret: 'JBSWY3DPEHPK3PXP',
        otpauthUrl: 'otpauth://totp/test?secret=test',
        backupCodes: ['ABCD1234'],
      },
    });
    renderSecurityPage();
    await waitFor(() => expect(screen.getByRole('button', { name: /begin 2fa setup/i })).toBeInTheDocument());

    await user.type(screen.getByPlaceholderText('Your account password'), 'MyPassword123!');
    await user.click(screen.getByRole('button', { name: /begin 2fa setup/i }));

    await waitFor(() => expect(screen.getByPlaceholderText('123456')).toBeInTheDocument());

    const confirmBtn = screen.getByRole('button', { name: /confirm and enable 2fa/i });
    expect(confirmBtn).toBeDisabled(); // no code, no checkbox ticked

    await user.type(screen.getByPlaceholderText('123456'), '123456');
    expect(confirmBtn).toBeDisabled(); // code present but checkbox unchecked

    await user.click(screen.getByLabelText(/I've saved my backup codes/i));
    expect(confirmBtn).not.toBeDisabled();
  });

  it('disables 2FA after entering password and clicking disable', async () => {
    // Force the state to "2FA enabled" by simulating a setup-then-enable sequence
    // First, mock setup
    apiMock.mockResolvedValueOnce({
      data: { secret: 'JBSWY3DPEHPK3PXP', otpauthUrl: 'otpauth://totp/t?secret=t', backupCodes: ['ABCD1234'] },
    });
    // Then, mock the enable call (returns empty success)
    apiMock.mockResolvedValueOnce({ data: {} });

    const user = userEvent.setup();
    renderSecurityPage();
    await waitFor(() => expect(screen.getByRole('button', { name: /begin 2fa setup/i })).toBeInTheDocument());

    // Run setup → enable
    await user.type(screen.getByPlaceholderText('Your account password'), 'MyPassword123!');
    await user.click(screen.getByRole('button', { name: /begin 2fa setup/i }));
    await waitFor(() => expect(screen.getByPlaceholderText('123456')).toBeInTheDocument());
    await user.type(screen.getByPlaceholderText('123456'), '123456');
    await user.click(screen.getByLabelText(/I've saved my backup codes/i));
    await user.click(screen.getByRole('button', { name: /confirm and enable 2fa/i }));

    // Success panel appears — "Two-factor authentication is enabled"
    await waitFor(() => expect(screen.getByText(/two-factor authentication is enabled/i)).toBeInTheDocument());
    // Click Close to return to the idle state — then "2FA is active on your account" + Disable button appears
    await user.click(screen.getByRole('button', { name: /close/i }));

    // Click "Disable 2FA…"
    await user.click(screen.getByRole('button', { name: /disable 2fa/i }));
    const disablePassword = screen.getByPlaceholderText('Account password');
    expect(disablePassword).toBeInTheDocument();

    apiMock.mockResolvedValueOnce({ data: {} });
    await user.type(disablePassword, 'MyPassword123!');
    await user.click(screen.getByRole('button', { name: /confirm disable/i }));

    await waitFor(() => {
      const disableCall = apiMock.mock.calls.find(([path]) => path === '/api/auth/2fa/disable');
      expect(disableCall).toBeDefined();
    });
  });
});
