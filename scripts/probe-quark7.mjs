// 第七轮：412 怎么破。dump 错误页 + 试 Range / Referer / 换域名
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
log('直链：' + url.slice(0, 120) + '…')
log('文件：' + item.file_name + ' · ' + item.size + ' 字节 · 时长 ' + item.duration + 's · md5 ' + item.md5)

// 1. 看 412 的错误页到底说什么
log('')
log('[1] 412 错误页内容：')
const raw = await fetch(url, { headers: { 'User-Agent': UA, Referer: shareUrl }, redirect: 'follow' })
const rawBody = await raw.text()
log('  状态 ' + raw.status + ' · ' + raw.headers.get('content-type'))
log('  ' + rawBody.replace(/\s+/g, ' ').slice(0, 400))
log('  响应头：')
for (const [k, v] of raw.headers) {
  if (!/^(date|server|via|x-cache|age|time)/i.test(k)) log('    ' + k + ': ' + String(v).slice(0, 150))
}

// 2. 各种头组合
log('')
log('[2] 头组合试：')
const combos = [
  ['裸请求', {}],
  ['+Range', { Range: 'bytes=0-1048575' }],
  ['+Referer 页面', { Referer: 'https://pan.quark.cn/s/' + pwdId }],
  ['+Range+Accept 音频', { Range: 'bytes=0-1048575', Accept: 'audio/mpeg,*/*' }],
  ['+Sec-Fetch', { Range: 'bytes=0-1048575', 'Sec-Fetch-Dest': 'audio', 'Sec-Fetch-Mode': 'no-cors', 'Sec-Fetch-Site': 'same-site' }],
  ['+Cookie 空', { Range: 'bytes=0-1048575', Cookie: '' }],
]
for (const [label, h] of combos) {
  try {
    const r = await fetch(url, { headers: { 'User-Agent': UA, ...h }, redirect: 'follow' })
    const buf = Buffer.from(await r.arrayBuffer())
    log('  ' + label + ' → ' + r.status + ' · ' + (r.headers.get('content-type') || '?') +
        ' · ' + buf.length + ' 字节 · magic=' + buf.slice(0, 4).toString('hex') +
        (buf.slice(0, 3).toString('hex') === '494433' ? '  ★ ID3/mp3' : ''))
    if (buf.length > 50000) {
      const fs = await import('node:fs')
      fs.writeFileSync('scripts/quark-test.mp3', buf)
      log('    已存 scripts/quark-test.mp3')
      break
    }
  } catch (e) {
    log('  ' + label + ' → 错误 ' + String(e))
  }
}

// 3. 换域名
log('')
log('[3] 换域名：')
for (const host of ['dl-guest-sz.drive.quark.cn', 'dl-guest-cn-hangzhou.drive.quark.cn', 'dl-guest-sz-u.drive.quark.cn']) {
  const u = url.replace('dl-guest-sz-u.drive.quark.cn', host)
  try {
    const r = await fetch(u, { headers: { 'User-Agent': UA, Range: 'bytes=0-1048575' }, redirect: 'follow' })
    const buf = Buffer.from(await r.arrayBuffer())
    log('  ' + host + ' → ' + r.status + ' · ' + (r.headers.get('content-type') || '?') +
        ' · ' + buf.length + ' 字节 · magic=' + buf.slice(0, 4).toString('hex'))
  } catch (e) {
    log('  ' + host + ' → 错误 ' + String(e))
  }
}

console.log(out.join('\n'))
