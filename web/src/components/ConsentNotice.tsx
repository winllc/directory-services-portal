import { useState } from 'react';
import { ShieldCheck } from 'lucide-react';
import { Alert, Button } from './ui';

type Block = { kind: 'p'; text: string } | { kind: 'ul'; items: string[] };

/** Plain text to blocks: blank lines separate paragraphs; paragraphs starting with "- " are list items. */
export function consentBlocks(text: string): Block[] {
  const out: Block[] = [];
  for (const raw of text.split(/\n\s*\n/)) {
    const para = raw.trim();
    if (!para) continue;
    if (para.startsWith('- ')) {
      const items = para.split(/\n(?=\s*- )/).map((item) => item.trim().slice(2).replace(/\s*\n\s*/g, ' '));
      const last = out[out.length - 1];
      if (last?.kind === 'ul') last.items.push(...items);
      else out.push({ kind: 'ul', items });
    } else {
      out.push({ kind: 'p', text: para.replace(/\s*\n\s*/g, ' ') });
    }
  }
  return out;
}

/**
 * The DoD notice-and-consent banner (ENS_CONSENT_BANNER_*), shown before the sign-in form. The
 * server refuses every sign-in until it has been acknowledged in this browser session.
 */
export function ConsentNotice({ title, text, onAgree }: { title: string; text: string; onAgree: () => Promise<void> }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const agree = async () => {
    setError(null);
    setBusy(true);
    try {
      await onAgree();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="login-card consent-card" aria-labelledby="consent-title">
      <div className="login-brand">
        <div className="brand-logo brand-logo-lg">
          <ShieldCheck size={24} />
        </div>
        <h1 id="consent-title">{title}</h1>
      </div>
      <div className="consent-text">
        {consentBlocks(text).map((b, i) =>
          b.kind === 'p' ? (
            <p key={i}>{b.text}</p>
          ) : (
            <ul key={i}>
              {b.items.map((item, j) => (
                <li key={j}>{item}</li>
              ))}
            </ul>
          ),
        )}
      </div>
      {error && <Alert tone="danger">{error}</Alert>}
      <Button variant="primary" className="btn-block" loading={busy} disabled={busy} onClick={agree}>
        I agree
      </Button>
    </section>
  );
}
