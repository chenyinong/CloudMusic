// 探测 so.liumingye.cn（���歌吧）有没有能直接拿音频的接口
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36';

async function get(url) {
    try {
        const r = await fetch(url, { headers: { 'User-Agent': UA, Accept: '*/*' }, redirect: 'follow' });
        return { status: r.status, text: await r.text(), type: r.headers.get('content-type') };
    } catch (e) {
        return { status: 0, text: String(e), type: '' };
    }
}

const home = await get('https://so.liumingye.cn/');
console.log('[首页] status=' + home.status + ' type=' + home.type + ' len=' + home.text.length);
const srcs = [...home.text.matchAll(/src="([^"]+\.js[^"]*)"/g)].map(m => m[1]);
console.log('[js 数量] ' + srcs.length);
for (const s of srcs.slice(0, 8)) console.log('   ' + s.slice(0, 150));

// 站点地图（能看出有哪些页面/接口形态）
for (const p of ['/sitemap.xml', '/robots.txt']) {
    const r = await get('https://so.liumingye.cn' + p);
    console.log('\n[' + p + '] ' + r.status + ' len=' + r.text.length);
    console.log(r.text.slice(0, 600));
}
