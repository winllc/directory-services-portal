import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Check, LogOut, Search, UserPlus, Users, X } from 'lucide-react';
import type { AccessRequest, GroupDirectory, GroupSummary } from '@dsp/shared';
import {
  useAccessRequests,
  useGroupDirectories,
  useGroupMutations,
  useGroupRequestSummary,
  useGroupSearch,
  useMyGroups,
} from '../api/hooks';
import { Alert, Button, ConfirmDialog, EmptyState, ErrorAlert, PageHeader, Spinner, Tabs, useDebounced, useToast } from '../components/ui';
import { DecisionDialog, GroupBadges, GroupLink, RequestAccessDialog, RequestStatus, when } from '../components/groups/GroupParts';

type Tab = 'find' | 'mine' | 'requests' | 'review';
const TABS: Tab[] = ['find', 'mine', 'requests', 'review'];

/** Self-service groups: find groups and ask to join, see one's groups and requests, and decide requests for groups one owns. */
export function GroupsPage() {
  const [params, setParams] = useSearchParams();
  const dirs = useGroupDirectories();
  const summary = useGroupRequestSummary(true);
  const tab = (TABS.includes(params.get('tab') as Tab) ? params.get('tab') : 'find') as Tab;
  const setTab = (t: Tab) => setParams(t === 'find' ? {} : { tab: t }, { replace: true });

  if (dirs.isLoading) return <Spinner label="Loading…" />;
  if (!dirs.data?.length) {
    return (
      <EmptyState icon={<Users size={28} />} title="No groups to join">
        Your administrator hasn't opened any group directory to requests.
      </EmptyState>
    );
  }
  const toReview = summary.data?.toReview ?? 0;
  const myPending = summary.data?.myPending ?? 0;

  return (
    <div className="page">
      <PageHeader
        icon={<Users size={22} />}
        title="Group access"
        subtitle="Find groups and ask to join them. Group owners approve requests."
      />
      <Tabs<Tab>
        value={tab}
        onChange={setTab}
        tabs={[
          { id: 'find', label: 'Find groups' },
          { id: 'mine', label: 'My groups' },
          { id: 'requests', label: <>My requests{myPending > 0 && <span className="tab-count">{myPending}</span>}</> },
          { id: 'review', label: <>To review{toReview > 0 && <span className="tab-count tab-count-attention">{toReview}</span>}</> },
        ]}
      />
      {tab === 'find' && <FindGroups directories={dirs.data} />}
      {tab === 'mine' && <MyGroups directories={dirs.data} />}
      {tab === 'requests' && <MyRequests />}
      {tab === 'review' && <Review />}
    </div>
  );
}

function settingsOf(directories: GroupDirectory[], definitionId: string) {
  return directories.find((d) => d.id === definitionId);
}

/** Request access, withdraw the open request, or nothing (member, closed group). */
function GroupAction({ group, directories }: { group: GroupSummary; directories: GroupDirectory[] }) {
  const [asking, setAsking] = useState(false);
  const { cancel } = useGroupMutations();
  const toast = useToast();
  if (group.member) return null;
  if (group.pendingRequestId) {
    return (
      <Button
        size="sm"
        variant="ghost"
        loading={cancel.isPending}
        onClick={() => cancel.mutate(group.pendingRequestId!, { onSuccess: () => toast.success('Request withdrawn.'), onError: (e) => toast.error(e) })}
      >
        Withdraw request
      </Button>
    );
  }
  if (!group.requestable) return null;
  return (
    <>
      <Button size="sm" icon={<UserPlus size={14} />} onClick={() => setAsking(true)}>
        Request access
      </Button>
      <RequestAccessDialog
        group={group}
        requireReason={settingsOf(directories, group.definitionId)?.requireReason ?? false}
        open={asking}
        onClose={() => setAsking(false)}
      />
    </>
  );
}

function GroupTable({ groups, directories, showDirectory, action }: { groups: GroupSummary[]; directories: GroupDirectory[]; showDirectory: boolean; action: (g: GroupSummary) => React.ReactNode }) {
  return (
    <div className="table-wrap">
      <table className="table">
        <thead>
          <tr>
            <th>Group</th>
            {showDirectory && <th>Directory</th>}
            <th>Status</th>
            <th className="actions-col">
              <span className="sr-only">Actions</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {groups.map((g) => (
            <tr key={`${g.definitionId}|${g.dn}`}>
              <td>
                <GroupLink definitionId={g.definitionId} dn={g.dn}>
                  {g.name}
                </GroupLink>
                {g.description && <div className="muted small">{g.description}</div>}
              </td>
              {showDirectory && <td className="muted">{g.definitionName}</td>}
              <td>
                <GroupBadges group={g} />
              </td>
              <td className="actions-col">{action(g)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function FindGroups({ directories }: { directories: GroupDirectory[] }) {
  const [search, setSearch] = useState('');
  const [definition, setDefinition] = useState('');
  const q = useDebounced(search.trim(), 300);
  const found = useGroupSearch(q, definition);
  const groups = found.data?.groups ?? [];
  return (
    <div className="stack">
      <div className="toolbar">
        <div className="search-box">
          <Search size={16} aria-hidden />
          <input
            className="input"
            type="search"
            placeholder="Search groups by name or description…"
            aria-label="Search groups"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
        {directories.length > 1 && (
          <select className="input select" aria-label="Directory" value={definition} onChange={(e) => setDefinition(e.target.value)}>
            <option value="">All group directories</option>
            {directories.map((d) => (
              <option key={d.id} value={d.id}>
                {d.name}
              </option>
            ))}
          </select>
        )}
        <div className="toolbar-meta muted">
          {found.isFetching ? <span className="spinner spinner-sm" /> : `${groups.length} ${groups.length === 1 ? 'group' : 'groups'}`}
        </div>
      </div>
      <ErrorAlert error={found.error} />
      {found.data?.truncated && <Alert tone="warning">Only the first {groups.length} groups are shown. Refine your search to narrow the results.</Alert>}
      {found.data && groups.length === 0 ? (
        <EmptyState icon={<Search size={28} />} title="No groups found">
          {q ? `Nothing matches “${q}”.` : 'There are no groups in these directories.'}
        </EmptyState>
      ) : (
        <GroupTable groups={groups} directories={directories} showDirectory={directories.length > 1} action={(g) => <GroupAction group={g} directories={directories} />} />
      )}
    </div>
  );
}

function MyGroups({ directories }: { directories: GroupDirectory[] }) {
  const mine = useMyGroups();
  const { leave } = useGroupMutations();
  const toast = useToast();
  const [leaving, setLeaving] = useState<GroupSummary | null>(null);
  if (mine.isLoading) return <Spinner label="Loading…" />;
  const groups = mine.data?.groups ?? [];
  return (
    <div className="stack">
      <ErrorAlert error={mine.error} />
      {groups.length === 0 ? (
        <EmptyState icon={<Users size={28} />} title="You aren't in any group yet">
          Find a group and ask to join it.
        </EmptyState>
      ) : (
        <GroupTable
          groups={groups}
          directories={directories}
          showDirectory={directories.length > 1}
          action={(g) =>
            settingsOf(directories, g.definitionId)?.allowLeave && (
              <Button size="sm" variant="ghost" icon={<LogOut size={14} />} onClick={() => setLeaving(g)}>
                Leave
              </Button>
            )
          }
        />
      )}
      <ConfirmDialog
        open={!!leaving}
        title={`Leave ${leaving?.name}?`}
        message="You lose the access this group gives. To join again, you'll have to ask its owners."
        confirmLabel="Leave group"
        loading={leave.isPending}
        onCancel={() => setLeaving(null)}
        onConfirm={() =>
          leaving &&
          leave.mutate(
            { definitionId: leaving.definitionId, dn: leaving.dn },
            {
              onSuccess: () => {
                toast.success(`You left ${leaving.name}.`);
                setLeaving(null);
              },
              onError: (e) => {
                toast.error(e);
                setLeaving(null);
              },
            },
          )
        }
      />
    </div>
  );
}

function MyRequests() {
  const requests = useAccessRequests('mine');
  const { cancel } = useGroupMutations();
  const toast = useToast();
  if (requests.isLoading) return <Spinner label="Loading…" />;
  const list = requests.data ?? [];
  if (!list.length) {
    return (
      <EmptyState icon={<UserPlus size={28} />} title="No requests">
        Requests you make to join groups are listed here with their answers.
      </EmptyState>
    );
  }
  return (
    <div className="table-wrap">
      <ErrorAlert error={requests.error} />
      <table className="table">
        <thead>
          <tr>
            <th>Group</th>
            <th>Asked</th>
            <th>Status</th>
            <th>Answer</th>
            <th className="actions-col">
              <span className="sr-only">Actions</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {list.map(({ request: r, definitionName }) => (
            <tr key={r.id}>
              <td>
                <GroupLink definitionId={r.definitionId} dn={r.groupDn}>
                  {r.groupName}
                </GroupLink>
                {definitionName && <div className="muted small">{definitionName}</div>}
                {r.reason && <div className="small request-quote">“{r.reason}”</div>}
              </td>
              <td className="muted nowrap">{when(r.createdAt)}</td>
              <td>
                <RequestStatus status={r.status} />
              </td>
              <td>
                {r.status !== 'pending' && r.status !== 'cancelled' && (
                  <>
                    <div className="small">
                      {r.decidedByName ?? r.decidedBy} · <span className="muted">{when(r.decidedAt)}</span>
                    </div>
                    {r.note && <div className="small request-quote">“{r.note}”</div>}
                  </>
                )}
              </td>
              <td className="actions-col">
                {r.status === 'pending' && (
                  <Button size="sm" variant="ghost" onClick={() => cancel.mutate(r.id, { onSuccess: () => toast.success('Request withdrawn.'), onError: (e) => toast.error(e) })}>
                    Withdraw
                  </Button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Open requests to decide: a table with the requester, their reason and approve / reject. */
export function RequestQueue({ requests, showGroup = true }: { requests: { request: AccessRequest; definitionName?: string; canDecide: boolean }[]; showGroup?: boolean }) {
  const [deciding, setDeciding] = useState<{ request: AccessRequest; approve: boolean } | null>(null);
  return (
    <>
      <div className="table-wrap">
        <table className="table">
          <thead>
            <tr>
              <th>Who</th>
              {showGroup && <th>Group</th>}
              <th>Reason</th>
              <th>Asked</th>
              <th className="actions-col">
                <span className="sr-only">Decision</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {requests.map(({ request: r, definitionName, canDecide }) => (
              <tr key={r.id}>
                <td>
                  {r.requesterName ?? r.requester}
                  {r.requesterDn && (
                    <div className="muted small mono ellipsis" title={r.requesterDn}>
                      {r.requesterDn}
                    </div>
                  )}
                </td>
                {showGroup && (
                  <td>
                    <GroupLink definitionId={r.definitionId} dn={r.groupDn}>
                      {r.groupName}
                    </GroupLink>
                    {definitionName && <div className="muted small">{definitionName}</div>}
                  </td>
                )}
                <td className="request-reason-cell">{r.reason ?? <span className="muted">—</span>}</td>
                <td className="muted nowrap">{when(r.createdAt)}</td>
                <td className="actions-col">
                  {canDecide ? (
                    <div className="toolbar-actions">
                      <Button size="sm" icon={<Check size={14} />} onClick={() => setDeciding({ request: r, approve: true })}>
                        Approve
                      </Button>
                      <Button size="sm" variant="ghost" icon={<X size={14} />} onClick={() => setDeciding({ request: r, approve: false })}>
                        Reject
                      </Button>
                    </div>
                  ) : (
                    <span className="muted small">Your request</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <DecisionDialog request={deciding?.request ?? null} approve={deciding?.approve ?? true} onClose={() => setDeciding(null)} />
    </>
  );
}

function Review() {
  const open = useAccessRequests('review');
  const decided = useAccessRequests('decided');
  if (open.isLoading) return <Spinner label="Loading…" />;
  const pending = open.data ?? [];
  const history = (decided.data ?? []).slice(0, 50);
  return (
    <div className="stack">
      <ErrorAlert error={open.error} />
      {pending.length === 0 ? (
        <EmptyState icon={<Check size={28} />} title="Nothing to review">
          Requests to join groups you own appear here.
        </EmptyState>
      ) : (
        <RequestQueue requests={pending} />
      )}
      {history.length > 0 && (
        <section className="stack">
          <h2 className="section-title">Recently decided</h2>
          <div className="table-wrap">
            <table className="table table-compact">
              <thead>
                <tr>
                  <th>Who</th>
                  <th>Group</th>
                  <th>Decision</th>
                  <th>By</th>
                </tr>
              </thead>
              <tbody>
                {history.map(({ request: r }) => (
                  <tr key={r.id}>
                    <td>{r.requesterName ?? r.requester}</td>
                    <td>
                      <GroupLink definitionId={r.definitionId} dn={r.groupDn}>
                        {r.groupName}
                      </GroupLink>
                    </td>
                    <td>
                      <RequestStatus status={r.status} />
                      {r.note && <div className="small request-quote">“{r.note}”</div>}
                    </td>
                    <td className="muted small">
                      {r.decidedByName ?? r.decidedBy} · {when(r.decidedAt)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </div>
  );
}
