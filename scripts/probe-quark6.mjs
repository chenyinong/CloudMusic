// 第六轮：dump file/download 的完整 JSON，拿 download_url 并真的下一次音频验证
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36'
const shareUrl = process.argv[2] || 'https://pan.quark.cn/s/cbe2f1cc45a9'
const pwdId = shareUrl.split('/s/')[1].split(/[?#]/)[0]
const out = []
const log = (...a) => out.push(a.join(' '))

async function req(url, opts = {}) {
  const t0 = Date.now()
  try {
    const r = await fetch(url, {
      headers: {
        'User-Agent': UA,
        'Accept': 'application/json, text/plain, */*',
        'Origin': 'https://pan.quark.cn',
        'Referer': shareUrl,
        ...(opts.headers || {}),
      },
      method: opts.method || 'GET',
      body: opts.body,
      redirect: 'follow',
    })
    const text = await r.text()
    let json = null
    try { json = JSON.parse(text) } catch (e) {}
    return { url, status: r.status, ms: Date.now() - t0, text, json, headers: r.headers }
  } catch (e) {
    return { url, status: 0, text: '', err: String(e) }
  }
}

const tok = await req(
  'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc',
  {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ pwd_id: pwdId, passcode: '' }),
  }
)
const stoken = tok.json.data.stoken
const st = encodeURIComponent(stoken)
log('[1] stoken ok')

const d = await req(
  'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc&pwd_id=' + pwdId +
  '&stoken=' + st + '&_fetch_share=1&_fetch_banner=1&_fetch_total=1&_page=1&_size=50'
)
const fid = d.json.data.list[0].fid
log('[2] fid=' + fid)

// file/download 完整 dump
const dl = await req('https://drive-pc.quark.cn/1/clouddrive/file/download?pr=ucpro&fr=pc', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ fid_list: [fid], fids: [fid], pwd_id: pwdId, stoken: stoken }),
})
log('')
log('[3] file/download 完整 JSON：')
log(JSON.stringify(dl.json, null, 1).slice(0, 3500))

// 抽所有 url 字段
const urls = []
function walk(o, path) {
  if (!o || typeof o !== 'object') return
  for (const k of Object.keys(o)) {
    const v = o[k]
    const p = path + '.' + k
    if (typeof v === 'string' && /^https?:/.test(v)) urls.push([p, v])
    else if (v && typeof v === 'object') walk(v, p)
  }
}
walk(dl.json, 'root')
log('')
log('[4] 里面所有 url 字段：')
for (const [p, v] of urls) log('  ' + p + '\n      ' + v.slice(0, 200))

// 真下一次试试
log('')
log('[5] 实际下载验证：')
for (const [p, v] of urls) {
  if (!/download|\.mp3|file$|content/i.test(p) && !/download/i.test(v)) continue
  try {
    const r = await fetch(v, { headers: { 'User-Agent': UA, Referer: shareUrl }, redirect: 'follow' })
    const buf = Buffer.from(await r.arrayBuffer())
    const magic = buf.slice(0, 4).toString('hex')
    log('  ' + p + ' → ' + r.status + ' · ' + (r.headers.get('content-type') || '?') +
        ' · ' + buf.length + ' 字节 · magic=' + magic +
        (magic === '494433' ? '  ← 这是 ID3/mp3！' : ''))
    if (buf.length > 100000) {
      const fs = await import('node:fs')
      fs.writeFileSync('scripts/quark-test.mp3', buf)
      log('  ★ 已存 scripts/quark-test.mp3（' + buf.length + ' 字节）')
      break
    }
  } catch (e) {
    log('  ' + p + ' → 错误 ' + String(e))
  }
}

console.log(out.join('\n'))
