// 复现 App 里 liumingye 第三音源的判定链路：search → detail → 夸克分享详情
// 重点看：分享里的文件名 / duration 字段，对不对得上「理想 - 赵雷 5:13」
import { writeFileSync } from 'node:fs'

const UA_PC =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36'
const BASE = 'https://so.liumingye.cn'
const QHOST = 'https://drive-pc.quark.cn'

async function jget(url, headers = {}) {
  try {
    const r = await fetch(url, {
      headers: {
        'User-Agent': UA_PC,
        Accept: 'application/json, text/plain, */*',
        Referer: BASE + '/',
        ...headers,
      },
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

const out = []
const say = (s) => { out.push(s); console.log(s) }

const title = process.argv[2] || '理想'
const artist = process.argv[3] || '赵雷'
const kw = `${title} ${artist}`

say(`=== liumingye 搜索：${kw} ===`)
const sr = await jget(`${BASE}/api/music/search?q=${encodeURIComponent(kw)}`)
say(`状态 ${sr.status}`)
const hits = sr.json?.data || []
say(`命中 ${hits.length} 条`)
for (const h of hits.slice(0, 10)) {
  say(`  - id=${h.id} | ${h.title} | ${h.artist} | ${h.album} | ${JSON.stringify(h.quality)}`)
}

// 逐个取详情，看分享链接
for (const h of hits.slice(0, 6)) {
  say(`\n=== detail ${h.id}（${h.title} - ${h.artist}）===`)
  const d = await jget(`${BASE}/api/music/${h.id}`)
  const dl = d.json?.downloads || []
  say(`  downloads ${dl.length} 条，lyrics ${d.json?.lyrics ? '有' : '无'}`)
  for (const x of dl) {
    say(`   · [${x.quality}] ${x.url}`)
  }
  // 夸克三段式（游客态，token/detail 免登录）
  for (const x of dl) {
    if (!String(x.url).includes('pan.quark.cn')) continue
    const m = String(x.url).match(/pan\.quark\.cn\/s\/([A-Za-z0-9]+)/)
    if (!m) { say(`   ! 分享码认不出：${x.url}`); continue }
    const pwdId = m[1]
    const tok = await qpost(
      `${QHOST}/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc`,
      { pwd_id: pwdId, passcode: '' },
    )
    const stoken = tok.json?.data?.stoken
    if (!stoken) { say(`   ! token 失败：${tok.text?.slice(0, 200)}`); continue }
    const det = await jget(
      `${QHOST}/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc&pwd_id=${pwdId}` +
        `&stoken=${encodeURIComponent(stoken)}&_fetch_share=1&_fetch_banner=1&_fetch_total=1&_page=1&_size=100`,
      { Referer: 'https://pan.quark.cn/' },
    )
    const list = det.json?.data?.list || []
    say(`   分享里 ${list.length} 项：`)
    for (const f of list) {
      say(
        `     · ${f.file_name} | size=${f.size} | duration=${f.duration} | dir=${f.dir_fid}` +
          ` | fid=${f.fid}`,
      )
    }
  }
}

writeFileSync('scripts/out-liumingye-check.txt', out.join('\n'))
