import { count, firstRequired, pageOf } from "../db";
import { emptyOk, intParam, ok, readJson, requireUser } from "../http";
import { HttpError, RequestContext } from "../types";

interface CategoryRow {
  id: number;
  user_id: number;
  name: string;
  icon: string | null;
  sort_order: number;
  created_at: string;
  updated_at: string;
}

interface LinkRow {
  id: number;
  user_id: number;
  category_id: number;
  name: string;
  url: string;
  description: string | null;
  icon: string | null;
  sort_order: number;
  created_at: string;
  updated_at: string;
}

export async function publicCategories(ctx: RequestContext): Promise<Response> {
  return ok(await listCategories(ctx, await publicUserId(ctx)));
}

export async function publicLinks(ctx: RequestContext): Promise<Response> {
  const userId = await publicUserId(ctx);
  const categoryId = ctx.url.searchParams.get("categoryId");
  return ok(await listLinks(ctx, userId, categoryId ? Number(categoryId) : null));
}

export async function publicNav(ctx: RequestContext): Promise<Response> {
  const userId = await publicUserId(ctx);
  const categories = await listCategories(ctx, userId);
  const links = await listLinks(ctx, userId, null);
  return ok(categories.map((category) => ({
    ...category,
    links: links.filter((link) => link.categoryId === category.id)
  })));
}

export async function listUserCategories(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  return ok(await listCategories(ctx, user.id));
}

export async function getCategory(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const row = await firstRequired<CategoryRow>(
    ctx.env.DB.prepare("SELECT * FROM category WHERE id = ? AND user_id = ?").bind(Number(ctx.params.id), user.id)
  );
  return ok(categoryView(row));
}

export async function createCategory(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const body = await readJson<Record<string, unknown>>(ctx.request);
  const name = stringField(body.name, "name");
  const result = await ctx.env.DB.prepare(
    "INSERT INTO category(user_id, name, icon, sort_order) VALUES(?, ?, ?, ?)"
  ).bind(user.id, name, nullableString(body.icon), intValue(body.sortOrder, 0)).run();
  ctx.env.APP_KV.delete(`public:user:${user.id}`).catch(() => undefined);
  return ok(await getCategoryById(ctx, user.id, Number(result.meta.last_row_id)));
}

export async function updateCategory(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const body = await readJson<Record<string, unknown>>(ctx.request);
  const id = Number(ctx.params.id);
  await assertOwnedCategory(ctx, user.id, id);
  await ctx.env.DB.prepare(
    "UPDATE category SET name = ?, icon = ?, sort_order = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?"
  ).bind(stringField(body.name, "name"), nullableString(body.icon), intValue(body.sortOrder, 0), id, user.id).run();
  ctx.env.APP_KV.delete(`public:user:${user.id}`).catch(() => undefined);
  return ok(await getCategoryById(ctx, user.id, id));
}

export async function deleteCategory(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  await ctx.env.DB.prepare("DELETE FROM category WHERE id = ? AND user_id = ?").bind(Number(ctx.params.id), user.id).run();
  return emptyOk();
}

export async function listUserLinks(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const categoryId = ctx.url.searchParams.get("categoryId");
  return ok(await listLinks(ctx, user.id, categoryId ? Number(categoryId) : null));
}

export async function getLink(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const row = await firstRequired<LinkRow>(
    ctx.env.DB.prepare("SELECT * FROM nav_link WHERE id = ? AND user_id = ?").bind(Number(ctx.params.id), user.id)
  );
  return ok(linkView(row));
}

export async function createLink(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const body = await readJson<Record<string, unknown>>(ctx.request);
  const categoryId = Number(body.categoryId);
  await assertOwnedCategory(ctx, user.id, categoryId);
  const result = await ctx.env.DB.prepare(
    "INSERT INTO nav_link(user_id, category_id, name, url, description, icon, sort_order) VALUES(?, ?, ?, ?, ?, ?, ?)"
  ).bind(
    user.id,
    categoryId,
    stringField(body.name, "name"),
    stringField(body.url, "url"),
    nullableString(body.description),
    nullableString(body.icon),
    intValue(body.sortOrder, 0)
  ).run();
  return ok(await getLinkById(ctx, user.id, Number(result.meta.last_row_id)));
}

export async function updateLink(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const id = Number(ctx.params.id);
  const body = await readJson<Record<string, unknown>>(ctx.request);
  const categoryId = Number(body.categoryId);
  await assertOwnedCategory(ctx, user.id, categoryId);
  await ctx.env.DB.prepare(
    "UPDATE nav_link SET category_id = ?, name = ?, url = ?, description = ?, icon = ?, sort_order = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?"
  ).bind(
    categoryId,
    stringField(body.name, "name"),
    stringField(body.url, "url"),
    nullableString(body.description),
    nullableString(body.icon),
    intValue(body.sortOrder, 0),
    id,
    user.id
  ).run();
  return ok(await getLinkById(ctx, user.id, id));
}

export async function deleteLink(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  await ctx.env.DB.prepare("DELETE FROM nav_link WHERE id = ? AND user_id = ?").bind(Number(ctx.params.id), user.id).run();
  return emptyOk();
}

export async function listConfigs(ctx: RequestContext): Promise<Response> {
  requireUser(ctx);
  const rows = await ctx.env.DB.prepare("SELECT * FROM sys_config ORDER BY config_key").all();
  return ok((rows.results ?? []).map(configView));
}

export async function createConfig(ctx: RequestContext): Promise<Response> {
  requireUser(ctx);
  const body = await readJson<Record<string, unknown>>(ctx.request);
  const result = await ctx.env.DB.prepare(
    "INSERT INTO sys_config(config_key, config_value, description) VALUES(?, ?, ?)"
  ).bind(stringField(body.configKey, "configKey"), stringField(body.configValue, "configValue"), nullableString(body.description)).run();
  return ok(await configById(ctx, Number(result.meta.last_row_id)));
}

export async function updateConfig(ctx: RequestContext): Promise<Response> {
  requireUser(ctx);
  const body = await readJson<Record<string, unknown>>(ctx.request);
  const id = Number(ctx.params.id);
  await ctx.env.DB.prepare(
    "UPDATE sys_config SET config_key = ?, config_value = ?, description = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?"
  ).bind(stringField(body.configKey, "configKey"), stringField(body.configValue, "configValue"), nullableString(body.description), id).run();
  return ok(await configById(ctx, id));
}

export async function deleteConfig(ctx: RequestContext): Promise<Response> {
  requireUser(ctx);
  await ctx.env.DB.prepare("DELETE FROM sys_config WHERE id = ?").bind(Number(ctx.params.id)).run();
  return emptyOk();
}

export async function pagedQuery<T>(
  ctx: RequestContext,
  listSql: string,
  countSql: string,
  params: unknown[],
  map: (row: any) => T,
  defaultSize = 20
): Promise<Response> {
  const page = intParam(ctx.url, "page", 0, 0, 10000);
  const size = intParam(ctx.url, "size", defaultSize, 1, 100);
  const total = await count(ctx.env.DB, countSql, ...params);
  const rows = await ctx.env.DB.prepare(`${listSql} LIMIT ? OFFSET ?`).bind(...params, size, page * size).all();
  return ok(pageOf((rows.results ?? []).map(map), page, size, total));
}

async function publicUserId(ctx: RequestContext): Promise<number> {
  if (ctx.user) {
    return ctx.user.id;
  }
  const cached = await ctx.env.APP_KV.get<number>("public:defaultUserId", "json");
  if (cached) {
    return cached;
  }
  const row = await ctx.env.DB.prepare("SELECT id FROM users WHERE role = 'ADMIN' ORDER BY id LIMIT 1").first<{ id: number }>();
  if (!row) {
    throw new HttpError(404, "No public user data");
  }
  await ctx.env.APP_KV.put("public:defaultUserId", JSON.stringify(row.id), { expirationTtl: 300 });
  return row.id;
}

async function listCategories(ctx: RequestContext, userId: number) {
  const rows = await ctx.env.DB.prepare("SELECT * FROM category WHERE user_id = ? ORDER BY sort_order, id").bind(userId).all<CategoryRow>();
  return (rows.results ?? []).map(categoryView);
}

async function listLinks(ctx: RequestContext, userId: number, categoryId: number | null) {
  const stmt = categoryId == null
    ? ctx.env.DB.prepare("SELECT * FROM nav_link WHERE user_id = ? ORDER BY sort_order, id").bind(userId)
    : ctx.env.DB.prepare("SELECT * FROM nav_link WHERE user_id = ? AND category_id = ? ORDER BY sort_order, id").bind(userId, categoryId);
  const rows = await stmt.all<LinkRow>();
  return (rows.results ?? []).map(linkView);
}

async function getCategoryById(ctx: RequestContext, userId: number, id: number) {
  const row = await firstRequired<CategoryRow>(ctx.env.DB.prepare("SELECT * FROM category WHERE id = ? AND user_id = ?").bind(id, userId));
  return categoryView(row);
}

async function getLinkById(ctx: RequestContext, userId: number, id: number) {
  const row = await firstRequired<LinkRow>(ctx.env.DB.prepare("SELECT * FROM nav_link WHERE id = ? AND user_id = ?").bind(id, userId));
  return linkView(row);
}

async function configById(ctx: RequestContext, id: number) {
  return configView(await firstRequired<any>(ctx.env.DB.prepare("SELECT * FROM sys_config WHERE id = ?").bind(id)));
}

async function assertOwnedCategory(ctx: RequestContext, userId: number, id: number): Promise<void> {
  if (!Number.isFinite(id)) {
    throw new HttpError(400, "categoryId is required");
  }
  await firstRequired(ctx.env.DB.prepare("SELECT id FROM category WHERE id = ? AND user_id = ?").bind(id, userId), "Category not found");
}

function categoryView(row: CategoryRow) {
  return {
    id: row.id,
    name: row.name,
    icon: row.icon,
    sortOrder: row.sort_order,
    createdAt: row.created_at,
    updatedAt: row.updated_at
  };
}

function linkView(row: LinkRow) {
  return {
    id: row.id,
    categoryId: row.category_id,
    name: row.name,
    url: row.url,
    description: row.description,
    icon: row.icon,
    sortOrder: row.sort_order,
    createdAt: row.created_at,
    updatedAt: row.updated_at
  };
}

function configView(row: any) {
  return {
    id: row.id,
    configKey: row.config_key,
    configValue: row.config_value,
    description: row.description,
    createdAt: row.created_at,
    updatedAt: row.updated_at
  };
}

function stringField(value: unknown, name: string): string {
  if (typeof value !== "string" || !value.trim()) {
    throw new HttpError(400, `${name} is required`);
  }
  return value.trim();
}

function nullableString(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function intValue(value: unknown, fallback: number): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? Math.trunc(parsed) : fallback;
}
