import { count, pageOf } from "../db";
import { intParam, ok, readJson, requireAdmin } from "../http";
import { HttpError, RequestContext } from "../types";

interface HotSource {
  id: string;
  name: string;
  url: string;
}

interface HotTopic {
  id: string;
  source: string;
  sourceName: string;
  rank: number;
  title: string;
  url: string | null;
  hot: string | null;
  summary: string | null;
  capturedAt: string;
}

interface ArticleGenerateBody {
  topics?: HotTopic[];
  topicIds?: string[];
  angle?: string;
  audience?: string;
  tone?: string;
  length?: "short" | "standard" | "long";
  generateCover?: boolean;
  coverStyle?: string;
  model?: string;
}

interface ArticleDraft {
  title: string;
  digest: string;
  contentMarkdown: string;
  contentHtml: string;
  coverPrompt: string;
  tags: string[];
  riskTips: string[];
}

const DEFAULT_HOT_SOURCES: HotSource[] = [
  { id: "weibo", name: "微博", url: "https://api-hot.imsyy.top/weibo" },
  { id: "zhihu", name: "知乎", url: "https://api-hot.imsyy.top/zhihu" },
  { id: "toutiao", name: "头条", url: "https://api-hot.imsyy.top/toutiao" },
  { id: "baidu", name: "百度", url: "https://api-hot.imsyy.top/baidu" }
];

const FALLBACK_TOPICS: HotTopic[] = [
  {
    id: "fallback:1",
    source: "fallback",
    sourceName: "趋势观察",
    rank: 1,
    title: "AI 工具正在重塑个人效率工作流",
    url: null,
    hot: "趋势",
    summary: "围绕效率、自动化、内容生产和个人知识管理的讨论持续升温。",
    capturedAt: new Date().toISOString()
  },
  {
    id: "fallback:2",
    source: "fallback",
    sourceName: "趋势观察",
    rank: 2,
    title: "普通人如何用数据判断一个选题值不值得写",
    url: null,
    hot: "方法论",
    summary: "热点选择从直觉走向数据化，选题、标题和转化链路成为内容创作重点。",
    capturedAt: new Date().toISOString()
  },
  {
    id: "fallback:3",
    source: "fallback",
    sourceName: "趋势观察",
    rank: 3,
    title: "公众号文章的阅读完成率比标题点击更值得关注",
    url: null,
    hot: "内容运营",
    summary: "内容平台更看重真实停留、互动和转发，单纯标题党越来越难持续。",
    capturedAt: new Date().toISOString()
  }
];

export async function status(ctx: RequestContext): Promise<Response> {
  requireAdmin(ctx);
  const [aiBase, aiKey, imageBase, imageKey, appId, appSecret] = await Promise.all([
    config(ctx, "ai.chat.baseUrl", ctx.env.AI_CHAT_BASE_URL || ""),
    config(ctx, "ai.chat.apiKey", ctx.env.AI_CHAT_API_KEY || ""),
    config(ctx, "image.api.baseUrl", ctx.env.IMAGE_API_BASE_URL || ""),
    config(ctx, "image.api.key", ctx.env.IMAGE_API_KEY || ""),
    config(ctx, "wechat.appId", ""),
    config(ctx, "wechat.appSecret", "")
  ]);

  return ok({
    aiReady: Boolean(aiBase),
    imageReady: Boolean(imageBase),
    wechatReady: Boolean(appId && appSecret),
    configs: [
      { key: "ai.chat.baseUrl", label: "文章模型地址", ready: Boolean(aiBase) },
      { key: "ai.chat.apiKey", label: "文章模型密钥", ready: Boolean(aiKey) },
      { key: "image.api.baseUrl", label: "封面图片地址", ready: Boolean(imageBase) },
      { key: "image.api.key", label: "封面图片密钥", ready: Boolean(imageKey) },
      { key: "wechat.appId", label: "公众号 AppID", ready: Boolean(appId) },
      { key: "wechat.appSecret", label: "公众号 AppSecret", ready: Boolean(appSecret) }
    ]
  });
}

export async function hotTopics(ctx: RequestContext): Promise<Response> {
  requireAdmin(ctx);
  const sources = await hotSources(ctx);
  const limit = intParam(ctx.url, "limit", 12, 1, 50);
  const settled = await Promise.allSettled(sources.map((source) => fetchHotSource(source, limit)));
  const topics = settled
    .flatMap((item) => item.status === "fulfilled" ? item.value : [])
    .slice(0, Math.max(limit, sources.length * limit));

  return ok({
    capturedAt: new Date().toISOString(),
    sources: sources.map(({ id, name }) => ({ id, name })),
    items: topics.length ? topics : FALLBACK_TOPICS
  });
}

export async function articles(ctx: RequestContext): Promise<Response> {
  const user = requireAdmin(ctx);
  await ensureContentTables(ctx);
  const page = intParam(ctx.url, "page", 0, 0, 10000);
  const size = intParam(ctx.url, "size", 10, 1, 50);
  const total = await count(ctx.env.DB, "SELECT COUNT(*) AS total FROM content_article WHERE user_id = ?", user.id);
  const rows = await ctx.env.DB.prepare(
    "SELECT * FROM content_article WHERE user_id = ? ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?"
  ).bind(user.id, size, page * size).all();

  return ok(pageOf((rows.results ?? []).map(articleView), page, size, total));
}

export async function getArticle(ctx: RequestContext): Promise<Response> {
  const user = requireAdmin(ctx);
  await ensureContentTables(ctx);
  return ok(await articleById(ctx, user.id, Number(ctx.params.id)));
}

export async function generateArticle(ctx: RequestContext): Promise<Response> {
  const user = requireAdmin(ctx);
  await ensureContentTables(ctx);
  const body = await readJson<ArticleGenerateBody>(ctx.request);
  const topics = normalizeSelectedTopics(body.topics).slice(0, 8);
  const selectedTopics = topics.length ? topics : (await collectDefaultTopics(ctx)).slice(0, 5);
  const model = body.model || await config(ctx, "content.article.model", await config(ctx, "ai.chat.defaultModel", "gpt-4.1-mini"));

  let draft: ArticleDraft;
  let articleError: string | null = null;
  try {
    draft = await createArticleDraft(ctx, selectedTopics, body, model);
  } catch (error) {
    articleError = error instanceof Error ? error.message : "article generation failed";
    draft = fallbackArticle(selectedTopics, body);
  }
  let coverImageUrl: string | null = null;
  let coverError: string | null = null;
  if (body.generateCover !== false) {
    try {
      coverImageUrl = await generateCoverImage(ctx, buildCoverPrompt(draft, body.coverStyle));
    } catch (error) {
      coverError = error instanceof Error ? error.message : "cover generation failed";
    }
  }

  const result = await ctx.env.DB.prepare(
    `INSERT INTO content_article(
      user_id, title, digest, content_markdown, content_html, cover_prompt, cover_image_url,
      topics_json, tags_json, risk_tips_json, model, status, error_message
    ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?)`
  ).bind(
    user.id,
    draft.title,
    draft.digest,
    draft.contentMarkdown,
    draft.contentHtml,
    draft.coverPrompt,
    coverImageUrl,
    JSON.stringify(selectedTopics),
    JSON.stringify(draft.tags),
    JSON.stringify(draft.riskTips),
    model,
    [articleError, coverError].filter(Boolean).join("\n") || null
  ).run();

  return ok(await articleById(ctx, user.id, Number(result.meta.last_row_id)));
}

export async function updateArticle(ctx: RequestContext): Promise<Response> {
  const user = requireAdmin(ctx);
  await ensureContentTables(ctx);
  const id = Number(ctx.params.id);
  await articleById(ctx, user.id, id);
  const body = await readJson<Record<string, unknown>>(ctx.request);
  const title = requiredString(body.title, "title");
  const digest = stringOrNull(body.digest) || "";
  const contentHtml = requiredString(body.contentHtml, "contentHtml");
  const contentMarkdown = stringOrNull(body.contentMarkdown) || htmlToPlainText(contentHtml);
  const coverImageUrl = stringOrNull(body.coverImageUrl);

  await ctx.env.DB.prepare(
    `UPDATE content_article
     SET title = ?, digest = ?, content_markdown = ?, content_html = ?, cover_image_url = ?, updated_at = CURRENT_TIMESTAMP
     WHERE id = ? AND user_id = ?`
  ).bind(title, digest, contentMarkdown, contentHtml, coverImageUrl, id, user.id).run();
  return ok(await articleById(ctx, user.id, id));
}

export async function createWechatDraft(ctx: RequestContext): Promise<Response> {
  const user = requireAdmin(ctx);
  await ensureContentTables(ctx);
  const article = await articleById(ctx, user.id, Number(ctx.params.id));
  const result = await pushArticleToWechat(ctx, article, false);
  await ctx.env.DB.prepare(
    `UPDATE content_article
     SET status = 'WECHAT_DRAFT', wechat_media_id = ?, wechat_url = ?, error_message = NULL, updated_at = CURRENT_TIMESTAMP
     WHERE id = ? AND user_id = ?`
  ).bind(result.mediaId, result.url, article.id, user.id).run();
  return ok({ article: await articleById(ctx, user.id, article.id), draft: result });
}

export async function publishWechat(ctx: RequestContext): Promise<Response> {
  const user = requireAdmin(ctx);
  await ensureContentTables(ctx);
  const article = await articleById(ctx, user.id, Number(ctx.params.id));
  const draft = await pushArticleToWechat(ctx, article, false);
  const token = await wechatAccessToken(ctx);
  const response = await fetch(`https://api.weixin.qq.com/cgi-bin/freepublish/submit?access_token=${encodeURIComponent(token)}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ media_id: draft.mediaId })
  });
  const data = await parseWechatJson(response, "publish article");
  const publishId = stringOrNull(data.publish_id) || stringOrNull(data.publishId) || null;

  await ctx.env.DB.prepare(
    `UPDATE content_article
     SET status = 'PUBLISHED', wechat_media_id = ?, wechat_publish_id = ?, wechat_url = ?, error_message = NULL, updated_at = CURRENT_TIMESTAMP
     WHERE id = ? AND user_id = ?`
  ).bind(draft.mediaId, publishId, draft.url, article.id, user.id).run();
  return ok({ article: await articleById(ctx, user.id, article.id), draft, publish: data });
}

export async function contentAssetFile(ctx: RequestContext): Promise<Response> {
  const bucket = ctx.env.R2_BUCKET;
  if (!bucket) {
    throw new HttpError(503, "R2_BUCKET is not configured");
  }
  const object = await bucket.get(`content/${ctx.params.filename}`);
  if (!object) {
    throw new HttpError(404, "Asset not found");
  }
  const headers = new Headers();
  object.writeHttpMetadata(headers);
  headers.set("cache-control", "public, max-age=604800");
  return new Response(object.body, { headers });
}

async function createArticleDraft(ctx: RequestContext, topics: HotTopic[], body: ArticleGenerateBody, model: string): Promise<ArticleDraft> {
  const base = await config(ctx, "ai.chat.baseUrl", ctx.env.AI_CHAT_BASE_URL || "");
  const apiKey = await config(ctx, "ai.chat.apiKey", ctx.env.AI_CHAT_API_KEY || "");
  if (!base) {
    return fallbackArticle(topics, body);
  }

  const prompt = [
    "你是资深微信公众号主编和增长编辑。",
    "基于输入的热榜数据，写一篇可直接发公众号的原创文章。",
    "要求：不编造事实；引用热点时保留来源线索；标题有传播力但不夸大；正文结构适合手机阅读；输出严格 JSON。",
    "JSON 字段：title, digest, contentMarkdown, contentHtml, coverPrompt, tags, riskTips。",
    "contentHtml 使用 p/h2/blockquote/ul/li/strong 标签，不要包含 script/style。",
    "",
    JSON.stringify({
      topics,
      angle: body.angle || "从热点中提炼普通人可执行的方法",
      audience: body.audience || "关注效率、AI、内容运营和个人成长的读者",
      tone: body.tone || "清醒、有洞察、有行动感",
      length: body.length || "standard"
    })
  ].join("\n");

  const response = await fetch(chatEndpoint(base), {
    method: "POST",
    headers: {
      "content-type": "application/json",
      ...(apiKey ? { authorization: `Bearer ${apiKey}` } : {})
    },
    body: JSON.stringify({
      model,
      temperature: 0.72,
      messages: [
        { role: "system", content: "只输出一个 JSON 对象，不要包裹 Markdown 代码块。" },
        { role: "user", content: prompt }
      ]
    })
  });

  const data = await response.json<any>().catch(() => null);
  if (!response.ok) {
    throw new HttpError(502, upstreamMessage(data, response.status, "AI article"));
  }
  const content = data?.choices?.[0]?.message?.content;
  if (typeof content !== "string" || !content.trim()) {
    return fallbackArticle(topics, body);
  }
  return normalizeArticleDraft(parseJsonObject(content) ?? {}, topics, body);
}

function fallbackArticle(topics: HotTopic[], body: ArticleGenerateBody): ArticleDraft {
  const main = topics[0] ?? FALLBACK_TOPICS[0];
  const title = `从“${main.title}”看见一个正在变热的机会`;
  const topicItems = topics.map((topic) => `- ${topic.sourceName} #${topic.rank}：${topic.title}${topic.hot ? `（${topic.hot}）` : ""}`).join("\n");
  const contentMarkdown = [
    `# ${title}`,
    "",
    `今天的热榜里，最值得关注的不是单个话题本身，而是它背后反复出现的需求：人们想更快判断趋势、更低成本做选择，也更希望把复杂信息变成可执行动作。`,
    "",
    "## 热点信号",
    topicItems,
    "",
    "## 为什么它会被转发",
    "一个话题能被持续讨论，通常同时满足三个条件：和当下生活有关、能提供新鲜视角、能让读者立刻联想到自己。",
    "",
    "## 可以怎么写",
    "先用一个具体场景切入，再给出可验证的数据线索，最后落到三个行动建议。这样的结构比单纯追热点更稳，也更容易形成收藏和转发。",
    "",
    "## 今天就能做的三件事",
    "1. 把热榜里的高频词归类，而不是只看排名。",
    "2. 为每个选题写一句“和读者有什么关系”。",
    "3. 在发布前删掉无法验证的判断，保留可以行动的结论。"
  ].join("\n");

  return {
    title,
    digest: `围绕 ${main.title}，拆解热点背后的传播逻辑和可执行写法。`,
    contentMarkdown,
    contentHtml: markdownToHtml(contentMarkdown),
    coverPrompt: buildFallbackCoverPrompt(main, body.coverStyle),
    tags: ["热点", "内容运营", "公众号"],
    riskTips: ["AI 未配置时生成的是规则草稿，发布前建议人工复核。"]
  };
}

function normalizeArticleDraft(value: Record<string, unknown>, topics: HotTopic[], body: ArticleGenerateBody): ArticleDraft {
  const fallback = fallbackArticle(topics, body);
  const contentMarkdown = stringOrNull(value.contentMarkdown) || stringOrNull(value.markdown) || fallback.contentMarkdown;
  const contentHtml = stringOrNull(value.contentHtml) || stringOrNull(value.html) || markdownToHtml(contentMarkdown);
  return {
    title: (stringOrNull(value.title) || fallback.title).slice(0, 96),
    digest: (stringOrNull(value.digest) || stringOrNull(value.summary) || fallback.digest).slice(0, 120),
    contentMarkdown,
    contentHtml: sanitizeArticleHtml(contentHtml),
    coverPrompt: stringOrNull(value.coverPrompt) || fallback.coverPrompt,
    tags: stringArray(value.tags).slice(0, 8),
    riskTips: stringArray(value.riskTips).slice(0, 6)
  };
}

async function generateCoverImage(ctx: RequestContext, prompt: string): Promise<string | null> {
  const base = await config(ctx, "image.api.baseUrl", ctx.env.IMAGE_API_BASE_URL || "");
  if (!base) {
    return null;
  }
  const apiKey = await config(ctx, "image.api.key", ctx.env.IMAGE_API_KEY || "");
  const model = await config(ctx, "content.cover.imageModel", await config(ctx, "image.api.model", "gpt-image-1"));
  const size = await config(ctx, "content.cover.size", "1024x1024");
  const endpoint = imageEndpoint(base);
  const isChat = endpoint.includes("/chat/completions");
  const response = await fetch(endpoint, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      ...(apiKey ? { authorization: `Bearer ${apiKey}` } : {})
    },
    body: JSON.stringify(isChat
      ? { model, messages: [{ role: "user", content: prompt }] }
      : { model, prompt, size, n: 1 })
  });
  const data = await response.json<any>().catch(() => null);
  if (!response.ok) {
    throw new HttpError(502, upstreamMessage(data, response.status, "image"));
  }
  const item = Array.isArray(data?.data) ? data.data[0] : null;
  if (typeof item?.url === "string" && item.url) {
    return storeRemoteImage(ctx, item.url);
  }
  if (typeof item?.b64_json === "string" && item.b64_json) {
    return storeImageBytes(ctx, base64ToBytes(item.b64_json), "image/png");
  }
  const text = data?.choices?.[0]?.message?.content;
  const url = typeof text === "string" ? extractFirstUrl(text) : null;
  return url ? storeRemoteImage(ctx, url) : null;
}

async function pushArticleToWechat(ctx: RequestContext, article: any, publish: boolean) {
  const token = await wechatAccessToken(ctx);
  const coverMediaId = await ensureWechatCoverMedia(ctx, article);
  const author = await config(ctx, "wechat.author", "");
  const contentSourceUrl = await config(ctx, "wechat.contentSourceUrl", "");
  const response = await fetch(`https://api.weixin.qq.com/cgi-bin/draft/add?access_token=${encodeURIComponent(token)}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      articles: [
        {
          title: article.title,
          author,
          digest: article.digest || "",
          content: sanitizeWechatContent(article.contentHtml),
          content_source_url: contentSourceUrl,
          thumb_media_id: coverMediaId,
          need_open_comment: 0,
          only_fans_can_comment: 0
        }
      ]
    })
  });
  const data = await parseWechatJson(response, publish ? "create publish draft" : "create draft");
  return {
    mediaId: stringOrNull(data.media_id) || "",
    url: stringOrNull(data.url)
  };
}

async function ensureWechatCoverMedia(ctx: RequestContext, article: any): Promise<string> {
  const configured = await config(ctx, "wechat.coverMediaId", "");
  if (configured) {
    return configured;
  }
  if (!article.coverImageUrl) {
    throw new HttpError(400, "cover image is required, or configure wechat.coverMediaId");
  }
  const token = await wechatAccessToken(ctx);
  const image = await loadImageBytes(ctx, article.coverImageUrl);
  const form = new FormData();
  form.set("media", new File([image.bytes], "cover.png", { type: image.contentType || "image/png" }));
  const response = await fetch(`https://api.weixin.qq.com/cgi-bin/material/add_material?access_token=${encodeURIComponent(token)}&type=image`, {
    method: "POST",
    body: form
  });
  const data = await parseWechatJson(response, "upload cover");
  const mediaId = stringOrNull(data.media_id);
  if (!mediaId) {
    throw new HttpError(502, "WeChat returned no media_id for cover");
  }
  return mediaId;
}

async function wechatAccessToken(ctx: RequestContext): Promise<string> {
  const appId = await config(ctx, "wechat.appId", "");
  const appSecret = await config(ctx, "wechat.appSecret", "");
  if (!appId || !appSecret) {
    throw new HttpError(503, "wechat.appId / wechat.appSecret is not configured");
  }
  const cacheKey = `wechat:access_token:${appId}`;
  const cached = await ctx.env.APP_KV.get<{ token: string; expiresAt: number }>(cacheKey, "json");
  if (cached?.token && cached.expiresAt > Date.now() + 60_000) {
    return cached.token;
  }
  const response = await fetch(
    `https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=${encodeURIComponent(appId)}&secret=${encodeURIComponent(appSecret)}`
  );
  const data = await parseWechatJson(response, "get access token");
  const token = stringOrNull(data.access_token);
  if (!token) {
    throw new HttpError(502, "WeChat returned no access_token");
  }
  const expiresIn = Number(data.expires_in ?? 7200);
  await ctx.env.APP_KV.put(cacheKey, JSON.stringify({ token, expiresAt: Date.now() + Math.max(60, expiresIn - 300) * 1000 }), {
    expirationTtl: Math.max(60, expiresIn - 300)
  });
  return token;
}

async function parseWechatJson(response: Response, action: string): Promise<Record<string, unknown>> {
  const data = await response.json<Record<string, unknown>>().catch(() => ({} as Record<string, unknown>));
  const errcode = Number(data.errcode ?? 0);
  if (!response.ok || errcode !== 0) {
    const errmsg = stringOrNull(data.errmsg) || `HTTP ${response.status}`;
    throw new HttpError(502, `WeChat ${action} failed: ${errmsg}`);
  }
  return data;
}

async function collectDefaultTopics(ctx: RequestContext): Promise<HotTopic[]> {
  const sources = await hotSources(ctx);
  const settled = await Promise.allSettled(sources.slice(0, 3).map((source) => fetchHotSource(source, 5)));
  const topics = settled.flatMap((item) => item.status === "fulfilled" ? item.value : []);
  return topics.length ? topics : FALLBACK_TOPICS;
}

async function hotSources(ctx: RequestContext): Promise<HotSource[]> {
  const configured = await config(ctx, "content.hot.sources", "");
  if (!configured.trim()) {
    return DEFAULT_HOT_SOURCES;
  }
  const parsed = parseJsonObject(configured);
  if (Array.isArray(parsed)) {
    const sources = parsed
      .map((item, index) => {
        const record = asRecord(item);
        const url = stringOrNull(record.url);
        if (!url) return null;
        return {
          id: stringOrNull(record.id) || `source-${index + 1}`,
          name: stringOrNull(record.name) || stringOrNull(record.id) || `Source ${index + 1}`,
          url
        };
      })
      .filter((item): item is HotSource => Boolean(item));
    if (sources.length) return sources;
  }
  const lineSources = configured
    .split("\n")
    .map((line, index) => {
      const [name, url] = line.split("|").map((item) => item?.trim());
      return url ? { id: `custom-${index + 1}`, name: name || `Source ${index + 1}`, url } : null;
    })
    .filter((item): item is HotSource => Boolean(item));
  return lineSources.length ? lineSources : DEFAULT_HOT_SOURCES;
}

async function fetchHotSource(source: HotSource, limit: number): Promise<HotTopic[]> {
  const response = await fetch(source.url, {
    headers: { "user-agent": "website-content-factory/1.0" }
  });
  const payload = await response.json<unknown>().catch(() => null);
  if (!response.ok || !payload) {
    return [];
  }
  return extractHotRows(payload)
    .slice(0, limit)
    .map((row, index) => hotTopicView(row, source, index + 1))
    .filter((item): item is HotTopic => Boolean(item));
}

function extractHotRows(payload: unknown): unknown[] {
  if (Array.isArray(payload)) return payload;
  const record = asRecord(payload);
  for (const key of ["data", "items", "list", "rows", "result", "hotList", "news"]) {
    const value = record[key];
    if (Array.isArray(value)) return value;
    const nested = asRecord(value);
    for (const nestedKey of ["data", "items", "list", "rows"]) {
      if (Array.isArray(nested[nestedKey])) return nested[nestedKey] as unknown[];
    }
  }
  return [];
}

function hotTopicView(row: unknown, source: HotSource, rank: number): HotTopic | null {
  const record = asRecord(row);
  const title = firstString(record, ["title", "name", "word", "keyword", "query", "desc", "display_query"]);
  if (!title) return null;
  return {
    id: `${source.id}:${rank}:${hashText(title).slice(0, 8)}`,
    source: source.id,
    sourceName: source.name,
    rank: Number(record.rank ?? record.index ?? rank) || rank,
    title,
    url: firstString(record, ["url", "link", "mobileUrl", "pcUrl", "href"]) || null,
    hot: firstString(record, ["hot", "heat", "score", "hotValue", "views", "metrics"]) || null,
    summary: firstString(record, ["summary", "excerpt", "description", "desc"]) || null,
    capturedAt: new Date().toISOString()
  };
}

async function articleById(ctx: RequestContext, userId: number, id: number) {
  const row = await ctx.env.DB.prepare("SELECT * FROM content_article WHERE id = ? AND user_id = ?").bind(id, userId).first();
  if (!row) {
    throw new HttpError(404, "Article not found");
  }
  return articleView(row);
}

function articleView(row: any) {
  return {
    id: row.id,
    title: row.title,
    digest: row.digest,
    contentMarkdown: row.content_markdown,
    contentHtml: row.content_html,
    coverPrompt: row.cover_prompt,
    coverImageUrl: row.cover_image_url,
    topics: parseJsonArray(row.topics_json),
    tags: parseJsonArray(row.tags_json),
    riskTips: parseJsonArray(row.risk_tips_json),
    model: row.model,
    status: row.status,
    wechatMediaId: row.wechat_media_id,
    wechatPublishId: row.wechat_publish_id,
    wechatUrl: row.wechat_url,
    errorMessage: row.error_message,
    createdAt: row.created_at,
    updatedAt: row.updated_at
  };
}

async function ensureContentTables(ctx: RequestContext): Promise<void> {
  await ctx.env.DB.prepare(
    `CREATE TABLE IF NOT EXISTS content_article (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER NOT NULL,
      title TEXT NOT NULL,
      digest TEXT,
      content_markdown TEXT,
      content_html TEXT NOT NULL,
      cover_prompt TEXT,
      cover_image_url TEXT,
      topics_json TEXT,
      tags_json TEXT,
      risk_tips_json TEXT,
      model TEXT,
      status TEXT NOT NULL DEFAULT 'DRAFT',
      wechat_media_id TEXT,
      wechat_publish_id TEXT,
      wechat_url TEXT,
      error_message TEXT,
      created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    )`
  ).run();
  await ctx.env.DB.prepare(
    "CREATE INDEX IF NOT EXISTS idx_content_article_user_updated ON content_article(user_id, updated_at DESC, id DESC)"
  ).run();
}

function buildCoverPrompt(draft: ArticleDraft, style?: string) {
  return `${draft.coverPrompt}\n微信公众号封面，比例 2.35:1，中文互联网内容风格，主题清晰，视觉干净，有传播感。${style ? `风格：${style}` : ""}`;
}

function buildFallbackCoverPrompt(topic: HotTopic, style?: string) {
  return `围绕“${topic.title}”创作一张公众号封面图，画面包含热榜数据、趋势洞察、内容创作工作台元素，避免真实人物肖像。${style ? `风格：${style}` : ""}`;
}

function imageEndpoint(base: string): string {
  const trimmed = base.trim().replace(/\/$/, "");
  if (trimmed.includes("/images/generations") || trimmed.includes("/chat/completions")) return trimmed;
  if (trimmed.endsWith("/v1")) return `${trimmed}/images/generations`;
  return `${trimmed}/images/generations`;
}

function chatEndpoint(base: string): string {
  const trimmed = base.trim().replace(/\/$/, "");
  if (trimmed.includes("/chat/completions")) return trimmed;
  if (trimmed.endsWith("/v1")) return `${trimmed}/chat/completions`;
  return `${trimmed}/chat/completions`;
}

async function storeRemoteImage(ctx: RequestContext, url: string): Promise<string> {
  const response = await fetch(url);
  if (!response.ok) {
    return url;
  }
  const bytes = new Uint8Array(await response.arrayBuffer());
  const contentType = response.headers.get("content-type") || "image/png";
  return storeImageBytes(ctx, bytes, contentType);
}

async function storeImageBytes(ctx: RequestContext, bytes: Uint8Array, contentType: string): Promise<string> {
  const bucket = ctx.env.R2_BUCKET;
  if (!bucket) {
    return `data:${contentType};base64,${bytesToBase64(bytes)}`;
  }
  const ext = contentType.includes("jpeg") ? "jpg" : contentType.includes("webp") ? "webp" : "png";
  const filename = `${crypto.randomUUID()}.${ext}`;
  await bucket.put(`content/${filename}`, bytes, { httpMetadata: { contentType } });
  return ctx.env.PUBLIC_R2_BASE_URL
    ? `${ctx.env.PUBLIC_R2_BASE_URL.replace(/\/$/, "")}/content/${filename}`
    : `/api/v1/content/assets/${filename}`;
}

async function loadImageBytes(ctx: RequestContext, input: string): Promise<{ bytes: Uint8Array; contentType: string }> {
  if (input.startsWith("data:")) {
    const match = input.match(/^data:([^;,]+)?;base64,(.*)$/);
    if (!match) throw new HttpError(400, "Invalid data URL cover image");
    return { bytes: base64ToBytes(match[2]), contentType: match[1] || "image/png" };
  }
  const url = input.startsWith("/") ? `${new URL(ctx.request.url).origin}${input}` : input;
  const response = await fetch(url);
  if (!response.ok) {
    throw new HttpError(502, `Cover image download failed: HTTP ${response.status}`);
  }
  return {
    bytes: new Uint8Array(await response.arrayBuffer()),
    contentType: response.headers.get("content-type") || "image/png"
  };
}

function sanitizeArticleHtml(html: string): string {
  return html
    .replace(/<script[\s\S]*?>[\s\S]*?<\/script>/gi, "")
    .replace(/<style[\s\S]*?>[\s\S]*?<\/style>/gi, "")
    .replace(/\son\w+="[^"]*"/gi, "");
}

function sanitizeWechatContent(html: string): string {
  return sanitizeArticleHtml(html).replace(/<h1([\s>])/gi, "<h2$1").replace(/<\/h1>/gi, "</h2>");
}

function markdownToHtml(markdown: string): string {
  const lines = markdown.split(/\r?\n/);
  const html: string[] = [];
  let listOpen = false;
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed) {
      if (listOpen) {
        html.push("</ul>");
        listOpen = false;
      }
      continue;
    }
    if (trimmed.startsWith("# ")) {
      if (listOpen) {
        html.push("</ul>");
        listOpen = false;
      }
      html.push(`<h1>${escapeHtml(trimmed.slice(2))}</h1>`);
    } else if (trimmed.startsWith("## ")) {
      if (listOpen) {
        html.push("</ul>");
        listOpen = false;
      }
      html.push(`<h2>${escapeHtml(trimmed.slice(3))}</h2>`);
    } else if (trimmed.startsWith("- ")) {
      if (!listOpen) {
        html.push("<ul>");
        listOpen = true;
      }
      html.push(`<li>${escapeHtml(trimmed.slice(2))}</li>`);
    } else {
      if (listOpen) {
        html.push("</ul>");
        listOpen = false;
      }
      html.push(`<p>${escapeHtml(trimmed)}</p>`);
    }
  }
  if (listOpen) html.push("</ul>");
  return html.join("\n");
}

function htmlToPlainText(html: string): string {
  return html.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
}

function normalizeSelectedTopics(value: unknown): HotTopic[] {
  if (!Array.isArray(value)) return [];
  return value.map((item, index) => {
    const record = asRecord(item);
    const title = stringOrNull(record.title);
    if (!title) return null;
    return {
      id: stringOrNull(record.id) || `manual:${index + 1}:${hashText(title).slice(0, 8)}`,
      source: stringOrNull(record.source) || "manual",
      sourceName: stringOrNull(record.sourceName) || "手动选题",
      rank: Number(record.rank ?? index + 1) || index + 1,
      title,
      url: stringOrNull(record.url),
      hot: stringOrNull(record.hot),
      summary: stringOrNull(record.summary),
      capturedAt: stringOrNull(record.capturedAt) || new Date().toISOString()
    };
  }).filter((item): item is HotTopic => Boolean(item));
}

function parseJsonObject(value: unknown): any {
  if (typeof value !== "string") return value;
  const cleaned = value.trim().replace(/^```(?:json)?/i, "").replace(/```$/i, "").trim();
  try {
    return JSON.parse(cleaned);
  } catch {
    const match = cleaned.match(/\{[\s\S]*\}/);
    if (!match) return null;
    try {
      return JSON.parse(match[0]);
    } catch {
      return null;
    }
  }
}

function parseJsonArray(value: unknown): unknown[] {
  const parsed = parseJsonObject(value);
  return Array.isArray(parsed) ? parsed : [];
}

function asRecord(value: unknown): Record<string, any> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, any> : {};
}

function firstString(record: Record<string, any>, keys: string[]): string {
  for (const key of keys) {
    const value = record[key];
    if (value !== undefined && value !== null && String(value).trim()) {
      return String(value).trim();
    }
  }
  return "";
}

function stringOrNull(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function requiredString(value: unknown, name: string): string {
  const text = stringOrNull(value);
  if (!text) throw new HttpError(400, `${name} is required`);
  return text;
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.map((item) => String(item || "").trim()).filter(Boolean) : [];
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function extractFirstUrl(value: string): string | null {
  const match = value.match(/https?:\/\/[^\s"'<>]+/);
  return match?.[0] ?? null;
}

function hashText(value: string): string {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) {
    hash = ((hash << 5) - hash + value.charCodeAt(index)) | 0;
  }
  return Math.abs(hash).toString(36);
}

function base64ToBytes(value: string): Uint8Array {
  const binary = atob(value.replace(/^data:[^,]+,/, ""));
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  const chunkSize = 0x8000;
  for (let index = 0; index < bytes.length; index += chunkSize) {
    binary += String.fromCharCode(...bytes.slice(index, index + chunkSize));
  }
  return btoa(binary);
}

function upstreamMessage(data: any, status: number, label: string): string {
  const message = data?.error?.message || data?.message || data?.msg || (typeof data?.error === "string" ? data.error : "");
  return message
    ? `${label} upstream returned ${status}: ${message}`
    : `${label} upstream returned HTTP ${status}`;
}

async function config(ctx: RequestContext, key: string, fallback: string): Promise<string> {
  const row = await ctx.env.DB.prepare("SELECT config_value FROM sys_config WHERE config_key = ?").bind(key).first<{ config_value: string }>();
  return row?.config_value || fallback;
}
