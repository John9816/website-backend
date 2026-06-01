import { firstRequired } from "../db";
import { ok, readJson, requireUser } from "../http";
import { HttpError, RequestContext } from "../types";
import { pagedQuery } from "./nav";

interface SendMessageBody {
  content?: string;
  message?: string;
  model?: string;
  ttsVoice?: string;
}

export async function models(ctx: RequestContext): Promise<Response> {
  requireUser(ctx);
  const value = await config(ctx, "ai.chat.models", "gpt-4.1-mini,gpt-4.1,gpt-4o-mini");
  const defaultModel = await config(ctx, "ai.chat.defaultModel", "gpt-4.1-mini");
  return ok(value.split(",").map((model) => model.trim()).filter(Boolean).map((model) => ({
    model,
    defaultModel: model === defaultModel,
    capabilities: modelCapabilities(model)
  })));
}

export async function voices(ctx: RequestContext): Promise<Response> {
  requireUser(ctx);
  const value = await config(ctx, "ai.chat.voices", "alloy|Alloy,verse|Verse,aria|Aria");
  return ok(value.split(",").map((item) => {
    const [id, label] = item.split("|");
    return { id: id?.trim(), label: (label || id)?.trim() };
  }).filter((item) => item.id));
}

export async function tts(ctx: RequestContext): Promise<Response> {
  requireUser(ctx);
  const body = await readJson<Record<string, unknown>>(ctx.request);
  const base = await config(ctx, "ai.chat.baseUrl", ctx.env.AI_CHAT_BASE_URL || "");
  const apiKey = await config(ctx, "ai.chat.apiKey", ctx.env.AI_CHAT_API_KEY || "");
  if (!base) {
    throw new HttpError(503, "AI_CHAT_BASE_URL is not configured");
  }
  const upstream = await fetch(`${base.replace(/\/$/, "")}/audio/speech`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      ...(apiKey ? { authorization: `Bearer ${apiKey}` } : {})
    },
    body: JSON.stringify(body)
  });
  return new Response(upstream.body, {
    status: upstream.status,
    headers: {
      "content-type": upstream.headers.get("content-type") || "audio/mpeg",
      "content-disposition": "inline; filename=\"speech.mp3\""
    }
  });
}

export async function createConversation(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const body = await readJson<{ title?: string; model?: string }>(ctx.request);
  const model = body.model || await config(ctx, "ai.chat.defaultModel", "gpt-4.1-mini");
  const result = await ctx.env.DB.prepare("INSERT INTO ai_conversation(user_id, title, model) VALUES(?, ?, ?)")
    .bind(user.id, body.title || "New conversation", model)
    .run();
  return ok(await conversationViewById(ctx, user.id, Number(result.meta.last_row_id)));
}

export async function conversations(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  return pagedQuery(
    ctx,
    "SELECT * FROM ai_conversation WHERE user_id = ? ORDER BY last_message_at DESC, id DESC",
    "SELECT COUNT(*) AS total FROM ai_conversation WHERE user_id = ?",
    [user.id],
    conversationView
  );
}

export async function conversation(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  return ok(await conversationViewById(ctx, user.id, Number(ctx.params.id)));
}

export async function messages(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  await conversationViewById(ctx, user.id, Number(ctx.params.id));
  return pagedQuery(
    ctx,
    "SELECT m.* FROM ai_chat_message m WHERE m.conversation_id = ? ORDER BY m.id",
    "SELECT COUNT(*) AS total FROM ai_chat_message WHERE conversation_id = ?",
    [Number(ctx.params.id)],
    messageView,
    50
  );
}

export async function sendMessage(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const conversationId = Number(ctx.params.id);
  const conv = await conversationViewById(ctx, user.id, conversationId);
  const body = await readJson<SendMessageBody>(ctx.request);
  const content = (body.content || body.message || "").trim();
  if (!content) {
    throw new HttpError(400, "content is required");
  }
  const model = body.model || conv.model;
  const userResult = await insertMessage(ctx, conversationId, "user", content, model);
  const assistant = await completeChat(ctx, conversationId, content, model);
  const assistantResult = await insertMessage(ctx, conversationId, "assistant", assistant.content, model, assistant);
  await ctx.env.DB.prepare(
    "UPDATE ai_conversation SET last_message_preview = ?, last_message_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE id = ?"
  ).bind(assistant.content.slice(0, 500), conversationId).run();
  return ok({
    conversation: await conversationViewById(ctx, user.id, conversationId),
    userMessage: messageView(await messageById(ctx, Number(userResult.meta.last_row_id))),
    assistantMessage: messageView(await messageById(ctx, Number(assistantResult.meta.last_row_id)))
  });
}

export async function streamMessage(ctx: RequestContext): Promise<Response> {
  const response = await sendMessage(ctx);
  const payload = await response.json<any>();
  const text = payload.data?.assistantMessage?.content || "";
  const reply = payload.data;
  const model = reply?.conversation?.model || reply?.assistantMessage?.model || "";
  const stream = new ReadableStream({
    start(controller) {
      if (model) {
        controller.enqueue(new TextEncoder().encode(`event: meta\ndata: ${JSON.stringify({ conversationId: reply?.conversation?.id, model })}\n\n`));
      }
      controller.enqueue(new TextEncoder().encode(`event: delta\ndata: ${JSON.stringify({ content: text })}\n\n`));
      controller.enqueue(new TextEncoder().encode(`event: done\ndata: ${JSON.stringify(reply)}\n\n`));
      controller.close();
    }
  });
  return new Response(stream, { headers: { "content-type": "text/event-stream;charset=UTF-8" } });
}

export async function messageAudio(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const row = await messageOwnedByUser(ctx, user.id, Number(ctx.params.id));
  if (!row.audio_data) {
    throw new HttpError(404, "Audio not found");
  }
  const bytes = Uint8Array.from(atob(row.audio_data), (char) => char.charCodeAt(0));
  return new Response(bytes, { headers: { "content-type": row.audio_mime_type || "audio/mpeg" } });
}

export async function regenerateAudio(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const row = await messageOwnedByUser(ctx, user.id, Number(ctx.params.id));
  return ok(messageView(row));
}

async function completeChat(ctx: RequestContext, conversationId: number, content: string, model: string) {
  const base = await config(ctx, "ai.chat.baseUrl", ctx.env.AI_CHAT_BASE_URL || "");
  const apiKey = await config(ctx, "ai.chat.apiKey", ctx.env.AI_CHAT_API_KEY || "");
  if (!base) {
    return { content: "AI_CHAT_BASE_URL is not configured.", finishReason: "mock" };
  }
  const rows = await ctx.env.DB.prepare("SELECT role, content FROM ai_chat_message WHERE conversation_id = ? ORDER BY id DESC LIMIT 20")
    .bind(conversationId)
    .all<{ role: string; content: string }>();
  const history = (rows.results ?? []).reverse().map((row) => ({ role: row.role, content: row.content }));
  const response = await fetch(`${base.replace(/\/$/, "")}/chat/completions`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      ...(apiKey ? { authorization: `Bearer ${apiKey}` } : {})
    },
    body: JSON.stringify({ model, messages: [...history, { role: "user", content }] })
  });
  const data = await response.json<any>().catch(() => null);
  const message = data?.choices?.[0]?.message?.content;
  return {
    content: typeof message === "string" ? message : "Upstream AI returned no content.",
    finishReason: data?.choices?.[0]?.finish_reason || null,
    promptTokens: data?.usage?.prompt_tokens ?? null,
    completionTokens: data?.usage?.completion_tokens ?? null,
    totalTokens: data?.usage?.total_tokens ?? null
  };
}

async function insertMessage(ctx: RequestContext, conversationId: number, role: string, content: string, model: string, meta: any = {}) {
  return ctx.env.DB.prepare(
    `INSERT INTO ai_chat_message(conversation_id, role, content, model, finish_reason, prompt_tokens, completion_tokens, total_tokens)
     VALUES(?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(
    conversationId,
    role,
    content,
    model,
    meta.finishReason ?? null,
    meta.promptTokens ?? null,
    meta.completionTokens ?? null,
    meta.totalTokens ?? null
  ).run();
}

async function conversationViewById(ctx: RequestContext, userId: number, id: number) {
  const row = await firstRequired<any>(
    ctx.env.DB.prepare("SELECT * FROM ai_conversation WHERE id = ? AND user_id = ?").bind(id, userId)
  );
  return conversationView(row);
}

async function messageById(ctx: RequestContext, id: number) {
  return firstRequired<any>(ctx.env.DB.prepare("SELECT * FROM ai_chat_message WHERE id = ?").bind(id));
}

async function messageOwnedByUser(ctx: RequestContext, userId: number, id: number) {
  return firstRequired<any>(
    ctx.env.DB.prepare(
      "SELECT m.* FROM ai_chat_message m JOIN ai_conversation c ON c.id = m.conversation_id WHERE m.id = ? AND c.user_id = ?"
    ).bind(id, userId)
  );
}

function conversationView(row: any) {
  return {
    id: row.id,
    title: row.title,
    model: row.model,
    lastMessagePreview: row.last_message_preview,
    lastMessageAt: row.last_message_at,
    createdAt: row.created_at,
    updatedAt: row.updated_at
  };
}

function messageView(row: any) {
  const hasAudioData = typeof row.audio_data === "string" && row.audio_data.trim().length > 0;
  const hasAudioUrl = typeof row.audio_source_url === "string" && row.audio_source_url.trim().length > 0;
  return {
    id: row.id,
    conversationId: row.conversation_id,
    role: row.role,
    content: row.content,
    model: row.model,
    audioAvailable: hasAudioData || hasAudioUrl,
    audioModel: row.audio_model,
    audioUrl: hasAudioUrl ? row.audio_source_url : hasAudioData ? `/api/user/ai/messages/${row.id}/audio` : null,
    audioMimeType: row.audio_mime_type,
    finishReason: row.finish_reason,
    promptTokens: row.prompt_tokens,
    completionTokens: row.completion_tokens,
    totalTokens: row.total_tokens,
    createdAt: row.created_at
  };
}

function modelCapabilities(model: string): string[] {
  const normalized = model.toLowerCase();
  const capabilities = new Set<string>(["text_chat"]);
  if (normalized.includes("tts") || normalized.includes("audio")) {
    capabilities.add("audio_output");
  }
  if (normalized.includes("4o") || normalized.includes("audio") || normalized.includes("transcribe")) {
    capabilities.add("audio_input");
  }
  if (normalized.includes("tts")) {
    capabilities.add("voice_customization");
  }
  return Array.from(capabilities);
}

async function config(ctx: RequestContext, key: string, fallback: string) {
  const row = await ctx.env.DB.prepare("SELECT config_value FROM sys_config WHERE config_key = ?").bind(key).first<{ config_value: string }>();
  return row?.config_value || fallback;
}
