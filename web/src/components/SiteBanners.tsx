import { useEffect } from 'react';
import { useServerInfo } from '../api/hooks';

/** Height of each banner; the layout reads it from the --banner-h CSS variable. */
export const BANNER_HEIGHT = '26px';

/**
 * Optional fixed banners at the top and bottom of every page (e.g. a classification or
 * environment marking), configured on the API with BANNER_TEXT / BANNER_FOREGROUND /
 * BANNER_BACKGROUND. The API only publishes validated colors.
 */
export function SiteBanners() {
  const banner = useServerInfo().data?.banner;

  useEffect(() => {
    const root = document.documentElement.style;
    if (banner) root.setProperty('--banner-h', BANNER_HEIGHT);
    else root.removeProperty('--banner-h');
    return () => {
      root.removeProperty('--banner-h');
    };
  }, [banner]);

  if (!banner) return null;
  const style = { color: banner.foreground, backgroundColor: banner.background };
  return (
    <>
      <div className="site-banner site-banner-top" style={style} role="note" aria-label="Site banner" title={banner.text}>
        {banner.text}
      </div>
      <div className="site-banner site-banner-bottom" style={style} aria-hidden="true" title={banner.text}>
        {banner.text}
      </div>
    </>
  );
}
