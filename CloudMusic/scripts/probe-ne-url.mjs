const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
const R = 'https://music.163.com/';

async function j(url) {
  const r = await fetch(url, { headers: { 'User-Agent': UA, Referer: R } });
  return r.json();
}
// 拿几个歌 id
const s = await j('https://music.163.com/api/search/get?s=' + encodeURIComponent('相信自己 零点乐队') + '&type=1&limit=3&offset=0');
const ids = (s.result.songs || []).map(x => ({ id: x.id, n: x.name, a: (x.artists || []).map(y => y.name).join('/'), d: Math.round(x.duration / 1000) }));
console.log(JSON.stringify(ids));

for (const it of ids) {
  console.log('\n=== ' + it.n + ' / ' + it.a + ' (' + it.d + 's) id=' + it.id + ' ===');
  const man = await fetch('https://music.163.com/song/media/outer/url?id=' + it.id + '.mp3', { headers: { 'User-Agent': UA, Referer: R }, redirect: 'manual' });
  console.log('  outer/url status=' + man.status + ' location=' + (man.headers.get('location') || '(无)').slice(0, 80));
  const loc = (man.headers.get('location') || '').replace('http://', 'https://');
  if (!loc) continue;
  // A: HEAD
  const h = await fetch(loc, { method: 'HEAD', headers: { 'User-Agent': UA, Referer: R } });
  console.log('  HEAD  status=' + h.status + ' content-length=' + h.headers.get('content-length') + ' content-range=' + h.headers.get('content-range') + ' type=' + h.headers.get('content-type'));
  // B: Range GET
  const g = await fetch(loc, { headers: { 'User-Agent': UA, Referer: R, Range: 'bytes=0-1023' } });
  const b = Buffer.from(await g.arrayBuffer());
  console.log('  GET   status=' + g.status + ' content-length=' + g.headers.get('content-length') + ' content-range=' + g.headers.get('content-range') + ' 实收=' + b.length + ' 魔数=' + b.slice(0, 3).toString('hex'));
}
