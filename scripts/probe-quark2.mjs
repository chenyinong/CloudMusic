// 第二轮：分享页只有 9.6KB，没有 pwd_id。看看到底返回了什么，并直接用 shareId 当 pwd_id 试
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36'
const shareUrl = process.argv[2] || 'https://pan.quark.cn/s/cbe2f1cc45a9'
const shareId = shareUrl.split('/s/')[1].split(/[?#]/)[0]
const out = []
const log = (...a) => out.push(a.join(' '))

async function req(url, opts = {}) {
  const t0 = Date.now()
  try {
    const r = await fetch(url, {
      headers: {
        'User-Agent': UA,
        'Accept': 'application/json, text/plain, */*',
        'Referer': 'https://pan.quark.cn/',
        ...(opts.headers || {}),
      },
      method: opts.method || 'GET',
      body: opts.body,
      redirect: 'follow',
    })
    const text = await r.text()
    let json = null
    try { json = JSON.parse(text) } catch (e) {}
    return { url, status: r.status, ms: Date.now() - t0, text, json, ct: r.headers.get('content-type') }
  } catch (e) {
    return { url, status: 0, text: '', err: String(e) }
  }
}

// 1. 分享页原文
const page = await req(shareUrl, { headers: { Accept: 'text/html,application/xhtml+xml,*/*' } })
log('[分享页] ' + page.status + ' · content-type=' + page.ct + ' · ' + page.text.length + ' 字节')
log(page.text.slice(0, 2200).replace(/\s+/g, ' '))
log('')
log('--- 关键字段扫描 ---')
for (const k of ['pwd_id', 'stoken', '__INITIAL_STATE__', 'passcode', '提取码', '验证', '登录', '扫码', 'window.']) {
  const i = page.text.indexOf(k)
  log('  ' + k + ': ' + (i >= 0 ? '命中 @' + i : '无'))
}

// 2. 直接把 shareId 当 pwd_id
log('')
log('[直接用 shareId 当 pwd_id 取 stoken]')
for (const passcode of ['', '8888', '6666', '1234']) {
  const r = await req(
    'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Referer: shareUrl },
      body: JSON.stringify({ pwd_id: shareId, passcode: passcode }),
    }
  )
  log('  passcode="' + passcode + '" -> ' + r.status + ' · ' + (r.text || '').slice(0, 220).replace(/\s+/g, ' '))
  if (r.status === 200 && r.json && r.json.data && r.json.data.stoken) {
    log('  >> 拿到 stoken')
    const stoken = r.json.data.stoken
    const d = await req(
      'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc&pwd_id=' +
        shareId + '&stoken=' + stoken + '&_fetch_share=1&_page=1&_size=50',
      { headers: { Referer: shareUrl } }
    )
    log('  [列文件] ' + d.status + ' · ' + (d.text || '').slice(0, 1200).replace(/\s+/g, ' '))
  }
}

// 3. liumingye 自己的 geturl：POST，body 各种形态
log('')
log('[liumingye geturl POST 各种 body]')
for (const body of [
  { url: shareUrl },
  { url: shareUrl, passcode: '' },
  { id: shareUrl },
  { link: shareUrl },
]) {
  const r = await req('https://so.liumingye.cn/api/source/geturl', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Referer: 'https://so.liumingye.cn/' },
    body: JSON.stringify(body),
  })
  log('  ' + JSON.stringify(body).slice(0, 60) + ' -> ' + r.status + ' · ' + (r.text || '').slice(0, 240).replace(/\s+/g, ' '))
}

console.log(out.join('\n'))
