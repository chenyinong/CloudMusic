// 验证夸克网盘分享链接能不能在 App 里直接解析出音频直链（不依赖 liumingye 的私有接口）
import { writeFileSync } from 'node:fs'

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36'
const out = []

function log(...a) { out.push(a.join(' ')); }

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
    return { url, status: r.status, ms: Date.now() - t0, text, json, headers: r.headers }
  } catch (e) {
    return { url, status: 0, text: '', err: String(e) }
  }
}

const shareUrl = process.argv[2] || 'https://pan.quark.cn/s/cbe2f1cc45a9'
const shareId = shareUrl.split('/s/')[1].split(/[?#]/)[0]
log(`分享链接：${shareUrl}\n提取的 shareId：${shareId}\n`)

// 1. 打开分享页，拿 pwd_id / stoken
const page = await req(shareUrl, { headers: { Accept: 'text/html,*/*' } })
log(`[1] 分享页 → 状态 ${page.status} · ${page.text.length} 字节`)
const m1 = page.text.match(/pwd_id["']?\s*[:=]\s*["']([\w-]+)["']/i)
const m2 = page.text.match(/stoken["']?\s*[:=]\s*["']([\w-]+)["']/i)
const m3 = page.text.match(/shareId["']?\s*[:=]\s*["']([\w-]+)["']/i)
log(`    pwd_id 命中：${m1 ? m1[1] : '无'}`)
log(`    stoken 命中：${m2 ? m2[1] : '无'}`)
log(`    shareId 命中：${m3 ? m3[1] : '无'}`)

let pwdId = m1 ? m1[1] : ''
let stoken = m2 ? m2[1] : ''

// 1.5 有时 pwd_id 藏在 __INITIAL_STATE__ 里
if (!pwdId) {
  const st = page.text.indexOf('__INITIAL_STATE__')
  if (st >= 0) {
    const seg = page.text.slice(st, st + 3000)
    const p = seg.match(/pwd_id["']?\s*[:=]\s*["']([\w-]+)["']/i)
    const s = seg.match(/stoken["']?\s*[:=]\s*["']([\w-]+)["']/i)
    if (p) { pwdId = p[1]; log(`    __INITIAL_STATE__ 里 pwd_id：${pwdId}`) }
    if (s) { stoken = s[1]; log(`    __INITIAL_STATE__ 里 stoken：${stoken}`) }
  }
}

// 2. 拿 stoken（免提取码）
if (!stoken && pwdId) {
  const r = await req(
    'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Referer: shareUrl },
      body: JSON.stringify({ pwd_id: pwdId, passcode: '' }),
    }
  )
  log(`\n[2] 取 stoken → 状态 ${r.status} · ${r.text.length} 字节`)
  log('    ' + (r.text || '').slice(0, 500).replace(/\s+/g, ' '))
  stoken = r.json?.data?.stoken || ''
  log(`    拿到 stoken：${stoken ? '有' : '无'}`)
}

// 3. 列文件
if (pwdId && stoken) {
  const d = await req(
    `https://drive-pc.quark.cn/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc&pwd_id=${pwdId}&stoken=${stoken}&_fetch_share=1&_fetch_banner=1&_fetch_share=1&_fetch_total=1&_page=1&_size=50`,
    { headers: { Referer: shareUrl } }
  )
  log(`\n[3] 列文件 → 状态 ${d.status} · ${d.text.length} 字节`)
  const list = d.json?.data?.list || []
  log(`    条目数：${list.length}`)
  for (const it of list.slice(0, 12)) {
    log(`    · ${it.file_name}  dir=${it.dir_fid ? 1 : 0} size=${it.size} fid=${it.fid} download_url=${it.share_download_url ? '有' : '无'}`)
  }

  // 4. 取下载直链
  const fids = list.filter(x => !x.dir_fid).map(x => x.fid)
  if (fids.length) {
    const dl = await req(
      `https://drive-pc.quark.cn/1/clouddrive/share/sharepage/download?pr=ucpro&fr=pc&pwd_id=${pwdId}&stoken=${stoken}&fids=${encodeURIComponent(JSON.stringify(fids))}`,
      { headers: { Referer: shareUrl } }
    )
    log(`\n[4] 取直链 → 状态 ${dl.status} · ${dl.text.length} 字节`)
    log('    ' + (dl.text || '').slice(0, 800).replace(/\s+/g, ' '))
  }
} else {
  log('\n[跳过] 没拿到 pwd_id/stoken')
}

const text = out.join('\n')
console.log(text)
writeFileSync('scripts/quark-result.txt', text)
console.log('\n[已存 scripts/quark-result.txt]')
