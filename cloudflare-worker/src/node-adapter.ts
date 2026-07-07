import Database from "better-sqlite3";
import { createReadStream, existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, normalize, resolve, sep } from "node:path";
import type { Env, ImageGenerateQueueMessage } from "./types";
import * as image from "./modules/image";

type D1Param = string | number | boolean | null | undefined | ArrayBuffer | Uint8Array;

export class LocalD1Database {
  private readonly db: Database.Database;

  constructor(path: string) {
    mkdirSync(dirname(path), { recursive: true });
    this.db = new Database(path);
    this.db.pragma("journal_mode = WAL");
    this.db.pragma("foreign_keys = ON");
  }

  prepare(sql: string): D1PreparedStatement {
    return new LocalD1PreparedStatement(this.db, sql, []) as unknown as D1PreparedStatement;
  }

  async batch<T = unknown>(statements: D1PreparedStatement[]): Promise<D1Result<T>[]> {
    return statements.map((statement) => (statement as unknown as LocalD1PreparedStatement).runSync() as D1Result<T>);
  }

  async exec(sql: string): Promise<D1ExecResult> {
    this.db.exec(sql);
    return { count: 0, duration: 0 };
  }
}

class LocalD1PreparedStatement {
  constructor(
    private readonly db: Database.Database,
    private readonly sql: string,
    private readonly params: D1Param[]
  ) {}

  bind(...values: D1Param[]): D1PreparedStatement {
    return new LocalD1PreparedStatement(this.db, this.sql, values) as unknown as D1PreparedStatement;
  }

  async first<T = unknown>(colName?: string): Promise<T | null> {
    const row = this.db.prepare(this.sql).get(...this.normalizeParams()) as Record<string, unknown> | undefined;
    if (!row) return null;
    return (colName ? row[colName] : row) as T;
  }

  async all<T = unknown>(): Promise<D1Result<T>> {
    const results = this.db.prepare(this.sql).all(...this.normalizeParams()) as T[];
    return {
      results,
      success: true,
      meta: {
        duration: 0,
        served_by: "local-sqlite",
        size_after: 0,
        changed_db: false,
        changes: 0,
        last_row_id: 0,
        rows_read: results.length,
        rows_written: 0
      }
    };
  }

  async run<T = unknown>(): Promise<D1Result<T>> {
    return this.runSync() as D1Result<T>;
  }

  async raw<T = unknown[]>(): Promise<T[]> {
    return this.db.prepare(this.sql).raw().all(...this.normalizeParams()) as T[];
  }

  runSync(): D1Result {
    const result = this.db.prepare(this.sql).run(...this.normalizeParams());
    const lastRowId = typeof result.lastInsertRowid === "bigint"
      ? Number(result.lastInsertRowid)
      : Number(result.lastInsertRowid || 0);
    return {
      results: [],
      success: true,
      meta: {
        duration: 0,
        served_by: "local-sqlite",
        size_after: 0,
        changed_db: result.changes > 0,
        changes: result.changes,
        last_row_id: lastRowId,
        rows_read: 0,
        rows_written: result.changes
      }
    };
  }

  private normalizeParams() {
    return this.params.map((value) => {
      if (value === undefined) return null;
      if (typeof value === "boolean") return value ? 1 : 0;
      if (value instanceof Uint8Array) return Buffer.from(value);
      if (value instanceof ArrayBuffer) return Buffer.from(value);
      return value;
    });
  }
}

export class LocalKVNamespace {
  private readonly values = new Map<string, { value: string; expiresAt: number | null }>();

  async get(key: string, type?: "text" | "json" | "arrayBuffer" | "stream"): Promise<unknown> {
    const item = this.values.get(key);
    if (!item) return null;
    if (item.expiresAt && item.expiresAt <= Date.now()) {
      this.values.delete(key);
      return null;
    }
    if (type === "json") return JSON.parse(item.value);
    if (type === "arrayBuffer") return new TextEncoder().encode(item.value).buffer;
    if (type === "stream") return new Blob([item.value]).stream();
    return item.value;
  }

  async put(key: string, value: string | ArrayBuffer | ArrayBufferView | ReadableStream, options?: KVNamespacePutOptions): Promise<void> {
    let text: string;
    if (typeof value === "string") {
      text = value;
    } else if (value instanceof ReadableStream) {
      text = await new Response(value).text();
    } else {
      const bytes = value instanceof ArrayBuffer
        ? new Uint8Array(value)
        : new Uint8Array(value.buffer as ArrayBuffer, value.byteOffset, value.byteLength);
      text = new TextDecoder().decode(bytes);
    }
    this.values.set(key, {
      value: text,
      expiresAt: options?.expirationTtl ? Date.now() + options.expirationTtl * 1000 : null
    });
  }

  async delete(key: string): Promise<void> {
    this.values.delete(key);
  }

  async list(): Promise<KVNamespaceListResult<unknown, string>> {
    return { keys: [...this.values.keys()].map((name) => ({ name })), list_complete: true, cacheStatus: null };
  }

  async getWithMetadata(): Promise<KVNamespaceGetWithMetadataResult<unknown, unknown>> {
    throw new Error("LocalKVNamespace.getWithMetadata is not implemented");
  }
}

export class LocalR2Bucket {
  constructor(private readonly root: string) {
    mkdirSync(root, { recursive: true });
  }

  async put(key: string, value: ReadableStream | ArrayBuffer | ArrayBufferView | string | null | Blob, options?: R2PutOptions): Promise<R2Object> {
    const filePath = this.pathForKey(key);
    mkdirSync(dirname(filePath), { recursive: true });
    const bytes = await toBuffer(value);
    writeFileSync(filePath, bytes);
    writeFileSync(this.metaPath(filePath), JSON.stringify({
      httpMetadata: options?.httpMetadata || {},
      customMetadata: options?.customMetadata || {}
    }));
    return this.headObject(key, bytes.length);
  }

  async get(key: string): Promise<R2ObjectBody | null> {
    const filePath = this.pathForKey(key);
    if (!existsSync(filePath) || !statSync(filePath).isFile()) return null;
    const meta = this.readMeta(filePath);
    const body = readFileSync(filePath);
    return {
      ...this.headObject(key, body.length, meta),
      body,
      bodyUsed: false,
      arrayBuffer: async () => body.buffer.slice(body.byteOffset, body.byteOffset + body.byteLength) as ArrayBuffer,
      bytes: async () => new Uint8Array(body),
      text: async () => body.toString("utf8"),
      json: async <T>() => JSON.parse(body.toString("utf8")) as T,
      blob: async () => new Blob([body], { type: meta.httpMetadata?.contentType || "application/octet-stream" }),
      writeHttpMetadata(headers: Headers) {
        writeHttpMetadata(headers, meta.httpMetadata);
      }
    } as unknown as R2ObjectBody;
  }

  async head(key: string): Promise<R2Object | null> {
    const filePath = this.pathForKey(key);
    if (!existsSync(filePath) || !statSync(filePath).isFile()) return null;
    return this.headObject(key, statSync(filePath).size, this.readMeta(filePath));
  }

  async delete(keys: string | string[]): Promise<void> {
    for (const key of Array.isArray(keys) ? keys : [keys]) {
      const filePath = this.pathForKey(key);
      if (existsSync(filePath)) rmSync(filePath);
      const metaPath = this.metaPath(filePath);
      if (existsSync(metaPath)) rmSync(metaPath);
    }
  }

  list(): Promise<R2Objects> {
    throw new Error("LocalR2Bucket.list is not implemented");
  }

  createMultipartUpload(): Promise<R2MultipartUpload> {
    throw new Error("LocalR2Bucket.createMultipartUpload is not implemented");
  }

  resumeMultipartUpload(): R2MultipartUpload {
    throw new Error("LocalR2Bucket.resumeMultipartUpload is not implemented");
  }

  private headObject(key: string, size: number, meta = this.readMeta(this.pathForKey(key))): R2Object {
    return {
      key,
      version: "",
      size,
      etag: "",
      httpEtag: "",
      uploaded: new Date(),
      httpMetadata: meta.httpMetadata,
      customMetadata: meta.customMetadata,
      range: undefined,
      checksums: {} as R2Checksums,
      storageClass: "Standard",
      writeHttpMetadata(headers: Headers) {
        writeHttpMetadata(headers, meta.httpMetadata);
      }
    };
  }

  private pathForKey(key: string): string {
    const filePath = resolve(this.root, normalize(key));
    const relative = normalize(filePath).slice(resolve(this.root).length);
    if (!(relative === "" || relative.startsWith(sep))) {
      throw new Error("Invalid R2 key");
    }
    return filePath;
  }

  private metaPath(filePath: string): string {
    return `${filePath}.meta.json`;
  }

  private readMeta(filePath: string): { httpMetadata: R2HTTPMetadata; customMetadata: Record<string, string> } {
    const metaPath = this.metaPath(filePath);
    if (!existsSync(metaPath)) return { httpMetadata: {}, customMetadata: {} };
    try {
      const meta = JSON.parse(readFileSync(metaPath, "utf8").replace(/^\uFEFF/, ""));
      return {
        httpMetadata: meta.httpMetadata || meta.http_metadata || {},
        customMetadata: meta.customMetadata || meta.custom_metadata || {}
      };
    } catch {
      return { httpMetadata: {}, customMetadata: {} };
    }
  }
}

export function createLocalQueue(env: Env): Queue<ImageGenerateQueueMessage> {
  const queue = {
    async send(message: ImageGenerateQueueMessage): Promise<void> {
      setImmediate(() => {
        image.consumeImageQueue(env, message).catch((error) => {
          console.error("Local image queue task failed", error);
        });
      });
    },
    async sendBatch(messages: Iterable<{ body: ImageGenerateQueueMessage }>): Promise<void> {
      for (const item of messages) {
        await queue.send(item.body);
      }
    },
    async metrics() {
      return { backlog: 0 };
    }
  };
  return queue as unknown as Queue<ImageGenerateQueueMessage>;
}

async function toBuffer(value: ReadableStream | ArrayBuffer | ArrayBufferView | string | null | Blob): Promise<Buffer> {
  if (value === null) return Buffer.alloc(0);
  if (typeof value === "string") return Buffer.from(value);
  if (value instanceof Blob) return Buffer.from(await value.arrayBuffer());
  if (value instanceof ReadableStream) return Buffer.from(await new Response(value).arrayBuffer());
  if (value instanceof ArrayBuffer) return Buffer.from(value);
  return Buffer.from(value.buffer, value.byteOffset, value.byteLength);
}

function writeHttpMetadata(headers: Headers, metadata?: R2HTTPMetadata): void {
  if (!metadata) return;
  if (metadata.contentType) headers.set("content-type", metadata.contentType);
  if (metadata.cacheControl) headers.set("cache-control", metadata.cacheControl);
  if (metadata.contentDisposition) headers.set("content-disposition", metadata.contentDisposition);
  if (metadata.contentEncoding) headers.set("content-encoding", metadata.contentEncoding);
  if (metadata.contentLanguage) headers.set("content-language", metadata.contentLanguage);
}
