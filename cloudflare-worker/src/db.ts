import { PageView } from "./types";

export function pageOf<T>(content: T[], page: number, size: number, totalElements: number): PageView<T> {
  const totalPages = size <= 0 ? 0 : Math.ceil(totalElements / size);
  return {
    items: content,
    content,
    total: totalElements,
    page,
    size,
    totalElements,
    totalPages
  };
}

export async function count(db: D1Database, sql: string, ...params: unknown[]): Promise<number> {
  const row = await db.prepare(sql).bind(...params).first<{ total: number }>();
  return Number(row?.total ?? 0);
}

export async function firstRequired<T>(stmt: D1PreparedStatement, message = "Not found"): Promise<T> {
  const row = await stmt.first<T>();
  if (!row) {
    const { HttpError } = await import("./types");
    throw new HttpError(404, message);
  }
  return row;
}

export function toBool(value: unknown): boolean {
  return value === true || value === 1 || value === "1";
}

export function nowIso(): string {
  return new Date().toISOString();
}
