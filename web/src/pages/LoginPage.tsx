import { useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { Database, IdCard, KeyRound, LogIn } from 'lucide-react';
import { useServerInfo } from '../api/hooks';
import { SIGNED_IN_STATE, useAuth } from '../lib/auth';
import { Alert, Button, FormRow, usePageTitle } from '../components/ui';
import { ConsentNotice } from '../components/ConsentNotice';

export function LoginPage() {
  const { user, login, loginWithCertificate, certificateError, sessionNotice, consentRequired, acknowledgeConsent } = useAuth();
  const info = useServerInfo();
  const navigate = useNavigate();
  const location = useLocation();
  usePageTitle('Sign in');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<'password' | 'certificate' | null>(null);
  const from = (location.state as { from?: string } | null)?.from ?? '/';
  const auth = info.data?.auth ?? { password: true, x509: false, x509AutoLogin: false };
  const sso = auth.sso ?? [];
  // A single sign-on the server refused comes back here as ?sso_error=<code>.
  const ssoError = ssoErrorMessage(new URLSearchParams(location.search).get('sso_error'));

  if (user) return <Navigate to={from} replace state={SIGNED_IN_STATE} />;
  // The notice-and-consent banner comes before any sign-in.
  if (consentRequired && !info.data) return <div className="page-loading"><span className="spinner" /></div>;
  if (consentRequired && info.data?.consent) {
    return (
      <div className="login-page">
        <ConsentNotice title={info.data.consent.title} text={info.data.consent.text} onAgree={acknowledgeConsent} />
      </div>
    );
  }

  const run = async (kind: 'password' | 'certificate', fn: () => Promise<void>) => {
    setError(null);
    setBusy(kind);
    try {
      await fn();
      navigate(from, { replace: true, state: SIGNED_IN_STATE });
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
            {sso.length > 0 && !auth.password && !auth.x509
              ? 'Sign in with your organization account'
              : auth.password && auth.x509
                ? 'Sign in with your certificate or directory account'
                : auth.x509
                  ? 'Sign in with your certificate'
                  : 'Sign in with your directory account'}
          </p>
        </div>
        {error && <Alert tone="danger">{error}</Alert>}
        {!error && ssoError && <Alert tone="danger" title="Single sign-on failed">{ssoError}</Alert>}
        {!error && sessionNotice && <Alert tone="info" title="Signed out">{sessionNotice}</Alert>}
        {!error && certificateError && auth.x509 && <Alert tone="warning" title="Certificate not accepted">{certificateError}</Alert>}
        {info.data && !info.data.connected && (
          <Alert tone="warning" title="Directory unreachable">
            {info.data.error ?? 'The portal cannot reach the directory right now, so signing in may fail. Try again later.'}
          </Alert>
        )}

        {sso.map((option) => (
          // A full-page navigation: the server redirects to the identity provider, which returns to the portal.
          <a
            key={option.id}
            href={option.url}
            className={`btn btn-${auth.password || auth.x509 ? 'secondary' : 'primary'} btn-block`}
            aria-disabled={busy !== null}
            onClick={(e) => busy !== null && e.preventDefault()}
          >
            <KeyRound size={16} />
            {option.label}
          </a>
        ))}
        {sso.length > 0 && (auth.x509 || auth.password) && (
          <div className="login-divider">
            <span>or</span>
          </div>
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

/** What to tell the user when the server sent them back from a single sign-on with an error. */
export function ssoErrorMessage(code: string | null): string | null {
  switch (code) {
    case null:
    case '':
      return null;
    case 'consent':
      return 'Read and acknowledge the notice and consent banner, then sign in again.';
    case 'no_account':
    case 'no_identity':
      return 'Your sign-in worked, but no directory account matches it. Contact your administrator.';
    case 'unverified_email':
      return 'Your identity provider has not verified your email address, so the portal cannot use it to find your account.';
    case 'unavailable':
      return 'Signing in is not possible right now (the audit log is unavailable). Try again later.';
    default:
      return 'Single sign-on did not complete. Try again, or contact your administrator if it keeps failing.';
  }
}
