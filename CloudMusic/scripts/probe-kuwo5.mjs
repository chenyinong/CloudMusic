const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';

async function raw(url, headers) {
  try {
    const r = await fetch(url, { headers: { 'User-Agent': UA, ...(headers || {}) }, redirect: 'follow' });
    const sc = r.headers.getSetCookie ? r.headers.getSetCookie() : [];
    return { status: r.status, text: await r.text(), sc, url: r.url };
  } catch (e) { return { status: 0, text: String(e), sc: [] }; }
}

// 1) 老搜索接口取一首歌
const s = await raw('https://search.kuwo.cn/r.s?all=%E7%A8%BB%E9%A6%99%20%E5%91%A8%E6%9D%B0%E4%BC%A6&ft=music&itemset=web_2013&client=kt&pn=0&rn=5&rformat=json&encoding=utf8', { Referer: 'https://www.kuwo.cn/' });
console.log('[搜索] status=' + s.status + ' len=' + s.text.length);
// 该老接口返回的是 python dict 风格，先取 rid
const m = s.text.match(/'DC_TARGETID':'(\d+)'/);
const rid = m ? m[1] : '';
console.log('rid=' + rid);
const nameM = s.text.match(/'SONGNAME':'([^']*)'/);
console.log('第一首=' + (nameM ? nameM[1] : '?'));

if (!rid) { console.log('没拿到 rid，结束'); process.exit(0); }

// 2) 老播放地址接口（多个变体）
const variants = [
  'http://antiserver.kuwo.cn/anti.s?type=convert&format=mp3&rid=' + rid + '&br=128',
  'http://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3&rid=' + rid + '&br=128&response=url',
  'https://antiserver.kuwo.cn/anti.s?type=convert&format=mp3&rid=' + rid + '&br=128',
  'http://antiserver.kuwo.cn/anti.s?type=convert&format=mp3&rid=' + rid + '&br=128&aa=1024'
];
let mp3 = '';
for (const u of variants) {
  const r = await raw(u, { Referer: 'https://www.kuwo.cn/' });
  const t = r.text.trim();
  console.log('\n' + r.status + ' | ' + u.slice(0, 78) + '\n   -> ' + t.slice(0, 200));
  const hm = t.match(/https?:[^"'\s\\]+\.(mp3|aac|flac)[^"'\s\\]*/i);
  if (hm) { mp3 = hm[0]; break; }
}

// 3) 新接口路径：/api/www/play/playlist?rid=
if (!mp3) {
  console.log('\n--- 新接口 /api/www/play/playlist?rid= ---');
  for (const u of [
    'https://www.kuwo.cn/api/www/play/playlist?rid=' + rid + '&pn=0&rn=30&httpsStatus=1&reqId=1',
    'http://www.kuwo.cn/api/www/play/playlist?rid=' + rid + '&pn=0&rn=30&httpsStatus=1'
  ]) {
    const r = await raw(u, { Referer: 'https://www.kuwo.cn/' });
    console.log(r.status + ' | ' + u.slice(0, 80) + '\n   -> ' + r.text.slice(0, 220));
    const hm = r.text.match(/https?:[^"'\s\\]+\.(mp3|aac|flac)[^"'\s\\]*/i);
    if (hm) { mp3 = hm[0]; break; }
  }
}

// 4) 老 song.kuwo.cn 播放页
if (!mp3) {
  console.log('\n--- 老 kuwo 播放页 ---');
  for (const u of [
    'http://player.kuwo.cn/webmusic/st/getNewMuiseByRid?rid=' + rid + '&mid=1',
    'http://www.kuwo.cn/st/songInfoAndStem?songId=' + rid
  ]) {
    const r = await raw(u, { Referer: 'http://www.kuwo.cn/' });
    console.log(r.status + ' | ' + u.slice(0, 70) + '\n   -> ' + r.text.slice(0, 220));
    const hm = r.text.match(/https?:[^"'\s\\]+\.(mp3|aac|flac)[^"'\s\\]*/i);
    if (hm) { mp3 = hm[0]; break; }
  }
}

// 5) 实测下载
if (mp3) {
  console.log('\n=== 实际下载 ===\n' + mp3);
  const a = await raw(mp3, { Referer: 'https://www.kuwo.cn/' });
  const buf = Buffer.from(a.text, 'binary');
  const hex = buf.slice(0, 4).toString('hex');
  console.log('status=' + a.status + ' 字节=' + buf.length + ' 魔数=' + hex +
    ' ≈' + (buf.length / 16000).toFixed(0) + 's ' +
    (hex.startsWith('494433') ? '→ ID3/MP3 音频 ✓' : hex.slice(0, 2) === 'ff' ? '→ MP3 ✓' : '→ 非音频'));
  console.log('末尾字节: ' + buf.slice(-16).toString('hex'));
} else {
  console.log('\n>>> 全部变体都没拿到直链');
}
