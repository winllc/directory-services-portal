import { useRef, useState } from 'react';
import { Check, Copy, KeyRound, Upload, X } from 'lucide-react';
import type { LicenseStatus } from '@dsp/shared';
import { useLicense, useLicenseUpload } from '../../api/hooks';
import { Alert, Badge, Button, Card, ErrorAlert, PageHeader, Spinner } from '../../components/ui';

const STATE_LABELS: Record<LicenseStatus['state'], string> = {
  VALID: 'Valid',
  GRACE: 'Expired: grace period',
  EXPIRED: 'Expired',
  RELEASE_NOT_COVERED: 'Release not covered',
  NOT_YET_VALID: 'Not yet valid',
  WRONG_PRODUCT: 'Wrong product',
  WRONG_INSTALLATION: 'Bound to another installation',
  SUPERSEDED: 'Superseded',
  MISSING: 'No license',
  INVALID: 'Invalid',
};

const day = (iso?: string) => (iso ? iso.slice(0, 10) : undefined);

function addDays(iso: string, days: number) {
  return new Date(new Date(iso).getTime() + days * 86_400_000).toISOString();
}

function modeTone(status: LicenseStatus) {
  if (status.mode === 'UNLICENSED') return 'danger' as const;
  if (status.mode === 'READ_ONLY' || status.state === 'GRACE') return 'warning' as const;
  return 'success' as const;
}

function InstallationId({ id, error }: { id?: string; error?: string }) {
  const [copied, setCopied] = useState(false);
  if (!id) return <Alert tone="danger" title="No installation id">{error ?? 'The installation id is not available.'}</Alert>;
  return (
    <div className="installation-id" role="group" aria-label="Installation id">
      <code>{id}</code>
      <Button
        size="sm"
        icon={copied ? <Check size={14} /> : <Copy size={14} />}
        onClick={() =>
          navigator.clipboard?.writeText(id).then(
            () => {
              setCopied(true);
              setTimeout(() => setCopied(false), 2000);
            },
            () => setCopied(false),
          )
        }
      >
        {copied ? 'Copied' : 'Copy'}
      </Button>
    </div>
  );
}

/** The file's text (FileReader rather than File.text(), which older browsers and jsdom lack). */
function readText(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result ?? ''));
    reader.onerror = () => reject(reader.error ?? new Error('The file could not be read'));
    reader.readAsText(file);
  });
}

function UploadForm() {
  const upload = useLicenseUpload();
  const input = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const submit = async () => {
    if (!file) return;
    upload.mutate(await readText(file));
  };
  return (
    <div className="license-upload">
      <p className="muted">
        Download the license file (<code>.lic</code>) from your account on the ENS licensing portal, then install it here.
        It replaces the installed license and takes effect at once.
      </p>
      <div className="license-upload-row">
        <input
          ref={input}
          type="file"
          accept=".lic,text/plain"
          aria-label="License file"
          onChange={(e) => {
            upload.reset();
            setFile(e.target.files?.[0] ?? null);
          }}
        />
        <Button variant="primary" icon={<Upload size={16} />} disabled={!file} loading={upload.isPending} onClick={submit}>
          Install license
        </Button>
      </div>
      {upload.isError && (
        <Alert tone="danger" title="The license was not installed">
          {upload.error.message}
        </Alert>
      )}
      {upload.data && (
        <Alert tone={upload.data.license.mode === 'FULL' ? 'success' : 'warning'} title="License installed">
          {upload.data.message}
        </Alert>
      )}
    </div>
  );
}

/** Administration > License: the installed license, this installation's id and the upload. */
export function LicensePage() {
  const license = useLicense();
  const status = license.data;
  if (license.isLoading) return <Spinner />;
  if (!status) return <ErrorAlert error={license.error} />;
  const d = status.details;
  const graceEnds = d?.expiresAt && d.graceDays ? day(addDays(d.expiresAt, d.graceDays)) : undefined;
  return (
    <div className="page">
      <PageHeader
        icon={<KeyRound size={22} />}
        title="License"
        subtitle="The ENS Solutions license this portal runs under."
      />
      {status.mode === 'UNLICENSED' && (
        <Alert tone="danger" title="This portal is not licensed">
          Until a license is installed, only sign-in and this page work. Users see a "not licensed" page.
        </Alert>
      )}

      <Card
        title="Status"
        actions={<Badge tone={modeTone(status)}>{STATE_LABELS[status.state] ?? status.state}</Badge>}
      >
        <p className="license-message">{status.message}</p>
        {d?.licensee && (
          <dl className="detail-grid">
            <div>
              <dt>Licensed to</dt>
              <dd>
                {d.licensee}
                {d.email && <div className="muted">{d.email}</div>}
              </dd>
            </div>
            <div>
              <dt>Edition</dt>
              <dd className="capitalize">{d.edition}{d.support && <span className="muted"> · {d.support} support</span>}</dd>
            </div>
            <div>
              <dt>Valid from</dt>
              <dd>{day(d.notBefore)}</dd>
            </div>
            <div>
              <dt>Expires</dt>
              <dd>
                {d.expiresAt ? day(d.expiresAt) : 'Never (perpetual)'}
                {graceEnds && <div className="muted">Grace period until {graceEnds}</div>}
              </dd>
            </div>
            {d.maintenanceUntil && (
              <div>
                <dt>Maintenance until</dt>
                <dd>{day(d.maintenanceUntil)}</dd>
              </div>
            )}
            {d.size && (
              <div>
                <dt>Size</dt>
                <dd>
                  {d.size.usage ?? `${d.size.quantity.toLocaleString()} ${d.size.unit}`}
                  {d.size.used === undefined && <div className="muted">Shown for reference; the portal doesn't count directory users.</div>}
                </dd>
              </div>
            )}
            <div>
              <dt>License</dt>
              <dd>
                <code>{d.licenseId}</code> <span className="muted">revision {d.revision}</span>
              </dd>
            </div>
            <div>
              <dt>Bound to</dt>
              <dd>{d.boundTo ?? <span className="muted">Any installation</span>}</dd>
            </div>
          </dl>
        )}
        {d && (
          <p className="muted license-file">
            License file on the server: <code>{d.file}</code>
          </p>
        )}
      </Card>

      <Card title="Installation id" subtitle="To bind a license to this installation, enter this id on the ENS licensing portal.">
        <InstallationId id={d?.installationId} error={d?.installationIdError} />
      </Card>

      <Card title="Features" subtitle="Everything the Directory Services Portal can do, and what this license unlocks.">
        <ul className="license-features">
          {status.features.map((f) => (
            <li key={f.id} className={f.enabled ? 'on' : 'off'}>
              {f.enabled ? <Check size={16} aria-label="Included" /> : <X size={16} aria-label="Not included" />}
              <div>
                <strong>{f.label}</strong>
                <div className="muted">{f.description}</div>
              </div>
            </li>
          ))}
        </ul>
      </Card>

      <Card title="Install a license">
        <UploadForm />
      </Card>
    </div>
  );
}
