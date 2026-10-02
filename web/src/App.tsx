import { Route, Routes } from 'react-router-dom';
import { Layout } from './components/Layout';
import { RequireAdmin, RequireAuth } from './lib/auth';
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
import { EmptyState } from './components/ui';
import { SiteBanners } from './components/SiteBanners';

const admin = (el: React.ReactNode) => <RequireAdmin>{el}</RequireAdmin>;

export function App() {
  return (
    <>
      <SiteBanners />
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route
          element={
            <RequireAuth>
              <Layout />
            </RequireAuth>
          }
        >
          <Route index element={<HomePage />} />
          <Route path="me" element={<MyProfilePage />} />
          <Route path="d/:slug" element={<DirectoryPage />} />
          <Route path="d/:slug/new" element={<CreateEntryPage />} />
          <Route path="d/:slug/entry" element={<EntryPage />} />
          <Route path="admin/schema" element={admin(<SchemaPage />)} />
          <Route path="admin/forms" element={admin(<FormsPage />)} />
          <Route path="admin/forms/:id" element={admin(<FormEditorPage />)} />
          <Route path="admin/definitions" element={admin(<DefinitionsPage />)} />
          <Route path="admin/definitions/:id" element={admin(<DefinitionEditorPage />)} />
          <Route path="admin/permissions" element={admin(<PermissionsPage />)} />
          <Route path="admin/audit" element={admin(<AuditPage />)} />
          <Route path="*" element={<EmptyState title="Page not found">The page you requested does not exist.</EmptyState>} />
        </Route>
      </Routes>
    </>
  );
}
