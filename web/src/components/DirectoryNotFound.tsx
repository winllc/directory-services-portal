import { Link } from 'react-router-dom';
import { FolderX } from 'lucide-react';
import { ApiError } from '../api/client';
import { useUser } from '../lib/auth';
import { EmptyState, ErrorAlert } from './ui';

/** Shown when a /d/:slug directory can't be loaded (unknown slug, no access, or another error). */
export function DirectoryNotFound({ slug, error }: { slug: string; error: unknown }) {
  const user = useUser();
  const status = error instanceof ApiError ? error.status : undefined;
  if (status !== undefined && status !== 404 && status !== 403) {
    return (
      <div className="page">
        <ErrorAlert error={error} />
      </div>
    );
  }
  return (
    <div className="page">
      <EmptyState icon={<FolderX size={28} />} title={status === 403 ? 'No access to this directory' : 'Directory not found'}>
        {status === 403 ? (
          <>You don't have permission to view <code>{slug}</code>. Ask an administrator for access.</>
        ) : (
          <>
            There is no directory called <code>{slug}</code>.{' '}
            {user.isAdmin ? (
              <>
                Create one under <Link to="/admin/definitions">Directory definitions</Link>, or pick one from the <Link to="/">home page</Link>.
              </>
            ) : (
              <>
                Pick one from the <Link to="/">home page</Link>.
              </>
            )}
          </>
        )}
      </EmptyState>
    </div>
  );
}
