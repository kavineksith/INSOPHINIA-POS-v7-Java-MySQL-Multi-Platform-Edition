import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { ShieldAlert, ShieldCheck, RefreshCw, Plus, X, Clock, AlertTriangle, FileText } from 'lucide-react';
import { api, money, dateTime, qs } from '../lib/api';
import { Button, Empty, Field, Loading, Modal, Notice, PageHeader, Pagination, SearchBox } from '../components/Ui';

type Warranty = {
  id: string; billId?: string; billItemId?: string; customerId?: string;
  customerName: string; customerEmail?: string; customerPhone?: string;
  itemId?: string; itemName: string; pluCode?: string; serialNumber?: string;
  warrantyMonths: number; warrantyStartsAt: string; warrantyExpiresAt: string;
  status: string; notes?: string; openClaimsCount: number;
  createdAt: string; updatedAt: string;
};

type Claim = {
  id: string; warrantyId: string; customerId?: string; customerName: string;
  claimNumber: string; faultReported: string; technicianId?: string;
  technicianName?: string; diagnosis?: string; resolution?: string;
  resolutionNotes?: string; replacementItemId?: string;
  refundAmount: number; status: string; filedAt: string;
  resolvedAt?: string; createdAt: string;
};

const STATUS_BADGES: Record<string, string> = {
  active: 'badge-green', expiring: 'badge-amber', expired: 'badge-muted',
  claimed: 'badge-blue', voided: 'badge-red',
};

export default function Warranties() {
  const [sp] = useSearchParams();
  const [rows, setRows] = useState<Warranty[]>([]);
  const [page, setPage] = useState(1);
  const [pages, setPages] = useState(1);
  const [total, setTotal] = useState(0);
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState(sp.get('status') || '');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [detail, setDetail] = useState<Warranty | null>(null);
  const [claims, setClaims] = useState<Claim[]>([]);
  const [claimModal, setClaimModal] = useState(false);
  const [claimFault, setClaimFault] = useState('');
  const [resolveModal, setResolveModal] = useState<Claim | null>(null);
  const [resolution, setResolution] = useState('repair');
  const [resolutionNotes, setResolutionNotes] = useState('');
  const [refundAmount, setRefundAmount] = useState('0');

  const load = useCallback(() => {
    setLoading(true); setError('');
    const q = qs({ page, limit: 50, search: search || undefined, status: status || undefined });
    api<any>(`/api/warranties?${q}`)
      .then(r => { setRows(r.data || []); setPages(r.pagination?.pages || 1); setTotal(r.pagination?.total ?? 0); })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  }, [page, search, status]);

  useEffect(() => { const t = setTimeout(() => void load(), 250); return () => clearTimeout(t); }, [load]);

  const openDetail = (w: Warranty) => {
    setDetail(w);
    api<any>(`/api/warranties/${w.id}/claims`)
      .then(r => setClaims(r.data || []))
      .catch(e => setError(e.message));
  };

  const fileClaim = async () => {
    if (!detail || !claimFault.trim()) return;
    try {
      await api(`/api/warranties/${detail.id}/claims`, {
        method: 'POST', body: JSON.stringify({ faultReported: claimFault }),
      });
      setMessage('Claim filed successfully');
      setClaimModal(false); setClaimFault('');
      openDetail(detail); // refresh
      load();
    } catch (e: any) { setError(e.message); }
  };

  const resolveClaim = async () => {
    if (!detail || !resolveModal) return;
    try {
      await api(`/api/warranties/claims/${resolveModal.id}/resolve`, {
        method: 'POST', body: JSON.stringify({
          resolution, resolutionNotes, refundAmount: Number(refundAmount) || 0,
        }),
      });
      setMessage('Claim resolved');
      setResolveModal(null); setResolutionNotes(''); setRefundAmount('0');
      openDetail(detail); load();
    } catch (e: any) { setError(e.message); }
  };

  return (
    <>
      <PageHeader title="Warranties" subtitle="Track warranty-backed items, file claims, and resolve repairs / replacements."
        action={<Button variant="secondary" onClick={load}><RefreshCw size={16} /> Refresh</Button>} />
      {error && <Notice message={error} onClose={() => setError('')} />}
      {message && <Notice kind="success" message={message} onClose={() => setMessage('')} />}

      <section className="panel" style={{ marginBottom: 16 }}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 200px', gap: 12, alignItems: 'end' }}>
          <SearchBox value={search} onChange={v => { setSearch(v); setPage(1); }} placeholder="Search by customer, item, or serial…" />
          <Field label="Status">
            <select value={status} onChange={e => { setStatus(e.target.value); setPage(1); }}>
              <option value="">All statuses</option>
              <option value="active">Active</option>
              <option value="expiring">Expiring soon</option>
              <option value="expired">Expired</option>
              <option value="claimed">Has open claims</option>
              <option value="voided">Voided</option>
            </select>
          </Field>
        </div>
      </section>

      {loading ? <Loading /> : rows.length === 0 ? <Empty text="No warranties found" /> : (
        <section className="panel">
          <div className="table-wrap"><table>
            <thead><tr>
              <th>Customer</th><th>Item</th><th>Serial</th>
              <th>Warranty</th><th>Expires</th><th>Status</th>
              <th>Open claims</th><th>Actions</th>
            </tr></thead>
            <tbody>
              {rows.map(w => (
                <tr key={w.id} style={{ cursor: 'pointer' }} onClick={() => openDetail(w)}>
                  <td><b>{w.customerName}</b><small>{w.customerPhone || w.customerEmail || '—'}</small></td>
                  <td><b>{w.itemName}</b><small>{w.pluCode || '—'}</small></td>
                  <td>{w.serialNumber || '—'}</td>
                  <td>{w.warrantyMonths} months</td>
                  <td>{dateTime(w.warrantyExpiresAt)}</td>
                  <td><span className={`badge ${STATUS_BADGES[w.status] || 'badge-muted'}`}>{w.status}</span></td>
                  <td>{w.openClaimsCount > 0 ? <b style={{ color: '#c2410c' }}>{w.openClaimsCount}</b> : '—'}</td>
                  <td><Button variant="secondary" onClick={()=>openDetail(w)}><FileText size={15} /> View</Button></td>
                </tr>
              ))}
            </tbody>
          </table></div>
          <Pagination page={page} pages={pages} total={total} onPage={setPage} />
        </section>
      )}

      {/* Detail modal with claims history */}
      {detail && (
        <Modal onClose={() => setDetail(null)} title={`Warranty · ${detail.itemName}`} wide>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 16 }}>
            <div><b>Customer:</b> {detail.customerName}<br />
              <small>{detail.customerEmail || ''} {detail.customerPhone || ''}</small></div>
            <div><b>Item:</b> {detail.itemName}<br />
              <small>PLU: {detail.pluCode || '—'} · Serial: {detail.serialNumber || '—'}</small></div>
            <div><b>Warranty period:</b> {detail.warrantyMonths} months<br />
              <small>From {dateTime(detail.warrantyStartsAt)} to {dateTime(detail.warrantyExpiresAt)}</small></div>
            <div><b>Status:</b> <span className={`badge ${STATUS_BADGES[detail.status]}`}>{detail.status}</span><br />
              <small>Open claims: {detail.openClaimsCount}</small></div>
          </div>

          {detail.notes && <div style={{ padding: 12, background: '#f8fafc', borderRadius: 8, marginBottom: 16 }}>
            <b>Notes:</b> {detail.notes}
          </div>}

          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
            <h3>Claims history</h3>
            <Button onClick={() => setClaimModal(true)} disabled={detail.status === 'voided' || detail.status === 'expired'}>
              <Plus size={16} /> File claim
            </Button>
          </div>

          {claims.length === 0 ? <Empty text="No claims filed" /> : (
            <div className="table-wrap"><table>
              <thead><tr><th>Claim #</th><th>Fault</th><th>Resolution</th><th>Status</th><th>Filed</th><th>Action</th></tr></thead>
              <tbody>
                {claims.map(c => (
                  <tr key={c.id}>
                    <td><b>{c.claimNumber}</b></td>
                    <td style={{ maxWidth: 300, overflow: 'hidden', textOverflow: 'ellipsis' }}>{c.faultReported}</td>
                    <td>{c.resolution || 'pending'}</td>
                    <td><span className={`badge ${c.status === 'open' ? 'badge-amber' : c.status === 'resolved' ? 'badge-green' : 'badge-muted'}`}>{c.status}</span></td>
                    <td>{dateTime(c.filedAt)}</td>
                    <td>
                      {(c.status === 'open' || c.status === 'in_progress') && (
                        <Button variant="secondary" onClick={() => {
                          setResolveModal(c); setResolution('repair'); setResolutionNotes(''); setRefundAmount('0');
                        }}>Resolve…</Button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table></div>
          )}
        </Modal>
      )}

      {/* File claim modal */}
      {claimModal && detail && (
        <Modal onClose={() => setClaimModal(false)} title="File a warranty claim">
          <Field label="Fault reported by customer">
            <textarea value={claimFault} onChange={e => setClaimFault(e.target.value)} rows={4}
              placeholder="Describe the fault the customer reported…" autoFocus required />
          </Field>
          <div className="modal-footer">
            <Button variant="secondary" onClick={() => setClaimModal(false)}>Cancel</Button>
            <Button onClick={fileClaim} disabled={!claimFault.trim()}>File claim</Button>
          </div>
        </Modal>
      )}

      {/* Resolve claim modal */}
      {resolveModal && (
        <Modal onClose={() => setResolveModal(null)} title={`Resolve claim ${resolveModal.claimNumber}`}>
          <Field label="Resolution">
            <select value={resolution} onChange={e => setResolution(e.target.value)}>
              <option value="repair">Repair (fix the item)</option>
              <option value="replacement">Replacement (swap for new item)</option>
              <option value="refund">Refund</option>
              <option value="rejected">Rejected (out of warranty / customer damage)</option>
              <option value="escalated">Escalated (to manufacturer / management)</option>
            </select>
          </Field>
          <Field label="Resolution notes">
            <textarea value={resolutionNotes} onChange={e => setResolutionNotes(e.target.value)} rows={3}
              placeholder="What was done? Parts replaced? Time spent?" />
          </Field>
          {resolution === 'refund' && (
            <Field label="Refund amount (LKR)">
              <input type="number" min="0" step="0.01" value={refundAmount}
                onChange={e => setRefundAmount(e.target.value)} />
            </Field>
          )}
          <div className="modal-footer">
            <Button variant="secondary" onClick={() => setResolveModal(null)}>Cancel</Button>
            <Button onClick={resolveClaim}>Resolve claim</Button>
          </div>
        </Modal>
      )}
    </>
  );
}
