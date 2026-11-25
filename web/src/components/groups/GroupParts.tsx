import { useId, useState } from 'react';
import { Link } from 'react-router-dom';
import { UserPlus } from 'lucide-react';
import type { AccessDecisions, AccessRequest, AccessRequestStatus, GroupPerson, GroupSummary } from '@dsp/shared';
import { dnParam } from '../../api/client';
import { useGroupMutations, useGroupPeople } from '../../api/hooks';
import { Alert, Badge, Button, ErrorAlert, FormRow, Modal, useDebounced, useToast } from '../ui';

export const groupPath = (definitionId: string, dn: string) => `/groups/${encodeURIComponent(definitionId)}/group?${dnParam(dn)}`;

export const when = (iso?: string) =>
  iso ? new Date(iso).toLocaleString(undefined, { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '';

/** An end date (`yyyy-MM-dd`, a whole day in UTC) as shown: the same day whatever the viewer's time zone. */
export const day = (date?: string | null) =>
  date ? new Date(`${date}T00:00:00Z`).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric', timeZone: 'UTC' }) : '';

/** Today (UTC) plus `days`, as `yyyy-MM-dd`: end dates are whole days in UTC. */
export const dayFromNow = (days = 0) => new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 10);

/**
 * The last day of a membership: a date from today on, no later than `latest` (a day) or the
 * directory's longest membership (`maxDays`).
 */
export function EndDateField({
  id,
  label,
  value,
  onChange,
  maxDays,
  latest,
  hint,
  error,
  required,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (v: string) => void;
  maxDays?: number | null;
  latest?: string;
  hint?: string;
  error?: string;
  required?: boolean;
}) {
  const limit = maxDays ? dayFromNow(maxDays) : undefined;
  const max = latest && (!limit || latest < limit) ? latest : limit;
  return (
    <FormRow label={label} htmlFor={id} hint={hint} error={error} required={required}>
      <input id={id} className="input" type="date" min={dayFromNow()} max={max} value={value} onChange={(e) => onChange(e.target.value)} />
    </FormRow>
  );
}

export function GroupLink({ definitionId, dn, children }: { definitionId: string; dn: string; children: React.ReactNode }) {
  return <Link to={groupPath(definitionId, dn)}>{children}</Link>;
}

/** The user's relation to a group, as badges (never colour alone: each says what it is). */
export function GroupBadges({ group }: { group: GroupSummary }) {
  return (
    <span className="group-badges">
      {group.member && <Badge tone="success">{group.memberUntil ? `Member until ${day(group.memberUntil)}` : 'Member'}</Badge>}
      {group.owner && <Badge tone="accent">Owner</Badge>}
      {group.pendingRequestId && <Badge tone="info">Requested</Badge>}
      {!group.member && group.open && <Badge tone="info">Open to join</Badge>}
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

/**
 * Asks to join a group, with a reason (required when the group's directory says so) and, if the
 * requester wants, the day until which they need access.
 */
export function RequestAccessDialog({
  group,
  requireReason,
  maxMembershipDays,
  open,
  onClose,
}: {
  group: Pick<GroupSummary, 'definitionId' | 'dn' | 'name'>;
  requireReason: boolean;
  maxMembershipDays?: number | null;
  open: boolean;
  onClose: () => void;
}) {
  const id = useId();
  const toast = useToast();
  const { request } = useGroupMutations();
  const [reason, setReason] = useState('');
  const [until, setUntil] = useState('');
  const close = () => {
    request.reset();
    setReason('');
    setUntil('');
    onClose();
  };
  const details = (request.error as { details?: Record<string, string> } | null)?.details;
  const submit = () =>
    request.mutate(
      { definitionId: group.definitionId, dn: group.dn, reason: reason.trim(), until },
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
        <EndDateField
          id={`${id}-until`}
          label="Access until (optional)"
          value={until}
          onChange={setUntil}
          maxDays={maxMembershipDays}
          error={details?.until}
          hint={
            maxMembershipDays
              ? `The last day you need access. Memberships here last at most ${maxMembershipDays} days; empty: that long.`
              : 'The last day you need access. Empty: no end date.'
          }
        />
      </div>
    </Modal>
  );
}

/** Approves or rejects a request, with an optional note for the requester; an approval may shorten the access asked for. */
export function DecisionDialog({ request, approve, onClose }: { request: AccessRequest | null; approve: boolean; onClose: () => void }) {
  const id = useId();
  const toast = useToast();
  const { decide } = useGroupMutations();
  const [note, setNote] = useState('');
  const [until, setUntil] = useState('');
  const close = () => {
    decide.reset();
    setNote('');
    setUntil('');
    onClose();
  };
  if (!request) return null;
  const who = request.requesterName ?? request.requester;
  const details = (decide.error as { details?: Record<string, string> } | null)?.details;
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
                { id: request.id, approve, note: note.trim(), until },
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
        {!details?.until && <ErrorAlert error={decide.error} />}
        {request.reason && (
          <blockquote className="request-reason">
            <span className="muted small">Their reason</span>
            <p>{request.reason}</p>
          </blockquote>
        )}
        {approve && (
          <EndDateField
            id={`${id}-until`}
            label="Access until (optional)"
            value={until}
            onChange={setUntil}
            latest={request.until}
            error={details?.until}
            hint={
              request.until
                ? `${who} asked for access until ${day(request.until)}. You can shorten it; empty: as asked.`
                : "Empty: no end date, or the directory's longest membership."
            }
          />
        )}
        <FormRow label={approve ? 'Note (optional)' : 'Why not? (optional)'} htmlFor={`${id}-note`} hint={`${who} sees this with the decision.`}>
          <textarea id={`${id}-note`} className="input" rows={3} maxLength={1000} value={note} onChange={(e) => setNote(e.target.value)} />
        </FormRow>
      </div>
    </Modal>
  );
}

/** Most requests decided together (the server's limit). */
export const MAX_DECISIONS = 50;

/**
 * Approves or rejects several requests with one note. Each is decided on its own: afterwards the
 * dialog says how many were decided and lists those that were not, with the reason.
 */
export function BulkDecisionDialog({ requests, approve, onClose }: { requests: AccessRequest[]; approve: boolean; onClose: () => void }) {
  const id = useId();
  const { decideMany } = useGroupMutations();
  const [note, setNote] = useState('');
  const [result, setResult] = useState<AccessDecisions | null>(null);
  const n = requests.length;
  const label = (r: AccessRequest) => `${r.requesterName ?? r.requester} · ${r.groupName}`;
  const failures = result?.results.filter((x) => !x.ok) ?? [];
  return (
    <Modal
      open
      onClose={onClose}
      title={result ? (approve ? 'Requests approved' : 'Requests rejected') : approve ? `Approve ${n} requests?` : `Reject ${n} requests?`}
      footer={
        result ? (
          <Button variant="primary" onClick={onClose}>
            Done
          </Button>
        ) : (
          <>
            <Button onClick={onClose}>Cancel</Button>
            <Button
              variant={approve ? 'primary' : 'danger'}
              loading={decideMany.isPending}
              onClick={() => decideMany.mutate({ ids: requests.map((r) => r.id), approve, note: note.trim() }, { onSuccess: setResult })}
            >
              {approve ? `Approve and add ${n}` : `Reject ${n}`}
            </Button>
          </>
        )
      }
    >
      {result ? (
        <div className="stack" role="status">
          <p>
            {result.decided} of {result.results.length} {result.results.length === 1 ? 'request was' : 'requests were'} {approve ? 'approved' : 'rejected'}.
          </p>
          {failures.length > 0 && (
            <Alert tone="warning" title={`${failures.length} could not be ${approve ? 'approved' : 'rejected'}`}>
              <ul className="plain-list">
                {failures.map((f) => {
                  const r = requests.find((x) => x.id === f.id);
                  return (
                    <li key={f.id}>
                      {r ? label(r) : f.id}: {f.error}
                    </li>
                  );
                })}
              </ul>
            </Alert>
          )}
        </div>
      ) : (
        <div className="stack">
          <ErrorAlert error={decideMany.error} />
          <ul className="plain-list person-list">
            {requests.map((r) => (
              <li key={r.id}>{label(r)}</li>
            ))}
          </ul>
          <FormRow
            label={approve ? 'Note for all (optional)' : 'Why not? For all (optional)'}
            htmlFor={`${id}-note`}
            hint="Every requester sees this with the decision. Each request is decided on its own."
          >
            <textarea id={`${id}-note`} className="input" rows={3} maxLength={1000} value={note} onChange={(e) => setNote(e.target.value)} />
          </FormRow>
        </div>
      )}
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

/** Joins an open group at once: no request, no approval. */
export function JoinButton({ group, primary, size }: { group: Pick<GroupSummary, 'definitionId' | 'dn' | 'name'>; primary?: boolean; size?: 'sm' }) {
  const toast = useToast();
  const { join } = useGroupMutations();
  return (
    <Button
      size={size}
      variant={primary ? 'primary' : undefined}
      icon={<UserPlus size={size ? 14 : 16} />}
      loading={join.isPending}
      onClick={() => join.mutate({ definitionId: group.definitionId, dn: group.dn }, { onSuccess: () => toast.success(`You joined ${group.name}.`), onError: (e) => toast.error(e) })}
    >
      Join
    </Button>
  );
}
