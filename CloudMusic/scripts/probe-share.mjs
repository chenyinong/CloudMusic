// 实测各类「分享链接」的真实形态，看现有的 PlaylistParser 能认几种
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
const UA_M = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148';

async function head(url, ua) {
  try {
    const r = await fetch(url, { headers: { 'User-Agent': ua || UA }, redirect: 'manual' });
    return { status: r.status, loc: r.headers.get('location') };
  } catch (e) { return { status: 0, err: String(e) }; }
}
async function body(url, ua) {
  try {
    const r = await fetch(url, { headers: { 'User-Agent': ua || UA }, redirect: 'follow' });
    return { status: r.status, url: r.url, text: await r.text() };
  } catch (e) { return { status: 0, text: String(e) }; }
}

console.log('=== 1. QQ taoge 分享页：id 是不是 disstid ===');
const t = await body('https://i.y.qq.com/n2/m/share/details/taoge.html?id=848213661', UA_M);
console.log('status=' + t.status + ' len=' + t.text.length);
const d1 = t.text.match(/disstid["'\s:=]+(\d{6,})/i);
const d2 = t.text.match(/taoge["'\s:=]+(\d{6,})/i);
const d3 = t.text.match(/playlist\/(\d{6,})/);
console.log('  disstid: ' + (d1 ? d1[1] : '无') + '  taoge: ' + (d2 ? d2[1] : '无') + '  playlist/: ' + (d3 ? d3[1] : '无'));
const nm = t.text.match(/<title>([^<]{2,40})<\/title>/);
console.log('  title: ' + (nm ? nm[1] : '无'));

console.log('\n=== 2. QQ 转发短链 c.y.qq.com/base/fcgi-bin/u ===');
const inner = encodeURIComponent('https://y.qq.com/n/ryqq/playlist/8919002463');
for (const u of [
  'https://c.y.qq.com/base/fcgi-bin/u?format=json&itemurl=' + inner + '&itemurl_title=' + encodeURIComponent('燃动国摇'),
  'https://c.y.qq.com/base/fcgi-bin/u?__=1&g_tk=5381&format=json&itemurl=' + inner
]) {
  const b = await body(u);
  console.log('status=' + b.status + ' len=' + b.text.length);
  console.log('  -> ' + b.text.slice(0, 260).replace(/\s+/g, ' '));
}

console.log('\n=== 3. 网易云各类链接形态 ===');
for (const u of [
  'https://music.163.com/playlist?id=3778678',
  'https://music.163.com/#/playlist?id=3778678',
  'https://music.163.com/playlist/3778678/',
  'https://y.music.163.com/m/playlist?id=3778678&userid=123456789',
  'https://music.163.com/m/playlist?id=3778678&userid=123456789'
]) {
  const b = await body(u);
  const n2 = b.text.match(/<title>([^<]{2,40})<\/title>/);
  console.log(b.status + ' len=' + b.text.length + ' title=' + (n2 ? n2[1].trim() : '无') + '  <- ' + u);
}

console.log('\n=== 4. 短链能否跟随重定向 ===');
for (const u of ['https://c.y.qq.com/base/fcgi-bin/u?itemurl=' + inner, 'https://t.cn/aBcDeF']) {
  const h = await head(u);
  console.log(h.status + ' loc=' + (h.loc || '无') + '  <- ' + u.slice(0, 70));
}

// 解析器的覆盖度验证在 probe-share-parse.mjs（那里复刻的是 Kotlin 的实现）

console.log('\n=== 5. 现有正则能认几种（离线判定）===');
const NETEASE_ID = /(?:playlist\?id=|playlist\/)(\d+)/;
const QQ_ID = /(?:playlist\/|taoge\.html\?id=|disstid=)(\d+)/;
function parse(input) {
  const s = input.trim();
  const lower = s.toLowerCase();
  if (lower.includes('y.qq.com') || lower.includes('qq.com')) {
    const m = QQ_ID.exec(s);
    return m ? 'QQ:' + m[1] : 'x 认不出';
  }
  if (lower.includes('163.com')) {
    const m = NETEASE_ID.exec(s);
    return m ? '网易云:' + m[1] : 'x 认不出';
  }
  if (/^\d+$/.test(s)) return '网易云:' + s;
  return 'x 认不出';
}
const samples = [
  'https://y.qq.com/n/ryqq/playlist/8919002463',
  'https://i.y.qq.com/n2/m/share/details/taoge.html?id=848213661',
  'https://c.y.qq.com/base/fcgi-bin/u?format=json&itemurl=' + inner,
  'https://c.y.qq.com/base/fcgi-bin/u?__=1&g_tk=5381&format=json&itemurl=' + inner,
  'https://music.163.com/playlist?id=3778678',
  'https://music.163.com/#/playlist?id=3778678',
  'https://music.163.com/playlist/3778678/',
  'https://y.music.163.com/m/playlist?id=3778678&userid=123',
  'https://t.cn/aBcDeF',
  '8919002463'
];
for (const s of samples) console.log('  ' + parse(s).padEnd(16) + ' <- ' + s.slice(0, 78));
