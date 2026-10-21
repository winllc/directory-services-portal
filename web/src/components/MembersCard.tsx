import { useId, useState } from 'react';
import { Download, Users } from 'lucide-react';
import type { FormDefinition } from '@dsp/shared';
import { getAttr } from '@dsp/shared';
import { downloadMembers, useChangeMembers } from '../api/hooks';
import { Button, Card, ErrorAlert, FormRow, Modal, useToast } from './ui';

const MEMBER_ATTRIBUTES = ['member', 'uniquemember', 'memberuid'];

/** The form's member field, if it has one (member, uniqueMember or memberUid). */
export function memberField(form: FormDefinition) {
  for (const a of MEMBER_ATTRIBUTES) {
    const f = form.fields.find((x) => x.multiValued && x.attribute.toLowerCase() === a);
    if (f) return f;
  }
  return undefined;
}

const lines = (text: string) =>
  text
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter(Boolean);

/** A group's members: export them, and add or remove many at once. */
export function MembersCard({
  definitionId,
  dn,
  form,
  attributes,
  canWrite,
}: {
  definitionId: string;
  dn: string;
  form: FormDefinition;
  attributes: Record<string, string[]>;
  canWrite: boolean;
}) {
  const field = memberField(form);
  const id = useId();
  const toast = useToast();
  const change = useChangeMembers(definitionId, dn);
  const [open, setOpen] = useState(false);
  const [add, setAdd] = useState('');
  const [remove, setRemove] = useState('');
  if (!field) return null;
  const count = getAttr(attributes, field.attribute).length;
  const exportAs = (format: 'csv' | 'ldif') => downloadMembers(definitionId, dn, format, field.attribute).catch((e) => toast.error(e));
  const close = () => {
    setOpen(false);
    change.reset();
  };

  return (
    <Card
      title={`${field.label} (${count})`}
      actions={
        <div className="toolbar-actions">
          <Button size="sm" icon={<Download size={14} />} onClick={() => exportAs('csv')}>
            Export CSV
          </Button>
          <Button size="sm" icon={<Download size={14} />} onClick={() => exportAs('ldif')}>
            Export LDIF
          </Button>
          {canWrite && (
            <Button size="sm" icon={<Users size={14} />} onClick={() => setOpen(true)}>
              Change members in bulk
            </Button>
          )}
        </div>
      }
    >
      <p className="muted small">Export the {field.label.toLowerCase()} of this entry, or add and remove many of them in one change.</p>
      <Modal
        open={open}
        onClose={close}
        title={`Change ${field.label.toLowerCase()}`}
        footer={
          <>
            <Button onClick={close}>Cancel</Button>
            <Button
              variant="primary"
              loading={change.isPending}
              disabled={!lines(add).length && !lines(remove).length}
              onClick={() =>
                change.mutate(
                  { attribute: field.attribute, add: lines(add), remove: lines(remove) },
                  {
                    onSuccess: (r) => {
                      toast.success(`${r.added.length} added, ${r.removed.length} removed.`);
                      setAdd('');
                      setRemove('');
                      close();
                    },
                  },
                )
              }
            >
              Apply
            </Button>
          </>
        }
      >
        <div className="stack">
          <ErrorAlert error={change.error} />
          <FormRow label="Add" htmlFor={`${id}-add`} hint="One per line (a DN for member and uniqueMember). Members already present are skipped.">
            <textarea id={`${id}-add`} className="input" rows={6} value={add} onChange={(e) => setAdd(e.target.value)} />
          </FormRow>
          <FormRow label="Remove" htmlFor={`${id}-remove`} hint="One per line.">
            <textarea id={`${id}-remove`} className="input" rows={4} value={remove} onChange={(e) => setRemove(e.target.value)} />
          </FormRow>
        </div>
      </Modal>
    </Card>
  );
}
