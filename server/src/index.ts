import path from 'node:path';
import { loadConfig } from './config';
import type { Directory } from './ldap/directory';
import { LdapDirectory } from './ldap/ldapDirectory';
import { MemoryDirectory } from './ldap/memoryDirectory';
import { SEED_BASE_DN, SEED_ENTRIES, SEED_SCHEMA } from './ldap/seed';
import { ConfigStore } from './store/configStore';
import { demoConfig } from './store/demoConfig';
import { createApp, createContext } from './app';

async function main() {
  const config = loadConfig();

  let directory: Directory;
  if (config.directoryMode === 'memory') {
    if (config.baseDn.toLowerCase() !== SEED_BASE_DN) {
      console.warn(`[portal] memory mode always uses ${SEED_BASE_DN}; ignoring LDAP_BASE_DN=${config.baseDn}`);
      config.baseDn = SEED_BASE_DN;
      config.userSearchBase = SEED_BASE_DN;
      config.groupSearchBase = SEED_BASE_DN;
    }
    directory = new MemoryDirectory({ schemaText: SEED_SCHEMA, entries: SEED_ENTRIES });
    console.log('[portal] using the in-memory demo directory (log in as admin / password)');
  } else {
    directory = new LdapDirectory(config.ldap);
    console.log(`[portal] using LDAP server ${config.ldap.url}`);
  }

  const store = new ConfigStore(path.join(config.dataDir, 'portal-config.json'));
  await store.load();
  if (store.isEmpty && config.seedDemoConfig) {
    const demo = demoConfig(config.baseDn);
    await store.update((d) => {
      d.forms = demo.forms;
      d.definitions = demo.definitions;
      d.grants = demo.grants;
    });
    console.log('[portal] seeded demo forms, directories and permissions');
  }

  const ctx = createContext(config, directory, store);
  const app = createApp(ctx);
  const server = app.listen(config.port, config.host, () => {
    console.log(`[portal] listening on http://${config.host}:${config.port}`);
  });

  const shutdown = () => {
    ctx.auth.close();
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 5000).unref();
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

main().catch((err) => {
  console.error('[portal] failed to start:', err);
  process.exit(1);
});
