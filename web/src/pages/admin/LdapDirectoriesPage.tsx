import { RefreshCw, Server } from 'lucide-react';
import type { LdapDirectoryStatus } from '@dsp/shared';
import { useLdapDirectories } from '../../api/hooks';
import { Alert, Badge, Button, ErrorAlert, PageHeader, Spinner } from '../../components/ui';

/**
 * The LDAP directories the portal connects to, read only (they are configured in the environment:
 * PORTAL_DIRECTORIES_<n>_*, or LDAP_* for one): servers in the order tried, the one in use, whether
 * each answers, and whether the license enables it.
 */
export function LdapDirectoriesPage() {
  const q = useLdapDirectories(true);
  return (
    <div className="page">
      <PageHeader
        icon={<Server size={22} />}
        title="LDAP directories"
        subtitle="The directory servers the portal connects to. They are configured in the portal's environment, not here."
        actions={
          <Button icon={<RefreshCw size={16} />} onClick={() => q.refetch()} loading={q.isFetching}>
            Check again
          </Button>
        }
      />
      {q.isLoading && <Spinner />}
      <ErrorAlert error={q.error} />
      {q.data?.notice && (
        <Alert tone="warning" title="Some directories are disabled">
          {q.data.notice}
        </Alert>
      )}
      {q.data && (
        <div className="table-wrap">
          <table className="table">
            <caption className="sr-only">Configured LDAP directories</caption>
            <thead>
              <tr>
                <th scope="col">Directory</th>
                <th scope="col">Servers</th>
                <th scope="col">In use</th>
                <th scope="col">Status</th>
                <th scope="col">License</th>
              </tr>
            </thead>
            <tbody>
              {q.data.directories.map((d) => (
                <DirectoryRow key={d.id} d={d} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function DirectoryRow({ d }: { d: LdapDirectoryStatus }) {
  return (
    <tr>
      <td>
        <div className="row-title">{d.name}</div>
        <div className="muted small">
          <code>{d.id}</code>
          {d.primary && ' · first (primary)'}
        </div>
        <div className="muted small">
          <code>{d.baseDn}</code>
        </div>
      </td>
      <td>
        <ol className="plain-list small">
          {d.servers.map((s) => (
            <li key={s}>
              <code>{s}</code>
            </li>
          ))}
        </ol>
        {d.servers.length > 1 && <div className="muted small">{d.serverSelection === 'round-robin' ? 'Round robin' : 'Failover, in this order'}</div>}
      </td>
      <td className="small">{d.currentServer ? <code>{d.currentServer}</code> : <span className="muted">Not connected yet</span>}</td>
      <td>
        {d.reachable === undefined || d.reachable === null ? (
          <span className="muted small">Not checked</span>
        ) : d.reachable ? (
          <Badge tone="success">Reachable</Badge>
        ) : (
          <>
            <Badge tone="danger">Unreachable</Badge>
            {d.error && <div className="small text-danger">{d.error}</div>}
          </>
        )}
      </td>
      <td>{d.enabled ? <Badge tone="success">Enabled</Badge> : <Badge tone="warning">Disabled: not licensed</Badge>}</td>
    </tr>
  );
}
