import { useCallback, useEffect, useState } from 'react';
import { Wrench, RefreshCw, Plus, Clock, AlertTriangle, CheckCircle2, X } from 'lucide-react';
import { api, money, dateTime, qs } from '../lib/api';
import { Button, Empty, Field, Loading, Modal, Notice, PageHeader, Pagination, SearchBox } from '../components/Ui';

type Repair = {
  id: string; repairNumber: string; customerId?: string; customerName: string;
  customerEmail?: string; customerPhone?: string; itemDescription: string;
  itemSerial?: string; problemReported: string; diagnosis?: string;
  technicianId?: string; technicianName?: string; status: string; priority: string;
  receivedAt: string; diagnosedAt?: string; startedAt?: string;
  completedAt?: string; deliveredAt?: string; closedAt?: string;
  estimatedCost: number; actualCost: number;
  finalBillId?: string; warrantyId?: string; warrantyMonths: number;
  notes?: string; createdAt: string; updatedAt: string;
  items?: RepairItem[];
};

type RepairItem = {
  id: string; lineType: string; description: string; itemId?: string;
  quantity: number; unitPrice: number; discount: number; subtotal: number;
  notes?: string; createdAt: string;
};

const STATUS_BADGES: Record<string, string> = {
  received: 'badge-blue', diagnosed: 'badge-amber', in_progress: 'badge-blue',
  awaiting_parts: 'badge-amber', completed: 'badge-green',
  delivered: 'badge-green', closed: 'badge-muted',
  rejected: 'badge-red', cancelled: 'badge-muted',
};

const STATUS_FLOW: Record<string, string[]> = {
  received: ['diagnosed', 'in_progress', 'rejected', 'cancelled'],
  diagnosed: ['in_progress', 'awaiting_parts', 'rejected', 'cancelled'],
  in_progress: ['awaiting_parts', 'completed', 'cancelled'],
  awaiting_parts: ['in_progress', 'cancelled'],
  completed: ['delivered'],
  delivered: ['closed'],
  closed: [], rejected: [], cancelled: [],
};

export default function Repairs() {
  const [rows, setRows] = useState<Repair[]>([]);
  const [page, setPage] = useState(1);
  const [pages, setPages] = useState(1);
  const [total, setTotal] = useState(0);
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [detail, setDetail] = useState<Repair | null>(null);
  const [transitionModal, setTransitionModal] = useState(false);
  const [nextStatus, setNextStatus] = useState('');
  const [diagnosis, setDiagnosis] = useState('');
  const [actualCost, setActualCost] = useState('');
  const [createModal, setCreateModal] = useState(false);
  const [createForm, setCreateForm] = useState({
    customerName: '', customerPhone: '', itemDescription: '', problemReported: '', priority: 'normal', estimatedCost: '0',
  });

  const load = useCallback(() => {
    setLoading(true); setError('');
    const q = qs({ page, limit: 50, search: search || undefined, status: status || undefined });
    api<any>(`/api/repairs?${q}`)
      .then(r => { setRows(r.data || []); setPages(r.pagination?.pages || 1); setTotal(r.pagination?.total ?? 0); })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  }, [page, search, status]);

  useEffect(() => { const t = setTimeout(() => void load(), 250); return () => clearTimeout(t); }, [load]);

  const openDetail = (r: Repair) => {
    api<any>(`/api/repairs/${r.id}`)
      .then(res => { setDetail(res.data); setDiagnosis(res.data.diagnosis || ''); setActualCost(String(res.data.actualCost || 0)); })
      .catch(e => setError(e.message));
  };

  const doTransition = async () => {
    if (!detail || !nextStatus) return;
    try {
      await api(`/api/repairs/${detail.id}/transition`, {
        method: 'POST', body: JSON.stringify({
          nextStatus, diagnosis: diagnosis || undefined, actualCost: Number(actualCost) || undefined,
        }),
      });
      setMessage(`Repair moved to ${nextStatus}`);
      setTransitionModal(false);
      openDetail(detail); load();
    } catch (e: any) { setError(e.message); }
  };

  const createRepair = async () => {
    try {
      const r = await api<Repair>('/api/repairs', {
        method: 'POST', body: JSON.stringify({
          customerName: createForm.customerName || 'Walk-in',
          customerPhone: createForm.customerPhone || undefined,
          itemDescription: createForm.itemDescription,
          problemReported: createForm.problemReported,
          priority: createForm.priority,
          estimatedCost: Number(createForm.estimatedCost) || 0,
        }),
      });
      setMessage(`Repair ${r.data?.repairNumber} created`);
      setCreateModal(false);
      setCreateForm({ customerName: '', customerPhone: '', itemDescription: '', problemReported: '', priority: 'normal', estimatedCost: '0' });
      load();
    } catch (e: any) { setError(e.message); }
  };

  return (
    <>
      <PageHeader title="Repairs & maintenance" subtitle="Track repair jobs from receipt to delivery."
        action={<div style={{ display: 'flex', gap: 8 }}>
          <Button variant="secondary" onClick={load}><RefreshCw size={16} /> Refresh</Button>
          <Button onClick={() => setCreateModal(true)}><Plus size={16} /> New repair</Button>
        </div>} />
      {error && <Notice message={error} onClose={() => setError('')} />}
      {message && <Notice kind="success" message={message} onClose={() => setMessage('')} />}

      <section className="panel" style={{ marginBottom: 16 }}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 200px', gap: 12, alignItems: 'end' }}>
          <SearchBox value={search} onChange={v => { setSearch(v); setPage(1); }} placeholder="Search by repair #, customer, or item…" />
          <Field label="Status">
            <select value={status} onChange={e => { setStatus(e.target.value); setPage(1); }}>
              <option value="">All statuses</option>
              <option value="received">Received</option>
              <option value="diagnosed">Diagnosed</option>
              <option value="in_progress">In progress</option>
              <option value="awaiting_parts">Awaiting parts</option>
              <option value="completed">Completed</option>
              <option value="delivered">Delivered</option>
              <option value="closed">Closed</option>
              <option value="rejected">Rejected</option>
              <option value="cancelled">Cancelled</option>
            </select>
          </Field>
        </div>
      </section>

      {loading ? <Loading /> : rows.length === 0 ? <Empty text="No repair jobs found" /> : (
        <section className="panel">
          <div className="table-wrap"><table>
            <thead><tr>
              <th>Repair #</th><th>Customer</th><th>Item</th>
              <th>Status</th><th>Priority</th><th>Received</th>
              <th>Est. cost</th><th>Action</th>
            </tr></thead>
            <tbody>
              {rows.map(r => (
                <tr key={r.id} style={{ cursor: 'pointer' }} onClick={() => openDetail(r)}>
                  <td><b>{r.repairNumber}</b></td>
                  <td>{r.customerName}<small>{r.customerPhone || ''}</small></td>
                  <td style={{ maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis' }}>{r.itemDescription}</td>
                  <td><span className={`badge ${STATUS_BADGES[r.status] || 'badge-muted'}`}>{r.status.replaceAll('_', ' ')}</span></td>
                  <td><span className={`badge ${r.priority === 'urgent' ? 'badge-red' : r.priority === 'high' ? 'badge-amber' : 'badge-muted'}`}>{r.priority}</span></td>
                  <td>{dateTime(r.receivedAt)}</td>
                  <td><b>{money(r.estimatedCost)}</b></td>
                  <td><Button variant="secondary" onClick={()=>openDetail(r)}>View</Button></td>
                </tr>
              ))}
            </tbody>
          </table></div>
          <Pagination page={page} pages={pages} total={total} onPage={setPage} />
        </section>
      )}

      {/* Detail modal */}
      {detail && (
        <Modal onClose={() => setDetail(null)} title={`Repair ${detail.repairNumber}`} wide>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 16 }}>
            <div><b>Customer:</b> {detail.customerName}<br /><small>{detail.customerEmail || ''} {detail.customerPhone || ''}</small></div>
            <div><b>Item:</b> {detail.itemDescription}<br /><small>Serial: {detail.itemSerial || '—'}</small></div>
            <div><b>Status:</b> <span className={`badge ${STATUS_BADGES[detail.status]}`}>{detail.status.replaceAll('_',' ')}</span></div>
            <div><b>Priority:</b> <span className={`badge ${detail.priority === 'urgent' ? 'badge-red' : 'badge-muted'}`}>{detail.priority}</span></div>
            <div><b>Received:</b> {dateTime(detail.receivedAt)}</div>
            <div><b>Technician:</b> {detail.technicianName || 'Unassigned'}</div>
          </div>

          <div style={{ padding: 12, background: '#f8fafc', borderRadius: 8, marginBottom: 16 }}>
            <b>Problem reported:</b><br />{detail.problemReported}
          </div>
          {detail.diagnosis && (
            <div style={{ padding: 12, background: '#fef3c7', borderRadius: 8, marginBottom: 16 }}>
              <b>Diagnosis:</b><br />{detail.diagnosis}
            </div>
          )}

          <div style={{ display: 'flex', gap: 12, marginBottom: 16 }}>
            <div style={{ flex: 1, padding: 12, background: '#f0fdf4', borderRadius: 8 }}>
              <b>Estimated cost:</b><br />{money(detail.estimatedCost)}
            </div>
            <div style={{ flex: 1, padding: 12, background: '#eff6ff', borderRadius: 8 }}>
              <b>Actual cost:</b><br />{money(detail.actualCost)}
            </div>
          </div>

          {detail.items && detail.items.length > 0 && (
            <div className="table-wrap" style={{ marginBottom: 16 }}><table>
              <thead><tr><th>Type</th><th>Description</th><th>Qty</th><th>Price</th><th>Subtotal</th></tr></thead>
              <tbody>
                {detail.items.map(it => (
                  <tr key={it.id}>
                    <td><span className="badge badge-muted">{it.lineType}</span></td>
                    <td>{it.description}</td>
                    <td>{it.quantity}</td>
                    <td>{money(it.unitPrice)}</td>
                    <td><b>{money(it.subtotal)}</b></td>
                  </tr>
                ))}
              </tbody>
            </table></div>
          )}

          {STATUS_FLOW[detail.status]?.length > 0 && (
            <div className="modal-footer">
              <Button onClick={() => {
                const next = STATUS_FLOW[detail.status][0];
                setNextStatus(next);
                setTransitionModal(true);
              }}><CheckCircle2 size={16} /> Advance to {STATUS_FLOW[detail.status][0].replaceAll('_',' ')}…</Button>
            </div>
          )}
        </Modal>
      )}

      {/* Transition modal */}
      {transitionModal && detail && (
        <Modal onClose={() => setTransitionModal(false)} title="Advance repair status">
          <Field label="Next status">
            <select value={nextStatus} onChange={e => setNextStatus(e.target.value)}>
              {STATUS_FLOW[detail.status]?.map(s => <option key={s} value={s}>{s.replaceAll('_',' ')}</option>)}
            </select>
          </Field>
          {(nextStatus === 'diagnosed' || nextStatus === 'completed') && (
            <Field label="Diagnosis / notes">
              <textarea value={diagnosis} onChange={e => setDiagnosis(e.target.value)} rows={3}
                placeholder="What was the diagnosis? What was done?" />
            </Field>
          )}
          {(nextStatus === 'completed' || nextStatus === 'delivered') && (
            <Field label="Actual cost (LKR)">
              <input type="number" min="0" step="0.01" value={actualCost}
                onChange={e => setActualCost(e.target.value)} />
            </Field>
          )}
          <div className="modal-footer">
            <Button variant="secondary" onClick={() => setTransitionModal(false)}>Cancel</Button>
            <Button onClick={doTransition}>Confirm</Button>
          </div>
        </Modal>
      )}

      {/* Create modal */}
      {createModal && (
        <Modal onClose={() => setCreateModal(false)} title="New repair job">
          <Field label="Customer name"><input value={createForm.customerName} onChange={e => setCreateForm({...createForm, customerName: e.target.value})} placeholder="Walk-in" /></Field>
          <Field label="Customer phone"><input value={createForm.customerPhone} onChange={e => setCreateForm({...createForm, customerPhone: e.target.value})} placeholder="+94…" /></Field>
          <Field label="Item description"><input value={createForm.itemDescription} onChange={e => setCreateForm({...createForm, itemDescription: e.target.value})} placeholder="e.g. Dell Latitude 5420 laptop" /></Field>
          <Field label="Problem reported"><textarea value={createForm.problemReported} onChange={e => setCreateForm({...createForm, problemReported: e.target.value})} rows={3} placeholder="Customer's description of the fault…" /></Field>
          <Field label="Priority">
            <select value={createForm.priority} onChange={e => setCreateForm({...createForm, priority: e.target.value})}>
              <option value="low">Low</option><option value="normal">Normal</option>
              <option value="high">High</option><option value="urgent">Urgent</option>
            </select>
          </Field>
          <Field label="Estimated cost (LKR)"><input type="number" min="0" step="0.01" value={createForm.estimatedCost} onChange={e => setCreateForm({...createForm, estimatedCost: e.target.value})} /></Field>
          <div className="modal-footer">
            <Button variant="secondary" onClick={() => setCreateModal(false)}>Cancel</Button>
            <Button onClick={createRepair} disabled={!createForm.itemDescription || !createForm.problemReported}>Create repair</Button>
          </div>
        </Modal>
      )}
    </>
  );
}
