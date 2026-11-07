// 实测「导入歌曲」链路上各环节的耗时，定位到底慢在哪
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
const UA_M = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148';

function ms(t) { return (Date.now() - t) + 'ms'; }

async function timed(label, fn) {
  const t = Date.now();
  try {
    const r = await fn();
    console.log(`  ${label.padEnd(28)} ${ms(t).padStart(9)}  ${r ?? ''}`);
    return r;
  } catch (e) {
    console.log(`  ${label.padEnd(28)} ${ms(t).padStart(9)}  ERR ${String(e).slice(0, 60)}`);
    return null;
  }
}

console.log('=== 1. 网易云搜索 ===');
const kw = encodeURIComponent('稻香 周杰伦');
const searchBody = await timed('搜索接口', async () => {
  const r = await fetch(`https://music.163.com/api/search/get/web?s=${kw}&type=1&limit=10&offset=0`,
    { headers: { 'User-Agent': UA, 'Referer': 'https://music.163.com/' } });
  const j = await r.json();
  const songs = j?.result?.songs || [];
  console.log(`     -> ${songs.length} 首`);
  return songs[0];
});

if (searchBody) {
  const id = searchBody.id;
  console.log(`\n=== 2. 网易云外链（${id} ${searchBody.name}）===`);
  await timed('outer/url 拿重定向', async () => {
    const r = await fetch(`https://music.163.com/song/media/outer/url?id=${id}.mp3`,
      { headers: { 'User-Agent': UA, 'Referer': 'https://music.163.com/' }, redirect: 'manual' });
    const loc = r.headers.get('location');
    console.log(`     -> ${r.status} ${String(loc).slice(0, 110)}`);
    return loc;
  });

  console.log('\n=== 3. 网易云音频下载速度 ===');
  const r3 = await fetch(`https://music.163.com/song/media/outer/url?id=${id}.mp3`,
    { headers: { 'User-Agent': UA, 'Referer': 'https://music.163.com/' }, redirect: 'follow' });
  console.log(`     最终地址 ${r3.url.slice(0, 110)}`);
  console.log(`     Content-Length=${r3.headers.get('content-length')} type=${r3.headers.get('content-type')}`);
  const t3 = Date.now();
  let bytes = 0;
  const reader = r3.body.getReader();
  const LIMIT = 8 * 1024 * 1024;
  while (bytes < LIMIT) {
    const { done, value } = await reader.read();
    if (done) break;
    bytes += value.length;
  }
  try { await reader.cancel(); } catch (e) { /* ignore */ }
  const dt = (Date.now() - t3) / 1000;
  console.log(`     下了 ${(bytes / 1048576).toFixed(2)}MB / ${dt.toFixed(2)}s = ${(bytes / 1048576 / dt).toFixed(2)} MB/s`);
}

console.log('\n=== 4. 下歌吧搜索（网盘源）===');
const lh = await timed('liumingye 搜索', async () => {
  const r = await fetch('https://so.liumingye.cn/api/music/search?q=' + encodeURIComponent('稻香 周杰伦'),
    { headers: { 'User-Agent': UA } });
  const j = await r.json();
  const d = j.data || [];
  console.log(`     -> ${d.length} 条`);
  return d[0];
});

if (lh) {
  console.log(`\n=== 5. 下歌吧详情（分享链接在这）===`);
  await timed('detail 接口', async () => {
    const r = await fetch('https://so.liumingye.cn/api/music/detail?id=' + lh.id,
      { headers: { 'User-Agent': UA } });
    const j = await r.json();
    const d = j.data || j.result || {};
    const dl = d.downloads || [];
    console.log(`     -> 下载源 ${dl.length} 个: ${dl.slice(0, 3).map(x => x.url.slice(0, 60)).join(' | ')}`);
    return d;
  });
}

console.log('\n=== 6. 夸克分享页（不登录能看多远）===');
await timed('pan.quark.cn 首页', async () => {
  const r = await fetch('https://pan.quark.cn/', { headers: { 'User-Agent': UA } });
  console.log(`     -> ${r.status}`);
});
