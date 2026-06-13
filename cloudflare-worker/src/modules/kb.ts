import { randomToken } from "../crypto";
import { firstRequired } from "../db";
import { emptyOk, ok, readJson, requireUser } from "../http";
import { HttpError, RequestContext } from "../types";
import { pagedQuery } from "./nav";

const KB_ASSET_MAX_BYTES = 10 * 1024 * 1024;
const KB_ASSET_EXTENSIONS: Record<string, string> = {
  "image/avif": "avif",
  "image/gif": "gif",
  "image/jpeg": "jpg",
  "image/png": "png",
  "image/svg+xml": "svg",
  "image/webp": "webp"
};

export async function spaces(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const rows = await ctx.env.DB.prepare("SELECT * FROM kb_space WHERE user_id = ? ORDER BY sort_order, id").bind(user.id).all();
  return ok((rows.results ?? []).map(spaceView));
}

export async function createSpace(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const body = await readJson<any>(ctx.request);
  const result = await ctx.env.DB.prepare("INSERT INTO kb_space(user_id, name, description, sort_order, doc_count) VALUES(?, ?, ?, ?, 0)")
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
  const rows = await ctx.env.DB.prepare("SELECT id, parent_id, title, status, sort_order FROM kb_doc WHERE user_id = ? AND space_id = ? ORDER BY sort_order, id")
    .bind(user.id, Number(ctx.params.id))
    .all<any>();
  const nodes = (rows.results ?? []).map((row) => ({
    id: row.id,
    parentId: row.parent_id,
    title: row.title,
    status: normalizeStatus(row.status),
    sortOrder: row.sort_order,
    children: [] as any[]
  }));
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
  const clauses = ["d.user_id = ?"];
  const params: unknown[] = [user.id];
  for (const [query, column] of [["spaceId", "space_id"], ["parentId", "parent_id"]] as const) {
    const value = ctx.url.searchParams.get(query);
    if (value) {
      clauses.push(`d.${column} = ?`);
      params.push(Number(value));
    }
  }
  const tagId = ctx.url.searchParams.get("tagId");
  if (tagId) {
    clauses.push("EXISTS (SELECT 1 FROM kb_doc_tag dt WHERE dt.doc_id = d.id AND dt.tag_id = ?)");
    params.push(Number(tagId));
  }
  const keyword = ctx.url.searchParams.get("keyword");
  if (keyword) {
    clauses.push("(d.title LIKE ? OR d.summary LIKE ?)");
    params.push(`%${keyword}%`, `%${keyword}%`);
  }
  const where = clauses.join(" AND ");
  return pagedQuery(
    ctx,
    `SELECT d.* FROM kb_doc d WHERE ${where} ORDER BY d.sort_order, d.id`,
    `SELECT COUNT(*) AS total FROM kb_doc d WHERE ${where}`,
    params,
    docSummaryView
  );
}

export async function createDoc(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const body = await readJson<any>(ctx.request);
  const content = contentFields(body);
  const result = await ctx.env.DB.prepare(
    "INSERT INTO kb_doc(user_id, space_id, parent_id, title, summary, content, content_json, content_html, status, sort_order, version_no) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)"
  ).bind(
    user.id,
    Number(body.spaceId),
    body.parentId || null,
    required(body.title, "title"),
    nullableString(body.summary),
    content.legacy,
    content.json,
    content.html,
    normalizeStatus(body.status || "draft"),
    Number(body.sortOrder || 0)
  ).run();
  await refreshSpaceDocCount(ctx, Number(body.spaceId));
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
  const content = contentFields(body, current);
  await ctx.env.DB.prepare(
    "INSERT INTO kb_doc_version(doc_id, version_no, title, summary, content, content_json, content_html, editor_user_id, change_note) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)"
  ).bind(
    id,
    Number(current.version_no || 1),
    current.title,
    current.summary || null,
    current.content || "",
    current.content_json || null,
    current.content_html || current.content || null,
    user.id,
    nullableString(body.changeNote)
  ).run();
  await ctx.env.DB.prepare(
    "UPDATE kb_doc SET space_id = ?, parent_id = ?, title = ?, summary = ?, content = ?, content_json = ?, content_html = ?, status = ?, sort_order = ?, version_no = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?"
  ).bind(
    Number(body.spaceId || current.space_id),
    body.parentId ?? current.parent_id,
    required(body.title ?? current.title, "title"),
    body.summary === undefined ? current.summary : nullableString(body.summary),
    content.legacy,
    content.json,
    content.html,
    normalizeStatus(body.status || current.status),
    Number(body.sortOrder ?? current.sort_order),
    Number(current.version_no || 1) + 1,
    id,
    user.id
  ).run();
  if (Number(body.spaceId || current.space_id) !== Number(current.space_id)) {
    await Promise.all([
      refreshSpaceDocCount(ctx, Number(current.space_id)),
      refreshSpaceDocCount(ctx, Number(body.spaceId || current.space_id))
    ]);
  }
  return ok(await docViewById(ctx, user.id, id));
}

export async function deleteDoc(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const current = await rowByOwner(ctx, "kb_doc", user.id, Number(ctx.params.id));
  await ctx.env.DB.prepare("DELETE FROM kb_doc WHERE id = ? AND user_id = ?").bind(Number(ctx.params.id), user.id).run();
  await refreshSpaceDocCount(ctx, Number(current.space_id));
  return emptyOk();
}

export async function moveDoc(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const body = await readJson<any>(ctx.request);
  const current = await rowByOwner(ctx, "kb_doc", user.id, Number(ctx.params.id));
  await ctx.env.DB.prepare("UPDATE kb_doc SET space_id = ?, parent_id = ?, sort_order = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?")
    .bind(Number(body.spaceId), body.parentId || null, Number(body.sortOrder || 0), Number(ctx.params.id), user.id)
    .run();
  if (Number(body.spaceId) !== Number(current.space_id)) {
    await Promise.all([
      refreshSpaceDocCount(ctx, Number(current.space_id)),
      refreshSpaceDocCount(ctx, Number(body.spaceId))
    ]);
  }
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
  await ctx.env.DB.prepare("UPDATE kb_doc SET title = ?, summary = ?, content = ?, content_json = ?, content_html = ?, version_no = version_no + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?")
    .bind(
      version.title,
      version.summary || null,
      version.content || "",
      version.content_json || null,
      version.content_html || version.content || null,
      Number(ctx.params.id),
      user.id
    )
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
    "SELECT d.*, s.token FROM kb_doc d JOIN kb_doc_share s ON s.doc_id = d.id WHERE d.user_id = ? AND (s.expires_at IS NULL OR s.expires_at > CURRENT_TIMESTAMP) ORDER BY d.sort_order, d.id"
  ).bind(share.user_id).all();
  await ctx.env.DB.prepare("UPDATE kb_doc_share SET view_count = view_count + 1 WHERE id = ?").bind(share.id).run();
  return ok({
    ...doc,
    token: share.token,
    documents: (docs.results ?? []).map(publicDocItemView)
  });
}

export async function uploadAsset(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  if (!ctx.request.headers.get("content-type")?.includes("multipart/form-data")) {
    throw new HttpError(400, "multipart/form-data is required");
  }

  const form = await ctx.request.formData();
  const file = form.get("file");
  if (!isFileLike(file)) {
    throw new HttpError(400, "file is required");
  }

  const contentType = normalizeImageContentType(file.type);
  const extension = KB_ASSET_EXTENSIONS[contentType];
  if (!extension) {
    throw new HttpError(400, "Only image files are allowed");
  }

  const bytes = new Uint8Array(await file.arrayBuffer());
  if (!bytes.byteLength) {
    throw new HttpError(400, "file is empty");
  }
  if (bytes.byteLength > KB_ASSET_MAX_BYTES) {
    throw new HttpError(413, "file is too large");
  }

  const docIdValue = form.get("docId");
  const docId = typeof docIdValue === "string" && docIdValue.trim() ? Number(docIdValue) : null;
  if (docId !== null) {
    if (!Number.isFinite(docId) || docId <= 0) {
      throw new HttpError(400, "docId is invalid");
    }
    await rowByOwner(ctx, "kb_doc", user.id, docId);
  }

  const filename = `${user.id}-${docId ?? "unassigned"}-${randomToken(18)}.${extension}`;
  const key = `kb-assets/${filename}`;
  const bucket = requireR2Bucket(ctx);
  await bucket.put(key, bytes, {
    httpMetadata: {
      contentType,
      cacheControl: "public, max-age=31536000, immutable"
    },
    customMetadata: {
      userId: String(user.id),
      ...(docId ? { docId: String(docId) } : {}),
      originalName: sanitizeMetadata(file.name || "image")
    }
  });

  return ok({
    url: publicKbAssetUrl(ctx, filename),
    key,
    contentType,
    size: bytes.byteLength
  });
}

export async function assetFile(ctx: RequestContext): Promise<Response> {
  const filename = ctx.params.filename;
  if (!/^[A-Za-z0-9._-]+$/.test(filename)) {
    throw new HttpError(400, "Invalid filename");
  }

  const object = await requireR2Bucket(ctx).get(`kb-assets/${filename}`);
  if (!object) {
    throw new HttpError(404, "File not found");
  }

  return new Response(object.body, {
    headers: {
      "content-type": object.httpMetadata?.contentType || "application/octet-stream",
      "cache-control": object.httpMetadata?.cacheControl || "public, max-age=31536000, immutable"
    }
  });
}

async function docViewById(ctx: RequestContext, userId: number, id: number) {
  const row = await rowByOwner(ctx, "kb_doc", userId, id);
  const tags = await ctx.env.DB.prepare("SELECT t.* FROM kb_tag t JOIN kb_doc_tag dt ON dt.tag_id = t.id WHERE dt.doc_id = ?").bind(id).all();
  return {
    ...docSummaryView(row),
    content: row.content,
    contentJson: row.content_json || null,
    contentHtml: row.content_html || row.content || null,
    tags: (tags.results ?? []).map(tagView)
  };
}

async function rowByOwner(ctx: RequestContext, table: string, userId: number, id: number): Promise<any> {
  if (!["kb_space", "kb_tag", "kb_doc"].includes(table)) throw new HttpError(500, "Invalid table");
  return firstRequired<any>(ctx.env.DB.prepare(`SELECT * FROM ${table} WHERE id = ? AND user_id = ?`).bind(id, userId), "Not found");
}

function spaceView(row: any) {
  return {
    id: row.id,
    name: row.name,
    description: row.description,
    sortOrder: row.sort_order,
    docCount: Number(row.doc_count || 0),
    createdAt: row.created_at,
    updatedAt: row.updated_at
  };
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
    summary: row.summary || null,
    status: normalizeStatus(row.status),
    sortOrder: row.sort_order,
    versionNo: Number(row.version_no || 1),
    createdAt: row.created_at,
    updatedAt: row.updated_at
  };
}

function versionView(row: any) {
  return {
    id: row.id,
    docId: row.doc_id,
    versionNo: Number(row.version_no || row.id || 1),
    title: row.title,
    summary: row.summary || null,
    content: row.content,
    contentJson: row.content_json || null,
    contentHtml: row.content_html || row.content || null,
    editorUserId: Number(row.editor_user_id || 0),
    changeNote: row.change_note || null,
    createdAt: row.created_at
  };
}

function shareView(row: any) {
  return {
    id: row.id,
    docId: row.doc_id,
    token: row.token,
    enabled: true,
    expiresAt: row.expires_at,
    viewCount: row.view_count,
    createdAt: row.created_at,
    updatedAt: row.updated_at
  };
}

function publicDocItemView(row: any) {
  return {
    id: row.id,
    token: row.token,
    parentId: row.parent_id,
    title: row.title,
    summary: row.summary || null,
    sortOrder: row.sort_order,
    updatedAt: row.updated_at
  };
}

function nullableString(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function contentFields(body: any, fallback?: any) {
  const hasJson = Object.prototype.hasOwnProperty.call(body, "contentJson");
  const hasHtml = Object.prototype.hasOwnProperty.call(body, "contentHtml");
  const jsonValue = hasJson ? String(body.contentJson ?? "") : String(fallback?.content_json ?? "");
  const htmlValue = hasHtml
    ? String(body.contentHtml ?? "")
    : String(fallback?.content_html ?? fallback?.content ?? "");
  const legacy = Object.prototype.hasOwnProperty.call(body, "content")
    ? String(body.content ?? "")
    : hasHtml
      ? htmlValue
      : hasJson
        ? jsonValue
        : htmlValue || jsonValue || String(fallback?.content ?? "");
  return {
    json: jsonValue || null,
    html: htmlValue || null,
    legacy
  };
}

function normalizeStatus(value: unknown): "draft" | "published" {
  return value === "published" || value === "ACTIVE" ? "published" : "draft";
}

async function refreshSpaceDocCount(ctx: RequestContext, spaceId: number): Promise<void> {
  if (!Number.isFinite(spaceId) || spaceId <= 0) return;
  await ctx.env.DB.prepare(
    "UPDATE kb_space SET doc_count = (SELECT COUNT(*) FROM kb_doc WHERE space_id = ?), updated_at = CURRENT_TIMESTAMP WHERE id = ?"
  ).bind(spaceId, spaceId).run();
}

function required(value: unknown, name: string): string {
  if (typeof value !== "string" || !value.trim()) {
    throw new HttpError(400, `${name} is required`);
  }
  return value.trim();
}

function isFileLike(value: unknown): value is File {
  return typeof value === "object"
    && value !== null
    && typeof (value as File).arrayBuffer === "function"
    && typeof (value as File).name === "string";
}

function normalizeImageContentType(value: string): string {
  const contentType = value.toLowerCase().split(";")[0].trim();
  return contentType === "image/jpg" ? "image/jpeg" : contentType;
}

function publicKbAssetUrl(ctx: RequestContext, filename: string): string {
  return ctx.env.PUBLIC_R2_BASE_URL
    ? `${ctx.env.PUBLIC_R2_BASE_URL.replace(/\/$/, "")}/kb-assets/${filename}`
    : `/api/v1/kb/assets/${filename}`;
}

function requireR2Bucket(ctx: RequestContext): R2Bucket {
  if (!ctx.env.R2_BUCKET) {
    throw new HttpError(503, "R2_BUCKET is not configured");
  }
  return ctx.env.R2_BUCKET;
}

function sanitizeMetadata(value: string): string {
  return value.replace(/[^\x20-\x7E]/g, "").slice(0, 200);
}
