import request from 'supertest';
import { loadConfig } from '../src/config';
import { MemoryDirectory } from '../src/ldap/memoryDirectory';
import { SEED_ENTRIES, SEED_SCHEMA } from '../src/ldap/seed';
import { ConfigStore } from '../src/store/configStore';
import { demoConfig } from '../src/store/demoConfig';
import { createApp, createContext } from '../src/app';

export async function setup() {
  const config = loadConfig({ DIRECTORY_MODE: 'memory', STATIC_DIR: '/nonexistent' } as NodeJS.ProcessEnv);
  const directory = new MemoryDirectory({ schemaText: SEED_SCHEMA, entries: SEED_ENTRIES });
  const store = new ConfigStore(null);
  const demo = demoConfig(config.baseDn);
  await store.update((d) => Object.assign(d, demo));
  const ctx = createContext(config, directory, store);
  const app = createApp(ctx);

  const login = async (username: string, password = 'password') => {
    const agent = request.agent(app);
    const res = await agent.post('/api/auth/login').set('X-DSP-Request', '1').send({ username, password });
    if (res.status !== 200) throw new Error(`login failed for ${username}: ${res.status} ${JSON.stringify(res.body)}`);
    return {
      agent,
      user: res.body.user,
      get: (url: string) => agent.get(url),
      post: (url: string, body?: object) => agent.post(url).set('X-DSP-Request', '1').send(body),
      put: (url: string, body?: object) => agent.put(url).set('X-DSP-Request', '1').send(body),
      del: (url: string) => agent.delete(url).set('X-DSP-Request', '1'),
    };
  };
  return { app, ctx, directory, store, login, close: () => ctx.auth.close() };
}

export const q = (dn: string) => `?dn=${encodeURIComponent(dn)}`;
