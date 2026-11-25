import { useId, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { ArrowLeft, CalendarClock, ChevronLeft, ChevronRight, ListPlus, LogOut, Search, UserMinus, UserPlus, Users } from 'lucide-react';
import type { GroupDetail, GroupMember, GroupPeopleResolved, GroupPerson, GroupSummary } from '@dsp/shared';
import { useGroup, useGroupAccessMembers, useGroupMutations, useGroupRequestsFor } from '../api/hooks';
import { Alert, Badge, Button, Card, ConfirmDialog, EmptyState, ErrorAlert, FormRow, Modal, PageHeader, Spinner, useDebounced, useToast } from '../components/ui';
import { day, EndDateField, GroupBadges, JoinButton, PersonSearch, RequestAccessDialog } from '../components/groups/GroupParts';
import { RequestQueue } from './GroupsPage';

/** One group: what it is, who owns it, the user's membership; for its owners, members and requests. */
export function GroupPage() {
  const { definitionId } = useParams();
  const [params] = useSearchParams();
  const dn = params.get('dn');
  const group = useGroup(definitionId, dn);

  if (group.isLoading) return <Spinner label="Loading…" />;
  if (!group.data) {
    return (
      <EmptyState icon={<Users size={28} />} title="Group not found" action={<Link to="/groups">Group access</Link>}>
        It may have been removed, or its directory no longer takes requests.
      </EmptyState>
    );
  }
  const d = group.data;
  return (
    <div className="page">
      <Link to="/groups" className="back-link">
        <ArrowLeft size={14} /> Group access
      </Link>
      <PageHeader
        icon={<Users size={22} />}
        title={d.group.name}
        subtitle={
          <>
            {d.group.description && <span>{d.group.description} · </span>}
            <span className="muted">{d.group.definitionName}</span> <GroupBadges group={d.group} />
          </>
        }
      />
      <div className="split split-even">
        <Membership detail={d} />
        <Owners detail={d} />
      </div>
      {d.group.canManage && definitionId && dn && (
        <>
          <GroupRequests definitionId={d.group.definitionId} dn={d.group.dn} count={d.pendingRequests} />
          <Members detail={d} />
        </>
      )}
    </div>
  );
}

function Membership({ detail }: { detail: GroupDetail }) {
  const g = detail.group;
  const [asking, setAsking] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const { cancel, leave } = useGroupMutations();
  const toast = useToast();
  return (
    <Card title="Your membership">
      <div className="stack">
        {g.member ? (
          <>
            <p>{g.memberUntil ? `You are a member of this group until ${day(g.memberUntil)}.` : 'You are a member of this group.'}</p>
            {detail.allowLeave && (
              <div>
                <Button icon={<LogOut size={16} />} onClick={() => setLeaving(true)}>
                  Leave group
                </Button>
              </div>
            )}
          </>
        ) : g.open ? (
          <>
            <p>You aren't a member. This group is open: you can join at once, without approval.</p>
            <div>
              <JoinButton group={g} primary />
            </div>
          </>
        ) : g.pendingRequestId ? (
          <>
            <p>You asked to join. The group's owners haven't decided yet.</p>
            <div>
              <Button loading={cancel.isPending} onClick={() => cancel.mutate(g.pendingRequestId!, { onSuccess: () => toast.success('Request withdrawn.'), onError: (e) => toast.error(e) })}>
                Withdraw request
              </Button>
            </div>
          </>
        ) : g.requestable ? (
          <>
            <p>You aren't a member. Ask to join and the group's owners decide.</p>
            <div>
              <Button variant="primary" icon={<UserPlus size={16} />} onClick={() => setAsking(true)}>
                Request access
              </Button>
            </div>
          </>
        ) : (
          <p className="muted">This group doesn't take requests. Ask its owners directly.</p>
        )}
        <p className="muted small">{detail.memberCount === null ? 'A large group.' : `${detail.memberCount} ${detail.memberCount === 1 ? 'member' : 'members'}.`}</p>
      </div>
      <RequestAccessDialog
        group={g}
        requireReason={detail.requireReason}
        maxMembershipDays={detail.maxMembershipDays}
        open={asking}
        onClose={() => setAsking(false)}
      />
      <ConfirmDialog
        open={leaving}
        title={`Leave ${g.name}?`}
        message="You lose the access this group gives. To join again, you'll have to ask its owners."
        confirmLabel="Leave group"
        loading={leave.isPending}
        onCancel={() => setLeaving(false)}
        onConfirm={() =>
          leave.mutate(
            { definitionId: g.definitionId, dn: g.dn },
            {
              onSuccess: () => {
                toast.success(`You left ${g.name}.`);
                setLeaving(false);
              },
              onError: (e) => {
                toast.error(e);
                setLeaving(false);
              },
            },
          )
        }
      />
    </Card>
  );
}

/** Who owns the group; where the directory allows it, owners add and remove owners here. */
function Owners({ detail }: { detail: GroupDetail }) {
  const g = detail.group;
  const toast = useToast();
  const { owners: change } = useGroupMutations();
  const [removing, setRemoving] = useState<GroupPerson | null>(null);
  const manage = detail.canManageOwners;
  // The last owner stays: someone has to decide the group's requests.
  const removable = manage && (detail.owners.length > 1 || detail.moreOwners);
  const apply = (add: string[], remove: string[], done: string) =>
    change.mutate(
      { definitionId: g.definitionId, dn: g.dn, add, remove },
      {
        onSuccess: (r) => {
          toast.success(r.added.length || r.removed.length ? done : 'Nothing changed: already so.');
          setRemoving(null);
        },
        onError: (e) => {
          toast.error(e);
          setRemoving(null);
        },
      },
    );
  return (
    <Card title="Owners" subtitle={manage ? 'They approve requests to join. You can add and remove owners.' : 'They approve requests to join.'}>
      <div className="stack">
        {detail.owners.length === 0 ? (
          <p className="muted">This group has no owners: an administrator decides its requests.</p>
        ) : (
          <ul className="plain-list person-list">
            {detail.owners.map((o) => (
              <li key={o.value} className="person-result">
                <span>
                  {o.name}
                  {o.dn && (
                    <span className="muted small mono ellipsis" title={o.dn}>
                      {' '}
                      {o.dn}
                    </span>
                  )}
                </span>
                {removable && (
                  <Button size="sm" variant="ghost" icon={<UserMinus size={14} />} aria-label={`Remove owner ${o.name}`} onClick={() => setRemoving(o)}>
                    Remove
                  </Button>
                )}
              </li>
            ))}
            {detail.moreOwners && <li className="muted small">and more</li>}
          </ul>
        )}
        {manage && !removable && detail.owners.length === 1 && <p className="muted small">The last owner can't be removed. Add another one first.</p>}
        {manage && (
          <PersonSearch
            definitionId={g.definitionId}
            dn={g.dn}
            label="Find someone to make an owner"
            placeholder="Add an owner: type a name, login or e-mail…"
            actionLabel="Make owner"
            pending={change.isPending}
            onPick={(p) => apply([p.dn ?? p.value], [], `${p.name} is now an owner.`)}
          />
        )}
      </div>
      <ConfirmDialog
        open={!!removing}
        title={`Remove ${removing?.name} as an owner?`}
        message={`They no longer decide requests or manage the members of ${g.name}.`}
        confirmLabel="Remove owner"
        loading={change.isPending}
        onCancel={() => setRemoving(null)}
        onConfirm={() => removing && apply([], [removing.value], `${removing.name} is no longer an owner.`)}
      />
    </Card>
  );
}

function GroupRequests({ definitionId, dn, count }: { definitionId: string; dn: string; count: number }) {
  const requests = useGroupRequestsFor(definitionId, dn, true);
  const list = requests.data ?? [];
  return (
    <Card title={`Requests to join (${list.length || count})`}>
      <ErrorAlert error={requests.error} />
      {requests.isLoading ? <Spinner /> : <RequestQueue requests={list} showGroup={false} empty={<p className="muted">No open requests.</p>} />}
    </Card>
  );
}

function Members({ detail }: { detail: GroupDetail }) {
  const g = detail.group;
  const toast = useToast();
  const { members: change } = useGroupMutations();
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  const q = useDebounced(search.trim(), 300);
  const members = useGroupAccessMembers(g.definitionId, g.dn, q, page, true);
  const [removing, setRemoving] = useState<{ value: string; name: string } | null>(null);
  const [until, setUntil] = useState('');
  const [dating, setDating] = useState<GroupMember | null>(null);
  const [pasting, setPasting] = useState(false);
  const id = useId();
  const max = detail.maxMembershipDays;
  const data = members.data;
  const pages = data ? Math.max(1, Math.ceil(data.total / data.pageSize)) : 1;
  const apply = (add: string[], remove: string[], done: string) =>
    change.mutate(
      { definitionId: g.definitionId, dn: g.dn, add, remove, until: add.length ? until : undefined },
      {
        onSuccess: (r) => {
          toast.success(r.added.length || r.removed.length ? done : 'Nothing changed: already so.');
          setRemoving(null);
        },
        onError: (e) => {
          toast.error(e);
          setRemoving(null);
        },
      },
    );

  return (
    <Card
      title={`Members (${detail.memberCount ?? data?.total ?? '…'})`}
      subtitle="As an owner you can add and remove members directly."
      actions={
        <div className="toolbar-actions">
          <Button size="sm" icon={<ListPlus size={14} />} onClick={() => setPasting(true)}>
            Add from a list
          </Button>
        </div>
      }
    >
      <div className="stack">
        <PersonSearch
          definitionId={g.definitionId}
          dn={g.dn}
          label="Find someone to add"
          placeholder="Add someone: type a name, login or e-mail…"
          actionLabel="Add"
          pending={change.isPending}
          onPick={(p) => apply([p.value], [], `${p.name} was added.`)}
        />
        <EndDateField
          id={`${id}-until`}
          label="End date for people you add (optional)"
          value={until}
          onChange={setUntil}
          maxDays={max}
          hint={max ? `Their last day in the group. Memberships here last at most ${max} days; empty: that long.` : 'Their last day in the group. Empty: no end date.'}
        />

        <div className="toolbar">
          <div className="search-box search-sm">
            <Search size={15} aria-hidden />
            <input
              className="input input-sm"
              type="search"
              placeholder="Find a member"
              aria-label="Find a member"
              value={search}
              onChange={(e) => {
                setSearch(e.target.value);
                setPage(1);
              }}
            />
          </div>
        </div>
        {data?.partial && <Alert tone="info">This group is large: the directory returned only part of its members.</Alert>}
        <ErrorAlert error={members.error} />
        {data && data.items.length === 0 ? (
          <p className="muted">{q ? `No member matches “${q}”.` : 'This group has no members.'}</p>
        ) : (
          <ul className={`plain-list person-list${members.isFetching ? ' is-refreshing' : ''}`}>
            {data?.items.map((m) => (
              <li key={m.value} className="person-result">
                <span>
                  {m.name}
                  {m.dn && (
                    <span className="muted small mono ellipsis" title={m.dn}>
                      {' '}
                      {m.dn}
                    </span>
                  )}
                </span>
                <div className="toolbar-actions">
                  {m.until && <span className="muted small nowrap">until {day(m.until)}</span>}
                  <Button size="sm" variant="ghost" icon={<CalendarClock size={14} />} aria-label={`End date for ${m.name}`} onClick={() => setDating(m)}>
                    End date
                  </Button>
                  <Button size="sm" variant="ghost" icon={<UserMinus size={14} />} aria-label={`Remove ${m.name}`} onClick={() => setRemoving({ value: m.value, name: m.name })}>
                    Remove
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        )}
        {pages > 1 && (
          <div className="pagination">
            <Button size="sm" variant="ghost" icon={<ChevronLeft size={16} />} disabled={page <= 1} onClick={() => setPage(page - 1)}>
              Previous
            </Button>
            <span className="muted small">
              Page {page} of {pages}
            </span>
            <Button size="sm" variant="ghost" disabled={page >= pages} onClick={() => setPage(page + 1)}>
              Next <ChevronRight size={16} />
            </Button>
          </div>
        )}
      </div>
      <ConfirmDialog
        open={!!removing}
        title={`Remove ${removing?.name}?`}
        message={`They lose the access ${g.name} gives.`}
        confirmLabel="Remove"
        loading={change.isPending}
        onCancel={() => setRemoving(null)}
        onConfirm={() => removing && apply([], [removing.value], `${removing.name} was removed.`)}
      />
      <MemberEndDateDialog key={dating?.value ?? 'none'} group={g} member={dating} maxDays={max} onClose={() => setDating(null)} />
      {pasting && <BulkAddDialog group={g} maxDays={max} onClose={() => setPasting(false)} />}
    </Card>
  );
}

/** Sets, changes or removes a member's end date (it can't be removed where the directory limits memberships). */
function MemberEndDateDialog({ group, member, maxDays, onClose }: { group: GroupSummary; member: GroupMember | null; maxDays?: number | null; onClose: () => void }) {
  const id = useId();
  const toast = useToast();
  const { memberUntil } = useGroupMutations();
  const [until, setUntil] = useState(member?.until ?? '');
  if (!member) return null;
  const details = (memberUntil.error as { details?: Record<string, string> } | null)?.details;
  const close = () => {
    memberUntil.reset();
    onClose();
  };
  const save = (date: string) =>
    memberUntil.mutate(
      { definitionId: group.definitionId, dn: group.dn, member: member.value, until: date },
      {
        onSuccess: () => {
          toast.success(date ? `${member.name} is a member until ${day(date)}.` : `${member.name}'s membership has no end date any more.`);
          close();
        },
      },
    );
  return (
    <Modal
      open
      onClose={close}
      title={`End date for ${member.name}`}
      footer={
        <>
          <Button onClick={close}>Cancel</Button>
          {member.until && !maxDays && (
            <Button loading={memberUntil.isPending} onClick={() => save('')}>
              Remove end date
            </Button>
          )}
          <Button variant="primary" loading={memberUntil.isPending} disabled={!until || until === member.until} onClick={() => save(until)}>
            Save
          </Button>
        </>
      }
    >
      <div className="stack">
        {!details?.until && <ErrorAlert error={memberUntil.error} />}
        <EndDateField
          id={`${id}-until`}
          label="Member until"
          value={until}
          onChange={setUntil}
          maxDays={maxDays}
          error={details?.until}
          hint={
            maxDays
              ? `Their last day in ${group.name}; the portal removes them the day after. At most ${maxDays} days from today.`
              : `Their last day in ${group.name}; the portal removes them the day after.`
          }
        />
      </div>
    </Modal>
  );
}

/** Most lines of a pasted list (the server's limit). */
const MAX_PASTED = 200;

/**
 * Adds many people at once from a pasted list of logins, e-mail addresses or DNs: the list is
 * looked up first, and the owner sees who was found, who was not and which lines are ambiguous
 * before adding the people found.
 */
function BulkAddDialog({ group, maxDays, onClose }: { group: GroupSummary; maxDays?: number | null; onClose: () => void }) {
  const id = useId();
  const toast = useToast();
  const { resolvePeople, members: change } = useGroupMutations();
  const [text, setText] = useState('');
  const [until, setUntil] = useState('');
  const [result, setResult] = useState<GroupPeopleResolved | null>(null);
  const lines = [...new Set(text.split(/\r?\n/).map((l) => l.trim()).filter(Boolean))];
  const tooMany = lines.length > MAX_PASTED;
  const toAdd = result?.found.filter((p) => !p.member) ?? [];
  const details = (change.error as { details?: Record<string, string> } | null)?.details;
  const check = () => resolvePeople.mutate({ definitionId: group.definitionId, dn: group.dn, lines }, { onSuccess: setResult });
  const add = () =>
    change.mutate(
      { definitionId: group.definitionId, dn: group.dn, add: toAdd.map((p) => p.value), remove: [], until },
      {
        onSuccess: (r) => {
          toast.success(`${r.added.length} ${r.added.length === 1 ? 'person was' : 'people were'} added to ${group.name}.`);
          onClose();
        },
      },
    );
  return (
    <Modal
      open
      wide
      onClose={onClose}
      title={`Add people to ${group.name} from a list`}
      footer={
        result ? (
          <>
            <Button onClick={onClose}>Cancel</Button>
            <Button
              onClick={() => {
                change.reset();
                setResult(null);
              }}
            >
              Back to the list
            </Button>
            <Button variant="primary" loading={change.isPending} disabled={!toAdd.length} onClick={add}>
              {toAdd.length === 1 ? 'Add 1 person' : `Add ${toAdd.length} people`}
            </Button>
          </>
        ) : (
          <>
            <Button onClick={onClose}>Cancel</Button>
            <Button variant="primary" loading={resolvePeople.isPending} disabled={!lines.length || tooMany} onClick={check}>
              Check the list
            </Button>
          </>
        )
      }
    >
      {result ? (
        <div className="stack">
          {!details?.until && <ErrorAlert error={change.error} />}
          <section aria-label="Found">
            <h3 className="section-title">
              Found ({result.found.length}){toAdd.length !== result.found.length && `, ${toAdd.length} to add`}
            </h3>
            {result.found.length === 0 ? (
              <p className="muted">Nobody on the list was found.</p>
            ) : (
              <ul className="plain-list person-list">
                {result.found.map((p) => (
                  <li key={p.value} className="person-result">
                    <span>
                      {p.name}
                      {p.dn && <span className="muted small mono"> {p.dn}</span>}
                    </span>
                    {p.member && <Badge>Already a member</Badge>}
                  </li>
                ))}
              </ul>
            )}
          </section>
          {result.notFound.length > 0 && (
            <section aria-label="Not found">
              <h3 className="section-title">Not found ({result.notFound.length})</h3>
              <ul className="plain-list person-list">
                {result.notFound.map((l) => (
                  <li key={l} className="mono small">
                    {l}
                  </li>
                ))}
              </ul>
            </section>
          )}
          {result.ambiguous.length > 0 && (
            <section aria-label="Ambiguous">
              <h3 className="section-title">Ambiguous ({result.ambiguous.length})</h3>
              <p className="muted small">Several people match these lines. Use their login or DN instead.</p>
              <ul className="plain-list person-list">
                {result.ambiguous.map((a) => (
                  <li key={a.line}>
                    <span className="mono small">{a.line}</span>
                    <div className="muted small">{a.matches.join(', ')}</div>
                  </li>
                ))}
              </ul>
            </section>
          )}
          {toAdd.length > 0 && (
            <EndDateField
              id={`${id}-until`}
              label="End date (optional)"
              value={until}
              onChange={setUntil}
              maxDays={maxDays}
              error={details?.until}
              hint={maxDays ? `Their last day in the group. Memberships here last at most ${maxDays} days; empty: that long.` : 'Their last day in the group. Empty: no end date.'}
            />
          )}
        </div>
      ) : (
        <div className="stack">
          <ErrorAlert error={resolvePeople.error} />
          <FormRow
            label="People to add"
            htmlFor={`${id}-lines`}
            error={tooMany ? `At most ${MAX_PASTED} lines at once: this list has ${lines.length}.` : undefined}
            hint={`Logins, e-mail addresses or DNs, one per line (at most ${MAX_PASTED}). Nothing is added until you have seen who was found.`}
          >
            <textarea id={`${id}-lines`} className="input mono" rows={10} value={text} onChange={(e) => setText(e.target.value)} />
          </FormRow>
        </div>
      )}
    </Modal>
  );
}
