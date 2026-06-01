import { randomToken } from "../crypto";
import { firstRequired } from "../db";
import { emptyOk, ok, readJson, requireUser } from "../http";
import { HttpError, RequestContext } from "../types";
import { pagedQuery } from "./nav";

export async function spaces(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const rows = await ctx.env.DB.prepare("SELECT * FROM kb_space WHERE user_id = ? ORDER BY sort_order, id").bind(user.id).all();
  return ok((rows.results ?? []).map(spaceView));
}

export async function createSpace(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const body = await readJson<any>(ctx.request);
  const result = await ctx.env.DB.prepare("INSERT INTO kb_space(user_id, name, description, sort_order) VALUES(?, ?, ?, ?)")
    .bind(user.id, required(body.name, "name"), body.description || null, Number(body.sortOrder || 0))
    .run();
  return ok(spaceView(await rowByOwner(ctx, "kb_space", user.id, Number(result.meta.last_row_id))));
}

export async function getSpace(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  return ok(spaceView(await rowByOwner(ctx, "kb_space", user.id, Number(ctx.params.id))));
}

export async function updateSpace(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const body = await readJson<any>(ctx.request);
  await ctx.env.DB.prepare("UPDATE kb_space SET name = ?, description = ?, sort_order = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?")
    .bind(required(body.name, "name"), body.description || null, Number(body.sortOrder || 0), Number(ctx.params.id), user.id)
    .run();
  return getSpace(ctx);
}

export async function deleteSpace(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  await ctx.env.DB.prepare("DELETE FROM kb_space WHERE id = ? AND user_id = ?").bind(Number(ctx.params.id), user.id).run();
  return emptyOk();
}

export async function tree(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const rows = await ctx.env.DB.prepare("SELECT id, parent_id, title, sort_order FROM kb_doc WHERE user_id = ? AND space_id = ? ORDER BY sort_order, id")
    .bind(user.id, Number(ctx.params.id))
    .all<any>();
  const nodes = (rows.results ?? []).map((row) => ({ id: row.id, parentId: row.parent_id, title: row.title, sortOrder: row.sort_order, children: [] as any[] }));
  const byId = new Map(nodes.map((node) => [node.id, node]));
  const roots: any[] = [];
  for (const node of nodes) {
    if (node.parentId && byId.has(node.parentId)) byId.get(node.parentId)!.children.push(node);
    else roots.push(node);
  }
  return ok(roots);
}

export async function tags(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const rows = await ctx.env.DB.prepare("SELECT * FROM kb_tag WHERE user_id = ? ORDER BY name").bind(user.id).all();
  return ok((rows.results ?? []).map(tagView));
}

export async function createTag(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const body = await readJson<any>(ctx.request);
  const result = await ctx.env.DB.prepare("INSERT INTO kb_tag(user_id, name, color) VALUES(?, ?, ?)")
    .bind(user.id, required(body.name, "name"), body.color || null)
    .run();
  return ok(tagView(await rowByOwner(ctx, "kb_tag", user.id, Number(result.meta.last_row_id))));
}

export async function updateTag(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const body = await readJson<any>(ctx.request);
  await ctx.env.DB.prepare("UPDATE kb_tag SET name = ?, color = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?")
    .bind(required(body.name, "name"), body.color || null, Number(ctx.params.id), user.id)
    .run();
  return ok(tagView(await rowByOwner(ctx, "kb_tag", user.id, Number(ctx.params.id))));
}

export async function deleteTag(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  await ctx.env.DB.prepare("DELETE FROM kb_tag WHERE id = ? AND user_id = ?").bind(Number(ctx.params.id), user.id).run();
  return emptyOk();
}

export async function docs(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const clauses = ["user_id = ?"];
  const params: unknown[] = [user.id];
  for (const [query, column] of [["spaceId", "space_id"], ["parentId", "parent_id"]] as const) {
    const value = ctx.url.searchParams.get(query);
    if (value) {
      clauses.push(`${column} = ?`);
      params.push(Number(value));
    }
  }
  const keyword = ctx.url.searchParams.get("keyword");
  if (keyword) {
    clauses.push("title LIKE ?");
    params.push(`%${keyword}%`);
  }
  const where = clauses.join(" AND ");
  return pagedQuery(ctx, `SELECT * FROM kb_doc WHERE ${where} ORDER BY sort_order, id`, `SELECT COUNT(*) AS total FROM kb_doc WHERE ${where}`, params, docSummaryView);
}

export async function createDoc(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const body = await readJson<any>(ctx.request);
  const result = await ctx.env.DB.prepare(
    "INSERT INTO kb_doc(user_id, space_id, parent_id, title, content, status, sort_order) VALUES(?, ?, ?, ?, ?, ?, ?)"
  ).bind(user.id, Number(body.spaceId), body.parentId || null, required(body.title, "title"), body.content || "", body.status || "ACTIVE", Number(body.sortOrder || 0)).run();
  return ok(await docViewById(ctx, user.id, Number(result.meta.last_row_id)));
}

export async function getDoc(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  return ok(await docViewById(ctx, user.id, Number(ctx.params.id)));
}

export async function updateDoc(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const id = Number(ctx.params.id);
  const current = await rowByOwner(ctx, "kb_doc", user.id, id);
  const body = await readJson<any>(ctx.request);
  await ctx.env.DB.prepare("INSERT INTO kb_doc_version(doc_id, title, content) VALUES(?, ?, ?)").bind(id, current.title, current.content).run();
  await ctx.env.DB.prepare(
    "UPDATE kb_doc SET space_id = ?, parent_id = ?, title = ?, content = ?, status = ?, sort_order = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?"
  ).bind(
    Number(body.spaceId || current.space_id),
    body.parentId ?? current.parent_id,
    required(body.title ?? current.title, "title"),
    body.content ?? current.content,
    body.status || current.status,
    Number(body.sortOrder ?? current.sort_order),
    id,
    user.id
  ).run();
  return ok(await docViewById(ctx, user.id, id));
}

export async function deleteDoc(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  await ctx.env.DB.prepare("DELETE FROM kb_doc WHERE id = ? AND user_id = ?").bind(Number(ctx.params.id), user.id).run();
  return emptyOk();
}

export async function moveDoc(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const body = await readJson<any>(ctx.request);
  await ctx.env.DB.prepare("UPDATE kb_doc SET space_id = ?, parent_id = ?, sort_order = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?")
    .bind(Number(body.spaceId), body.parentId || null, Number(body.sortOrder || 0), Number(ctx.params.id), user.id)
    .run();
  return ok(await docViewById(ctx, user.id, Number(ctx.params.id)));
}

export async function setDocTags(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const id = Number(ctx.params.id);
  await rowByOwner(ctx, "kb_doc", user.id, id);
  const body = await readJson<{ tagIds?: number[] }>(ctx.request);
  await ctx.env.DB.prepare("DELETE FROM kb_doc_tag WHERE doc_id = ?").bind(id).run();
  for (const tagId of body.tagIds ?? []) {
    await ctx.env.DB.prepare("INSERT OR IGNORE INTO kb_doc_tag(doc_id, tag_id) VALUES(?, ?)").bind(id, tagId).run();
  }
  return ok(await docViewById(ctx, user.id, id));
}

export async function versions(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  await rowByOwner(ctx, "kb_doc", user.id, Number(ctx.params.id));
  return pagedQuery(
    ctx,
    "SELECT * FROM kb_doc_version WHERE doc_id = ? ORDER BY id DESC",
    "SELECT COUNT(*) AS total FROM kb_doc_version WHERE doc_id = ?",
    [Number(ctx.params.id)],
    versionView
  );
}

export async function versionDetail(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  await rowByOwner(ctx, "kb_doc", user.id, Number(ctx.params.id));
  return ok(versionView(await firstRequired<any>(ctx.env.DB.prepare("SELECT * FROM kb_doc_version WHERE id = ? AND doc_id = ?").bind(Number(ctx.params.versionId), Number(ctx.params.id)))));
}

export async function restoreVersion(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const version = await firstRequired<any>(ctx.env.DB.prepare("SELECT * FROM kb_doc_version WHERE id = ? AND doc_id = ?").bind(Number(ctx.params.versionId), Number(ctx.params.id)));
  await ctx.env.DB.prepare("UPDATE kb_doc SET title = ?, content = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?")
    .bind(version.title, version.content, Number(ctx.params.id), user.id)
    .run();
  return ok(await docViewById(ctx, user.id, Number(ctx.params.id)));
}

export async function getShare(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const row = await ctx.env.DB.prepare("SELECT * FROM kb_doc_share WHERE doc_id = ? AND user_id = ?").bind(Number(ctx.params.id), user.id).first();
  return ok(row ? shareView(row) : null);
}

export async function enableShare(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  await rowByOwner(ctx, "kb_doc", user.id, Number(ctx.params.id));
  const body = await readJson<any>(ctx.request);
  const token = randomToken(18);
  await ctx.env.DB.prepare(
    "INSERT INTO kb_doc_share(doc_id, user_id, token, expires_at) VALUES(?, ?, ?, ?) ON CONFLICT(doc_id) DO UPDATE SET expires_at = excluded.expires_at, updated_at = CURRENT_TIMESTAMP"
  ).bind(Number(ctx.params.id), user.id, token, body.expiresAt || null).run();
  const row = await firstRequired<any>(ctx.env.DB.prepare("SELECT * FROM kb_doc_share WHERE doc_id = ?").bind(Number(ctx.params.id)));
  return ok(shareView(row));
}

export async function disableShare(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  await ctx.env.DB.prepare("DELETE FROM kb_doc_share WHERE doc_id = ? AND user_id = ?").bind(Number(ctx.params.id), user.id).run();
  return emptyOk();
}

export async function publicShare(ctx: RequestContext): Promise<Response> {
  const share = await firstRequired<any>(
    ctx.env.DB.prepare("SELECT * FROM kb_doc_share WHERE token = ? AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)").bind(ctx.params.token),
    "Share not found"
  );
  const doc = await docViewById(ctx, share.user_id, share.doc_id);
  const docs = await ctx.env.DB.prepare(
    "SELECT d.* FROM kb_doc d JOIN kb_doc_share s ON s.doc_id = d.id WHERE d.user_id = ? AND (s.expires_at IS NULL OR s.expires_at > CURRENT_TIMESTAMP) ORDER BY d.sort_order, d.id"
  ).bind(share.user_id).all();
  await ctx.env.DB.prepare("UPDATE kb_doc_share SET view_count = view_count + 1 WHERE id = ?").bind(share.id).run();
  return ok({ doc, docs: (docs.results ?? []).map(docSummaryView), share: shareView(share) });
}

async function docViewById(ctx: RequestContext, userId: number, id: number) {
  const row = await rowByOwner(ctx, "kb_doc", userId, id);
  const tags = await ctx.env.DB.prepare("SELECT t.* FROM kb_tag t JOIN kb_doc_tag dt ON dt.tag_id = t.id WHERE dt.doc_id = ?").bind(id).all();
  return { ...docSummaryView(row), content: row.content, tags: (tags.results ?? []).map(tagView) };
}

async function rowByOwner(ctx: RequestContext, table: string, userId: number, id: number): Promise<any> {
  if (!["kb_space", "kb_tag", "kb_doc"].includes(table)) throw new HttpError(500, "Invalid table");
  return firstRequired<any>(ctx.env.DB.prepare(`SELECT * FROM ${table} WHERE id = ? AND user_id = ?`).bind(id, userId), "Not found");
}

function spaceView(row: any) {
  return { id: row.id, name: row.name, description: row.description, sortOrder: row.sort_order, createdAt: row.created_at, updatedAt: row.updated_at };
}

function tagView(row: any) {
  return { id: row.id, name: row.name, color: row.color, createdAt: row.created_at, updatedAt: row.updated_at };
}

function docSummaryView(row: any) {
  return {
    id: row.id,
    spaceId: row.space_id,
    parentId: row.parent_id,
    title: row.title,
    status: row.status,
    sortOrder: row.sort_order,
    createdAt: row.created_at,
    updatedAt: row.updated_at
  };
}

function versionView(row: any) {
  return { id: row.id, docId: row.doc_id, title: row.title, content: row.content, createdAt: row.created_at };
}

function shareView(row: any) {
  return { id: row.id, docId: row.doc_id, token: row.token, expiresAt: row.expires_at, viewCount: row.view_count, createdAt: row.created_at };
}

function required(value: unknown, name: string): string {
  if (typeof value !== "string" || !value.trim()) {
    throw new HttpError(400, `${name} is required`);
  }
  return value.trim();
}
