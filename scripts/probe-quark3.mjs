// 第三轮：stoken 之前没 URL 编码（里面有 + 和 =），修正后走完 detail -> download
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

// 1. stoken（这次把 stoken 编码好再用）
const tok = await req(
  'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc',
  {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ pwd_id: pwdId, passcode: '' }),
  }
)
const stoken = tok.json && tok.json.data ? tok.json.data.stoken : ''
log('[1] stoken → ' + (stoken ? '拿到' : '失败') + ' · ' + (tok.text || '').slice(0, 120).replace(/\s+/g, ' '))
if (!stoken) { console.log(out.join('\n')); process.exit(0) }
const st = encodeURIComponent(stoken)
log('    stoken（编码后）= ' + st)
log('    share 元数据：author=' + (tok.json.data.author ? tok.json.data.author.nickname : '?') +
    ' total_files=' + (tok.json.data.total_files ?? tok.json.data.total ?? '?'))

// 2. 列文件（几个变体）
const detailUrls = [
  'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc&pwd_id=' + pwdId + '&stoken=' + st + '&_fetch_share=1&_fetch_banner=1&_fetch_total=1&_page=1&_size=50',
  'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc&pwd_id=' + pwdId + '&stoken=' + st + '&_fetch_share=1&_fetch_banner=1&_page=1&_size=50',
  'https://drive-h.quark.cn/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc&pwd_id=' + pwdId + '&stoken=' + st + '&_fetch_share=1&_page=1&_size=50',
]

let list = null
for (const u of detailUrls) {
  const d = await req(u)
  log('')
  log('[2] detail ' + u.split('?')[0] + ' → ' + d.status + ' · ' + (d.text || '').slice(0, 260).replace(/\s+/g, ' '))
  const l = d.json && d.json.data ? d.json.data.list : null
  if (l && l.length) {
    list = l
    log('    ★ 条目数 ' + l.length)
    for (const it of l.slice(0, 15)) {
      log('      · ' + it.file_name + ' | dir=' + (it.dir_fid ? 1 : 0) + ' | size=' + it.size +
          ' | fid=' + it.fid + ' | share_download_url=' + (it.share_download_url ? '有' : '无') +
          ' | pwd_id=' + (it.pwd_id || '-'))
    }
    break
  }
}

// 3. 取下载直链
if (list) {
  const fids = list.filter(x => !x.dir_fid).map(x => x.fid)
  log('')
  log('[3] 可下载文件 ' + fids.length + ' 个')
  if (fids.length) {
    for (const host of ['https://drive-pc.quark.cn', 'https://drive-h.quark.cn']) {
      const dl = await req(
        host + '/1/clouddrive/share/sharepage/download?pr=ucpro&fr=pc&pwd_id=' + pwdId +
        '&stoken=' + st + '&fids=' + encodeURIComponent(JSON.stringify(fids))
      )
      log('  ' + host + ' → ' + dl.status + ' · ' + (dl.text || '').slice(0, 700).replace(/\s+/g, ' '))
      const arr = dl.json && dl.json.data ? dl.json.data : null
      if (arr && Array.isArray(arr) && arr[0] && arr[0].download_url) {
        const u = arr[0].download_url
        log('  ★ 直链: ' + u.slice(0, 160))
        // 4. 验证直链真能下
        const head = await fetch(u, { headers: { 'User-Agent': UA, Referer: shareUrl }, redirect: 'follow' })
        const buf = Buffer.from(await head.arrayBuffer())
        log('  [4] 直链下载 → ' + head.status + ' · ' + head.headers.get('content-type') +
            ' · ' + buf.length + ' 字节 · magic=' + buf.slice(0, 4).toString('hex'))
        break
      }
    }
  }
}

console.log(out.join('\n'))
