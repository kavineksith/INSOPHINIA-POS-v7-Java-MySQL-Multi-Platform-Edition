import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AuthzModal from '../components/AuthzModal';

/**
 * Unit tests for the AuthzModal component, which replaces the insecure
 * `window.prompt` authorizer flow used in the original Vite port. These
 * tests cover:
 *   - Rendering: title changes based on `kind` (redeem / return / cancel)
 *   - The reason field appears only for return/cancel (not for redeem)
 *   - The submit callback is invoked with the form payload
 *   - The cancel button closes the modal
 */
describe('AuthzModal', () => {
  it('is null when closed', () => {
    const { container } = render(<AuthzModal open={false} kind="redeem" onClose={() => {}} onSubmit={() => {}} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders the loyalty-redeem title when kind=redeem and hides the reason field', () => {
    render(<AuthzModal open kind="redeem" onClose={() => {}} onSubmit={() => {}} />);
    expect(screen.getByText('Approve loyalty redemption')).toBeInTheDocument();
    expect(screen.queryByLabelText(/reason/i)).not.toBeInTheDocument();
  });

  it('renders the return title when kind=return and shows the reason field', () => {
    render(<AuthzModal open kind="return" onClose={() => {}} onSubmit={() => {}} />);
    expect(screen.getByText('Authorize bill return')).toBeInTheDocument();
    expect(screen.getByLabelText(/reason/i)).toBeInTheDocument();
  });

  it('renders the cancel title when kind=cancel', () => {
    render(<AuthzModal open kind="cancel" onClose={() => {}} onSubmit={() => {}} />);
    expect(screen.getByText('Authorize bill cancellation')).toBeInTheDocument();
  });

  it('submits the username + password (no reason for redeem)', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    render(<AuthzModal open kind="redeem" onClose={() => {}} onSubmit={onSubmit} />);

    await user.type(screen.getByLabelText(/supervisor username/i), 'supervisor1');
    await user.type(screen.getByLabelText(/supervisor password/i), 'SuperSecret123!');
    await user.click(screen.getByRole('button', { name: /authorize/i }));

    expect(onSubmit).toHaveBeenCalledTimes(1);
    const arg = onSubmit.mock.calls[0][0];
    expect(arg).toEqual({
      kind: 'redeem',
      reason: undefined,
      username: 'supervisor1',
      password: 'SuperSecret123!',
    });
  });

  it('submits the reason when kind=return', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    render(<AuthzModal open kind="return" onClose={() => {}} onSubmit={onSubmit} />);

    await user.type(screen.getByLabelText(/reason/i), 'Customer changed mind');
    await user.type(screen.getByLabelText(/supervisor username/i), 'supervisor1');
    await user.type(screen.getByLabelText(/supervisor password/i), 'SuperSecret123!');
    await user.click(screen.getByRole('button', { name: /authorize/i }));

    expect(onSubmit.mock.calls[0][0]).toEqual({
      kind: 'return',
      reason: 'Customer changed mind',
      username: 'supervisor1',
      password: 'SuperSecret123!',
    });
  });

  it('calls onClose when the Cancel button is clicked', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(<AuthzModal open kind="return" onClose={onClose} onSubmit={() => {}} />);
    await user.click(screen.getByRole('button', { name: /cancel/i }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('disables the submit button while busy', () => {
    render(<AuthzModal open kind="redeem" busy onClose={() => {}} onSubmit={() => {}} />);
    expect(screen.getByRole('button', { name: /authorize/i })).toBeDisabled();
  });

  it('renders the error message when present', () => {
    render(<AuthzModal open kind="redeem" error="Invalid credentials" onClose={() => {}} onSubmit={() => {}} />);
    expect(screen.getByText('Invalid credentials')).toBeInTheDocument();
  });
});
