import { useState, type ReactNode } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router-dom';
import {
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
  UserCircle2,
  Users,
  UsersRound,
  X,
} from 'lucide-react';
import { useDefinitions, useServerInfo } from '../api/hooks';
import { useAuth, useUser } from '../lib/auth';
import { Badge, cx } from './ui';

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
  const defs = useDefinitions();
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
          <NavItem to="/me" icon={<UserCircle2 size={18} />}>
            My profile
          </NavItem>

          <div className="nav-heading">Directories</div>
          {defs.data?.map((d) => (
            <NavItem key={d.id} to={`/d/${d.slug}`} icon={<DefinitionIcon icon={d.icon} />}>
              {d.name}
              {d.mode === 'readonly' && <span className="nav-pill">RO</span>}
            </NavItem>
          ))}
          {defs.data?.length === 0 && <div className="nav-empty">No directories available</div>}

          {user.isAdmin && (
            <>
              <div className="nav-heading">Administration</div>
              <NavItem to="/admin/definitions" icon={<LayoutList size={18} />}>
                Directory definitions
              </NavItem>
              <NavItem to="/admin/forms" icon={<FormInput size={18} />}>
                Forms
              </NavItem>
              <NavItem to="/admin/schema" icon={<Database size={18} />}>
                Schema
              </NavItem>
              <NavItem to="/admin/permissions" icon={<KeyRound size={18} />}>
                Permissions
              </NavItem>
              <NavItem to="/admin/audit" icon={<History size={18} />}>
                Audit log
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
        </div>
      </aside>
      <div className="nav-scrim" onClick={() => setOpen(false)} />

      <div className="main">
        <header className="topbar">
          <button className="icon-btn nav-toggle" onClick={() => setOpen(true)} aria-label="Open menu">
            <Menu size={20} />
          </button>
          <div className="topbar-spacer" />
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
        <main className="content" id="main">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
