// 第二步：实测 liumingye 新暴露的 API 层，找能直连下载的路子
import { writeFileSync, readFileSync } from 'node:fs'

const UA_PC =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36'
const BASE = 'https://so.liumingye.cn'

async function jget(url, headers = {}) {
  const t0 = Date.now()
  try {
    const r = await fetch(url, {
      headers: {
        'User-Agent': UA_PC,
        'Accept': 'application/json, text/plain, */*',
        'Accept-Language': 'zh-CN,zh;q=0.9',
        Referer: BASE + '/',
        ...headers,
      },
      redirect: 'follow',
    })
    const text = await r.text()
    let json = null
    try { json = JSON.parse(text) } catch (e) { /* 不是 json */ }
    return { url, status: r.status, ms: Date.now() - t0, text, json }
  } catch (e) {
    return { url, status: 0, text: '', json: null, err: String(e) }
  }
}

const q = process.argv[2] || '稻香'
const out = []
const show = (r, label, n = 900) => {
  out.push(`\n[${label}] ${r.url}\n  状态 ${r.status} · ${r.ms}ms · ${r.text.length} 字节`)
  if (r.err) out.push(`  错误 ${r.err}`)
  out.push('  ' + (r.text || '').slice(0, n).replace(/\s+/g, ' '))
}

// 1. 音乐搜索
show(await jget(`${BASE}/api/music/search?q=${encodeURIComponent(q)}`), 'api/music/search', 1200)

// 2. 音乐详情
const musicId = process.argv[3] || 'cmtn1pvfp0hsdkovgpo76ri9v'
show(await jget(`${BASE}/api/music/${musicId}`), 'api/music/{id}', 1500)

// 3. 源搜索
show(await jget(`${BASE}/api/source/search?q=${encodeURIComponent(q)}`), 'api/source/search', 1200)

// 4. 源树
show(await jget(`${BASE}/api/source/tree`), 'api/source/tree', 800)

// 5. AI 搜索
show(await jget(`${BASE}/api/ai-search?q=${encodeURIComponent(q)}`), 'api/ai-search', 800)

// 6. 从详情页 HTML 里扒 geturl 的调用方式
let html = ''
try { html = readFileSync('scripts/liumingye-detail.html', 'utf8') } catch (e) { html = '' }
for (const key of ['/api/source/geturl', '/api/source/search', '/api/source/tree']) {
  const i = html.indexOf(key)
  out.push(`\n[HTML 里 ${key} 的上下文]`)
  if (i < 0) { out.push('  没找到'); continue }
  out.push('  ' + html.slice(Math.max(0, i - 420), i + 420).replace(/\s+/g, ' '))
}

console.log(out.join('\n'))
