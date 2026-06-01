import { randomToken } from "../crypto";
import { firstRequired } from "../db";
import { emptyOk, ok, readJson, requireUser } from "../http";
import { HttpError, RequestContext } from "../types";
import { pagedQuery } from "./nav";
import { musicProviderData } from "./musicProviders";

interface TrackBody {
  source?: string;
  songId?: string;
  id?: string;
  name?: string;
  artist?: string;
  album?: string;
  coverUrl?: string;
  durationSec?: number;
  requestedQuality?: string;
  expiresAt?: string;
}

export async function musicProxy(ctx: RequestContext): Promise<Response> {
  try {
    return ok(await musicProviderData(ctx));
  } catch (error) {
    if (!supportsLocalFallback(ctx.url.pathname)) {
      throw error;
    }
    console.warn("Music provider unavailable, falling back to local D1 data", {
      path: ctx.url.pathname,
      message: error instanceof Error ? error.message : String(error)
    });
    // Fall through to imported D1 playlist data when an upstream music provider is unavailable.
  }
  return ok(await localMusicData(ctx));
}

function supportsLocalFallback(pathname: string): boolean {
  return pathname.endsWith("/playlist")
    || pathname.endsWith("/playlist/detail")
    || pathname.endsWith("/new");
}

export async function history(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  return pagedQuery(
    ctx,
    "SELECT * FROM music_play_history WHERE user_id = ? ORDER BY played_at DESC, id DESC",
    "SELECT COUNT(*) AS total FROM music_play_history WHERE user_id = ?",
    [user.id],
    musicRowView
  );
}

export async function deleteHistory(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  await ctx.env.DB.prepare("DELETE FROM music_play_history WHERE id = ? AND user_id = ?").bind(Number(ctx.params.id), user.id).run();
  return emptyOk();
}

export async function favorites(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  return pagedQuery(
    ctx,
    "SELECT * FROM music_favorite WHERE user_id = ? ORDER BY created_at DESC, id DESC",
    "SELECT COUNT(*) AS total FROM music_favorite WHERE user_id = ?",
    [user.id],
    musicRowView
  );
}

export async function saveFavorite(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const body = await readJson<TrackBody>(ctx.request);
  const track = normalizeTrack(body);
  await ctx.env.DB.prepare(
    `INSERT INTO music_favorite(user_id, source, song_id, name, artist, album, cover_url, duration_sec)
     VALUES(?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(user_id, source, song_id) DO UPDATE SET
       name = excluded.name, artist = excluded.artist, album = excluded.album,
       cover_url = excluded.cover_url, duration_sec = excluded.duration_sec, updated_at = CURRENT_TIMESTAMP`
  ).bind(user.id, track.source, track.songId, track.name, track.artist, track.album, track.coverUrl, track.durationSec).run();
  return ok(await favoriteBySong(ctx, user.id, track.source, track.songId));
}

export async function deleteFavorite(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  await ctx.env.DB.prepare("DELETE FROM music_favorite WHERE user_id = ? AND source = ? AND song_id = ?")
    .bind(user.id, ctx.url.searchParams.get("source"), ctx.url.searchParams.get("songId"))
    .run();
  return emptyOk();
}

export async function favoriteStatus(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const body = await readJson<{ source?: string; songIds?: string[] }>(ctx.request);
  const source = body.source || "";
  const ids = body.songIds ?? [];
  if (ids.length === 0) {
    return ok([]);
  }
  const rows = await ctx.env.DB.prepare(
    `SELECT id, song_id FROM music_favorite WHERE user_id = ? AND source = ? AND song_id IN (${ids.map(() => "?").join(",")})`
  ).bind(user.id, source, ...ids).all<{ id: number; song_id: string }>();
  const bySong = new Map((rows.results ?? []).map((row) => [row.song_id, row.id]));
  return ok(ids.map((songId) => ({ source, songId, liked: bySong.has(songId), favoriteId: bySong.get(songId) ?? null })));
}

export async function shareStatus(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const source = ctx.url.searchParams.get("source") || "";
  const songId = ctx.url.searchParams.get("songId") || "";
  const row = await ctx.env.DB.prepare("SELECT * FROM music_share WHERE user_id = ? AND source = ? AND song_id = ?")
    .bind(user.id, source, songId)
    .first();
  return ok(row ? musicShareView(row) : null);
}

export async function saveShare(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const body = await readJson<TrackBody>(ctx.request);
  const track = normalizeTrack(body);
  const token = randomToken(18);
  await ctx.env.DB.prepare(
    `INSERT INTO music_share(user_id, source, song_id, name, artist, album, cover_url, duration_sec, requested_quality, token, expires_at)
     VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(user_id, source, song_id) DO UPDATE SET
       name = excluded.name, artist = excluded.artist, album = excluded.album, cover_url = excluded.cover_url,
       duration_sec = excluded.duration_sec, requested_quality = excluded.requested_quality, updated_at = CURRENT_TIMESTAMP`
  ).bind(
    user.id,
    track.source,
    track.songId,
    track.name,
    track.artist,
    track.album,
    track.coverUrl,
    track.durationSec,
    body.requestedQuality || "standard",
    token,
    body.expiresAt || null
  ).run();
  const row = await firstRequired<any>(
    ctx.env.DB.prepare("SELECT * FROM music_share WHERE user_id = ? AND source = ? AND song_id = ?").bind(user.id, track.source, track.songId)
  );
  return ok(musicShareView(row));
}

export async function deleteShare(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  await ctx.env.DB.prepare("DELETE FROM music_share WHERE user_id = ? AND source = ? AND song_id = ?")
    .bind(user.id, ctx.url.searchParams.get("source"), ctx.url.searchParams.get("songId"))
    .run();
  return emptyOk();
}

export async function publicMusicShare(ctx: RequestContext): Promise<Response> {
  const row = await firstRequired<any>(
    ctx.env.DB.prepare("SELECT * FROM music_share WHERE token = ? AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)").bind(ctx.params.token),
    "Share not found"
  );
  await ctx.env.DB.prepare("UPDATE music_share SET view_count = view_count + 1 WHERE id = ?").bind(row.id).run();
  return ok({ ...musicShareView(row), playInfo: null });
}

export async function listPlaylists(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  return pagedQuery(
    ctx,
    "SELECT * FROM user_playlist WHERE user_id = ? ORDER BY created_at DESC, id DESC",
    "SELECT COUNT(*) AS total FROM user_playlist WHERE user_id = ?",
    [user.id],
    playlistView
  );
}

export async function importPlaylist(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const body = await readJson<{ url?: string; name?: string }>(ctx.request);
  const name = body.name || body.url || "Imported playlist";
  const result = await ctx.env.DB.prepare("INSERT INTO user_playlist(user_id, source, name, source_url) VALUES(?, 'external', ?, ?)")
    .bind(user.id, name.slice(0, 200), body.url || null)
    .run();
  const row = await firstRequired<any>(ctx.env.DB.prepare("SELECT * FROM user_playlist WHERE id = ? AND user_id = ?").bind(result.meta.last_row_id, user.id));
  return ok(playlistView(row));
}

export async function playlistDetail(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const playlist = await firstRequired<any>(
    ctx.env.DB.prepare("SELECT * FROM user_playlist WHERE id = ? AND user_id = ?").bind(Number(ctx.params.id), user.id)
  );
  const page = Number(ctx.url.searchParams.get("page") || 0);
  const size = Number(ctx.url.searchParams.get("size") || 30);
  const items = await ctx.env.DB.prepare("SELECT * FROM user_playlist_item WHERE playlist_id = ? ORDER BY sort_order, id LIMIT ? OFFSET ?")
    .bind(Number(ctx.params.id), Math.max(1, Math.min(100, size)), Math.max(0, page) * Math.max(1, Math.min(100, size)))
    .all();
  const total = await ctx.env.DB.prepare("SELECT COUNT(*) AS total FROM user_playlist_item WHERE playlist_id = ?").bind(Number(ctx.params.id)).first<{ total: number }>();
  return ok({
    playlist: playlistView(playlist),
    items: (items.results ?? []).map(playlistItemView),
    total: Number(total?.total ?? 0),
    page,
    size: Math.max(1, Math.min(100, size))
  });
}

export async function deletePlaylist(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  await ctx.env.DB.prepare("DELETE FROM user_playlist WHERE id = ? AND user_id = ?").bind(Number(ctx.params.id), user.id).run();
  return emptyOk();
}

export async function renamePlaylist(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  const body = await readJson<{ name?: string }>(ctx.request);
  if (!body.name) {
    throw new HttpError(400, "name is required");
  }
  await ctx.env.DB.prepare("UPDATE user_playlist SET name = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?")
    .bind(body.name, Number(ctx.params.id), user.id)
    .run();
  const row = await firstRequired<any>(ctx.env.DB.prepare("SELECT * FROM user_playlist WHERE id = ? AND user_id = ?").bind(Number(ctx.params.id), user.id));
  return ok(playlistView(row));
}

export async function removePlaylistItem(ctx: RequestContext): Promise<Response> {
  const user = requireUser(ctx);
  await firstRequired(ctx.env.DB.prepare("SELECT id FROM user_playlist WHERE id = ? AND user_id = ?").bind(Number(ctx.params.id), user.id));
  await ctx.env.DB.prepare("DELETE FROM user_playlist_item WHERE id = ? AND playlist_id = ?").bind(Number(ctx.params.itemId), Number(ctx.params.id)).run();
  return emptyOk();
}

async function recordPlay(ctx: RequestContext, userId: number, playInfo: any): Promise<void> {
  const track = normalizeTrack({
    source: playInfo.source,
    songId: playInfo.songId || playInfo.id,
    name: playInfo.name,
    artist: playInfo.artist,
    album: playInfo.album,
    coverUrl: playInfo.coverUrl,
    durationSec: playInfo.durationSec
  });
  await ctx.env.DB.prepare(
    `INSERT INTO music_play_history(user_id, source, song_id, name, artist, album, cover_url, duration_sec)
     VALUES(?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(user_id, source, song_id) DO UPDATE SET
       name = excluded.name, artist = excluded.artist, album = excluded.album, cover_url = excluded.cover_url,
       duration_sec = excluded.duration_sec, played_at = CURRENT_TIMESTAMP`
  ).bind(userId, track.source, track.songId, track.name, track.artist, track.album, track.coverUrl, track.durationSec).run();
}

async function favoriteBySong(ctx: RequestContext, userId: number, source: string, songId: string) {
  const row = await firstRequired<any>(
    ctx.env.DB.prepare("SELECT * FROM music_favorite WHERE user_id = ? AND source = ? AND song_id = ?").bind(userId, source, songId)
  );
  return musicRowView(row);
}

function normalizeTrack(body: TrackBody) {
  const source = body.source;
  const songId = body.songId || body.id;
  const name = body.name;
  if (!source || !songId || !name) {
    throw new HttpError(400, "source, songId and name are required");
  }
  return {
    source,
    songId,
    name,
    artist: body.artist || null,
    album: body.album || null,
    coverUrl: body.coverUrl || null,
    durationSec: Number.isFinite(Number(body.durationSec)) ? Number(body.durationSec) : null
  };
}

function musicRowView(row: any) {
  return {
    id: row.id,
    source: row.source,
    songId: row.song_id,
    name: row.name,
    artist: row.artist,
    album: row.album,
    coverUrl: row.cover_url,
    durationSec: row.duration_sec,
    createdAt: row.created_at,
    playedAt: row.played_at,
    likedAt: row.created_at
  };
}

function musicShareView(row: any) {
  return {
    ...musicRowView(row),
    requestedQuality: row.requested_quality,
    token: row.token,
    expiresAt: row.expires_at,
    viewCount: row.view_count
  };
}

function playlistView(row: any) {
  return {
    id: row.id,
    source: row.source,
    externalId: row.external_id,
    name: row.name,
    coverUrl: row.cover_url,
    trackCount: row.track_count,
    sourceUrl: row.source_url,
    createdAt: row.created_at,
    updatedAt: row.updated_at
  };
}

function playlistItemView(row: any) {
  return {
    id: row.id,
    source: row.source,
    songId: row.song_id,
    name: row.name,
    artist: row.artist,
    album: row.album,
    coverUrl: row.cover_url,
    durationSec: row.duration_sec,
    sortOrder: row.sort_order
  };
}

async function localMusicData(ctx: RequestContext) {
  const pathname = ctx.url.pathname;
  if (pathname.endsWith("/playlist")) {
    const source = ctx.url.searchParams.get("source") || "netease";
    const page = Number(ctx.url.searchParams.get("page") || 1);
    const pageSize = Math.max(1, Math.min(100, Number(ctx.url.searchParams.get("pageSize") || 20)));
    const rows = await ctx.env.DB.prepare(
      "SELECT * FROM user_playlist WHERE source = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?"
    ).bind(source, pageSize, (Math.max(1, page) - 1) * pageSize).all();
    const total = await ctx.env.DB.prepare("SELECT COUNT(*) AS total FROM user_playlist WHERE source = ?").bind(source).first<{ total: number }>();
    return {
      source,
      category: ctx.url.searchParams.get("category"),
      order: ctx.url.searchParams.get("order"),
      page,
      pageSize,
      total: Number(total?.total ?? 0),
      list: (rows.results ?? []).map(publicPlaylistView)
    };
  }
  if (pathname.endsWith("/playlist/detail")) {
    const id = Number(ctx.url.searchParams.get("id"));
    const source = ctx.url.searchParams.get("source") || "netease";
    if (!Number.isFinite(id)) {
      return emptyMusicData(pathname);
    }
    const playlist = await ctx.env.DB.prepare("SELECT * FROM user_playlist WHERE id = ?").bind(id).first<any>();
    if (!playlist) {
      return emptyMusicData(pathname);
    }
    const page = Number(ctx.url.searchParams.get("page") || 1);
    const pageSize = Math.max(1, Math.min(100, Number(ctx.url.searchParams.get("pageSize") || 30)));
    const items = await ctx.env.DB.prepare(
      "SELECT * FROM user_playlist_item WHERE playlist_id = ? ORDER BY sort_order, id LIMIT ? OFFSET ?"
    ).bind(id, pageSize, (Math.max(1, page) - 1) * pageSize).all();
    const total = await ctx.env.DB.prepare("SELECT COUNT(*) AS total FROM user_playlist_item WHERE playlist_id = ?").bind(id).first<{ total: number }>();
    return {
      id: String(playlist.id),
      source,
      name: playlist.name,
      coverUrl: playlist.cover_url,
      creatorName: playlist.creator_name,
      page,
      pageSize,
      total: Number(total?.total ?? 0),
      list: (items.results ?? []).map(songView)
    };
  }
  if (pathname.endsWith("/new")) {
    const source = ctx.url.searchParams.get("source") || "qq";
    const page = Number(ctx.url.searchParams.get("page") || 1);
    const pageSize = Math.max(1, Math.min(100, Number(ctx.url.searchParams.get("pageSize") || 30)));
    const rows = await ctx.env.DB.prepare(
      "SELECT * FROM user_playlist_item WHERE source = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?"
    ).bind(source, pageSize, (Math.max(1, page) - 1) * pageSize).all();
    const total = await ctx.env.DB.prepare("SELECT COUNT(*) AS total FROM user_playlist_item WHERE source = ?").bind(source).first<{ total: number }>();
    return {
      id: "local-new",
      source,
      name: "Imported songs",
      page,
      pageSize,
      total: Number(total?.total ?? 0),
      list: (rows.results ?? []).map(songView)
    };
  }
  if (pathname.endsWith("/search")) {
    const source = ctx.url.searchParams.get("source") || "qq";
    const keyword = ctx.url.searchParams.get("keyword") || "";
    const page = Number(ctx.url.searchParams.get("page") || 1);
    const pageSize = Math.max(1, Math.min(100, Number(ctx.url.searchParams.get("pageSize") || 10)));
    const like = `%${keyword}%`;
    const rows = await ctx.env.DB.prepare(
      "SELECT * FROM user_playlist_item WHERE source = ? AND (name LIKE ? OR artist LIKE ? OR album LIKE ?) ORDER BY sort_order, id LIMIT ? OFFSET ?"
    ).bind(source, like, like, like, pageSize, (Math.max(1, page) - 1) * pageSize).all();
    return { source, keyword, page, pageSize, list: (rows.results ?? []).map(songView) };
  }
  return emptyMusicData(pathname);
}

function publicPlaylistView(row: any) {
  return {
    id: String(row.id),
    source: row.source,
    name: row.name,
    coverUrl: row.cover_url,
    creatorName: row.creator_name,
    trackCount: row.track_count,
    sourceUrl: row.source_url
  };
}

function songView(row: any) {
  return {
    id: row.song_id,
    source: row.source,
    name: row.name,
    artist: row.artist || "",
    album: row.album,
    coverUrl: row.cover_url,
    durationSec: row.duration_sec,
    availableQualities: ["128k", "320k", "flac", "flac24bit"]
  };
}

function emptyMusicData(pathname: string) {
  if (pathname.endsWith("/search")) {
    return { source: "qq", keyword: "", page: 1, pageSize: 10, list: [] };
  }
  if (pathname.endsWith("/toplist")) {
    return { source: "qq", list: [] };
  }
  if (pathname.endsWith("/toplist/detail")) {
    return { id: "", source: "qq", page: 1, pageSize: 10, total: 0, list: [] };
  }
  if (pathname.endsWith("/playlist")) {
    return { source: "netease", category: null, order: null, page: 1, pageSize: 10, total: 0, list: [] };
  }
  if (pathname.endsWith("/playlist/detail")) {
    return { id: "", source: "netease", page: 1, pageSize: 10, total: 0, list: [] };
  }
  if (pathname.endsWith("/new")) {
    return { id: "new", source: "qq", name: "New songs", page: 1, pageSize: 10, total: 0, list: [] };
  }
  if (pathname.endsWith("/lyric")) {
    return { id: "", source: "qq", lineLyrics: null, karaokeLyrics: null };
  }
  if (pathname.endsWith("/play")) {
    return {
      id: "",
      source: "qq",
      playUrl: "",
      requestedQuality: "flac"
    };
  }
  return {};
}
