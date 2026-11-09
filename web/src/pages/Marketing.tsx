import { useCallback, useEffect, useState } from 'react';
import { Megaphone, RefreshCw, Plus, Play, XCircle, Trash2, Mail, Clock, CheckCircle2, X } from 'lucide-react';
import { api, dateTime, qs } from '../lib/api';
import { Button, Empty, Field, Loading, Modal, Notice, PageHeader, Pagination, SearchBox } from '../components/Ui';

type Campaign = {
  id: string; name: string; description?: string; templateId: string;
  templateName?: string; status: string; targetFilter?: string;
  scheduledAt?: string; startedAt?: string; completedAt?: string;
  snapshotSubject: string; targetCount: number; sentCount: number;
  failedCount: number; openedCount: number; clickedCount: number;
  createdBy?: string; createdAt: string;
};

type SendLog = {
  id: string; campaignId: string; customerId?: string; customerEmail: string;
  status: string; failureReason?: string; sentAt?: string;
  openedAt?: string; clickedAt?: string; createdAt: string;
};

type EmailTemplate = { id: string; name: string; kind: string; subject: string; };

const STATUS_BADGES: Record<string, string> = {
  draft: 'badge-muted', scheduled: 'badge-blue', sending: 'badge-amber',
  sent: 'badge-green', cancelled: 'badge-red', failed: 'badge-red',
};

export default function Marketing() {
  const [rows, setRows] = useState<Campaign[]>([]);
  const [page, setPage] = useState(1);
  const [pages, setPages] = useState(1);
  const [total, setTotal] = useState(0);
  const [status, setStatus] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [createModal, setCreateModal] = useState(false);
  const [templates, setTemplates] = useState<EmailTemplate[]>([]);
  const [createForm, setCreateForm] = useState({
    name: '', description: '', templateId: '', targetFilter: '',
    loyaltyPointsMin: '', hasEmail: 'true', emailVerified: 'true', sendNow: false, scheduledAt: '',
  });
  const [detail, setDetail] = useState<Campaign | null>(null);
  const [sendLogs, setSendLogs] = useState<SendLog[]>([]);
  const [sendLogsPage, setSendLogsPage] = useState(1);

  const load = useCallback(() => {
    setLoading(true); setError('');
    const q = qs({ page, limit: 50, status: status || undefined });
    api<any>(`/api/marketing/campaigns?${q}`)
      .then(r => { setRows(r.data || []); setPages(r.pagination?.pages || 1); setTotal(r.pagination?.total ?? 0); })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  }, [page, status]);

  useEffect(() => { const t = setTimeout(() => void load(), 250); return () => clearTimeout(t); }, [load]);

  useEffect(() => {
    api<any>('/api/email-templates?kind=marketing')
      .then(r => setTemplates(r.data || []))
      .catch(() => {});
  }, []);

  const createCampaign = async () => {
    try {
      const filter = JSON.stringify({
        loyaltyPointsMin: createForm.loyaltyPointsMin ? Number(createForm.loyaltyPointsMin) : undefined,
        hasEmail: createForm.hasEmail === 'true',
        emailVerified: createForm.emailVerified === 'true',
      });
      const r = await api<Campaign>('/api/marketing/campaigns', {
        method: 'POST', body: JSON.stringify({
          name: createForm.name, description: createForm.description,
          templateId: createForm.templateId, targetFilter: filter,
          scheduledAt: createForm.scheduledAt ? new Date(createForm.scheduledAt).toISOString() : undefined,
          sendNow: createForm.sendNow,
        }),
      });
      setMessage(`Campaign "${r.data?.name}" created`);
      setCreateModal(false);
      setCreateForm({ name: '', description: '', templateId: '', targetFilter: '', loyaltyPointsMin: '', hasEmail: 'true', emailVerified: 'true', sendNow: false, scheduledAt: '' });
      load();
    } catch (e: any) { setError(e.message); }
  };

  const sendNow = async (id: string) => {
    if (!confirm('Send this campaign now? This will send emails to all matching customers.')) return;
    try {
      await api(`/api/marketing/campaigns/${id}/send`, { method: 'POST' });
      setMessage('Campaign send loop executed');
      load();
    } catch (e: any) { setError(e.message); }
  };

  const cancel = async (id: string) => {
    if (!confirm('Cancel this campaign?')) return;
    try { await api(`/api/marketing/campaigns/${id}/cancel`, { method: 'POST' }); setMessage('Campaign cancelled'); load(); }
    catch (e: any) { setError(e.message); }
  };

  const remove = async (id: string) => {
    if (!confirm('Delete this campaign?')) return;
    try { await api(`/api/marketing/campaigns/${id}`, { method: 'DELETE' }); setMessage('Campaign deleted'); load(); }
    catch (e: any) { setError(e.message); }
  };

  const openDetail = (c: Campaign) => {
    setDetail(c);
    api<any>(`/api/marketing/campaigns/${c.id}/sends?page=1&limit=50`)
      .then(r => setSendLogs(r.data || []))
      .catch(() => {});
  };

  return (
    <>
      <PageHeader title="Marketing campaigns" subtitle="Send bulk promotional emails to filtered customer segments."
        action={<div style={{ display: 'flex', gap: 8 }}>
          <Button variant="secondary" onClick={load}><RefreshCw size={16} /> Refresh</Button>
          <Button onClick={() => setCreateModal(true)}><Plus size={16} /> New campaign</Button>
        </div>} />
      {error && <Notice message={error} onClose={() => setError('')} />}
      {message && <Notice kind="success" message={message} onClose={() => setMessage('')} />}

      <section className="panel" style={{ marginBottom: 16 }}>
        <Field label="Status filter">
          <select value={status} onChange={e => { setStatus(e.target.value); setPage(1); }}>
            <option value="">All statuses</option>
            <option value="draft">Draft</option>
            <option value="scheduled">Scheduled</option>
            <option value="sending">Sending</option>
            <option value="sent">Sent</option>
            <option value="cancelled">Cancelled</option>
            <option value="failed">Failed</option>
          </select>
        </Field>
      </section>

      {loading ? <Loading /> : rows.length === 0 ? <Empty text="No marketing campaigns yet" /> : (
        <section className="panel">
          <div className="table-wrap"><table>
            <thead><tr>
              <th>Campaign</th><th>Template</th><th>Status</th>
              <th>Target</th><th>Sent</th><th>Failed</th>
              <th>Created</th><th>Actions</th>
            </tr></thead>
            <tbody>
              {rows.map(c => (
                <tr key={c.id} style={{ cursor: 'pointer' }} onClick={() => openDetail(c)}>
                  <td><b>{c.name}</b><small>{c.description || ''}</small></td>
                  <td>{c.templateName || '—'}</td>
                  <td><span className={`badge ${STATUS_BADGES[c.status]}`}>{c.status}</span></td>
                  <td>{c.targetCount}</td>
                  <td>{c.sentCount > 0 ? <b style={{ color: '#16a34a' }}>{c.sentCount}</b> : '—'}</td>
                  <td>{c.failedCount > 0 ? <b style={{ color: '#dc2626' }}>{c.failedCount}</b> : '—'}</td>
                  <td>{dateTime(c.createdAt)}</td>
                  <td>
                    <div className="row-actions" onClick={e => e.stopPropagation()}>
                      {c.status === 'scheduled' && <button className="icon-button" title="Send now" onClick={() => sendNow(c.id)}><Play size={16} /></button>}
                      {(c.status === 'scheduled' || c.status === 'draft' || c.status === 'sending') && <button className="icon-button" title="Cancel" onClick={() => cancel(c.id)}><XCircle size={16} /></button>}
                      <button className="icon-button danger-icon" title="Delete" onClick={() => remove(c.id)}><Trash2 size={16} /></button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table></div>
          <Pagination page={page} pages={pages} total={total} onPage={setPage} />
        </section>
      )}

      {/* Create campaign modal */}
      {createModal && (
        <Modal onClose={() => setCreateModal(false)} title="New marketing campaign" wide>
          <Field label="Campaign name"><input value={createForm.name} onChange={e => setCreateForm({...createForm, name: e.target.value})} placeholder="e.g. October 2026 loyalty promo" /></Field>
          <Field label="Description (optional)"><textarea value={createForm.description} onChange={e => setCreateForm({...createForm, description: e.target.value})} rows={2} /></Field>
          <Field label="Email template">
            <select value={createForm.templateId} onChange={e => setCreateForm({...createForm, templateId: e.target.value})}>
              <option value="">Select a marketing template…</option>
              {templates.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
            </select>
            {templates.length === 0 && <small style={{ color: '#c2410c' }}>No marketing templates found. Create one in Email Templates first.</small>}
          </Field>
          <Field label="Minimum loyalty points (optional)"><input type="number" min="0" value={createForm.loyaltyPointsMin} onChange={e => setCreateForm({...createForm, loyaltyPointsMin: e.target.value})} placeholder="0 = all customers" /></Field>
          <Field label="Require email-verified customers (anti-spam compliance)">
            <select value={createForm.emailVerified} onChange={e => setCreateForm({...createForm, emailVerified: e.target.value})}>
              <option value="true">Yes — only email-verified (recommended)</option>
              <option value="false">No — include all with an email address</option>
            </select>
          </Field>
          <Field label="Schedule for later (optional)">
            <input type="datetime-local" value={createForm.scheduledAt} onChange={e => setCreateForm({...createForm, scheduledAt: e.target.value, sendNow: false })} />
          </Field>
          <div className="modal-footer">
            <Button variant="secondary" onClick={() => setCreateModal(false)}>Cancel</Button>
            <Button onClick={createCampaign} disabled={!createForm.name || !createForm.templateId}>Create campaign</Button>
          </div>
        </Modal>
      )}

      {/* Detail + send logs */}
      {detail && (
        <Modal onClose={() => setDetail(null)} title={`Campaign · ${detail.name}`} wide>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 12, marginBottom: 16 }}>
            <div style={{ padding: 12, background: '#f0fdf4', borderRadius: 8 }}>
              <small>Target</small><br /><b>{detail.targetCount}</b> customers
            </div>
            <div style={{ padding: 12, background: '#eff6ff', borderRadius: 8 }}>
              <small>Sent</small><br /><b style={{ color: '#16a34a' }}>{detail.sentCount}</b>
            </div>
            <div style={{ padding: 12, background: '#fef2f2', borderRadius: 8 }}>
              <small>Failed</small><br /><b style={{ color: '#dc2626' }}>{detail.failedCount}</b>
            </div>
          </div>
          {detail.description && <p>{detail.description}</p>}
          <h3>Send log</h3>
          {sendLogs.length === 0 ? <Empty text="No sends yet" /> : (
            <div className="table-wrap"><table>
              <thead><tr><th>Recipient</th><th>Status</th><th>Failure reason</th><th>Sent at</th></tr></thead>
              <tbody>
                {sendLogs.map(l => (
                  <tr key={l.id}>
                    <td>{l.customerEmail}</td>
                    <td><span className={`badge ${l.status === 'sent' ? 'badge-green' : l.status === 'failed' ? 'badge-red' : 'badge-muted'}`}>{l.status}</span></td>
                    <td style={{ maxWidth: 300, overflow: 'hidden', textOverflow: 'ellipsis' }}>{l.failureReason || '—'}</td>
                    <td>{l.sentAt ? dateTime(l.sentAt) : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table></div>
          )}
        </Modal>
      )}
    </>
  );
}
