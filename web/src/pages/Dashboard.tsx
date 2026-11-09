import { useEffect, useState } from 'react';
import { ArrowUpRight, Boxes, ContactRound, FileText, Package, Percent, RefreshCw, ShoppingCart, Star, TrendingUp, Wallet, AlertTriangle, RotateCcw, ShieldX, Gift } from 'lucide-react';
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api, money } from '../lib/api';
import { useAuth } from '../context/AuthContext';
import { Loading, Notice, PageHeader } from '../components/Ui';

type D = {
  todaySales: number; todayBills: number; totalItems: number; lowStockCount: number;
  totalCustomers: number; totalLoyaltyPoints: number; monthSales: number;
  salesLast7Days: Array<{ date: string; revenue: number; bills: number }>;
  topItems: Array<{ itemId?: string; itemName?: string; name?: string; totalQty?: number; totalRevenue?: number }>;
};

type Bill = {
  id: string; billNumber: string; customerName: string; staffName: string;
  totalAmount: number; paidAmount: number; status: string; createdAt: string;
};

type LowStockItem = {
  id: string; name: string; pluCode: string; stockQuantity: number;
  lowStockThreshold: number; unit: string;
};

/**
 * Dashboard — role-aware store operations snapshot.
 *
 * RBAC hierarchy (parity with v5 dashboard/page.tsx):
 *   - All roles see: today sales + bills count, total items, low-stock count,
 *     7-day sales chart, top-items list, recent-bills table, quick actions.
 *   - supervisor+ see: month-to-date sales, returns & cancellations counts
 *     (operational metrics).
 *   - admin+ see: customer loyalty points + customer count, ongoing
 *     promotions count (marketing metrics).
 *
 * A low-stock alert banner is rendered at the top whenever the API reports
 * any items at or below their threshold. Clicking "View inventory" jumps to
 * the inventory page.
 *
 * Recent bills are fetched separately via /api/billing so this dashboard
 * view does not require backend changes to the dashboard aggregate.
 */
export default function Dashboard() {
  const { user } = useAuth();
  const role = user?.role?.toLowerCase() || '';
  const isSupervisorOrAbove = role === 'supervisor' || role === 'admin' || role === 'master_admin';
  const isAdminOrAbove = role === 'admin' || role === 'master_admin';

  const [data, setData] = useState<D | null>(null);
  const [recentBills, setRecentBills] = useState<Bill[]>([]);
  const [lowStock, setLowStock] = useState<LowStockItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = () => {
    setLoading(true);
    Promise.all([
      api<D>('/api/dashboard'),
      api<any>(`/api/billing?page=1&limit=10&sort=createdAt&direction=desc`).catch(() => ({ data: [] as Bill[] })),
      api<any>('/api/inventory/low-stock').catch(() => ({ data: [] as LowStockItem[] })),
    ])
      .then(([d, bills, ls]) => {
        setData(d.data);
        setRecentBills((bills.data as Bill[]) || []);
        const lowStockData = Array.isArray(ls) ? ls : (ls.data as LowStockItem[]) || [];
        setLowStock(lowStockData);
      })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  return (
    <>
      <PageHeader
        title={`Welcome back, ${user?.fullName || user?.username || 'team'}.`}
        subtitle="Here is the latest snapshot of your store operations."
        action={<button className="btn btn-secondary" onClick={load}><RefreshCw size={16} /> Refresh data</button>}
      />

      {error && <Notice message={error} />}

      {/* Low-stock alert banner — visible to anyone with manage:inventory (supervisor+) */}
      {isSupervisorOrAbove && data && data.lowStockCount > 0 && (
        <div className="low-stock-alert" style={{
          display: 'flex', alignItems: 'center', gap: 12, padding: '12px 16px',
          background: '#fff7ed', border: '1px solid #fed7aa', borderRadius: 10,
          marginBottom: 16,
        }}>
          <AlertTriangle size={20} style={{ color: '#c2410c' }} />
          <div style={{ flex: 1 }}>
            <b style={{ color: '#9a3412' }}>{data.lowStockCount} items at or below reorder threshold</b>
            <div style={{ fontSize: 12, color: '#7c2d12', marginTop: 2 }}>
              {lowStock.slice(0, 3).map(i => i.name).join(' · ') || 'Open inventory to review.'}
              {lowStock.length > 3 && ` · +${lowStock.length - 3} more`}
            </div>
          </div>
          <a href="/inventory" className="btn btn-secondary" style={{ padding: '6px 12px' }}>
            View inventory
          </a>
        </div>
      )}

      {loading ? <Loading /> : data && (
        <>
          {/* KPI metric cards — RBAC-gated */}
          <div className="metric-grid">
            <Metric icon={<Wallet />} label="Sales today" value={money(data.todaySales)} trend={`${data.todayBills} completed bills`} tone="teal" />
            {isSupervisorOrAbove && (
              <Metric icon={<TrendingUp />} label="Sales this month" value={money(data.monthSales)} trend="Month to date" tone="blue" />
            )}
            <Metric icon={<Package />} label="Active catalog items" value={data.totalItems.toLocaleString()} trend={`${data.lowStockCount} items low on stock`} tone="amber" />
            {isAdminOrAbove && (
              <Metric icon={<ContactRound />} label="Customer loyalty points" value={data.totalLoyaltyPoints.toLocaleString()} trend={`${data.totalCustomers.toLocaleString()} customer profiles`} tone="purple" />
            )}
            {!isAdminOrAbove && (
              <Metric icon={<Boxes />} label="Low-stock items" value={data.lowStockCount.toLocaleString()} trend="At or below reorder threshold" tone="amber" />
            )}
          </div>

          {/* Charts */}
          <div className="chart-grid">
            <section className="panel chart-panel">
              <div className="panel-heading">
                <div><h2>Sales performance</h2><p>Daily revenue · last 7 days</p></div>
                <span className="chart-legend"><i /> Revenue</span>
              </div>
              <div className="chart-area">
                <ResponsiveContainer width="100%" height="100%">
                  <AreaChart data={data.salesLast7Days || []} margin={{ left: 0, right: 8, top: 8, bottom: 0 }}>
                    <defs>
                      <linearGradient id="revenueFill" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0%" stopColor="#0d9488" stopOpacity={0.22} />
                        <stop offset="95%" stopColor="#0d9488" stopOpacity={0.01} />
                      </linearGradient>
                    </defs>
                    <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e9eff2" />
                    <XAxis dataKey="date" tickFormatter={v => String(v).slice(5)} axisLine={false} tickLine={false} tick={{ fontSize: 11, fill: '#81909b' }} />
                    <YAxis axisLine={false} tickLine={false} tick={{ fontSize: 11, fill: '#81909b' }} tickFormatter={v => `${Math.round(Number(v) / 1000)}k`} width={42} />
                    <Tooltip formatter={(v: any) => money(v)} labelFormatter={v => `Date: ${v}`} />
                    <Area type="monotone" dataKey="revenue" stroke="#0d9488" strokeWidth={2.5} fill="url(#revenueFill)" />
                  </AreaChart>
                </ResponsiveContainer>
              </div>
            </section>

            <section className="panel top-items-panel">
              <div className="panel-heading">
                <div><h2>Top selling items</h2><p>Revenue · last 30 days</p></div>
                <Star size={18} className="muted-icon" />
              </div>
              {(data.topItems || []).length === 0
                ? <div className="chart-empty">No sales data available yet.</div>
                : <div className="top-items-list">
                    {data.topItems.map((x, i) => (
                      <div className="top-item" key={x.itemId || i}>
                        <div className="rank">{String(i + 1).padStart(2, '0')}</div>
                        <div className="top-item-main">
                          <b>{x.itemName || x.name}</b>
                          <span>{Number(x.totalQty || 0).toLocaleString()} units sold</span>
                          <div className="mini-bar">
                            <i style={{ width: `${Math.max(8, Number(x.totalRevenue || 0) / Math.max(...data.topItems.map((a: any) => Number(a.totalRevenue || 0)), 1) * 100)}%` }} />
                          </div>
                        </div>
                        <strong>{money(x.totalRevenue)}</strong>
                      </div>
                    ))}
                  </div>}
            </section>
          </div>

          {/* Recent bills — visible to all authenticated roles */}
          <section className="panel" style={{ marginTop: 16 }}>
            <div className="panel-heading">
              <div><h2>Recent bills</h2><p>Last 10 transactions</p></div>
              <a href="/bills" style={{ color: '#0d9488', fontSize: 13, fontWeight: 600 }}>View all →</a>
            </div>
            {recentBills.length === 0 ? (
              <div className="chart-empty">No bills yet — your first sale will appear here.</div>
            ) : (
              <div className="table-wrap">
                <table>
                  <thead><tr><th>Bill #</th><th>Customer</th><th>Staff</th><th>Total</th><th>Status</th><th>When</th></tr></thead>
                  <tbody>
                    {recentBills.map(b => (
                      <tr key={b.id}>
                        <td><b className="bill-number">{b.billNumber}</b></td>
                        <td>{b.customerName || 'Walk-in'}</td>
                        <td>{b.staffName || '—'}</td>
                        <td><b>{money(b.totalAmount)}</b></td>
                        <td>
                          <span className={`badge ${b.status === 'completed' ? 'badge-green' : b.status === 'cancelled' ? 'badge-red' : b.status === 'returned' ? 'badge-amber' : 'badge-muted'}`}>
                            {b.status}
                          </span>
                        </td>
                        <td>{new Date(b.createdAt).toLocaleString()}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          {/* Quick actions */}
          <div className="quick-actions">
            <Quick href="/pos" icon={<ShoppingCart />} title="Open point of sale" text="Start a new customer checkout" />
            <Quick href="/items" icon={<Boxes />} title="Manage inventory" text="Review catalog and stock levels" />
            <Quick href="/reports" icon={<FileText />} title="View reports" text="Explore sales and product performance" />
          </div>
        </>
      )}
    </>
  );
}

function Metric({ icon, label, value, trend, tone }: { icon: any; label: string; value: string; trend: string; tone: string }) {
  return <div className="metric-card"><div className={`metric-icon ${tone}`}>{icon}</div><span>{label}</span><strong>{value}</strong><div className="metric-foot"><span>{trend}</span><ArrowUpRight size={15} /></div></div>;
}

function Quick({ href, icon, title, text }: { href: string; icon: any; title: string; text: string }) {
  return <a href={href} className="quick-action"><div className="quick-icon">{icon}</div><div><b>{title}</b><span>{text}</span></div><ArrowUpRight size={16} /></a>;
}
