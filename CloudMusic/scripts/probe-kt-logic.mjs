// 复刻 Kotlin 侧 PlaylistImportService 的判定逻辑，验证 39 首 QQ 歌单的分类结果
// 对应实现：data/playlist/PlaylistModels.kt + PlaylistImportService.kt + AudioSources.kt
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
const UA_N = 'Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36';
const R163 = 'https://music.163.com/';
const RKW = 'https://www.kuwo.cn/';
const DURATION_TOLERANCE = 25;
const MAX_RESOLVE_PER_SOURCE = 3;

// ---------------- TrackMatcher ----------------
function norm(s) {
  return String(s || '').toLowerCase().replace(/[^\p{L}\p{N}]/gu, '');
}
function stripBrackets(s) {
  return String(s || '').replace(/[（(【\[].*?[)）】\]]/g, ' ').trim();
}
const VARIANT_TAGS = [
  [/伴奏/, '伴奏'],
  [/纯音乐|instrumental/i, '纯音乐'],
  [/吉他版|贝斯版|鼓版|钢琴版|钢琴曲|\bpiano\b/i, '器乐版'],
  [/\blive\b|演唱会|现场/i, '现场版'],
  [/\bdj\b/i, 'DJ版'],
  [/\bremix\b/i, 'Remix'],
  [/\bdemo\b/i, 'Demo'],
  [/acoustic/i, '不插电版'],
  [/超燃版|超燃/i, '超燃版'],
  [/电视剧版|影视版|电影版|动画版|游戏版/i, '影视版'],
  [/翻唱|\bcover\b/i, '翻唱'],
  [/铃声|rington/i, '铃声'],
];
function variantTag(title, artist = '') {
  const raw = (title || '') + ' ' + (artist || '');
  for (const [re, tag] of VARIANT_TAGS) if (re.test(raw)) return tag;
  return null;
}
function sameTitle(a, b) {
  const na = norm(stripBrackets(a)), nb = norm(stripBrackets(b));
  return !!na && na === nb;
}
function artistHit(target, candidate) {
  const n = norm(target);
  if (!n) return true;
  const c = norm(candidate);
  if (!c) return false;
  if (c.includes(n)) return true;
  return c.split('&').some(x => norm(x) === n);
}
function durationClose(a, b, tol = DURATION_TOLERANCE) {
  if (a <= 0 || b <= 0) return false;
  return Math.abs(a - b) <= tol;
}
function tooShort(candidateSec, targetSec) {
  if (candidateSec <= 0 || targetSec <= 0) return false;
  return candidateSec < targetSec * 0.6;
}
function rank(c, title, artist, dur) {
  let s = 0;
  if (sameTitle(c.title, title)) s += 10;
  if (artistHit(artist, c.artist)) s += 8;
  if (durationClose(c.durationSec, dur)) s += 5;
  if (c.variantTag != null) s -= 8;
  return s;
}

// ---------------- HTTP ----------------
async function get(url, referer, ua = UA) {
  try {
    const r = await fetch(url, { headers: { 'User-Agent': ua, Referer: referer }, redirect: 'follow' });
    return r.ok ? await r.text() : null;
  } catch (e) { return null; }
}
async function redirectOnce(url, referer, ua) {
  try {
    const r = await fetch(url, { headers: { 'User-Agent': ua, Referer: referer }, redirect: 'manual' });
    return r.headers.get('location');
  } catch (e) { return null; }
}
async function headLength(url, referer, ua) {
  try {
    const r = await fetch(url, { method: 'HEAD', headers: { 'User-Agent': ua, Referer: referer } });
    if (!r.ok) return null;
    const t = (r.headers.get('content-type') || '');
    if (t.includes('text/html') || t.includes('application/json')) return null;
    const len = parseInt(r.headers.get('content-length') || '0', 10);
    return len > 0 ? len : null;
  } catch (e) { return null; }
}
const https = u => u.startsWith('http://') ? 'https://' + u.slice(7) : u;
const BPS = 16000, RATIO = 0.85;

// ---------------- 音源 ----------------
async function neteaseSearch(title, artist, dur) {
  const merged = new Map();
  for (const kw of [...new Set([`${title} ${artist}`, title].map(s => s.trim()).filter(Boolean))]) {
    const txt = await get(`${R163}api/search/get?s=${encodeURIComponent(kw)}&type=1&limit=25&offset=0`, R163, UA_N);
    if (!txt) continue;
    let songs; try { songs = JSON.parse(txt).result.songs; } catch (e) { continue; }
    for (const s of songs || []) {
      const id = String(s.id);
      if (merged.has(id)) continue;
      const art = (s.artists || []).map(a => a.name).join(' / ');
      merged.set(id, { sourceId: 'netease', sourceLabel: '网易云', songId: id, title: s.name || '', artist: art,
        album: (s.album && s.album.name) || '', durationSec: Math.round((s.duration || 0) / 1000),
        variantTag: variantTag(s.name || '', art) });
    }
  }
  return [...merged.values()].sort((a, b) => rank(b, title, artist, dur) - rank(a, title, artist, dur));
}
async function neteaseResolve(c) {
  const loc = await redirectOnce(`${R163}song/media/outer/url?id=${c.songId}.mp3`, R163, UA_N);
  if (!loc) return null;
  if (loc.includes('/404')) return null;
  const url = https(loc);
  const bytes = await headLength(url, R163, UA_N);
  if (!bytes) return null;
  return { ...c, audioUrl: url, partial: c.durationSec > 0 && bytes < c.durationSec * BPS * RATIO };
}
function unescKuwo(s) {
  return s.replace(/\\u0026/g, '&').replace(/\\\\/g, '\\').replace(/&nbsp;/g, ' ').replace(/\\/g, '').trim();
}
function field(chunk, key) {
  const m = chunk.match(new RegExp("'" + key + "':'([^']*)'"));
  return m ? unescKuwo(m[1]) : null;
}
async function kuwoSearch(title, artist, dur) {
  const merged = new Map();
  for (const kw of [...new Set([`${title} ${artist}`, title].map(s => s.trim()).filter(Boolean))]) {
    const txt = await get(`https://search.kuwo.cn/r.s?all=${encodeURIComponent(kw)}&ft=music&itemset=web_2013&client=kt&pn=0&rn=20&rformat=json&encoding=utf8`, RKW);
    if (!txt) continue;
    for (const chunk of txt.split('},{')) {
      const rid = field(chunk, 'DC_TARGETID');
      if (!rid || merged.has(rid)) continue;
      if (field(chunk, 'ONLINE') !== '1') continue;
      const fm = field(chunk, 'FORMATS') || '';
      if (!/MP3/i.test(fm)) continue;
      const t = field(chunk, 'SONGNAME') || '';
      const a = field(chunk, 'ARTIST') || '';
      merged.set(rid, { sourceId: 'kuwo:' + rid, sourceLabel: '酷我', songId: rid, title: t, artist: a,
        album: '', durationSec: parseInt(field(chunk, 'DURATION') || '0', 10),
        variantTag: variantTag(t, a) });
    }
  }
  return [...merged.values()].sort((a, b) => rank(b, title, artist, dur) - rank(a, title, artist, dur));
}
async function kuwoResolve(c) {
  const txt = await get(`https://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3&rid=${c.songId}&br=128&response=url`, RKW);
  if (!txt) return null;
  const m = txt.match(/https?:\/\/[^\s'"<>]+\.(mp3|aac|flac)/i);
  if (!m) return null;
  const url = https(m[0]);
  const bytes = await headLength(url, RKW, UA);
  if (!bytes) return null;
  return { ...c, audioUrl: url, partial: c.durationSec > 0 && bytes < c.durationSec * BPS * RATIO };
}

// ---------------- 服务编排 ----------------
function isStrict(c, track, targetTag) {
  if (c.partial) return false;
  if (!sameTitle(c.title, track.title)) return false;
  if (!artistHit(track.artist, c.artist)) return false;
  if (!durationClose(c.durationSec, track.durationSec, DURATION_TOLERANCE)) return false;
  if (tooShort(c.durationSec, track.durationSec)) return false;
  return c.variantTag == null || c.variantTag === targetTag;
}
function isVariant(c, track, targetTag) {
  if (c.partial) return false;
  if (!sameTitle(c.title, track.title)) return false;
  if (!artistHit(track.artist, c.artist)) return false;
  if (tooShort(c.durationSec, track.durationSec)) return false;
  return c.variantTag != null && c.variantTag !== targetTag;
}
function isPartial(c, track) {
  if (!sameTitle(c.title, track.title)) return false;
  if (!artistHit(track.artist, c.artist)) return false;
  return c.partial || tooShort(c.durationSec, track.durationSec);
}
async function analyzeOne(track) {
  const resolved = [];
  for (const source of [['netease', neteaseSearch, neteaseResolve], ['kuwo', kuwoSearch, kuwoResolve]]) {
    const [, search, resolve] = source;
    const raw = await search(track.title, track.artist, track.durationSec);
    const matched = raw.filter(c => sameTitle(c.title, track.title) && artistHit(track.artist, c.artist)).slice(0, MAX_RESOLVE_PER_SOURCE);
    const targetTag = variantTag(track.title, track.artist);
    for (const c of matched) {
      const r = await resolve(c);
      if (!r) continue;
      resolved.push(r);
      if (isStrict(r, track, targetTag)) break;
    }
  }
  const targetTag = variantTag(track.title, track.artist);
  const ready = resolved.find(c => isStrict(c, track, targetTag));
  if (ready) return { status: 'READY', c: ready };
  const variant = resolved.find(c => isVariant(c, track, targetTag));
  if (variant) return { status: 'VARIANT', c: variant };
  const partial = resolved.find(c => isPartial(c, track));
  if (partial) return { status: 'PARTIAL', c: partial };
  return { status: 'NO_SOURCE', c: null };
}

// ---------------- QQ 歌单 ----------------
async function qqList(disstid) {
  const REF = 'https://i2.y.qq.com/n3/other/pages/details/playlist.html';
  const UA2 = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148';
  const all = []; let begin = 0, total = 0, guard = 0;
  for (;;) {
    const body = { comm: { cv: '10020000' }, req_0: { module: 'music.srfDissInfo.aiDissInfo', method: 'uniform_get_Dissinfo',
      param: { disstid: Number(disstid), enc_host_uin: '', tag: 1, userinfo: 1, song_begin: begin, song_num: 100, orderlist: 1 } } };
    const r = await fetch('https://u.y.qq.com/cgi-bin/musicu.fcg?data=' + encodeURIComponent(JSON.stringify(body)),
      { headers: { 'User-Agent': UA2, Referer: REF, Origin: 'https://y.qq.com' } });
    const b = JSON.parse(await r.text()).req_0 || {};
    if (b.code !== 0) break;
    const d = b.data || {};
    if (!total) { total = d.total_song_num || 0; console.log('QQ 歌单《' + ((d.dirinfo || {}).title || '') + '》' + total + ' 首'); }
    const list = d.songlist || [];
    for (const s of list) all.push({ title: (s.title || '').trim() || (s.title_hilight || '').trim(),
      artist: (s.singer || []).map(x => x.name).join(' / '), durationSec: s.interval || 0 });
    begin += list.length;
    if (!list.length || !d.hasmore || begin >= total || ++guard > 30) break;
  }
  return all;
}

// ---------------- 网易云歌单（platformId 直连路径）----------------
async function neteaseList(id) {
  const txt = await get(`${R163}api/v6/playlist/detail?id=${id}&n=1000&s=0`, R163, UA_N);
  const obj = JSON.parse(txt).playlist;
  const ids = (obj.trackIds || []).map(t => t.id);
  const out = [];
  for (let i = 0; i < ids.length; i += 50) {
    const batch = ids.slice(i, i + 50);
    const d = await get(`${R163}api/song/detail/?ids=${encodeURIComponent(JSON.stringify(batch))}`, R163, UA_N);
    if (!d) continue;
    for (const s of JSON.parse(d).songs || []) {
      out.push({ title: s.name, artist: (s.artists || []).map(a => a.name).join(' / '),
        album: (s.album && s.album.name) || '', durationSec: Math.round((s.duration || 0) / 1000),
        platformId: String(s.id) });
    }
  }
  return { name: obj.name, tracks: out };
}

// ---------------- 主流程 ----------------
const mode = process.argv[3] || 'qq';
const limit = parseInt(process.argv[4] || '0', 10);
let tracks, plName = '';
if (mode === 'netease') {
  const pl = await neteaseList(process.argv[2] || '3778678');
  plName = pl.name;
  tracks = pl.tracks;
  console.log('网易云歌单《' + plName + '》' + tracks.length + ' 首（走 platformId 直连）');
} else {
  tracks = await qqList(process.argv[2] || '8919002463');
  console.log('QQ 歌单《' + plName + '》' + tracks.length + ' 首（走搜索）');
}
if (limit > 0) tracks = tracks.slice(0, limit);
console.log('取前 ' + tracks.length + ' 首，按 Kotlin 逻辑分类\n');
const stat = { READY: 0, VARIANT: 0, PARTIAL: 0, NO_SOURCE: 0 };
for (let i = 0; i < tracks.length; i++) {
  const t = tracks[i];
  const r = await analyzeOne(t);
  stat[r.status]++;
  const d = r.c ? `${r.c.title} / ${r.c.artist} ${r.c.durationSec}s${r.c.variantTag ? ' [' + r.c.variantTag + ']' : ''}${r.c.partial ? ' 片段' : ''}` : '-';
  console.log(`${String(i + 1).padStart(2)}. ${t.title} - ${t.artist} [${t.durationSec}s] -> ${r.status}  ${d}`);
  await new Promise(x => setTimeout(x, 120));
}
console.log('\n===== Kotlin 逻辑下的分类结果 =====');
console.log(`READY     ${stat.READY}`);
console.log(`VARIANT   ${stat.VARIANT}`);
console.log(`PARTIAL   ${stat.PARTIAL}`);
console.log(`NO_SOURCE ${stat.NO_SOURCE}`);
console.log(`严格可下率 ${(stat.READY / tracks.length * 100).toFixed(0)}%`);
console.log(`含衍生版可下率 ${((stat.READY + stat.VARIANT) / tracks.length * 100).toFixed(0)}%`);
