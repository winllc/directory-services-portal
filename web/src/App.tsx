import { Route, Routes } from 'react-router-dom';
import { Layout, UnlicensedLayout } from './components/Layout';
import { RequireAdmin, RequireAuth } from './lib/auth';
import { LicenseGate, RequireLicense } from './lib/license';
import type { LicensedFeatureId } from '@dsp/shared';
import { LoginPage } from './pages/LoginPage';
import { HomePage } from './pages/HomePage';
import { DirectoryPage } from './pages/DirectoryPage';
import { CreateEntryPage, EntryPage } from './pages/EntryPage';
import { MyProfilePage } from './pages/MyProfilePage';
import { SchemaPage } from './pages/admin/SchemaPage';
import { FormsPage } from './pages/admin/FormsPage';
import { FormEditorPage } from './pages/admin/FormEditorPage';
import { DefinitionsPage } from './pages/admin/DefinitionsPage';
import { DefinitionEditorPage } from './pages/admin/DefinitionEditorPage';
import { PermissionsPage } from './pages/admin/PermissionsPage';
import { AuditPage } from './pages/admin/AuditPage';
import { LicensePage } from './pages/admin/LicensePage';
import { NotLicensedPage } from './pages/NotLicensedPage';
import { AnalysisListPage } from './pages/admin/AnalysisListPage';
import { AnalysisEditorPage } from './pages/admin/AnalysisEditorPage';
import { AnalysisResultsPage } from './pages/admin/AnalysisResultsPage';
import { MetricsHomePage } from './pages/metrics/MetricsHomePage';
import { OverviewPage } from './pages/metrics/OverviewPage';
import { DashboardPage } from './pages/metrics/DashboardPage';
import { DashboardEditorPage } from './pages/metrics/DashboardEditorPage';
import { ExplorerPage } from './pages/metrics/ExplorerPage';
import { EmptyState } from './components/ui';
import { SiteBanners } from './components/SiteBanners';

const admin = (el: React.ReactNode) => <RequireAdmin>{el}</RequireAdmin>;
const licensed = (feature: LicensedFeatureId) => (el: React.ReactNode) => <RequireLicense feature={feature}>{el}</RequireLicense>;
const directories = licensed('directory-editor');
const profile = licensed('self-service-profile');
const metrics = licensed('metrics');
const forms = (el: React.ReactNode) => admin(licensed('form-builder')(el));
const analysis = (el: React.ReactNode) => admin(licensed('data-analysis')(el));

/** Signed in, before the license: the full app, or (no license in effect) only the license screen. */
const shell = (
  <RequireAuth>
    <LicenseGate unlicensed={(isAdmin) => <UnlicensedLayout>{isAdmin ? <LicensePage /> : <NotLicensedPage />}</UnlicensedLayout>}>
      <Layout />
    </LicenseGate>
  </RequireAuth>
);

export function App() {
  return (
    <>
      <SiteBanners />
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route element={shell}>
          <Route index element={<HomePage />} />
          <Route path="me" element={profile(<MyProfilePage />)} />
          <Route path="d/:slug" element={directories(<DirectoryPage />)} />
          <Route path="d/:slug/new" element={directories(<CreateEntryPage />)} />
          <Route path="d/:slug/entry" element={directories(<EntryPage />)} />
          <Route path="metrics" element={metrics(<MetricsHomePage />)} />
          <Route path="metrics/explore" element={metrics(<ExplorerPage />)} />
          <Route path="metrics/directories/:slug" element={metrics(<OverviewPage />)} />
          <Route path="metrics/dashboards/:id" element={metrics(<DashboardPage />)} />
          <Route path="metrics/dashboards/:id/edit" element={admin(metrics(<DashboardEditorPage />))} />
          <Route path="admin/schema" element={admin(<SchemaPage />)} />
          <Route path="admin/forms" element={forms(<FormsPage />)} />
          <Route path="admin/forms/:id" element={forms(<FormEditorPage />)} />
          <Route path="admin/definitions" element={forms(<DefinitionsPage />)} />
          <Route path="admin/definitions/:id" element={forms(<DefinitionEditorPage />)} />
          <Route path="admin/permissions" element={admin(<PermissionsPage />)} />
          <Route path="admin/audit" element={admin(<AuditPage />)} />
          <Route path="admin/license" element={admin(<LicensePage />)} />
          <Route path="unlicensed" element={<NotLicensedPage />} />
          <Route path="admin/analysis" element={analysis(<AnalysisListPage />)} />
          <Route path="admin/analysis/results/:jobId" element={analysis(<AnalysisResultsPage />)} />
          <Route path="admin/analysis/:id" element={analysis(<AnalysisEditorPage />)} />
          <Route path="*" element={<EmptyState title="Page not found">The page you requested does not exist.</EmptyState>} />
        </Route>
      </Routes>
    </>
  );
}
