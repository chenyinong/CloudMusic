// 重新实测 so.liumingye.cn 到底能不能接进 App。
// 上次的结论是「网盘聚合站，只有分享链接，没有直链」，站点可能变了，重新验一遍。
import { writeFileSync } from 'node:fs'

const UA_PC =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36'
const BASE = 'https://so.liumingye.cn'

async function get(url, headers = {}) {
  const t0 = Date.now()
  try {
    const r = await fetch(url, {
      headers: { 'User-Agent': UA_PC, 'Accept-Language': 'zh-CN,zh;q=0.9', ...headers },
      redirect: 'follow',
    })
    const body = await r.text()
    return { url, status: r.status, ms: Date.now() - t0, body, finalUrl: r.url }
  } catch (e) {
    return { url, status: 0, ms: Date.now() - t0, body: '', err: String(e) }
  }
}

function pick(re, s) {
  const m = re.exec(s)
  return m ? m[1] : null
}

const q = process.argv[2] || '稻香'
const out = []

// ---------- 1. 搜索页 ----------
const search = await get(`${BASE}/search?q=${encodeURIComponent(q)}`)
out.push(`[搜索页] ${search.url}\n  状态 ${search.status} · ${search.ms}ms · ${search.body.length} 字节`)
if (search.err) out.push(`  错误 ${search.err}`)

// 页面里所有 href，找详情页链接
const detailLinks = [...new Set((search.body.match(/href="\/music\/[^"]+"/g) || []))]
out.push(`  详情页链接 ${detailLinks.length} 个：${detailLinks.slice(0, 5).join(', ')}`)

// 有没有内嵌的 SSR 数据
const ssrKeys = [...new Set((search.body.match(/window\.__NUXT__|__NUXT_DATA__|__INITIAL_STATE__/g) || []))]
out.push(`  SSR 数据标记：${ssrKeys.join(', ') || '无'}`)

// 有没有 api 路径
const apiPaths = [...new Set((search.body.match(/["'`]\/api\/[a-zA-Z0-9_\/\-\.]+/g) || []))]
out.push(`  页面里出现的 /api 路径：${apiPaths.join(', ') || '无'}`)

// ---------- 2. 拿一个详情页 ----------
let detail = null
if (detailLinks.length) {
  const href = detailLinks[0].replace('href="', '').replace('"', '')
  detail = await get(BASE + href)
  out.push(`\n[详情页] ${BASE + href}\n  状态 ${detail.status} · ${detail.ms}ms · ${detail.body.length} 字节`)

  // 网盘链接
  const pans = [...new Set((detail.body.match(/https?:\/\/[a-z0-9.]*(baidu|quark|aliyun|alipan|123pan|lanzou|ctfile|weiyun)[a-z0-9.]*\.[a-z]{2,}[^\s"'<>]*/gi) || []))]
  out.push(`  网盘类链接 ${pans.length} 个：`)
  for (const p of pans.slice(0, 10)) out.push(`    ${p}`)

  // 提取码
  const codes = [...new Set((detail.body.match(/(?:提取码|访问码|密码|pwd|code)["'\s:：]{1,6}([a-zA-Z0-9]{4})/g) || []))]
  out.push(`  提取码样式命中：${codes.slice(0, 8).join(' | ') || '无'}`)

  // 音频直链
  const audio = [...new Set((detail.body.match(/https?:\/\/[^\s"'<>]+\.(?:mp3|flac|m4a|aac|wav|ogg)[^\s"'<>]*/gi) || []))]
  out.push(`  音频直链 ${audio.length} 个：${audio.slice(0, 5).join(', ') || '无'}`)

  // 站内下载接口
  const dlApi = [...new Set((detail.body.match(/["'`]\/api\/[a-zA-Z0-9_\/\-\.]+/g) || []))]
  out.push(`  详情页 /api 路径：${dlApi.join(', ') || '无'}`)

  // 前端 chunk
  const chunks = [...new Set((detail.body.match(/\/_nuxt\/[a-zA-Z0-9_\-\.]+\.js/g) || []))]
  out.push(`  前端 chunk ${chunks.length} 个`)
  let apiInChunk = new Set()
  for (const c of chunks.slice(0, 12)) {
    const js = await get(BASE + c)
    const paths = js.body.match(/["'`]\/api\/[a-zA-Z0-9_\/\-\.]+/g) || []
    for (const p of paths) apiInChunk.add(p)
  }
  out.push(`  chunk 里出现的 /api 路径（去重）：${[...apiInChunk].join(', ') || '无'}`)
}

// ---------- 3. 已知 api 逐个试 ----------
out.push('\n[逐个试 api]')
for (const p of ['/api/site-seo', '/api/captcha/verify']) {
  const r = await get(BASE + p)
  out.push(`  ${p} → ${r.status} · ${r.body.slice(0, 160).replace(/\s+/g, ' ')}`)
}

console.log(out.join('\n'))
writeFileSync('scripts/liumingye-detail.html', detail?.body || '')
console.log('\n详情页 HTML 已存 scripts/liumingye-detail.html')
