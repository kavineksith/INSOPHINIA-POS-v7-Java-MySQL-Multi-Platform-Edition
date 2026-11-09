import { useEffect, useState } from 'react';
import { Mail, Save, Send, Settings2, ShieldCheck, Smartphone, RefreshCw, LockKeyhole, Unlock, Power, Activity, Percent, Monitor, MonitorSmartphone, ExternalLink } from 'lucide-react';
import { api } from '../lib/api';
import { Button, Field, Loading, Notice, PageHeader } from '../components/Ui';
import { useAuth } from '../context/AuthContext';

/**
 * Settings — store configuration with explicit panels for the most common
 * operator tasks (consistency with the current version settings/page.tsx).
 *
 * Panels:
 *   1. Tax configuration (tax_enabled, vat_rate, sscl_rate)
 *   2. Loyalty program (loyalty_point_value)
 *   3. Shop identity (shop_name, shop_address, shop_phone)
 *   4. Email delivery (email_enabled) + test email
 *   5. Terminal profile (per-browser device profile)
 *   6. Master system control (deadman switch status + lock/unlock)
 *
 * All settings rows remain available under the "All settings" panel at the
 * bottom so admins can still edit any non-canonical key.
 *
 * RBAC: only `manage:settings` (admin+ master_admin) can edit. Cashier/
 * supervisor would not reach this route because the sidebar hides it.
 */
export default function Settings() {
  const { user } = useAuth();
  const role = user?.role?.toLowerCase() || '';
  const isMasterAdmin = role === 'master_admin';

  const [rows, setRows] = useState<any[]>([]);
  const [values, setValues] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [email, setEmail] = useState('');

  // Master system state (deadman switch + lock)
  const [systemStatus, setSystemStatus] = useState<any>(null);
  const [lockReason, setLockReason] = useState('');

  const [device, setDevice] = useState({
    deviceIdentifier: import.meta.env.VITE_TERMINAL_ID || 'web-terminal',
    name: 'Web POS terminal',
    defaultPrinter: '',
    defaultScanner: '',
    active: true,
  });

  const load = () => {
    setLoading(true);
    Promise.all([
      api<any[]>('/api/settings').catch(() => ({ data: [] })),
      api('/api/settings/device').catch(() => ({ data: null })),
      api('/api/system/status').catch(() => ({ data: null })),
    ])
      .then(([settingsRes, deviceRes, statusRes]) => {
        const settingsRows = (settingsRes as any).data || [];
        setRows(settingsRows);
        const v: any = {};
        settingsRows.forEach((x: any) => v[x.key] = x.value ?? '');
        setValues(v);

        const d = (deviceRes as any).data;
        if (Array.isArray(d) && d.length > 0) {
          const match = d.find((x: any) => x.deviceIdentifier === (import.meta.env.VITE_TERMINAL_ID || 'web-terminal'));
          if (match) setDevice(match);
        }

        setSystemStatus((statusRes as any).data);
      })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  const saveAll = async () => {
    setBusy(true);
    setError('');
    try {
      await api('/api/settings', { method: 'PUT', body: JSON.stringify({ settings: values }) });
      setMessage('Settings saved');
    } catch (e: any) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  const testEmail = async () => {
    setBusy(true);
    try {
      await api('/api/settings/test-email', { method: 'POST', body: JSON.stringify({ to: email }) });
      setMessage('Test email sent successfully');
    } catch (e: any) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  const saveDevice = async () => {
    try {
      await api('/api/settings/device', { method: 'PUT', body: JSON.stringify(device) });
      setMessage('Device profile saved');
    } catch (e: any) {
      setError(e.message);
    }
  };

  // Master system control handlers (master_admin only)
  const lockSystem = async () => {
    if (!isMasterAdmin) return;
    setBusy(true);
    try {
      await api('/api/system/lock', { method: 'POST', body: JSON.stringify({ locked: true, reason: lockReason || 'Manual lock' }) });
      setMessage('System locked');
      setLockReason('');
      load();
    } catch (e: any) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  const unlockSystem = async () => {
    if (!isMasterAdmin) return;
    setBusy(true);
    try {
      await api('/api/system/lock', { method: 'POST', body: JSON.stringify({ locked: false, reason: '' }) });
      setMessage('System unlocked');
      load();
    } catch (e: any) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  const checkin = async () => {
    if (!isMasterAdmin) return;
    setBusy(true);
    try {
      await api('/api/system/checkin', { method: 'POST', body: JSON.stringify({ deviceIdentifier: 'web-terminal' }) });
      setMessage('Check-in recorded. Deadman timer reset.');
      load();
    } catch (e: any) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  // Helper to set a value for a known settings key
  const setKey = (key: string, value: string) => setValues({ ...values, [key]: value });

  return (
    <>
      <PageHeader
        title="Settings"
        subtitle="Tax, loyalty, shop identity, email delivery, terminal profile, and master system control."
        action={<Button variant="secondary" onClick={load}><RefreshCw size={16} /> Refresh</Button>}
      />

      {error && <Notice message={error} onClose={() => setError('')} />}
      {message && <Notice kind="success" message={message} onClose={() => setMessage('')} />}

      {loading ? <Loading /> : (
        <div className="settings-grid">
          {/* --- Tax panel (feature gap #4) --- */}
          <section className="panel settings-panel">
            <div className="panel-heading">
              <div className="settings-heading-icon"><Percent /></div>
              <div><h2>Tax configuration</h2><p>VAT (Value Added Tax) and SSCL (Social Security Contribution Levy) rates. Toggle both via the single tax_enabled key.</p></div>
            </div>
            <Field label="Enable tax collection">
              <select value={values['tax_enabled'] || 'true'} onChange={e => setKey('tax_enabled', e.target.value)}>
                <option value="true">Tax enabled</option>
                <option value="false">Tax disabled (no VAT or SSCL on bills)</option>
              </select>
            </Field>
            <Field label="VAT rate (%)">
              <input type="number" step="0.1" min="0" max="100" value={values['vat_rate'] ?? '18'} onChange={e => setKey('vat_rate', e.target.value)} />
            </Field>
            <Field label="SSCL rate (%)">
              <input type="number" step="0.1" min="0" max="100" value={values['sscl_rate'] ?? '2.5'} onChange={e => setKey('sscl_rate', e.target.value)} />
            </Field>
            <div className="modal-footer"><Button onClick={saveAll} disabled={busy}><Save size={16} /> Save tax settings</Button></div>
          </section>

          {/* --- Loyalty program --- */}
          <section className="panel settings-panel">
            <div className="panel-heading">
              <div className="settings-heading-icon"><ShieldCheck /></div>
              <div><h2>Loyalty program</h2><p>Points-per-currency value. Customers earn 1 point per unit of currency spent.</p></div>
            </div>
            <Field label="Loyalty point value (currency units per 1 point)">
              <input type="number" min="1" value={values['loyalty_point_value'] ?? '100'} onChange={e => setKey('loyalty_point_value', e.target.value)} />
            </Field>
            <div className="modal-footer"><Button onClick={saveAll} disabled={busy}><Save size={16} /> Save loyalty</Button></div>
          </section>

          {/* --- Shop identity --- */}
          <section className="panel settings-panel">
            <div className="panel-heading">
              <div className="settings-heading-icon"><Settings2 /></div>
              <div><h2>Shop identity</h2><p>Appears on printed receipts and email receipts.</p></div>
            </div>
            <Field label="Shop name"><input value={values['shop_name'] ?? ''} onChange={e => setKey('shop_name', e.target.value)} /></Field>
            <Field label="Shop address"><input value={values['shop_address'] ?? ''} onChange={e => setKey('shop_address', e.target.value)} /></Field>
            <Field label="Shop phone"><input value={values['shop_phone'] ?? ''} onChange={e => setKey('shop_phone', e.target.value)} /></Field>
            <div className="modal-footer"><Button onClick={saveAll} disabled={busy}><Save size={16} /> Save shop identity</Button></div>
          </section>

          {/* --- Email delivery --- */}
          <section className="panel settings-panel">
            <div className="panel-heading">
              <div className="settings-heading-icon"><Mail /></div>
              <div><h2>Email delivery</h2><p>Toggle email sending and send a test receipt. SMTP host/port/user/password live only in backend env (never in browser).</p></div>
            </div>
            <Field label="Email enabled">
              <select value={values['email_enabled'] || 'false'} onChange={e => setKey('email_enabled', e.target.value)}>
                <option value="true">Email sending enabled</option>
                <option value="false">Email sending disabled</option>
              </select>
            </Field>
            <Field label="Send test email to"><input type="email" value={email} onChange={e => setEmail(e.target.value)} placeholder="you@example.com" /></Field>
            <div className="modal-footer">
              <Button onClick={saveAll} disabled={busy}><Save size={16} /> Save email setting</Button>
              <Button variant="secondary" onClick={testEmail} disabled={busy || !email}><Send size={16} /> Send test</Button>
            </div>
          </section>

          {/* --- Terminal profile --- */}
          <section className="panel settings-panel">
            <div className="panel-heading">
              <div className="settings-heading-icon"><Smartphone /></div>
              <div><h2>Terminal profile</h2><p>Per-browser device profile — set defaults for this terminal only.</p></div>
            </div>
            <Field label="Terminal identifier"><input value={device.deviceIdentifier} onChange={e => setDevice({ ...device, deviceIdentifier: e.target.value })} /></Field>
            <Field label="Display name"><input value={device.name || ''} onChange={e => setDevice({ ...device, name: e.target.value })} /></Field>
            <Field label="Default printer"><input value={device.defaultPrinter || ''} onChange={e => setDevice({ ...device, defaultPrinter: e.target.value })} /></Field>
            <Field label="Default scanner"><input value={device.defaultScanner || ''} onChange={e => setDevice({ ...device, defaultScanner: e.target.value })} /></Field>
            <Button onClick={saveDevice}><Save size={16} /> Save terminal</Button>
          </section>

          {/* --- Master system control (deadman switch + lock) --- */}
          {isMasterAdmin && systemStatus && (
            <section className="panel settings-panel">
              <div className="panel-heading">
                <div className="settings-heading-icon"><Power /></div>
                <div><h2>Master system control</h2><p>Deadman switch & system lock. Only the master_admin role can use these controls.</p></div>
              </div>
              <div className="metric-grid three" style={{ gridTemplateColumns: 'repeat(3, 1fr)', gap: 8, marginBottom: 12 }}>
                <div className="metric-card">
                  <div className={`metric-icon ${systemStatus.locked ? 'red' : 'teal'}`}><LockKeyhole /></div>
                  <span>Lock state</span>
                  <strong>{systemStatus.locked ? 'LOCKED' : 'Unlocked'}</strong>
                </div>
                <div className="metric-card">
                  <div className="metric-icon blue"><Activity /></div>
                  <span>Last check-in</span>
                  <strong>{systemStatus.lastCheckinAt ? new Date(systemStatus.lastCheckinAt).toLocaleString() : '—'}</strong>
                </div>
                <div className="metric-card">
                  <div className="metric-icon amber"><ShieldCheck /></div>
                  <span>Deadman window</span>
                  <strong>{systemStatus.deadmanDays || 72} days</strong>
                </div>
              </div>
              {systemStatus.locked && systemStatus.lockReason && (
                <div style={{ padding: '8px 12px', background: '#fef2f2', color: '#991b1b', borderRadius: 8, fontSize: 13, marginBottom: 12 }}>
                  Lock reason: {systemStatus.lockReason}
                  {systemStatus.lockedAt && ` · since ${new Date(systemStatus.lockedAt).toLocaleString()}`}
                </div>
              )}
              <Field label="Lock reason (optional, for audit log)">
                <input value={lockReason} onChange={e => setLockReason(e.target.value)} placeholder="Why are you locking the system?" />
              </Field>
              <div className="modal-footer" style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                {!systemStatus.locked ? (
                  <Button variant="danger" onClick={lockSystem} disabled={busy}><LockKeyhole size={16} /> Lock system</Button>
                ) : (
                  <Button onClick={unlockSystem} disabled={busy}><Unlock size={16} /> Unlock system</Button>
                )}
                <Button variant="secondary" onClick={checkin} disabled={busy}><RefreshCw size={16} /> Check-in (reset deadman)</Button>
              </div>
            </section>
          )}

          {/* --- Customer display / dual-screen configuration (feature gap closed) --- */}
          <section className="panel settings-panel">
            <div className="panel-heading">
              <div className="settings-heading-icon"><Monitor /></div>
              <div><h2>Customer display · dual-screen</h2><p>Pair a second monitor to show the active cart to customers during checkout. Multi-terminal pairing: multiple POS terminals can pair to the same channel.</p></div>
            </div>

            <DualScreenConfig
              channel={device.deviceIdentifier}
              onChannelChange={(ch) => setDevice({ ...device, deviceIdentifier: ch })}
              activeChannelsUrl="/api/sync/customer-display/channels"
            />

            <Field label="Display pairing channel (16+ chars; share with the terminal(s) that should mirror their cart to this display)">
              <input
                value={device.deviceIdentifier || ''}
                onChange={e => setDevice({ ...device, deviceIdentifier: e.target.value })}
                placeholder="e.g. front-counter-display-9k2x"
                minLength={16}
              />
            </Field>
            <Field label="Customer display mode (settings key: customer_display_mode)">
              <select value={values['customer_display_mode'] || 'cart'} onChange={e => setKey('customer_display_mode', e.target.value)}>
                <option value="cart">Live cart (default)</option>
                <option value="bill">Final bill (paid + balance)</option>
                <option value="idle">Idle / shop branding</option>
              </select>
            </Field>
            <Field label="Show shop logo on customer display (settings key: customer_display_logo)">
              <select value={values['customer_display_logo'] || 'true'} onChange={e => setKey('customer_display_logo', e.target.value)}>
                <option value="true">Show shop logo</option>
                <option value="false">Hide logo</option>
              </select>
            </Field>
            <div className="modal-footer" style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              <Button onClick={saveAll} disabled={busy}><Save size={16} /> Save display config</Button>
              <a
                href="/customer-display"
                target="_blank"
                rel="noopener noreferrer"
                className="btn btn-secondary"
                style={{ display: 'inline-flex', alignItems: 'center', gap: 6, textDecoration: 'none' }}
              >
                <ExternalLink size={16} /> Open display window
              </a>
            </div>
          </section>

          {/* --- All settings (full key/value list) --- */}
          <section className="panel settings-panel">
            <div className="panel-heading">
              <div className="settings-heading-icon"><Settings2 /></div>
              <div><h2>All settings</h2><p>Full key/value list returned by the API. Editing here overrides the focused panels above.</p></div>
            </div>
            {rows.length === 0 ? (
              <div className="empty-state">No editable settings were returned.</div>
            ) : (
              <div className="settings-fields">
                {rows.map(r => (
                  <Field key={r.key} label={`${r.key}${r.description ? ` · ${r.description}` : ''}`}>
                    {r.type === 'boolean' ? (
                      <select value={values[r.key] || 'false'} onChange={e => setKey(r.key, e.target.value)}>
                        <option value="true">Enabled</option>
                        <option value="false">Disabled</option>
                      </select>
                    ) : (
                      <input value={values[r.key] ?? ''} onChange={e => setKey(r.key, e.target.value)} />
                    )}
                  </Field>
                ))}
              </div>
            )}
            <div className="modal-footer"><Button onClick={saveAll} disabled={busy}><Save size={16} /> Save all</Button></div>
          </section>

          <div className="security-note">
            <ShieldCheck />
            <div>
              <b>Security boundary</b>
              <p>Never place JWT signing keys, SMTP passwords, or database credentials in Vite environment variables. Browser environment values are public.</p>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

/**
 * Dual-screen configuration component for the customer display panel.
 *
 * Uses the Window Management API (chrome.windows, screen.isExtended, and the
 * `getScreenDetails()` method on Chromium 100+) when available to:
 *   - Detect whether the current device has multiple screens attached.
 *   - List each screen's label + dimensions so the operator can pick where to
 *     pop the customer display window.
 *   - Open the customer-display URL (`/customer-display`) on the chosen
 *     screen via `window.open()` + a feature string with explicit left/top
 *     coordinates.
 *
 * On browsers that don't support the Window Management API (Firefox, Safari,
 * older Chromium), the panel gracefully degrades to "manually drag the
 * display window to the second monitor" instructions.
 *
 * Multi-terminal pairing: the channel string entered here is what other POS
 * terminals also pair with. Two terminals pairing to the same channel both
 * receive each other's cart broadcasts — i.e. terminal A's cart will appear
 * on terminal B's customer display, and vice versa. This is the intended
 * behaviour for shops with multiple checkouts sharing one customer-facing
 * display.
 */
function DualScreenConfig({ channel, onChannelChange, activeChannelsUrl }: {
  channel?: string;
  onChannelChange: (ch: string) => void;
  activeChannelsUrl?: string;
}) {
  const [screenDetails, setScreenDetails] = useState<any>(null);
  const [windowApiSupported, setWindowApiSupported] = useState(false);
  const [multiScreen, setMultiScreen] = useState(false);
  const [popupWindow, setPopupWindow] = useState<Window | null>(null);
  const [error, setError] = useState('');
  const [activeChannels, setActiveChannels] = useState<any>(null);

  useEffect(() => {
    // Detect support: Chrome 100+ exposes `window.getScreenDetails`, Safari
    // 16.4+ supports `screen.isExtended` (read-only, no enumeration).
    const hasGetScreenDetails = typeof (window as any).getScreenDetails === 'function';
    setWindowApiSupported(hasGetScreenDetails);
    if (typeof screen !== 'undefined' && 'isExtended' in screen) {
      // @ts-ignore - isExtended is part of the Multi-Screen Window Placement API
      setMultiScreen(!!screen.isExtended);
    }

    // Fetch the admin overview of active channels + paired client counts.
    // Non-fatal — if the user lacks manage:settings, the panel still works
    // for screen-detection purposes.
    if (activeChannelsUrl) {
      api<any>(activeChannelsUrl).then(r => setActiveChannels(r.data)).catch(() => { /* non-fatal */ });
    }
  }, [activeChannelsUrl]);

  const requestPermission = async () => {
    setError('');
    try {
      // @ts-ignore - Window Management API
      const details: any = await (window as any).getScreenDetails?.();
      if (details) {
        setScreenDetails(details);
        setMultiScreen(true);
      }
    } catch (e: any) {
      setError(`Permission denied: ${e?.message || e}. The customer display can still be opened in a regular popup window — drag it to the second monitor manually.`);
    }
  };

  const openOnScreen = (screen: any, screenIdx: number) => {
    const displayUrl = `${window.location.origin}/customer-display`;
    const features = [
      `popup=true`,
      `width=${Math.min(screen.width || 1024, 1280)}`,
      `height=${Math.min(screen.height || 720, 800)}`,
      `left=${screen.left || 0}`,
      `top=${screen.top || 0}`,
      `noopener`,
    ].join(',');
    const w = window.open(displayUrl, `insophinia-customer-display-${screenIdx}`, features);
    if (w) {
      setPopupWindow(w);
      // Focus the popup so the OS raises it on top of the second monitor.
      setTimeout(() => w.focus(), 100);
    } else {
      setError('Popup was blocked. Allow pop-ups for this site, then try again.');
    }
  };

  const openFallbackPopup = () => {
    const displayUrl = `${window.location.origin}/customer-display`;
    const w = window.open(displayUrl, 'insophinia-customer-display',
      'popup=true,width=1280,height=800,left=1920,top=0,noopener');
    if (w) {
      setPopupWindow(w);
    } else {
      setError('Popup was blocked. Allow pop-ups for this site, then try again.');
    }
  };

  return (
    <div style={{ background: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: 8, padding: 16, marginBottom: 12 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
        <MonitorSmartphone size={18} style={{ color: '#0d9488' }} />
        <b>Dual-screen setup</b>
        {multiScreen && <span className="badge badge-green">Multi-screen detected</span>}
        {!windowApiSupported && <span className="badge badge-muted">Window Management API unavailable</span>}
      </div>

      {error && <div style={{ color: '#991b1b', fontSize: 13, marginBottom: 8 }}>{error}</div>}

      {!windowApiSupported && (
        <p style={{ fontSize: 13, color: '#475569' }}>
          Your browser doesn't expose the Window Management API. Open the display popup below and drag it to your
          second monitor. For automatic placement, use Chrome 100+ or Edge 100+.
        </p>
      )}

      {windowApiSupported && !screenDetails && (
        <>
          <p style={{ fontSize: 13, color: '#475569', marginBottom: 8 }}>
            Click below to request screen enumeration permission. This lets the browser place the customer
            display window on the correct monitor automatically.
          </p>
          <Button onClick={requestPermission}><Monitor size={16} /> Detect displays</Button>
        </>
      )}

      {screenDetails && (
        <div>
          <p style={{ fontSize: 13, color: '#475569', marginBottom: 8 }}>
            {screenDetails.screens?.length || 0} screen(s) detected. Click to open the customer display on a specific screen.
          </p>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            {(screenDetails.screens || []).map((s: any, i: number) => (
              <button
                key={i}
                className="btn btn-secondary"
                onClick={() => openOnScreen(s, i)}
                style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}
              >
                <Monitor size={14} /> Screen {i + 1}
                <small style={{ color: '#94a3b8' }}>
                  ({s.width}×{s.height})
                </small>
              </button>
            ))}
            {screenDetails.screens?.length === 1 && (
              <span className="badge badge-amber">Only one screen detected</span>
            )}
          </div>
        </div>
      )}

      <div style={{ marginTop: 12, fontSize: 12, color: '#64748b', borderTop: '1px solid #e2e8f0', paddingTop: 8 }}>
        <b>Pairing channel:</b> <code>{channel || '(none — set the terminal identifier above)'}</code>
        <br />
        Share this channel with any other POS terminal that should mirror its cart to the same display.
        Terminals pairing to the same channel receive each other's cart broadcasts in real time via SSE.
      </div>

      {activeChannels && (
        <div style={{ marginTop: 12, padding: 12, background: '#f1f5f9', borderRadius: 8, fontSize: 12 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 8 }}>
            <b style={{ color: '#0d9488' }}>Multi-terminal status</b>
            <span className="badge badge-muted">{activeChannels.generalSyncClients ?? 0} POS sync clients</span>
            <span className="badge badge-muted">{activeChannels.totalDisplayClients ?? 0} display clients</span>
          </div>
          {(activeChannels.activeChannels && Object.keys(activeChannels.activeChannels).length > 0) ? (
            <table style={{ width: '100%', fontSize: 12, borderCollapse: 'collapse' }}>
              <thead><tr><th style={{ textAlign: 'left', padding: '4px 8px' }}>Channel</th><th style={{ textAlign: 'right', padding: '4px 8px' }}>Paired clients</th></tr></thead>
              <tbody>
                {Object.entries(activeChannels.activeChannels).map(([ch, n]) => (
                  <tr key={ch}><td style={{ padding: '4px 8px' }}><code>{ch}</code></td><td style={{ textAlign: 'right', padding: '4px 8px' }}>{String(n)}</td></tr>
                ))}
              </tbody>
            </table>
          ) : (
            <span style={{ color: '#64748b' }}>No active customer-display channels — open the display window on a terminal to pair.</span>
          )}
          {activeChannels.allowedChannels && activeChannels.allowedChannels.length > 0 && (
            <div style={{ marginTop: 8, color: '#64748b' }}>
              <b>Allowed channels (from POS_DISPLAY_CHANNELS env):</b>{' '}
              {activeChannels.allowedChannels.map((ch: string) => <code key={ch} style={{ marginRight: 8 }}>{ch.substring(0, 16)}…</code>)}
            </div>
          )}
        </div>
      )}

      <div className="modal-footer" style={{ marginTop: 12 }}>
        <Button variant="secondary" onClick={openFallbackPopup}>
          <ExternalLink size={16} /> Open display popup manually
        </Button>
        {popupWindow && !popupWindow.closed && (
          <Button variant="secondary" onClick={() => { popupWindow.close(); setPopupWindow(null); }}>
            Close display window
          </Button>
        )}
      </div>
    </div>
  );
}

