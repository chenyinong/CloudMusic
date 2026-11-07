const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
async function raw(url, headers) {
  try {
    const r = await fetch(url, { headers: { 'User-Agent': UA, Referer: 'https://www.kuwo.cn/', ...(headers || {}) }, redirect: 'follow' });
    return { status: r.status, text: await r.text() };
  } catch (e) { return { status: 0, text: String(e) }; }
}
const RID = '351583919';
console.log('=== antiserver 协议对比 ===');
for (const u of [
  'http://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3&rid=' + RID + '&br=128&response=url',
  'https://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3&rid=' + RID + '&br=128&response=url',
  'https://antiserver.kuwo.cn/anti.s?type=convert&format=mp3&rid=' + RID + '&br=128',
  'https://search.kuwo.cn/r.s?all=%E7%A8%BB%E9%A6%99&ft=music&itemset=web_2013&client=kt&pn=0&rn=2&rformat=json&encoding=utf8'
]) {
  const r = await raw(u);
  console.log(r.status + ' len=' + r.text.length + '  ' + u.slice(0, 72) + '\n   -> ' + r.text.slice(0, 150).replace(/\s+/g, ' '));
}

console.log('\n=== 网易云 302 目标判定（/404 会被 cleartext 挡吗）===');
const man = await fetch('https://music.163.com/song/media/outer/url?id=363394.mp3',
  { headers: { 'User-Agent': UA, Referer: 'https://music.163.com/' }, redirect: 'manual' });
const loc = man.headers.get('location');
console.log('location=' + loc);
if (loc) {
  const h = await fetch(loc.replace('http://', 'https://'), { method: 'HEAD', headers: { 'User-Agent': UA, Referer: 'https://music.163.com/' } });
  console.log('https 访问: status=' + h.status + ' len=' + h.headers.get('content-length') + ' type=' + h.headers.get('content-type'));
  const h2 = await fetch(loc, { method: 'HEAD', headers: { 'User-Agent': UA, Referer: 'https://music.163.com/' } });
  console.log('http  访问: status=' + h2.status + ' len=' + h2.headers.get('content-length') + ' type=' + h2.headers.get('content-type'));
}
