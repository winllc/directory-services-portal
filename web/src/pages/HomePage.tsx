import { Link } from 'react-router-dom';
import { ArrowRight, Eye, FolderOpen, Pencil, UserCircle2 } from 'lucide-react';
import { useDefinitions } from '../api/hooks';
import { useUser } from '../lib/auth';
import { DefinitionIcon } from '../components/Layout';
import { Badge, EmptyState, ErrorAlert, PageHeader, Spinner } from '../components/ui';

export function AccessBadge({ access, mode }: { access: string; mode: string }) {
  if (mode === 'readonly') return <Badge tone="info" title="White pages: read only for everyone">Read only</Badge>;
  if (access === 'write') return <Badge tone="success"><Pencil size={12} /> Can edit</Badge>;
  return <Badge tone="neutral"><Eye size={12} /> View</Badge>;
}

export function HomePage() {
  const user = useUser();
  const defs = useDefinitions();
  return (
    <div className="page">
      <PageHeader title={`Welcome, ${user.displayName.split(' ')[0]}`} subtitle="Browse the directories you have access to or review your own entries." />

      <Link to="/me" className="profile-banner">
        <UserCircle2 size={28} />
        <div>
          <strong>My profile</strong>
          <div className="muted">See how you appear in each directory and update your details.</div>
        </div>
        <ArrowRight size={18} className="profile-banner-arrow" />
      </Link>

      <h2 className="section-title">Directories</h2>
      {defs.isLoading && <Spinner />}
      <ErrorAlert error={defs.error} />
      {defs.data?.length === 0 && (
        <EmptyState icon={<FolderOpen size={32} />} title="No directories yet">
          {user.isAdmin ? (
            <>
              Create a <Link to="/admin/definitions/new">directory definition</Link> to get started.
            </>
          ) : (
            'Ask an administrator to grant you access to a directory.'
          )}
        </EmptyState>
      )}
      <div className="tile-grid">
        {defs.data?.map((d) => (
          <Link key={d.id} to={`/d/${d.slug}`} className="tile">
            <div className="tile-icon">
              <DefinitionIcon icon={d.icon} size={22} />
            </div>
            <div className="tile-body">
              <div className="tile-title">{d.name}</div>
              <div className="tile-desc">{d.description || d.baseDn}</div>
              <div className="tile-meta">
                <AccessBadge access={d.access} mode={d.mode} />
                <code className="tile-dn" title={d.baseDn}>{d.baseDn}</code>
              </div>
            </div>
          </Link>
        ))}
      </div>
    </div>
  );
}
