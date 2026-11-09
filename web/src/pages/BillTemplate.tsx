import { useCallback, useEffect, useState } from 'react';
import { Save, Eye, RefreshCw, FileText, ExternalLink } from 'lucide-react';
import { api } from '../lib/api';
import { Button, Field, Loading, Notice, PageHeader } from '../components/Ui';

/**
 * Bill template settings — controls what sections appear on A4 receipts
 * (sub-items, specifications, warranty, revisions, T&C page).
 *
 * The live preview calls GET /api/billing/{id}/a4-html with the most recent
 * bill, so the operator sees exactly what a printed A4 receipt will look
 * like with the current settings applied.
 *
 * The T&C editor is a raw HTML textarea — the backend sanitizes it via
 * Jsoup before persistence + render, so operators can't persist XSS payloads.
 */
export default function BillTemplate() {
  const [values, setValues] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [previewHtml, setPreviewHtml] = useState('');
  const [previewBillId, setPreviewBillId] = useState('');
  const [showPreview, setShowPreview] = useState(false);

  const load = useCallback(() => {
    setLoading(true); setError('');
    api<any>('/api/settings')
      .then(r => {
        const v: any = {};
        (r.data || []).forEach((x: any) => v[x.key] = x.value ?? '');
        setValues(v);
      })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { void load(); }, [load]);

  const save = async () => {
    setBusy(true); setError('');
    try {
      await api('/api/settings', { method: 'PUT', body: JSON.stringify({ settings: values }) });
      setMessage('Bill template settings saved');
    } catch (e: any) { setError(e.message); }
    finally { setBusy(false); }
  };

  const showLivePreview = async () => {
    setError(''); setMessage('');
    try {
      // Find the most recent bill
      const billsResp = await api<any>('/api/billing?page=1&limit=1&sort=createdAt&direction=desc');
      const latest = (billsResp.data || [])[0];
      if (!latest) {
        setError('No bills found — create at least one bill to preview the A4 template.');
        return;
      }
      setPreviewBillId(latest.id);
      // Fetch the rendered A4 HTML directly (returns text/html)
      const token = sessionStorage.getItem('pos_token') || '';
      const res = await fetch(`${import.meta.env.VITE_API_BASE_URL || 'http://127.0.0.1:8080'}/api/billing/${latest.id}/a4-html`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      const html = await res.text();
      setPreviewHtml(html);
      setShowPreview(true);
    } catch (e: any) { setError(e.message); }
  };

  const setKey = (key: string, value: string) => setValues({ ...values, [key]: value });

  if (loading) return <><PageHeader title="Bill template" subtitle="Loading settings…" /><Loading /></>;

  return (
    <>
      <PageHeader title="A4 bill template" subtitle="Control what sections appear on A4 receipts. Thermal receipts always use the compact format."
        action={<div style={{ display: 'flex', gap: 8 }}>
          <Button variant="secondary" onClick={load}><RefreshCw size={16} /></Button>
          <Button variant="secondary" onClick={showLivePreview}><Eye size={16} /> Live preview</Button>
          <Button onClick={save} disabled={busy}><Save size={16} /> Save</Button>
        </div>} />
      {error && <Notice message={error} onClose={() => setError('')} />}
      {message && <Notice kind="success" message={message} onClose={() => setMessage('')} />}

      <div className="settings-grid">
        {/* Section toggles */}
        <section className="panel settings-panel">
          <div className="panel-heading">
            <div className="settings-heading-icon"><FileText /></div>
            <div><h2>A4 receipt sections</h2><p>Toggle which sections appear on the printed A4 receipt.</p></div>
          </div>

          <Field label="Show sub-items breakdown (child line items under parent lines)">
            <select value={values['a4_bill_include_subitems'] || 'true'} onChange={e => setKey('a4_bill_include_subitems', e.target.value)}>
              <option value="true">Show sub-items</option>
              <option value="false">Hide sub-items</option>
            </select>
          </Field>
          <Field label="Show per-line specifications (RAM, Storage, Color, etc.)">
            <select value={values['a4_bill_include_specifications'] || 'true'} onChange={e => setKey('a4_bill_include_specifications', e.target.value)}>
              <option value="true">Show specifications</option>
              <option value="false">Hide specifications</option>
            </select>
          </Field>
          <Field label="Show warranty duration + expiry per line">
            <select value={values['a4_bill_include_warranty'] || 'true'} onChange={e => setKey('a4_bill_include_warranty', e.target.value)}>
              <option value="true">Show warranty</option>
              <option value="false">Hide warranty</option>
            </select>
          </Field>
          <Field label="Show revision count + price (freelance scope items)">
            <select value={values['a4_bill_include_revisions'] || 'true'} onChange={e => setKey('a4_bill_include_revisions', e.target.value)}>
              <option value="true">Show revisions</option>
              <option value="false">Hide revisions</option>
            </select>
          </Field>
          <Field label="Append Terms & Conditions page after the bill">
            <select value={values['a4_bill_include_terms'] || 'true'} onChange={e => setKey('a4_bill_include_terms', e.target.value)}>
              <option value="true">Include T&C page</option>
              <option value="false">No T&C page</option>
            </select>
          </Field>
        </section>

        {/* Terms & Conditions editor */}
        <section className="panel settings-panel">
          <div className="panel-heading">
            <div className="settings-heading-icon"><FileText /></div>
            <div><h2>Terms & Conditions content</h2><p>HTML body appended after the bill on a new A4 page. Sanitized via Jsoup (no &lt;script&gt;, &lt;iframe&gt;, etc.).</p></div>
          </div>
          <Field label="T&C HTML body (use &lt;h2&gt;, &lt;ol&gt;, &lt;li&gt;, &lt;p&gt;, &lt;strong&gt; — other tags are stripped)">
            <textarea
              value={values['a4_bill_terms_content'] || ''}
              onChange={e => setKey('a4_bill_terms_content', e.target.value)}
              rows={15}
              style={{ fontFamily: 'monospace', fontSize: 13 }}
              placeholder={'<h2>Terms &amp; Conditions</h2>\n<ol>\n<li>All sales subject to warranty.</li>\n<li>Refunds within 7 business days.</li>\n</ol>'}
            />
          </Field>
          <div className="modal-footer">
            <Button onClick={save} disabled={busy}><Save size={16} /> Save T&C content</Button>
          </div>
        </section>

        {/* Multi-channel defaults */}
        <section className="panel settings-panel">
          <div className="panel-heading">
            <div className="settings-heading-icon"><ExternalLink /></div>
            <div><h2>Multi-channel send defaults</h2><p>Default format when sending a receipt via WhatsApp or email.</p></div>
          </div>
          <Field label="Default WhatsApp receipt format">
            <select value={values['whatsapp_default_format'] || 'text'} onChange={e => setKey('whatsapp_default_format', e.target.value)}>
              <option value="text">Text (plain message body)</option>
              <option value="pdf">PDF (A4 receipt URL link)</option>
            </select>
          </Field>
          <Field label="Default email receipt format">
            <select value={values['email_default_format'] || 'pdf'} onChange={e => setKey('email_default_format', e.target.value)}>
              <option value="text">Text (inline HTML body)</option>
              <option value="pdf">PDF (A4 receipt embedded)</option>
            </select>
          </Field>
          <div className="modal-footer">
            <Button onClick={save} disabled={busy}><Save size={16} /> Save defaults</Button>
          </div>
        </section>
      </div>

      {/* Live preview modal */}
      {showPreview && (
        <div className="modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) setShowPreview(false); }}>
          <div style={{ background: '#fff', borderRadius: 12, padding: 24, maxWidth: 900, width: '90%', maxHeight: '90vh', overflow: 'auto' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
              <h2>A4 receipt preview</h2>
              <div style={{ display: 'flex', gap: 8 }}>
                <a href={`${import.meta.env.VITE_API_BASE_URL || 'http://127.0.0.1:8080'}/api/billing/${previewBillId}/a4-html`}
                  target="_blank" rel="noopener noreferrer"
                  className="btn btn-secondary" style={{ display: 'inline-flex', alignItems: 'center', gap: 6, textDecoration: 'none' }}>
                  <ExternalLink size={16} /> Open in new tab
                </a>
                <Button variant="secondary" onClick={() => setShowPreview(false)}>Close</Button>
              </div>
            </div>
            <div style={{ border: '1px solid #e2e8f0', borderRadius: 8, padding: 16, overflow: 'auto' }}
              dangerouslySetInnerHTML={{ __html: previewHtml }} />
          </div>
        </div>
      )}
    </>
  );
}
