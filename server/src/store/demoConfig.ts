/**
 * Example forms, definitions and grants that match the in-memory demo directory.
 */
import type { DirectoryDefinition, FormDefinition, FormField, PermissionGrant } from '@dsp/shared';
import type { StoreData } from './configStore';

const now = () => new Date().toISOString();

const field = (attribute: string, label: string, extra: Partial<FormField> = {}): FormField => ({
  id: `f-${attribute.toLowerCase()}`,
  attribute,
  label,
  widget: 'text',
  multiValued: false,
  required: false,
  readOnly: false,
  selfEditable: false,
  ...extra,
});

export function demoConfig(baseDn: string): Pick<StoreData, 'forms' | 'definitions' | 'grants'> {
  const t = now();
  const people = `ou=people,${baseDn}`;
  const dept = ['Engineering', 'Sales', 'Marketing', 'Design', 'IT', 'Human Resources', 'Finance'];
  const offices = ['HQ', 'Denver', 'Austin', 'Remote'];

  const personForm: FormDefinition = {
    id: 'form-person',
    name: 'Employee',
    description: 'inetOrgPerson with the ACME employee extension',
    objectClasses: ['top', 'person', 'organizationalPerson', 'inetOrgPerson', 'acmePerson'],
    rdnAttribute: 'uid',
    createdAt: t,
    updatedAt: t,
    fields: [
      field('uid', 'Username', { required: true, section: 'Identity', pattern: '[a-z][a-z0-9._-]{1,31}', helpText: 'Lower case login name; also names the entry.' }),
      field('givenName', 'First name', { required: true, section: 'Identity', multiValued: false }),
      field('sn', 'Last name', { required: true, section: 'Identity' }),
      field('cn', 'Full name', { required: true, section: 'Identity' }),
      field('displayName', 'Display name', { section: 'Identity', selfEditable: true }),
      field('acmePronouns', 'Pronouns', {
        section: 'Identity',
        widget: 'dropdown',
        selfEditable: true,
        allowCustomValues: true,
        dropdown: { type: 'static', options: ['she/her', 'he/him', 'they/them'].map((value) => ({ value })) },
      }),
      field('mail', 'E-mail', { format: 'email', section: 'Contact', multiValued: true, required: true }),
      field('telephoneNumber', 'Work phone', { format: 'phone', section: 'Contact', multiValued: true, selfEditable: true }),
      field('mobile', 'Mobile', { format: 'phone', section: 'Contact', multiValued: true, selfEditable: true }),
      field('title', 'Job title', { section: 'Organization' }),
      field('departmentNumber', 'Department', {
        section: 'Organization',
        widget: 'dropdown',
        dropdown: { type: 'static', options: dept.map((value) => ({ value })) },
      }),
      field('manager', 'Manager', {
        section: 'Organization',
        widget: 'dropdown',
        dropdown: { type: 'ldap', baseDn: people, scope: 'one', filter: '(objectClass=inetOrgPerson)', valueAttribute: 'dn', labelAttribute: 'cn' },
      }),
      field('acmeOfficeLocation', 'Office', {
        section: 'Organization',
        widget: 'dropdown',
        dropdown: { type: 'static', options: offices.map((value) => ({ value })) },
        selfEditable: true,
      }),
      field('acmeCostCenter', 'Cost center', { section: 'Organization', pattern: 'CC-[0-9]{3}', placeholder: 'CC-000' }),
      field('acmeBadgeNumber', 'Badge number', { section: 'Organization', readOnly: true }),
      field('acmeSkill', 'Skills', {
        section: 'About',
        widget: 'dropdown',
        multiValued: true,
        allowCustomValues: true,
        selfEditable: true,
        dropdown: { type: 'static', options: ['TypeScript', 'Go', 'Kubernetes', 'LDAP', 'Security', 'Leadership', 'Figma', 'Research'].map((value) => ({ value })) },
      }),
      field('description', 'About me', { section: 'About', widget: 'textarea', selfEditable: true, maxLength: 1000 }),
    ],
  };

  const deviceForm: FormDefinition = {
    id: 'form-device',
    name: 'Managed device',
    description: 'Hardware inventory using the non-standard acmeDevice class',
    objectClasses: ['top', 'acmeDevice'],
    rdnAttribute: 'cn',
    createdAt: t,
    updatedAt: t,
    fields: [
      field('cn', 'Device name', { required: true, placeholder: 'LT-0000' }),
      field('acmeDeviceSerial', 'Serial number', { required: true }),
      field('acmeDeviceType', 'Type', { widget: 'dropdown', required: true, dropdown: { type: 'static', options: ['Laptop', 'Desktop', 'Phone', 'Tablet', 'Monitor'].map((value) => ({ value })) } }),
      field('acmeDeviceStatus', 'Status', { widget: 'dropdown', dropdown: { type: 'static', options: ['In stock', 'In use', 'In repair', 'Retired'].map((value) => ({ value })) } }),
      field('acmeAssignedTo', 'Assigned to', {
        widget: 'dropdown',
        dropdown: { type: 'ldap', baseDn: people, scope: 'one', filter: '(objectClass=inetOrgPerson)', valueAttribute: 'dn', labelAttribute: 'cn' },
      }),
      field('acmeOperatingSystem', 'Operating system'),
      field('acmeAssetTag', 'Asset tag'),
      field('description', 'Notes', { widget: 'textarea' }),
    ],
  };

  const groupForm: FormDefinition = {
    id: 'form-group',
    name: 'Group',
    description: 'groupOfNames',
    objectClasses: ['top', 'groupOfNames'],
    rdnAttribute: 'cn',
    createdAt: t,
    updatedAt: t,
    fields: [
      field('cn', 'Group name', { required: true }),
      field('description', 'Description', { widget: 'textarea' }),
      field('member', 'Members', {
        widget: 'dropdown',
        multiValued: true,
        required: true,
        dropdown: { type: 'ldap', baseDn, scope: 'sub', filter: '(objectClass=inetOrgPerson)', valueAttribute: 'dn', labelAttribute: 'cn' },
      }),
      field('owner', 'Owners', {
        widget: 'dropdown',
        multiValued: true,
        dropdown: { type: 'ldap', baseDn: people, scope: 'one', filter: '(objectClass=inetOrgPerson)', valueAttribute: 'dn', labelAttribute: 'cn' },
      }),
    ],
  };

  const partnerForm: FormDefinition = {
    id: 'form-partner',
    name: 'Partner contact',
    description: 'External contractors (acmePartner auxiliary class)',
    objectClasses: ['top', 'person', 'organizationalPerson', 'inetOrgPerson', 'acmePartner'],
    rdnAttribute: 'uid',
    createdAt: t,
    updatedAt: t,
    fields: [
      field('uid', 'Username', { required: true }),
      field('cn', 'Full name', { required: true }),
      field('givenName', 'First name'),
      field('sn', 'Last name', { required: true }),
      field('mail', 'E-mail', { format: 'email', selfEditable: true }),
      field('telephoneNumber', 'Phone', { format: 'phone', multiValued: true, selfEditable: true }),
      field('title', 'Role'),
      field('acmePartnerCompany', 'Company', { required: true }),
      field('acmeContractEnd', 'Contract end', { pattern: '\\d{4}-\\d{2}-\\d{2}', placeholder: 'YYYY-MM-DD' }),
      field('acmeSponsor', 'Sponsor', {
        widget: 'dropdown',
        dropdown: { type: 'ldap', baseDn: people, scope: 'one', filter: '(objectClass=inetOrgPerson)', valueAttribute: 'dn', labelAttribute: 'cn' },
      }),
    ],
  };

  const def = (d: Omit<DirectoryDefinition, 'createdAt' | 'updatedAt'>): DirectoryDefinition => ({ ...d, createdAt: t, updatedAt: t });

  const definitions: DirectoryDefinition[] = [
    def({
      id: 'def-staff',
      name: 'Staff Directory',
      slug: 'staff',
      description: 'Manage employee records (HR and administrators).',
      formId: personForm.id,
      baseDn: people,
      scope: 'one',
      filter: '(objectClass=inetOrgPerson)',
      mode: 'readwrite',
      everyoneCanRead: false,
      listAttributes: ['cn', 'title', 'departmentNumber', 'mail', 'telephoneNumber'],
      searchAttributes: ['cn', 'uid', 'mail', 'title', 'departmentNumber'],
      titleAttribute: 'cn',
      createContainers: [people],
      selfMatch: { type: 'dn' },
      icon: 'users',
    }),
    def({
      id: 'def-whitepages',
      name: 'White Pages',
      slug: 'white-pages',
      description: 'Company-wide phone book. Read only for everyone.',
      formId: personForm.id,
      baseDn: baseDn,
      scope: 'sub',
      filter: '(objectClass=inetOrgPerson)',
      mode: 'readonly',
      everyoneCanRead: true,
      listAttributes: ['cn', 'title', 'departmentNumber', 'telephoneNumber', 'mail'],
      searchAttributes: ['cn', 'mail', 'title', 'departmentNumber', 'telephoneNumber'],
      titleAttribute: 'cn',
      createContainers: [],
      selfMatch: { type: 'dn' },
      icon: 'book',
    }),
    def({
      id: 'def-devices',
      name: 'Devices',
      slug: 'devices',
      description: 'IT asset inventory in ou=devices.',
      formId: deviceForm.id,
      baseDn: `ou=devices,${baseDn}`,
      scope: 'one',
      mode: 'readwrite',
      everyoneCanRead: false,
      listAttributes: ['cn', 'acmeDeviceType', 'acmeDeviceStatus', 'acmeOperatingSystem', 'acmeAssignedTo'],
      searchAttributes: ['cn', 'acmeDeviceSerial', 'acmeAssetTag', 'acmeOperatingSystem'],
      titleAttribute: 'cn',
      createContainers: [`ou=devices,${baseDn}`],
      selfMatch: { type: 'attribute', entryAttribute: 'acmeAssignedTo', userAttribute: 'dn' },
      icon: 'laptop',
    }),
    def({
      id: 'def-groups',
      name: 'Groups',
      slug: 'groups',
      description: 'Security and distribution groups.',
      formId: groupForm.id,
      baseDn: `ou=groups,${baseDn}`,
      scope: 'one',
      mode: 'readwrite',
      everyoneCanRead: true,
      listAttributes: ['cn', 'description'],
      searchAttributes: ['cn', 'description'],
      titleAttribute: 'cn',
      createContainers: [`ou=groups,${baseDn}`],
      selfMatch: { type: 'attribute', entryAttribute: 'member', userAttribute: 'dn' },
      icon: 'group',
    }),
    def({
      id: 'def-partners',
      name: 'Partners',
      slug: 'partners',
      description: 'External contractors, scoped to ou=partners.',
      formId: partnerForm.id,
      baseDn: `ou=partners,${baseDn}`,
      scope: 'sub',
      mode: 'readwrite',
      everyoneCanRead: false,
      listAttributes: ['cn', 'acmePartnerCompany', 'mail', 'acmeContractEnd'],
      searchAttributes: ['cn', 'mail', 'acmePartnerCompany'],
      titleAttribute: 'cn',
      createContainers: [`ou=partners,${baseDn}`],
      selfMatch: { type: 'dn' },
      icon: 'briefcase',
    }),
  ];

  const grants: PermissionGrant[] = [
    { id: 'g-hr-staff', definitionId: 'def-staff', subjectType: 'group', subject: `cn=hr,ou=groups,${baseDn}`, subjectLabel: 'hr', access: 'write', createdAt: t },
    { id: 'g-eng-staff', definitionId: 'def-staff', subjectType: 'group', subject: `cn=engineering,ou=groups,${baseDn}`, subjectLabel: 'engineering', access: 'read', createdAt: t },
    { id: 'g-helpdesk-devices', definitionId: 'def-devices', subjectType: 'group', subject: `cn=helpdesk,ou=groups,${baseDn}`, subjectLabel: 'helpdesk', access: 'write', createdAt: t },
    { id: 'g-alice-devices', definitionId: 'def-devices', subjectType: 'user', subject: 'alice', subjectLabel: 'Alice Anderson', access: 'read', createdAt: t },
    { id: 'g-carol-partners', definitionId: 'def-partners', subjectType: 'user', subject: 'carol', subjectLabel: 'Carol Chen', access: 'write', createdAt: t },
    { id: 'g-bob-partners', definitionId: 'def-partners', subjectType: 'user', subject: 'bob', subjectLabel: 'Bob Baker', access: 'read', createdAt: t },
  ];

  return { forms: [personForm, deviceForm, groupForm, partnerForm], definitions, grants };
}
