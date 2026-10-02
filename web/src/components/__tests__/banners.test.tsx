import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SiteBanners } from '../SiteBanners';

function mockInfo(info: object) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response(JSON.stringify({ mode: 'memory', baseDn: 'dc=x', connected: true, version: '1', ...info }), { status: 200 })),
  );
}

const renderBanners = () =>
  render(
    <QueryClientProvider client={new QueryClient()}>
      <SiteBanners />
    </QueryClientProvider>,
  );

afterEach(() => {
  vi.unstubAllGlobals();
  document.documentElement.style.removeProperty('--banner-h');
});

describe('SiteBanners', () => {
  it('renders top and bottom banners with the configured text and colors', async () => {
    mockInfo({ banner: { text: 'UNCLASSIFIED', foreground: '#ffffff', background: '#007a33' } });
    renderBanners();
    const top = await screen.findByRole('note', { name: 'Site banner' });
    expect(top).toHaveTextContent('UNCLASSIFIED');
    expect(top).toHaveStyle({ color: '#ffffff', backgroundColor: '#007a33' });
    const all = document.querySelectorAll('.site-banner');
    expect(all).toHaveLength(2);
    expect(all[1]).toHaveClass('site-banner-bottom');
    expect(document.documentElement.style.getPropertyValue('--banner-h')).toBe('26px');
  });

  it('renders nothing and reserves no space when disabled', async () => {
    mockInfo({});
    renderBanners();
    await waitFor(() => expect(fetch).toHaveBeenCalled());
    expect(document.querySelectorAll('.site-banner')).toHaveLength(0);
    expect(document.documentElement.style.getPropertyValue('--banner-h')).toBe('');
  });
});
