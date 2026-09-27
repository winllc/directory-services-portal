/**
 * Demo data for the in-memory directory: a subset of the standard schema
 * (RFC 4519, RFC 2798) plus a non-standard "acme" schema, and a small organisation.
 */
import type { MemorySeedEntry } from './memoryDirectory';

const DS = '1.3.6.1.4.1.1466.115.121.1.15'; // Directory String
const IA5 = '1.3.6.1.4.1.1466.115.121.1.26';
const DN = '1.3.6.1.4.1.1466.115.121.1.12';
const TEL = '1.3.6.1.4.1.1466.115.121.1.50';
const OCTET = '1.3.6.1.4.1.1466.115.121.1.40';
const OID = '1.3.6.1.4.1.1466.115.121.1.38';
const INT = '1.3.6.1.4.1.1466.115.121.1.27';
const JPEG = '1.3.6.1.4.1.1466.115.121.1.28';

const at = (oid: string, name: string | string[], opts: string) =>
  `attributetype ( ${oid} NAME ${Array.isArray(name) ? `( ${name.map((n) => `'${n}'`).join(' ')} )` : `'${name}'`} ${opts} )`;

export const SEED_SCHEMA = [
  // --- core (RFC 4512 / 4519) ---
  at('2.5.4.0', 'objectClass', `EQUALITY objectIdentifierMatch SYNTAX ${OID}`),
  at('2.5.4.41', 'name', `EQUALITY caseIgnoreMatch SUBSTR caseIgnoreSubstringsMatch SYNTAX ${DS}{32768}`),
  at('2.5.4.3', ['cn', 'commonName'], "DESC 'Common name' SUP name"),
  at('2.5.4.4', ['sn', 'surname'], "DESC 'Surname' SUP name"),
  at('2.5.4.42', ['givenName', 'gn'], "DESC 'Given name' SUP name"),
  at('2.5.4.43', 'initials', 'SUP name'),
  at('2.5.4.12', 'title', "DESC 'Job title' SUP name"),
  at('2.5.4.10', ['o', 'organizationName'], "DESC 'Organization name' SUP name"),
  at('2.5.4.11', ['ou', 'organizationalUnitName'], "DESC 'Organizational unit' SUP name"),
  at('2.5.4.7', ['l', 'localityName'], "DESC 'Locality' SUP name"),
  at('2.5.4.8', ['st', 'stateOrProvinceName'], "DESC 'State or province' SUP name"),
  at('2.5.4.9', ['street', 'streetAddress'], `EQUALITY caseIgnoreMatch SYNTAX ${DS}{128}`),
  at('2.5.4.17', 'postalCode', `EQUALITY caseIgnoreMatch SYNTAX ${DS}{40}`),
  at('2.5.4.16', 'postalAddress', `EQUALITY caseIgnoreListMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.41`),
  at('2.5.4.13', 'description', `EQUALITY caseIgnoreMatch SUBSTR caseIgnoreSubstringsMatch SYNTAX ${DS}{1024}`),
  at('2.5.4.20', 'telephoneNumber', `EQUALITY telephoneNumberMatch SUBSTR telephoneNumberSubstringsMatch SYNTAX ${TEL}{32}`),
  at('2.5.4.23', ['facsimileTelephoneNumber', 'fax'], `SYNTAX 1.3.6.1.4.1.1466.115.121.1.22`),
  at('2.5.4.35', 'userPassword', `EQUALITY octetStringMatch SYNTAX ${OCTET}{128}`),
  at('2.5.4.34', 'seeAlso', `SUP distinguishedName`),
  at('2.5.4.49', 'distinguishedName', `EQUALITY distinguishedNameMatch SYNTAX ${DN}`),
  at('2.5.4.31', 'member', `SUP distinguishedName`),
  at('2.5.4.50', 'uniqueMember', `EQUALITY uniqueMemberMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.34`),
  at('2.5.4.32', 'owner', `SUP distinguishedName`),
  at('2.5.4.15', 'businessCategory', `EQUALITY caseIgnoreMatch SYNTAX ${DS}{128}`),
  at('2.5.4.5', 'serialNumber', `EQUALITY caseIgnoreMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.44{64}`),
  at('0.9.2342.19200300.100.1.25', ['dc', 'domainComponent'], `EQUALITY caseIgnoreIA5Match SYNTAX ${IA5} SINGLE-VALUE`),
  at('0.9.2342.19200300.100.1.1', ['uid', 'userid'], `EQUALITY caseIgnoreMatch SUBSTR caseIgnoreSubstringsMatch SYNTAX ${DS}{256}`),
  at('0.9.2342.19200300.100.1.3', ['mail', 'rfc822Mailbox'], `EQUALITY caseIgnoreIA5Match SUBSTR caseIgnoreIA5SubstringsMatch SYNTAX ${IA5}{256}`),
  at('0.9.2342.19200300.100.1.41', ['mobile', 'mobileTelephoneNumber'], `EQUALITY telephoneNumberMatch SYNTAX ${TEL}`),
  at('0.9.2342.19200300.100.1.20', ['homePhone', 'homeTelephoneNumber'], `EQUALITY telephoneNumberMatch SYNTAX ${TEL}`),
  at('0.9.2342.19200300.100.1.6', 'roomNumber', `EQUALITY caseIgnoreMatch SYNTAX ${DS}{256}`),
  at('0.9.2342.19200300.100.1.10', 'manager', `EQUALITY distinguishedNameMatch SYNTAX ${DN}`),
  at('0.9.2342.19200300.100.1.21', 'secretary', `EQUALITY distinguishedNameMatch SYNTAX ${DN}`),
  at('0.9.2342.19200300.100.1.60', 'jpegPhoto', `SYNTAX ${JPEG}`),
  at('1.3.6.1.4.1.250.1.57', 'labeledURI', `EQUALITY caseExactMatch SYNTAX ${DS}`),
  // --- inetOrgPerson (RFC 2798) ---
  at('2.16.840.1.113730.3.1.1', 'carLicense', `EQUALITY caseIgnoreMatch SYNTAX ${DS}`),
  at('2.16.840.1.113730.3.1.2', 'departmentNumber', `EQUALITY caseIgnoreMatch SYNTAX ${DS}`),
  at('2.16.840.1.113730.3.1.241', 'displayName', `EQUALITY caseIgnoreMatch SUBSTR caseIgnoreSubstringsMatch SYNTAX ${DS} SINGLE-VALUE`),
  at('2.16.840.1.113730.3.1.3', 'employeeNumber', `EQUALITY caseIgnoreMatch SYNTAX ${DS} SINGLE-VALUE`),
  at('2.16.840.1.113730.3.1.4', 'employeeType', `EQUALITY caseIgnoreMatch SYNTAX ${DS}`),
  at('2.16.840.1.113730.3.1.39', 'preferredLanguage', `EQUALITY caseIgnoreMatch SYNTAX ${DS} SINGLE-VALUE`),
  // --- operational ---
  at('1.2.840.113556.1.2.102', 'memberOf', `EQUALITY distinguishedNameMatch SYNTAX ${DN} NO-USER-MODIFICATION USAGE dSAOperation`),
  at('1.3.6.1.1.20', 'entryDN', `EQUALITY distinguishedNameMatch SYNTAX ${DN} SINGLE-VALUE NO-USER-MODIFICATION USAGE directoryOperation`),

  // --- non-standard ACME schema (private enterprise arc) ---
  at('1.3.6.1.4.1.99999.1.1', 'acmeBadgeNumber', `DESC 'Physical access badge number' EQUALITY caseIgnoreMatch SYNTAX ${DS}{32} SINGLE-VALUE`),
  at('1.3.6.1.4.1.99999.1.2', 'acmeCostCenter', `DESC 'Finance cost center' EQUALITY caseIgnoreMatch SYNTAX ${DS}{16} SINGLE-VALUE`),
  at('1.3.6.1.4.1.99999.1.3', 'acmeSkill', `DESC 'Professional skills' EQUALITY caseIgnoreMatch SUBSTR caseIgnoreSubstringsMatch SYNTAX ${DS}{64}`),
  at('1.3.6.1.4.1.99999.1.4', 'acmeOfficeLocation', `DESC 'Office / campus' EQUALITY caseIgnoreMatch SYNTAX ${DS}{64} SINGLE-VALUE`),
  at('1.3.6.1.4.1.99999.1.5', 'acmePronouns', `DESC 'Preferred pronouns' EQUALITY caseIgnoreMatch SYNTAX ${DS}{32} SINGLE-VALUE`),
  at('1.3.6.1.4.1.99999.1.6', 'acmeEmergencyContact', `DESC 'Emergency contact' EQUALITY caseIgnoreMatch SYNTAX ${DS}{256}`),
  at('1.3.6.1.4.1.99999.2.1', 'acmeDeviceSerial', `DESC 'Hardware serial number' EQUALITY caseIgnoreMatch SYNTAX ${DS}{64} SINGLE-VALUE`),
  at('1.3.6.1.4.1.99999.2.2', 'acmeDeviceType', `DESC 'Laptop, phone, tablet...' EQUALITY caseIgnoreMatch SYNTAX ${DS}{32} SINGLE-VALUE`),
  at('1.3.6.1.4.1.99999.2.3', 'acmeAssignedTo', `DESC 'Person the device is assigned to' EQUALITY distinguishedNameMatch SYNTAX ${DN} SINGLE-VALUE`),
  at('1.3.6.1.4.1.99999.2.4', 'acmeOperatingSystem', `DESC 'Installed operating system' EQUALITY caseIgnoreMatch SYNTAX ${DS}{64} SINGLE-VALUE`),
  at('1.3.6.1.4.1.99999.2.5', 'acmeAssetTag', `DESC 'Inventory asset tag' EQUALITY caseIgnoreMatch SYNTAX ${DS}{32} SINGLE-VALUE`),
  at('1.3.6.1.4.1.99999.2.6', 'acmeDeviceStatus', `DESC 'Lifecycle status' EQUALITY caseIgnoreMatch SYNTAX ${DS}{32} SINGLE-VALUE`),
  at('1.3.6.1.4.1.99999.3.1', 'acmePartnerCompany', `DESC 'Partner organization' EQUALITY caseIgnoreMatch SYNTAX ${DS}{128} SINGLE-VALUE`),
  at('1.3.6.1.4.1.99999.3.2', 'acmeContractEnd', `DESC 'Contract end date (YYYY-MM-DD)' EQUALITY caseIgnoreMatch SYNTAX ${DS}{10} SINGLE-VALUE`),
  at('1.3.6.1.4.1.99999.3.3', 'acmeSponsor', `DESC 'Internal sponsor' EQUALITY distinguishedNameMatch SYNTAX ${DN} SINGLE-VALUE`),

  // --- object classes ---
  "objectclass ( 2.5.6.0 NAME 'top' ABSTRACT MUST objectClass )",
  "objectclass ( 1.3.6.1.4.1.1466.101.120.111 NAME 'extensibleObject' SUP top AUXILIARY )",
  "objectclass ( 2.5.6.4 NAME 'organization' SUP top STRUCTURAL MUST o MAY ( userPassword $ seeAlso $ businessCategory $ telephoneNumber $ facsimileTelephoneNumber $ street $ postalCode $ postalAddress $ st $ l $ description ) )",
  "objectclass ( 2.5.6.5 NAME 'organizationalUnit' SUP top STRUCTURAL MUST ou MAY ( userPassword $ seeAlso $ businessCategory $ telephoneNumber $ facsimileTelephoneNumber $ street $ postalCode $ postalAddress $ st $ l $ description ) )",
  "objectclass ( 1.3.6.1.4.1.1466.344 NAME 'dcObject' SUP top AUXILIARY MUST dc )",
  "objectclass ( 2.5.6.6 NAME 'person' SUP top STRUCTURAL MUST ( sn $ cn ) MAY ( userPassword $ telephoneNumber $ seeAlso $ description ) )",
  "objectclass ( 2.5.6.7 NAME 'organizationalPerson' SUP person STRUCTURAL MAY ( title $ facsimileTelephoneNumber $ street $ postalCode $ postalAddress $ st $ l $ ou $ roomNumber ) )",
  "objectclass ( 2.16.840.1.113730.3.2.2 NAME 'inetOrgPerson' SUP organizationalPerson STRUCTURAL MAY ( carLicense $ departmentNumber $ displayName $ employeeNumber $ employeeType $ givenName $ homePhone $ initials $ jpegPhoto $ labeledURI $ mail $ manager $ mobile $ o $ preferredLanguage $ roomNumber $ secretary $ uid ) )",
  "objectclass ( 2.5.6.9 NAME 'groupOfNames' SUP top STRUCTURAL MUST ( member $ cn ) MAY ( businessCategory $ seeAlso $ owner $ ou $ o $ description ) )",
  "objectclass ( 2.5.6.17 NAME 'groupOfUniqueNames' SUP top STRUCTURAL MUST ( uniqueMember $ cn ) MAY ( businessCategory $ seeAlso $ owner $ ou $ o $ description ) )",
  "objectclass ( 2.5.6.14 NAME 'device' SUP top STRUCTURAL MUST cn MAY ( serialNumber $ seeAlso $ owner $ ou $ o $ l $ description ) )",
  "objectclass ( 0.9.2342.19200300.100.4.19 NAME 'simpleSecurityObject' SUP top AUXILIARY MUST userPassword )",
  "objectclass ( 1.3.6.1.4.1.99999.10.1 NAME 'acmePerson' DESC 'ACME employee extensions' SUP top AUXILIARY MAY ( acmeBadgeNumber $ acmeCostCenter $ acmeSkill $ acmeOfficeLocation $ acmePronouns $ acmeEmergencyContact ) )",
  "objectclass ( 1.3.6.1.4.1.99999.10.2 NAME 'acmeDevice' DESC 'ACME managed hardware' SUP top STRUCTURAL MUST ( cn $ acmeDeviceSerial ) MAY ( acmeDeviceType $ acmeAssignedTo $ acmeOperatingSystem $ acmeAssetTag $ acmeDeviceStatus $ description $ l ) )",
  "objectclass ( 1.3.6.1.4.1.99999.10.3 NAME 'acmePartner' DESC 'External partner / contractor' SUP top AUXILIARY MAY ( acmePartnerCompany $ acmeContractEnd $ acmeSponsor ) )",
].join('\n');

export const SEED_BASE_DN = 'dc=example,dc=com';
const B = SEED_BASE_DN;
const PW = 'password';

interface Person {
  uid: string;
  given: string;
  sn: string;
  title: string;
  dept: string;
  phone: string;
  office: string;
  skills?: string[];
  manager?: string;
  pronouns?: string;
  cost?: string;
}

const people: Person[] = [
  { uid: 'admin', given: 'Ada', sn: 'Admin', title: 'Directory Administrator', dept: 'IT', phone: '+1 555 0100', office: 'HQ', skills: ['LDAP', 'Security'], cost: 'CC-100', pronouns: 'she/her' },
  { uid: 'alice', given: 'Alice', sn: 'Anderson', title: 'Senior Software Engineer', dept: 'Engineering', phone: '+1 555 0101', office: 'HQ', skills: ['TypeScript', 'Go'], manager: 'carol', cost: 'CC-200', pronouns: 'she/her' },
  { uid: 'bob', given: 'Bob', sn: 'Baker', title: 'Account Executive', dept: 'Sales', phone: '+1 555 0102', office: 'Denver', skills: ['Negotiation'], cost: 'CC-300' },
  { uid: 'carol', given: 'Carol', sn: 'Chen', title: 'Engineering Manager', dept: 'Engineering', phone: '+1 555 0103', office: 'HQ', skills: ['Leadership', 'Kubernetes'], cost: 'CC-200' },
  { uid: 'dave', given: 'Dave', sn: 'Diaz', title: 'Helpdesk Technician', dept: 'IT', phone: '+1 555 0104', office: 'Austin', skills: ['Hardware'], cost: 'CC-100' },
  { uid: 'erin', given: 'Erin', sn: 'Evans', title: 'HR Business Partner', dept: 'Human Resources', phone: '+1 555 0105', office: 'Remote', cost: 'CC-400' },
  { uid: 'fatima', given: 'Fatima', sn: 'Farouk', title: 'Product Designer', dept: 'Design', phone: '+1 555 0106', office: 'Denver', skills: ['Figma', 'Research'], manager: 'carol', cost: 'CC-200' },
  { uid: 'george', given: 'George', sn: 'Gupta', title: 'Marketing Lead', dept: 'Marketing', phone: '+1 555 0107', office: 'HQ', cost: 'CC-500' },
];

const personDn = (uid: string) => `uid=${uid},ou=people,${B}`;

export const SEED_ENTRIES: MemorySeedEntry[] = [
  { dn: B, attributes: { objectClass: ['top', 'dcObject', 'organization'], dc: 'example', o: 'Example Corp' } },
  ...['people', 'groups', 'devices', 'partners', 'system'].map((ou) => ({
    dn: `ou=${ou},${B}`,
    attributes: { objectClass: ['top', 'organizationalUnit'], ou, description: `${ou[0].toUpperCase()}${ou.slice(1)} container` },
  })),
  {
    dn: `cn=portal,ou=system,${B}`,
    attributes: { objectClass: ['top', 'device', 'simpleSecurityObject'], cn: 'portal', userPassword: 'portal-secret', description: 'Portal service account' },
  },
  ...people.map((p) => ({
    dn: personDn(p.uid),
    attributes: {
      objectClass: ['top', 'person', 'organizationalPerson', 'inetOrgPerson', 'acmePerson'],
      uid: p.uid,
      cn: `${p.given} ${p.sn}`,
      givenName: p.given,
      sn: p.sn,
      displayName: `${p.given} ${p.sn}`,
      mail: `${p.uid}@example.com`,
      title: p.title,
      departmentNumber: p.dept,
      telephoneNumber: p.phone,
      employeeType: 'Full time',
      acmeOfficeLocation: p.office,
      ...(p.skills ? { acmeSkill: p.skills } : {}),
      ...(p.cost ? { acmeCostCenter: p.cost } : {}),
      ...(p.pronouns ? { acmePronouns: p.pronouns } : {}),
      ...(p.manager ? { manager: personDn(p.manager) } : {}),
      acmeBadgeNumber: `B-${1000 + people.indexOf(p)}`,
      userPassword: PW,
    },
  })),
  {
    dn: `uid=frank,ou=partners,${B}`,
    attributes: {
      objectClass: ['top', 'person', 'organizationalPerson', 'inetOrgPerson', 'acmePartner'],
      uid: 'frank',
      cn: 'Frank Fischer',
      givenName: 'Frank',
      sn: 'Fischer',
      mail: 'frank@partner.example.net',
      title: 'Consultant',
      o: 'Fischer Consulting',
      acmePartnerCompany: 'Fischer Consulting',
      acmeContractEnd: '2027-06-30',
      acmeSponsor: personDn('carol'),
      telephoneNumber: '+49 30 555 0199',
      userPassword: PW,
    },
  },
  {
    dn: `uid=hana,ou=partners,${B}`,
    attributes: {
      objectClass: ['top', 'person', 'organizationalPerson', 'inetOrgPerson', 'acmePartner'],
      uid: 'hana',
      cn: 'Hana Hoshino',
      givenName: 'Hana',
      sn: 'Hoshino',
      mail: 'hana@vendor.example.org',
      title: 'Implementation Specialist',
      o: 'Vendor Co',
      acmePartnerCompany: 'Vendor Co',
      acmeContractEnd: '2026-12-31',
      acmeSponsor: personDn('bob'),
      userPassword: PW,
    },
  },
  ...(
    [
      ['directory-admins', 'Portal administrators', ['admin']],
      ['engineering', 'Engineering department', ['alice', 'carol', 'fatima']],
      ['sales', 'Sales team', ['bob']],
      ['helpdesk', 'IT helpdesk', ['dave', 'admin']],
      ['hr', 'Human resources', ['erin']],
    ] as const
  ).map(([cn, description, members]) => ({
    dn: `cn=${cn},ou=groups,${B}`,
    attributes: { objectClass: ['top', 'groupOfNames'], cn, description, member: members.map(personDn), owner: personDn('admin') },
  })),
  ...(
    [
      ['LT-0001', 'SN-8842-A', 'Laptop', 'alice', 'macOS 15', 'In use'],
      ['LT-0002', 'SN-8842-B', 'Laptop', 'bob', 'Windows 11', 'In use'],
      ['PH-0001', 'SN-1177-P', 'Phone', 'alice', 'iOS 18', 'In use'],
      ['LT-0003', 'SN-9001-C', 'Laptop', 'carol', 'Ubuntu 24.04', 'In repair'],
      ['TB-0001', 'SN-5521-T', 'Tablet', undefined, 'iPadOS 18', 'In stock'],
    ] as const
  ).map(([cn, serial, type, owner, os, status]) => ({
    dn: `cn=${cn},ou=devices,${B}`,
    attributes: {
      objectClass: ['top', 'acmeDevice'],
      cn,
      acmeDeviceSerial: serial,
      acmeDeviceType: type,
      acmeOperatingSystem: os,
      acmeDeviceStatus: status,
      acmeAssetTag: `AT-${cn}`,
      ...(owner ? { acmeAssignedTo: personDn(owner) } : {}),
    },
  })),
];
