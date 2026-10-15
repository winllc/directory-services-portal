import type { ReactNode } from 'react';
import { KeyRound } from 'lucide-react';
import type { LicensedFeatureId, LicenseStatus } from '@dsp/shared';
import { useLicense } from '../api/hooks';
import { EmptyState } from '../components/ui';

export const FEATURE_LABELS: Record<LicensedFeatureId, string> = {
  analysis: 'Data analysis',
  metrics: 'Metrics',
  'schema-transfer': 'Schema import and export',
};

/**
 * Whether the license enables a feature. Unknown (still loading, or the status could not be read)
 * counts as not enabled; the server refuses unlicensed requests either way.
 */
export function useFeature(feature: LicensedFeatureId): { enabled: boolean; loading: boolean } {
  const license = useLicense();
  return { enabled: featureEnabled(license.data, feature), loading: license.isLoading };
}

export function featureEnabled(status: LicenseStatus | undefined, feature: LicensedFeatureId): boolean {
  return status?.features.some((f) => f.id === feature && f.enabled) ?? false;
}

export function LicenseRequired({ feature }: { feature: LicensedFeatureId }) {
  return (
    <EmptyState icon={<KeyRound size={28} />} title={`${FEATURE_LABELS[feature]} needs a license`}>
      This feature requires an ENS Solutions license. Ask your administrator to install a license file that includes it.
    </EmptyState>
  );
}

/** Renders the page only when the license enables its feature. */
export function RequireLicense({ feature, children }: { feature: LicensedFeatureId; children: ReactNode }) {
  const { enabled, loading } = useFeature(feature);
  if (loading) return <div className="page-loading"><span className="spinner" /></div>;
  return enabled ? <>{children}</> : <LicenseRequired feature={feature} />;
}

/** One line for administrators: who the portal is licensed to, or why it runs core features only. */
export function licenseSummary(status: LicenseStatus): string {
  if (status.state === 'LICENSED') {
    return `Licensed to ${status.licensee}${status.expires ? ` until ${status.expires}` : ''}`;
  }
  const reason = status.state === 'MISSING' ? 'no license file' : status.problem ?? status.state.toLowerCase();
  return `Unlicensed: core features only (${reason})`;
}
