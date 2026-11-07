// 下载源对比 v2：修复网易云 302 直链 + 增加「完整文件 vs 试听片段」判定
// 用法: node scripts/probe-source-compare.mjs [QQ disstid]
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
const REF_163 = 'https://music.163.com/';
const REF_KW = 'https://www.kuwo.cn/';

function norm(s) {
  if (!s) return '';
  let x = String(s);
  x = x.replace(/[（(【\[]([^）)】\]]*)[）)】\]]/g, '');
  x = x.replace(/[\u3000\s]/g, '');
  x = x.replace(/[！-～]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0xFEE0));
  x = x.replace(/[·・、,，.。_~!！?？:：;；'"“”‘’@#￥$%^&*+=|\\/<>【】]/g, '');
  x = x.replace(/(live|完整版|官方版|原版|高清|无损|flac|remaster(ed)?|explicit|伴奏|纯音乐|钢琴版|吉他版|弦乐版|动态歌词版|动态版|inst\.?|instrumental|demo|acoustic|现场版|翻唱版|cover)/gi, '');
  return x.toLowerCase();
}
function artistHit(qArtist, cArtist) {
  const cs = norm(cArtist);
  const list = String(qArtist).split(/\s*\/\s*/).filter(Boolean);
  if (!list.length) return true;
  return list.some(a => { const n = norm(a); return n && cs.indexOf(n) >= 0; });
}
function isBadVariant(name) {
  return /(伴奏|instrumental|inst\.|纯音乐|demo|remix|cover:|dj\s|mix\s|铃声|试音|片头)/i.test(String(name));
}

async function raw(url, headers, manual) {
  try {
    const r = await fetch(url, { headers: { 'User-Agent': UA, ...(headers || {}) }, redirect: manual ? 'manual' : 'follow' });
    const sc = r.headers.getSetCookie ? r.headers.getSetCookie() : [];
    return { status: r.status, text: await r.text(), sc, location: r.headers.get('location'), headers: r.headers };
  } catch (e) { return { status: 0, text: String(e), sc: [], location: null }; }
}

// ---------- 网易云 ----------
async function neSearch(key, limit) {
  const r = await raw('https://music.163.com/api/search/get?s=' + encodeURIComponent(key) + '&type=1&limit=' + limit + '&offset=0', { Referer: REF_163 });
  try {
    const j = JSON.parse(r.text);
    return (j.result && j.result.songs ? j.result.songs : []).map(s => ({
      id: s.id, name: s.name, artist: (s.artists || []).map(a => a.name).join(' / '),
      album: (s.album && s.album.name) || '', dur: Math.round((s.duration || 0) / 1000)
    }));
  } catch (e) { return []; }
}
// 返回 {ok, kb, full, reason}
async function neUrl(id, dur) {
  const r = await raw('https://music.163.com/song/media/outer/url?id=' + id + '.mp3', { Referer: REF_163 }, true);
  if (!r.location) return { ok: false, kb: 0, full: false, reason: 'noLocation(HTTP ' + r.status + ')' };
  const loc = r.location.replace('http://', 'https://');
  const h = await fetch(loc, { method: 'HEAD', headers: { 'User-Agent': UA, Referer: REF_163 } });
  const bytes = parseInt(h.headers.get('content-length') || '0', 10);
  const kb = Math.round(bytes / 1024);
  if (h.status !== 200 || bytes < 50000) return { ok: false, kb, full: false, reason: 'http' + h.status };
  const need = dur * 16000;          // 128kbps 完整文件期望字节
  const full = bytes >= need * 0.85;  // 85% 视为完整
  return { ok: true, kb, full, reason: full ? '完整' : '片段(' + Math.round(bytes / 16000) + 's/' + dur + 's)' };
}

// ---------- 酷我 ----------
function unesc(s) { return s ? s.replace(/\\u0026/g, '&').replace(/\\\\/g, '\\').replace(/&nbsp;/g, ' ').trim() : ''; }
async function kwSearch(key, rn) {
  const u = 'https://search.kuwo.cn/r.s?all=' + encodeURIComponent(key) + '&ft=music&itemset=web_2013&client=kt&pn=0&rn=' + rn + '&rformat=json&encoding=utf8';
  const r = await raw(u, { Referer: REF_KW });
  const out = [];
  for (const c of r.text.split('},{')) {
    const rid = (c.match(/'DC_TARGETID':'(\d+)'/) || [])[1];
    if (!rid) continue;
    const g = (k) => { const m = c.match(new RegExp("'" + k + "':'([^']*)'")); return m ? unesc(m[1]) : ''; };
    out.push({ rid, name: g('SONGNAME'), artist: g('ARTIST'), album: g('ALBUM'),
      dur: parseInt(g('DURATION') || '0', 10), online: g('ONLINE'), pay: g('PAY'), formats: g('FORMATS') });
  }
  return out;
}
async function kwUrl(rid, dur) {
  const r = await raw('http://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3&rid=' + rid + '&br=128&response=url', { Referer: REF_KW });
  const m = r.text.trim().match(/https?:\/\/[^\s'"]+\.(mp3|aac|flac)/i);
  if (!m) return { ok: false, kb: 0, full: false, reason: 'noUrl' };
  const h = await fetch(m[0], { method: 'HEAD', headers: { 'User-Agent': UA, Referer: REF_KW } });
  const bytes = parseInt(h.headers.get('content-length') || '0', 10);
  const kb = Math.round(bytes / 1024);
  if (h.status !== 200 || bytes < 50000) return { ok: false, kb, full: false, reason: 'http' + h.status };
  const need = dur * 16000;
  const full = bytes >= need * 0.85;
  return { ok: true, kb, full, reason: full ? '完整' : '片段(' + Math.round(bytes / 16000) + 's/' + dur + 's)' };
}

// ---------- QQ 歌单 ----------
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
    if (!total) { total = d.total_song_num || 0; console.log('QQ 歌单《' + ((d.dirinfo || {}).title || '') + '》 共 ' + total + ' 首'); }
    const list = d.songlist || [];
    for (const s of list) all.push({ title: (s.title || '') + (s.title_hilight || ''),
      artists: (s.singer || []).map(x => x.name).join(' / '), sec: s.interval || 0 });
    begin += list.length;
    if (!list.length || !d.hasmore || begin >= total || ++guard > 30) break;
  }
  return all;
}

// ---------- 严格匹配 ----------
function strictPick(cands, t, allowBadVariant) {
  const tn = norm(t.title);
  const scored = [];
  for (const c of cands) {
    if (norm(c.name) !== tn) continue;
    if (!artistHit(t.artists, c.artist)) continue;
    if (!allowBadVariant && isBadVariant(c.name)) continue;
    const diff = Math.abs(c.dur - t.sec);
    if (diff > 5) continue;
    scored.push({ c, diff });
  }
  scored.sort((a, b) => a.diff - b.diff);
  return scored[0] ? scored[0].c : null;
}
async function matchWithFallback(searchFn, t) {
  // 1) 歌名+歌手
  let c = await searchFn(t.title + ' ' + t.artists, 20);
  let hit = strictPick(c, t, false);
  if (hit) return { hit, stage: '歌名+歌手' };
  // 2) 仅歌名
  c = await searchFn(t.title, 20);
  hit = strictPick(c, t, false);
  if (hit) return { hit, stage: '仅歌名' };
  // 3) 放宽：允许衍生版本词
  c = await searchFn(t.title + ' ' + t.artists, 20);
  hit = strictPick(c, t, true);
  if (hit) return { hit, stage: '放宽版本词' };
  c = await searchFn(t.title, 20);
  hit = strictPick(c, t, true);
  if (hit) return { hit, stage: '仅歌名+放宽' };
  return { hit: null, stage: '无' };
}

// ---------- 主流程 ----------
const disstid = process.argv[2] || '8919002463';
const tracks = await qqList(disstid);
console.log('拿到 ' + tracks.length + ' 首\n');

const stat = {
  ne: { strict: 0, dl: 0, full: 0, none: 0, need: 0 },
  kw: { strict: 0, dl: 0, full: 0, none: 0, need: 0 }
};
for (let i = 0; i < tracks.length; i++) {
  const t = tracks[i];
  const nm = await matchWithFallback(neSearch, t);
  let neD = { ok: false, kb: 0, full: false, reason: '-' };
  if (nm.hit) neD = await neUrl(nm.hit.id, nm.hit.dur);
  if (nm.hit) { stat.ne.strict++; stat.ne.need++; if (neD.ok) stat.ne.dl++; if (neD.full) stat.ne.full++; } else stat.ne.none++;

  const km = await matchWithFallback(kwSearch, t);
  let kwD = { ok: false, kb: 0, full: false, reason: '-' };
  if (km.hit) kwD = await kwUrl(km.hit.rid, km.hit.dur);
  if (km.hit) { stat.kw.strict++; stat.kw.need++; if (kwD.ok) stat.kw.dl++; if (kwD.full) stat.kw.full++; } else stat.kw.none++;

  const mk = (m, d) => {
    if (!m.hit) return '无匹配';
    const bad = isBadVariant(m.hit.name) ? ' ⚠衍生版本' : '';
    return m.hit.name + ' / ' + m.hit.artist + ' ' + m.hit.dur + 's [' + m.stage + '] ' +
      (d.ok ? d.kb + 'KB ' + d.reason : '直链FAIL:' + d.reason) + bad;
  };
  console.log(`${String(i + 1).padStart(2)}. ${t.title} - ${t.artists} [${t.sec}s]`);
  console.log('    网易云 ' + mk(nm, neD));
  console.log('    酷我   ' + mk(km, kwD));
  await new Promise(r => setTimeout(r, 150));
}

console.log('\n===== 汇总（QQ 歌单 ' + tracks.length + ' 首）=====');
console.log('              匹配上   直链通   完整文件   无匹配');
console.log(`网易云        ${stat.ne.strict}      ${stat.ne.dl}      ${stat.ne.full}       ${stat.ne.none}`);
console.log(`酷我          ${stat.kw.strict}      ${stat.kw.dl}      ${stat.kw.full}       ${stat.kw.none}`);
console.log(`\n网易云：匹配率 ${(stat.ne.strict / tracks.length * 100).toFixed(0)}%  完整可下率 ${(stat.ne.full / tracks.length * 100).toFixed(0)}%`);
console.log(`酷我  ：匹配率 ${(stat.kw.strict / tracks.length * 100).toFixed(0)}%  完整可下率 ${(stat.kw.full / tracks.length * 100).toFixed(0)}%`);
