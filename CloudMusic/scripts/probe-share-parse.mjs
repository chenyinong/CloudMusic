// 模拟 Kotlin 的 PlaylistParser，验证各种分享链接能不能认出来
const NETEASE_ID = /(?:playlist\?id=|playlist\/)(\d+)/;
const QQ_ID = /(?:playlist\/|taoge\.html\?id=|disstid=|[?&]id=)(\d+)/;
const ITEM_URL = /itemurl=([^&\s]+)/i;

function decodeRepeatedly(s) {
  let out = s;
  for (let i = 0; i < 3; i++) {
    let next;
    try { next = decodeURIComponent(out); } catch (e) { return out; }
    if (next === out) return out;
    out = next;
  }
  return out;
}
function parse(input) {
  let s = (input || '').trim();
  if (!s) return null;
  const nested = (ITEM_URL.exec(s) || [])[1];
  if (nested) {
    const decoded = decodeRepeatedly(nested);
    const r = parse(decoded);
    if (r) return r;
    s = decoded;
  }
  const lower = s.toLowerCase();
  if (lower.includes('qq.com')) {
    const m = QQ_ID.exec(s);
    return m ? { platform: 'QQ', id: m[1] } : null;
  }
  if (lower.includes('163.com')) {
    const m = NETEASE_ID.exec(s);
    return m ? { platform: '网易云', id: m[1] } : null;
  }
  if (/^\d+$/.test(s)) return { platform: '网易云', id: s };
  return null;
}

const enc = encodeURIComponent('https://y.qq.com/n/ryqq/playlist/8919002463');
const samples = [
  ['QQ 网页版歌单', 'https://y.qq.com/n/ryqq/playlist/8919002463'],
  ['QQ 带追踪参数', 'https://y.qq.com/n/ryqq/playlist/8919002463?ADTAG=myqq&from=myqq&channel=10000000'],
  ['QQ 分享页 taoge', 'https://i.y.qq.com/n2/m/share/details/taoge.html?id=848213661'],
  ['QQ 老 H5 播放页', 'https://i.y.qq.com/n8/y/play/playlist.html?ADTAG=myqq&channel=10000000&id=8919002463'],
  ['QQ 转发链接(带itemurl)', 'https://c.y.qq.com/base/fcgi-bin/u?__=1&g_tk=5381&format=json&itemurl=' + enc + '&itemurl_title=%E7%87%83%E5%8A%A8'],
  ['QQ 转发链接(中文前缀)', '【歌单】燃动国摇｜全力以赴心中的梦 https://c.y.qq.com/base/fcgi-bin/u?itemurl=' + enc],
  ['QQ 转发链接(itemurl二次编码)', 'https://c.y.qq.com/base/fcgi-bin/u?itemurl=' + encodeURIComponent(enc)],
  ['网易云 标准', 'https://music.163.com/playlist?id=3778678'],
  ['网易云 hash路由', 'https://music.163.com/#/playlist?id=3778678'],
  ['网易云 路径式', 'https://music.163.com/playlist/3778678/'],
  ['网易云 移动端', 'https://y.music.163.com/m/playlist?id=3778678&userid=123456789'],
  ['网易云 带userid', 'https://music.163.com/playlist?id=3778678&userid=123&userid2=456'],
  ['纯数字(网易云)', '3778678'],
  ['纯数字(QQ)', '8919002463'],
  ['不认识的东西', '今天天气不错'],
  ['短链(需跟重定向)', 'https://t.cn/aBcDeF']
];
let ok = 0;
for (const [name, s] of samples) {
  const r = parse(s);
  if (r) ok++;
  console.log(`  ${(r ? r.platform + ':' + r.id : 'x 认不出').padEnd(16)} ${name.padEnd(24)} <- ${s.slice(0, 60)}`);
}
console.log(`\n离线能认 ${ok}/${samples.length - 1}（短链那条例外，要靠网络跟重定向）`);

console.log('\n=== 短链跟重定向 ===');
async function followRedirect(url, hops = 6) {
  let current = url;
  for (let i = 0; i < hops; i++) {
    const loc = await fetch(current, { headers: { 'User-Agent': 'Mozilla/5.0' }, redirect: 'manual' })
      .then(r => r.headers.get('location')).catch(() => null);
    if (!loc) return current;
    current = loc.startsWith('http://') ? 'https://' + loc.slice(7) : loc;
  }
  return current;
}
for (const u of ['https://y.music.163.com/m/playlist?id=3778678&userid=1', 'https://t.cn/aBcDeF']) {
  const f = await followRedirect(u);
  const r = parse(f);
  console.log(`  ${u.slice(0, 50).padEnd(52)} -> ${f.slice(0, 44)}  ${r ? r.platform + ':' + r.id : 'x 认不出'}`);
}
