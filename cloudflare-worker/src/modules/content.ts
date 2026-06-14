import { count, pageOf } from "../db";
import { emptyOk, intParam, ok, readJson, requireAdmin } from "../http";
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

type ContentCategory = "emotion_psychology" | "history_philosophy" | "society_livelihood";
type ContentLayoutTheme = "clean" | "warm" | "magazine";
type ContentImageMode = "generate" | "fetch" | "none";
type ContentResearchDepth = "quick" | "standard" | "deep";

interface ArticleGenerateBody {
  topics?: HotTopic[];
  topicIds?: string[];
  topic?: string;
  category?: ContentCategory;
  layoutTheme?: ContentLayoutTheme;
  imageMode?: ContentImageMode;
  researchEnabled?: boolean;
  researchDepth?: ContentResearchDepth;
  searchQueries?: string[];
  autoWechatDraft?: boolean;
  autoPublish?: boolean;
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

interface ResearchSource {
  title: string;
  url: string;
  snippet: string | null;
  content: string | null;
  sourceName: string | null;
}

interface CategoryProfile {
  id: ContentCategory;
  label: string;
  angle: string;
  audience: string;
  tone: string;
  coreConcern: string;
  promptFocus: string[];
  riskBoundaries: string[];
  keywords: string[];
  coverKeywords: string;
  tags: string[];
  color: string;
  accent: string;
  softBg: string;
}

const DEFAULT_CATEGORY: ContentCategory = "emotion_psychology";
const DEFAULT_LAYOUT_THEME: ContentLayoutTheme = "clean";
const DEFAULT_IMAGE_MODE: ContentImageMode = "generate";

const CATEGORY_PROFILES: Record<ContentCategory, CategoryProfile> = {
  emotion_psychology: {
    id: "emotion_psychology",
    label: "情感心理",
    angle: "从真实关系、情绪需求和自我成长切入，给读者可复盘、可练习的理解框架",
    audience: "正在处理亲密关系、家庭沟通、职场情绪和自我成长的公众号读者",
    tone: "温暖、清醒、克制，不鸡汤、不诊断",
    coreConcern: "情绪、关系和自我理解",
    promptFocus: [
      "用生活场景开头，让读者先看见自己",
      "解释心理机制时保持通俗，不做医学诊断",
      "结尾给出可执行的沟通或自我照顾动作"
    ],
    riskBoundaries: [
      "避免把复杂心理问题简单归因",
      "避免使用治疗、诊断、痊愈等医疗承诺",
      "涉及亲密关系冲突时避免煽动对立"
    ],
    keywords: ["情绪", "心理", "婚姻", "恋爱", "亲密关系", "家庭", "沟通", "焦虑", "成长", "疗愈", "人格", "边界感"],
    coverKeywords: "柔和人文、情绪曲线、关系线索、温暖但不甜腻",
    tags: ["情感心理", "关系", "自我成长"],
    color: "#b8326f",
    accent: "#f08ab8",
    softBg: "#fff4f8"
  },
  history_philosophy: {
    id: "history_philosophy",
    label: "历史哲学",
    angle: "从历史人物、事件脉络或思想命题切入，把旧问题讲成今天仍然有用的判断力",
    audience: "喜欢历史故事、思想辨析和长期主义思考的公众号读者",
    tone: "沉稳、有证据、有思辨感，避免故作玄虚",
    coreConcern: "历史经验、思想命题和现实判断",
    promptFocus: [
      "先交代人物、时代或概念背景，再进入观点",
      "区分史实、解释和作者判断",
      "用今天的生活问题承接历史经验"
    ],
    riskBoundaries: [
      "不要编造史料、引文和具体年代",
      "不把复杂历史事件写成单一阴谋论",
      "涉及历史评价时保留多角度表述"
    ],
    keywords: ["历史", "哲学", "人物", "王朝", "战争", "思想", "文明", "古代", "近代", "孔子", "庄子", "苏格拉底", "权力"],
    coverKeywords: "纸本文献、时间轴、古籍纹理、克制高级的历史感",
    tags: ["历史哲学", "思想", "长期主义"],
    color: "#7c4a21",
    accent: "#d59f55",
    softBg: "#fff8ec"
  },
  society_livelihood: {
    id: "society_livelihood",
    label: "社会民生",
    angle: "从公共议题背后的生活成本、教育就业、城市生活和普通人处境切入",
    audience: "关心现实生活、公共议题和社会变化的公众号读者",
    tone: "客观、克制、有温度，不煽动",
    coreConcern: "公共议题与普通人的日常处境",
    promptFocus: [
      "用可核验的信息和生活场景建立问题",
      "把宏观议题落到普通人的选择和影响",
      "提供审慎判断，不制造恐慌"
    ],
    riskBoundaries: [
      "避免传播未证实消息和极端判断",
      "避免地域、职业、群体对立表达",
      "涉及政策和公共事件时保留信息来源线索"
    ],
    keywords: ["社会", "民生", "就业", "教育", "房价", "城市", "消费", "医疗", "养老", "收入", "政策", "公共", "年轻人"],
    coverKeywords: "城市街景、民生数据、公共议题、清晰可信的新闻杂志感",
    tags: ["社会民生", "公共议题", "生活观察"],
    color: "#0f766e",
    accent: "#54b9ad",
    softBg: "#effdfa"
  }
};

const CATEGORY_FALLBACK_TOPICS: Record<ContentCategory, Array<{ title: string; hot: string; summary: string }>> = {
  emotion_psychology: [
    {
      title: "为什么很多关系不是不爱了，而是不会好好说话",
      hot: "关系沟通",
      summary: "亲密关系和家庭沟通相关话题长期有讨论度，适合从情绪表达和边界感切入。"
    },
    {
      title: "成年人最容易忽视的情绪成本",
      hot: "情绪管理",
      summary: "职场压力、家庭责任和自我期待叠加，让情绪消耗成为高共鸣选题。"
    },
    {
      title: "边界感不是冷漠，而是一种稳定关系的能力",
      hot: "自我成长",
      summary: "边界感、讨好型人格、关系修复等关键词持续受到关注。"
    }
  ],
  history_philosophy: [
    {
      title: "历史上真正改变局势的，往往不是最响亮的人",
      hot: "历史人物",
      summary: "适合用人物命运和时代结构讨论选择、判断和长期影响。"
    },
    {
      title: "一个古老问题：人为什么总在确定性里失去自由",
      hot: "哲学命题",
      summary: "从思想命题切入现实焦虑，兼顾故事性和思辨性。"
    },
    {
      title: "读历史最重要的不是记结论，而是训练判断力",
      hot: "历史方法",
      summary: "可把历史事件转化为当代生活中的决策方法。"
    }
  ],
  society_livelihood: [
    {
      title: "普通人越来越关心的，不是宏大叙事，而是生活确定性",
      hot: "民生观察",
      summary: "就业、教育、消费和城市生活成本是持续高关注方向。"
    },
    {
      title: "年轻人的消费变化，背后是对风险的重新计算",
      hot: "消费趋势",
      summary: "适合从社会变化落到普通人的预算、选择和安全感。"
    },
    {
      title: "一座城市是否友好，最终会体现在普通人的日常里",
      hot: "城市生活",
      summary: "公共服务、通勤、住房和生活便利性都能生成有温度的民生文章。"
    }
  ]
};

const DEFAULT_HOT_SOURCES: HotSource[] = [
  { id: "weibo", name: "微博", url: "https://api-hot.imsyy.top/weibo" },
  { id: "zhihu", name: "知乎", url: "https://api-hot.imsyy.top/zhihu" },
  { id: "toutiao", name: "头条", url: "https://api-hot.imsyy.top/toutiao" },
  { id: "baidu", name: "百度", url: "https://api-hot.imsyy.top/baidu" }
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
  const category = normalizeCategory(ctx.url.searchParams.get("category"));
  const sources = await hotSources(ctx, category);
  const limit = intParam(ctx.url, "limit", 12, 1, 50);
  const settled = await Promise.allSettled(sources.map((source) => fetchHotSource(source, limit)));
  const topics = settled
    .flatMap((item) => item.status === "fulfilled" ? item.value : [])
    .slice(0, Math.max(limit, sources.length * limit));
  const rankedTopics = rankTopicsByCategory(topics, category);
  const items = rankedTopics.length
    ? [...rankedTopics, ...fallbackTopics(category)].slice(0, limit)
    : fallbackTopics(category).slice(0, limit);

  return ok({
    category,
    categories: categoryOptions(),
    capturedAt: new Date().toISOString(),
    sources: sources.map(({ id, name }) => ({ id, name })),
    items
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
  const category = normalizeCategory(body.category);
  const layoutTheme = normalizeLayoutTheme(body.layoutTheme);
  const imageMode = normalizeImageMode(body.imageMode, body.generateCover);
  const researchDepth = normalizeResearchDepth(body.researchDepth);
  const normalizedBody: ArticleGenerateBody = { ...body, category, layoutTheme, imageMode, researchDepth };
  const manualTopic = stringOrNull(body.topic);
  const topics = manualTopic ? [manualTopicView(manualTopic, category)] : normalizeSelectedTopics(body.topics).slice(0, 8);
  const selectedTopics = topics.length ? topics : (await collectDefaultTopics(ctx, category)).slice(0, 5);
  const model = body.model || await config(ctx, "content.article.model", await config(ctx, "ai.chat.defaultModel", "gpt-4.1-mini"));
  let researchSources: ResearchSource[] = [];
  let researchError: string | null = null;
  if (body.researchEnabled !== false) {
    try {
      researchSources = await collectResearch(ctx, selectedTopics, normalizedBody, category);
    } catch (error) {
      researchError = error instanceof Error ? error.message : "web research failed";
    }
  }

  let draft: ArticleDraft;
  let articleError: string | null = null;
  try {
    draft = await createArticleDraft(ctx, selectedTopics, normalizedBody, model, researchSources);
  } catch (error) {
    articleError = error instanceof Error ? error.message : "article generation failed";
    draft = fallbackArticle(selectedTopics, normalizedBody, researchSources);
  }
  draft = formatDraftForWechat(draft, category, layoutTheme);
  let coverImageUrl: string | null = null;
  let coverError: string | null = null;
  if (imageMode !== "none") {
    try {
      coverImageUrl = await resolveCoverImage(ctx, draft, selectedTopics, normalizedBody, category);
    } catch (error) {
      coverError = error instanceof Error ? error.message : "cover generation failed";
    }
  }

  const result = await ctx.env.DB.prepare(
    `INSERT INTO content_article(
      user_id, title, digest, content_markdown, content_html, cover_prompt, cover_image_url,
      topics_json, tags_json, risk_tips_json, model, category, layout_theme, image_mode,
      automation_json, status, error_message
    ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?)`
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
    category,
    layoutTheme,
    imageMode,
    JSON.stringify({
      requestedDraft: Boolean(body.autoWechatDraft),
      requestedPublish: Boolean(body.autoPublish),
      researchEnabled: body.researchEnabled !== false,
      researchDepth,
      researchError,
      researchSources: researchSources.map((item) => ({
        title: item.title,
        url: item.url,
        snippet: item.snippet,
        sourceName: item.sourceName
      })),
      createdAt: new Date().toISOString()
    }),
    [researchError, articleError, coverError].filter(Boolean).join("\n") || null
  ).run();

  let article = await articleById(ctx, user.id, Number(result.meta.last_row_id));
  if (body.autoPublish || body.autoWechatDraft) {
    article = await runGenerationAutomation(ctx, user.id, article, body.autoPublish ? "publish" : "draft");
  }

  return ok(article);
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

export async function deleteArticle(ctx: RequestContext): Promise<Response> {
  const user = requireAdmin(ctx);
  await ensureContentTables(ctx);
  const id = Number(ctx.params.id);
  await articleById(ctx, user.id, id);
  await ctx.env.DB.prepare("DELETE FROM content_article WHERE id = ? AND user_id = ?").bind(id, user.id).run();
  return emptyOk();
}

export async function createWechatDraft(ctx: RequestContext): Promise<Response> {
  const user = requireAdmin(ctx);
  await ensureContentTables(ctx);
  const article = await articleById(ctx, user.id, Number(ctx.params.id));
  const result = await createWechatDraftForArticle(ctx, user.id, article);
  return ok({ article: await articleById(ctx, user.id, article.id), draft: result });
}

export async function publishWechat(ctx: RequestContext): Promise<Response> {
  const user = requireAdmin(ctx);
  await ensureContentTables(ctx);
  const article = await articleById(ctx, user.id, Number(ctx.params.id));
  const result = await publishWechatForArticle(ctx, user.id, article);
  return ok({ article: await articleById(ctx, user.id, article.id), ...result });
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

async function createArticleDraft(
  ctx: RequestContext,
  topics: HotTopic[],
  body: ArticleGenerateBody,
  model: string,
  researchSources: ResearchSource[] = []
): Promise<ArticleDraft> {
  const base = await config(ctx, "ai.chat.baseUrl", ctx.env.AI_CHAT_BASE_URL || "");
  const apiKey = await config(ctx, "ai.chat.apiKey", ctx.env.AI_CHAT_API_KEY || "");
  const category = normalizeCategory(body.category);
  const profile = CATEGORY_PROFILES[category];
  if (!base) {
    return fallbackArticle(topics, body, researchSources);
  }

  const prompt = [
    "你是资深微信公众号主编和爆款选题策划。",
    `当前公众号栏目：${profile.label}。`,
    "任务：围绕给定话题，先消化网页搜索资料，再写一篇可直接发公众号的原创爆文正文。",
    "注意：最终 contentMarkdown/contentHtml 必须是完整公众号正文，不要输出选题方案、写作提纲、素材清单或运营建议。",
    "爆文不是标题党，而是：开头强共鸣、有冲突问题、观点有反常识、论证有资料支撑、段落适合手机阅读、结尾让读者愿意转发或留言。",
    "硬性要求：不得编造事实、数据、人物经历、政策和史料；网页资料不够时必须降级为观点分析，并在 riskTips 说明。",
    "标题要有传播力但不夸大；正文不要写成资料汇编；把搜索资料消化成观点、故事线和可读表达。",
    "文章结构建议：痛点/场景开头 → 抛出核心矛盾 → 3-5 个小标题展开 → 给出有记忆点的金句/判断 → 结尾收束到读者处境。",
    "正文末尾可以用简短“参考资料”列出 3-6 个来源标题，不要堆 URL。",
    `栏目写作重点：${profile.promptFocus.join("；")}。`,
    `风险边界：${profile.riskBoundaries.join("；")}。`,
    "JSON 字段：title, digest, contentMarkdown, contentHtml, coverPrompt, tags, riskTips。",
    "contentMarkdown 至少 1200 字；contentHtml 使用 p/h2/blockquote/ul/li/strong 标签，不要包含 script/style。",
    "",
    JSON.stringify({
      topics,
      mainTopic: body.topic || topics[0]?.title || "",
      category: profile.label,
      angle: body.angle || profile.angle,
      audience: body.audience || profile.audience,
      tone: body.tone || profile.tone,
      length: body.length || "standard",
      layoutTheme: body.layoutTheme || DEFAULT_LAYOUT_THEME,
      researchSources: researchSources.map((item) => ({
        title: item.title,
        url: item.url,
        snippet: item.snippet,
        content: item.content
      }))
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
    return fallbackArticle(topics, body, researchSources);
  }
  return normalizeArticleDraft(parseJsonObject(content) ?? {}, topics, body, researchSources);
}

function fallbackArticle(topics: HotTopic[], body: ArticleGenerateBody, researchSources: ResearchSource[] = []): ArticleDraft {
  const category = normalizeCategory(body.category);
  const profile = CATEGORY_PROFILES[category];
  const main = topics[0] ?? fallbackTopics(category)[0];
  const title = `围绕“${main.title}”，真正值得写的不是热闹`;
  const sourceItems = researchSources.length
    ? researchSources.slice(0, 6).map((source) => `- ${source.title}：${source.snippet || source.content?.slice(0, 90) || source.url}`).join("\n")
    : topics.map((topic) => `- ${topic.sourceName} #${topic.rank}：${topic.title}${topic.hot ? `（${topic.hot}）` : ""}`).join("\n");
  const contentMarkdown = [
    `# ${title}`,
    "",
    `一个话题能不能写成公众号爆文，关键不在于它排第几，而在于它能不能说出读者心里已经有、但还没被清楚表达出来的东西。围绕“${main.title}”，真正值得抓住的是它背后的${profile.coreConcern}。`,
    "",
    "## 先看资料里出现了什么",
    sourceItems,
    "",
    "## 这篇文章真正的矛盾",
    `表面上，这是一个关于“${main.title}”的话题；更深一层，它讨论的是普通人如何在变化里重新理解自己的处境。放在「${profile.label}」栏目里，文章应该先写具体生活场景，再写背后的结构性问题。`,
    "",
    "## 爆文写法不是夸张，而是让读者点头",
    "开头要让读者觉得“这说的是我”；中段要给一个不同于常识的解释；结尾要把复杂判断落到一句能被转发的话。信息只是原料，观点和表达才是文章被读完的原因。",
    "",
    "## 可以这样收束",
    `不要把读者推向情绪，而是给他一个更清楚的理解：他遇到的不是孤立问题，而是很多人正在共同面对的${profile.coreConcern}变化。`,
    "",
    "## 参考资料",
    sourceItems
  ].join("\n");

  return {
    title,
    digest: `围绕 ${main.title}，结合网页资料梳理可写成公众号爆文的核心矛盾。`,
    contentMarkdown,
    contentHtml: markdownToHtml(contentMarkdown),
    coverPrompt: buildFallbackCoverPrompt(main, body.coverStyle, category),
    tags: uniqueStrings([...profile.tags, "热点", "公众号"]),
    riskTips: uniqueStrings([
      researchSources.length ? "当前为规则兜底草稿，已使用网页资料摘要，但仍建议人工复核来源。" : "未获得足够网页资料，当前为规则兜底草稿。",
      ...profile.riskBoundaries
    ])
  };
}

function normalizeArticleDraft(value: Record<string, unknown>, topics: HotTopic[], body: ArticleGenerateBody, researchSources: ResearchSource[] = []): ArticleDraft {
  const fallback = fallbackArticle(topics, body, researchSources);
  const contentMarkdown = stringOrNull(value.contentMarkdown) || stringOrNull(value.markdown) || fallback.contentMarkdown;
  const contentHtml = stringOrNull(value.contentHtml) || stringOrNull(value.html) || markdownToHtml(contentMarkdown);
  return {
    title: (stringOrNull(value.title) || fallback.title).slice(0, 96),
    digest: (stringOrNull(value.digest) || stringOrNull(value.summary) || fallback.digest).slice(0, 120),
    contentMarkdown,
    contentHtml: sanitizeArticleHtml(contentHtml),
    coverPrompt: stringOrNull(value.coverPrompt) || fallback.coverPrompt,
    tags: uniqueStrings([...CATEGORY_PROFILES[normalizeCategory(body.category)].tags, ...stringArray(value.tags)]).slice(0, 8),
    riskTips: uniqueStrings([...stringArray(value.riskTips), ...CATEGORY_PROFILES[normalizeCategory(body.category)].riskBoundaries]).slice(0, 8)
  };
}

async function resolveCoverImage(
  ctx: RequestContext,
  draft: ArticleDraft,
  topics: HotTopic[],
  body: ArticleGenerateBody,
  category: ContentCategory
): Promise<string | null> {
  const imageMode = normalizeImageMode(body.imageMode, body.generateCover);
  if (imageMode === "none") {
    return null;
  }
  if (imageMode === "fetch") {
    const fetched = await fetchCoverImage(ctx, topics, body, category);
    if (fetched) {
      return fetched;
    }
  }
  return generateCoverImage(ctx, buildCoverPrompt(draft, body.coverStyle, category));
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

async function fetchCoverImage(ctx: RequestContext, topics: HotTopic[], body: ArticleGenerateBody, category: ContentCategory): Promise<string | null> {
  const fromTopic = await fetchTopicImage(ctx, topics);
  if (fromTopic) {
    return fromTopic;
  }

  const template = await config(ctx, "content.image.searchBaseUrl", "");
  if (!template) {
    return null;
  }
  const imageUrl = buildConfiguredImageUrl(template, topics[0], body, category);
  return imageUrl ? storeRemoteImage(ctx, imageUrl) : null;
}

async function fetchTopicImage(ctx: RequestContext, topics: HotTopic[]): Promise<string | null> {
  for (const topic of topics) {
    if (!topic.url || !/^https?:\/\//i.test(topic.url)) {
      continue;
    }
    try {
      const response = await fetch(topic.url, {
        headers: { "user-agent": "website-content-factory/1.0 (+wechat-content)" }
      });
      if (!response.ok) {
        continue;
      }
      const contentType = response.headers.get("content-type") || "";
      if (contentType.startsWith("image/")) {
        return storeRemoteImage(ctx, topic.url);
      }
      if (!contentType.includes("text/html") && !contentType.includes("application/xhtml")) {
        continue;
      }
      const html = await response.text();
      const imageUrl = extractPageImageUrl(html, topic.url);
      if (imageUrl) {
        return storeRemoteImage(ctx, imageUrl);
      }
    } catch {
      continue;
    }
  }
  return null;
}

async function pushArticleToWechat(ctx: RequestContext, article: any, publish: boolean) {
  const token = await wechatAccessToken(ctx);
  const coverMediaId = await ensureWechatCoverMedia(ctx, article);
  const content = await prepareWechatContent(ctx, article, token);
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
          content,
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

async function prepareWechatContent(ctx: RequestContext, article: any, token: string): Promise<string> {
  const content = sanitizeWechatContent(article.contentHtml, normalizeCategory(article.category), normalizeLayoutTheme(article.layoutTheme));
  return uploadWechatInlineImages(ctx, content, token);
}

async function uploadWechatInlineImages(ctx: RequestContext, html: string, token: string): Promise<string> {
  const imageUrls = uniqueStrings(
    Array.from(html.matchAll(/<img\b[^>]*\bsrc=["']([^"']+)["'][^>]*>/gi))
      .map((match) => match[1])
      .filter((url) => Boolean(url) && !url.includes("mmbiz.qpic.cn"))
  ).slice(0, 12);
  let nextHtml = html;
  for (const imageUrl of imageUrls) {
    try {
      const image = await loadImageBytes(ctx, imageUrl);
      const form = new FormData();
      form.set("media", new File([image.bytes], "inline.png", { type: image.contentType || "image/png" }));
      const response = await fetch(`https://api.weixin.qq.com/cgi-bin/media/uploadimg?access_token=${encodeURIComponent(token)}`, {
        method: "POST",
        body: form
      });
      const data = await parseWechatJson(response, "upload content image");
      const wechatUrl = stringOrNull(data.url);
      if (wechatUrl) {
        nextHtml = replaceImageSource(nextHtml, imageUrl, wechatUrl);
      }
    } catch {
      continue;
    }
  }
  return nextHtml;
}

async function createWechatDraftForArticle(ctx: RequestContext, userId: number, article: any) {
  const result = await pushArticleToWechat(ctx, article, false);
  await ctx.env.DB.prepare(
    `UPDATE content_article
     SET status = 'WECHAT_DRAFT', wechat_media_id = ?, wechat_url = ?, error_message = NULL,
         automation_json = ?, updated_at = CURRENT_TIMESTAMP
     WHERE id = ? AND user_id = ?`
  ).bind(
    result.mediaId,
    result.url,
    JSON.stringify({ lastAction: "draft", ok: true, at: new Date().toISOString() }),
    article.id,
    userId
  ).run();
  return result;
}

async function publishWechatForArticle(ctx: RequestContext, userId: number, article: any) {
  const draft = await pushArticleToWechat(ctx, article, true);
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
     SET status = 'PUBLISHED', wechat_media_id = ?, wechat_publish_id = ?, wechat_url = ?,
         error_message = NULL, automation_json = ?, updated_at = CURRENT_TIMESTAMP
     WHERE id = ? AND user_id = ?`
  ).bind(
    draft.mediaId,
    publishId,
    draft.url,
    JSON.stringify({ lastAction: "publish", ok: true, publishId, at: new Date().toISOString() }),
    article.id,
    userId
  ).run();
  return { draft, publish: data };
}

async function runGenerationAutomation(ctx: RequestContext, userId: number, article: any, action: "draft" | "publish") {
  try {
    if (action === "publish") {
      await publishWechatForArticle(ctx, userId, article);
    } else {
      await createWechatDraftForArticle(ctx, userId, article);
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : "wechat automation failed";
    await ctx.env.DB.prepare(
      `UPDATE content_article
       SET error_message = ?, automation_json = ?, updated_at = CURRENT_TIMESTAMP
       WHERE id = ? AND user_id = ?`
    ).bind(
      message,
      JSON.stringify({ lastAction: action, ok: false, error: message, at: new Date().toISOString() }),
      article.id,
      userId
    ).run();
  }
  return articleById(ctx, userId, article.id);
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

async function collectDefaultTopics(ctx: RequestContext, category: ContentCategory): Promise<HotTopic[]> {
  const sources = await hotSources(ctx, category);
  const settled = await Promise.allSettled(sources.slice(0, 3).map((source) => fetchHotSource(source, 5)));
  const topics = settled.flatMap((item) => item.status === "fulfilled" ? item.value : []);
  return topics.length ? rankTopicsByCategory(topics, category) : fallbackTopics(category);
}

async function hotSources(ctx: RequestContext, category: ContentCategory): Promise<HotSource[]> {
  const configured = await config(ctx, `content.hot.sources.${category}`, await config(ctx, "content.hot.sources", ""));
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

function categoryOptions() {
  return Object.values(CATEGORY_PROFILES).map((item) => ({
    value: item.id,
    label: item.label
  }));
}

function fallbackTopics(category: ContentCategory = DEFAULT_CATEGORY): HotTopic[] {
  const profile = CATEGORY_PROFILES[category];
  return CATEGORY_FALLBACK_TOPICS[category].map((item, index) => ({
    id: `fallback:${category}:${index + 1}`,
    source: "fallback",
    sourceName: `${profile.label}选题库`,
    rank: index + 1,
    title: item.title,
    url: null,
    hot: item.hot,
    summary: item.summary,
    capturedAt: new Date().toISOString()
  }));
}

function rankTopicsByCategory(topics: HotTopic[], category: ContentCategory): HotTopic[] {
  const profile = CATEGORY_PROFILES[category];
  return topics
    .map((topic, index) => ({ topic, index, score: topicCategoryScore(topic, profile) }))
    .sort((left, right) => right.score - left.score || left.index - right.index)
    .map((item) => item.topic);
}

function topicCategoryScore(topic: HotTopic, profile: CategoryProfile): number {
  const text = `${topic.title} ${topic.summary || ""} ${topic.hot || ""}`.toLowerCase();
  return profile.keywords.reduce((score, keyword) => score + (text.includes(keyword.toLowerCase()) ? 6 : 0), 0);
}

async function collectResearch(
  ctx: RequestContext,
  topics: HotTopic[],
  body: ArticleGenerateBody,
  category: ContentCategory
): Promise<ResearchSource[]> {
  const profile = CATEGORY_PROFILES[category];
  const depth = researchDepthConfig(normalizeResearchDepth(body.researchDepth));
  const queries = buildResearchQueries(topics, body, profile).slice(0, depth.queryCount);
  const searchSettled = await Promise.allSettled(queries.map((query) => searchWeb(ctx, query, depth.resultsPerQuery)));
  const searchResults = searchSettled.flatMap((item) => item.status === "fulfilled" ? item.value : []);

  const byUrl = new Map<string, ResearchSource>();
  for (const result of searchResults) {
    const normalizedUrl = normalizeResearchUrl(result.url);
    if (!normalizedUrl || byUrl.has(normalizedUrl)) continue;
    byUrl.set(normalizedUrl, { ...result, url: normalizedUrl });
  }

  const selected = [...byUrl.values()].slice(0, depth.pageLimit);
  const enriched = await Promise.allSettled(
    selected.map(async (source) => {
      const content = await readResearchContent(ctx, source.url, depth.contentChars);
      return {
        ...source,
        title: content.title || source.title,
        content: content.content || source.content,
        sourceName: source.sourceName || hostName(source.url)
      };
    })
  );

  const sources = enriched
    .map((item, index) => item.status === "fulfilled" ? item.value : selected[index])
    .filter((item) => item.title || item.snippet || item.content)
    .slice(0, depth.pageLimit);
  return sources.length ? sources : searchResults.slice(0, depth.pageLimit);
}

function buildResearchQueries(topics: HotTopic[], body: ArticleGenerateBody, profile: CategoryProfile): string[] {
  const mainTopic = stringOrNull(body.topic) || topics[0]?.title || profile.label;
  const configured = Array.isArray(body.searchQueries) ? body.searchQueries.map((item) => String(item || "")) : [];
  return uniqueStrings([
    ...configured,
    `${mainTopic} ${profile.label}`,
    `${mainTopic} 原因 影响 分析`,
    `${mainTopic} 观点 评论`,
    `${mainTopic} 数据 案例`
  ]).slice(0, 6);
}

async function searchWeb(ctx: RequestContext, query: string, limit: number): Promise<ResearchSource[]> {
  const apiUrl = await config(ctx, "content.search.apiUrl", "");
  if (apiUrl) {
    const apiKey = await config(ctx, "content.search.apiKey", "");
    const configured = await configuredSearch(apiUrl, apiKey, query, limit);
    if (configured.length) return configured;
  }
  return duckDuckGoSearch(query, limit);
}

async function configuredSearch(apiUrl: string, apiKey: string, query: string, limit: number): Promise<ResearchSource[]> {
  const url = buildSearchApiUrl(apiUrl, query, limit);
  const response = await fetchWithTimeout(url, {
    headers: {
      "accept": "application/json",
      ...(apiKey ? { authorization: `Bearer ${apiKey}`, "x-api-key": apiKey } : {})
    }
  }, 10_000);
  const data = await response.json<any>().catch(() => null);
  if (!response.ok || !data) return [];
  return parseSearchJson(data).slice(0, limit);
}

function buildSearchApiUrl(apiUrl: string, query: string, limit: number): string {
  const encoded = encodeURIComponent(query);
  if (apiUrl.includes("{query}") || apiUrl.includes("{q}") || apiUrl.includes("{limit}")) {
    return apiUrl
      .replace(/\{query\}/g, encoded)
      .replace(/\{q\}/g, encoded)
      .replace(/\{limit\}/g, String(limit));
  }
  const url = new URL(apiUrl);
  if (!url.searchParams.has("q") && !url.searchParams.has("query")) {
    url.searchParams.set("q", query);
  }
  if (!url.searchParams.has("limit") && !url.searchParams.has("count") && !url.searchParams.has("num")) {
    url.searchParams.set("limit", String(limit));
  }
  return url.toString();
}

function parseSearchJson(data: any): ResearchSource[] {
  const rows = Array.isArray(data?.results)
    ? data.results
    : Array.isArray(data?.organic_results)
      ? data.organic_results
      : Array.isArray(data?.items)
        ? data.items
        : Array.isArray(data?.data)
          ? data.data
          : Array.isArray(data)
            ? data
            : [];
  return rows.map((item: any) => {
    const url = stringOrNull(item.url) || stringOrNull(item.link) || stringOrNull(item.href);
    if (!url) return null;
    return {
      title: stringOrNull(item.title) || stringOrNull(item.name) || hostName(url),
      url,
      snippet: stringOrNull(item.snippet) || stringOrNull(item.summary) || stringOrNull(item.content) || stringOrNull(item.description),
      content: null,
      sourceName: stringOrNull(item.source) || stringOrNull(item.siteName) || hostName(url)
    };
  }).filter((item: ResearchSource | null): item is ResearchSource => Boolean(item));
}

async function duckDuckGoSearch(query: string, limit: number): Promise<ResearchSource[]> {
  const url = `https://html.duckduckgo.com/html/?q=${encodeURIComponent(query)}`;
  const response = await fetchWithTimeout(url, {
    headers: {
      "accept": "text/html,application/xhtml+xml",
      "user-agent": "Mozilla/5.0 website-content-factory/1.0"
    }
  }, 10_000);
  if (!response.ok) return [];
  const html = await response.text();
  const results: ResearchSource[] = [];
  const pattern = /<a[^>]+class=["'][^"']*result__a[^"']*["'][^>]+href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi;
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(html)) && results.length < limit) {
    const href = decodeDuckDuckGoUrl(htmlDecode(match[1]));
    if (!href) continue;
    const tail = html.slice(pattern.lastIndex, pattern.lastIndex + 1600);
    const snippetMatch = tail.match(/<a[^>]+class=["'][^"']*result__snippet[^"']*["'][^>]*>([\s\S]*?)<\/a>|<div[^>]+class=["'][^"']*result__snippet[^"']*["'][^>]*>([\s\S]*?)<\/div>/i);
    results.push({
      title: cleanResearchText(stripTags(match[2])).slice(0, 120) || hostName(href),
      url: href,
      snippet: snippetMatch ? cleanResearchText(stripTags(snippetMatch[1] || snippetMatch[2] || "")).slice(0, 220) : null,
      content: null,
      sourceName: hostName(href)
    });
  }
  return results;
}

function decodeDuckDuckGoUrl(value: string): string | null {
  if (!value) return null;
  const absolute = value.startsWith("//") ? `https:${value}` : value;
  try {
    const url = new URL(absolute, "https://duckduckgo.com");
    const uddg = url.searchParams.get("uddg");
    return uddg ? decodeURIComponent(uddg) : url.toString();
  } catch {
    return null;
  }
}

async function readResearchContent(ctx: RequestContext, url: string, maxChars: number): Promise<{ title: string | null; content: string | null }> {
  const reader = await readWithJinaReader(url, maxChars);
  if (reader.content) return reader;
  return readDirectPage(ctx, url, maxChars);
}

async function readWithJinaReader(url: string, maxChars: number): Promise<{ title: string | null; content: string | null }> {
  const readerUrl = `https://r.jina.ai/http://${url}`;
  const response = await fetchWithTimeout(readerUrl, {
    headers: { "accept": "text/plain", "user-agent": "website-content-factory/1.0" }
  }, 12_000);
  if (!response.ok) return { title: null, content: null };
  const text = (await response.text()).slice(0, 80_000);
  const title = text.match(/^Title:\s*(.+)$/m)?.[1]?.trim() || null;
  const content = cleanResearchText(text.replace(/^Title:.+$/m, "").replace(/^URL Source:.+$/m, "").replace(/^Published Time:.+$/m, ""));
  return { title, content: content.slice(0, maxChars) || null };
}

async function readDirectPage(ctx: RequestContext, url: string, maxChars: number): Promise<{ title: string | null; content: string | null }> {
  const response = await fetchWithTimeout(url, {
    headers: { "accept": "text/html,text/plain", "user-agent": "Mozilla/5.0 website-content-factory/1.0" }
  }, 10_000);
  if (!response.ok) return { title: null, content: null };
  const contentType = response.headers.get("content-type") || "";
  const text = (await response.text()).slice(0, 120_000);
  if (!contentType.includes("html")) {
    const content = cleanResearchText(text).slice(0, maxChars);
    return { title: hostName(url), content: content || null };
  }
  const title = htmlDecode(text.match(/<title[^>]*>([\s\S]*?)<\/title>/i)?.[1] || "").trim() || null;
  const meta = htmlDecode(text.match(/<meta[^>]+(?:name|property)=["'](?:description|og:description)["'][^>]+content=["']([^"']+)["'][^>]*>/i)?.[1] || "");
  const content = cleanResearchText(`${meta}\n${stripTags(text)}`).slice(0, maxChars);
  return { title, content: content || null };
}

async function fetchWithTimeout(input: string, init: RequestInit, timeoutMs: number): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(input, { ...init, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

function researchDepthConfig(depth: ContentResearchDepth) {
  if (depth === "quick") return { queryCount: 1, resultsPerQuery: 4, pageLimit: 2, contentChars: 1200 };
  if (depth === "deep") return { queryCount: 3, resultsPerQuery: 6, pageLimit: 6, contentChars: 2200 };
  return { queryCount: 2, resultsPerQuery: 5, pageLimit: 4, contentChars: 1700 };
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
    category: row.category || null,
    layoutTheme: row.layout_theme || null,
    imageMode: row.image_mode || null,
    automation: parseJsonObject(row.automation_json) || null,
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
      category TEXT,
      layout_theme TEXT,
      image_mode TEXT,
      automation_json TEXT,
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
  await ctx.env.DB.prepare(
    "CREATE INDEX IF NOT EXISTS idx_content_article_user_category_updated ON content_article(user_id, category, updated_at DESC)"
  ).run();
}

function buildCoverPrompt(draft: ArticleDraft, style?: string, category: ContentCategory = DEFAULT_CATEGORY) {
  const profile = CATEGORY_PROFILES[category];
  return `${draft.coverPrompt}\n栏目：${profile.label}。微信公众号封面，比例 2.35:1，主题清晰，视觉干净，有传播感。画面关键词：${profile.coverKeywords}。${style ? `风格：${style}` : ""}`;
}

function buildFallbackCoverPrompt(topic: HotTopic, style?: string, category: ContentCategory = DEFAULT_CATEGORY) {
  const profile = CATEGORY_PROFILES[category];
  return `围绕“${topic.title}”创作一张公众号封面图，栏目为${profile.label}，画面包含${profile.coverKeywords}，避免真实人物肖像。${style ? `风格：${style}` : ""}`;
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
    .replace(/\son\w+=("[^"]*"|'[^']*'|[^\s>]+)/gi, "");
}

function sanitizeWechatContent(html: string, category: ContentCategory = DEFAULT_CATEGORY, layoutTheme: ContentLayoutTheme = DEFAULT_LAYOUT_THEME): string {
  const cleaned = sanitizeArticleHtml(html).replace(/<h1([\s>])/gi, "<h2$1").replace(/<\/h1>/gi, "</h2>");
  if (/data-content-factory=["']wechat["']/i.test(cleaned)) {
    return cleaned;
  }
  return formatWechatArticleHtml(cleaned, category, layoutTheme);
}

function formatDraftForWechat(draft: ArticleDraft, category: ContentCategory, layoutTheme: ContentLayoutTheme): ArticleDraft {
  const profile = CATEGORY_PROFILES[category];
  return {
    ...draft,
    contentHtml: formatWechatArticleHtml(draft.contentHtml, category, layoutTheme),
    tags: uniqueStrings([...profile.tags, ...draft.tags]).slice(0, 8),
    riskTips: uniqueStrings([...draft.riskTips, ...profile.riskBoundaries]).slice(0, 8)
  };
}

function formatWechatArticleHtml(html: string, category: ContentCategory, layoutTheme: ContentLayoutTheme): string {
  const profile = CATEGORY_PROFILES[category];
  const base = sanitizeArticleHtml(html)
    .replace(/<h1\b[^>]*>/gi, "<h2>")
    .replace(/<\/h1>/gi, "</h2>")
    .replace(/<section\b[^>]*data-content-factory=["']wechat["'][^>]*>/gi, "")
    .replace(/<\/section>\s*$/i, "");
  const theme = wechatTheme(profile, layoutTheme);
  const body = base
    .replace(/<h2\b[^>]*>/gi, `<h2 style="${theme.h2}">`)
    .replace(/<h3\b[^>]*>/gi, `<h3 style="${theme.h3}">`)
    .replace(/<p\b[^>]*>/gi, `<p style="${theme.p}">`)
    .replace(/<blockquote\b[^>]*>/gi, `<blockquote style="${theme.blockquote}">`)
    .replace(/<ul\b[^>]*>/gi, `<ul style="${theme.ul}">`)
    .replace(/<ol\b[^>]*>/gi, `<ol style="${theme.ol}">`)
    .replace(/<li\b[^>]*>/gi, `<li style="${theme.li}">`)
    .replace(/<strong\b[^>]*>/gi, `<strong style="${theme.strong}">`)
    .replace(/<img\b([^>]*)>/gi, `<img$1 style="${theme.img}">`)
    .replace(/<hr\b[^>]*>/gi, `<hr style="${theme.hr}">`);

  return [
    `<section data-content-factory="wechat" style="${theme.section}">`,
    body,
    "</section>"
  ].join("\n");
}

function wechatTheme(profile: CategoryProfile, layoutTheme: ContentLayoutTheme) {
  const themeOffset = layoutTheme === "magazine"
    ? {
        section: "letter-spacing:0;",
        h2Extra: `padding:10px 12px;border-left:5px solid ${profile.color};background:${profile.softBg};`,
        blockquoteExtra: `border:1px solid ${profile.accent};`
      }
    : layoutTheme === "warm"
      ? {
          section: `background:${profile.softBg};padding:18px 14px;border-radius:8px;letter-spacing:0;`,
          h2Extra: `padding-bottom:8px;border-bottom:2px solid ${profile.accent};`,
          blockquoteExtra: ""
        }
      : {
          section: "letter-spacing:0;",
          h2Extra: `padding-left:12px;border-left:4px solid ${profile.color};`,
          blockquoteExtra: ""
        };
  return {
    section: `box-sizing:border-box;max-width:100%;font-size:16px;line-height:1.78;color:#1f2937;${themeOffset.section}`,
    h2: `margin:28px 0 14px;color:${profile.color};font-size:20px;line-height:1.45;font-weight:700;${themeOffset.h2Extra}`,
    h3: `margin:22px 0 10px;color:#374151;font-size:17px;line-height:1.5;font-weight:700;`,
    p: "margin:0 0 16px;color:#1f2937;font-size:16px;line-height:1.78;",
    blockquote: `margin:18px 0;padding:14px 16px;border-left:4px solid ${profile.accent};border-radius:8px;background:${profile.softBg};color:#374151;font-size:15px;line-height:1.75;${themeOffset.blockquoteExtra}`,
    ul: "margin:0 0 16px;padding-left:1.2em;color:#1f2937;",
    ol: "margin:0 0 16px;padding-left:1.35em;color:#1f2937;",
    li: "margin:0 0 8px;color:#1f2937;font-size:16px;line-height:1.7;",
    strong: `color:${profile.color};font-weight:700;`,
    img: "display:block;max-width:100%;height:auto;margin:18px auto;border-radius:8px;",
    hr: `height:1px;border:0;background:${profile.accent};opacity:.35;margin:24px 0;`
  };
}

function normalizeCategory(value: unknown): ContentCategory {
  return value === "history_philosophy" || value === "society_livelihood" || value === "emotion_psychology"
    ? value
    : DEFAULT_CATEGORY;
}

function normalizeLayoutTheme(value: unknown): ContentLayoutTheme {
  return value === "warm" || value === "magazine" || value === "clean"
    ? value
    : DEFAULT_LAYOUT_THEME;
}

function normalizeImageMode(value: unknown, generateCover?: boolean): ContentImageMode {
  if (value === "fetch" || value === "none" || value === "generate") {
    return value;
  }
  return generateCover === false ? "none" : DEFAULT_IMAGE_MODE;
}

function normalizeResearchDepth(value: unknown): ContentResearchDepth {
  return value === "quick" || value === "deep" || value === "standard" ? value : "standard";
}

function markdownToHtml(markdown: string): string {
  const lines = markdown.split(/\r?\n/);
  const html: string[] = [];
  let listOpen = false;
  let orderedListOpen = false;
  const closeLists = () => {
    if (listOpen) {
      html.push("</ul>");
      listOpen = false;
    }
    if (orderedListOpen) {
      html.push("</ol>");
      orderedListOpen = false;
    }
  };
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed) {
      closeLists();
      continue;
    }
    if (trimmed.startsWith("# ")) {
      closeLists();
      html.push(`<h1>${renderInlineMarkdown(trimmed.slice(2))}</h1>`);
    } else if (trimmed.startsWith("## ")) {
      closeLists();
      html.push(`<h2>${renderInlineMarkdown(trimmed.slice(3))}</h2>`);
    } else if (trimmed.startsWith("### ")) {
      closeLists();
      html.push(`<h3>${renderInlineMarkdown(trimmed.slice(4))}</h3>`);
    } else if (trimmed.startsWith("> ")) {
      closeLists();
      html.push(`<blockquote>${renderInlineMarkdown(trimmed.slice(2))}</blockquote>`);
    } else if (trimmed.startsWith("- ")) {
      if (orderedListOpen) {
        html.push("</ol>");
        orderedListOpen = false;
      }
      if (!listOpen) {
        html.push("<ul>");
        listOpen = true;
      }
      html.push(`<li>${renderInlineMarkdown(trimmed.slice(2))}</li>`);
    } else if (/^\d+\.\s+/.test(trimmed)) {
      if (listOpen) {
        html.push("</ul>");
        listOpen = false;
      }
      if (!orderedListOpen) {
        html.push("<ol>");
        orderedListOpen = true;
      }
      html.push(`<li>${renderInlineMarkdown(trimmed.replace(/^\d+\.\s+/, ""))}</li>`);
    } else {
      closeLists();
      html.push(`<p>${renderInlineMarkdown(trimmed)}</p>`);
    }
  }
  closeLists();
  return html.join("\n");
}

function htmlToPlainText(html: string): string {
  return html.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
}

function manualTopicView(title: string, category: ContentCategory): HotTopic {
  return {
    id: `manual:${category}:${hashText(title).slice(0, 10)}`,
    source: "manual",
    sourceName: `${CATEGORY_PROFILES[category].label}手动话题`,
    rank: 1,
    title,
    url: null,
    hot: "手动话题",
    summary: `围绕“${title}”进行网页搜索和公众号写作。`,
    capturedAt: new Date().toISOString()
  };
}

function renderInlineMarkdown(value: string): string {
  return escapeHtml(value)
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/\[([^\]]+)\]\((https?:\/\/[^)]+)\)/g, '<a href="$2">$1</a>');
}

function buildConfiguredImageUrl(template: string, topic: HotTopic | undefined, body: ArticleGenerateBody, category: ContentCategory): string | null {
  const profile = CATEGORY_PROFILES[category];
  const query = [profile.label, topic?.title, profile.coverKeywords, body.coverStyle]
    .filter(Boolean)
    .join(" ");
  const encoded = encodeURIComponent(query);
  if (template.includes("{query}") || template.includes("{keyword}") || template.includes("{category}") || template.includes("{topic}")) {
    return template
      .replace(/\{query\}/g, encoded)
      .replace(/\{keyword\}/g, encoded)
      .replace(/\{category\}/g, encodeURIComponent(profile.label))
      .replace(/\{topic\}/g, encodeURIComponent(topic?.title || profile.label));
  }
  try {
    const url = new URL(template);
    if (!url.searchParams.has("q")) {
      url.searchParams.set("q", query);
    }
    return url.toString();
  } catch {
    return null;
  }
}

function extractPageImageUrl(html: string, pageUrl: string): string | null {
  const patterns = [
    /<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["'][^>]*>/i,
    /<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:image["'][^>]*>/i,
    /<meta[^>]+name=["']twitter:image["'][^>]+content=["']([^"']+)["'][^>]*>/i,
    /<meta[^>]+content=["']([^"']+)["'][^>]+name=["']twitter:image["'][^>]*>/i,
    /<link[^>]+rel=["']image_src["'][^>]+href=["']([^"']+)["'][^>]*>/i,
    /<img[^>]+src=["']([^"']+)["'][^>]*>/i
  ];
  for (const pattern of patterns) {
    const match = html.match(pattern);
    const value = match?.[1]?.trim();
    if (value && !value.startsWith("data:")) {
      return absolutizeUrl(value, pageUrl);
    }
  }
  return null;
}

function absolutizeUrl(value: string, baseUrl: string): string | null {
  try {
    return new URL(value, baseUrl).toString();
  } catch {
    return null;
  }
}

function normalizeResearchUrl(value: string): string | null {
  if (!/^https?:\/\//i.test(value)) return null;
  try {
    const url = new URL(value);
    url.hash = "";
    return url.toString();
  } catch {
    return null;
  }
}

function hostName(value: string): string {
  try {
    return new URL(value).hostname.replace(/^www\./, "");
  } catch {
    return "网页资料";
  }
}

function stripTags(value: string): string {
  return htmlDecode(value)
    .replace(/<script[\s\S]*?<\/script>/gi, " ")
    .replace(/<style[\s\S]*?<\/style>/gi, " ")
    .replace(/<[^>]+>/g, " ");
}

function cleanResearchText(value: string): string {
  return htmlDecode(value)
    .replace(/!\[[^\]]*]\([^)]+\)/g, " ")
    .replace(/\[[^\]]*]\((javascript:|#)[^)]+\)/gi, " ")
    .replace(/\[(.*?)\]\((https?:\/\/[^)]+)\)/g, "$1")
    .replace(/^\s*(Warning|Markdown Content):.*$/gim, " ")
    .replace(/\s+/g, " ")
    .trim();
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

function uniqueStrings(values: string[]): string[] {
  return [...new Set(values.map((item) => item.trim()).filter(Boolean))];
}

function htmlDecode(value: string): string {
  return value
    .replace(/&nbsp;/g, " ")
    .replace(/&amp;/g, "&")
    .replace(/&quot;/g, "\"")
    .replace(/&#39;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&#(\d+);/g, (_, code) => String.fromCharCode(Number(code)))
    .replace(/&#x([0-9a-f]+);/gi, (_, code) => String.fromCharCode(parseInt(code, 16)));
}

function replaceImageSource(html: string, source: string, target: string): string {
  const sourcePattern = escapeRegExp(source);
  return html.replace(
    new RegExp(`(<img\\b[^>]*\\bsrc=["'])${sourcePattern}(["'][^>]*>)`, "gi"),
    `$1${target}$2`
  );
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
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
