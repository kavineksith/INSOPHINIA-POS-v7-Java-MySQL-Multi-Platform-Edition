const BASE = (import.meta.env.VITE_API_BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
export type ApiResult<T> = { success: boolean; data: T; message?: string; pagination?: {page:number;limit:number;total:number;pages:number} };
export class ApiError extends Error { status: number; constructor(message: string, status: number) { super(message); this.status = status; } }
export function getToken() { return sessionStorage.getItem('pos_token') || ''; }
export async function api<T = any>(path: string, init: RequestInit = {}): Promise<ApiResult<T>> {
  const headers = new Headers(init.headers || {}); headers.set('Accept','application/json');
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type','application/json');
  const token = getToken(); if (token) headers.set('Authorization', `Bearer ${token}`);
  const res = await fetch(`${BASE}${path}`, { ...init, headers });
  const raw = await res.text(); let data: any = {};
  try { data = raw ? JSON.parse(raw) : {}; } catch { data = { message: raw || res.statusText }; }
  if (!res.ok || data.success === false) {
    if (res.status === 401) { sessionStorage.removeItem('pos_token'); sessionStorage.removeItem('pos_user'); window.dispatchEvent(new Event('pos:unauthorized')); }
    throw new ApiError(data.message || data.error || `Request failed (${res.status})`, res.status);
  }
  return data;
}
export function apiUrl(path: string) { return `${BASE}${path}`; }
export function money(value: unknown) { return new Intl.NumberFormat('en-LK',{style:'currency',currency:'LKR',maximumFractionDigits:2}).format(Number(value || 0)); }
export function dateTime(value?: string) { return value ? new Date(value).toLocaleString() : '—'; }
export function qs(params: Record<string, string | number | undefined | null>) { const q = new URLSearchParams(); Object.entries(params).forEach(([k,v])=>{if(v!==undefined&&v!==null&&String(v).trim()!=='')q.set(k,String(v));}); return q.toString(); }
