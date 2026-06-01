import { HttpError, RequestContext } from "./types";

export function json(data: unknown, init: ResponseInit = {}): Response {
  const headers = new Headers(init.headers);
  headers.set("content-type", "application/json;charset=UTF-8");
  return new Response(JSON.stringify(data), { ...init, headers });
}

export function ok(data: unknown = null, init: ResponseInit = {}): Response {
  return json({ code: 0, message: "ok", data }, init);
}

export function emptyOk(init: ResponseInit = {}): Response {
  return json({ code: 0, message: "ok", data: null }, init);
}

export function fail(status: number, message: string, code = status): Response {
  return json({ code, message, data: null }, { status });
}

export async function readJson<T = Record<string, unknown>>(request: Request): Promise<T> {
  if (!request.headers.get("content-type")?.includes("application/json")) {
    return {} as T;
  }
  try {
    return await request.json<T>();
  } catch {
    throw new HttpError(400, "Invalid JSON body");
  }
}

export function requireUser(ctx: RequestContext) {
  if (!ctx.user) {
    throw new HttpError(401, "Unauthorized");
  }
  return ctx.user;
}

export function requireAdmin(ctx: RequestContext) {
  const user = requireUser(ctx);
  if (user.role !== "ADMIN") {
    throw new HttpError(403, "Forbidden");
  }
  return user;
}

export function intParam(url: URL, name: string, fallback: number, min = 0, max = 100): number {
  const raw = url.searchParams.get(name);
  const parsed = raw == null ? fallback : Number.parseInt(raw, 10);
  if (!Number.isFinite(parsed)) {
    return fallback;
  }
  return Math.min(max, Math.max(min, parsed));
}

export function withCors(response: Response, request: Request, allowed: string): Response {
  const headers = new Headers(response.headers);
  const origin = request.headers.get("origin");
  const allowOrigin = pickAllowedOrigin(origin, allowed);
  if (allowOrigin) {
    headers.set("access-control-allow-origin", allowOrigin);
    headers.set("vary", "Origin");
  }
  headers.set("access-control-allow-methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS");
  headers.set("access-control-allow-headers", "Authorization,Content-Type");
  headers.set("access-control-max-age", "86400");
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
}

export function optionsResponse(request: Request, allowed: string): Response {
  return withCors(new Response(null, { status: 204 }), request, allowed);
}

function pickAllowedOrigin(origin: string | null, allowed: string): string | null {
  if (!origin) {
    return null;
  }
  const rules = allowed.split(",").map((item) => item.trim()).filter(Boolean);
  if (rules.includes("*") || rules.includes(origin)) {
    return origin;
  }
  for (const rule of rules) {
    if (rule.startsWith("https://*.")) {
      const suffix = rule.slice("https://*".length);
      if (origin.endsWith(suffix)) {
        return origin;
      }
    }
  }
  return null;
}
