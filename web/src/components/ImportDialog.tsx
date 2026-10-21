import { useId, useState } from 'react';
import type { ImportResult } from '@dsp/shared';
import { useImportEntries } from '../api/hooks';
import { Alert, Badge, Button, ErrorAlert, FormRow, Modal } from './ui';

type Format = 'csv' | 'ldif';
type Mode = 'upsert' | 'create' | 'update';

/** The file's text (UTF-8), through FileReader so it works everywhere File.text() doesn't. */
function readText(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result ?? ''));
    reader.onerror = () => reject(reader.error ?? new Error('The file could not be read'));
    reader.readAsText(file, 'utf-8');
  });
}

const OPERATION_TONE = { create: 'success', update: 'info', unchanged: 'neutral', error: 'danger' } as const;

function Summary({ result }: { result: ImportResult }) {
  return (
    <div className="import-summary" role="status">
      <strong>{result.dryRun ? 'Check:' : 'Imported:'}</strong>
      <Badge tone="success">{result.created} to create</Badge>
      <Badge tone="info">{result.updated} to update</Badge>
      <Badge>{result.unchanged} unchanged</Badge>
      <Badge tone={result.failed ? 'danger' : 'neutral'}>{result.failed} with errors</Badge>
    </div>
  );
}

function Rows({ result }: { result: ImportResult }) {
  const shown = result.rows.filter((r) => r.operation !== 'unchanged').slice(0, 500);
  if (!shown.length) return null;
  return (
    <div className="table-wrap import-rows">
      <table className="table" aria-label={result.dryRun ? 'What the import would do' : 'What the import did'}>
        <thead>
          <tr>
            <th scope="col">Row</th>
            <th scope="col">Entry</th>
            <th scope="col">Result</th>
            <th scope="col">Details</th>
          </tr>
        </thead>
        <tbody>
          {shown.map((r) => (
            <tr key={r.row}>
              <td>{r.row}</td>
              <td className="small">
                <code>{r.dn ?? '—'}</code>
              </td>
              <td>
                <Badge tone={OPERATION_TONE[r.operation]}>{r.operation}</Badge>
              </td>
              <td className="small">
                {r.error ? (
                  <>
                    {r.error}
                    {r.details && (
                      <ul>
                        {Object.entries(r.details).map(([k, v]) => (
                          <li key={k}>{v}</li>
                        ))}
                      </ul>
                    )}
                  </>
                ) : (
                  r.attributes.join(', ')
                )}
                {r.ignored.length > 0 && <div className="muted">Ignored: {r.ignored.join(', ')}</div>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * Import a CSV or LDIF file into a directory: check it first (a dry run that writes nothing), then
 * run it. Rows go through the same permissions, validation and audit as the entry form.
 */
export function ImportDialog({ open, onClose, definitionId, name }: { open: boolean; onClose: () => void; definitionId: string; name: string }) {
  const id = useId();
  const run = useImportEntries();
  const [text, setText] = useState<string | null>(null);
  const [fileName, setFileName] = useState('');
  const [format, setFormat] = useState<Format>('csv');
  const [mode, setMode] = useState<Mode>('upsert');
  const [checked, setChecked] = useState<ImportResult | null>(null);
  const [done, setDone] = useState<ImportResult | null>(null);

  const reset = () => {
    setChecked(null);
    setDone(null);
    run.reset();
  };
  const close = () => {
    reset();
    setText(null);
    setFileName('');
    onClose();
  };
  const go = (dryRun: boolean) =>
    text !== null &&
    run.mutate({ definitionId, format, mode, dryRun, text }, { onSuccess: (r) => (dryRun ? setChecked(r) : setDone(r)) });

  return (
    <Modal
      open={open}
      onClose={close}
      wide
      title={`Import into ${name}`}
      footer={
        done ? (
          <Button variant="primary" onClick={close}>
            Done
          </Button>
        ) : (
          <>
            <Button onClick={close}>Cancel</Button>
            <Button onClick={() => go(true)} disabled={text === null} loading={run.isPending && !checked}>
              Check file
            </Button>
            <Button
              variant="primary"
              onClick={() => go(false)}
              disabled={!checked || checked.created + checked.updated === 0}
              loading={run.isPending && !!checked}
            >
              Import {checked ? checked.created + checked.updated : ''} {checked && checked.created + checked.updated === 1 ? 'entry' : 'entries'}
            </Button>
          </>
        )
      }
    >
      <div className="stack">
        <p className="muted small">
          CSV: one row per entry, a header of the form's attribute names (as an export writes them), an optional <code>dn</code> column; several values
          in one cell are separated by <code>|</code>. LDIF: entries (or <code>changetype: add</code>). An entry that exists is updated with the values
          given (empty cells leave an attribute as it is); otherwise it is created. Check the file first: nothing is written until you import.
        </p>
        <div className="form-grid">
          <FormRow label="File" htmlFor={`${id}-file`}>
            <input
              id={`${id}-file`}
              className="input"
              type="file"
              accept=".csv,.ldif,.txt,text/csv,text/plain"
              onChange={async (e) => {
                const f = e.target.files?.[0];
                reset();
                if (!f) return setText(null);
                setFileName(f.name);
                if (/\.ldif$/i.test(f.name)) setFormat('ldif');
                else if (/\.csv$/i.test(f.name)) setFormat('csv');
                setText(await readText(f));
              }}
            />
          </FormRow>
          <FormRow label="Format" htmlFor={`${id}-format`}>
            <select
              id={`${id}-format`}
              className="input"
              value={format}
              onChange={(e) => {
                setFormat(e.target.value as Format);
                reset();
              }}
            >
              <option value="csv">CSV</option>
              <option value="ldif">LDIF</option>
            </select>
          </FormRow>
          <FormRow label="Entries" htmlFor={`${id}-mode`}>
            <select
              id={`${id}-mode`}
              className="input"
              value={mode}
              onChange={(e) => {
                setMode(e.target.value as Mode);
                reset();
              }}
            >
              <option value="upsert">Create new ones and update existing ones</option>
              <option value="create">Only create new ones</option>
              <option value="update">Only update existing ones</option>
            </select>
          </FormRow>
        </div>
        {fileName && <div className="muted small">{fileName}</div>}
        <ErrorAlert error={run.error} />
        {done ? (
          <>
            <Alert tone={done.failed ? 'warning' : 'success'}>
              {done.created} created, {done.updated} updated{done.failed ? `, ${done.failed} not imported (see below)` : ''}.
            </Alert>
            <Rows result={done} />
          </>
        ) : (
          checked && (
            <>
              <Summary result={checked} />
              <Rows result={checked} />
            </>
          )
        )}
      </div>
    </Modal>
  );
}
