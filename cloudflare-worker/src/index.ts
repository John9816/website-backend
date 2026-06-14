import { getAuthUser, ensureAdmin, login, register, changePassword, currentUserView } from "./auth";
import { fail, optionsResponse, readJson, ok, emptyOk, requireUser, withCors } from "./http";
import { Router, childContext } from "./router";
import { Env, HttpError, RequestContext } from "./types";
import * as nav from "./modules/nav";
import * as music from "./modules/music";
import * as image from "./modules/image";
import * as ai from "./modules/ai";
import * as kb from "./modules/kb";
import * as content from "./modules/content";
import * as credits from "./credits";

const router = new Router();

router.on("POST", "/api/auth/register", async (ctx) => {
  const body = await readJson<{ username?: string; email?: string; password?: string }>(ctx.request);
  return ok(await register(ctx.env, body.username || "", body.password || "", body.email || ""));
});

router.on("POST", "/api/auth/login", async (ctx) => {
  const body = await readJson<{ username?: string; password?: string }>(ctx.request);
  return ok(await login(ctx.env, body.username || "", body.password || ""));
});

router.on("GET", "/api/user/me", async (ctx) => ok(await currentUserView(ctx.env, requireUser(ctx))));
router.on("GET", "/api/user/credits", credits.credits);
router.on("POST", "/api/user/check-in", credits.checkIn);
router.on("POST", "/api/user/change-password", passwordChange);
router.on("POST", "/api/admin/change-password", passwordChange);

router.on("GET", "/api/public/categories", nav.publicCategories);
router.on("GET", "/api/public/links", nav.publicLinks);
router.on("GET", "/api/public/nav", nav.publicNav);

for (const prefix of ["/api/user/categories", "/api/admin/categories"]) {
  router.on("GET", prefix, nav.listUserCategories);
  router.on("GET", `${prefix}/:id`, nav.getCategory);
  router.on("POST", prefix, nav.createCategory);
  router.on("PUT", `${prefix}/:id`, nav.updateCategory);
  router.on("DELETE", `${prefix}/:id`, nav.deleteCategory);
}

for (const prefix of ["/api/user/links", "/api/admin/links"]) {
  router.on("GET", prefix, nav.listUserLinks);
  router.on("GET", `${prefix}/:id`, nav.getLink);
  router.on("POST", prefix, nav.createLink);
  router.on("PUT", `${prefix}/:id`, nav.updateLink);
  router.on("DELETE", `${prefix}/:id`, nav.deleteLink);
}

router.on("GET", "/api/admin/configs", nav.listConfigs);
router.on("POST", "/api/admin/configs", nav.createConfig);
router.on("PUT", "/api/admin/configs/:id", nav.updateConfig);
router.on("DELETE", "/api/admin/configs/:id", nav.deleteConfig);

router.on("GET", "/api/admin/content/status", content.status);
router.on("GET", "/api/admin/content/hot", content.hotTopics);
router.on("GET", "/api/admin/content/articles", content.articles);
router.on("POST", "/api/admin/content/articles/generate", content.generateArticle);
router.on("GET", "/api/admin/content/articles/:id", content.getArticle);
router.on("PUT", "/api/admin/content/articles/:id", content.updateArticle);
router.on("POST", "/api/admin/content/articles/:id/wechat-draft", content.createWechatDraft);
router.on("POST", "/api/admin/content/articles/:id/publish", content.publishWechat);

for (const prefix of ["/api/user/image", "/api/admin/image"]) {
  router.on("POST", `${prefix}/generate`, image.generateImage);
  router.on("POST", `${prefix}/edit`, image.editImage);
  router.on("GET", `${prefix}/generate/:taskId`, image.imageTask);
  router.on("GET", `${prefix}/history`, image.imageHistory);
  router.on("PATCH", `${prefix}/history/:id/share`, image.toggleImageShare);
  router.on("POST", `${prefix}/history/:id/retry`, image.retryImageTask);
  router.on("DELETE", `${prefix}/history/:id`, image.deleteImageHistory);
}
router.on("GET", "/api/public/image/shared", image.publicSharedImages);
router.on("POST", "/api/admin/image/telegram/test", image.testTelegramImage);
router.on("GET", "/api/v1/image/file/:filename", image.imageFile);
router.on("GET", "/api/v1/image/telegram/:fileId", image.telegramImageFile);

router.on("GET", "/api/user/ai/models", ai.models);
router.on("GET", "/api/user/ai/voices", ai.voices);
router.on("POST", "/api/user/ai/tts", ai.tts);
router.on("POST", "/api/user/ai/conversations", ai.createConversation);
router.on("GET", "/api/user/ai/conversations", ai.conversations);
router.on("GET", "/api/user/ai/conversations/:id", ai.conversation);
router.on("DELETE", "/api/user/ai/conversations/:id", ai.deleteConversation);
router.on("GET", "/api/user/ai/conversations/:id/messages", ai.messages);
router.on("POST", "/api/user/ai/conversations/:id/messages", async (ctx) => {
  return ctx.url.searchParams.get("stream") === "true" ? ai.streamMessage(ctx) : ai.sendMessage(ctx);
});
router.on("GET", "/api/user/ai/messages/:id/audio", ai.messageAudio);
router.on("POST", "/api/user/ai/messages/:id/audio", ai.regenerateAudio);

for (const prefix of ["/api/user/kb/spaces", "/api/admin/kb/spaces"]) {
  router.on("GET", prefix, kb.spaces);
  router.on("GET", `${prefix}/:id`, kb.getSpace);
  router.on("POST", prefix, kb.createSpace);
  router.on("PUT", `${prefix}/:id`, kb.updateSpace);
  router.on("DELETE", `${prefix}/:id`, kb.deleteSpace);
  router.on("GET", `${prefix}/:id/tree`, kb.tree);
}
for (const prefix of ["/api/user/kb/tags", "/api/admin/kb/tags"]) {
  router.on("GET", prefix, kb.tags);
  router.on("POST", prefix, kb.createTag);
  router.on("PUT", `${prefix}/:id`, kb.updateTag);
  router.on("DELETE", `${prefix}/:id`, kb.deleteTag);
}
router.on("POST", "/api/user/kb/assets", kb.uploadAsset);
router.on("POST", "/api/admin/kb/assets", kb.uploadAsset);
for (const prefix of ["/api/user/kb/docs", "/api/admin/kb/docs"]) {
  router.on("GET", prefix, kb.docs);
  router.on("POST", prefix, kb.createDoc);
  router.on("GET", `${prefix}/:id`, kb.getDoc);
  router.on("PUT", `${prefix}/:id`, kb.updateDoc);
  router.on("DELETE", `${prefix}/:id`, kb.deleteDoc);
  router.on("POST", `${prefix}/:id/move`, kb.moveDoc);
  router.on("PUT", `${prefix}/:id/tags`, kb.setDocTags);
  router.on("GET", `${prefix}/:id/versions`, kb.versions);
  router.on("GET", `${prefix}/:id/versions/:versionId`, kb.versionDetail);
  router.on("POST", `${prefix}/:id/versions/:versionId/restore`, kb.restoreVersion);
  router.on("GET", `${prefix}/:id/share`, kb.getShare);
  router.on("POST", `${prefix}/:id/share`, kb.enableShare);
  router.on("DELETE", `${prefix}/:id/share`, kb.disableShare);
}
router.on("GET", "/api/public/kb/share/:token", kb.publicShare);
router.on("GET", "/api/v1/kb/assets/:filename", kb.assetFile);
router.on("GET", "/api/v1/content/assets/:filename", content.contentAssetFile);

router.on("GET", "/api/v1/music/search", music.musicProxy);
router.on("GET", "/api/v1/music/play", music.musicProxy);
router.on("GET", "/api/v1/music/lyric", music.musicProxy);
router.on("GET", "/api/v1/music/toplist", music.musicProxy);
router.on("GET", "/api/v1/music/toplist/detail", music.musicProxy);
router.on("GET", "/api/v1/music/playlist", music.musicProxy);
router.on("GET", "/api/v1/music/playlist/detail", music.musicProxy);
router.on("GET", "/api/v1/music/new", music.musicProxy);
router.on("GET", "/api/user/music/history", music.history);
router.on("DELETE", "/api/user/music/history/:id", music.deleteHistory);
router.on("GET", "/api/user/music/favorites", music.favorites);
router.on("POST", "/api/user/music/favorites", music.saveFavorite);
router.on("DELETE", "/api/user/music/favorites", music.deleteFavorite);
router.on("POST", "/api/user/music/favorites/status", music.favoriteStatus);
router.on("GET", "/api/user/music/shares/status", music.shareStatus);
router.on("POST", "/api/user/music/shares", music.saveShare);
router.on("DELETE", "/api/user/music/shares", music.deleteShare);
router.on("POST", "/api/user/music/playlists/import", music.importPlaylist);
router.on("GET", "/api/user/music/playlists", music.listPlaylists);
router.on("GET", "/api/user/music/playlists/:id", music.playlistDetail);
router.on("PATCH", "/api/user/music/playlists/:id", music.renamePlaylist);
router.on("DELETE", "/api/user/music/playlists/:id", music.deletePlaylist);
router.on("DELETE", "/api/user/music/playlists/:id/items/:itemId", music.removePlaylistItem);
router.on("GET", "/api/public/music/share/:token", music.publicMusicShare);

router.on("GET", "/health", async () => ok({ status: "up", runtime: "cloudflare-workers" }));

export default {
  async fetch(request: Request, env: Env, executionCtx: ExecutionContext): Promise<Response> {
    if (request.method === "OPTIONS") {
      return optionsResponse(request, env.CORS_ORIGINS || "*");
    }

    try {
      await ensureAdmin(env);
      const url = new URL(request.url);
      const matched = router.match(request.method, url.pathname);
      if (!matched) {
        return withCors(fail(404, "Not found"), request, env.CORS_ORIGINS || "*");
      }
      const baseCtx: RequestContext = {
        request,
        env,
        url,
        params: {},
        user: await getAuthUser(request, env),
        waitUntil: (promise) => executionCtx.waitUntil(promise)
      };
      const response = await matched.handler(childContext(baseCtx, matched.params));
      return withCors(response, request, env.CORS_ORIGINS || "*");
    } catch (error) {
      const response = error instanceof HttpError
        ? fail(error.status, error.message, error.code)
        : fail(500, error instanceof Error ? error.message : "Internal Server Error");
      return withCors(response, request, env.CORS_ORIGINS || "*");
    }
  },

  async queue(batch: MessageBatch<import("./types").ImageGenerateQueueMessage>, env: Env): Promise<void> {
    for (const message of batch.messages) {
      try {
        await image.consumeImageQueue(env, message.body);
        message.ack();
      } catch (error) {
        console.error("Image queue task failed", error);
        message.retry();
      }
    }
  }
};

async function passwordChange(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const body = await readJson<{ oldPassword?: string; newPassword?: string }>(ctx.request);
  await changePassword(ctx.env, user.id, body.oldPassword || "", body.newPassword || "");
  return emptyOk();
}
