import { createServer, type IncomingMessage } from "node:http";
import { Readable } from "node:stream";
import worker from "./index";
import { createLocalQueue, LocalD1Database, LocalKVNamespace, LocalR2Bucket } from "./node-adapter";
import type { Env } from "./types";

const port = Number(process.env.PORT || 8080);
const host = process.env.HOST || "127.0.0.1";
const dbPath = process.env.DB_PATH || "/opt/website-api/data/website.db";
const r2Root = process.env.R2_ROOT || "/opt/website-api/r2";

const env = {
  DB: new LocalD1Database(dbPath),
  APP_KV: new LocalKVNamespace(),
  R2_BUCKET: new LocalR2Bucket(r2Root),
  CORS_ORIGINS: process.env.CORS_ORIGINS || "http://localhost:5173,http://8.156.95.93,http://hi.751152.xyz,https://hi.751152.xyz",
  PUBLIC_R2_BASE_URL: process.env.PUBLIC_R2_BASE_URL || "",
  AI_CHAT_BASE_URL: process.env.AI_CHAT_BASE_URL || "",
  AI_CHAT_API_KEY: process.env.AI_CHAT_API_KEY || "",
  IMAGE_API_BASE_URL: process.env.IMAGE_API_BASE_URL || "",
  IMAGE_API_KEY: process.env.IMAGE_API_KEY || "",
  TELEGRAM_BOT_TOKEN: process.env.TELEGRAM_BOT_TOKEN || "",
  TELEGRAM_CHAT_ID: process.env.TELEGRAM_CHAT_ID || "",
  JWT_SECRET: process.env.JWT_SECRET || "",
  ADMIN_DEFAULT_USERNAME: process.env.ADMIN_DEFAULT_USERNAME || "admin",
  ADMIN_DEFAULT_PASSWORD: process.env.ADMIN_DEFAULT_PASSWORD || "admin123",
  IMAGE_QUEUE: null
} as unknown as Env;

env.IMAGE_QUEUE = createLocalQueue(env);

const server = createServer(async (req, res) => {
  try {
    const request = await toFetchRequest(req);
    const waitUntilTasks: Promise<unknown>[] = [];
    const response = await worker.fetch(request, env, {
      waitUntil(promise: Promise<unknown>) {
        waitUntilTasks.push(promise);
      },
      passThroughOnException() {}
    } as ExecutionContext);

    res.writeHead(response.status, Object.fromEntries(response.headers.entries()));
    if (!response.body) {
      res.end();
    } else {
      Readable.fromWeb(response.body as unknown as import("node:stream/web").ReadableStream).pipe(res);
    }

    Promise.allSettled(waitUntilTasks).catch(() => undefined);
  } catch (error) {
    console.error(error);
    res.writeHead(500, { "content-type": "application/json;charset=UTF-8" });
    res.end(JSON.stringify({ code: 500, message: "Internal Server Error", data: null }));
  }
});

server.listen(port, host, () => {
  console.log(`website-api listening on http://${host}:${port}`);
  console.log(`SQLite DB: ${dbPath}`);
  console.log(`R2 root: ${r2Root}`);
});

async function toFetchRequest(req: IncomingMessage): Promise<Request> {
  const proto = req.headers["x-forwarded-proto"] || "http";
  const host = req.headers.host || "127.0.0.1";
  const url = `${proto}://${host}${req.url || "/"}`;
  const headers = new Headers();
  for (const [name, value] of Object.entries(req.headers)) {
    if (Array.isArray(value)) {
      for (const item of value) headers.append(name, item);
    } else if (value !== undefined) {
      headers.set(name, value);
    }
  }

  const method = req.method || "GET";
  if (method === "GET" || method === "HEAD") {
    return new Request(url, { method, headers });
  }

  const body = await readBody(req);
  return new Request(url, { method, headers, body });
}

function readBody(req: IncomingMessage): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    req.on("data", (chunk) => chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)));
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", reject);
  });
}
