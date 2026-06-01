import { HttpError, RequestContext } from "../types";

type Source = "qq" | "netease" | "kuwo";
type Quality = "128k" | "320k" | "flac" | "flac24bit";
type SearchType = "song" | "album" | "artist" | "playlist";

interface SongItem {
  id: string;
  source: Source;
  name: string;
  artist: string;
  album?: string;
  albumId?: string;
  coverUrl?: string;
  durationMs?: number;
  durationSec?: number;
  availableQualities: Quality[];
}

interface SearchCollectionItem {
  id: string;
  source: Source;
  type: SearchType;
  name: string;
  artist?: string;
  creatorName?: string;
  coverUrl?: string;
  trackCount?: number;
  playCount?: number;
}

const JSON_HEADERS = {
  accept: "application/json",
  "user-agent": "Mozilla/5.0"
};

export async function musicProviderData(ctx: RequestContext): Promise<any> {
  const path = ctx.url.pathname;
  const source = parseSource(ctx.url.searchParams.get("source"));

  if (path.endsWith("/search")) {
    const keyword = required(ctx.url.searchParams.get("keyword"), "keyword is required", 1007, 404);
    const type = parseSearchType(ctx.url.searchParams.get("type"));
    const page = intParam(ctx, "page", 1, 1, 999);
    const pageSize = intParam(ctx, "pageSize", 10, 1, 30);
    return searchView(source, keyword, type, page, pageSize);
  }

  if (path.endsWith("/play")) {
    const id = required(ctx.url.searchParams.get("id"), "id is required", 1007, 404);
    const quality = parseQuality(ctx.url.searchParams.get("quality"));
    const info = await play(ctx, source, id, quality);
    if (ctx.user && info?.playUrl) {
      await recordProviderPlay(ctx, ctx.user.id, info).catch(() => undefined);
    }
    return info;
  }

  if (path.endsWith("/lyric")) {
    const id = required(ctx.url.searchParams.get("id"), "id is required", 1007, 404);
    return lyric(ctx, source, id);
  }

  if (path.endsWith("/toplist/detail")) {
    const id = required(ctx.url.searchParams.get("id"), "id is required", 1007, 404);
    return toplistDetail(source, id, intParam(ctx, "page", 1, 1, 999), intParam(ctx, "pageSize", 30, 1, 100));
  }

  if (path.endsWith("/toplist")) {
    return { source, list: await toplists(source) };
  }

  if (path.endsWith("/playlist/detail")) {
    const id = required(ctx.url.searchParams.get("id"), "id is required", 1007, 404);
    return playlistDetail(source, id, intParam(ctx, "page", 1, 1, 999), intParam(ctx, "pageSize", 30, 1, 100));
  }

  if (path.endsWith("/playlist")) {
    return playlists(
      source,
      ctx.url.searchParams.get("category") || undefined,
      ctx.url.searchParams.get("order") || undefined,
      intParam(ctx, "page", 1, 1, 999),
      intParam(ctx, "pageSize", 20, 1, 50)
    );
  }

  if (path.endsWith("/new")) {
    const id = source === "netease" ? "3779629" : source === "qq" ? "27" : "17";
    return toplistDetail(source, id, intParam(ctx, "page", 1, 1, 999), intParam(ctx, "pageSize", 30, 1, 100));
  }

  return {};
}

async function search(source: Source, keyword: string, page: number, pageSize: number): Promise<SongItem[]> {
  if (source === "qq") return qqSearch(keyword, page, pageSize);
  if (source === "netease") return neteaseSearch(keyword, page, pageSize);
  return kuwoSearch(keyword, page, pageSize);
}

async function searchView(source: Source, keyword: string, type: SearchType, page: number, pageSize: number): Promise<any> {
  if (type === "song") {
    const list = await search(source, keyword, page, pageSize);
    return { source, type, keyword, page, pageSize, total: null, list, songs: list, artists: [], albums: [], playlists: [] };
  }

  const result = await collectionSearch(source, keyword, type, page, pageSize);
  return {
    source,
    type,
    keyword,
    page,
    pageSize,
    total: result.total,
    list: [],
    songs: [],
    artists: type === "artist" ? result.list : [],
    albums: type === "album" ? result.list : [],
    playlists: type === "playlist" ? result.list : []
  };
}

async function collectionSearch(source: Source, keyword: string, type: SearchType, page: number, pageSize: number): Promise<{ total: number; list: SearchCollectionItem[] }> {
  if (source === "qq") return qqCollectionSearch(keyword, type, page, pageSize);
  if (source === "netease") return neteaseCollectionSearch(keyword, type, page, pageSize);
  return kuwoCollectionSearch(keyword, type, page, pageSize);
}

async function toplists(source: Source) {
  if (source === "qq") {
    const body = { req_1: { module: "musicToplist.ToplistInfoServer", method: "GetAll", param: {} } };
    const root = unwrapMusicUData(await qqMusicU(body, 1010));
    const groups = arr(root?.group);
    return groups.flatMap((group) => arr(asObj(group)?.toplist)).map((row) => {
      const item = asObj(row);
      return {
        id: str(item?.topId),
        source,
        name: first(str(item?.title), str(item?.titleDetail)),
        coverUrl: first(str(item?.headPicUrl), str(item?.picUrl), str(item?.frontPicUrl)),
        description: str(item?.titleDetail),
        updateTime: str(item?.updateTime)
      };
    }).filter((item) => item.id && item.name);
  }

  if (source === "netease") {
    const json = await fetchJson("https://music.163.com/api/toplist", { headers: neteaseHeaders() }, 1010);
    return arr(json.list).map((row) => {
      const item = asObj(row);
      return {
        id: str(item?.id),
        source,
        name: str(item?.name),
        coverUrl: str(item?.coverImgUrl),
        description: str(item?.description),
        updateTime: str(item?.updateFrequency)
      };
    }).filter((item) => item.id && item.name);
  }

  const url = withQuery("http://qukudata.kuwo.cn/q.k", { op: "query", cont: "tree", node: "2", pn: "0", rn: "1000", fmt: "json", level: "2" });
  const json = await fetchJson(url, { headers: JSON_HEADERS }, 1010);
  const out: any[] = [];
  walkKuwoTree(json, out);
  return out;
}

async function toplistDetail(source: Source, id: string, page: number, pageSize: number) {
  if (source === "qq") {
    const body = {
      req_1: {
        module: "musicToplist.ToplistInfoServer",
        method: "GetDetail",
        param: { topId: Number(id), offset: Math.max(0, (page - 1) * pageSize), num: pageSize, period: "" }
      }
    };
    const root = unwrapMusicUData(await qqMusicU(body, 1010));
    return {
      id: first(str(root?.topId), id),
      source,
      name: first(str(root?.title), str(root?.name)),
      coverUrl: first(str(root?.frontPicUrl), str(root?.headPicUrl), str(root?.picUrl)),
      description: str(root?.titleDetail),
      updateTime: str(root?.updateTime),
      page,
      pageSize,
      total: num(root?.songTotalNum) ?? num(root?.total_song_num) ?? num(root?.totalNum),
      list: songListFromUnknown(firstValue(root?.songInfoList, root?.song, asObj(root?.data)?.songInfoList, asObj(root?.data)?.song), "qq")
    };
  }

  if (source === "netease") {
    const json = await fetchJson(withQuery("https://music.163.com/api/v3/playlist/detail", { id, n: "1000", s: "0" }), { headers: neteaseHeaders() }, 1010);
    return parseNeteaseCollection(json, id, page, pageSize, true);
  }

  const json = await fetchJson(withQuery("http://kbangserver.kuwo.cn/ksong.s", {
    from: "pc", fmt: "json", pn: String(Math.max(0, page - 1)), rn: String(pageSize), type: "bang", data: "content", id
  }), { headers: JSON_HEADERS }, 1010);
  return {
    id,
    source,
    name: str(json.name),
    coverUrl: first(str(json.pic), str(json.picurl), str(json.img)),
    description: str(json.info),
    updateTime: str(json.pub),
    page,
    pageSize,
    total: num(json.total) ?? num(json.num),
    list: songListFromUnknown(json.musiclist, "kuwo")
  };
}

async function playlists(source: Source, category: string | undefined, order: string | undefined, page: number, pageSize: number) {
  if (source === "netease") {
    const cat = category?.trim() || "全部";
    const ord = order?.trim() || "hot";
    const json = await fetchJson(withQuery("https://music.163.com/api/playlist/list", {
      cat, order: ord, limit: String(pageSize), offset: String(Math.max(0, (page - 1) * pageSize))
    }), { headers: neteaseHeaders() }, 1011);
    return {
      source,
      category: cat,
      order: ord,
      page,
      pageSize,
      total: num(json.total),
      list: arr(json.playlists).map((row) => playlistItem(row, source))
    };
  }

  if (source === "kuwo") {
    const json = await fetchJson(withQuery("http://wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList", {
      loginUid: "0", loginSid: "0", appUid: "76039576", pn: String(page), rn: String(pageSize)
    }), { headers: JSON_HEADERS }, 1011);
    const data = asObj(json.data);
    return {
      source,
      page,
      pageSize,
      total: num(data?.total),
      list: arr(data?.data).map((row) => playlistItem(row, source))
    };
  }

  const rawOffset = Math.max(0, (page - 1) * pageSize);
  const json = await fetchJson(withQuery("https://c.y.qq.com/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg", {
    picmid: "1", rnd: "0.123", g_tk: "732560869", loginUin: "0", hostUin: "0", format: "json",
    inCharset: "utf8", outCharset: "utf-8", notice: "0", platform: "yqq.json", needNewCode: "0",
    categoryId: qqPlaylistCategoryId(category), sortId: qqPlaylistSortId(order), sin: String(rawOffset), ein: String(rawOffset + pageSize - 1)
  }), { headers: { ...JSON_HEADERS, referer: "https://y.qq.com/" } }, 1011);
  return {
    source,
    category,
    order: order || "hot",
    page,
    pageSize,
    total: num(asObj(json.data)?.sum),
    list: arr(asObj(json.data)?.list).map((row) => playlistItem(row, source))
  };
}

async function playlistDetail(source: Source, id: string, page: number, pageSize: number) {
  if (source === "netease") {
    const json = await fetchJson(withQuery("https://music.163.com/api/v3/playlist/detail", { id, n: "1000", s: "0" }), { headers: neteaseHeaders() }, 1011);
    return parseNeteaseCollection(json, id, page, pageSize, false);
  }

  if (source === "kuwo") {
    const json = await fetchJson(withQuery("http://nplserver.kuwo.cn/pl.svc", {
      op: "getlistinfo", pid: id, pn: String(Math.max(0, page - 1)), rn: String(pageSize), encode: "utf8",
      keyset: "pl2012", vipver: "MUSIC_9.1.1.2_BCS2", newver: "1"
    }), { headers: JSON_HEADERS }, 1011);
    return {
      id: first(str(json.id), id),
      source,
      name: first(str(json.title), str(json.name)),
      coverUrl: first(str(json.pic), str(json.img)),
      description: first(str(json.info), str(json.desc)),
      creatorName: str(json.uname),
      playCount: num(json.playnum),
      updateTime: str(json.abstime),
      page,
      pageSize,
      total: num(json.total) ?? num(json.validtotal),
      list: songListFromUnknown(json.musiclist, source)
    };
  }

  const json = await fetchJson(withQuery("https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg", {
    type: "1", json: "1", utf8: "1", onlysong: "0", new_format: "1", disstid: id
  }), { headers: { ...JSON_HEADERS, referer: "https://y.qq.com/" } }, 1011, true);
  const playlist = asObj(arr(json.cdlist)[0]);
  const songs = arr(playlist?.songlist);
  const from = Math.max(0, (page - 1) * pageSize);
  return {
    id: first(str(playlist?.disstid), id),
    source,
    name: first(str(playlist?.dissname), str(playlist?.title)),
    coverUrl: first(str(playlist?.logo), str(playlist?.picurl)),
    description: htmlUnescape(str(playlist?.desc)),
    creatorName: first(str(playlist?.nickname), str(playlist?.nick)),
    playCount: num(playlist?.visitnum),
    updateTime: first(str(playlist?.mtime), str(playlist?.ctime)),
    page,
    pageSize,
    total: num(playlist?.songnum) ?? num(playlist?.total_song_num) ?? songs.length,
    list: songs.slice(from, from + pageSize).map((row) => toSong(row, source)).filter((song) => song.id)
  };
}

async function lyric(ctx: RequestContext, source: Source, id: string) {
  if (source === "netease") {
    const json = await fetchJson(withQuery("https://music.163.com/api/song/lyric/v1", {
      cp: "false", id, lv: "0", tv: "0", rv: "0", kv: "0", yv: "0", ytv: "0", yrv: "0"
    }), { headers: neteaseHeaders() }, 1006);
    return { id, source, lineLyrics: str(asObj(json.lrc)?.lyric) || null, karaokeLyrics: str(asObj(json.yrc)?.lyric) || str(asObj(json.klyric)?.lyric) || null };
  }

  if (source === "qq") {
    const json = await fetchJson(withQuery("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg", {
      songmid: id, format: "json", nobase64: "1"
    }), { headers: { ...JSON_HEADERS, referer: "https://y.qq.com/" } }, 1006);
    return { id, source, lineLyrics: str(json.lyric) || null, karaokeLyrics: null };
  }

  const info = await play(ctx, source, id, "flac");
  return { id, source, lineLyrics: info.lyric?.lineLyrics || null, karaokeLyrics: info.lyric?.karaokeLyrics || null };
}

async function play(ctx: RequestContext | null, source: Source, id: string, quality: Quality) {
  const token = await getTuneFreeToken(ctx);
  const json = await fetchJson(withQuery("https://tf-pay.sayqz.com/api/music/", {
    id,
    platform: source === "kuwo" ? "kw" : source,
    quality,
    token
  }), {}, 1005);
  const data0 = extractDataZero(json);
  const playUrl = str(data0?.url);
  if (!playUrl) throw new HttpError(502, `no playable url for ${source}:${id}`, 1008);
  const info = asObj(data0?.info);
  const line = str(data0?.lyrics);
  const karaoke = str(data0?.wordByWordLyrics);
  return {
    id,
    source,
    actualSource: source,
    name: str(info?.name),
    artist: str(info?.artist),
    album: str(info?.album),
    coverUrl: str(data0?.cover),
    durationSec: num(info?.duration),
    playUrl,
    requestedQuality: first(str(data0?.requestedQuality), quality),
    actualQuality: str(data0?.actualQuality),
    fileSize: num(data0?.fileSize),
    expireSec: num(data0?.expire),
    fromCache: bool(data0?.fromCache),
    lyric: line || karaoke ? { lineLyrics: line || null, karaokeLyrics: karaoke || null } : undefined
  };
}

async function getTuneFreeToken(ctx: RequestContext | null): Promise<string> {
  if (!ctx) throw new HttpError(502, "music.tunefree.token is required", 1003);
  const token = await config(ctx, "music.tunefree.token");
  if (token) return token;
  const account = await config(ctx, "music.tunefree.account");
  const password = await config(ctx, "music.tunefree.password");
  const udid = (await config(ctx, "music.tunefree.udid")) || "TUNEFREENEXT_BFF_001";
  if (!account || !password) throw new HttpError(502, "music.tunefree.account / music.tunefree.password not configured", 1003);

  const json = await fetchJson(withQuery("https://ums.sayqz.com/api/user/1000/V3/3.0.9/logon", { account, password, udid }), {}, 1003);
  const next = str(asObj(json.data)?.token);
  if (!next) throw new HttpError(502, "tunefree logon response missing token", 1003);
  await upsertConfig(ctx, "music.tunefree.token", next, "TuneFreeNext token, refreshed from Worker");
  await upsertConfig(ctx, "music.tunefree.token_updated_at", new Date().toISOString(), null);
  await upsertConfig(ctx, "music.tunefree.token_status", "ok", null);
  return next;
}

async function qqSearch(keyword: string, page: number, pageSize: number): Promise<SongItem[]> {
  const json = await fetchJson(withQuery("https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp", {
    g_tk: "5381",
    uin: "0",
    format: "json",
    inCharset: "utf-8",
    outCharset: "utf-8",
    notice: "0",
    platform: "h5",
    needNewCode: "1",
    w: keyword,
    zhidaqu: "1",
    catZhida: "1",
    t: "0",
    flag: "1",
    ie: "utf-8",
    sem: "1",
    aggr: "0",
    perpage: String(pageSize),
    n: String(pageSize),
    p: String(page),
    remoteplace: "txt.mqq.all"
  }), { headers: { ...JSON_HEADERS, referer: "https://y.qq.com/" } }, 1004);
  return songListFromUnknown(asObj(asObj(json.data)?.song)?.list, "qq");
}

async function qqCollectionSearch(keyword: string, type: SearchType, page: number, pageSize: number): Promise<{ total: number; list: SearchCollectionItem[] }> {
  if (type === "artist") {
    const json = await fetchJson(withQuery("https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg", {
      format: "json",
      inCharset: "utf8",
      outCharset: "utf-8",
      key: keyword
    }), { headers: { ...JSON_HEADERS, referer: "https://y.qq.com/" } }, 1004);
    const box = asObj(asObj(json.data)?.singer);
    const list = arr(box?.itemlist)
      .map((row) => qqCollectionItem(row, "artist"))
      .filter((item) => item.id && item.name && matchesKeyword(item.name, keyword));
    return { total: list.length, list: slicePage(list, page, pageSize) };
  }

  if (type === "album") {
    const json = await qqSearchCp(keyword, page, pageSize, "8");
    const album = asObj(asObj(json.data)?.album);
    return {
      total: num(album?.totalnum) ?? 0,
      list: arr(album?.list).map((row) => qqCollectionItem(row, "album")).filter((item) => item.id && item.name)
    };
  }

  return emptyCollectionSearch();
}

async function neteaseSearch(keyword: string, page: number, pageSize: number): Promise<SongItem[]> {
  const json = await neteaseSearchJson(keyword, "song", page, pageSize);
  return songListFromUnknown(asObj(json.result)?.songs, "netease");
}

async function neteaseCollectionSearch(keyword: string, type: SearchType, page: number, pageSize: number): Promise<{ total: number; list: SearchCollectionItem[] }> {
  const json = await neteaseSearchJson(keyword, type, page, pageSize);
  const result = asObj(json.result);
  if (type === "artist") {
    const list = arr(result?.artists)
      .map((row) => collectionItem(row, "artist"))
      .filter((item) => item.id && item.name && matchesKeyword(item.name, keyword));
    return {
      total: list.length,
      list
    };
  }
  if (type === "album") {
    return {
      total: num(result?.albumCount) ?? 0,
      list: arr(result?.albums).map((row) => collectionItem(row, "album")).filter((item) => item.id && item.name)
    };
  }
  return {
    total: num(result?.playlistCount) ?? 0,
    list: arr(result?.playlists).map((row) => collectionItem(row, "playlist")).filter((item) => item.id && item.name)
  };
}

async function kuwoCollectionSearch(keyword: string, type: SearchType, page: number, pageSize: number): Promise<{ total: number; list: SearchCollectionItem[] }> {
  const json = await fetchJson(withQuery("http://search.kuwo.cn/r.s", {
    client: "kt",
    all: keyword,
    pn: String(Math.max(0, page - 1)),
    rn: String(pageSize),
    ft: type === "playlist" ? "playlist" : type,
    encoding: "utf8",
    rformat: "json",
    vipver: "MUSIC_9.0.5.0_W1",
    newver: "1"
  }), { headers: JSON_HEADERS }, 1004, false, true);
  const list = arr(firstValue(json.abslist, json.albumlist))
      .map((row) => kuwoCollectionItem(row, type))
      .filter((item) => item.id && item.name && (type !== "artist" || matchesKeyword(item.name, keyword)));
  return {
    total: type === "artist" ? list.length : num(firstValue(json.total, json.TOTAL, json.HIT)) ?? 0,
    list
  };
}

async function qqSearchCp(keyword: string, page: number, pageSize: number, t: string): Promise<any> {
  return fetchJson(withQuery("https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp", {
    g_tk: "5381",
    uin: "0",
    format: "json",
    inCharset: "utf-8",
    outCharset: "utf-8",
    notice: "0",
    platform: "h5",
    needNewCode: "1",
    w: keyword,
    zhidaqu: "1",
    catZhida: "1",
    t,
    flag: "1",
    ie: "utf-8",
    sem: "1",
    aggr: "0",
    perpage: String(pageSize),
    n: String(pageSize),
    p: String(page),
    remoteplace: "txt.mqq.all"
  }), { headers: { ...JSON_HEADERS, referer: "https://y.qq.com/" } }, 1004);
}

async function neteaseSearchJson(keyword: string, type: SearchType, page: number, pageSize: number): Promise<any> {
  const params = {
    s: keyword,
    type: neteaseSearchCode(type),
    offset: String(Math.max(0, (page - 1) * pageSize)),
    limit: String(pageSize)
  };
  try {
    return await fetchJson(withQuery("https://music.163.com/api/cloudsearch/pc", params), { headers: neteaseHeaders() }, 1004);
  } catch (error) {
    console.warn("Netease cloudsearch failed, falling back to legacy search", {
      message: error instanceof Error ? error.message : String(error)
    });
    return fetchJson(withQuery("https://music.163.com/api/search/get/web", params), { headers: neteaseHeaders() }, 1004);
  }
}

async function kuwoSearch(keyword: string, page: number, pageSize: number): Promise<SongItem[]> {
  const json = await fetchJson(withQuery("http://search.kuwo.cn/r.s", {
    client: "kt", all: keyword, pn: String(Math.max(0, page - 1)), rn: String(pageSize), ft: "music",
    encoding: "utf8", rformat: "json", vipver: "MUSIC_9.0.5.0_W1", newver: "1"
  }), { headers: JSON_HEADERS }, 1004, false, true);
  return songListFromUnknown(json.abslist, "kuwo");
}

async function qqMusicU(body: any, errorCode: number): Promise<any> {
  const url = withQuery("https://u.y.qq.com/cgi-bin/musicu.fcg", {
    loginUin: "0", hostUin: "0", format: "json", inCharset: "utf-8", outCharset: "utf-8",
    notice: "0", platform: "wk_v15.json", needNewCode: "0"
  });
  return fetchJson(url, {
    method: "POST",
    headers: { ...JSON_HEADERS, referer: "https://y.qq.com/", "content-type": "application/json; charset=utf-8" },
    body: escapeNonAsciiJson(body)
  }, errorCode);
}

function parseNeteaseCollection(json: any, reqId: string, page: number, pageSize: number, toplist: boolean) {
  const playlist = asObj(json.playlist);
  const tracks = arr(playlist?.tracks);
  const from = Math.max(0, (page - 1) * pageSize);
  return {
    id: first(str(playlist?.id), reqId),
    source: "netease" as Source,
    name: str(playlist?.name),
    coverUrl: str(playlist?.coverImgUrl),
    description: str(playlist?.description),
    creatorName: toplist ? undefined : str(asObj(playlist?.creator)?.nickname),
    playCount: toplist ? undefined : num(playlist?.playCount),
    updateTime: str(playlist?.updateTime),
    page,
    pageSize,
    total: num(playlist?.trackCount) ?? tracks.length,
    list: tracks.slice(from, from + pageSize).map((row) => toSong(row, "netease")).filter((song) => song.id)
  };
}

function toSong(row: any, source: Source): SongItem {
  const item = asObj(row) || {};
  if (source === "qq") {
    const album = asObj(item.album);
    const mid = first(str(album?.mid), str(item.albummid));
    return {
      id: first(str(item.mid), str(item.songmid), str(item.id), str(item.songid)),
      source,
      name: first(str(item.title), str(item.name), str(item.songname), str(item.songorig)),
      artist: joinNamed(item.singer),
      album: first(str(album?.title), str(album?.name), str(item.albumname), str(item.albumdesc)),
      albumId: mid,
      coverUrl: mid ? `https://y.gtimg.cn/music/photo_new/T002R500x500M000${mid}.jpg` : undefined,
      durationSec: num(item.interval),
      durationMs: num(item.interval) == null ? undefined : Number(item.interval) * 1000,
      availableQualities: qqQualities(asObj(item.file), item)
    };
  }
  if (source === "netease") {
    const album = asObj(item.al) || asObj(item.album);
    const durationMs = num(item.dt);
    return {
      id: str(item.id),
      source,
      name: str(item.name),
      artist: joinNamed(item.ar || item.artists),
      album: str(album?.name),
      albumId: str(album?.id),
      coverUrl: str(album?.picUrl),
      durationMs,
      durationSec: durationMs == null ? undefined : Math.floor(durationMs / 1000),
      availableQualities: neteaseQualities(item)
    };
  }
  const durationSec = num(firstValue(item.duration, item.DURATION));
  return {
    id: first(str(item.id), str(item.musicrid), str(item.MUSICRID), str(item.DC_TARGETID)),
    source,
    name: first(str(item.name), str(item.songname), str(item.NAME), str(item.SONGNAME)),
    artist: first(str(item.artist), str(item.ARTIST), str(item.FARTIST)),
    album: first(str(item.album), str(item.ALBUM), str(item.FALBUM)),
    coverUrl: first(str(item.albumpic), str(item.pic)),
    durationSec,
    durationMs: durationSec == null ? undefined : durationSec * 1000,
    availableQualities: kuwoQualities(first(str(item.N_MINFO), str(item.MINFO)))
  };
}

function playlistItem(row: any, source: Source) {
  const item = asObj(row) || {};
  if (source === "qq") {
    return {
      id: str(item.dissid),
      source,
      name: str(item.dissname),
      coverUrl: str(item.imgurl),
      description: str(item.introduction),
      creatorName: str(asObj(item.creator)?.name),
      playCount: num(item.listennum)
    };
  }
  if (source === "netease") {
    return {
      id: str(item.id),
      source,
      name: str(item.name),
      coverUrl: str(item.coverImgUrl),
      description: str(item.description),
      creatorName: str(asObj(item.creator)?.nickname),
      trackCount: num(item.trackCount),
      playCount: num(item.playCount)
    };
  }
  return {
    id: str(item.id),
    source,
    name: str(item.name),
    coverUrl: first(str(item.img), str(item.pic)),
    description: first(str(item.info), str(item.desc)),
    creatorName: str(item.uname),
    trackCount: num(item.total),
    playCount: num(item.listencnt)
  };
}

function songListFromUnknown(value: any, source: Source): SongItem[] {
  return arr(value).map((row) => toSong(row, source)).filter((song) => song.id && song.name);
}

function collectionItem(row: any, type: SearchType): SearchCollectionItem {
  const item = asObj(row) || {};
  if (type === "artist") {
    return {
      id: str(item.id),
      source: "netease",
      type,
      name: str(item.name),
      coverUrl: first(str(item.picUrl), str(item.img1v1Url)),
      trackCount: num(item.musicSize)
    };
  }
  if (type === "album") {
    return {
      id: str(item.id),
      source: "netease",
      type,
      name: str(item.name),
      artist: joinNamed(item.artists || (item.artist ? [item.artist] : [])),
      coverUrl: str(item.picUrl),
      trackCount: num(item.size)
    };
  }
  return {
    id: str(item.id),
    source: "netease",
    type,
    name: str(item.name),
    creatorName: str(asObj(item.creator)?.nickname),
    coverUrl: str(item.coverImgUrl),
    trackCount: num(item.trackCount),
    playCount: num(item.playCount)
  };
}

function qqCollectionItem(row: any, type: SearchType): SearchCollectionItem {
  const item = asObj(row) || {};
  if (type === "artist") {
    return {
      id: first(str(item.mid), str(item.id), str(item.docid)),
      source: "qq",
      type,
      name: first(str(item.name), str(item.singer)),
      coverUrl: str(item.pic)
    };
  }
  return {
    id: first(str(item.albumMID), str(item.albumMid), str(item.mid), str(item.albumID), str(item.id), str(item.docid)),
    source: "qq",
    type,
    name: first(str(item.albumName), str(item.name)),
    artist: first(str(item.singerName), str(item.singer)),
    coverUrl: first(str(item.pic), str(item.albumMID) ? `https://y.gtimg.cn/music/photo_new/T002R500x500M000${str(item.albumMID)}.jpg` : "")
  };
}

function kuwoCollectionItem(row: any, type: SearchType): SearchCollectionItem {
  const item = asObj(row) || {};
  if (type === "artist") {
    return {
      id: first(str(item.ARTISTID), str(item.artistid), str(item.DC_TARGETID), str(item.id)),
      source: "kuwo",
      type,
      name: first(str(item.ARTIST), str(item.name)),
      coverUrl: first(str(item.hts_PICPATH), absoluteKuwoImage(str(item.PICPATH)), str(item.artistpic)),
      trackCount: num(firstValue(item.SONGNUM, item.songnum))
    };
  }
  if (type === "album") {
    return {
      id: first(str(item.albumid), str(item.id), str(item.DC_TARGETID)),
      source: "kuwo",
      type,
      name: first(str(item.name), str(item.album), str(item.ALBUM)),
      artist: first(str(item.artist), str(item.ARTIST), str(item.fartist)),
      coverUrl: first(str(item.hts_img), str(item.img), absoluteKuwoImage(str(item.pic))),
      trackCount: num(firstValue(item.musiccnt, item.songnum)),
      playCount: num(firstValue(item.PLAYCNT, item.playcnt))
    };
  }
  return {
    id: first(str(item.playlistid), str(item.id), str(item.DC_TARGETID)),
    source: "kuwo",
    type,
    name: first(str(item.name), str(item.title)),
    creatorName: first(str(item.nickname), str(item.uname)),
    coverUrl: first(str(item.hts_pic), str(item.pic), str(item.img)),
    trackCount: num(firstValue(item.songnum, item.total)),
    playCount: num(firstValue(item.playcnt, item.listencnt))
  };
}

function emptyCollectionSearch(): { total: number; list: SearchCollectionItem[] } {
  return { total: 0, list: [] };
}

function slicePage<T>(items: T[], page: number, pageSize: number): T[] {
  const from = Math.max(0, (page - 1) * pageSize);
  return items.slice(from, from + pageSize);
}

function absoluteKuwoImage(path?: string): string {
  if (!path) return "";
  if (/^https?:\/\//i.test(path)) return path;
  return `https://img1.kuwo.cn/star/starheads/${path.replace(/^\/+/, "")}`;
}

async function fetchJson(url: string, init: RequestInit, errorCode: number, jsonp = false, relaxed = false): Promise<any> {
  let response: Response;
  try {
    response = await fetch(url, init);
  } catch (error) {
    throw new HttpError(errorCode === 1007 ? 404 : 502, error instanceof Error ? error.message : "music upstream failed", errorCode);
  }
  const raw = await response.text();
  if (!response.ok) throw new HttpError(502, `music upstream returned ${response.status}`, errorCode);
  try {
    return JSON.parse(jsonp ? stripJsonp(raw) : relaxed ? relaxJsonLike(raw) : raw);
  } catch {
    throw new HttpError(502, "music upstream returned invalid json", errorCode);
  }
}

async function recordProviderPlay(ctx: RequestContext, userId: number, playInfo: any): Promise<void> {
  await ctx.env.DB.prepare(
    `INSERT INTO music_play_history(user_id, source, song_id, name, artist, album, cover_url, duration_sec)
     VALUES(?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(user_id, source, song_id) DO UPDATE SET
       name = excluded.name, artist = excluded.artist, album = excluded.album,
       cover_url = excluded.cover_url, duration_sec = excluded.duration_sec, played_at = CURRENT_TIMESTAMP`
  ).bind(
    userId,
    playInfo.source,
    playInfo.id,
    playInfo.name || "",
    playInfo.artist || null,
    playInfo.album || null,
    playInfo.coverUrl || null,
    Number.isFinite(Number(playInfo.durationSec)) ? Number(playInfo.durationSec) : null
  ).run();
}

async function config(ctx: RequestContext, key: string): Promise<string> {
  const row = await ctx.env.DB.prepare("SELECT config_value FROM sys_config WHERE config_key = ?").bind(key).first<{ config_value: string }>();
  return row?.config_value || "";
}

async function upsertConfig(ctx: RequestContext, key: string, value: string, description: string | null): Promise<void> {
  await ctx.env.DB.prepare(
    `INSERT INTO sys_config(config_key, config_value, description) VALUES(?, ?, ?)
     ON CONFLICT(config_key) DO UPDATE SET config_value = excluded.config_value, updated_at = CURRENT_TIMESTAMP`
  ).bind(key, value, description).run();
}

function parseSource(raw: string | null): Source {
  const value = (raw || "").toLowerCase();
  if (value === "qq" || value === "netease" || value === "kuwo") return value;
  throw new HttpError(422, "invalid source: allowed qq, netease, kuwo", 1001);
}

function parseQuality(raw: string | null): Quality {
  const value = (raw || "flac").toLowerCase();
  if (value === "128k" || value === "320k" || value === "flac" || value === "flac24bit") return value;
  throw new HttpError(422, "invalid quality: allowed 128k, 320k, flac, flac24bit", 1002);
}

function parseSearchType(raw: string | null): SearchType {
  const value = (raw || "song").toLowerCase();
  if (value === "song" || value === "songs" || value === "track" || value === "tracks") return "song";
  if (value === "album" || value === "albums") return "album";
  if (value === "artist" || value === "artists" || value === "singer" || value === "singers") return "artist";
  if (value === "playlist" || value === "playlists" || value === "songlist" || value === "songlists") return "playlist";
  return "song";
}

function intParam(ctx: RequestContext, name: string, fallback: number, min: number, max: number): number {
  const parsed = Number.parseInt(ctx.url.searchParams.get(name) || "", 10);
  return Math.min(max, Math.max(min, Number.isFinite(parsed) ? parsed : fallback));
}

function required(value: string | null, message: string, code: number, status: number): string {
  if (!value?.trim()) throw new HttpError(status, message, code);
  return value.trim();
}

function withQuery(base: string, params: Record<string, string | number | undefined>): string {
  const url = new URL(base);
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== "") url.searchParams.set(key, String(value));
  }
  return url.toString();
}

function neteaseHeaders() {
  return { ...JSON_HEADERS, referer: "https://music.163.com", cookie: "os=pc" };
}

function neteaseSearchCode(type: SearchType): string {
  if (type === "album") return "10";
  if (type === "artist") return "100";
  if (type === "playlist") return "1000";
  return "1";
}

function unwrapMusicUData(json: any): any {
  const data = asObj(asObj(json.req_1)?.data);
  const inner = asObj(data?.data);
  return inner ? { ...data, ...inner } : data;
}

function extractDataZero(json: any): any {
  const outer = json.data;
  const nested = asObj(outer)?.data;
  return asObj(arr(nested)[0]) || asObj(arr(outer)[0]);
}

function walkKuwoTree(node: any, out: any[]): void {
  const item = asObj(node);
  if (!item) return;
  const id = str(item.sourceid);
  if (id) {
    out.push({
      id,
      source: "kuwo",
      name: first(str(item.name), str(item.disname)),
      coverUrl: first(str(item.pic), str(item.picurl), str(item.img)),
      description: str(item.info),
      updateTime: str(item.pub)
    });
  }
  for (const child of arr(item.child)) walkKuwoTree(child, out);
}

function qqQualities(file: any, item?: any): Quality[] {
  const q: Quality[] = [];
  if (num(file?.size_128mp3)) q.push("128k");
  if (num(file?.size_320mp3)) q.push("320k");
  if (num(file?.size_flac)) q.push("flac");
  if (q.length === 0 && item) {
    if (num(item.size128) || num(item.size128mp3)) q.push("128k");
    if (num(item.size320) || num(item.size320mp3)) q.push("320k");
    if (num(item.sizeflac)) q.push("flac");
  }
  return q;
}

function neteaseQualities(song: any): Quality[] {
  const q: Quality[] = [];
  if (song.l) q.push("128k");
  if (song.h) q.push("320k");
  if (song.sq) q.push("flac");
  if (song.hr) q.push("flac24bit");
  return q;
}

function kuwoQualities(value?: string): Quality[] {
  const lower = (value || "").toLowerCase();
  const q: Quality[] = [];
  if (lower.includes("128k") || lower.includes("bitrate:128")) q.push("128k");
  if (lower.includes("320k") || lower.includes("bitrate:320")) q.push("320k");
  if (lower.includes("flac")) q.push("flac");
  return q;
}

function qqPlaylistCategoryId(category?: string): string {
  const trimmed = category?.trim() || "";
  return /^\d+$/.test(trimmed) ? trimmed : "10000000";
}

function qqPlaylistSortId(order?: string): string {
  const trimmed = (order || "").trim().toLowerCase();
  if (trimmed === "new" || trimmed === "latest" || trimmed === "time") return "2";
  if (/^\d+$/.test(trimmed)) return trimmed;
  return "5";
}

function stripJsonp(raw: string): string {
  const start = raw.indexOf("(");
  const end = raw.lastIndexOf(")");
  return start >= 0 && end > start ? raw.slice(start + 1, end) : raw;
}

function relaxJsonLike(raw: string): string {
  return raw
    .replace(/'/g, "\"")
    .replace(/\bNone\b/g, "null")
    .replace(/\bTrue\b/g, "true")
    .replace(/\bFalse\b/g, "false");
}

function escapeNonAsciiJson(value: unknown): string {
  return JSON.stringify(value).replace(/[^\x00-\x7F]/g, (char) => `\\u${char.charCodeAt(0).toString(16).padStart(4, "0")}`);
}

function htmlUnescape(value?: string): string | undefined {
  return value?.replace(/&#160;/g, " ").replace(/&nbsp;/g, " ").replace(/&amp;/g, "&").replace(/&quot;/g, "\"");
}

function joinNamed(value: any): string {
  return arr(value).map((item) => str(asObj(item)?.name)).filter(Boolean).join(" / ");
}

function asObj(value: any): Record<string, any> | null {
  return value && typeof value === "object" && !Array.isArray(value) ? value : null;
}

function arr(value: any): any[] {
  return Array.isArray(value) ? value : [];
}

function str(value: any): string {
  return value == null ? "" : String(value);
}

function matchesKeyword(value: string, keyword: string): boolean {
  const normalizedValue = value.toLowerCase().replace(/\s+/g, "");
  const normalizedKeyword = keyword.toLowerCase().replace(/\s+/g, "");
  return normalizedKeyword === "" || normalizedValue.includes(normalizedKeyword);
}

function num(value: any): number | undefined {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string" && value.trim()) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : undefined;
  }
  return undefined;
}

function bool(value: any): boolean | undefined {
  if (typeof value === "boolean") return value;
  if (typeof value === "string") return value === "true";
  return undefined;
}

function first(...values: Array<string | undefined>): string {
  return values.find((value) => value != null && value !== "") || "";
}

function firstValue(...values: any[]): any {
  return values.find((value) => value != null);
}
