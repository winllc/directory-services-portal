import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CalendarClock } from 'lucide-react';
import type { SponsoredEntry } from '@dsp/shared';
import { api } from '../api/client';
import { Button, EmptyState, ErrorAlert, PageHeader, Spinner, useToast } from '../components/ui';

/** Entries with an end date that the signed-in user sponsors, and extending them. */
export function SponsoredPage() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const list = useQuery({ queryKey: ['sponsored'], queryFn: () => api.get<SponsoredEntry[]>('/api/sponsored') });
  const [dates, setDates] = useState<Record<string, string>>({});
  const renew = useMutation({
    mutationFn: (e: SponsoredEntry) => api.post<SponsoredEntry>('/api/sponsored/renew', { definitionId: e.definitionId, dn: e.dn, date: dates[e.dn] }),
    onSuccess: (e) => {
      toast.success(`${e.name} now ends on ${e.endDate}.`);
      queryClient.invalidateQueries({ queryKey: ['sponsored'] });
    },
    onError: (err) => toast.error(err),
  });
  if (list.isLoading) return <Spinner label="Loading…" />;
  const rows = list.data ?? [];
  return (
    <div className="stack">
      <PageHeader title="Sponsored entries" subtitle="Entries that end on a date and name you as their sponsor. Extend those that are still needed." />
      <ErrorAlert error={list.error} />
      {!rows.length ? (
        <EmptyState icon={<CalendarClock size={28} />} title="Nothing to sponsor">
          Entries that end on a date and name you as sponsor are listed here.
        </EmptyState>
      ) : (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Entry</th>
                <th>Ends</th>
                <th>New end date</th>
                <th className="actions-col">
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((e) => (
                <tr key={e.dn}>
                  <td>
                    {e.name}
                    <div className="muted small">{e.definitionName}</div>
                  </td>
                  <td className="nowrap">
                    {e.endDate ?? 'not set'}
                    {e.endDate && <div className="muted small">{e.daysLeft < 0 ? 'ended' : e.daysLeft === 0 ? 'today' : `in ${e.daysLeft} days`}</div>}
                  </td>
                  <td>
                    <label className="sr-only" htmlFor={`renew-${e.dn}`}>
                      New end date for {e.name}
                    </label>
                    <input
                      id={`renew-${e.dn}`}
                      className="input"
                      type="date"
                      max={e.latestDate}
                      value={dates[e.dn] ?? ''}
                      onChange={(ev) => setDates((d) => ({ ...d, [e.dn]: ev.target.value }))}
                    />
                    <div className="muted small">At most {e.latestDate}</div>
                  </td>
                  <td className="actions-col">
                    <Button size="sm" disabled={!dates[e.dn]} loading={renew.isPending && renew.variables?.dn === e.dn} onClick={() => renew.mutate(e)}>
                      Extend
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
