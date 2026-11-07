const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
const kw = encodeURIComponent('稻香 周杰伦');
const cands = [
  'https://so.liumingye.cn/search?keyword=' + kw,
  'https://so.liumingye.cn/search?q=' + kw,
  'https://so.liumingye.cn/search?key=' + kw,
  'https://so.liumingye.cn/search/' + kw,
  'https://so.liumingye.cn/?s=' + kw
];
for (const u of cands) {
  try {
    const r = await fetch(u, { headers: { 'User-Agent': UA, Referer: 'https://so.liumingye.cn/' }, redirect: 'follow' });
    const t = await r.text();
    const hasSong = /稻香/.test(t);
    const hasCaptcha = /captcha|验证码|人机/.test(t);
    const pan = (t.match(/pan\.baidu\.com\/officialrsc\/s\/[A-Za-z0-9]+/g) || []).length;
    console.log(r.status + ' len=' + t.length + ' 稻香=' + hasSong + ' 验证码=' + hasCaptcha + ' 网盘链接=' + pan + '  <- ' + u);
  } catch (e) {
    console.log('ERR ' + u + ' ' + e.message);
  }
}
