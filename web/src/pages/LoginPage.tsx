import { useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { Database, LogIn } from 'lucide-react';
import { useServerInfo } from '../api/hooks';
import { useAuth } from '../lib/auth';
import { Alert, Button, FormRow } from '../components/ui';

export function LoginPage() {
  const { user, login } = useAuth();
  const info = useServerInfo();
  const navigate = useNavigate();
  const location = useLocation();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const from = (location.state as { from?: string } | null)?.from ?? '/';

  if (user) return <Navigate to={from} replace />;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await login(username.trim(), password);
      navigate(from, { replace: true });
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login-page">
      <form className="login-card" onSubmit={submit}>
        <div className="login-brand">
          <div className="brand-logo brand-logo-lg">
            <Database size={24} />
          </div>
          <h1>Directory Services Portal</h1>
          <p className="muted">Sign in with your directory account</p>
        </div>
        {error && <Alert tone="danger">{error}</Alert>}
        {info.data && !info.data.connected && (
          <Alert tone="warning" title="Directory unreachable">
            {info.data.error}
          </Alert>
        )}
        <FormRow label="Username" htmlFor="username">
          <input id="username" className="input" autoComplete="username" autoFocus value={username} onChange={(e) => setUsername(e.target.value)} required />
        </FormRow>
        <FormRow label="Password" htmlFor="password">
          <input id="password" className="input" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        </FormRow>
        <Button type="submit" variant="primary" className="btn-block" loading={busy} disabled={!username || !password} icon={<LogIn size={16} />}>
          Sign in
        </Button>
        {info.data?.mode === 'memory' && (
          <div className="demo-hint">
            <strong>Demo directory.</strong> Sign in as <code>admin</code> (administrator), <code>alice</code>, <code>bob</code>, <code>erin</code> (HR), <code>dave</code> (helpdesk) or <code>frank</code> (partner). Password: <code>password</code>
          </div>
        )}
      </form>
    </div>
  );
}
