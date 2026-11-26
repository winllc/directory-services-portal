import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ArrowLeft, Users } from 'lucide-react';
import type { FormDefinition, GroupContainer } from '@dsp/shared';
import { OptionsSourceContext, useGroupContainers, useGroupMutations, useNewGroupForm } from '../api/hooks';
import { EntryForm } from '../components/EntryForm';
import { groupPath } from '../components/groups/GroupParts';
import { Card, EmptyState, ErrorAlert, FormRow, PageHeader, Spinner, useToast } from '../components/ui';

const keyOf = (c: GroupContainer) => `${c.definitionId}|${c.dn}`;

/**
 * A new group, for the owner of an organizational unit: the unit to create it in (when they own
 * several), then the directory's own form for a new entry. The creator is filled in as the
 * group's owner and, where a member is required, its first member; both are theirs to change.
 */
export function NewGroupPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const containers = useGroupContainers(true);
  const [chosen, setChosen] = useState('');
  const list = containers.data ?? [];
  const container = list.find((c) => keyOf(c) === chosen) ?? list[0];
  const loaded = useNewGroupForm(container?.definitionId);
  const { create } = useGroupMutations();
  const form = useMemo<FormDefinition | undefined>(() => {
    if (!loaded.data) return undefined;
    const { form: f, prefill } = loaded.data;
    return { ...f, fields: f.fields.map((x) => (x.defaultValues?.length || !prefill[x.attribute.toLowerCase()]?.length ? x : { ...x, defaultValues: prefill[x.attribute.toLowerCase()] })) };
  }, [loaded.data]);

  if (containers.isLoading) return <Spinner label="Loading…" />;
  if (!container) {
    return (
      <div className="page">
        <ErrorAlert error={containers.error} />
        <EmptyState icon={<Users size={28} />} title="Nowhere to create a group" action={<Link to="/groups">Group access</Link>}>
          Groups are created by the owners of the organizational unit they go into.
        </EmptyState>
      </div>
    );
  }
  const severalDirectories = new Set(list.map((c) => c.definitionId)).size > 1;

  return (
    <div className="page">
      <Link to="/groups" className="back-link">
        <ArrowLeft size={14} /> Group access
      </Link>
      <PageHeader icon={<Users size={22} />} title="New group" subtitle={container.definitionName} />
      <Card>
        <div className="stack">
          {list.length > 1 && (
            <FormRow label="Create in" htmlFor="group-container" hint="The organizational unit the new group goes into. You own these.">
              <select id="group-container" className="input select" value={keyOf(container)} onChange={(ev) => setChosen(ev.target.value)}>
                {list.map((c) => (
                  <option key={keyOf(c)} value={keyOf(c)}>
                    {severalDirectories ? `${c.definitionName}: ${c.dn}` : c.dn}
                  </option>
                ))}
              </select>
            </FormRow>
          )}
          <ErrorAlert error={loaded.error} />
          {loaded.isLoading && <Spinner />}
          {form && (
            <OptionsSourceContext.Provider value="new-group">
              <EntryForm
                key={container.definitionId}
                definitionId={container.definitionId}
                form={form}
                mode="create"
                submitLabel="Create group"
                submitting={create.isPending}
                error={create.error}
                onCancel={() => navigate('/groups')}
                header={
                  <p className="muted form-intro">
                    The group is created as{' '}
                    <code>
                      {form.rdnAttribute}=…,{container.dn}
                    </code>
                    . You are made its owner (and its first member, where a group needs one) unless you name others.
                  </p>
                }
                onSubmit={(values) =>
                  create.mutate(
                    { definitionId: container.definitionId, container: container.dn, values },
                    {
                      onSuccess: (created) => {
                        toast.success(`${created.name} was created.`);
                        navigate(groupPath(created.definitionId, created.dn), { replace: true });
                      },
                    },
                  )
                }
              />
            </OptionsSourceContext.Provider>
          )}
        </div>
      </Card>
    </div>
  );
}
