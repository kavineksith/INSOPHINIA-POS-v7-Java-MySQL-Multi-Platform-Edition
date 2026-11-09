import { useEffect, useState } from 'react';
import { Download, FilterX, RefreshCw, FileSpreadsheet, FileText, FileCode } from 'lucide-react';
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api, money, qs } from '../lib/api';
import { Button, Empty, Field, Loading, Notice, PageHeader } from '../components/Ui';

type StaffUser = { id: string; fullName?: string; firstName?: string; lastName?: string; username?: string };

/**
 * Reports — sales-by-day + item-performance with exportable artifacts.
 *
 * Parity with v5 reports/page.tsx:
 *   - Date-range filter (from / to)
 *   - Staff filter — fetches the user list and narrows sales to a single staff
 *   - Bill status filter (all / completed / returned / cancelled)
 *   - CSV export (already present in earlier Vite version)
 *   - PDF export via jspdf + jspdf-autotable (new)
 *   - JSON export for spreadsheet-less workflows (new)
 *
 * Server endpoints used:
 *   - GET /api/reports/sales?from=&to=&staffId=&status=
 *   - GET /api/reports/items?from=&to=
 *   - GET /api/users?limit=200 (for the staff dropdown)
 */
export default function Reports() {
  const [from, setFrom] = useState(() => new Date(Date.now() - 29 * 86400000).toISOString().slice(0, 10));
  const [to, setTo] = useState(() => new Date().toISOString().slice(0, 10));
  const [staffId, setStaffId] = useState(''); // '' = all staff
  const [status, setStatus] = useState(''); // '' = all statuses
  const [staff, setStaff] = useState<StaffUser[]>([]);
  const [sales, setSales] = useState<any[]>([]);
  const [items, setItems] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState('');

  // Fetch the staff list once for the dropdown.
  useEffect(() => {
    api<{ data?: StaffUser[] }>('/api/users?limit=200')
      .then(r => setStaff(r.data?.data || []))
      .catch(() => { /* non-fatal — staff filter stays empty */ });
  }, []);

  const buildQuery = () => qs({
    from: new Date(`${from}T00:00:00Z`).toISOString(),
    to: new Date(`${to}T23:59:59Z`).toISOString(),
    staffId: staffId || undefined,
    status: status || undefined,
  });

  const load = () => {
    setLoading(true);
    setError('');
    const q = buildQuery();
    Promise.all([
      api<any[]>(`/api/reports/sales?${q}`),
      api<any[]>(`/api/reports/items?${q}`),
    ])
      .then(([a, b]) => { setSales(a.data || []); setItems(b.data || []); })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  const total = sales.reduce((s, r) => s + Number(r.revenue || 0), 0);
  const count = sales.reduce((s, r) => s + Number(r.bills || 0), 0);
  const discount = sales.reduce((s, r) => s + Number(r.discount || 0), 0);
  const avg = count > 0 ? total / count : 0;

  // ---- Exports -----------------------------------------------------------

  const exportCsv = () => {
    const csv = 'Date,Bills,Revenue,Discount\n' + sales.map(r =>
      [r.date, r.bills, r.revenue, r.discount].map(v => `"${String(v ?? '').replaceAll('"', '""')}"`).join(',')
    ).join('\n');
    downloadBlob(csv, `insophinia-sales-${from}_${to}.csv`, 'text/csv;charset=utf-8');
  };

  const exportJson = async () => {
    setExporting(true);
    try {
      // Re-fetch with the same filter (the data we have may be chart-aggregated;
      // for JSON we want the full report payload).
      const r = await api<any>(`/api/reports/sales?${buildQuery()}`);
      const payload = {
        generatedAt: new Date().toISOString(),
        filter: { from, to, staffId: staffId || null, status: status || null },
        summary: { totalRevenue: total, totalBills: count, totalDiscount: discount, avgBill: avg },
        daily: r.data || sales,
      };
      downloadBlob(JSON.stringify(payload, null, 2), `insophinia-sales-${from}_${to}.json`, 'application/json');
    } catch (e: any) {
      setError(e.message);
    } finally {
      setExporting(false);
    }
  };

  const exportPdf = async () => {
    setExporting(true);
    try {
      // Lazy-load jspdf + autotable to keep the main bundle small.
      const [{ jsPDF }, autoTableMod] = await Promise.all([
        import('jspdf'),
        import('jspdf-autotable'),
      ]);
      const autoTable = (autoTableMod as any).default || (autoTableMod as any).autoTable;
      const doc = new jsPDF({ orientation: 'portrait', unit: 'pt', format: 'a4' });

      // Header
      doc.setFontSize(20);
      doc.text('INSOPHINIA POS — Sales Report', 40, 48);
      doc.setFontSize(10);
      doc.setTextColor(100);
      doc.text(`Period: ${from} to ${to}`, 40, 66);
      const staffLabel = staffId
        ? staff.find(s => s.id === staffId)?.fullName || staff.find(s => s.id === staffId)?.username || staffId
        : 'All staff';
      doc.text(`Staff: ${staffLabel}`, 40, 80);
      doc.text(`Status filter: ${status || 'All'}`, 40, 94);
      doc.setTextColor(0);
      doc.setFontSize(13);
      doc.text(`Total revenue: ${money(total)}    Bills: ${count}    Avg / bill: ${money(avg)}    Discounts: ${money(discount)}`, 40, 116);

      // Daily sales table
      autoTable(doc, {
        startY: 134,
        head: [['Date', 'Bills', 'Revenue', 'Discounts']],
        body: sales.map(r => [r.date, r.bills, money(r.revenue), money(r.discount)]),
        headStyles: { fillColor: [13, 118, 110], textColor: 255 },
        styles: { fontSize: 9, cellPadding: 4 },
        columnStyles: { 0: { cellWidth: 100 }, 1: { cellWidth: 60, halign: 'right' }, 2: { halign: 'right' }, 3: { halign: 'right' } },
      });

      // Item performance table on the next page.
      const afterSales = (doc as any).lastAutoTable?.finalY || 200;
      if (afterSales > 700) doc.addPage();
      doc.setFontSize(13);
      doc.text('Item performance', 40, ((doc as any).lastAutoTable?.finalY || 200) + 24);
      autoTable(doc, {
        startY: ((doc as any).lastAutoTable?.finalY || 200) + 32,
        head: [['Item', 'PLU', 'Qty', 'Revenue']],
        body: items.map((r: any) => [r.itemName, r.pluCode, String(r.totalQty), money(r.totalRevenue)]),
        headStyles: { fillColor: [13, 118, 110], textColor: 255 },
        styles: { fontSize: 9, cellPadding: 4 },
        columnStyles: { 0: { cellWidth: 200 }, 1: { cellWidth: 80 }, 2: { cellWidth: 60, halign: 'right' }, 3: { halign: 'right' } },
      });

      doc.save(`insophinia-sales-${from}_${to}.pdf`);
    } catch (e: any) {
      setError(`PDF export failed: ${e.message}`);
    } finally {
      setExporting(false);
    }
  };

  const downloadBlob = (content: string, filename: string, mime: string) => {
    const blob = new Blob([content], { type: mime });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = filename;
    a.click();
    URL.revokeObjectURL(a.href);
  };

  const reset = () => {
    setFrom(new Date(Date.now() - 29 * 86400000).toISOString().slice(0, 10));
    setTo(new Date().toISOString().slice(0, 10));
    setStaffId('');
    setStatus('');
  };

  return (
    <>
      <PageHeader
        title="Reports & analytics"
        subtitle="Sales by day and top-performing items. Filter by staff and bill status; export to CSV, PDF, or JSON."
        action={
          <div style={{ display: 'flex', gap: 8 }}>
            <Button variant="secondary" onClick={exportCsv} disabled={loading || sales.length === 0}><FileSpreadsheet size={16} /> CSV</Button>
            <Button variant="secondary" onClick={() => void exportPdf()} disabled={loading || exporting}><FileText size={16} /> PDF</Button>
            <Button variant="secondary" onClick={() => void exportJson()} disabled={loading || exporting}><FileCode size={16} /> JSON</Button>
          </div>
        }
      />

      {error && <Notice message={error} />}

      <section className="panel report-filter" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12, alignItems: 'end' }}>
        <Field label="Start date"><input type="date" value={from} onChange={e => setFrom(e.target.value)} /></Field>
        <Field label="End date"><input type="date" value={to} onChange={e => setTo(e.target.value)} /></Field>
        <Field label="Staff member">
          <select value={staffId} onChange={e => setStaffId(e.target.value)}>
            <option value="">All staff</option>
            {staff.map(u => (
              <option key={u.id} value={u.id}>
                {u.fullName || [u.firstName, u.lastName].filter(Boolean).join(' ') || u.username}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Bill status">
          <select value={status} onChange={e => setStatus(e.target.value)}>
            <option value="">All statuses</option>
            <option value="completed">Completed</option>
            <option value="returned">Returned</option>
            <option value="cancelled">Cancelled</option>
          </select>
        </Field>
        <Button onClick={load} disabled={loading}><RefreshCw size={16} /> Run report</Button>
        <Button variant="secondary" onClick={reset}><FilterX size={16} /> Clear</Button>
      </section>

      {loading ? <Loading /> : (
        <>
          <div className="metric-grid three">
            <div className="metric-card"><span>Revenue</span><strong>{money(total)}</strong><div className="metric-foot"><span>Selected date range</span></div></div>
            <div className="metric-card"><span>Completed bills</span><strong>{count.toLocaleString()}</strong><div className="metric-foot"><span>Daily aggregation</span></div></div>
            <div className="metric-card"><span>Items sold</span><strong>{items.reduce((s, r) => s + Number(r.totalQty || 0), 0).toLocaleString()}</strong><div className="metric-foot"><span>From item performance report</span></div></div>
          </div>

          <div className="chart-grid">
            <section className="panel chart-panel">
              <div className="panel-heading"><div><h2>Daily sales</h2><p>Revenue per business day</p></div></div>
              {sales.length === 0 ? <Empty /> : (
                <div className="chart-area">
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={sales}>
                      <CartesianGrid strokeDasharray="3 3" vertical={false} />
                      <XAxis dataKey="date" tick={{ fontSize: 11 }} />
                      <YAxis tick={{ fontSize: 11 }} />
                      <Tooltip formatter={(v: any) => money(v)} />
                      <Bar dataKey="revenue" fill="#0d9488" radius={[5, 5, 0, 0]} />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              )}
            </section>
            <section className="panel">
              <div className="panel-heading"><div><h2>Item performance</h2><p>Revenue by item — bar chart + table</p></div></div>
              {items.length === 0 ? <Empty /> : (
                <>
                  <div className="chart-area" style={{ height: 280, marginBottom: 16 }}>
                    <ResponsiveContainer width="100%" height="100%">
                      <BarChart data={items.slice(0, 10)} layout="vertical" margin={{ left: 8, right: 32, top: 8, bottom: 0 }}>
                        <CartesianGrid strokeDasharray="3 3" horizontal={false} />
                        <XAxis type="number" tick={{ fontSize: 11 }} tickFormatter={(v: any) => `${Math.round(Number(v) / 1000)}k`} />
                        <YAxis type="category" dataKey="itemName" width={140} tick={{ fontSize: 11 }} />
                        <Tooltip formatter={(v: any) => money(v)} />
                        <Bar dataKey="totalRevenue" fill="#0d9488" radius={[0, 5, 5, 0]} />
                      </BarChart>
                    </ResponsiveContainer>
                  </div>
                  <div className="table-wrap"><table>
                    <thead><tr><th>Item</th><th>Qty</th><th>Revenue</th></tr></thead>
                    <tbody>
                      {items.map((r: any) => (
                        <tr key={r.itemId || r.pluCode}>
                          <td><b>{r.itemName}</b><small>{r.pluCode}</small></td>
                          <td>{r.totalQty}</td>
                          <td><b>{money(r.totalRevenue)}</b></td>
                        </tr>
                      ))}
                    </tbody>
                  </table></div>
                </>
              )}
            </section>
          </div>

          <section className="panel" style={{ marginTop: 16 }}>
            <div className="panel-heading"><div><h2>Daily breakdown</h2><p>Sales totals by date</p></div></div>
            {sales.length === 0 ? <Empty /> : (
              <div className="table-wrap"><table>
                <thead><tr><th>Date</th><th>Bills</th><th>Revenue</th><th>Discounts</th></tr></thead>
                <tbody>
                  {sales.map((r: any) => (
                    <tr key={r.date}>
                      <td>{r.date}</td>
                      <td>{r.bills}</td>
                      <td>{money(r.revenue)}</td>
                      <td>{money(r.discount)}</td>
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
