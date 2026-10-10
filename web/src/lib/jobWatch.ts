/**
 * Jobs whose result the current page is waiting for and will show itself, so the global
 * notifier doesn't also announce them.
 */
const watched = new Set<string>();

export function watchJob(id: string): () => void {
  watched.add(id);
  return () => watched.delete(id);
}

export function isWatched(id: string): boolean {
  return watched.has(id);
}
