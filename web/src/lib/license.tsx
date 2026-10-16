import { useEffect, type ReactNode } from 'react';
import { Link, Navigate, useLocation } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, KeyRound } from 'lucide-react';
import type { LicensedFeatureId, LicenseStatus } from '@dsp/shared';
import { keys, useLicense } from '../api/hooks';
import { setLicenseRefusalHandler } from '../api/client';
import { EmptyState } from '../components/ui';
import { useUser } from './auth';

export const FEATURE_LABELS: Record<LicensedFeatureId, string> = {
  'directory-editor': 'Directory editor',
  'form-builder': 'Form builder',
  'self-service-profile': 'Self-service profiles',
  'white-pages': 'White pages',
  'smart-card-sign-in': 'Smart-card sign-in',
  metrics: 'Metrics and dashboards',
  'data-analysis': 'Data-quality analysis',
  'schema-transfer': 'Schema import and export',
};

/** Where the license screen is (administrators), and the page everyone else gets when unlicensed. */
export const LICENSE_PATH = '/admin/license';
export const UNLICENSED_PATH = '/unlicensed';

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
    <EmptyState icon={<KeyRound size={28} />} title={`${FEATURE_LABELS[feature]} is not licensed`}>
      This portal's ENS Solutions license doesn't include this feature. Ask your administrator about a license that does.
    </EmptyState>
  );
}

/** Renders the page only when the license enables its feature. */
export function RequireLicense({ feature, children }: { feature: LicensedFeatureId; children: ReactNode }) {
  const { enabled, loading } = useFeature(feature);
  if (loading) return <div className="page-loading"><span className="spinner" /></div>;
  return enabled ? <>{children}</> : <LicenseRequired feature={feature} />;
}

/** One line for administrators at the bottom of the navigation. */
export function licenseSummary(status: LicenseStatus): string {
  const d = status.details;
  if (status.mode === 'FULL' && d?.licensee) {
    return `Licensed to ${d.licensee}${d.expiresAt ? ` until ${d.expiresAt.slice(0, 10)}` : ''}`;
  }
  if (status.mode === 'READ_ONLY') return 'License expired: read-only';
  return status.mode === 'FULL' ? 'Licensed' : 'Not licensed';
}

/**
 * Re-reads the license whenever the API refuses a request because of it (403 license_required or
 * license_read_only), so the app follows a license that expired or was removed meanwhile.
 */
export function useLicenseRefusalRouting() {
  const qc = useQueryClient();
  useEffect(() => {
    setLicenseRefusalHandler(() => {
      qc.invalidateQueries({ queryKey: keys.license });
    });
    return () => setLicenseRefusalHandler(undefined);
  }, [qc]);
}

/**
 * Between sign-in and the app: while no license is in effect, administrators are routed to the
 * license screen and everyone else to the "not licensed" page (both rendered by `unlicensed`,
 * in a bare shell); otherwise the normal layout (`children`).
 */
export function LicenseGate({ children, unlicensed }: { children: ReactNode; unlicensed: (admin: boolean) => ReactNode }) {
  const user = useUser();
  const license = useLicense();
  const location = useLocation();
  useLicenseRefusalRouting();
  if (license.isLoading) return <div className="page-loading"><span className="spinner" /></div>;
  if (license.data?.mode === 'UNLICENSED') {
    const target = user.isAdmin ? LICENSE_PATH : UNLICENSED_PATH;
    if (location.pathname !== target) return <Navigate to={target} replace state={{ from: location.pathname }} />;
    return <>{unlicensed(user.isAdmin)}</>;
  }
  // Licensed (or the status could not be read: the API still enforces it).
  if (location.pathname === UNLICENSED_PATH) return <Navigate to="/" replace />;
  return <>{children}</>;
}

/** The evaluation banner (everyone), read-only notice (everyone) and renewal reminder (administrators). */
export function LicenseNotices() {
  const user = useUser();
  const license = useLicense().data;
  if (!license) return null;
  return (
    <>
      {license.evaluation && (
        <div className="license-notice license-notice-eval" role="note">
          Evaluation license: not for production use.
        </div>
      )}
      {license.mode === 'READ_ONLY' && (
        <div className="license-notice license-notice-warn" role="status">
          <AlertTriangle size={14} aria-hidden /> The portal is read-only: {license.message}
          {user.isAdmin && (
            <>
              {' '}
              <Link to={LICENSE_PATH}>Install a renewed license</Link>
            </>
          )}
        </div>
      )}
      {user.isAdmin && license.mode === 'FULL' && license.renewalWarning && (
        <div className="license-notice license-notice-warn" role="status">
          <AlertTriangle size={14} aria-hidden />{' '}
          {license.state === 'GRACE'
            ? license.message
            : `The license expires in ${license.daysLeft ?? 0} day${license.daysLeft === 1 ? '' : 's'}.`}{' '}
          <Link to={LICENSE_PATH}>Renew it</Link>
        </div>
      )}
    </>
  );
}
