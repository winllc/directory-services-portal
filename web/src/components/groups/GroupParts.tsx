import { useId, useState } from 'react';
import { Link } from 'react-router-dom';
import { UserPlus } from 'lucide-react';
import type { AccessRequest, AccessRequestStatus, GroupPerson, GroupSummary } from '@dsp/shared';
import { dnParam } from '../../api/client';
import { useGroupMutations, useGroupPeople } from '../../api/hooks';
import { Badge, Button, ErrorAlert, FormRow, Modal, useDebounced, useToast } from '../ui';

export const groupPath = (definitionId: string, dn: string) => `/groups/${encodeURIComponent(definitionId)}/group?${dnParam(dn)}`;

export const when = (iso?: string) =>
  iso ? new Date(iso).toLocaleString(undefined, { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '';

export function GroupLink({ definitionId, dn, children }: { definitionId: string; dn: string; children: React.ReactNode }) {
  return <Link to={groupPath(definitionId, dn)}>{children}</Link>;
}

/** The user's relation to a group, as badges (never colour alone: each says what it is). */
export function GroupBadges({ group }: { group: GroupSummary }) {
  return (
    <span className="group-badges">
      {group.member && <Badge tone="success">Member</Badge>}
      {group.owner && <Badge tone="accent">Owner</Badge>}
      {group.pendingRequestId && <Badge tone="info">Requested</Badge>}
      {!group.member && !group.pendingRequestId && !group.requestable && <Badge>Closed to requests</Badge>}
    </span>
  );
}

const STATUS: Record<AccessRequestStatus, { label: string; tone: 'info' | 'success' | 'danger' | 'neutral' }> = {
  pending: { label: 'Waiting for approval', tone: 'info' },
  approved: { label: 'Approved', tone: 'success' },
  rejected: { label: 'Rejected', tone: 'danger' },
  cancelled: { label: 'Withdrawn', tone: 'neutral' },
  expired: { label: 'Expired', tone: 'neutral' },
};

/** What became of a request nobody answered in time, for the requester and the group's owners. */
export const expiredText = (r: AccessRequest) =>
  r.expiredAfterDays ? `Nobody answered within ${r.expiredAfterDays} ${r.expiredAfterDays === 1 ? 'day' : 'days'}.` : 'Nobody answered in time.';

export function RequestStatus({ status }: { status: AccessRequestStatus }) {
  const s = STATUS[status];
  return <Badge tone={s.tone}>{s.label}</Badge>;
}

/** Asks to join a group, with a reason (required when the group's directory says so). */
export function RequestAccessDialog({
  group,
  requireReason,
  open,
  onClose,
}: {
  group: Pick<GroupSummary, 'definitionId' | 'dn' | 'name'>;
  requireReason: boolean;
  open: boolean;
  onClose: () => void;
}) {
  const id = useId();
  const toast = useToast();
  const { request } = useGroupMutations();
  const [reason, setReason] = useState('');
  const close = () => {
    request.reset();
    setReason('');
    onClose();
  };
  const details = (request.error as { details?: Record<string, string> } | null)?.details;
  const submit = () =>
    request.mutate(
      { definitionId: group.definitionId, dn: group.dn, reason: reason.trim() },
      {
        onSuccess: () => {
          toast.success(`Asked to join ${group.name}. Its owners will decide.`);
          close();
        },
      },
    );
  return (
    <Modal
      open={open}
      onClose={close}
      title={`Ask to join ${group.name}`}
      footer={
        <>
          <Button onClick={close}>Cancel</Button>
          <Button variant="primary" loading={request.isPending} disabled={requireReason && !reason.trim()} onClick={submit}>
            Send request
          </Button>
        </>
      }
    >
      <div className="stack">
        {!details && <ErrorAlert error={request.error} />}
        <p className="muted">The group's owners are asked to approve. You'll see their answer under My requests.</p>
        <FormRow
          label="Why do you need access?"
          htmlFor={`${id}-reason`}
          required={requireReason}
          error={details?.reason}
          hint={requireReason ? 'Required: the owners decide on it.' : 'Optional, but it helps the owners decide.'}
        >
          <textarea id={`${id}-reason`} className="input" rows={4} maxLength={1000} value={reason} onChange={(e) => setReason(e.target.value)} />
        </FormRow>
      </div>
    </Modal>
  );
}

/** Approves or rejects a request, with an optional note for the requester. */
export function DecisionDialog({ request, approve, onClose }: { request: AccessRequest | null; approve: boolean; onClose: () => void }) {
  const id = useId();
  const toast = useToast();
  const { decide } = useGroupMutations();
  const [note, setNote] = useState('');
  const close = () => {
    decide.reset();
    setNote('');
    onClose();
  };
  if (!request) return null;
  const who = request.requesterName ?? request.requester;
  return (
    <Modal
      open
      onClose={close}
      title={approve ? `Add ${who} to ${request.groupName}?` : `Reject ${who}'s request?`}
      footer={
        <>
          <Button onClick={close}>Cancel</Button>
          <Button
            variant={approve ? 'primary' : 'danger'}
            loading={decide.isPending}
            onClick={() =>
              decide.mutate(
                { id: request.id, approve, note: note.trim() },
                {
                  onSuccess: () => {
                    toast.success(approve ? `${who} was added to ${request.groupName}.` : `The request was rejected.`);
                    close();
                  },
                },
              )
            }
          >
            {approve ? 'Approve and add' : 'Reject'}
          </Button>
        </>
      }
    >
      <div className="stack">
        <ErrorAlert error={decide.error} />
        {request.reason && (
          <blockquote className="request-reason">
            <span className="muted small">Their reason</span>
            <p>{request.reason}</p>
          </blockquote>
        )}
        <FormRow label={approve ? 'Note (optional)' : 'Why not? (optional)'} htmlFor={`${id}-note`} hint={`${who} sees this with the decision.`}>
          <textarea id={`${id}-note`} className="input" rows={3} maxLength={1000} value={note} onChange={(e) => setNote(e.target.value)} />
        </FormRow>
      </div>
    </Modal>
  );
}

/** Finds people of the group's directory by name, login or e-mail, for those who manage the group, with one action per person. */
export function PersonSearch({
  definitionId,
  dn,
  label,
  placeholder,
  actionLabel,
  pending,
  onPick,
}: {
  definitionId: string;
  dn: string;
  label: string;
  placeholder: string;
  actionLabel: string;
  pending: boolean;
  onPick: (person: GroupPerson) => void;
}) {
  const [text, setText] = useState('');
  const who = useDebounced(text.trim(), 300);
  const people = useGroupPeople(definitionId, dn, who, who.length >= 2);
  return (
    <div className="member-add">
      <div className="search-box">
        <UserPlus size={16} aria-hidden />
        <input className="input" type="search" placeholder={placeholder} aria-label={label} value={text} onChange={(e) => setText(e.target.value)} />
      </div>
      {who.length >= 2 && (
        <ul className="plain-list person-results" aria-live="polite">
          {people.isFetching && <li className="muted small">Searching…</li>}
          {!people.isFetching && people.data?.length === 0 && <li className="muted small">No one matches “{who}”.</li>}
          {people.data?.map((p) => (
            <li key={p.value} className="person-result">
              <span>
                {p.name}
                {p.dn && <span className="muted small mono"> {p.dn}</span>}
              </span>
              <Button
                size="sm"
                loading={pending}
                onClick={() => {
                  onPick(p);
                  setText('');
                }}
              >
                {actionLabel}
              </Button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
