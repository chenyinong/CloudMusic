// 第九轮：412 的最后一搏 —— 改 URL 参数组合 + 换一首歌的分享 + 试 save 接口
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36'
const BASE = 'https://so.liumingye.cn'
const out = []
const log = (...a) => out.push(a.join(' '))

async function req(url, opts = {}) {
  try {
    const r = await fetch(url, {
      headers: {
        'User-Agent': UA,
        'Accept': 'application/json, text/plain, */*',
        'Origin': 'https://pan.quark.cn',
        'Referer': opts.referer || 'https://pan.quark.cn/',
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

// 对一个分享链接走完 token -> detail -> file/download，返回直链
async function resolve(shareUrl) {
  const pwdId = shareUrl.split('/s/')[1].split(/[?#]/)[0]
  const tok = await req(
    'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pwd_id: pwdId, passcode: '' }),
      referer: shareUrl,
    }
  )
  if (!tok.json || !tok.json.data || !tok.json.data.stoken) {
    return { err: 'stoken 失败: ' + (tok.text || '').slice(0, 160) }
  }
  const stoken = tok.json.data.stoken
  const st = encodeURIComponent(stoken)
  const d = await req(
    'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc&pwd_id=' + pwdId +
    '&stoken=' + st + '&_fetch_share=1&_fetch_banner=1&_fetch_total=1&_page=1&_size=50',
    { referer: shareUrl }
  )
  if (!d.json || !d.json.data || !d.json.data.list || !d.json.data.list.length) {
    return { err: 'detail 失败: ' + (d.text || '').slice(0, 160) }
  }
  const fid = d.json.data.list.find(x => !x.dir_fid)?.fid
  if (!fid) return { err: '没有文件' }
  const dl = await req('https://drive-pc.quark.cn/1/clouddrive/file/download?pr=ucpro&fr=pc', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fid_list: [fid], fids: [fid], pwd_id: pwdId, stoken: stoken }),
    referer: shareUrl,
  })
  if (!dl.json || !dl.json.data || !dl.json.data.length) {
    return { err: 'file/download 失败: ' + (dl.text || '').slice(0, 200) }
  }
  return { url: dl.json.data[0].download_url, file: dl.json.data[0], pwdId, stoken }
}

async function tryDl(label, url, headers = {}) {
  try {
    const r = await fetch(url, { headers: { 'User-Agent': UA, ...headers }, redirect: 'follow' })
    const buf = Buffer.from(await r.arrayBuffer())
    const magic = buf.slice(0, 4).toString('hex')
    const ok = magic === '494433' || magic === 'fffb' || magic === 'fff3' || magic === '4f676753'
    log('  ' + label + ' → ' + r.status + ' · ' + (r.headers.get('content-type') || '?') +
        ' · ' + buf.length + ' 字节 · magic=' + magic + (ok ? '  ★ 音频！' : ''))
    return { ok, buf, status: r.status }
  } catch (e) {
    log('  ' + label + ' → 错误 ' + String(e))
    return { ok: false }
  }
}

// ---------- 1. 参数组合变体 ----------
log('=== 1. 直链 URL 参数变体 ===')
const r0 = await resolve('https://pan.quark.cn/s/cbe2f1cc45a9')
if (r0.err) { log('  ' + r0.err); }
else {
  const u = new URL(r0.url)
  log('  完整直链参数：' + [...u.searchParams.keys()].join(', '))
  const variants = [
    ['原样', r0.url],
    ['去掉 abt', (() => { const x = new URL(r0.url); x.searchParams.delete('abt'); return x.toString() })()],
    ['去掉 filename', (() => { const x = new URL(r0.url); x.searchParams.delete('filename'); return x.toString() })()],
    ['只留 abt+auth_key+sp+token', (() => {
      const x = new URL(r0.url)
      const keep = ['abt', 'auth_key', 'sp', 'token']
      for (const k of [...x.searchParams.keys()]) if (!keep.includes(k)) x.searchParams.delete(k)
      return x.toString()
    })()],
    ['只留 auth_key+sp', (() => {
      const x = new URL(r0.url)
      const keep = ['auth_key', 'sp']
      for (const k of [...x.searchParams.keys()]) if (!keep.includes(k)) x.searchParams.delete(k)
      return x.toString()
    })()],
  ]
  for (const [label, u2] of variants) await tryDl(label, u2)
}

// ---------- 2. 换一首歌 ----------
log('')
log('=== 2. 换不同来源的分享 ===')
const search = await req(BASE + '/api/music/search?q=' + encodeURIComponent('稻香'))
const items = search.json.data.slice(0, 6)
for (const it of items) {
  const det = await req(BASE + '/api/music/' + it.id)
  const dls = det.json.downloads || []
  const q = dls.find(x => /quark/.test(x.url))
  const b = dls.find(x => /baidu/.test(x.url))
  log('  ' + it.title + '/' + it.artist + ' · ' + it.quality.join(',') +
      ' · 分享 ' + dls.map(x => x.quality + '=' + x.url.split('/')[2]).join(' '))
  if (q) {
    const rr = await resolve(q.url)
    if (rr.err) log('    解析失败: ' + rr.err)
    else {
      log('    直链 ok: ' + rr.file.file_name + ' · ' + rr.file.size + ' 字节 · ' + rr.file.duration + 's')
      await tryDl('    下载 ' + rr.file.file_name, rr.url)
    }
  }
}

// ---------- 3. save 接口 ----------
log('')
log('=== 3. sharepage/save 各种形态 ===')
if (!r0.err) {
  for (const path of ['/1/clouddrive/share/sharepage/save', '/1/clouddrive/share/sharepage/batch_save']) {
    for (const body of [
      { fid_list: [], to_pdir_fid: '0', pwd_id: r0.pwdId, stoken: r0.stoken },
      { fid_list: [''], to_pdir_fid: '0', pwd_id: r0.pwdId, stoken: r0.stoken, pwd_save_path: '' },
    ]) {
      const r = await req('https://drive-pc.quark.cn' + path + '?pr=ucpro&fr=pc', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      log('  ' + path + ' → ' + r.status + ' · ' + (r.text || '').slice(0, 200).replace(/\s+/g, ' '))
    }
  }
}

console.log(out.join('\n'))
