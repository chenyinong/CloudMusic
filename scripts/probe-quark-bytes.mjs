// 摸清夸克直链的响应特征：到底返回的是不是原始音频字节
// 重点对比：① 完整下载 ② Range 请求 —— 两边内容不一样的话，就是「探测能过、下载被毙」的根因
import { writeFileSync } from 'node:fs'

const UA_PC =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36'
const BASE = 'https://so.liumingye.cn'
const QHOST = 'https://drive-pc.quark.cn'

async function jget(url, headers = {}) {
  try {
    const r = await fetch(url, {
      headers: { 'User-Agent': UA_PC, Referer: BASE + '/', ...headers },
      redirect: 'follow',
    })
    const text = await r.text()
    let json = null
    try { json = JSON.parse(text) } catch (e) { /* ignore */ }
    return { status: r.status, text, json }
  } catch (e) {
    return { status: 0, text: '', json: null, err: String(e) }
  }
}

async function qpost(url, body, headers = {}) {
  try {
    const r = await fetch(url, {
      method: 'POST',
      headers: {
        'User-Agent': UA_PC,
        'Content-Type': 'application/json',
        Referer: 'https://pan.quark.cn/',
        ...headers,
      },
      body: JSON.stringify(body),
    })
    const text = await r.text()
    let json = null
    try { json = JSON.parse(text) } catch (e) { /* ignore */ }
    return { status: r.status, text, json }
  } catch (e) {
    return { status: 0, text: '', json: null, err: String(e) }
  }
}

/** 读响应头 + 前 N 字节的十六进制 */
async function peek(url, { headers = {}, range = null, cookie = '' } = {}) {
  const h = { 'User-Agent': UA_PC, Referer: 'https://pan.quark.cn/', ...headers }
  if (range) h.Range = range
  if (cookie) h.Cookie = cookie
  try {
    const r = await fetch(url, { headers: h, redirect: 'follow' })
    const buf = Buffer.from(await r.arrayBuffer())
    return {
      status: r.status,
      headers: Object.fromEntries(r.headers.entries()),
      len: buf.length,
      head: buf.subarray(0, 32).toString('hex'),
      text: buf.subarray(0, 64).toString('latin1').replace(/[^\x20-\x7e]/g, '.'),
    }
  } catch (e) {
    return { status: 0, err: String(e) }
  }
}

const out = []
const say = (s) => { out.push(s); console.log(s) }

const title = process.argv[2] || '克卜勒'
const artist = process.argv[3] || '孙燕姿'

// 1. liumingye 搜歌 → 详情 → 夸克分享
const sr = await jget(`${BASE}/api/music/search?q=${encodeURIComponent(`${title} ${artist}`)}`)
const hits = (sr.json?.data || []).filter((h) => h.title === title && h.artist === artist)
say(`liumingye 命中同名同歌手：${hits.length} 条`)
if (!hits.length) { say('没找到'); process.exit(0) }

for (const h of hits.slice(0, 3)) {
  const d = await jget(`${BASE}/api/music/${h.id}`)
  for (const x of d.json?.downloads || []) {
    if (!String(x.url).includes('pan.quark.cn')) continue
    const m = String(x.url).match(/pan\.quark\.cn\/s\/([A-Za-z0-9]+)/)
    if (!m) continue
    const pwdId = m[1]
    const tok = await qpost(`${QHOST}/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc`, {
      pwd_id: pwdId,
      passcode: '',
    })
    const stoken = tok.json?.data?.stoken
    if (!stoken) { say(`  token 失败`); continue }
    const det = await jget(
      `${QHOST}/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc&pwd_id=${pwdId}` +
        `&stoken=${encodeURIComponent(stoken)}&_fetch_share=1&_page=1&_size=100`,
      { Referer: 'https://pan.quark.cn/' },
    )
    const list = det.json?.data?.list || []
    for (const f of list) {
      if (!/\.(mp3|flac|wav)$/i.test(f.file_name || '')) continue
      say(`\n=== ${f.file_name} (fid=${f.fid}) ===`)

      // 拿直链（游客态）
      const dl = await qpost(`${QHOST}/1/clouddrive/file/download?pr=ucpro&fr=pc`, {
        fid_list: [f.fid],
        fids: [f.fid],
        pwd_id: pwdId,
        stoken,
      })
      const arr = dl.json?.data
      if (!Array.isArray(arr) || !arr.length) {
        say(`  file/download 失败：${(dl.text || '').slice(0, 200)}`)
        continue
      }
      const url = arr[0].download_url
      say(`  直链：${url.slice(0, 110)}…`)

      const full = await peek(url)
      say(`  [完整下载] HTTP ${full.status}  收到 ${full.len} 字节`)
      say(`     content-type=${full.headers['content-type']}  content-encoding=${full.headers['content-encoding']}`)
      say(`     content-length=${full.headers['content-length']}  accept-ranges=${full.headers['accept-ranges']}`)
      say(`     前 32 字节：${full.head}`)
      say(`     可打印：${full.text}`)

      const ranged = await peek(url, { range: 'bytes=0-8191' })
      say(`  [Range 0-8191] HTTP ${ranged.status}  收到 ${ranged.len} 字节`)
      say(`     content-type=${ranged.headers['content-type']}  content-encoding=${ranged.headers['content-encoding']}`)
      say(`     前 32 字节：${ranged.head}`)

      const ident = await peek(url, { headers: { 'Accept-Encoding': 'identity' } })
      say(`  [Accept-Encoding: identity] HTTP ${ident.status}  收到 ${ident.len} 字节`)
      say(`     content-type=${ident.headers['content-type']}  content-encoding=${ident.headers['content-encoding']}`)
      say(`     前 32 字节：${ident.head}`)
    }
  }
}

writeFileSync('scripts/out-quark-probe.txt', out.join('\n'))
