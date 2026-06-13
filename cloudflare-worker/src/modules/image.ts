import { randomToken } from "../crypto";
import { chargeCredits, imageCreditCost, refundCredits } from "../credits";
import { firstRequired, pageOf, toBool } from "../db";
import { emptyOk, ok, readJson, requireUser } from "../http";
import { HttpError, ImageGenerateQueueMessage, RequestContext } from "../types";
import { intParam } from "../http";

const IMAGE_UPSTREAM_TIMEOUT_MS = 170_000;
const REMOTE_IMAGE_TIMEOUT_MS = 30_000;
const PUBLIC_SHARED_IMAGES_CACHE_KEY = "public:image:shared:0:20";
const PUBLIC_SHARED_IMAGES_CACHE_TTL_SECONDS = 120;
const PUBLIC_SHARED_IMAGES_CACHE_CONTROL = "public, max-age=60, stale-while-revalidate=300";

interface EditImageInput {
  prompt: string;
  image: File;
  mask: File | null;
  model: string | null;
  size: string | null;
  quality: string | null;
  n: number;
}

type ImageStageName = "upstream" | "download" | "r2Upload" | "telegramUpload" | "telegramResponse";

interface ImageStageTiming {
  startedAt?: string;
  endedAt?: string;
  durationMs?: number;
  status?: "running" | "ok" | "failed";
  error?: string;
  meta?: Record<string, unknown>;
  runs?: ImageStageRunTiming[];
}

interface ImageStageRunTiming {
  startedAt?: string;
  endedAt?: string;
  durationMs?: number;
  status?: "running" | "ok" | "failed";
  error?: string;
  meta?: Record<string, unknown>;
}

type ImageStageTimings = Partial<Record<ImageStageName, ImageStageTiming>>;

interface ImagePipelineTracker {
  taskId: number;
  timings: ImageStageTimings;
}

export async function generateImage(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const body = await readJson<Record<string, unknown>>(ctx.request);
  const prompt = typeof body.prompt === "string" ? body.prompt.trim() : "";
  if (!prompt) {
    throw new HttpError(400, "prompt is required");
  }
  const model = String(body.model || await getConfig(ctx, "image.api.model", "gpt-image-1"));
  const size = typeof body.size === "string" ? body.size : null;
  const quality = typeof body.quality === "string" && body.quality.trim() ? body.quality.trim() : null;
  const n = Math.min(10, Math.max(1, Number(body.n || 1)));
  const creditCost = await calculateCreditCost(ctx, n);
  await chargeCredits(ctx.env, user.id, creditCost);
  const result = await ctx.env.DB.prepare(
    "INSERT INTO image_generation_task(user_id, type, status, prompt, model, size, n, credit_cost) VALUES(?, 'generate', 'PENDING', ?, ?, ?, ?, ?)"
  ).bind(user.id, prompt, model, size, n, creditCost).run();
  const taskId = Number(result.meta.last_row_id);
  await enqueueImageTask(ctx, { type: "generate", userId: user.id, taskId, prompt, model, size, quality, n });
  return ok(await taskById(ctx, user.id, taskId));
}

async function enqueueImageTask(ctx: RequestContext, message: ImageGenerateQueueMessage): Promise<void> {
  await ctx.env.IMAGE_QUEUE.send(message);
}

export async function consumeImageQueue(env: RequestContext["env"], message: ImageGenerateQueueMessage): Promise<void> {
  if (message.type !== "generate") {
    return;
  }
  const ctx = {
    request: new Request("https://worker.internal/queue"),
    env,
    url: new URL("https://worker.internal/queue"),
    params: {},
    user: null
  };
  await runImageTask(ctx, message.userId, message.taskId, message.prompt, message.model, message.size, message.quality ?? null, message.n);
}

async function runImageTask(
  ctx: RequestContext,
  userId: number,
  taskId: number,
  prompt: string,
  model: string,
  size: string | null,
  quality: string | null,
  n: number
): Promise<void> {
  const tracker = createPipelineTracker(taskId);
  try {
    await ctx.env.DB.prepare(
      "UPDATE image_generation_task SET status = 'PROCESSING', updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status IN ('PENDING', 'PROCESSING')"
    ).bind(taskId).run();
    await saveTimings(ctx, tracker);
    const imageResult = await callImageUpstream(ctx, prompt, model, size, quality, n, tracker);
    imageResult.usage = { ...(imageResult.usage || {}), timings: tracker.timings };
    for (const image of imageResult.data) {
      const imageData = image.b64Json ? `data:image/png;base64,${image.b64Json}` : null;
      await ctx.env.DB.prepare(
        "INSERT INTO generated_image(user_id, prompt, image_url, image_data, model, size) VALUES(?, ?, ?, ?, ?, ?)"
      ).bind(userId, prompt, image.url || imageData || "", imageData, model, size).run();
    }
    await ctx.env.DB.prepare("UPDATE image_generation_task SET status = 'COMPLETED', result_json = ?, timings_json = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")
      .bind(JSON.stringify(imageResult), JSON.stringify(tracker.timings), taskId)
      .run();
  } catch (error) {
    await ctx.env.DB.prepare("UPDATE image_generation_task SET status = 'FAILED', error_message = ?, timings_json = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")
      .bind(error instanceof Error ? error.message : "Image generation failed", JSON.stringify(tracker.timings), taskId)
      .run();
    await refundCredits(ctx.env, userId, taskId);
  }
}

export async function editImage(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const input = await readEditImageInput(ctx.request);
  const model = input.model || await getConfig(ctx, "image.api.model", "gpt-image-1");
  const creditCost = await calculateCreditCost(ctx, input.n);
  await chargeCredits(ctx.env, user.id, creditCost);
  const result = await ctx.env.DB.prepare(
    "INSERT INTO image_generation_task(user_id, type, status, prompt, model, size, n, credit_cost) VALUES(?, 'edit', 'PROCESSING', ?, ?, ?, ?, ?)"
  ).bind(user.id, input.prompt, model, input.size, input.n, creditCost).run();
  const taskId = Number(result.meta.last_row_id);
  await runEditImageTask(ctx, user.id, taskId, input, model);
  return ok(await taskById(ctx, user.id, taskId));
}

export async function imageTask(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  return ok(await taskById(ctx, user.id, Number(ctx.params.taskId)));
}

export async function imageHistory(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  await expireStaleProcessingTasks(ctx, user.id);
  const page = intParam(ctx.url, "page", 0, 0, 10000);
  const size = intParam(ctx.url, "size", 20, 1, 100);
  const countRow = await ctx.env.DB.prepare(
    `SELECT
      (SELECT COUNT(*) FROM generated_image WHERE user_id = ?) +
      (SELECT COUNT(*) FROM image_generation_task WHERE user_id = ? AND status IN ('PENDING', 'PROCESSING', 'FAILED')) AS total`
  ).bind(user.id, user.id).first<{ total: number }>();
  const total = Number(countRow?.total ?? 0);
  const rows = await ctx.env.DB.prepare(
    `SELECT * FROM (
      SELECT
        id,
        NULL AS task_id,
        'image' AS item_type,
        'COMPLETED' AS status,
        user_id,
        prompt,
        image_url,
        image_data,
        model,
        size,
        is_shared,
        NULL AS error_message,
        NULL AS timings_json,
        created_at,
        created_at AS sort_at
      FROM generated_image
      WHERE user_id = ?
      UNION ALL
      SELECT
        -id AS id,
        id AS task_id,
        type AS item_type,
        status,
        user_id,
        prompt,
        '' AS image_url,
        NULL AS image_data,
        model,
        size,
        0 AS is_shared,
        error_message,
        timings_json,
        created_at,
        updated_at AS sort_at
      FROM image_generation_task
      WHERE user_id = ? AND status IN ('PENDING', 'PROCESSING', 'FAILED')
    ) ORDER BY sort_at DESC, id DESC LIMIT ? OFFSET ?`
  ).bind(user.id, user.id, size, page * size).all();
  return ok(pageOf((rows.results ?? []).map(imageView), page, size, total));
}

export async function toggleImageShare(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const shared = ctx.url.searchParams.get("shared") === "true";
  await ctx.env.DB.prepare("UPDATE generated_image SET is_shared = ? WHERE id = ? AND user_id = ?")
    .bind(shared ? 1 : 0, Number(ctx.params.id), user.id)
    .run();
  invalidateSharedImagesCache(ctx);
  const row = await firstRequired<any>(
    ctx.env.DB.prepare("SELECT * FROM generated_image WHERE id = ? AND user_id = ?").bind(Number(ctx.params.id), user.id)
  );
  return ok(imageView(row));
}

export async function deleteImageHistory(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const id = Number(ctx.params.id);
  if (id < 0) {
    await ctx.env.DB.prepare("DELETE FROM image_generation_task WHERE id = ? AND user_id = ?")
      .bind(Math.abs(id), user.id)
      .run();
  } else {
    await ctx.env.DB.prepare("DELETE FROM generated_image WHERE id = ? AND user_id = ?")
      .bind(id, user.id)
      .run();
  }
  invalidateSharedImagesCache(ctx);
  return emptyOk();
}

export async function retryImageTask(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const id = Math.abs(Number(ctx.params.id));
  const row = await firstRequired<any>(
    ctx.env.DB.prepare("SELECT * FROM image_generation_task WHERE id = ? AND user_id = ? AND status = 'FAILED'")
      .bind(id, user.id)
  );
  if (row.type === "edit") {
    await failRetriedEditTask(ctx, id);
  } else {
    const creditCost = await calculateCreditCost(ctx, Number(row.n || 1));
    await chargeCredits(ctx.env, user.id, creditCost);
    await ctx.env.DB.prepare(
      "UPDATE image_generation_task SET status = 'PROCESSING', error_message = NULL, result_json = NULL, timings_json = NULL, credit_cost = ?, credit_refunded = 0, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?"
    ).bind(creditCost, id, user.id).run();
    await enqueueImageTask(ctx, {
      type: "generate",
      userId: user.id,
      taskId: id,
      prompt: row.prompt,
      model: row.model || await getConfig(ctx, "image.api.model", "gpt-image-1"),
      size: row.size,
      quality: null,
      n: Number(row.n || 1)
    });
  }
  return ok(await taskById(ctx, user.id, id));
}

async function failRetriedEditTask(ctx: RequestContext, taskId: number): Promise<void> {
  await ctx.env.DB.prepare(
    "UPDATE image_generation_task SET status = 'FAILED', error_message = 'Edit retry requires re-uploading the source image', updated_at = CURRENT_TIMESTAMP WHERE id = ?"
  ).bind(taskId).run();
}

export async function publicSharedImages(ctx: RequestContext): Promise<Response> {
  const page = intParam(ctx.url, "page", 0, 0, 10000);
  const size = intParam(ctx.url, "size", 20, 1, 100);
  const headers = { "cache-control": PUBLIC_SHARED_IMAGES_CACHE_CONTROL };
  const canCache = page === 0 && size === 20;
  if (canCache) {
    const cached = await ctx.env.APP_KV.get<unknown>(PUBLIC_SHARED_IMAGES_CACHE_KEY, "json");
    if (cached) {
      return ok(cached, { headers });
    }
  }
  const countRow = await ctx.env.DB.prepare(
    "SELECT COUNT(*) AS total FROM generated_image WHERE is_shared IN (1, '1', 'true', 'b''\\x01''')"
  ).first<{ total: number }>();
  const total = Number(countRow?.total ?? 0);
  const rows = await ctx.env.DB.prepare(
    "SELECT * FROM generated_image WHERE is_shared IN (1, '1', 'true', 'b''\\x01''') ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?"
  ).bind(size, page * size).all();
  const data = pageOf((rows.results ?? []).map(imageView), page, size, total);
  if (canCache) {
    runInBackground(ctx, ctx.env.APP_KV.put(
      PUBLIC_SHARED_IMAGES_CACHE_KEY,
      JSON.stringify(data),
      { expirationTtl: PUBLIC_SHARED_IMAGES_CACHE_TTL_SECONDS }
    ));
  }
  return ok(data, { headers });
}

export async function testTelegramImage(ctx: RequestContext): Promise<Response> {
  requireUser(ctx);
  const bytes = Uint8Array.from(atob("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="), (char) => char.charCodeAt(0));
  const url = await uploadTelegramPhoto(ctx, bytes);
  if (!url) {
    throw new HttpError(502, "Telegram returned no photo file id");
  }
  return ok({ url });
}

export async function imageFile(ctx: RequestContext): Promise<Response> {
  const object = await requireR2Bucket(ctx).get(`images/${ctx.params.filename}`);
  if (!object) {
    throw new HttpError(404, "File not found");
  }
  return new Response(object.body, {
    headers: {
      "content-type": object.httpMetadata?.contentType || "application/octet-stream",
      "cache-control": "public, max-age=31536000, immutable"
    }
  });
}

export async function telegramImageFile(ctx: RequestContext): Promise<Response> {
  const botToken = await getConfig(ctx, "image.telegram.botToken", ctx.env.TELEGRAM_BOT_TOKEN || "");
  if (!botToken) {
    throw new HttpError(503, "image.telegram.botToken is not configured");
  }
  const fileId = ctx.params.fileId;
  const metaResponse = await fetch(`https://api.telegram.org/bot${botToken}/getFile?file_id=${encodeURIComponent(fileId)}`);
  const meta = await metaResponse.json<any>().catch(() => null);
  if (!metaResponse.ok || !meta?.ok || !meta?.result?.file_path) {
    throw new HttpError(502, "Failed to resolve Telegram file");
  }
  const fileResponse = await fetch(`https://api.telegram.org/file/bot${botToken}/${meta.result.file_path}`);
  if (!fileResponse.ok || !fileResponse.body) {
    throw new HttpError(502, "Failed to fetch Telegram file");
  }
  const headers = new Headers();
  headers.set("content-type", fileResponse.headers.get("content-type") || "image/png");
  headers.set("cache-control", "public, max-age=86400");
  return new Response(fileResponse.body, { headers });
}

async function callImageUpstream(ctx: RequestContext, prompt: string, model: string, size: string | null, quality: string | null, n: number, tracker?: ImagePipelineTracker) {
  const base = await getConfig(ctx, "image.api.baseUrl", ctx.env.IMAGE_API_BASE_URL || "");
  const apiKey = await getConfig(ctx, "image.api.key", ctx.env.IMAGE_API_KEY || "");
  if (!base) {
    throw new HttpError(503, "image.api.baseUrl is not configured");
  }
  await markStageStart(ctx, tracker, "upstream", { model, size, quality, n });
  let response: Response;
  let data: any = null;
  try {
    const body: Record<string, unknown> = { model, prompt, n };
    if (size) {
      body.size = size;
    }
    if (quality && quality !== "auto") {
      body.quality = quality;
    }
    response = await fetchWithTimeout(base, IMAGE_UPSTREAM_TIMEOUT_MS, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        ...(apiKey ? { authorization: `Bearer ${apiKey}` } : {})
      },
      body: JSON.stringify(body)
    });
    data = await response.json<any>().catch(() => null);
    if (!response.ok) {
      throw new HttpError(502, upstreamErrorMessage(data, response.status));
    }
    await markStageEnd(ctx, tracker, "upstream", {
      status: response.status,
      itemCount: Array.isArray(data?.data) ? data.data.length : 0
    });
  } catch (error) {
    await markStageError(ctx, tracker, "upstream", error);
    throw error;
  }
  const items = Array.isArray(data?.data) ? data.data : [];
  const images: Array<{ url: string | null; b64Json: string | null; revisedPrompt: string | null }> = [];
  for (const item of items) {
    const revisedPrompt = typeof item.revised_prompt === "string" ? item.revised_prompt : null;
    if (item.url) {
      const storedUrl = await persistRemoteImageUrl(ctx, item.url, tracker);
      images.push({ url: storedUrl || item.url, b64Json: null, revisedPrompt });
    } else if (item.b64_json) {
      const bytes = Uint8Array.from(atob(item.b64_json), (char) => char.charCodeAt(0));
      const storedUrl = await persistImageBytes(ctx, bytes, tracker);
      images.push({ url: storedUrl || `data:image/png;base64,${item.b64_json}`, b64Json: storedUrl ? null : item.b64_json, revisedPrompt });
    }
  }
  if (!images.length) {
    throw new HttpError(502, "Upstream returned no image data");
  }
  return {
    created: typeof data?.created === "number" ? data.created : Math.floor(Date.now() / 1000),
    model,
    data: images,
    usage: data?.usage ?? null
  };
}

async function runEditImageTask(
  ctx: RequestContext,
  userId: number,
  taskId: number,
  input: EditImageInput,
  model: string
): Promise<void> {
  const tracker = createPipelineTracker(taskId);
  try {
    await saveTimings(ctx, tracker);
    const imageResult = await callImageEditUpstream(ctx, input, model, tracker);
    imageResult.usage = { ...(imageResult.usage || {}), timings: tracker.timings };
    for (const image of imageResult.data) {
      const imageData = image.b64Json ? `data:image/png;base64,${image.b64Json}` : null;
      await ctx.env.DB.prepare(
        "INSERT INTO generated_image(user_id, prompt, image_url, image_data, model, size) VALUES(?, ?, ?, ?, ?, ?)"
      ).bind(userId, input.prompt, image.url || imageData || "", imageData, model, input.size).run();
    }
    await ctx.env.DB.prepare("UPDATE image_generation_task SET status = 'COMPLETED', result_json = ?, timings_json = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")
      .bind(JSON.stringify(imageResult), JSON.stringify(tracker.timings), taskId)
      .run();
  } catch (error) {
    await ctx.env.DB.prepare("UPDATE image_generation_task SET status = 'FAILED', error_message = ?, timings_json = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")
      .bind(error instanceof Error ? error.message : "Image edit failed", JSON.stringify(tracker.timings), taskId)
      .run();
    await refundCredits(ctx.env, userId, taskId);
  }
}

async function callImageEditUpstream(ctx: RequestContext, input: EditImageInput, model: string, tracker?: ImagePipelineTracker) {
  const base = await getImageEditBaseUrl(ctx);
  const apiKey = await getConfig(ctx, "image.api.key", ctx.env.IMAGE_API_KEY || "");
  const form = new FormData();
  form.set("model", model);
  form.set("prompt", input.prompt);
  form.set("n", String(input.n));
  if (input.size) {
    form.set("size", input.size);
  }
  if (input.quality && input.quality !== "auto") {
    form.set("quality", input.quality);
  }
  form.set("image", input.image, input.image.name || "image.png");
  if (input.mask) {
    form.set("mask", input.mask, input.mask.name || "mask.png");
  }
  await markStageStart(ctx, tracker, "upstream", {
    model,
    size: input.size,
    quality: input.quality,
    n: input.n,
    type: "edit"
  });
  let response: Response;
  let data: any = null;
  try {
    response = await fetchWithTimeout(base, IMAGE_UPSTREAM_TIMEOUT_MS, {
      method: "POST",
      headers: {
        accept: "application/json",
        ...(apiKey ? { authorization: `Bearer ${apiKey}` } : {})
      },
      body: form
    });
    data = await response.json<any>().catch(() => null);
    if (!response.ok) {
      throw new HttpError(502, upstreamErrorMessage(data, response.status));
    }
    await markStageEnd(ctx, tracker, "upstream", {
      status: response.status,
      itemCount: Array.isArray(data?.data) ? data.data.length : 0
    });
  } catch (error) {
    await markStageError(ctx, tracker, "upstream", error);
    throw error;
  }
  return normalizeImageResponse(ctx, data, model, tracker);
}

async function readEditImageInput(request: Request): Promise<EditImageInput> {
  if (!request.headers.get("content-type")?.includes("multipart/form-data")) {
    throw new HttpError(400, "multipart/form-data is required");
  }
  const form = await request.formData();
  const prompt = String(form.get("prompt") || "").trim();
  if (!prompt) {
    throw new HttpError(400, "prompt is required");
  }
  const image = form.get("image");
  if (!isFileLike(image)) {
    throw new HttpError(400, "image is required");
  }
  const mask = form.get("mask");
  const model = typeof form.get("model") === "string" ? String(form.get("model")).trim() || null : null;
  const size = typeof form.get("size") === "string" ? String(form.get("size")).trim() || null : null;
  const quality = typeof form.get("quality") === "string" ? String(form.get("quality")).trim() || null : null;
  const n = Math.min(10, Math.max(1, Number(form.get("n") || 1)));
  return {
    prompt,
    image,
    mask: isFileLike(mask) ? mask : null,
    model,
    size,
    quality,
    n
  };
}

function isFileLike(value: unknown): value is File {
  return typeof value === "object"
    && value !== null
    && typeof (value as File).arrayBuffer === "function"
    && typeof (value as File).name === "string";
}

async function getImageEditBaseUrl(ctx: RequestContext): Promise<string> {
  const configured = await getConfig(ctx, "image.edit.api.baseUrl", "");
  if (configured.trim()) {
    return configured.trim();
  }
  const base = await getConfig(ctx, "image.api.baseUrl", ctx.env.IMAGE_API_BASE_URL || "");
  if (!base.trim()) {
    throw new HttpError(503, "image.api.baseUrl is not configured");
  }
  return deriveImageEditBaseUrl(base);
}

function deriveImageEditBaseUrl(base: string): string {
  const trimmed = base.trim();
  if (trimmed.includes("/images/edits")) {
    return trimmed;
  }
  if (trimmed.includes("/images/generations")) {
    return trimmed.replace("/images/generations", "/images/edits");
  }
  if (trimmed.includes("/chat/completions")) {
    return trimmed.replace("/chat/completions", "/images/edits");
  }
  return trimmed.endsWith("/") ? `${trimmed}images/edits` : `${trimmed}/images/edits`;
}

async function normalizeImageResponse(ctx: RequestContext, data: any, model: string, tracker?: ImagePipelineTracker) {
  const items = Array.isArray(data?.data) ? data.data : [];
  const images: Array<{ url: string | null; b64Json: string | null; revisedPrompt: string | null }> = [];
  for (const item of items) {
    const revisedPrompt = typeof item.revised_prompt === "string" ? item.revised_prompt : null;
    if (item.url) {
      const storedUrl = await persistRemoteImageUrl(ctx, item.url, tracker);
      images.push({ url: storedUrl || item.url, b64Json: null, revisedPrompt });
    } else if (item.b64_json) {
      const bytes = Uint8Array.from(atob(item.b64_json), (char) => char.charCodeAt(0));
      const storedUrl = await persistImageBytes(ctx, bytes, tracker);
      images.push({ url: storedUrl || `data:image/png;base64,${item.b64_json}`, b64Json: storedUrl ? null : item.b64_json, revisedPrompt });
    }
  }
  if (!images.length) {
    throw new HttpError(502, "Upstream returned no image data");
  }
  return {
    created: typeof data?.created === "number" ? data.created : Math.floor(Date.now() / 1000),
    model,
    data: images,
    usage: data?.usage ?? null
  };
}

async function taskById(ctx: RequestContext, userId: number, id: number) {
  await expireStaleProcessingTasks(ctx, userId);
  const row = await firstRequired<any>(ctx.env.DB.prepare("SELECT * FROM image_generation_task WHERE id = ? AND user_id = ?").bind(id, userId));
  return {
    id: row.id,
    type: row.type,
    status: row.status,
    prompt: row.prompt,
    model: row.model,
    size: row.size,
    n: row.n,
    result: row.result_json ? JSON.parse(row.result_json) : null,
    timings: parseJsonOrNull(row.timings_json),
    errorMessage: row.error_message,
    createdAt: row.created_at,
    updatedAt: row.updated_at
  };
}

async function expireStaleProcessingTasks(ctx: RequestContext, userId: number): Promise<void> {
  const rows = await ctx.env.DB.prepare(
    "SELECT id FROM image_generation_task WHERE user_id = ? AND status = 'PROCESSING' AND datetime(updated_at) < datetime('now', '-20 minutes')"
  ).bind(userId).all<{ id: number }>();
  await ctx.env.DB.prepare(
    "UPDATE image_generation_task SET status = 'FAILED', error_message = 'Task expired before completion', updated_at = CURRENT_TIMESTAMP WHERE user_id = ? AND status = 'PROCESSING' AND datetime(updated_at) < datetime('now', '-20 minutes')"
  ).bind(userId).run();
  for (const row of rows.results ?? []) {
    await refundCredits(ctx.env, userId, row.id);
  }
}

function imageView(row: any) {
  return {
    id: row.id,
    taskId: row.task_id,
    type: row.item_type,
    status: row.status,
    prompt: row.prompt,
    imageUrl: row.image_url || row.image_data,
    imageData: row.image_data,
    model: row.model,
    size: row.size,
    isShared: toBool(row.is_shared),
    shared: toBool(row.is_shared),
    errorMessage: row.error_message,
    timings: parseJsonOrNull(row.timings_json),
    createdAt: row.created_at
  };
}

function createPipelineTracker(taskId: number): ImagePipelineTracker {
  return { taskId, timings: {} };
}

async function markStageStart(
  ctx: RequestContext,
  tracker: ImagePipelineTracker | undefined,
  stage: ImageStageName,
  meta?: Record<string, unknown>
): Promise<void> {
  if (!tracker) {
    return;
  }
  const startedAt = new Date().toISOString();
  const current = tracker.timings[stage] || {};
  const run: ImageStageRunTiming = {
    startedAt,
    status: "running",
    ...(meta ? { meta } : {})
  };
  tracker.timings[stage] = {
    ...current,
    startedAt: current.startedAt || startedAt,
    endedAt: undefined,
    status: "running",
    error: undefined,
    meta: { ...(current.meta || {}), ...(meta || {}) },
    runs: [...(current.runs || []), run]
  };
  await saveTimings(ctx, tracker);
}

async function markStageEnd(
  ctx: RequestContext,
  tracker: ImagePipelineTracker | undefined,
  stage: ImageStageName,
  meta?: Record<string, unknown>
): Promise<void> {
  if (!tracker) {
    return;
  }
  const current = tracker.timings[stage] || {};
  const endedAt = new Date().toISOString();
  const runs = [...(current.runs || [])];
  const runIndex = findLastRunningRunIndex(runs);
  if (runIndex >= 0) {
    const run = runs[runIndex];
    runs[runIndex] = {
      ...run,
      endedAt,
      durationMs: run.startedAt ? Date.parse(endedAt) - Date.parse(run.startedAt) : undefined,
      status: "ok",
      meta: { ...(run.meta || {}), ...(meta || {}) }
    };
  }
  const durationMs = sumRunDurations(runs);
  tracker.timings[stage] = {
    ...current,
    endedAt,
    durationMs,
    status: "ok",
    meta: { ...(current.meta || {}), ...(meta || {}) },
    runs
  };
  await saveTimings(ctx, tracker);
}

async function markStageError(
  ctx: RequestContext,
  tracker: ImagePipelineTracker | undefined,
  stage: ImageStageName,
  error: unknown
): Promise<void> {
  if (!tracker) {
    return;
  }
  const current = tracker.timings[stage] || {};
  const endedAt = new Date().toISOString();
  const runs = [...(current.runs || [])];
  const runIndex = findLastRunningRunIndex(runs);
  if (runIndex >= 0) {
    const run = runs[runIndex];
    runs[runIndex] = {
      ...run,
      endedAt,
      durationMs: run.startedAt ? Date.parse(endedAt) - Date.parse(run.startedAt) : undefined,
      status: "failed",
      error: error instanceof Error ? error.message : String(error)
    };
  }
  const durationMs = sumRunDurations(runs);
  tracker.timings[stage] = {
    ...current,
    endedAt,
    durationMs,
    status: "failed",
    error: error instanceof Error ? error.message : String(error),
    runs
  };
  await saveTimings(ctx, tracker);
}

function findLastRunningRunIndex(runs: ImageStageRunTiming[]): number {
  for (let index = runs.length - 1; index >= 0; index -= 1) {
    if (runs[index].status === "running") {
      return index;
    }
  }
  return -1;
}

function sumRunDurations(runs: ImageStageRunTiming[]): number | undefined {
  const total = runs.reduce((sum, run) => sum + (typeof run.durationMs === "number" ? run.durationMs : 0), 0);
  return total > 0 ? total : undefined;
}

async function saveTimings(ctx: RequestContext, tracker: ImagePipelineTracker): Promise<void> {
  await ctx.env.DB.prepare(
    "UPDATE image_generation_task SET timings_json = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?"
  ).bind(JSON.stringify(tracker.timings), tracker.taskId).run();
}

function parseJsonOrNull(value: unknown): unknown {
  if (typeof value !== "string" || !value.trim()) {
    return null;
  }
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}

async function getConfig(ctx: RequestContext, key: string, fallback: string) {
  const row = await ctx.env.DB.prepare("SELECT config_value FROM sys_config WHERE config_key = ?").bind(key).first<{ config_value: string }>();
  return row?.config_value || fallback;
}

async function calculateCreditCost(ctx: RequestContext, n: number): Promise<number> {
  return Math.max(0, n) * await imageCreditCost(ctx.env);
}

function publicR2Url(ctx: RequestContext, filename: string): string {
  return ctx.env.PUBLIC_R2_BASE_URL
    ? `${ctx.env.PUBLIC_R2_BASE_URL.replace(/\/$/, "")}/images/${filename}`
    : `/api/v1/image/file/${filename}`;
}

async function persistImageBytes(ctx: RequestContext, bytes: Uint8Array, tracker?: ImagePipelineTracker): Promise<string> {
  const bucket = requireR2Bucket(ctx);
  const filename = `${randomToken(12)}.png`;
  await markStageStart(ctx, tracker, "r2Upload", { bytes: bytes.byteLength, key: `images/${filename}` });
  try {
    await bucket.put(`images/${filename}`, bytes, { httpMetadata: { contentType: "image/png" } });
    await markStageEnd(ctx, tracker, "r2Upload", { bytes: bytes.byteLength, key: `images/${filename}` });
    return publicR2Url(ctx, filename);
  } catch (error) {
    await markStageError(ctx, tracker, "r2Upload", error);
    throw error;
  }
}

async function persistRemoteImageUrl(ctx: RequestContext, url: string, tracker?: ImagePipelineTracker): Promise<string> {
  requireR2Bucket(ctx);
  await markStageStart(ctx, tracker, "download", { source: "remote-url" });
  let bytes: Uint8Array;
  try {
    const response = await fetchWithTimeout(url, REMOTE_IMAGE_TIMEOUT_MS);
    if (!response.ok) {
      throw new HttpError(502, `Remote image download failed: HTTP ${response.status}`);
    }
    bytes = new Uint8Array(await response.arrayBuffer());
    await markStageEnd(ctx, tracker, "download", {
      status: response.status,
      bytes: bytes.byteLength,
      contentType: response.headers.get("content-type") || null
    });
  } catch (error) {
    await markStageError(ctx, tracker, "download", error);
    throw error;
  }
  const storedUrl = await persistImageBytes(ctx, bytes, tracker);
  return storedUrl;
}

function requireR2Bucket(ctx: RequestContext): R2Bucket {
  if (!ctx.env.R2_BUCKET) {
    throw new HttpError(503, "R2_BUCKET is not configured");
  }
  return ctx.env.R2_BUCKET;
}

async function uploadTelegramPhoto(ctx: RequestContext, bytes: Uint8Array, tracker?: ImagePipelineTracker): Promise<string | null> {
  const botToken = await getConfig(ctx, "image.telegram.botToken", ctx.env.TELEGRAM_BOT_TOKEN || "");
  const chatId = await getConfig(ctx, "image.telegram.chatId", ctx.env.TELEGRAM_CHAT_ID || "");
  if (!botToken || !chatId) {
    return null;
  }

  const form = new FormData();
  form.set("chat_id", chatId);
  form.set("disable_notification", "true");
  form.set("photo", new File([bytes], `${randomToken(12)}.png`, { type: "image/png" }));

  await markStageStart(ctx, tracker, "telegramUpload", { bytes: bytes.byteLength });
  let response: Response;
  try {
    response = await fetch(`https://api.telegram.org/bot${botToken}/sendPhoto`, {
      method: "POST",
      body: form
    });
    await markStageEnd(ctx, tracker, "telegramUpload", { status: response.status });
  } catch (error) {
    await markStageError(ctx, tracker, "telegramUpload", error);
    throw error;
  }

  await markStageStart(ctx, tracker, "telegramResponse", { status: response.status });
  let data: any = null;
  try {
    data = await response.json<any>().catch(() => null);
    if (!response.ok || !data?.ok) {
      const description = typeof data?.description === "string" && data.description.trim()
        ? data.description.trim()
        : `HTTP ${response.status}`;
      throw new Error(description);
    }
    const photos = data.result?.photo;
    if (!Array.isArray(photos) || !photos.length) {
      await markStageEnd(ctx, tracker, "telegramResponse", { fileId: null, photoCount: 0 });
      return null;
    }
    const best = photos[photos.length - 1];
    const fileId = typeof best?.file_id === "string" ? best.file_id : null;
    await markStageEnd(ctx, tracker, "telegramResponse", {
      fileId: fileId ? "present" : null,
      photoCount: photos.length
    });
    return fileId ? `/api/v1/image/telegram/${encodeURIComponent(fileId)}` : null;
  } catch (error) {
    await markStageError(ctx, tracker, "telegramResponse", error);
    throw error;
  }
}

async function fetchWithTimeout(input: RequestInfo | URL, timeoutMs: number, init?: RequestInit): Promise<Response> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(input, { ...init, signal: controller.signal });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new HttpError(504, `Request timed out after ${Math.round(timeoutMs / 1000)}s`);
    }
    throw error;
  } finally {
    clearTimeout(timeoutId);
  }
}

function upstreamErrorMessage(data: any, status: number): string {
  const message = data?.error?.message || data?.message || data?.error;
  return typeof message === "string" && message.trim()
    ? `Upstream image API returned ${status}: ${message}`
    : `Upstream image API returned ${status}`;
}

function invalidateSharedImagesCache(ctx: RequestContext): void {
  runInBackground(ctx, ctx.env.APP_KV.delete(PUBLIC_SHARED_IMAGES_CACHE_KEY));
}

function runInBackground(ctx: RequestContext, promise: Promise<unknown>): void {
  const guarded = promise.catch(() => undefined);
  if (ctx.waitUntil) {
    ctx.waitUntil(guarded);
    return;
  }
  void guarded;
}
