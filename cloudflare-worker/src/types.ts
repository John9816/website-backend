export interface Env {
  DB: D1Database;
  IMAGE_QUEUE: Queue<ImageGenerateQueueMessage>;
  R2_BUCKET?: R2Bucket;
  APP_KV: KVNamespace;
  CORS_ORIGINS: string;
  PUBLIC_R2_BASE_URL: string;
  AI_CHAT_BASE_URL: string;
  AI_CHAT_API_KEY: string;
  IMAGE_API_BASE_URL: string;
  IMAGE_API_KEY: string;
  TELEGRAM_BOT_TOKEN?: string;
  TELEGRAM_CHAT_ID?: string;
  JWT_SECRET?: string;
  ADMIN_DEFAULT_USERNAME?: string;
  ADMIN_DEFAULT_PASSWORD?: string;
}

export interface ImageGenerateQueueMessage {
  type: "generate";
  userId: number;
  taskId: number;
  prompt: string;
  model: string;
  size: string | null;
  quality?: string | null;
  n: number;
}

export interface AuthUser {
  id: number;
  username: string;
  role: string;
}

export interface RequestContext {
  request: Request;
  env: Env;
  url: URL;
  params: Record<string, string>;
  user: AuthUser | null;
  waitUntil?: (promise: Promise<unknown>) => void;
}

export type Handler = (ctx: RequestContext) => Promise<Response>;

export class HttpError extends Error {
  status: number;
  code: number;

  constructor(status: number, message: string, code = status) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export interface PageView<T> {
  items: T[];
  content: T[];
  total: number;
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
