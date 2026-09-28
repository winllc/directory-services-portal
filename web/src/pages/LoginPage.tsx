import { useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { Database, IdCard, LogIn } from 'lucide-react';
import { useServerInfo } from '../api/hooks';
import { useAuth } from '../lib/auth';
import { Alert, Button, FormRow } from '../components/ui';

export function LoginPage() {
  const { user, login, loginWithCertificate, certificateError } = useAuth();
  const info = useServerInfo();
  const navigate = useNavigate();
  const location = useLocation();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<'password' | 'certificate' | null>(null);
  const from = (location.state as { from?: string } | null)?.from ?? '/';
  const auth = info.data?.auth ?? { password: true, x509: false, x509AutoLogin: false };

  if (user) return <Navigate to={from} replace />;

  const run = async (kind: 'password' | 'certificate', fn: () => Promise<void>) => {
    setError(null);
    setBusy(kind);
    try {
      await fn();
      navigate(from, { replace: true });
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(null);
    }
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    run('password', () => login(username.trim(), password));
  };

  return (
    <div className="login-page">
      <form className="login-card" onSubmit={submit}>
        <div className="login-brand">
          <div className="brand-logo brand-logo-lg">
            <Database size={24} />
          </div>
          <h1>Directory Services Portal</h1>
          <p className="muted">
            {auth.password && auth.x509
              ? 'Sign in with your certificate or directory account'
              : auth.x509
                ? 'Sign in with your certificate'
                : 'Sign in with your directory account'}
          </p>
        </div>
        {error && <Alert tone="danger">{error}</Alert>}
        {!error && certificateError && auth.x509 && <Alert tone="warning" title="Certificate not accepted">{certificateError}</Alert>}
        {info.data && !info.data.connected && (
          <Alert tone="warning" title="Directory unreachable">
            {info.data.error}
          </Alert>
        )}

        {auth.x509 && (
          <Button
            variant={auth.password ? 'secondary' : 'primary'}
            className="btn-block"
            icon={<IdCard size={16} />}
            loading={busy === 'certificate'}
            disabled={busy !== null}
            onClick={() => run('certificate', loginWithCertificate)}
          >
            Sign in with certificate
          </Button>
        )}
        {auth.x509 && auth.password && (
          <div className="login-divider">
            <span>or</span>
          </div>
        )}

        {auth.password && (
          <>
            <FormRow label="Username" htmlFor="username">
              <input id="username" className="input" autoComplete="username" autoFocus value={username} onChange={(e) => setUsername(e.target.value)} required />
            </FormRow>
            <FormRow label="Password" htmlFor="password">
              <input id="password" className="input" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
            </FormRow>
            <Button type="submit" variant="primary" className="btn-block" loading={busy === 'password'} disabled={!username || !password || busy !== null} icon={<LogIn size={16} />}>
              Sign in
            </Button>
          </>
        )}
        {info.data?.loginHint && <div className="demo-hint">{info.data.loginHint}</div>}
      </form>
    </div>
  );
}
