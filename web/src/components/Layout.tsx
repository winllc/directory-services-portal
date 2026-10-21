import { useState, type ReactNode } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router-dom';
import {
  BadgeCheck,
  BarChart3,
  BookOpen,
  Briefcase,
  Building2,
  Contact,
  Database,
  Folder,
  FormInput,
  Globe,
  History,
  Home,
  KeyRound,
  Laptop,
  LayoutList,
  LogOut,
  Menu,
  Phone,
  Server,
  Shield,
  ShieldCheck,
  UserCircle2,
  Users,
  UsersRound,
  Workflow,
  X,
} from 'lucide-react';
import { useDefinitions, useLicense, useServerInfo } from '../api/hooks';
import { useAuth, useUser } from '../lib/auth';
import { AUDIT_PATH, featureEnabled, LICENSE_PATH, LicenseNotices, licenseSummary } from '../lib/license';
import { Badge, Button, cx } from './ui';
import { JobsMenu } from './jobs/JobsMenu';

export const DEFINITION_ICONS: Record<string, typeof Users> = {
  users: Users,
  book: BookOpen,
  laptop: Laptop,
  group: UsersRound,
  briefcase: Briefcase,
  building: Building2,
  folder: Folder,
  shield: Shield,
  server: Server,
  globe: Globe,
  phone: Phone,
  contact: Contact,
};

export function DefinitionIcon({ icon, size = 18 }: { icon?: string; size?: number }) {
  const Icon = (icon && DEFINITION_ICONS[icon]) || Folder;
  return <Icon size={size} aria-hidden />;
}

function NavItem({ to, icon, children, end }: { to: string; icon: ReactNode; children: ReactNode; end?: boolean }) {
  return (
    <NavLink to={to} end={end} className={({ isActive }) => cx('nav-item', isActive && 'active')}>
      {icon}
      <span className="nav-text">{children}</span>
    </NavLink>
  );
}

export function Layout() {
  const user = useUser();
  const { logout } = useAuth();
  const license = useLicense();
  const has = (feature: Parameters<typeof featureEnabled>[1]) => featureEnabled(license.data, feature);
  const defs = useDefinitions(has('directory-editor'));
  const info = useServerInfo();
  const [open, setOpen] = useState(false);
  const location = useLocation();
  const [lastPath, setLastPath] = useState(location.pathname);
  if (lastPath !== location.pathname) {
    setLastPath(location.pathname);
    setOpen(false);
  }

  const initials = user.displayName
    .split(/\s+/)
    .map((p) => p[0])
    .slice(0, 2)
    .join('')
    .toUpperCase();

  return (
    <div className={cx('app-shell', open && 'nav-open')}>
      <a className="skip-link" href="#main">
        Skip to main content
      </a>
      <aside className="sidebar" aria-label="Main navigation">
        <div className="brand">
          <div className="brand-logo">
            <Database size={18} />
          </div>
          <div>
            <div className="brand-name">Directory Portal</div>
            <div className="brand-sub">{info.data?.baseDn ?? '…'}</div>
          </div>
          <button className="icon-btn nav-close" onClick={() => setOpen(false)} aria-label="Close menu">
            <X size={18} />
          </button>
        </div>

        <nav className="nav">
          <NavItem to="/" end icon={<Home size={18} />}>
            Home
          </NavItem>
          {has('self-service-profile') && (
            <NavItem to="/me" icon={<UserCircle2 size={18} />}>
              My profile
            </NavItem>
          )}
          {has('metrics') && (
            <NavItem to="/metrics" icon={<BarChart3 size={18} />}>
              Metrics
            </NavItem>
          )}

          {has('directory-editor') && (
            <>
              <div className="nav-heading">Directories</div>
              {defs.data?.map((d) => (
                <NavItem key={d.id} to={`/d/${d.slug}`} icon={<DefinitionIcon icon={d.icon} />}>
                  {d.name}
                  {d.mode === 'readonly' && <span className="nav-pill">RO</span>}
                </NavItem>
              ))}
              {defs.data?.length === 0 && <div className="nav-empty">No directories available</div>}
            </>
          )}

          {user.isAdmin && (
            <>
              <div className="nav-heading">Administration</div>
              {has('form-builder') && (
                <>
                  <NavItem to="/admin/definitions" icon={<LayoutList size={18} />}>
                    Directory definitions
                  </NavItem>
                  <NavItem to="/admin/forms" icon={<FormInput size={18} />}>
                    Forms
                  </NavItem>
                </>
              )}
              <NavItem to="/admin/schema" icon={<Database size={18} />}>
                Schema
              </NavItem>
              <NavItem to="/admin/permissions" icon={<KeyRound size={18} />}>
                Permissions
              </NavItem>
              {has('data-analysis') && (
                <NavItem to="/admin/analysis" icon={<ShieldCheck size={18} />}>
                  Data analysis
                </NavItem>
              )}
              <NavItem to="/admin/audit" icon={<History size={18} />}>
                Audit log
              </NavItem>
              <NavItem to="/admin/integrations" icon={<Workflow size={18} />}>
                Integrations
              </NavItem>
              <NavItem to={LICENSE_PATH} icon={<BadgeCheck size={18} />}>
                License
              </NavItem>
            </>
          )}
        </nav>

        <div className="sidebar-footer">
          {info.data && (
            <div className="conn-status" title={info.data.error ?? info.data.ldapUrl ?? 'In-memory demo directory'}>
              <span className={cx('dot', info.data.connected ? 'dot-ok' : 'dot-bad')} />
              {info.data.mode === 'memory' ? 'Demo directory' : info.data.connected ? 'Connected' : 'Directory unreachable'}
            </div>
          )}
          {user.isAdmin && license.data && (
            <div className="license-status" title={license.data.message}>
              {licenseSummary(license.data)}
            </div>
          )}
        </div>
      </aside>
      <div className="nav-scrim" onClick={() => setOpen(false)} />

      <div className="main">
        <header className="topbar">
          <button className="icon-btn nav-toggle" onClick={() => setOpen(true)} aria-label="Open menu">
            <Menu size={20} />
          </button>
          <div className="topbar-spacer" />
          <JobsMenu />
          <div className="user-menu">
            <div className="avatar" aria-hidden>
              {initials}
            </div>
            <div className="user-meta">
              <div className="user-name">
                {user.displayName} {user.isAdmin && <Badge tone="accent">Admin</Badge>}
              </div>
              <div className="user-dn" title={user.dn}>
                {user.username}
              </div>
            </div>
            <button className="icon-btn" onClick={() => logout()} aria-label="Sign out" title="Sign out">
              <LogOut size={18} />
            </button>
          </div>
        </header>
        <LicenseNotices />
        <main className="content" id="main" tabIndex={-1}>
          <Outlet />
        </main>
      </div>
    </div>
  );
}

/**
 * The shell while no license is in effect: no navigation (nothing else works), just who is signed
 * in and sign-out, around the license screen or the "not licensed" page.
 */
export function UnlicensedLayout({ children }: { children: ReactNode }) {
  const user = useUser();
  const { logout } = useAuth();
  return (
    <div className="bare-shell">
      <header className="topbar">
        <div className="brand">
          <div className="brand-logo">
            <Database size={18} />
          </div>
          <div className="brand-name">Directory Portal</div>
        </div>
        {user.isAdmin && (
          <nav className="bare-nav" aria-label="Administration">
            <NavLink to={LICENSE_PATH} className={({ isActive }) => cx('bare-nav-link', isActive && 'active')}>
              <KeyRound size={16} /> License
            </NavLink>
            <NavLink to={AUDIT_PATH} className={({ isActive }) => cx('bare-nav-link', isActive && 'active')}>
              <History size={16} /> Audit log
            </NavLink>
          </nav>
        )}
        <div className="topbar-spacer" />
        <div className="user-menu">
          <div className="user-meta">
            <div className="user-name">
              {user.displayName} {user.isAdmin && <Badge tone="accent">Admin</Badge>}
            </div>
            <div className="user-dn" title={user.dn}>
              {user.username}
            </div>
          </div>
          <Button icon={<LogOut size={16} />} onClick={() => logout()}>
            Sign out
          </Button>
        </div>
      </header>
      <main className="content" id="main">
        {children}
      </main>
    </div>
  );
}
