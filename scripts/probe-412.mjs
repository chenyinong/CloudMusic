// 把 412 的响应页完整 dump 出来 —— 那 262 字节可能直接写了原因
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36'
const out = []
const log = (...a) => out.push(a.join(' '))

async function jget(url, method = 'GET', body = null, referer = 'https://pan.quark.cn/') {
  const r = await fetch(url, {
    method: method,
    headers: {
      'User-Agent': UA,
      Accept: 'application/json, text/plain, */*',
      Origin: 'https://pan.quark.cn',
      Referer: referer,
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
    redirect: 'follow',
  })
  const t = await r.text()
  let j = null
  try { j = JSON.parse(t) } catch (e) {}
  return { status: r.status, text: t, json: j, headers: r.headers }
}

// 拿一个单首 mp3 分享的直链
const share = process.argv[2] || 'https://pan.quark.cn/s/cbe2f1cc45a9'
const pwdId = share.split('/s/')[1].split(/[?#]/)[0]
const tok = await jget(
  'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc',
  'POST',
  { pwd_id: pwdId, passcode: '' }
)
const stoken = tok.json.data.stoken
const det = await jget(
  'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc&pwd_id=' +
    encodeURIComponent(pwdId) + '&stoken=' + encodeURIComponent(stoken) +
    '&_fetch_share=1&_fetch_banner=1&_fetch_total=1&_page=1&_size=100'
)
const fid = det.json.data.list.find(x => !x.dir_fid).fid
const dl = await jget('https://drive-pc.quark.cn/1/clouddrive/file/download?pr=ucpro&fr=pc', 'POST', {
  fid_list: [fid],
  fids: [fid],
  pwd_id: pwdId,
  stoken: stoken,
})
const item = dl.json.data[0]
const url = item.download_url
log('文件: ' + item.file_name + ' · ' + item.size + ' 字节 · ' + item.duration + 's')
log('直链: ' + url)
log('')

// 完整 dump 412 响应
log('=== 直链 GET（无 Range）===')
const r1 = await fetch(url, { headers: { 'User-Agent': UA, Referer: 'https://pan.quark.cn/' }, redirect: 'follow' })
log('状态 ' + r1.status)
for (const [k, v] of r1.headers) log('  ' + k + ': ' + String(v).slice(0, 160))
const b1 = Buffer.from(await r1.arrayBuffer())
log('body (' + b1.length + ' 字节):')
log(b1.toString('utf8'))
log('')

// 带 Range
log('=== 带 Range ===')
const r2 = await fetch(url, {
  headers: { 'User-Agent': UA, Referer: 'https://pan.quark.cn/', Range: 'bytes=0-1023' },
  redirect: 'follow',
})
log('状态 ' + r2.status)
for (const [k, v] of r2.headers) log('  ' + k + ': ' + String(v).slice(0, 160))
const b2 = Buffer.from(await r2.arrayBuffer())
log('body (' + b2.length + ' 字节):')
log(b2.toString('utf8').slice(0, 800))

console.log(out.join('\n'))
