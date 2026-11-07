// 夸克登录方式 + 转存(save) 参数实测
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36'
const out = []
const log = (...a) => out.push(a.join(' '))

async function req(url, opts = {}) {
  try {
    const r = await fetch(url, {
      headers: {
        'User-Agent': UA,
        'Accept': 'application/json, text/plain, */*',
        'Referer': opts.referer || 'https://drive-pc.quark.cn/',
        ...(opts.headers || {}),
      },
      method: opts.method || 'GET',
      body: opts.body,
      redirect: 'follow',
    })
    const text = await r.text()
    let json = null
    try { json = JSON.parse(text) } catch (e) {}
    return { url, status: r.status, text, json, headers: r.headers }
  } catch (e) {
    return { url, status: 0, text: '', err: String(e) }
  }
}

// ---------- 1. 分享页里引的 JS（扒 save/login 的真实参数） ----------
log('=== 1. 分享页引用的脚本 ===')
const page = await req('https://pan.quark.cn/s/cbe2f1cc45a9', {
  referer: 'https://pan.quark.cn/',
  headers: { Accept: 'text/html,*/*' },
})
const scripts = [...new Set((page.text.match(/src="([^"]+\.js)"/g) || []).map(s => s.slice(5, -1)))]
for (const s of scripts) log('  ' + s)

// 下载脚本并搜关键字
const KEYS = ['sharepage/save', 'sharepage/download', 'login', 'qrcode', 'passkey', 'getQrcode', 'captcha']
const found = []
for (const s of scripts.slice(0, 8)) {
  const abs = s.startsWith('//') ? 'https:' + s : s.startsWith('/') ? 'https://pan.quark.cn' + s : s
  const js = await req(abs)
  if (!js.text) continue
  for (const k of KEYS) {
    const i = js.text.indexOf(k)
    if (i >= 0) found.push({ file: s.split('/').pop(), key: k, ctx: js.text.slice(Math.max(0, i - 320), i + 320) })
  }
}
log('')
log('=== 2. 脚本里命中 ' + found.length + ' 处 ===')
const seen = new Set()
for (const f of found) {
  const sig = f.key + '|' + f.ctx.slice(0, 60)
  if (seen.has(sig)) continue
  seen.add(sig)
  log('')
  log('--- [' + f.key + '] ' + f.file + ' ---')
  log(f.ctx.replace(/\s+/g, ' '))
}

// ---------- 3. 试登录相关端点 ----------
log('')
log('=== 3. 登录相关端点探测 ===')
const cands = [
  ['GET', '/1/clouddrive/login'],
  ['POST', '/1/clouddrive/login'],
  ['GET', '/1/clouddrive/login/captcha'],
  ['GET', '/1/clouddrive/passport/captcha'],
  ['GET', '/1/clouddrive/login/qrcode'],
  ['GET', '/1/clouddrive/passport/qrcode'],
  ['GET', '/1/clouddrive/login/getQrcode'],
  ['GET', '/1/clouddrive/member/info'],
  ['GET', '/1/clouddrive/member'],
]
for (const [method, path] of cands) {
  const r = await req('https://drive-pc.quark.cn' + path, {
    method: method,
    headers: { 'Content-Type': 'application/json' },
    body: method === 'POST' ? JSON.stringify({}) : undefined,
  })
  log('  ' + method + ' ' + path + ' → ' + r.status + ' · ' + (r.text || '').slice(0, 200).replace(/\s+/g, ' '))
}

console.log(out.join('\n'))
