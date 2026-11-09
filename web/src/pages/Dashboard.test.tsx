import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import Dashboard from './Dashboard';

// Mock AuthContext and api — both factories need vi.hoisted because vi.mock
// is hoisted to the top of the file (above the const declarations).
const { useAuthMock, apiMock } = vi.hoisted(() => ({
  useAuthMock: vi.fn(),
  apiMock: vi.fn(),
}));

vi.mock('../context/AuthContext', () => ({ useAuth: () => useAuthMock() }));
vi.mock('../lib/api', () => ({
  api: apiMock,
  money: (v: any) => `Rs. ${Number(v || 0).toFixed(2)}`,
}));

function makeDashboardResponse(overrides: Partial<any> = {}) {
  return {
    todaySales: 4500, todayBills: 12, totalItems: 38, lowStockCount: 2,
    totalCustomers: 28, totalLoyaltyPoints: 1500, monthSales: 95000,
    salesLast7Days: [{ date: '2026-10-15', revenue: 4000, bills: 10 }],
    topItems: [{ itemId: '11111111-1111-1111-1111-111111111111', itemName: 'Demo Item', totalQty: 5, totalRevenue: 600 }],
    ...overrides,
  };
}

function renderDashboard(role: string) {
  useAuthMock.mockReturnValue({
    user: {
      userId: '1', username: 'tester', role, fullName: 'Test User',
      permissions: ['view:dashboard'], mustChangePassword: false,
    },
  });
  apiMock.mockReset();
  apiMock
    .mockResolvedValueOnce({ data: makeDashboardResponse() }) // /api/dashboard
    .mockResolvedValueOnce({ data: [] }) // /api/billing
    .mockResolvedValueOnce({ data: [] }); // /api/inventory/low-stock
  return render(<MemoryRouter><Dashboard /></MemoryRouter>);
}

describe('Dashboard RBAC rendering', () => {
  beforeEach(() => {
    useAuthMock.mockReset();
    apiMock.mockReset();
  });

  it('shows customer loyalty card to admin role', async () => {
    renderDashboard('admin');
    await waitFor(() => {
      expect(screen.getByText('Customer loyalty points')).toBeInTheDocument();
      expect(screen.getByText('Sales this month')).toBeInTheDocument();
    });
  });

  it('shows customer loyalty card to master_admin role', async () => {
    renderDashboard('master_admin');
    await waitFor(() => {
      expect(screen.getByText('Customer loyalty points')).toBeInTheDocument();
    });
  });

  it('hides customer loyalty card from supervisor role', async () => {
    renderDashboard('supervisor');
    await waitFor(() => {
      expect(screen.getByText('Sales this month')).toBeInTheDocument();
      expect(screen.queryByText('Customer loyalty points')).not.toBeInTheDocument();
    });
  });

  it('hides customer loyalty card AND month sales from cashier role', async () => {
    renderDashboard('cashier');
    await waitFor(() => {
      expect(screen.queryByText('Customer loyalty points')).not.toBeInTheDocument();
      expect(screen.queryByText('Sales this month')).not.toBeInTheDocument();
      expect(screen.getByText('Low-stock items')).toBeInTheDocument();
    });
  });

  it('renders the low-stock alert banner only for supervisor+', async () => {
    const { rerender } = renderDashboard('cashier');
    await waitFor(() => {
      // banner text — should not be present for cashier
      expect(screen.queryByText(/items at or below reorder threshold/i)).not.toBeInTheDocument();
    });

    // Re-render for supervisor — should now see the banner
    useAuthMock.mockReturnValue({
      user: { userId: '2', username: 'sup', role: 'supervisor', fullName: 'Sup', permissions: ['view:dashboard'], mustChangePassword: false },
    });
    apiMock.mockResolvedValueOnce({ data: makeDashboardResponse({ lowStockCount: 3 }) });
    apiMock.mockResolvedValueOnce({ data: [] });
    apiMock.mockResolvedValueOnce({ data: [{ id: '1', name: 'Low Item', pluCode: 'DEMO-0001', stockQuantity: 2, lowStockThreshold: 5, unit: 'pcs' }] });
    rerender(<MemoryRouter><Dashboard /></MemoryRouter>);
    await waitFor(() => {
      expect(screen.getByText(/items at or below reorder threshold/i)).toBeInTheDocument();
    });
  });

  it('renders the recent-bills section', async () => {
    useAuthMock.mockReturnValue({
      user: { userId: '3', username: 'cashier', role: 'cashier', fullName: 'Cashier', permissions: ['view:dashboard'], mustChangePassword: false },
    });
    apiMock.mockResolvedValueOnce({ data: makeDashboardResponse() });
    apiMock.mockResolvedValueOnce({
      data: [{
        id: 'bill-1', billNumber: 'BILL-20261018-0001', customerName: 'Alice',
        staffName: 'cashier1', totalAmount: 950, paidAmount: 1000, status: 'completed',
        createdAt: '2026-10-18T10:00:00Z',
      }],
    });
    apiMock.mockResolvedValueOnce({ data: [] });
    render(<MemoryRouter><Dashboard /></MemoryRouter>);
    await waitFor(() => {
      expect(screen.getByText('Recent bills')).toBeInTheDocument();
      expect(screen.getByText('BILL-20261018-0001')).toBeInTheDocument();
      expect(screen.getByText('Alice')).toBeInTheDocument();
    });
  });
});
