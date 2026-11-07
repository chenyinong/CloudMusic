// 第四步：扒前端 chunk，找 geturl / tree 的真实调用方式
import { readFileSync, writeFileSync } from 'node:fs'

const UA_PC =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36'
const BASE = 'https://so.liumingye.cn'

const html = readFileSync('scripts/liumingye-detail.html', 'utf8')

// 所有 chunk 路径
const chunks = [...new Set(html.match(/\/_nuxt\/[a-zA-Z0-9_\-\.]+\.(?:js|mjs)/g) || [])]
const out = []
out.push(`chunk 共 ${chunks.length} 个，开始并发下载并搜关键字`)

const KEY = ['geturl', 'source/tree', 'source/search', 'pan.quark', 'downloads', '提取码', 'decipher', 'decrypt']

async function fetchChunk(c) {
  try {
    const r = await fetch(BASE + c, { headers: { 'User-Agent': UA_PC } })
    return { c, body: await r.text() }
  } catch (e) {
    return { c, body: '' }
  }
}

const hits = []
for (let i = 0; i < chunks.length; i += 12) {
  const batch = chunks.slice(i, i + 12)
  const bodies = await Promise.all(batch.map(fetchChunk))
  for (const b of bodies) {
    for (const k of KEY) {
      const idx = b.body.indexOf(k)
      if (idx >= 0) {
        hits.push({ chunk: b.c, key: k, ctx: b.body.slice(Math.max(0, idx - 500), idx + 500) })
      }
    }
  }
}

out.push(`\n命中 ${hits.length} 处：`)
const seen = new Set()
for (const h of hits) {
  const sig = h.key + '|' + h.ctx.slice(0, 80)
  if (seen.has(sig)) continue
  seen.add(sig)
  out.push(`\n=== [${h.key}] ${h.chunk} ===`)
  out.push(h.ctx.replace(/\s+/g, ' '))
}

// 也看看 SSR 数据里 downloads 长什么样
const n = html.indexOf('__NUXT_DATA__')
if (n >= 0) {
  out.push(`\n=== __NUXT_DATA__ 片段 ===`)
  out.push(html.slice(n, n + 1500).replace(/\s+/g, ' '))
}

const text = out.join('\n')
console.log(text.slice(0, 20000))
writeFileSync('scripts/liumingye-chunk-hits.txt', text)
console.log('\n\n[完整结果已存 scripts/liumingye-chunk-hits.txt]')
