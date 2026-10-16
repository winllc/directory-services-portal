export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly details?: Record<string, string>,
    /** Machine-readable reason, e.g. "license_required", "license_read_only", "feature_not_licensed". */
    readonly code?: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

type Method = 'GET' | 'POST' | 'PUT' | 'DELETE';

/** Called whenever the server reports the session is gone. */
let onUnauthorized: (() => void) | undefined;
export function setUnauthorizedHandler(fn: () => void) {
  onUnauthorized = fn;
}

/**
 * Called when the server refuses a request because of the license (403 license_required or
 * license_read_only): the license changed under the client, so its status is re-read and the
 * app routes to the license screen (administrators) or the "not licensed" page (everyone else).
 */
let onLicenseRefusal: ((code: string) => void) | undefined;
export function setLicenseRefusalHandler(fn: ((code: string) => void) | undefined) {
  onLicenseRefusal = fn;
}

async function request<T>(method: Method, url: string, body?: unknown, contentType = 'application/json'): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (method !== 'GET') headers['X-DSP-Request'] = '1';
  if (body !== undefined) headers['Content-Type'] = contentType;
  const res = await fetch(url, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : contentType === 'application/json' ? JSON.stringify(body) : (body as string),
  });
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  let data: unknown = undefined;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }
  if (!res.ok) {
    const err = (data ?? {}) as { error?: string; details?: Record<string, string>; code?: string };
    if (res.status === 401 && !url.endsWith('/auth/login')) onUnauthorized?.();
    if (res.status === 403 && (err.code === 'license_required' || err.code === 'license_read_only')) onLicenseRefusal?.(err.code);
    throw new ApiError(res.status, err.error ?? `Request failed (${res.status})`, err.details, err.code);
  }
  return data as T;
}

export const api = {
  get: <T>(url: string) => request<T>('GET', url),
  post: <T>(url: string, body?: unknown) => request<T>('POST', url, body ?? {}),
  put: <T>(url: string, body?: unknown) => request<T>('PUT', url, body ?? {}),
  del: <T = void>(url: string) => request<T>('DELETE', url),
  /** POST a text body (e.g. a license file) as text/plain. */
  postText: <T>(url: string, text: string) => request<T>('POST', url, text, 'text/plain; charset=utf-8'),
};

export const dnParam = (dn: string) => `dn=${encodeURIComponent(dn)}`;

export function qs(params: Record<string, string | number | undefined>): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== '') p.set(k, String(v));
  const s = p.toString();
  return s ? `?${s}` : '';
}
