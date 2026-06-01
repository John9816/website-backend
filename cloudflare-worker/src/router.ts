import { Handler, RequestContext } from "./types";

interface Route {
  method: string;
  parts: string[];
  handler: Handler;
}

export class Router {
  private routes: Route[] = [];

  on(method: string, path: string, handler: Handler): void {
    this.routes.push({
      method: method.toUpperCase(),
      parts: path.split("/").filter(Boolean),
      handler
    });
  }

  all(path: string, handler: Handler): void {
    this.on("GET", path, handler);
    this.on("POST", path, handler);
    this.on("PUT", path, handler);
    this.on("PATCH", path, handler);
    this.on("DELETE", path, handler);
  }

  match(method: string, pathname: string): { handler: Handler; params: Record<string, string> } | null {
    const requestParts = pathname.split("/").filter(Boolean);
    for (const route of this.routes) {
      if (route.method !== method.toUpperCase() || route.parts.length !== requestParts.length) {
        continue;
      }
      const params: Record<string, string> = {};
      let ok = true;
      for (let i = 0; i < route.parts.length; i += 1) {
        const expected = route.parts[i];
        const actual = requestParts[i];
        if (expected.startsWith(":")) {
          params[expected.slice(1)] = decodeURIComponent(actual);
        } else if (expected !== actual) {
          ok = false;
          break;
        }
      }
      if (ok) {
        return { handler: route.handler, params };
      }
    }
    return null;
  }
}

export function childContext(ctx: RequestContext, params: Record<string, string>): RequestContext {
  return { ...ctx, params };
}
