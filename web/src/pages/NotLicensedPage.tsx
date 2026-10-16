import { KeyRound } from 'lucide-react';
import { EmptyState } from '../components/ui';

/** What users who are not administrators see while no license is in effect. */
export function NotLicensedPage() {
  return (
    <div className="page">
      <EmptyState icon={<KeyRound size={32} />} title="This application is not licensed">
        The Directory Services Portal can't be used until a license is installed. Please ask your administrator.
      </EmptyState>
    </div>
  );
}
