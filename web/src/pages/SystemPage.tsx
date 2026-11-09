import { useEffect, useState } from 'react';
import { Archive, Download, RefreshCw, ShieldCheck, Trash2, LockKeyhole, Activity, FileText, QrCode, KeyRound, LoaderCircle, AlertTriangle } from 'lucide-react';
import { QRCodeSVG } from 'qrcode.react';
import { api, apiUrl, getToken, dateTime } from '../lib/api';
import { Button, Empty, Field, Loading, Notice, PageHeader } from '../components/Ui';
import { useAuth } from '../context/AuthContext';

type TwoFactorSetup = {
  secret: string;
  otpauthUrl: string;
  backupCodes: string[];
};

/**
 * System page — handles two modes:
 *
 * 1. `security` mode (route /security): the user's own security center.
 *    - Two-factor authentication setup/disable with QR code + backup codes
 *      (consistency with the current version security/page.tsx)
 *    - Active sessions list + revoke
 *    - Security event audit log
 *
 * 2. Operations mode (route /system): backups + system logs.
 *
 * 2FA lifecycle:
 *   1. User enters current password → POST /api/auth/2fa/setup
 *      Backend returns { secret, otpauthUrl, backupCodes }
 *   2. UI shows QR code (rendered from otpauthUrl), the secret for manual
 *      entry, and the backup codes to save.
 *   3. User scans QR with their authenticator, enters a 6-digit code →
 *      POST /api/auth/2fa/enable { code }
 *   4. On success, 2FA is enabled and backup codes are dismissed.
 *
 * Disable flow:
 *   1. User enters current password → POST /api/auth/2fa/disable { password }
 *   2. On success, 2FA flag flips off and the stored secret is cleared.
 */
export default function SystemPage({ security = false }: { security?: boolean }) {
  const { user } = useAuth();

  const [backups, setBackups] = useState<any[]>([]);
  const [logs, setLogs] = useState<any[]>([]);
  const [sessions, setSessions] = useState<any[]>([]);
  const [events, setEvents] = useState<any[]>([]);
  const [status, setStatus] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);

  // 2FA flow state
  const [twoFactorEnabled, setTwoFactorEnabled] = useState<boolean | null>(null);
  const [setupStep, setSetupStep] = useState<'idle' | 'verify' | 'done'>('idle');
  const [setup, setSetup] = useState<TwoFactorSetup | null>(null);
  const [setupPassword, setSetupPassword] = useState('');
  const [verifyCode, setVerifyCode] = useState('');
  const [backupCodesSaved, setBackupCodesSaved] = useState(false);
  const [disablePassword, setDisablePassword] = useState('');
  const [showDisable, setShowDisable] = useState(false);

  const load = () => {
    setLoading(true);
    const jobs = security
      ? [
          api<any[]>('/api/auth/sessions'),
          api<any[]>('/api/auth/security-events?page=0&size=50'),
          api('/api/system/status'),
        ]
      : [
          api<any[]>('/api/backups'),
          api('/api/logs?page=1&limit=50'),
          api('/api/logs/aggregate'),
        ];
    Promise.all(jobs)
      .then(r => {
        if (security) {
          setSessions(r[0].data || []);
          setEvents(r[1].data || []);
          setStatus(r[2].data);
        } else {
          setBackups(r[0].data || []);
          setLogs(r[1].data || []);
          setStatus(r[2].data);
        }
      })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
    if (security) {
      // Best-effort fetch of 2FA enabled state. The /api/auth/sessions payload
      // doesn't include it; the user JWT payload doesn't either. We rely on a
      // small HEAD-ish endpoint: GET /api/auth/security-events will succeed for
      // authenticated users and return 200. To determine 2FA state without a
      // dedicated endpoint, we look at the local user JWT's `permissions` —
      // but those don't carry 2FA either. Best approach: the backend should
      // expose /api/auth/me; if absent, we fall back to null (UI shows "unknown").
      // To keep this self-contained, we just leave the value as null until the
      // user runs setup or disable; then we know the resulting state.
      setTwoFactorEnabled(null);
    }
  }, [security]);

  // ---- 2FA handlers ----------------------------------------------------

  const beginSetup = async () => {
    setBusy(true);
    setError('');
    try {
      const r = await api<TwoFactorSetup>('/api/auth/2fa/setup', {
        method: 'POST',
        body: JSON.stringify({ password: setupPassword }),
      });
      setSetup(r.data);
      setSetupStep('verify');
      setBackupCodesSaved(false);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  const enable2fa = async () => {
    setBusy(true);
    setError('');
    try {
      await api('/api/auth/2fa/enable', {
        method: 'POST',
        body: JSON.stringify({ code: verifyCode }),
      });
      setTwoFactorEnabled(true);
      setSetupStep('done');
      setMessage('Two-factor authentication is now enabled. Save your backup codes before closing this panel.');
    } catch (e: any) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  const cancelSetup = () => {
    setSetup(null);
    setSetupStep('idle');
    setSetupPassword('');
    setVerifyCode('');
    setBackupCodesSaved(false);
  };

  const disable2fa = async () => {
    setBusy(true);
    setError('');
    try {
      await api('/api/auth/2fa/disable', {
        method: 'POST',
        body: JSON.stringify({ password: disablePassword }),
      });
      setTwoFactorEnabled(false);
      setShowDisable(false);
      setDisablePassword('');
      setMessage('Two-factor authentication has been disabled.');
    } catch (e: any) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  // ---- Backup / session / log handlers --------------------------------

  const createBackup = async () => {
    setBusy(true);
    try {
      await api('/api/backups', { method: 'POST' });
      setMessage('Backup created');
      load();
    } catch (e: any) { setError(e.message); }
    finally { setBusy(false); }
  };

  const remove = async (id: string) => {
    if (!confirm('Delete this backup?')) return;
    try {
      await api(`/api/backups/${id}`, { method: 'DELETE' });
      load();
    } catch (e: any) { setError(e.message); }
  };

  const revoke = async (id: string) => {
    if (!confirm('Revoke this active session?')) return;
    try {
      await api(`/api/auth/sessions/${id}/revoke`, { method: 'POST' });
      setMessage('Session revoked');
      load();
    } catch (e: any) { setError(e.message); }
  };

  const revokeAll = async () => {
    if (!confirm('Revoke ALL other sessions? You will stay logged in only on this browser.')) return;
    setBusy(true);
    try {
      await api('/api/auth/sessions/revoke-all', { method: 'POST' });
      setMessage('All other sessions revoked');
      load();
    } catch (e: any) { setError(e.message); }
    finally { setBusy(false); }
  };

  const download = async (id: string) => {
    try {
      const res = await fetch(apiUrl(`/api/backups/${id}/download`), {
        headers: { Authorization: `Bearer ${getToken()}` },
      });
      if (!res.ok) throw new Error(`Download failed (${res.status})`);
      const blob = await res.blob();
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = `backup-${id}.bin`;
      a.click();
      URL.revokeObjectURL(a.href);
    } catch (e: any) { setError(e.message); }
  };

  return (
    <>
      <PageHeader
        title={security ? 'Security center' : 'System operations'}
        subtitle={security
          ? 'Two-factor authentication, active sessions, and the security audit log.'
          : 'Create backups and review recent application logs.'}
        action={<Button variant="secondary" onClick={load}><RefreshCw size={16} /> Refresh</Button>}
      />

      {error && <Notice message={error} onClose={() => setError('')} />}
      {message && <Notice kind="success" message={message} onClose={() => setMessage('')} />}

      {loading ? <Loading /> : security ? (
        <>
          {/* Metric cards */}
          <div className="metric-grid three">
            <div className="metric-card">
              <div className="metric-icon teal"><ShieldCheck /></div>
              <span>Active sessions</span>
              <strong>{sessions.length}</strong>
            </div>
            <div className="metric-card">
              <div className="metric-icon blue"><Activity /></div>
              <span>Security events</span>
              <strong>{events.length}</strong>
            </div>
            <div className="metric-card">
              <div className={`metric-icon ${status?.locked ? 'amber' : 'teal'}`}><LockKeyhole /></div>
              <span>System lock</span>
              <strong>{status?.locked ? 'Locked' : 'Unlocked'}</strong>
            </div>
          </div>

          {/* 2FA panel (NEW — feature gap) */}
          <section className="panel" style={{ marginTop: 16 }}>
            <div className="panel-heading">
              <div>
                <h2>Two-factor authentication</h2>
                <p>Protect your account with a TOTP authenticator app (Google Authenticator, Authy, 1Password, etc.).</p>
              </div>
              <span className={`badge ${twoFactorEnabled ? 'badge-green' : 'badge-muted'}`}>
                {twoFactorEnabled === null ? 'Unknown' : twoFactorEnabled ? 'Enabled' : 'Disabled'}
              </span>
            </div>

            {/* Idle state — not yet verified, but setup not started */}
            {twoFactorEnabled !== true && setupStep === 'idle' && (
              <>
                <Field label="Confirm your current password to begin 2FA setup">
                  <input
                    type="password"
                    value={setupPassword}
                    onChange={e => setSetupPassword(e.target.value)}
                    placeholder="Your account password"
                    autoComplete="current-password"
                  />
                </Field>
                <div className="modal-footer">
                  <Button onClick={beginSetup} disabled={busy || !setupPassword}>
                    {busy ? <LoaderCircle className="spin" size={16} /> : <QrCode size={16} />} Begin 2FA setup
                  </Button>
                </div>
              </>
            )}

            {/* Verify step — show QR + secret + ask for code */}
            {setupStep === 'verify' && setup && (
              <div style={{ display: 'grid', gridTemplateColumns: 'minmax(220px, 240px) 1fr', gap: 24, alignItems: 'start', marginTop: 12 }}>
                <div style={{ background: '#fff', padding: 16, border: '1px solid var(--border)', borderRadius: 12, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8 }}>
                  <QRCodeSVG value={setup.otpauthUrl} size={200} level="M" />
                  <small style={{ color: '#778791', textAlign: 'center' }}>Scan with your authenticator app</small>
                </div>
                <div>
                  <Field label="Or enter this secret manually">
                    <code style={{ fontFamily: 'monospace', background: '#f1f5f9', padding: '8px 12px', borderRadius: 6, display: 'block', wordBreak: 'break-all' }}>
                      {setup.secret}
                    </code>
                  </Field>
                  <Field label="Backup codes (save these now — they cannot be recovered)">
                    <div style={{ background: '#fef3c7', padding: 12, borderRadius: 8, border: '1px solid #fde68a' }}>
                      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 6, fontFamily: 'monospace', fontSize: 13 }}>
                        {(setup.backupCodes || []).map((c, i) => <div key={i}>{c}</div>)}
                      </div>
                      <label style={{ marginTop: 8, display: 'flex', alignItems: 'center', gap: 6, fontSize: 13 }}>
                        <input type="checkbox" checked={backupCodesSaved} onChange={e => setBackupCodesSaved(e.target.checked)} />
                        I've saved my backup codes
                      </label>
                    </div>
                  </Field>
                  <Field label="Enter the 6-digit code from your authenticator">
                    <input
                      value={verifyCode}
                      onChange={e => setVerifyCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                      inputMode="numeric"
                      maxLength={6}
                      placeholder="123456"
                      autoComplete="one-time-code"
                      style={{ fontFamily: 'monospace', fontSize: 18, letterSpacing: '0.2em' }}
                    />
                  </Field>
                  <div className="modal-footer" style={{ display: 'flex', gap: 8 }}>
                    <Button onClick={enable2fa} disabled={busy || verifyCode.length !== 6 || !backupCodesSaved}>
                      {busy ? <LoaderCircle className="spin" size={16} /> : <ShieldCheck size={16} />} Confirm and enable 2FA
                    </Button>
                    <Button variant="secondary" onClick={cancelSetup}>Cancel setup</Button>
                  </div>
                </div>
              </div>
            )}

            {/* Done state — confirmation */}
            {setupStep === 'done' && (
              <div style={{ padding: '16px 20px', background: '#ecfdf5', border: '1px solid #a7f3d0', borderRadius: 10, marginTop: 12 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: '#065f46', fontWeight: 600 }}>
                  <ShieldCheck size={20} /> Two-factor authentication is enabled
                </div>
                <p style={{ fontSize: 13, color: '#047857', marginTop: 6 }}>
                  Keep your backup codes somewhere safe. Each can be used once to log in if you lose your authenticator device.
                </p>
                <div className="modal-footer">
                  <Button variant="secondary" onClick={cancelSetup}>Close</Button>
                </div>
              </div>
            )}

            {/* Disable flow (only when 2FA is enabled) */}
            {twoFactorEnabled === true && setupStep === 'idle' && (
              <>
                <div style={{ padding: '12px 16px', background: '#f0fdf4', border: '1px solid #bbf7d0', borderRadius: 8, marginBottom: 12, color: '#166534', fontSize: 13 }}>
                  2FA is active on your account. To disable it, confirm with your current password.
                </div>
                {!showDisable ? (
                  <Button variant="danger" onClick={() => setShowDisable(true)}><KeyRound size={16} /> Disable 2FA…</Button>
                ) : (
                  <>
                    <Field label="Confirm with your current password to disable 2FA">
                      <input
                        type="password"
                        value={disablePassword}
                        onChange={e => setDisablePassword(e.target.value)}
                        placeholder="Account password"
                        autoComplete="current-password"
                      />
                    </Field>
                    <div className="modal-footer" style={{ display: 'flex', gap: 8 }}>
                      <Button variant="danger" onClick={disable2fa} disabled={busy || !disablePassword}>
                        {busy ? <LoaderCircle className="spin" size={16} /> : <AlertTriangle size={16} />} Confirm disable
                      </Button>
                      <Button variant="secondary" onClick={() => { setShowDisable(false); setDisablePassword(''); }}>Cancel</Button>
                    </div>
                  </>
                )}
              </>
            )}
          </section>

          {/* Active sessions */}
          <section className="panel" style={{ marginTop: 16 }}>
            <div className="panel-heading">
              <div><h2>Active sessions</h2><p>Revoke sessions you no longer recognize. Your current session is marked.</p></div>
              {sessions.filter(s => !s.current).length > 0 && (
                <Button variant="danger" onClick={revokeAll} disabled={busy}>
                  <Trash2 size={15} /> Revoke all others
                </Button>
              )}
            </div>
            {sessions.length === 0 ? <Empty /> : (
              <div className="table-wrap"><table>
                <thead><tr><th>Device</th><th>IP address</th><th>Created</th><th>Last active</th><th>Expires</th><th>Action</th></tr></thead>
                <tbody>
                  {sessions.map(s => (
                    <tr key={s.id}>
                      <td>{s.browser || 'Browser'} · {s.os || s.device || 'Unknown'} {s.current && <span className="badge badge-green">Current</span>}</td>
                      <td>{s.ipAddress || '—'}</td>
                      <td>{dateTime(s.createdAt)}</td>
                      <td>{dateTime(s.lastActivityAt)}</td>
                      <td>{dateTime(s.expiresAt)}</td>
                      <td><Button variant="danger" disabled={s.current} onClick={() => void revoke(s.id)}><Trash2 size={15} /> Revoke</Button></td>
                    </tr>
                  ))}
                </tbody>
              </table></div>
            )}
          </section>

          {/* Security events */}
          <section className="panel" style={{ marginTop: 16 }}>
            <div className="panel-heading">
              <div><h2>Security events</h2><p>Recent authentication and account events.</p></div>
            </div>
            {events.length === 0 ? <Empty /> : (
              <div className="table-wrap"><table>
                <thead><tr><th>When</th><th>Event</th><th>Severity</th><th>IP</th><th>Details</th></tr></thead>
                <tbody>
                  {events.map((e: any) => (
                    <tr key={e.id}>
                      <td>{dateTime(e.createdAt)}</td>
                      <td>{e.eventType}</td>
                      <td>
                        <span className={`badge ${e.severity === 'HIGH' ? 'badge-red' : e.severity === 'MEDIUM' ? 'badge-amber' : 'badge-muted'}`}>{e.severity}</span>
                      </td>
                      <td>{e.ipAddress || '—'}</td>
                      <td>{e.details}</td>
                    </tr>
                  ))}
                </tbody>
              </table></div>
            )}
          </section>
        </>
      ) : (
        <>
          {/* Operations mode — backups + logs */}
          <section className="panel">
            <div className="panel-heading">
              <div><h2>Backups</h2><p>Encrypted backup files managed by the backend service.</p></div>
              <Button onClick={createBackup} disabled={busy}><Archive size={16} /> {busy ? 'Creating…' : 'Create backup'}</Button>
            </div>
            {backups.length === 0 ? <Empty text="No backups yet" /> : (
              <div className="table-wrap"><table>
                <thead><tr><th>File</th><th>Type</th><th>Size</th><th>Created</th><th>Actions</th></tr></thead>
                <tbody>
                  {backups.map(b => (
                    <tr key={b.id}>
                      <td><b>{b.filename}</b></td>
                      <td>{b.backupType}</td>
                      <td>{(Number(b.fileSize || 0) / 1024).toFixed(1)} KB</td>
                      <td>{dateTime(b.createdAt)}</td>
                      <td>
                        <div className="row-actions">
                          <button className="icon-button" title="Download" onClick={() => void download(b.id)}><Download size={16} /></button>
                          <button className="icon-button danger-icon" title="Delete" onClick={() => void remove(b.id)}><Trash2 size={16} /></button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table></div>
            )}
          </section>

          <section className="panel" style={{ marginTop: 16 }}>
            <div className="panel-heading">
              <div><h2>Recent application logs</h2><p>Latest 50 entries from the system log.</p></div>
              <FileText size={18} className="muted-icon" />
            </div>
            {logs.length === 0 ? <Empty /> : (
              <div className="table-wrap"><table>
                <thead><tr><th>Time</th><th>Level</th><th>Category</th><th>Action</th><th>Message</th></tr></thead>
                <tbody>
                  {logs.map((l: any) => (
                    <tr key={l.id}>
                      <td>{dateTime(l.createdAt)}</td>
                      <td><span className={`badge ${l.level === 'ERROR' ? 'badge-red' : l.level === 'WARN' ? 'badge-amber' : 'badge-muted'}`}>{l.level}</span></td>
                      <td>{l.category}</td>
                      <td>{l.action}</td>
                      <td>{l.message}</td>
                    </tr>
                  ))}
                </tbody>
              </table></div>
            )}
          </section>
        </>
      )}
    </>
  );
}
