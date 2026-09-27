/**
 * Persistent storage for portal configuration (custom schema, forms, directory
 * definitions and permission grants). Data lives in a single JSON document that
 * is written atomically (write to temp file + rename) and serialized through a
 * promise chain so concurrent requests never interleave writes.
 */
import { promises as fs } from 'node:fs';
import path from 'node:path';
import type {
  CustomSchema,
  DirectoryDefinition,
  FormDefinition,
  PermissionGrant,
  SchemaSnapshot,
} from '@dsp/shared';

export interface StoreData {
  version: 1;
  customSchema: CustomSchema;
  /** Last schema pulled from the directory. */
  schemaCache?: SchemaSnapshot;
  forms: FormDefinition[];
  definitions: DirectoryDefinition[];
  grants: PermissionGrant[];
}

export function emptyStore(): StoreData {
  return {
    version: 1,
    customSchema: { attributeTypes: [], objectClasses: [] },
    forms: [],
    definitions: [],
    grants: [],
  };
}

export class ConfigStore {
  private data: StoreData = emptyStore();
  private writeChain: Promise<void> = Promise.resolve();

  /** @param file Path of the JSON file; `null` keeps everything in memory (tests). */
  constructor(private readonly file: string | null) {}

  async load(): Promise<void> {
    if (!this.file) return;
    try {
      const raw = await fs.readFile(this.file, 'utf8');
      const parsed = JSON.parse(raw) as Partial<StoreData>;
      this.data = { ...emptyStore(), ...parsed, version: 1 };
      this.data.customSchema ??= { attributeTypes: [], objectClasses: [] };
    } catch (err) {
      if ((err as NodeJS.ErrnoException).code !== 'ENOENT') throw err;
      this.data = emptyStore();
    }
  }

  get isEmpty(): boolean {
    return !this.data.forms.length && !this.data.definitions.length;
  }

  /** Read-only snapshot. Callers must not mutate the result. */
  get snapshot(): Readonly<StoreData> {
    return this.data;
  }

  /**
   * Apply a mutation and persist. The mutator receives a deep copy; if it throws,
   * nothing is changed.
   */
  async update<T>(mutator: (draft: StoreData) => T): Promise<T> {
    const draft = structuredClone(this.data);
    const result = mutator(draft);
    this.data = draft;
    await this.persist();
    return result;
  }

  private persist(): Promise<void> {
    if (!this.file) return Promise.resolve();
    const file = this.file;
    const json = JSON.stringify(this.data, null, 2);
    this.writeChain = this.writeChain
      .catch(() => undefined)
      .then(async () => {
        await fs.mkdir(path.dirname(file), { recursive: true });
        const tmp = `${file}.${process.pid}.${Date.now()}.tmp`;
        await fs.writeFile(tmp, json, { mode: 0o600 });
        await fs.rename(tmp, file);
      });
    return this.writeChain;
  }
}
