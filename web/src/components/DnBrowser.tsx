import { useState } from 'react';
import { ChevronRight, CornerLeftUp, FolderTree } from 'lucide-react';
import { parentDn } from '@dsp/shared';
import { useBrowse, useServerInfo } from '../api/hooks';
import { Button, ErrorAlert, Modal, Spinner } from './ui';

/**
 * Modal that lets administrators walk the directory tree and pick a DN. `directory`: the LDAP
 * directory to browse (none: the first), whose base DN is `rootDn` (none: the signed-in user's).
 */
export function DnBrowser({
  open,
  initial,
  onSelect,
  onClose,
  directory,
  rootDn,
}: {
  open: boolean;
  initial?: string;
  onSelect: (dn: string) => void;
  onClose: () => void;
  directory?: string;
  rootDn?: string;
}) {
  const info = useServerInfo();
  const root = rootDn ?? info.data?.baseDn;
  const [base, setBase] = useState(initial || '');
  const current = base || root || '';
  const q = useBrowse(current, open && !!current, directory);
  const atRoot = !!root && current.toLowerCase() === root.toLowerCase();

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Browse directory"
      wide
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" onClick={() => onSelect(current)} disabled={!current}>
            Use this DN
          </Button>
        </>
      }
    >
      <div className="browser-path">
        <FolderTree size={16} />
        <code>{current}</code>
        {!atRoot && current && (
          <Button size="sm" variant="ghost" icon={<CornerLeftUp size={14} />} onClick={() => setBase(parentDn(current))}>
            Up
          </Button>
        )}
      </div>
      <ErrorAlert error={q.error} />
      {q.isLoading && <Spinner />}
      <ul className="browser-list">
        {q.data?.children.map((c) => (
          <li key={c.dn}>
            <button type="button" onClick={() => setBase(c.dn)}>
              <span>
                <strong>{c.name}</strong>
                <span className="muted small"> {c.objectClasses.filter((o) => o !== 'top').join(', ')}</span>
              </span>
              <ChevronRight size={16} />
            </button>
          </li>
        ))}
        {q.data && q.data.children.length === 0 && <li className="muted empty-inline">No child entries.</li>}
      </ul>
    </Modal>
  );
}
