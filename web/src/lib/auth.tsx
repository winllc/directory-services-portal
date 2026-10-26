import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Navigate, useLocation } from 'react-router-dom';
import type { SessionUser } from '@dsp/shared';
import { api, ApiError, setUnauthorizedHandler } from '../api/client';

interface AuthState {
  user: SessionUser | null;
  loading: boolean;
  login(username: string, password: string): Promise<void>;
  /** Sign in with the TLS client certificate the browser presented. */
  loginWithCertificate(): Promise<void>;
  /** Why automatic certificate sign-in failed, if it was attempted. */
  certificateError: string | null;
  /** Why the previous session ended (timed out), shown on the login page. */
  sessionNotice: string | null;
  /** The notice-and-consent banner must be acknowledged before signing in (the server enforces it). */
  consentRequired: boolean;
  acknowledgeConsent(): Promise<void>;
  logout(): Promise<void>;
}

interface MeResponse {
  user: SessionUser | null;
  certificateError?: string;
  consentRequired?: boolean;
}

const AuthContext = createContext<AuthState | null>(null);

/**
 * Leaving the portal for another site: signing out at the identity provider after signing out
 * here (a plain navigation, which the CSP's form-action does not govern). An object, so tests can
 * replace it.
 */
export const browser = {
  leave(url: string) {
    window.location.assign(url);
  },
};

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<SessionUser | null>(null);
  const [loading, setLoading] = useState(true);
  const [certificateError, setCertificateError] = useState<string | null>(null);
  const [consentRequired, setConsentRequired] = useState(false);
  const [sessionNotice, setSessionNotice] = useState<string | null>(null);
  const qc = useQueryClient();

  /** Reads the session: who is signed in, and whether the consent banner comes first. */
  const checkSession = useCallback(async () => {
    const r = await api.get<MeResponse>('/api/auth/me');
    setUser(r.user);
    setCertificateError(r.certificateError ?? null);
    setConsentRequired(!!r.consentRequired);
  }, []);

  useEffect(() => {
    let cancelled = false;
    checkSession()
      .catch((e) => {
        if (!(e instanceof ApiError && e.status === 401)) console.error(e);
      })
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [checkSession]);

  useEffect(() => {
    setUnauthorizedHandler((expired) => {
      if (expired) setSessionNotice(expired);
      setUser(null);
      qc.clear();
      // The session is gone (signed out elsewhere, or expired): a new one may need the banner first.
      checkSession().catch(() => undefined);
    });
  }, [qc, checkSession]);

  /** A sign-in the server refused because the banner was not acknowledged shows the banner. */
  const signIn = useCallback(
    async (fn: () => Promise<{ user: SessionUser }>) => {
      try {
        const r = await fn();
        qc.clear();
        setSessionNotice(null);
        setUser(r.user);
      } catch (e) {
        if (e instanceof ApiError && e.code === 'consent_required') setConsentRequired(true);
        throw e;
      }
    },
    [qc],
  );

  const login = useCallback(
    (username: string, password: string) => signIn(() => api.post<{ user: SessionUser }>('/api/auth/login', { username, password })),
    [signIn],
  );

  const loginWithCertificate = useCallback(async () => {
    await signIn(() => api.post<{ user: SessionUser }>('/api/auth/x509'));
    setCertificateError(null);
  }, [signIn]);

  const acknowledgeConsent = useCallback(async () => {
    await api.post('/api/auth/consent');
    // With certificate auto-login, the browser's certificate may now sign the user in.
    await checkSession();
  }, [checkSession]);

  const logout = useCallback(async () => {
    let next: string | undefined;
    try {
      // redirect: after a single sign-on, the provider's sign-out (the portal's session is over already).
      const r = await api.post<{ consentRequired?: boolean; redirect?: string }>('/api/auth/logout');
      setConsentRequired(!!r.consentRequired);
      next = r.redirect;
    } finally {
      qc.clear();
      setUser(null);
    }
    if (next && /^https?:\/\//i.test(next)) browser.leave(next);
  }, [qc]);

  const value = useMemo(
    () => ({ user, loading, login, loginWithCertificate, certificateError, sessionNotice, consentRequired, acknowledgeConsent, logout }),
    [user, loading, login, loginWithCertificate, certificateError, sessionNotice, consentRequired, acknowledgeConsent, logout],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}

/** The authenticated user; only use below <RequireAuth>. */
export function useUser(): SessionUser {
  const { user } = useAuth();
  if (!user) throw new Error('No authenticated user');
  return user;
}

/**
 * Navigation state of the page signing in leads to: the page remembered on the way to the sign-in
 * page (a link that was followed, or where the previous session ended, perhaps somebody else's).
 */
export const SIGNED_IN_STATE = { signedIn: true } as const;

/**
 * Whether this page was reached by signing in. A page the new account may not open is then not
 * shown as refused: the remembered page simply does not apply to it, so it starts at the home page.
 */
export function useJustSignedIn(): boolean {
  const location = useLocation();
  return (location.state as { signedIn?: boolean } | null)?.signedIn === true;
}

export function RequireAuth({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth();
  const location = useLocation();
  if (loading) return <div className="page-loading"><span className="spinner" /></div>;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  return <>{children}</>;
}

export function RequireAdmin({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const justSignedIn = useJustSignedIn();
  if (!user?.isAdmin) {
    if (justSignedIn) return <Navigate to="/" replace />;
    return (
      <div className="empty-state">
        <h2>Administrators only</h2>
        <p>You need administrator rights to view this page.</p>
      </div>
    );
  }
  return <>{children}</>;
}
