import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ArrowRight, CheckCircle2, AlertTriangle, LoaderCircle, MailCheck } from 'lucide-react';

type State = 'loading' | 'success' | 'expired' | 'not_found' | 'error';

/**
 * Public landing page for the customer email-verification flow.
 *
 * Invoked when a customer clicks the verification link in their email. The link
 * is `<APP_URL>/verify-email?token=<hex>`. The page calls the public
 * `GET /api/customers/verify-email?token=…` endpoint, then renders a
 * success / failure card.
 *
 * The route is registered OUTSIDE the auth Guard so unauthenticated customers
 * can click through from their inbox.
 */
export default function VerifyEmail() {
  const [sp] = useSearchParams();
  const nav = useNavigate();
  const [state, setState] = useState<State>('loading');
  const [message, setMessage] = useState('');
  const token = sp.get('token') || '';

  useEffect(() => {
    if (!token) {
      setState('not_found');
      setMessage('No verification token was provided in the link.');
      return;
    }
    const ctrl = new AbortController();
    (async () => {
      try {
        const res = await fetch(`/api/customers/verify-email?token=${encodeURIComponent(token)}`, {
          method: 'GET',
          headers: { 'Accept': 'application/json' },
          signal: ctrl.signal,
        });
        const data = await res.json().catch(() => ({}));
        if (res.ok && data.success !== false) {
          setState('success');
          setMessage(data.message || 'Your email address has been verified. Thank you.');
        } else if (res.status === 404) {
          setState('not_found');
          setMessage(data.message || 'This verification link is invalid or has already been used.');
        } else if (res.status === 400) {
          setState('expired');
          setMessage(data.message || 'This verification link has expired. Please request a new one.');
        } else {
          setState('error');
          setMessage(data.message || `Verification failed (${res.status}).`);
        }
      } catch (err: any) {
        setState('error');
        setMessage(err.message || 'Network error while contacting the server.');
      }
    })();
    return () => ctrl.abort();
  }, [token]);

  return (
    <div className="login-screen">
      <div className="login-glow glow-one" />
      <section className="login-form-panel" style={{ maxWidth: 520 }}>
        <div className="login-form-wrap">
          <div className="form-mark">
            {state === 'success' ? <CheckCircle2 size={23} /> :
             state === 'loading' ? <LoaderCircle size={23} className="spin" /> :
             <AlertTriangle size={23} />}
          </div>
          <div className="eyebrow">EMAIL VERIFICATION</div>
          <h2>
            {state === 'success' ? 'Email verified' :
             state === 'loading' ? 'Verifying…' :
             state === 'expired' ? 'Link expired' :
             state === 'not_found' ? 'Invalid link' : 'Verification failed'}
          </h2>
          <p className="login-copy">{message}</p>
          {state === 'success' && (
            <div className="login-feature-grid" style={{ marginTop: 24 }}>
              <div><MailCheck /><b>Account confirmed</b><span>You can now receive receipts and offers</span></div>
            </div>
          )}
          <button className="login-submit" onClick={() => nav('/login')}>
            Continue to sign in <ArrowRight size={17} />
          </button>
        </div>
        <div className="login-copyright">© 2026 INSOPHINIA · Customer email verification</div>
      </section>
    </div>
  );
}
