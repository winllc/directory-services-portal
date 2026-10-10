/**
 * Types shared between the portal server and web client.
 */

// ---------------------------------------------------------------------------
// LDAP schema
// ---------------------------------------------------------------------------

/** Where a schema element came from. */
export type SchemaSource = 'server' | 'custom';

export interface AttributeTypeDef {
  oid: string;
  /** All NAMEs of the attribute type; the first is the primary name. */
  names: string[];
  desc?: string;
  sup?: string;
  equality?: string;
  ordering?: string;
  substr?: string;
  syntax?: string;
  singleValue: boolean;
  collective?: boolean;
  noUserModification: boolean;
  obsolete?: boolean;
  usage?: 'userApplications' | 'directoryOperation' | 'distributedOperation' | 'dSAOperation';
  source: SchemaSource;
  /** Portal-only display hint, never sent to the directory. */
  displayName?: string;
}

export type ObjectClassKind = 'STRUCTURAL' | 'AUXILIARY' | 'ABSTRACT';

export interface ObjectClassDef {
  oid: string;
  names: string[];
  desc?: string;
  sup: string[];
  kind: ObjectClassKind;
  must: string[];
  may: string[];
  obsolete?: boolean;
  source: SchemaSource;
  displayName?: string;
}

export interface SchemaSnapshot {
  attributeTypes: AttributeTypeDef[];
  objectClasses: ObjectClassDef[];
  /** ISO timestamp of the last successful pull from the directory, if any. */
  fetchedAt?: string;
  /** DN of the subschema subentry the server schema was read from. */
  subschemaDn?: string;
}

/** Portal-side overrides/extensions layered on top of the server schema. */
export interface CustomSchema {
  attributeTypes: AttributeTypeDef[];
  objectClasses: ObjectClassDef[];
}

// ---------------------------------------------------------------------------
// Forms
// ---------------------------------------------------------------------------

/** Free text (single line or multi-line) or drop down. */
export type FieldWidget = 'text' | 'textarea' | 'dropdown';

export type TextFormat = 'plain' | 'email' | 'phone' | 'url' | 'number' | 'dn';

export interface OptionItem {
  value: string;
  label?: string;
}

export type DropdownSource =
  | { type: 'static'; options: OptionItem[] }
  | {
      type: 'ldap';
      /** Search base; must be a valid DN. */
      baseDn: string;
      scope: 'one' | 'sub';
      filter: string;
      /** Attribute whose value is stored ("dn" means the entry DN). */
      valueAttribute: string;
      /** Attribute shown to the user. */
      labelAttribute: string;
    };

export interface FormField {
  id: string;
  /** LDAP attribute name. */
  attribute: string;
  label: string;
  helpText?: string;
  widget: FieldWidget;
  /** Whether the attribute accepts multiple values in this form. */
  multiValued: boolean;
  required: boolean;
  /** Displayed but never written through this form. */
  readOnly: boolean;
  /** When true, a user may edit this field on their own entry from the "My profile" page. */
  selfEditable: boolean;
  /** Free-text validation. */
  format?: TextFormat;
  pattern?: string;
  maxLength?: number;
  placeholder?: string;
  /** Drop-down configuration (required when widget === 'dropdown'). */
  dropdown?: DropdownSource;
  /** For drop downs: permit values that are not in the option list. */
  allowCustomValues?: boolean;
  /** Optional section heading used to group fields visually. */
  section?: string;
  defaultValues?: string[];
}

export interface FormDefinition {
  id: string;
  name: string;
  description?: string;
  /** Object classes written to new entries. */
  objectClasses: string[];
  /** Attribute used as the RDN when creating entries (must be a field). */
  rdnAttribute: string;
  fields: FormField[];
  createdAt: string;
  updatedAt: string;
}

// ---------------------------------------------------------------------------
// Directory definitions
// ---------------------------------------------------------------------------

export type DefinitionMode = 'readwrite' | 'readonly';

export type AccessLevel = 'none' | 'read' | 'write';

/**
 * How to locate the logged-in user's entry inside a definition.
 *  - `dn`: the entry DN equals the user's DN.
 *  - `attribute`: entry[entryAttribute] equals user[userAttribute]
 *    (userAttribute "dn" means the user's DN, which is useful for member/owner style links).
 */
export type SelfMatch =
  | { type: 'none' }
  | { type: 'dn' }
  | { type: 'attribute'; entryAttribute: string; userAttribute: string };

export interface DirectoryDefinition {
  id: string;
  name: string;
  slug: string;
  description?: string;
  formId: string;
  /** Namespace this definition is scoped to. All operations are confined below it. */
  baseDn: string;
  scope: 'one' | 'sub';
  /** Extra LDAP filter ANDed with the form's object classes. */
  filter?: string;
  /** `readonly` definitions act as white pages: nobody can write through them. */
  mode: DefinitionMode;
  /** Grants read access to every authenticated user (typical for white pages). */
  everyoneCanRead: boolean;
  /** Columns shown in list views. */
  listAttributes: string[];
  /** Attributes matched by the quick search box. */
  searchAttributes: string[];
  /** Attribute used as the entry's display title. */
  titleAttribute: string;
  /** Allowed parent containers for new entries (defaults to baseDn). */
  createContainers: string[];
  selfMatch: SelfMatch;
  icon?: string;
  createdAt: string;
  updatedAt: string;
}

// ---------------------------------------------------------------------------
// Permissions
// ---------------------------------------------------------------------------

export type SubjectType = 'user' | 'group';

export interface PermissionGrant {
  id: string;
  definitionId: string;
  subjectType: SubjectType;
  /** Username for users, group DN for groups. */
  subject: string;
  /** Friendly name for display only. */
  subjectLabel?: string;
  access: Exclude<AccessLevel, 'none'>;
  createdAt: string;
}

// ---------------------------------------------------------------------------
// Directory entries & sessions
// ---------------------------------------------------------------------------

/** Attribute names are lower-cased; values are always arrays of strings. */
export type EntryAttributes = Record<string, string[]>;

export interface DirectoryEntry {
  dn: string;
  attributes: EntryAttributes;
}

export interface EntryListResponse {
  entries: DirectoryEntry[];
  total: number;
  page: number;
  pageSize: number;
  /** True when the directory size limit was hit and results are incomplete. */
  truncated: boolean;
}

export interface SessionUser {
  username: string;
  dn: string;
  displayName: string;
  mail?: string;
  groups: string[];
  isAdmin: boolean;
  /** How the user signed in. */
  authMethod?: 'password' | 'x509';
  /** Subject DN of the client certificate, for certificate sign-ins. */
  certificateSubject?: string;
}

/** Definition as seen by a specific user, including their effective access. */
export interface DefinitionSummary extends DirectoryDefinition {
  access: AccessLevel;
  formName?: string;
}

export interface SelfEntryResult {
  definition: DefinitionSummary;
  form: FormDefinition;
  entries: DirectoryEntry[];
  /** Fields the user may edit on their own entry. */
  editableFields: string[];
  error?: string;
}

export interface ServerInfo {
  mode: 'ldap' | 'memory';
  ldapUrl?: string;
  baseDn: string;
  connected: boolean;
  error?: string;
  version: string;
  /** Sign-in methods offered by the server. */
  auth?: { password: boolean; x509: boolean; x509AutoLogin: boolean };
  /** Text shown on the login page, e.g. demo credentials. */
  loginHint?: string;
  /** Fixed banner shown at the top and bottom of every page (absent when disabled). */
  banner?: { text: string; foreground: string; background: string };
}

export interface SubjectSearchResult {
  type: SubjectType;
  id: string;
  label: string;
  dn: string;
}

export interface ApiError {
  error: string;
  details?: Record<string, string>;
}

/** Outcome of an audited action: refused by the portal (denied) or by the directory or credentials (failed). */
export type AuditOutcome = 'success' | 'denied' | 'failed';

export interface AuditChange {
  attribute: string;
  /** Secret values are recorded as "(redacted)". */
  before: string[];
  after: string[];
}

/** One entry in the audit log of changes and sign-ins. Empty fields are omitted. */
export interface AuditEvent {
  id: string;
  at: string;
  /** Dotted name, e.g. "entry.update", "auth.login", "grant.revoke". */
  action: string;
  outcome: AuditOutcome;
  /** Absent for anonymous requests such as a failed sign-in. */
  actor?: { username: string; dn: string; authMethod?: string };
  sourceAddress?: string;
  target?: { type: string; id?: string; name?: string };
  definitionId?: string;
  message?: string;
  changes?: AuditChange[];
  details?: Record<string, string>;
}

export interface AuditPage {
  events: AuditEvent[];
  total: number;
  page: number;
  pageSize: number;
  /** How many events the server holds in memory to search, of at most {@link capacity}. */
  retained: number;
  capacity: number;
  /** Timestamp of the oldest searchable event. Older ones are only in the audit files. */
  oldest?: string;
}

// ---------------------------------------------------------------------------
// Metrics
// ---------------------------------------------------------------------------

export type MetricKind = 'count' | 'distribution' | 'completeness' | 'timeline' | 'numeric';
export type ChartType = 'stat' | 'bar' | 'column' | 'stacked' | 'donut' | 'line' | 'area' | 'table';
export type ValueTransform =
  | 'none'
  | 'lowercase'
  | 'uppercase'
  | 'emailDomain'
  | 'dnRdn'
  | 'dnParent'
  | 'prefix'
  | 'regex'
  | 'year'
  | 'quarter'
  | 'month'
  | 'week'
  | 'day';
export type TimeBucket = 'day' | 'week' | 'month' | 'quarter' | 'year';
export type WidgetWidth = 'third' | 'half' | 'two-thirds' | 'full';

/**
 * One chart or statistic: a source directory (plus an optional extra filter), what to measure,
 * and how to draw it. Unused options are omitted.
 */
export interface MetricWidget {
  id?: string;
  title: string;
  description?: string;
  definitionId: string;
  filter?: string;
  kind: MetricKind;
  /** distribution, timeline, numeric ("dn" is the entry DN). */
  attribute?: string;
  transform?: ValueTransform;
  /** prefix: number of characters; regex: pattern (first group is used). */
  transformArg?: string;
  /** distribution: second attribute, drawn as stacked segments. */
  splitBy?: string;
  splitTransform?: ValueTransform;
  /** completeness: attributes to check (default: every form field). */
  attributes?: string[];
  /** distribution: top N (rest folded into "Other"); timeline: last N periods. */
  limit?: number;
  /** distribution: count entries without a value as "(none)" (default true). */
  includeMissing?: boolean;
  sort?: 'count' | 'label';
  bucket?: TimeBucket;
  cumulative?: boolean;
  /** numeric: the values themselves, or how many values each entry has. */
  measure?: 'values' | 'valueCount';
  bins?: number;
  chart: ChartType;
  width?: WidgetWidth;
}

export interface MetricRow {
  key: string;
  label: string;
  value: number;
  /** value / total (numeric: / number of values). */
  share?: number;
  other?: boolean;
  missing?: boolean;
}

export interface MetricSeries {
  key: string;
  label: string;
  other?: boolean;
  missing?: boolean;
}

export interface MetricStats {
  count: number;
  min?: number;
  max?: number;
  mean?: number;
  median?: number;
  p90?: number;
  sum?: number;
  invalid: number;
  missing: number;
}

export interface MetricResult {
  kind: MetricKind;
  /** Entries scanned. */
  total: number;
  truncated: boolean;
  computedAt: string;
  rows?: MetricRow[];
  series?: MetricSeries[];
  /** matrix[row][series] counts for split distributions. */
  matrix?: number[][];
  distinctValues?: number;
  stats?: MetricStats;
  warnings?: string[];
}

/** A widget's result, or why it could not be computed. */
export interface MetricOutcome {
  result?: MetricResult;
  error?: string;
  details?: Record<string, string>;
  forbidden?: boolean;
}

export interface Dashboard {
  id: string;
  name: string;
  description?: string;
  shared: boolean;
  widgets: MetricWidget[];
  createdAt: string;
  updatedAt: string;
}

export interface DashboardSummary {
  id: string;
  name: string;
  description?: string;
  shared: boolean;
  widgets: number;
  updatedAt: string;
}

export interface AttributeChoice {
  attribute: string;
  label: string;
  /** form field, list column, operational timestamp, schema attribute (admins) or the entry DN. */
  source: 'form' | 'list' | 'operational' | 'schema' | 'dn';
  multiValued: boolean;
  widget?: FieldWidget;
  format?: TextFormat;
}

export interface MetricSource {
  id: string;
  name: string;
  slug: string;
  icon?: string;
  description?: string;
  attributes: AttributeChoice[];
}

export interface DirectorySummaryRow {
  id: string;
  name: string;
  slug: string;
  icon?: string;
  count?: number;
  truncated: boolean;
  error?: string;
}

export interface OverviewResponse {
  definition: { id: string; name: string; slug: string; icon?: string; description?: string };
  widgets: MetricWidget[];
  results: Record<string, MetricOutcome>;
}

// ---------------------------------------------------------------------------
// Analysis
// ---------------------------------------------------------------------------

export type AnalysisType = 'compare' | 'quality';

export interface AnalysisSource {
  definitionId: string;
  filter?: string;
  label?: string;
  /** compare: attribute that identifies the same object in every source. */
  keyAttribute?: string;
}

export interface AttributeMapping {
  label?: string;
  /** One attribute for all sources, or one per source. */
  attributes: string[];
}

export interface CompareOptions {
  attributes: AttributeMapping[];
  ignoreCase: boolean;
  normalizeWhitespace: boolean;
  ignoreOrder: boolean;
  keyIgnoreCase: boolean;
}

export type RuleType = 'required' | 'singleValue' | 'unique' | 'pattern' | 'format' | 'allowedValues' | 'length' | 'dnExists';
export type RuleFormat = 'email' | 'phone' | 'url' | 'number' | 'integer' | 'date' | 'dn';
export type Severity = 'error' | 'warning';

export interface QualityRule {
  id?: string;
  attribute: string;
  type: RuleType;
  pattern?: string;
  format?: RuleFormat;
  values?: string[];
  min?: number;
  max?: number;
  /** unique: within each source, or across all sources. */
  scope?: 'source' | 'all';
  ignoreCase?: boolean;
  severity?: Severity;
  description?: string;
}

export interface QualityOptions {
  /** Also check every form field's rules (required, single value, format, pattern, options). */
  formRules: boolean;
  rules: QualityRule[];
}

export interface AnalysisLastRun {
  at: string;
  by: string;
  entries: number;
  issues: number;
  summary: string;
}

export interface AnalysisDefinition {
  id: string;
  name: string;
  description?: string;
  type: AnalysisType;
  sources: AnalysisSource[];
  compare?: CompareOptions;
  quality?: QualityOptions;
  createdAt: string;
  updatedAt: string;
  lastRun?: AnalysisLastRun;
}

export type AnalysisInput = Pick<AnalysisDefinition, 'name' | 'description' | 'type' | 'sources' | 'compare' | 'quality'>;

export interface AnalysisSourceStats {
  label: string;
  definitionId: string;
  definitionName: string;
  entries: number;
  truncated: boolean;
  keyed?: number;
  missingKey?: number;
  duplicateKeys?: number;
}

export interface CompareRow {
  key: string;
  status: 'missing' | 'different' | 'duplicate';
  duplicate?: boolean;
  present: boolean[];
  /** DNs per source (null where absent). */
  dns: (string[] | null)[];
  differences?: { label: string; values: (string[] | null)[] }[];
}

export interface CompareResult {
  sources: AnalysisSourceStats[];
  summary: { keys: number; inAll: number; identical: number; different: number; partial: number; onlyIn: number[]; duplicates: number };
  attributes: { label: string; attributes: string[]; differences: number }[];
  rows: CompareRow[];
  rowCount: number;
  rowsTruncated: boolean;
}

export interface QualityRuleStats {
  id: string;
  label: string;
  attribute: string;
  type: RuleType | 'form';
  severity: Severity;
  violations: number;
  /** Index of the source a form rule belongs to. */
  source?: number;
}

export interface QualityIssue {
  ruleId: string;
  source: number;
  dn: string;
  title: string;
  attribute: string;
  values: string[];
  message: string;
  severity: Severity;
}

export interface QualityResult {
  sources: AnalysisSourceStats[];
  summary: { entries: number; entriesWithIssues: number; issues: number; errors: number; warnings: number; cleanShare: number };
  rules: QualityRuleStats[];
  issues: QualityIssue[];
  issuesTruncated: boolean;
}

export interface AnalysisResult {
  type: AnalysisType;
  ranAt: string;
  durationMs: number;
  compare?: CompareResult;
  quality?: QualityResult;
  warnings?: string[];
}
