// 救援测试：对「网易云直链 404（版权受限）」的曲目，看看酷我能不能救回
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
const R163 = 'https://music.163.com/';
const RKW = 'https://www.kuwo.cn/';

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
function artistHit(q, c) {
  const cs = norm(c);
  return String(q).split(/\s*\/\s*/).filter(Boolean).some(a => { const n = norm(a); return n && cs.indexOf(n) >= 0; });
}
async function raw(url, headers, manual) {
  try {
    const r = await fetch(url, { headers: { 'User-Agent': UA, ...(headers || {}) }, redirect: manual ? 'manual' : 'follow' });
    return { status: r.status, text: await r.text(), location: r.headers.get('location'), headers: r.headers };
  } catch (e) { return { status: 0, text: String(e) }; }
}
async function neSearch(key, limit) {
  const r = await raw('https://music.163.com/api/search/get?s=' + encodeURIComponent(key) + '&type=1&limit=' + limit + '&offset=0', { Referer: R163 });
  try { const j = JSON.parse(r.text); return (j.result && j.result.songs ? j.result.songs : []).map(s => ({
    id: s.id, name: s.name, artist: (s.artists || []).map(a => a.name).join(' / '), dur: Math.round((s.duration || 0) / 1000) })); }
  catch (e) { return []; }
}
async function neCheck(id) {
  const r = await raw('https://music.163.com/song/media/outer/url?id=' + id + '.mp3', { Referer: R163 }, true);
  const loc = r.location || '';
  if (/404/.test(loc)) return { ok: false, why: '404' };
  if (!loc) return { ok: false, why: 'noLoc' };
  const h = await fetch(loc.replace('http://', 'https://'), { method: 'HEAD', headers: { 'User-Agent': UA, Referer: R163 } });
  const bytes = parseInt(h.headers.get('content-length') || '0', 10);
  return { ok: h.status === 200 && bytes > 50000, bytes, why: h.status + '/' + bytes };
}
function unesc(s) { return s ? s.replace(/\\u0026/g, '&').replace(/\\\\/g, '\\').replace(/&nbsp;/g, ' ').trim() : ''; }
async function kwSearch(key, rn, itemset) {
  const u = 'https://search.kuwo.cn/r.s?all=' + encodeURIComponent(key) + '&ft=music' + (itemset ? '&itemset=web_2013' : '') + '&client=kt&pn=0&rn=' + rn + '&rformat=json&encoding=utf8';
  const r = await raw(u, { Referer: RKW });
  const out = [];
  for (const c of r.text.split('},{')) {
    const rid = (c.match(/'DC_TARGETID':'(\d+)'/) || [])[1];
    if (!rid) continue;
    const g = (k) => { const m = c.match(new RegExp("'" + k + "':'([^']*)'")); return m ? unesc(m[1]) : ''; };
    out.push({ rid, name: g('SONGNAME'), artist: g('ARTIST'), dur: parseInt(g('DURATION') || '0', 10), online: g('ONLINE'), pay: g('PAY'), formats: g('FORMATS') });
  }
  return out;
}
async function kwCheck(rid, dur) {
  const r = await raw('http://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3&rid=' + rid + '&br=128&response=url', { Referer: RKW });
  const m = r.text.trim().match(/https?:\/\/[^\s'"]+\.(mp3|aac|flac)/i);
  if (!m) return { ok: false, why: 'noUrl' };
  const h = await fetch(m[0], { method: 'HEAD', headers: { 'User-Agent': UA, Referer: RKW } });
  const bytes = parseInt(h.headers.get('content-length') || '0', 10);
  return { ok: h.status === 200 && bytes > 50000, bytes, why: h.status + '/' + bytes };
}

// ---- QQ 歌单 ----
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
    for (const s of list) all.push({ title: (s.title || '') + (s.title_hilight || ''), artists: (s.singer || []).map(x => x.name).join(' / '), sec: s.interval || 0 });
    begin += list.length;
    if (!list.length || !d.hasmore || begin >= total || ++guard > 30) break;
  }
  return all;
}

const tracks = await qqList(process.argv[2] || '8919002463');
console.log('=== 第一步：网易云哪些曲目能下 ===\n');
const neState = [];
for (const t of tracks) {
  let cands = await neSearch(t.title + ' ' + t.artists, 20);
  if (!cands.length) cands = await neSearch(t.title, 20);
  let hit = cands.filter(c => norm(c.name) === norm(t.title) && artistHit(t.artists, c.artist) && Math.abs(c.dur - t.sec) <= 5)
    .sort((a, b) => Math.abs(a.dur - t.sec) - Math.abs(b.dur - t.sec))[0];
  let st = { hit: null, ok: false };
  if (hit) st = { hit, ok: (await neCheck(hit.id)).ok };
  neState.push(st);
}
const bad = tracks.filter((t, i) => !neState[i].ok);
console.log(`网易云可下 ${tracks.length - bad.length}/${tracks.length}，需救援 ${bad.length} 首\n`);

console.log('=== 第二步：对这 ' + bad.length + ' 首，酷我 + 网易云备用通道能否救回 ===\n');
let saved = 0;
for (let i = 0; i < tracks.length; i++) {
  if (neState[i].ok) continue;
  const t = tracks[i];
  // 酷我：多路搜索
  const pools = [];
  pools.push(await kwSearch(t.title + ' ' + t.artists, 30, true));
  pools.push(await kwSearch(t.title, 30, true));
  pools.push(await kwSearch(t.title + ' ' + t.artists, 30, false));
  const merged = [];
  const seen = new Set();
  for (const p of pools) for (const c of p) { if (!seen.has(c.rid)) { seen.add(c.rid); merged.push(c); } }
  const strict = merged.filter(c => norm(c.name) === norm(t.title) && artistHit(t.artists, c.artist) && Math.abs(c.dur - t.sec) <= 5);
  const loose = merged.filter(c => norm(c.name) === norm(t.title) && artistHit(t.artists, c.artist));
  let kwRes = null;
  for (const c of strict) { const r = await kwCheck(c.rid, c.dur); if (r.ok) { kwRes = { c, r, strict: true }; break; } }
  if (!kwRes) for (const c of loose) { const r = await kwCheck(c.rid, c.dur); if (r.ok) { kwRes = { c, r, strict: false }; break; } }

  // 网易云备用：换同名的其它 id（不同版本）
  let neAlt = '';
  const cands = await neSearch(t.title + ' ' + t.artists, 30);
  for (const c of cands) {
    if (norm(c.name) !== norm(t.title)) continue;
    if (!artistHit(t.artists, c.artist)) continue;
    const chk = await neCheck(c.id);
    if (chk.ok) { neAlt = c.name + ' / ' + c.artist + ' (id=' + c.id + ')'; break; }
  }
  const ok = !!(kwRes || neAlt);
  if (ok) saved++;
  console.log(`${ok ? '✓' : '✗'} ${t.title} - ${t.artists} [${t.sec}s]`);
  if (kwRes) console.log(`     酷我救回: ${kwRes.c.name} / ${kwRes.c.artist} ${kwRes.c.dur}s ${kwRes.strict ? '严格' : '宽松'} ${Math.round(kwRes.r.bytes / 1024)}KB`);
  if (neAlt) console.log(`     网易云换版本救回: ${neAlt}`);
  if (!ok) {
    console.log('     酷我候选前6:');
    for (const c of merged.slice(0, 6)) console.log('        ' + c.name + ' / ' + c.artist + ' ' + c.dur + 's online=' + c.online + ' pay=' + c.pay);
  }
  await new Promise(r => setTimeout(r, 150));
}
console.log(`\n===== 救援汇总 =====`);
console.log(`需救援 ${bad.length} 首，成功救回 ${saved} 首 (${(saved / bad.length * 100).toFixed(0)}%)`);
console.log(`综合可下率：${tracks.length - bad.length + saved}/${tracks.length} = ${((tracks.length - bad.length + saved) / tracks.length * 100).toFixed(0)}%`);
