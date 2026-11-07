// 第四轮：dump detail 完整 JSON，找下载直链的正确入口
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

// detail 完整 dump
const d = await req(
  'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc&pwd_id=' + pwdId +
  '&stoken=' + st + '&_fetch_share=1&_fetch_banner=1&_fetch_total=1&_page=1&_size=50'
)
log('')
log('[2] detail 完整 JSON：')
log(JSON.stringify(d.json, null, 1).slice(0, 4000))

const list = d.json.data.list || []
const file = list.find(x => !x.dir_fid)
const fid = file ? file.fid : ''

// 3. 试各种下载路径
log('')
log('[3] 试下载路径：')
const cands = [
  'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/download?pr=ucpro&fr=pc&pwd_id=' + pwdId + '&stoken=' + st + '&fids=' + encodeURIComponent(JSON.stringify([fid])),
  'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/download?pr=ucpro&fr=pc&pwd_id=' + pwdId + '&stoken=' + st + '&fid=' + fid,
  'https://drive-pc.quark.cn/1/clouddrive/file/download?pr=ucpro&fr=pc&fids=' + encodeURIComponent(JSON.stringify([fid])),
  'https://drive-pc.quark.cn/1/clouddrive/share/download?pr=ucpro&fr=pc&pwd_id=' + pwdId + '&stoken=' + st + '&fids=' + encodeURIComponent(JSON.stringify([fid])),
  'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/download_url?pr=ucpro&fr=pc&pwd_id=' + pwdId + '&stoken=' + st + '&fids=' + encodeURIComponent(JSON.stringify([fid])),
  'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/preview?pr=ucpro&fr=pc&pwd_id=' + pwdId + '&stoken=' + st + '&fids=' + encodeURIComponent(JSON.stringify([fid])),
]
for (const u of cands) {
  const r = await req(u)
  const path = u.split('?')[0].replace('https://drive-pc.quark.cn', '')
  log('  ' + path + ' → ' + r.status + ' · ' + (r.text || '').slice(0, 220).replace(/\s+/g, ' '))
}

// 4. 转存接口（可能可以免登录保存到临时）
log('')
log('[4] 试 save（转存）：')
for (const path of ['/1/clouddrive/share/sharepage/save', '/1/clouddrive/share/sharepage/batch_save']) {
  const r = await req('https://drive-pc.quark.cn' + path + '?pr=ucpro&fr=pc', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fid_list: [fid], to_pdir_fid: '0', pwd_id: pwdId, stoken: stoken, pwd_save_path: '' }),
  })
  log('  ' + path + ' → ' + r.status + ' · ' + (r.text || '').slice(0, 260).replace(/\s+/g, ' '))
}

// 5. share 对象里有没有现成的 url
log('')
log('[5] detail 里所有含 url/download 的字段：')
function walk(o, path) {
  if (!o || typeof o !== 'object') return
  for (const k of Object.keys(o)) {
    const v = o[k]
    const p = path + '.' + k
    if (typeof v === 'string' && /https?:|download/i.test(v)) log('  ' + p + ' = ' + v.slice(0, 140))
    else if (v && typeof v === 'object') walk(v, p)
  }
}
walk(d.json, 'root')

console.log(out.join('\n'))
