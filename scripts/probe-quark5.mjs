// 第五轮：参数名是 fid_list 不是 fids；并验证 detail 里给的 preview_url 能不能直接下
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
    return { url, status: r.status, ms: Date.now() - t0, text, json }
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
const f = d.json.data.list[0]
const fid = f.fid
log('[2] 文件：' + f.file_name + ' · ' + f.size + ' 字节 · format=' + f.format_type)
log('    list[0] 里所有 url 类字段：')
for (const k of Object.keys(f)) {
  const v = f[k]
  if (typeof v === 'string' && /^https?:/.test(v)) log('      ' + k + ' = ' + v.slice(0, 110) + '…')
}

// 3. download / preview 用 fid_list
log('')
log('[3] fid_list 形态：')
const cands = [
  ['GET', '/1/clouddrive/share/sharepage/download?pr=ucpro&fr=pc&pwd_id=' + pwdId + '&stoken=' + st + '&fid_list=' + encodeURIComponent(JSON.stringify([fid]))],
  ['GET', '/1/clouddrive/share/sharepage/preview?pr=ucpro&fr=pc&pwd_id=' + pwdId + '&stoken=' + st + '&fid_list=' + encodeURIComponent(JSON.stringify([fid]))],
  ['POST', '/1/clouddrive/share/sharepage/download?pr=ucpro&fr=pc'],
  ['POST', '/1/clouddrive/file/download?pr=ucpro&fr=pc'],
]
for (const [method, path] of cands) {
  const r = await req('https://drive-pc.quark.cn' + path, {
    method: method,
    headers: { 'Content-Type': 'application/json' },
    body: method === 'POST' ? JSON.stringify({ fid_list: [fid], fids: [fid], pwd_id: pwdId, stoken: stoken }) : undefined,
  })
  log('  ' + method + ' ' + path.split('?')[0] + ' → ' + r.status + ' · ' + (r.text || '').slice(0, 400).replace(/\s+/g, ' '))
}

// 4. 直接拉 detail 给的 preview_url，看是不是能下的音频
log('')
log('[4] 拉 preview_url：')
for (const key of ['preview_url', 'big_thumbnail']) {
  const u = f[key]
  if (!u) { log('  ' + key + ' 无'); continue }
  try {
    const r = await fetch(u, { headers: { 'User-Agent': UA, Referer: shareUrl }, redirect: 'follow' })
    const buf = Buffer.from(await r.arrayBuffer())
    log('  ' + key + ' → ' + r.status + ' · ' + (r.headers.get('content-type') || '?') +
        ' · ' + buf.length + ' 字节 · magic=' + buf.slice(0, 4).toString('hex'))
  } catch (e) {
    log('  ' + key + ' → 错误 ' + String(e))
  }
}

console.log(out.join('\n'))
