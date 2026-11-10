import { useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { ArrowLeft, ChevronLeft, ChevronRight, LogOut, Search, UserMinus, UserPlus, Users } from 'lucide-react';
import type { GroupDetail } from '@dsp/shared';
import { useGroup, useGroupAccessMembers, useGroupMutations, useGroupPeople, useGroupRequestsFor } from '../api/hooks';
import { Alert, Button, Card, ConfirmDialog, EmptyState, ErrorAlert, PageHeader, Spinner, useDebounced, useToast } from '../components/ui';
import { GroupBadges, RequestAccessDialog } from '../components/groups/GroupParts';
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
        <Card title="Owners" subtitle="They approve requests to join.">
          {d.owners.length === 0 ? (
            <p className="muted">This group has no owners: an administrator decides its requests.</p>
          ) : (
            <ul className="plain-list person-list">
              {d.owners.map((o) => (
                <li key={o.value}>
                  {o.name}
                  {o.dn && (
                    <div className="muted small mono ellipsis" title={o.dn}>
                      {o.dn}
                    </div>
                  )}
                </li>
              ))}
              {d.moreOwners && <li className="muted small">and more</li>}
            </ul>
          )}
        </Card>
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
            <p>You are a member of this group.</p>
            {detail.allowLeave && (
              <div>
                <Button icon={<LogOut size={16} />} onClick={() => setLeaving(true)}>
                  Leave group
                </Button>
              </div>
            )}
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
      <RequestAccessDialog group={g} requireReason={detail.requireReason} open={asking} onClose={() => setAsking(false)} />
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

function GroupRequests({ definitionId, dn, count }: { definitionId: string; dn: string; count: number }) {
  const requests = useGroupRequestsFor(definitionId, dn, true);
  const list = requests.data ?? [];
  return (
    <Card title={`Requests to join (${list.length || count})`}>
      <ErrorAlert error={requests.error} />
      {requests.isLoading ? <Spinner /> : list.length === 0 ? <p className="muted">No open requests.</p> : <RequestQueue requests={list} showGroup={false} />}
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
  const [adding, setAdding] = useState('');
  const who = useDebounced(adding.trim(), 300);
  const people = useGroupPeople(g.definitionId, g.dn, who, who.length >= 2);
  const [removing, setRemoving] = useState<{ value: string; name: string } | null>(null);
  const data = members.data;
  const pages = data ? Math.max(1, Math.ceil(data.total / data.pageSize)) : 1;
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
    <Card title={`Members (${detail.memberCount ?? data?.total ?? '…'})`} subtitle="As an owner you can add and remove members directly.">
      <div className="stack">
        <div className="member-add">
          <div className="search-box">
            <UserPlus size={16} aria-hidden />
            <input
              className="input"
              type="search"
              placeholder="Add someone: type a name, login or e-mail…"
              aria-label="Find someone to add"
              value={adding}
              onChange={(e) => setAdding(e.target.value)}
            />
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
                    loading={change.isPending}
                    onClick={() => {
                      apply([p.value], [], `${p.name} was added.`);
                      setAdding('');
                    }}
                  >
                    Add
                  </Button>
                </li>
              ))}
            </ul>
          )}
        </div>

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
                <Button size="sm" variant="ghost" icon={<UserMinus size={14} />} onClick={() => setRemoving({ value: m.value, name: m.name })}>
                  Remove
                </Button>
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
    </Card>
  );
}
