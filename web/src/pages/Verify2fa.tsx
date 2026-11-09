import { useEffect, useRef, useState } from 'react';import type { FormEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ArrowRight, ArrowLeft, LoaderCircle, ShieldCheck, KeyRound } from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { api } from '../lib/api';

/**
 * Standalone 2FA verification page — parity with v5 /verify-2fa.
 *
 * - Reads `pos_pending_2fa` from sessionStorage (set by Login.tsx on
 *   `requires2fa` response). If absent, redirects back to /login.
 * - 6-cell OTP with auto-advance, paste handler, backspace navigation.
 * - Backup-code toggle for users without their authenticator device.
 * - Honors `?redirect=` query param to deep-link back to the original route.
 */
export default function Verify2fa() {
  const { acceptToken } = useAuth();
  const nav = useNavigate();
  const [sp] = useSearchParams();

  const [cells, setCells] = useState(['', '', '', '', '', '']);
  const [backup, setBackup] = useState('');
  const [useBackup, setUseBackup] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const refs = useRef<(HTMLInputElement | null)[]>([]);

  const pending = sessionStorage.getItem('pos_pending_2fa') || '';

  useEffect(() => {
    if (!pending) {
      nav('/login', { replace: true });
    } else {
      refs.current[0]?.focus();
    }
  }, [pending, nav]);

  const onCell = (i: number, v: string) => {
    if (!/^\d*$/.test(v)) return;
    const next = [...cells];
    next[i] = v.slice(-1);
    setCells(next);
    if (v && i < 5) refs.current[i + 1]?.focus();
  };

  const onKey = (i: number, e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Backspace' && !cells[i] && i > 0) refs.current[i - 1]?.focus();
  };

  const onPaste = (e: React.ClipboardEvent) => {
    e.preventDefault();
    const digits = e.clipboardData.getData('text').replace(/\D/g, '').slice(0, 6);
    if (!digits) return;
    const next = ['', '', '', '', '', ''];
    for (let i = 0; i < digits.length; i++) next[i] = digits[i];
    setCells(next);
    if (digits.length === 6) refs.current[5]?.focus();
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError('');
    const code = useBackup ? backup.trim() : cells.join('');
    if (!code || (!useBackup && code.length !== 6)) {
      setError('Enter a valid 6-digit code.');
      setBusy(false);
      return;
    }
    try {
      const r = await api<any>('/api/auth/2fa/verify', {
        method: 'POST',
        body: JSON.stringify({ pendingToken: pending, code }),
      });
      const d = r.data;
      sessionStorage.removeItem('pos_pending_2fa');
      acceptToken(d.token, {
        userId: d.userId,
        username: d.username,
        role: d.role,
        fullName: d.fullName,
        permissions: d.permissions || [],
        mustChangePassword: d.mustChangePassword,
      });
      nav(d.mustChangePassword ? '/change-password' : (sp.get('redirect') || '/dashboard'), { replace: true });
    } catch (err: any) {
      setError(err.message || 'Invalid code');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login-screen">
      <div className="login-glow glow-one" />
      <div className="login-glow glow-two" />
      <section className="login-brand-panel">
        <div className="login-brand">
          <img src="/insophinia_logo.png" alt="INSOPHINIA" />
          <div><b>INSOPHINIA</b><span>RETAIL SYSTEMS</span></div>
        </div>
        <div className="login-hero">
          <div className="hero-tag"><span /> TWO-FACTOR VERIFICATION</div>
          <h1>One more step.<br/><em>Stay secure.</em></h1>
          <p>Enter the code from your authenticator app or one of your saved backup codes to continue.</p>
        </div>
        <div className="login-footnote">INSOPHINIA POS · SPRING BOOT EDITION</div>
      </section>
      <section className="login-form-panel">
        <div className="login-form-wrap">
          <div className="form-mark">{useBackup ? <KeyRound size={23} /> : <ShieldCheck size={23} />}</div>
          <div className="eyebrow">{useBackup ? 'BACKUP CODE' : 'TWO-FACTOR AUTH'}</div>
          <h2>{useBackup ? 'Enter backup code' : 'Verify your identity'}</h2>
          <p className="login-copy">
            {useBackup
              ? 'Enter one of the one-time backup codes you saved when enabling 2FA.'
              : 'Enter the 6-digit code shown in your authenticator app.'}
          </p>
          {error && <div className="login-error">{error}</div>}
          <form onSubmit={submit} className="login-form">
            {useBackup ? (
              <label>Backup code
                <input
                  value={backup}
                  onChange={e => setBackup(e.target.value.toUpperCase())}
                  maxLength={11}
                  autoFocus
                  required
                  autoComplete="one-time-code"
                  placeholder="ABCD-EFGH"
                />
              </label>
            ) : (
              <div className="otp-row" onPaste={onPaste}>
                {cells.map((c, i) => (
                  <input
                    key={i}
                    ref={el => { refs.current[i] = el; }}
                    value={c}
                    inputMode="numeric"
                    maxLength={1}
                    onChange={e => onCell(i, e.target.value)}
                    onKeyDown={e => onKey(i, e)}
                    autoFocus={i === 0}
                    autoComplete="one-time-code"
                    required
                  />
                ))}
              </div>
            )}
            <button className="login-submit" disabled={busy}>
              {busy ? <LoaderCircle className="spin" /> : 'Verify and continue'}
              <ArrowRight size={17} />
            </button>
            <button
              type="button"
              className="text-button"
              onClick={() => {
                setUseBackup(v => !v);
                setError('');
                setBackup('');
                setCells(['', '', '', '', '', '']);
              }}
            >
              {useBackup ? <><ArrowLeft size={14} /> Use authenticator app</> : 'Use a backup code instead'}
            </button>
            <button
              type="button"
              className="text-button"
              onClick={() => {
                sessionStorage.removeItem('pos_pending_2fa');
                nav('/login');
              }}
            >
              <ArrowLeft size={14} /> Back to login
            </button>
          </form>
          <div className="login-secure"><ShieldCheck size={16} /> Your session is protected by the backend API.</div>
        </div>
        <div className="login-copyright">© 2026 INSOPHINIA · Authorized users only</div>
      </section>
    </div>
  );
}
