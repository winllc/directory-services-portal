import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Check, LogOut, Plus, Search, UserPlus, Users, X } from 'lucide-react';
import type { AccessRequest, GroupDirectory, GroupSummary } from '@dsp/shared';
import {
  useAccessRequests,
  useGroupContainers,
  useGroupDirectories,
  useGroupMutations,
  useGroupRequestSummary,
  useGroupSearch,
  useMyGroups,
} from '../api/hooks';
import { Alert, Button, ConfirmDialog, EmptyState, ErrorAlert, PageHeader, Spinner, Tabs, useDebounced, useToast } from '../components/ui';
import { useFeature } from '../lib/license';
import { BulkDecisionDialog, day, DecisionDialog, expiredText, GroupBadges, GroupLink, JoinButton, RequestAccessDialog, RequestStatus, MAX_DECISIONS, when } from '../components/groups/GroupParts';

type Tab = 'find' | 'mine' | 'requests' | 'review';
const TABS: Tab[] = ['find', 'mine', 'requests', 'review'];

/** Self-service groups: find groups and ask to join, see one's groups and requests, and decide requests for groups one owns. */
export function GroupsPage() {
  const [params, setParams] = useSearchParams();
  const dirs = useGroupDirectories();
  const summary = useGroupRequestSummary(true);
  // Owners of an organizational unit create groups in it: like creating entries, the directory editor's.
  const editor = useFeature('directory-editor');
  const containers = useGroupContainers(editor.enabled);
  const navigate = useNavigate();
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
        actions={
          editor.enabled &&
          !!containers.data?.length && (
            <Button variant="primary" icon={<Plus size={16} />} onClick={() => navigate('/groups/new')}>
              New group
            </Button>
          )
        }
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

/** Join (an open group), request access, withdraw the open request, or nothing (member, closed group). */
function GroupAction({ group, directories }: { group: GroupSummary; directories: GroupDirectory[] }) {
  const [asking, setAsking] = useState(false);
  const { cancel } = useGroupMutations();
  const toast = useToast();
  if (group.member) return null;
  if (group.open) return <JoinButton group={group} size="sm" />;
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
        maxMembershipDays={settingsOf(directories, group.definitionId)?.maxMembershipDays}
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
                {r.until && <div className="muted small">Asked for until {day(r.until)}</div>}
              </td>
              <td className="muted nowrap">{when(r.createdAt)}</td>
              <td>
                <RequestStatus status={r.status} />
                {r.status === 'approved' && r.grantedUntil && <div className="muted small">Access until {day(r.grantedUntil)}</div>}
              </td>
              <td>
                {r.status === 'expired' && (
                  <div className="small">
                    {expiredText(r)} You can ask again. · <span className="muted">{when(r.decidedAt)}</span>
                  </div>
                )}
                {(r.status === 'approved' || r.status === 'rejected') && (
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

/**
 * Open requests to decide: a table with the requester, their reason and approve / reject. With
 * several to decide, they can be ticked and approved or rejected together. `empty` is shown when
 * there are none (here, so the answer to a decision stays up when it was the last request).
 */
export function RequestQueue({
  requests,
  showGroup = true,
  empty,
}: {
  requests: { request: AccessRequest; definitionName?: string; canDecide: boolean }[];
  showGroup?: boolean;
  empty: React.ReactNode;
}) {
  const [deciding, setDeciding] = useState<{ request: AccessRequest; approve: boolean } | null>(null);
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [bulk, setBulk] = useState<{ requests: AccessRequest[]; approve: boolean } | null>(null);
  const decidable = requests.filter((r) => r.canDecide).map((r) => r.request);
  const selectable = decidable.length > 1;
  // Only what is still shown counts: a request decided meanwhile drops out of the selection.
  const chosen = decidable.filter((r) => selected.has(r.id));
  const all = chosen.length === decidable.length;
  const toggle = (id: string) => {
    const next = new Set(selected);
    if (!next.delete(id)) next.add(id);
    setSelected(next);
  };
  return (
    <>
      {selectable && chosen.length > 0 && (
        <div className="toolbar" role="group" aria-label="Selected requests">
          <span className="muted">{chosen.length} selected</span>
          <div className="toolbar-actions">
            <Button size="sm" icon={<Check size={14} />} disabled={chosen.length > MAX_DECISIONS} onClick={() => setBulk({ requests: chosen, approve: true })}>
              Approve selected
            </Button>
            <Button size="sm" variant="ghost" icon={<X size={14} />} disabled={chosen.length > MAX_DECISIONS} onClick={() => setBulk({ requests: chosen, approve: false })}>
              Reject selected
            </Button>
          </div>
          {chosen.length > MAX_DECISIONS && <span className="muted small">At most {MAX_DECISIONS} at once.</span>}
        </div>
      )}
      {requests.length === 0 ? (
        empty
      ) : (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                {selectable && (
                  <th className="check-col">
                    <input type="checkbox" aria-label="Select all shown" checked={all} onChange={() => setSelected(all ? new Set() : new Set(decidable.map((r) => r.id)))} />
                  </th>
                )}
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
                  {selectable && (
                    <td className="check-col">
                      {canDecide && (
                        <input
                          type="checkbox"
                          aria-label={`Select the request of ${r.requesterName ?? r.requester}${showGroup ? ` for ${r.groupName}` : ''}`}
                          checked={selected.has(r.id)}
                          onChange={() => toggle(r.id)}
                        />
                      )}
                    </td>
                  )}
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
                  <td className="request-reason-cell">
                    {r.reason ?? <span className="muted">—</span>}
                    {r.until && <div className="muted small">Until {day(r.until)}</div>}
                  </td>
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
      )}
      <DecisionDialog request={deciding?.request ?? null} approve={deciding?.approve ?? true} onClose={() => setDeciding(null)} />
      {bulk && (
        <BulkDecisionDialog
          requests={bulk.requests}
          approve={bulk.approve}
          onClose={() => {
            setBulk(null);
            setSelected(new Set());
          }}
        />
      )}
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
      <RequestQueue
        requests={pending}
        empty={
          <EmptyState icon={<Check size={28} />} title="Nothing to review">
            Requests to join groups you own appear here.
          </EmptyState>
        }
      />
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
