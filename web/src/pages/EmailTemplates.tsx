import { useCallback, useEffect, useState } from 'react';
import { Mail, RefreshCw, Plus, Save, Trash2, Eye, Check } from 'lucide-react';
import { api } from '../lib/api';
import { Button, Empty, Field, Loading, Modal, Notice, PageHeader } from '../components/Ui';

type Template = {
  id: string; name: string; kind: string; subject: string;
  bodyHtml: string; bodyText?: string; allowedVariables?: string;
  active: boolean; isDefault: boolean; createdAt: string; updatedAt: string;
};

const KINDS = [
  { value: 'receipt', label: 'Receipt (used when sending bills via email)' },
  { value: 'marketing', label: 'Marketing (bulk campaigns)' },
  { value: 'test', label: 'Test (admin test email)' },
  { value: 'welcome', label: 'Welcome (customer signup)' },
  { value: 'custom', label: 'Custom (manual use)' },
];

const CANONICAL_VARIABLES = [
  'billNumber', 'billId', 'customerName', 'customerEmail', 'customerPhone',
  'staffName', 'shopName', 'shopAddress', 'shopPhone', 'totalAmount',
  'paidAmount', 'balance', 'subtotal', 'taxTotal', 'vatAmount', 'ssclAmount',
  'totalDiscount', 'createdAt', 'billUrl', 'warrantyExpiresAt',
  'campaignName', 'unsubscribeUrl',
];

export default function EmailTemplates() {
  const [rows, setRows] = useState<Template[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [edit, setEdit] = useState<Template | null>(null);
  const [createModal, setCreateModal] = useState(false);
  const [previewModal, setPreviewModal] = useState<Template | null>(null);
  const [previewHtml, setPreviewHtml] = useState('');
  const [previewError, setPreviewError] = useState('');

  const load = useCallback(() => {
    setLoading(true); setError('');
    api<any>('/api/email-templates')
      .then(r => setRows(r.data || []))
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { void load(); }, [load]);

  const save = async () => {
    if (!edit) return;
    try {
      await api(`/api/email-templates/${edit.id}`, {
        method: 'PUT', body: JSON.stringify({
          name: edit.name, kind: edit.kind, subject: edit.subject,
          bodyHtml: edit.bodyHtml, bodyText: edit.bodyText,
          allowedVariables: edit.allowedVariables,
          active: edit.active, isDefault: edit.isDefault,
        }),
      });
      setMessage('Template saved');
      setEdit(null); load();
    } catch (e: any) { setError(e.message); }
  };

  const createTemplate = async (name: string, kind: string) => {
    try {
      const r = await api<Template>('/api/email-templates', {
        method: 'POST', body: JSON.stringify({
          name, kind, subject: 'New template subject',
          bodyHtml: '<p>Hello {{customerName}},</p><p>This is a new template.</p>',
          allowedVariables: '', active: true, isDefault: false,
        }),
      });
      setMessage('Template created');
      setCreateModal(false);
      setEdit(r.data);
      load();
    } catch (e: any) { setError(e.message); }
  };

  const remove = async (id: string) => {
    if (!confirm('Delete this template?')) return;
    try { await api(`/api/email-templates/${id}`, { method: 'DELETE' }); setMessage('Template deleted'); load(); }
    catch (e: any) { setError(e.message); }
  };

  const setDefault = async (id: string) => {
    try { await api(`/api/email-templates/${id}/set-default`, { method: 'POST' }); setMessage('Default updated'); load(); }
    catch (e: any) { setError(e.message); }
  };

  const showPreview = async (t: Template) => {
    setPreviewModal(t); setPreviewError(''); setPreviewHtml('Loading…');
    try {
      const r = await api<{ subject: string; bodyHtml: string }>(`/api/email-templates/${t.id}/render`, {
        method: 'POST', body: JSON.stringify({
          variables: Object.fromEntries(CANONICAL_VARIABLES.map(v => [v, `[${v}]`])),
        }),
      });
      setPreviewHtml(r.data?.bodyHtml || '');
    } catch (e: any) { setPreviewError(e.message); setPreviewHtml(''); }
  };

  const insertVariable = (varName: string) => {
    if (!edit) return;
    setEdit({ ...edit, bodyHtml: edit.bodyHtml + `{{${varName}}}` });
  };

  return (
    <>
      <PageHeader title="Email templates" subtitle="Custom email templates for receipts, marketing, and notifications. Variables use {{double-brace}} syntax."
        action={<div style={{ display: 'flex', gap: 8 }}>
          <Button variant="secondary" onClick={load}><RefreshCw size={16} /> Refresh</Button>
          <Button onClick={() => setCreateModal(true)}><Plus size={16} /> New template</Button>
        </div>} />
      {error && <Notice message={error} onClose={() => setError('')} />}
      {message && <Notice kind="success" message={message} onClose={() => setMessage('')} />}

      {loading ? <Loading /> : rows.length === 0 ? <Empty text="No email templates yet — create one to get started" /> : (
        <section className="panel">
          <div className="table-wrap"><table>
            <thead><tr>
              <th>Name</th><th>Kind</th><th>Subject</th>
              <th>Default?</th><th>Active</th><th>Actions</th>
            </tr></thead>
            <tbody>
              {rows.map(t => (
                <tr key={t.id}>
                  <td><b>{t.name}</b></td>
                  <td><span className="badge badge-muted">{t.kind}</span></td>
                  <td style={{ maxWidth: 300, overflow: 'hidden', textOverflow: 'ellipsis' }}>{t.subject}</td>
                  <td>{t.isDefault ? <Check size={16} style={{ color: '#16a34a' }} /> : '—'}</td>
                  <td>{t.active ? <Check size={16} style={{ color: '#16a34a' }} /> : <span style={{ color: '#94a3b8' }}>off</span>}</td>
                  <td>
                    <div className="row-actions">
                      <button className="icon-button" title="Edit" onClick={() => setEdit(t)}><Mail size={16} /></button>
                      <button className="icon-button" title="Preview" onClick={() => showPreview(t)}><Eye size={16} /></button>
                      {!t.isDefault && <button className="icon-button" title="Set default" onClick={() => setDefault(t.id)}><Check size={16} /></button>}
                      <button className="icon-button danger-icon" title="Delete" onClick={() => remove(t.id)}><Trash2 size={16} /></button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table></div>
        </section>
      )}

      {/* Editor modal */}
      {edit && (
        <Modal onClose={() => setEdit(null)} title={`Edit template · ${edit.name}`} wide>
          <Field label="Name"><input value={edit.name} onChange={e => setEdit({...edit, name: e.target.value})} /></Field>
          <Field label="Kind">
            <select value={edit.kind} onChange={e => setEdit({...edit, kind: e.target.value})}>
              {KINDS.map(k => <option key={k.value} value={k.value}>{k.label}</option>)}
            </select>
          </Field>
          <Field label="Subject"><input value={edit.subject} onChange={e => setEdit({...edit, subject: e.target.value})} /></Field>
          <Field label="Body (HTML — {{variables}} are substituted server-side; dangerous tags are stripped via Jsoup)">
            <textarea value={edit.bodyHtml} onChange={e => setEdit({...edit, bodyHtml: e.target.value})} rows={12}
              style={{ fontFamily: 'monospace', fontSize: 13 }} />
          </Field>
          <div style={{ marginBottom: 12, padding: 12, background: '#f1f5f9', borderRadius: 8 }}>
            <b style={{ fontSize: 12 }}>Insert variable:</b>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 6 }}>
              {CANONICAL_VARIABLES.map(v => (
                <button key={v} type="button" onClick={() => insertVariable(v)}
                  style={{ padding: '4px 8px', fontSize: 12, border: '1px solid #cbd5e1', borderRadius: 4, background: '#fff', cursor: 'pointer', fontFamily: 'monospace' }}>
                  {`{{${v}}}`}
                </button>
              ))}
            </div>
          </div>
          <Field label="Allowed variables (CSV — leave blank for kind defaults)">
            <input value={edit.allowedVariables || ''} onChange={e => setEdit({...edit, allowedVariables: e.target.value})}
              placeholder="e.g. customerName,totalAmount,billNumber" />
          </Field>
          <div style={{ display: 'flex', gap: 12, marginBottom: 12 }}>
            <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13 }}>
              <input type="checkbox" checked={edit.active} onChange={e => setEdit({...edit, active: e.target.checked})} /> Active
            </label>
            <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13 }}>
              <input type="checkbox" checked={edit.isDefault} onChange={e => setEdit({...edit, isDefault: e.target.checked})} /> Default for this kind
            </label>
          </div>
          <div className="modal-footer">
            <Button variant="secondary" onClick={() => setEdit(null)}>Cancel</Button>
            <Button onClick={save}><Save size={16} /> Save template</Button>
          </div>
        </Modal>
      )}

      {/* Create modal */}
      {createModal && (
        <CreateTemplateModal onCreate={createTemplate} onClose={() => setCreateModal(false)} />
      )}

      {/* Preview modal */}
      {previewModal && (
        <Modal onClose={() => setPreviewModal(null)} title={`Preview · ${previewModal.name}`} wide>
          {previewError ? <Notice message={previewError} onClose={() => setPreviewError('')} /> : (
            <div style={{ padding: 16, background: '#fff', border: '1px solid #e2e8f0', borderRadius: 8, maxHeight: 500, overflow: 'auto' }}
              dangerouslySetInnerHTML={{ __html: previewHtml }} />
          )}
        </Modal>
      )}
    </>
  );
}

function CreateTemplateModal({ onCreate, onClose }: { onCreate: (name: string, kind: string) => void; onClose: () => void }) {
  const [name, setName] = useState('');
  const [kind, setKind] = useState('receipt');
  return (
    <Modal onClose={onClose} title="New email template">
      <Field label="Template name"><input value={name} onChange={e => setName(e.target.value)} placeholder="e.g. Default receipt email" autoFocus /></Field>
      <Field label="Kind">
        <select value={kind} onChange={e => setKind(e.target.value)}>
          {KINDS.map(k => <option key={k.value} value={k.value}>{k.label}</option>)}
        </select>
      </Field>
      <div className="modal-footer">
        <Button variant="secondary" onClick={onClose}>Cancel</Button>
        <Button onClick={() => onCreate(name || 'Untitled', kind)} disabled={!name.trim()}>Create</Button>
      </div>
    </Modal>
  );
}
