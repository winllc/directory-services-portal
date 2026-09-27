import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import request from 'supertest';
import { q, setup } from './helpers';

const B = 'dc=example,dc=com';
const alice = `uid=alice,ou=people,${B}`;
let env: Awaited<ReturnType<typeof setup>>;

beforeEach(async () => {
  env = await setup();
});
afterEach(() => env.close());

describe('authentication', () => {
  it('logs in with directory credentials and resolves groups/admin', async () => {
    const admin = await env.login('admin');
    expect(admin.user.isAdmin).toBe(true);
    expect(admin.user.groups.some((g: string) => g.startsWith('cn=directory-admins'))).toBe(true);
    const a = await env.login('alice');
    expect(a.user).toMatchObject({ username: 'alice', dn: alice, isAdmin: false, displayName: 'Alice Anderson' });
    const me = await a.get('/api/auth/me');
    expect(me.body.user.username).toBe('alice');
  });

  it('rejects bad credentials and filter injection', async () => {
    const agent = request(env.app);
    const bad = await agent.post('/api/auth/login').set('X-DSP-Request', '1').send({ username: 'alice', password: 'nope' });
    expect(bad.status).toBe(401);
    const inj = await agent.post('/api/auth/login').set('X-DSP-Request', '1').send({ username: '*)(uid=*', password: 'password' });
    expect(inj.status).toBe(401);
    const empty = await agent.post('/api/auth/login').set('X-DSP-Request', '1').send({ username: 'alice', password: '' });
    expect(empty.status).toBe(400);
  });

  it('requires the CSRF header on mutations', async () => {
    const res = await request(env.app).post('/api/auth/login').send({ username: 'alice', password: 'password' });
    expect(res.status).toBe(403);
  });

  it('requires a session for API calls', async () => {
    expect((await request(env.app).get('/api/definitions')).status).toBe(401);
  });

  it('throttles repeated failures', async () => {
    const agent = request(env.app);
    for (let i = 0; i < 8; i++) {
      await agent.post('/api/auth/login').set('X-DSP-Request', '1').send({ username: 'bob', password: 'x' });
    }
    const res = await agent.post('/api/auth/login').set('X-DSP-Request', '1').send({ username: 'bob', password: 'password' });
    expect(res.status).toBe(429);
  });
});

describe('permissions', () => {
  it('lists only definitions a user can access, with effective access', async () => {
    const alice = await env.login('alice');
    const defs = (await alice.get('/api/definitions')).body as { slug: string; access: string }[];
    const access = Object.fromEntries(defs.map((d) => [d.slug, d.access]));
    // engineering group -> staff read; user grant -> devices read; everyoneCanRead -> white pages & groups
    expect(access).toEqual({ staff: 'read', devices: 'read', 'white-pages': 'read', groups: 'read' });

    const bob = await env.login('bob');
    const bobDefs = (await bob.get('/api/definitions')).body as { slug: string }[];
    expect(bobDefs.map((d) => d.slug).sort()).toEqual(['groups', 'partners', 'white-pages']);
  });

  it('enforces read access on entry endpoints', async () => {
    const bob = await env.login('bob');
    expect((await bob.get('/api/directories/def-staff/entries')).status).toBe(403);
    expect((await bob.get(`/api/directories/def-staff/entry${q(alice)}`)).status).toBe(403);
  });

  it('enforces write access', async () => {
    const alice = await env.login('alice');
    const res = await alice.put(`/api/directories/def-staff/entry${q(alice.user.dn)}`, { values: { title: 'CEO' } });
    expect(res.status).toBe(403);
    const erin = await env.login('erin'); // hr group has write
    const ok = await erin.put(`/api/directories/def-staff/entry${q(alice.user.dn)}`, { values: { title: 'Staff Engineer' } });
    expect(ok.status).toBe(200);
    expect(ok.body.attributes.title).toEqual(['Staff Engineer']);
  });

  it('never allows writes through read-only (white pages) definitions, even for admins', async () => {
    const admin = await env.login('admin');
    const res = await admin.put(`/api/directories/def-whitepages/entry${q(alice)}`, { values: { title: 'x' } });
    expect(res.status).toBe(403);
    const del = await admin.del(`/api/directories/def-whitepages/entry${q(alice)}`);
    expect(del.status).toBe(403);
    const grant = await admin.post('/api/admin/grants', { definitionId: 'def-whitepages', subjectType: 'user', subject: 'bob', access: 'write' });
    expect(grant.status).toBe(400);
  });

  it('admin grants take effect immediately and can be revoked', async () => {
    const admin = await env.login('admin');
    const g = await admin.post('/api/admin/grants', { definitionId: 'def-devices', subjectType: 'user', subject: 'bob', access: 'write' });
    expect(g.status).toBe(201);
    const bob = await env.login('bob');
    const create = await bob.post('/api/directories/def-devices/entries', {
      values: { cn: 'LT-0099', acmeDeviceSerial: 'SN-X', acmeDeviceType: 'Laptop' },
    });
    expect(create.status).toBe(201);
    await admin.del(`/api/admin/grants/${g.body.id}`);
    expect((await bob.get('/api/directories/def-devices/entries')).status).toBe(403);
  });

  it('blocks non-admins from admin APIs', async () => {
    const alice = await env.login('alice');
    expect((await alice.get('/api/admin/forms')).status).toBe(403);
    expect((await alice.get('/api/admin/schema')).status).toBe(403);
  });
});

describe('entries CRUD', () => {
  it('lists, searches, sorts and pages', async () => {
    const admin = await env.login('admin');
    const all = await admin.get('/api/directories/staff/entries?pageSize=3');
    expect(all.status).toBe(200);
    expect(all.body.total).toBe(8);
    expect(all.body.entries).toHaveLength(3);
    expect(all.body.entries[0].attributes.cn).toEqual(['Ada Admin']);
    expect(all.body.entries[0].attributes.userpassword).toBeUndefined();

    const search = await admin.get('/api/directories/staff/entries?q=engineer');
    expect(search.body.entries.map((e: { attributes: { cn: string[] } }) => e.attributes.cn[0]).sort()).toEqual(['Alice Anderson', 'Carol Chen']);

    const injection = await admin.get(`/api/directories/staff/entries?q=${encodeURIComponent('*)(uid=*')}`);
    expect(injection.body.total).toBe(0);
  });

  it('creates, reads, updates, renames and deletes an entry', async () => {
    const admin = await env.login('admin');
    const create = await admin.post('/api/directories/staff/entries', {
      values: {
        uid: 'zoe',
        givenName: 'Zoe',
        sn: 'Zimmer',
        cn: 'Zoe Zimmer',
        mail: ['zoe@example.com', 'z@example.com'],
        departmentNumber: 'Engineering',
        manager: `uid=carol,ou=people,${B}`,
        acmeSkill: ['Go', 'Rust'],
        acmeBadgeNumber: 'ignored-read-only',
      },
    });
    expect(create.status).toBe(201);
    const dn = create.body.dn as string;
    expect(dn).toBe(`uid=zoe,ou=people,${B}`);
    expect(create.body.attributes.objectclass).toContain('acmePerson');
    expect(create.body.attributes.acmebadgenumber).toBeUndefined();
    expect(create.body.attributes.acmeskill).toEqual(['Go', 'Rust']);

    const upd = await admin.put(`/api/directories/staff/entry${q(dn)}`, { values: { mail: ['zoe@example.com'], acmeSkill: [], title: 'SRE' } });
    expect(upd.status).toBe(200);
    expect(upd.body.attributes.mail).toEqual(['zoe@example.com']);
    expect(upd.body.attributes.acmeskill).toBeUndefined();
    expect(upd.body.attributes.title).toEqual(['SRE']);

    const ren = await admin.put(`/api/directories/staff/entry${q(dn)}`, { values: { uid: 'zoe.z' } });
    expect(ren.status).toBe(200);
    expect(ren.body.dn).toBe(`uid=zoe.z,ou=people,${B}`);
    expect(ren.body.attributes.uid).toEqual(['zoe.z']);

    const del = await admin.del(`/api/directories/staff/entry${q(ren.body.dn)}`);
    expect(del.status).toBe(204);
    expect((await admin.get(`/api/directories/staff/entry${q(ren.body.dn)}`)).status).toBe(404);
  });

  it('validates values against the form', async () => {
    const admin = await env.login('admin');
    const res = await admin.post('/api/directories/staff/entries', {
      values: { uid: 'xy', givenName: 'X', sn: 'Y', cn: 'X Y', mail: 'not-an-email', departmentNumber: 'Nope', manager: 'uid=ghost,ou=people,dc=example,dc=com' },
    });
    expect(res.status).toBe(400);
    expect(Object.keys(res.body.details).sort()).toEqual(['departmentnumber', 'mail', 'manager']);
  });

  it('surfaces directory schema violations', async () => {
    const admin = await env.login('admin');
    const res = await admin.post('/api/directories/devices/entries', { values: { cn: 'NO-SERIAL', acmeDeviceType: 'Laptop' } });
    expect(res.status).toBe(400); // required by form
    const dup = await admin.post('/api/directories/devices/entries', { values: { cn: 'LT-0001', acmeDeviceSerial: 'x', acmeDeviceType: 'Laptop' } });
    expect(dup.status).toBe(409);
  });

  it('confines operations to the definition namespace', async () => {
    const admin = await env.login('admin');
    // A person DN through the devices definition
    expect((await admin.get(`/api/directories/devices/entry${q(alice)}`)).status).toBe(404);
    expect((await admin.del(`/api/directories/devices/entry${q(alice)}`)).status).toBe(404);
    // The base itself is not an entry of the directory
    expect((await admin.get(`/api/directories/devices/entry${q(`ou=devices,${B}`)}`)).status).toBe(404);
    // Creating into a container outside the allowed list
    const res = await admin.post('/api/directories/devices/entries', {
      parentDn: `ou=people,${B}`,
      values: { cn: 'X', acmeDeviceSerial: 'x', acmeDeviceType: 'Laptop' },
    });
    expect(res.status).toBe(400);
    // Entry in namespace but not matching the definition filter
    expect((await admin.get(`/api/directories/white-pages/entry${q(`cn=LT-0001,ou=devices,${B}`)}`)).status).toBe(404);
  });

  it('resolves LDAP-backed drop-down options', async () => {
    const admin = await env.login('admin');
    const opts = await admin.get('/api/directories/devices/options/f-acmeassignedto');
    expect(opts.status).toBe(200);
    expect(opts.body.find((o: { label: string }) => o.label === 'Alice Anderson').value).toBe(alice);
  });
});

describe('self service', () => {
  it('shows the user in every definition that matches them', async () => {
    const a = await env.login('alice');
    const res = await a.get('/api/me/entries');
    expect(res.status).toBe(200);
    const bySlug = Object.fromEntries(
      (res.body as { definition: { slug: string }; entries: { dn: string }[] }[]).map((r) => [r.definition.slug, r.entries.map((e) => e.dn)]),
    );
    expect(bySlug.staff).toEqual([alice]);
    expect(bySlug['white-pages']).toEqual([alice]);
    expect(bySlug.devices.sort()).toEqual([`cn=LT-0001,ou=devices,${B}`, `cn=PH-0001,ou=devices,${B}`]);
    expect(bySlug.groups).toEqual([`cn=engineering,ou=groups,${B}`]);
    expect(bySlug.partners).toEqual([]);
  });

  it('lets users edit self-editable fields on their own entry only', async () => {
    const a = await env.login('alice');
    const ok = await a.put(`/api/me/def-staff/entry${q(alice)}`, { values: { mobile: ['+1 555 9999'], title: 'CTO' } });
    expect(ok.status).toBe(200);
    expect(ok.body.attributes.mobile).toEqual(['+1 555 9999']);
    expect(ok.body.attributes.title).toEqual(['Senior Software Engineer']); // not self-editable, ignored

    const other = await a.put(`/api/me/def-staff/entry${q(`uid=bob,ou=people,${B}`)}`, { values: { mobile: ['1'] } });
    expect(other.status).toBe(403);

    const ro = await a.put(`/api/me/def-whitepages/entry${q(alice)}`, { values: { mobile: ['1'] } });
    expect(ro.status).toBe(403);
  });
});

describe('admin configuration', () => {
  it('creates forms and definitions and validates them', async () => {
    const admin = await env.login('admin');
    const bad = await admin.post('/api/admin/forms', {
      name: 'Bad',
      objectClasses: ['inetOrgPerson'],
      rdnAttribute: 'uid',
      fields: [{ id: '1', attribute: 'displayName', label: 'D', widget: 'text', multiValued: true, required: false, readOnly: false, selfEditable: false }],
    });
    expect(bad.status).toBe(400);

    const form = await admin.post('/api/admin/forms', {
      name: 'Contractor',
      objectClasses: ['top', 'inetOrgPerson', 'acmePartner'],
      rdnAttribute: 'uid',
      fields: [
        { id: '1', attribute: 'uid', label: 'User', widget: 'text', multiValued: false, required: true, readOnly: false, selfEditable: false },
        { id: '2', attribute: 'cn', label: 'Name', widget: 'text', multiValued: false, required: true, readOnly: false, selfEditable: false },
        { id: '3', attribute: 'sn', label: 'Surname', widget: 'text', multiValued: false, required: true, readOnly: false, selfEditable: false },
        { id: '4', attribute: 'acmePartnerCompany', label: 'Company', widget: 'dropdown', dropdown: { type: 'static', options: [{ value: 'A' }, { value: 'B' }] }, multiValued: false, required: false, readOnly: false, selfEditable: false },
      ],
    });
    expect(form.status).toBe(201);

    const badDef = await admin.post('/api/admin/definitions', {
      name: 'X', slug: 'x', formId: form.body.form.id, baseDn: `ou=partners,${B}`, scope: 'one', mode: 'readwrite',
      everyoneCanRead: false, listAttributes: ['cn'], searchAttributes: ['cn'], titleAttribute: 'cn',
      createContainers: [`ou=people,${B}`], selfMatch: { type: 'none' }, filter: '(bad',
    });
    expect(badDef.status).toBe(400);

    const def = await admin.post('/api/admin/definitions', {
      name: 'Contractors', slug: 'contractors', formId: form.body.form.id, baseDn: `ou=partners,${B}`, scope: 'one', mode: 'readwrite',
      everyoneCanRead: false, listAttributes: ['cn', 'acmePartnerCompany'], searchAttributes: ['cn'], titleAttribute: 'cn',
      createContainers: [`ou=partners,${B}`], selfMatch: { type: 'dn' },
    });
    expect(def.status).toBe(201);
    const list = await admin.get('/api/directories/contractors/entries');
    expect(list.body.total).toBe(2);

    // Forms in use cannot be deleted
    expect((await admin.del(`/api/admin/forms/${form.body.form.id}`)).status).toBe(409);
    expect((await admin.del(`/api/admin/definitions/${def.body.id}`)).status).toBe(204);
    expect((await admin.del(`/api/admin/forms/${form.body.form.id}`)).status).toBe(204);
  });

  it('pulls, imports, customizes and exports schema', async () => {
    const admin = await env.login('admin');
    const refresh = await admin.post('/api/admin/schema/refresh');
    expect(refresh.body.objectClasses).toBeGreaterThan(10);

    const imp = await admin.post('/api/admin/schema/import', {
      text: "attributetype ( 1.2.3.4.5 NAME 'widgetColor' SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )\nobjectclass ( 1.2.3.4.6 NAME 'widget' SUP top STRUCTURAL MUST cn MAY widgetColor )",
    });
    expect(imp.body).toMatchObject({ attributeTypes: 1, objectClasses: 1 });

    const override = await admin.put('/api/admin/schema/attribute-types', {
      definition: { oid: '1.3.6.1.4.1.99999.1.3', names: ['acmeSkill'], singleValue: false, noUserModification: false, displayName: 'Skills', desc: 'Overridden' },
    });
    expect(override.status).toBe(200);

    const schema = await admin.get('/api/admin/schema');
    const skill = schema.body.merged.attributeTypes.filter((a: { names: string[] }) => a.names.includes('acmeSkill'));
    expect(skill).toHaveLength(1);
    expect(skill[0]).toMatchObject({ source: 'custom', desc: 'Overridden' });
    expect(schema.body.merged.objectClasses.some((o: { names: string[] }) => o.names.includes('widget'))).toBe(true);

    const exported = await admin.get('/api/admin/schema/export');
    expect(exported.text).toContain("NAME 'widgetColor'");

    expect((await admin.del('/api/admin/schema/object-classes/1.2.3.4.6')).status).toBe(204);
  });

  it('searches users and groups for permission subjects', async () => {
    const admin = await env.login('admin');
    const res = await admin.get('/api/admin/subjects?q=car');
    expect(res.body.some((s: { type: string; id: string }) => s.type === 'user' && s.id === 'carol')).toBe(true);
    const groups = await admin.get('/api/admin/subjects?type=group&q=eng');
    expect(groups.body).toEqual([expect.objectContaining({ type: 'group', label: 'engineering' })]);
  });
});
