import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { FilePlus2, KeyRound, LayoutList, Pencil, Trash2 } from 'lucide-react';
import { useAdminDefinitions, useDefinitionMutations, useForms, useGrants } from '../../api/hooks';
import { DefinitionIcon } from '../../components/Layout';
import { Badge, Button, ConfirmDialog, EmptyState, ErrorAlert, PageHeader, Spinner, useToast } from '../../components/ui';

export function DefinitionsPage() {
  const defs = useAdminDefinitions();
  const forms = useForms();
  const grants = useGrants();
  const { remove } = useDefinitionMutations();
  const navigate = useNavigate();
  const toast = useToast();
  const [deleting, setDeleting] = useState<{ id: string; name: string } | null>(null);

  return (
    <div className="page">
      <PageHeader
        icon={<LayoutList size={22} />}
        title="Directory definitions"
        subtitle="Each definition exposes one object type (form) within a namespace of the directory, as an editable directory or read-only white pages."
        actions={
          <Button variant="primary" icon={<FilePlus2 size={16} />} onClick={() => navigate('/admin/definitions/new')} disabled={!forms.data?.length}>
            New definition
          </Button>
        }
      />
      {defs.isLoading && <Spinner />}
      <ErrorAlert error={defs.error} />
      {forms.data?.length === 0 && (
        <EmptyState title="Create a form first" icon={<LayoutList size={28} />}>
          Definitions display entries using a form. <Link to="/admin/forms/new">Create a form</Link>.
        </EmptyState>
      )}
      {!!defs.data?.length && (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Namespace</th>
                <th>Form</th>
                <th>Mode</th>
                <th>Access</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {defs.data.map((d) => {
                const g = grants.data?.filter((x) => x.definitionId === d.id) ?? [];
                return (
                  <tr key={d.id}>
                    <td>
                      <div className="cell-with-icon">
                        <DefinitionIcon icon={d.icon} />
                        <div>
                          <Link to={`/admin/definitions/${d.id}`} className="row-title">
                            {d.name}
                          </Link>
                          <div className="muted small">/d/{d.slug}</div>
                        </div>
                      </div>
                    </td>
                    <td>
                      <code className="small">{d.baseDn}</code>
                      <div className="muted small">
                        {d.scope === 'one' ? 'one level' : 'subtree'}
                        {d.filter && <> · {d.filter}</>}
                      </div>
                    </td>
                    <td>{forms.data?.find((f) => f.id === d.formId)?.name ?? <span className="text-danger">missing</span>}</td>
                    <td>{d.mode === 'readonly' ? <Badge tone="info">White pages</Badge> : <Badge tone="success">Read / write</Badge>}</td>
                    <td className="small">
                      {d.everyoneCanRead && <Badge>Everyone reads</Badge>} {g.length} grant{g.length === 1 ? '' : 's'}
                    </td>
                    <td className="cell-actions">
                      <Button size="sm" variant="ghost" icon={<KeyRound size={14} />} onClick={() => navigate(`/admin/permissions?definition=${d.id}`)}>
                        Permissions
                      </Button>
                      <Button size="sm" variant="ghost" icon={<Pencil size={14} />} onClick={() => navigate(`/admin/definitions/${d.id}`)}>
                        Edit
                      </Button>
                      <Button size="sm" variant="ghost" icon={<Trash2 size={14} />} onClick={() => setDeleting({ id: d.id, name: d.name })}>
                        Delete
                      </Button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      <ConfirmDialog
        open={!!deleting}
        title="Delete directory definition?"
        message={<>Delete <strong>{deleting?.name}</strong> and its permission grants? Directory entries are not affected.</>}
        loading={remove.isPending}
        onCancel={() => setDeleting(null)}
        onConfirm={() =>
          remove.mutate(deleting!.id, {
            onSuccess: () => {
              toast.success('Definition deleted');
              setDeleting(null);
            },
            onError: (e) => toast.error(e),
          })
        }
      />
    </div>
  );
}
