import { describe, expect, it } from 'vitest';
import { buildDn, dnEquals, escapeDnValue, isDnUnder, normalizeDn, parseDn, parentDn, validateFormValues, escapeFilterValue, lintForm, SchemaIndex } from '@dsp/shared';
import type { FormDefinition } from '@dsp/shared';
import { evaluateFilter, parseFilter } from '../src/ldap/filter';
import { formatAttributeType, formatObjectClass, parseAttributeType, parseObjectClass, parseSchemaText } from '../src/ldap/schemaParser';
import { SEED_SCHEMA } from '../src/ldap/seed';

describe('DN utilities', () => {
  it('parses escaped and multi-valued RDNs', () => {
    const rdns = parseDn('cn=Doe\\, John+uid=jd, ou=People ,dc=example,dc=com');
    expect(rdns).toHaveLength(4);
    expect(rdns[0]).toEqual([
      { type: 'cn', value: 'Doe, John' },
      { type: 'uid', value: 'jd' },
    ]);
    expect(rdns[1][0].value).toBe('People');
  });

  it('handles hex escapes', () => {
    expect(parseDn('cn=caf\\C3\\A9,dc=x')[0][0].value).toBe('café');
  });

  it('normalizes and compares case-insensitively', () => {
    expect(dnEquals('UID=Alice, OU=People,DC=Example,DC=com', 'uid=alice,ou=people,dc=example,dc=com')).toBe(true);
    expect(normalizeDn('CN=A+UID=b,dc=x')).toBe(normalizeDn('uid=B+cn=a,DC=X'));
  });

  it('checks namespace containment', () => {
    expect(isDnUnder('uid=a,ou=people,dc=example,dc=com', 'ou=people,dc=example,dc=com')).toBe(true);
    expect(isDnUnder('ou=people,dc=example,dc=com', 'ou=people,dc=example,dc=com', true)).toBe(false);
    expect(isDnUnder('uid=a,ou=people2,dc=example,dc=com', 'ou=people,dc=example,dc=com')).toBe(false);
    // A value that merely ends with the base string must not match.
    expect(isDnUnder('cn=x\\,ou=people,dc=evil,dc=example,dc=com', 'ou=people,dc=example,dc=com')).toBe(false);
  });

  it('escapes values when building DNs', () => {
    expect(escapeDnValue(' #a,b ')).toBe('\\ #a\\,b\\ ');
    const dn = buildDn('cn', 'Smith, Jr. <x>', 'ou=people,dc=example,dc=com');
    expect(parseDn(dn)[0][0].value).toBe('Smith, Jr. <x>');
    expect(parentDn(dn)).toBe('ou=people,dc=example,dc=com');
  });

  it('rejects malformed DNs', () => {
    expect(() => parseDn('not a dn')).toThrow();
    expect(() => parseDn('cn=a,')).toThrow();
  });
});

describe('filters', () => {
  const attrs: Record<string, string[]> = { cn: ['Alice Anderson'], mail: ['alice@example.com'], age: ['42'], objectclass: ['top', 'person'] };
  const lookup = (a: string) => attrs[a.toLowerCase()] ?? [];

  it('parses and evaluates complex filters', () => {
    expect(evaluateFilter(parseFilter('(&(objectClass=person)(|(cn=bob*)(cn=*ander*)))'), lookup)).toBe(true);
    expect(evaluateFilter(parseFilter('(!(mail=*))'), lookup)).toBe(false);
    expect(evaluateFilter(parseFilter('(age>=40)'), lookup)).toBe(true);
    expect(evaluateFilter(parseFilter('(age<=40)'), lookup)).toBe(false);
    expect(evaluateFilter(parseFilter('cn=alice anderson'), lookup)).toBe(true);
    expect(evaluateFilter(parseFilter('(cn=A*ce*son)'), lookup)).toBe(true);
  });

  it('escapes user input so it cannot alter filter structure', () => {
    const evil = '*)(objectClass=*';
    const f = parseFilter(`(cn=${escapeFilterValue(evil)})`);
    expect(f).toEqual({ type: 'equality', attribute: 'cn', value: evil });
  });

  it('rejects invalid filters', () => {
    expect(() => parseFilter('(&(cn=a)')).toThrow();
    expect(() => parseFilter('(cn=a))')).toThrow();
    expect(() => parseFilter('(=a)')).toThrow();
  });
});

describe('schema parser', () => {
  it('parses attribute types', () => {
    const at = parseAttributeType(
      "( 1.3.6.1.4.1.99999.1.1 NAME ( 'acmeBadge' 'badge' ) DESC 'It\\27s a badge' EQUALITY caseIgnoreMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.15{32} SINGLE-VALUE X-ORIGIN 'test' )",
    );
    expect(at.names).toEqual(['acmeBadge', 'badge']);
    expect(at.desc).toBe("It's a badge");
    expect(at.singleValue).toBe(true);
    expect(at.syntax).toBe('1.3.6.1.4.1.1466.115.121.1.15{32}');
    expect(parseAttributeType(formatAttributeType(at))).toMatchObject({ names: at.names, singleValue: true, desc: at.desc });
  });

  it('parses object classes', () => {
    const oc = parseObjectClass("( 2.5.6.6 NAME 'person' SUP top STRUCTURAL MUST ( sn $ cn ) MAY ( userPassword $ telephoneNumber ) )");
    expect(oc).toMatchObject({ kind: 'STRUCTURAL', sup: ['top'], must: ['sn', 'cn'], may: ['userPassword', 'telephoneNumber'] });
    expect(parseObjectClass(formatObjectClass(oc))).toMatchObject({ must: oc.must, may: oc.may, kind: oc.kind });
  });

  it('parses LDIF and .schema text', () => {
    const ldif = `dn: cn=acme,cn=schema,cn=config
olcAttributeTypes: {0}( 1.2.3.4.1 NAME 'fooAttr' DESC 'foo'
  SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 )
olcObjectClasses: {0}( 1.2.3.4.2 NAME 'fooClass' SUP top AUXILIARY MAY fooAttr )
`;
    const parsed = parseSchemaText(ldif);
    expect(parsed.errors).toEqual([]);
    expect(parsed.attributeTypes[0].names).toEqual(['fooAttr']);
    expect(parsed.objectClasses[0]).toMatchObject({ kind: 'AUXILIARY', may: ['fooAttr'] });

    const seed = parseSchemaText(SEED_SCHEMA);
    expect(seed.errors).toEqual([]);
    expect(seed.objectClasses.length).toBeGreaterThan(10);
  });
});

describe('form validation', () => {
  const form: FormDefinition = {
    id: 'f',
    name: 'T',
    objectClasses: ['inetOrgPerson'],
    rdnAttribute: 'uid',
    createdAt: '',
    updatedAt: '',
    fields: [
      { id: 'uid', attribute: 'uid', label: 'User', widget: 'text', multiValued: false, required: true, readOnly: false, selfEditable: false },
      { id: 'mail', attribute: 'mail', label: 'Mail', widget: 'text', format: 'email', multiValued: true, required: false, readOnly: false, selfEditable: true },
      { id: 'dept', attribute: 'departmentNumber', label: 'Dept', widget: 'dropdown', dropdown: { type: 'static', options: [{ value: 'A' }, { value: 'B' }] }, multiValued: false, required: false, readOnly: false, selfEditable: false },
      { id: 'badge', attribute: 'acmeBadgeNumber', label: 'Badge', widget: 'text', multiValued: false, required: false, readOnly: true, selfEditable: false },
    ],
  };

  it('validates required, single-value, formats and options', () => {
    const r = validateFormValues(form, { uid: ['a', 'b'], mail: ['nope'], departmentNumber: 'C' }, { mode: 'create' });
    expect(Object.keys(r.errors).sort()).toEqual(['departmentnumber', 'mail', 'uid']);
  });

  it('drops read-only and non-self-editable fields', () => {
    const r = validateFormValues(form, { uid: 'x', mail: ['a@b.co'], acmeBadgeNumber: '1', evil: 'x' }, { mode: 'self' });
    expect(r.errors).toEqual({});
    expect(r.values).toEqual({ mail: ['a@b.co'] });
  });

  it('lints forms against the schema', () => {
    const index = new SchemaIndex({ ...parseSchemaText(SEED_SCHEMA) });
    const lint = lintForm({ ...form, fields: [...form.fields, { ...form.fields[0], id: 'dn2', attribute: 'displayName', multiValued: true }] }, index);
    expect(lint.errors.some((e) => e.includes('SINGLE-VALUE'))).toBe(true);
    expect(lint.warnings.some((w) => w.includes('sn'))).toBe(true);
  });
});
