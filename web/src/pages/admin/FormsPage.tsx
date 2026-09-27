import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { FilePlus2, FormInput, Pencil, Trash2 } from 'lucide-react';
import { useAdminDefinitions, useFormMutations, useForms } from '../../api/hooks';
import { Badge, Button, ConfirmDialog, EmptyState, ErrorAlert, PageHeader, Spinner, useToast } from '../../components/ui';

export function FormsPage() {
  const forms = useForms();
  const defs = useAdminDefinitions();
  const { remove } = useFormMutations();
  const toast = useToast();
  const navigate = useNavigate();
  const [deleting, setDeleting] = useState<{ id: string; name: string } | null>(null);

  return (
    <div className="page">
      <PageHeader
        icon={<FormInput size={22} />}
        title="Forms"
        subtitle="Forms describe how an object type is displayed and edited: which attributes, as free text or drop downs, single or multi-valued."
        actions={
          <Button variant="primary" icon={<FilePlus2 size={16} />} onClick={() => navigate('/admin/forms/new')}>
            New form
          </Button>
        }
      />
      {forms.isLoading && <Spinner />}
      <ErrorAlert error={forms.error} />
      {forms.data?.length === 0 && <EmptyState title="No forms yet" icon={<FormInput size={28} />}>Create a form for an object class to start managing entries.</EmptyState>}
      <div className="table-wrap">
        {!!forms.data?.length && (
          <table className="table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Object classes</th>
                <th>Fields</th>
                <th>Used by</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {forms.data.map((f) => {
                const usedBy = defs.data?.filter((d) => d.formId === f.id) ?? [];
                return (
                  <tr key={f.id}>
                    <td>
                      <Link to={`/admin/forms/${f.id}`} className="row-title">
                        {f.name}
                      </Link>
                      {f.description && <div className="muted small">{f.description}</div>}
                    </td>
                    <td>
                      <div className="chips">
                        {f.objectClasses
                          .filter((c) => c !== 'top')
                          .map((c) => (
                            <Badge key={c}>{c}</Badge>
                          ))}
                      </div>
                    </td>
                    <td>
                      {f.fields.length}
                      <span className="muted small">
                        {' '}
                        ({f.fields.filter((x) => x.widget === 'dropdown').length} drop down, {f.fields.filter((x) => x.multiValued).length} multi)
                      </span>
                    </td>
                    <td>{usedBy.length ? usedBy.map((d) => d.name).join(', ') : <span className="muted">—</span>}</td>
                    <td className="cell-actions">
                      <Button size="sm" variant="ghost" icon={<Pencil size={14} />} onClick={() => navigate(`/admin/forms/${f.id}`)}>
                        Edit
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        icon={<Trash2 size={14} />}
                        disabled={usedBy.length > 0}
                        title={usedBy.length ? 'Used by a directory definition' : 'Delete'}
                        onClick={() => setDeleting({ id: f.id, name: f.name })}
                      >
                        Delete
                      </Button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>
      <ConfirmDialog
        open={!!deleting}
        title="Delete form?"
        message={<>Delete the form <strong>{deleting?.name}</strong>? Directory entries are not affected.</>}
        loading={remove.isPending}
        onCancel={() => setDeleting(null)}
        onConfirm={() =>
          remove.mutate(deleting!.id, {
            onSuccess: () => {
              toast.success('Form deleted');
              setDeleting(null);
            },
            onError: (e) => {
              toast.error(e);
              setDeleting(null);
            },
          })
        }
      />
    </div>
  );
}
