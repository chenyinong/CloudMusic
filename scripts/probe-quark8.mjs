// 第八轮：用 node:https 发原始请求，排除 fetch(undici) 自动加头的影响
import https from 'node:https'
import { writeFileSync } from 'node:fs'

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36'
const shareUrl = process.argv[2] || 'https://pan.quark.cn/s/cbe2f1cc45a9'
const pwdId = shareUrl.split('/s/')[1].split(/[?#]/)[0]
const out = []
const log = (...a) => out.push(a.join(' '))

async function req(url, opts = {}) {
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
    return { url, status: r.status, text, json }
  } catch (e) {
    return { url, status: 0, text: '', err: String(e) }
  }
}

// 原始 https 请求，header 完全可控
function rawGet(url, headers) {
  return new Promise((resolve) => {
    const u = new URL(url)
    const req = https.request(
      {
        hostname: u.hostname,
        path: u.pathname + u.search,
        method: 'GET',
        headers: headers,
        rejectUnauthorized: false,
      },
      (res) => {
        const chunks = []
        res.on('data', (c) => chunks.push(c))
        res.on('end', () =>
          resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks) })
        )
      }
    )
    req.on('error', (e) => resolve({ status: 0, error: String(e) }))
    req.setTimeout(20000, () => { req.destroy(); resolve({ status: 0, error: 'timeout' }) })
    req.end()
  })
}

// 先拿直链
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
const d = await req(
  'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc&pwd_id=' + pwdId +
  '&stoken=' + st + '&_fetch_share=1&_fetch_banner=1&_fetch_total=1&_page=1&_size=50'
)
const fid = d.json.data.list[0].fid
const dl = await req('https://drive-pc.quark.cn/1/clouddrive/file/download?pr=ucpro&fr=pc', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ fid_list: [fid], fids: [fid], pwd_id: pwdId, stoken: stoken }),
})
const item = dl.json.data[0]
const url = item.download_url
log('直链已取到：' + url.slice(0, 100) + '…')
log('文件 ' + item.file_name + ' · ' + item.size + ' 字节 · ' + item.duration + 's')
log('')

// 各种原始 header 组合
const combos = [
  ['只有 Host+UA', { Host: new URL(url).host, 'User-Agent': UA }],
  ['+Range', { Host: new URL(url).host, 'User-Agent': UA, Range: 'bytes=0-65535' }],
  ['+Referer+UA', { Host: new URL(url).host, 'User-Agent': UA, Referer: 'https://pan.quark.cn/' }],
  ['+Accept-Encoding identity', { Host: new URL(url).host, 'User-Agent': UA, 'Accept-Encoding': 'identity', Range: 'bytes=0-65535' }],
  ['+sec-fetch 全套', { Host: new URL(url).host, 'User-Agent': UA, Range: 'bytes=0-65535', 'Accept': '*/*', 'Accept-Language': 'zh-CN,zh;q=0.9', 'Sec-Fetch-Dest': 'audio', 'Sec-Fetch-Mode': 'cors', 'Sec-Fetch-Site': 'same-site' }],
  ['+If-None-Match 空', { Host: new URL(url).host, 'User-Agent': UA, 'If-None-Match': '' }],
  ['+Pragma no-cache', { Host: new URL(url).host, 'User-Agent': UA, Pragma: 'no-cache', 'Cache-Control': 'no-cache' }],
]

for (const [label, h] of combos) {
  const r = await rawGet(url, h)
  if (r.error) { log('  ' + label + ' → 错误 ' + r.error); continue }
  const magic = r.body ? r.body.slice(0, 4).toString('hex') : '?'
  log('  ' + label + ' → ' + r.status + ' · ' + (r.headers['content-type'] || '?') +
      ' · ' + (r.body ? r.body.length : 0) + ' 字节 · magic=' + magic +
      (magic === '494433' || magic === 'fffb' ? '  ★ 音频！' : ''))
  if (r.body && r.body.length > 50000 && magic !== '3c21444f') {
    writeFileSync('scripts/quark-test.mp3', r.body)
    log('    ★ 已存 scripts/quark-test.mp3（' + r.body.length + ' 字节）')
    log('    响应头：')
    for (const [k, v] of Object.entries(r.headers)) log('      ' + k + ': ' + String(v).slice(0, 120))
    break
  }
}

console.log(out.join('\n'))
