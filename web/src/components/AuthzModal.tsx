import { useState } from 'react';
import type { FormEvent } from 'react';
import { LoaderCircle, X } from 'lucide-react';

/**
 * Supervisor authorizer modal.
 *
 * Replaces the original `window.prompt`-based authorizer flow used in the
 * v5 → Vite port (where it was both insecure — password leaked through the
 * browser autofill history — and unusable on mobile / POS terminals).
 *
 * Used by:
 *   - Pos.tsx — loyalty point redemption above 100 points
 *   - Bills.tsx — bill return and bill cancel
 *
 * The parent component owns the open/close state and wires the onSubmit
 * callback. The modal itself is uncontrolled except for the form fields.
 */
export interface AuthzRequest {
  kind: 'redeem' | 'return' | 'cancel';
  reason?: string;
  username: string;
  password: string;
}

export interface AuthzModalProps {
  open: boolean;
  kind: 'redeem' | 'return' | 'cancel';
  busy?: boolean;
  error?: string;
  onClose: () => void;
  onSubmit: (req: AuthzRequest) => void | Promise<void>;
}

export default function AuthzModal({ open, kind, busy, error, onClose, onSubmit }: AuthzModalProps) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [reason, setReason] = useState('');

  if (!open) return null;

  const title =
    kind === 'redeem' ? 'Approve loyalty redemption' :
    kind === 'return' ? 'Authorize bill return' :
    'Authorize bill cancellation';

  const eyebrow =
    kind === 'redeem' ? 'LOYALTY AUTHORIZATION' :
    kind === 'return' ? 'RETURN AUTHORIZATION' : 'CANCELLATION AUTHORIZATION';

  const requiresReason = kind === 'return' || kind === 'cancel';

  const submit = (e: FormEvent) => {
    e.preventDefault();
    onSubmit({ kind, username: username.trim(), password, reason: requiresReason ? reason.trim() : undefined });
  };

  return (
    <div
      className="modal-backdrop"
      style={{
        position: 'fixed', inset: 0, background: 'rgba(15,23,42,0.55)',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        zIndex: 1000, padding: 16,
      }}
      onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}
    >
      <form
        onSubmit={submit}
        style={{
          width: '100%', maxWidth: 460, background: '#fff', borderRadius: 12,
          padding: 24, boxShadow: '0 20px 60px rgba(15,23,42,0.3)',
          display: 'flex', flexDirection: 'column', gap: 12,
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
          <div>
            <div style={{ fontSize: 11, letterSpacing: '0.12em', color: '#64748b', fontWeight: 700 }}>{eyebrow}</div>
            <h2 style={{ margin: '4px 0 0', fontSize: 20, fontWeight: 700 }}>{title}</h2>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            style={{ border: 'none', background: 'transparent', cursor: 'pointer', color: '#64748b' }}
          >
            <X size={18} />
          </button>
        </div>

        {error && (
          <div style={{ padding: '8px 12px', background: '#fee2e2', color: '#991b1b', borderRadius: 8, fontSize: 13 }}>
            {error}
          </div>
        )}

        {requiresReason && (
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <span style={{ fontSize: 13, fontWeight: 600 }}>Reason</span>
            <textarea
              value={reason}
              onChange={e => setReason(e.target.value)}
              required
              rows={3}
              style={{ padding: '8px 10px', borderRadius: 8, border: '1px solid #cbd5e1', fontSize: 14 }}
              placeholder="Explain why this action is being taken…"
            />
          </label>
        )}

        <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <span style={{ fontSize: 13, fontWeight: 600 }}>Supervisor username</span>
          <input
            value={username}
            onChange={e => setUsername(e.target.value)}
            required
            autoComplete="username"
            style={{ padding: '8px 10px', borderRadius: 8, border: '1px solid #cbd5e1', fontSize: 14 }}
          />
        </label>

        <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <span style={{ fontSize: 13, fontWeight: 600 }}>Supervisor password</span>
          <input
            type="password"
            value={password}
            onChange={e => setPassword(e.target.value)}
            required
            autoComplete="current-password"
            style={{ padding: '8px 10px', borderRadius: 8, border: '1px solid #cbd5e1', fontSize: 14 }}
          />
        </label>

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 8 }}>
          <button
            type="button"
            onClick={onClose}
            style={{ padding: '8px 16px', borderRadius: 8, border: '1px solid #cbd5e1', background: '#fff', cursor: 'pointer' }}
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={busy}
            style={{
              padding: '8px 16px', borderRadius: 8, border: 'none',
              background: busy ? '#94a3b8' : '#0f172a', color: '#fff',
              cursor: busy ? 'wait' : 'pointer', display: 'inline-flex', alignItems: 'center', gap: 6,
            }}
          >
            {busy && <LoaderCircle size={16} className="spin" />}
            Authorize
          </button>
        </div>
      </form>
    </div>
  );
}
