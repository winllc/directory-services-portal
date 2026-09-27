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
  logout(): Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<SessionUser | null>(null);
  const [loading, setLoading] = useState(true);
  const [certificateError, setCertificateError] = useState<string | null>(null);
  const qc = useQueryClient();

  useEffect(() => {
    let cancelled = false;
    api
      .get<{ user: SessionUser | null; certificateError?: string }>('/api/auth/me')
      .then((r) => {
        if (cancelled) return;
        setUser(r.user);
        setCertificateError(r.certificateError ?? null);
      })
      .catch((e) => {
        if (!(e instanceof ApiError && e.status === 401)) console.error(e);
      })
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    setUnauthorizedHandler(() => {
      setUser(null);
      qc.clear();
    });
  }, [qc]);

  const login = useCallback(
    async (username: string, password: string) => {
      const r = await api.post<{ user: SessionUser }>('/api/auth/login', { username, password });
      qc.clear();
      setUser(r.user);
    },
    [qc],
  );

  const loginWithCertificate = useCallback(async () => {
    const r = await api.post<{ user: SessionUser }>('/api/auth/x509');
    qc.clear();
    setCertificateError(null);
    setUser(r.user);
  }, [qc]);

  const logout = useCallback(async () => {
    try {
      await api.post('/api/auth/logout');
    } finally {
      qc.clear();
      setUser(null);
    }
  }, [qc]);

  const value = useMemo(
    () => ({ user, loading, login, loginWithCertificate, certificateError, logout }),
    [user, loading, login, loginWithCertificate, certificateError, logout],
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

export function RequireAuth({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth();
  const location = useLocation();
  if (loading) return <div className="page-loading"><span className="spinner" /></div>;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  return <>{children}</>;
}

export function RequireAdmin({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  if (!user?.isAdmin) {
    return (
      <div className="empty-state">
        <h2>Administrators only</h2>
        <p>You need administrator rights to view this page.</p>
      </div>
    );
  }
  return <>{children}</>;
}
