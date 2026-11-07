// 第三步：摸清 /api/source/tree 和 /api/source/geturl 的参数格式
// geturl 限流 15 次/30s，谨慎试
import { readFileSync } from 'node:fs'

const UA_PC =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36'
const BASE = 'https://so.liumingye.cn'

async function jget(url, method = 'GET', body = null) {
  const t0 = Date.now()
  try {
    const r = await fetch(url, {
      method,
      headers: {
        'User-Agent': UA_PC,
        'Accept': 'application/json, text/plain, */*',
        'Accept-Language': 'zh-CN,zh;q=0.9',
        Referer: BASE + '/',
        ...(body ? { 'Content-Type': 'application/json' } : {}),
      },
      redirect: 'follow',
      body: body ? JSON.stringify(body) : undefined,
    })
    const text = await r.text()
    return { url, status: r.status, ms: Date.now() - t0, text }
  } catch (e) {
    return { url, status: 0, text: '', err: String(e) }
  }
}

const out = []
const show = (r, label, n = 1400) => {
  out.push(`\n[${label}]\n  ${r.url}\n  状态 ${r.status} · ${r.ms}ms · ${r.text.length} 字节`)
  if (r.err) out.push(`  错误 ${r.err}`)
  out.push('  ' + (r.text || '').slice(0, n).replace(/\s+/g, ' '))
}

// 拿一个真实的夸克分享链接
const musicId = 'cmtn1pvfp0hsdkovgpo76ri9v'
const detail = await jget(`${BASE}/api/music/${musicId}`)
let quark = null
try { quark = JSON.parse(detail.text).downloads?.[0]?.url } catch (e) {}
out.push(`拿到的分享链接：${quark}`)

if (quark) {
  // tree：先试 url 参数
  show(await jget(`${BASE}/api/source/tree?url=${encodeURIComponent(quark)}`), 'tree?url=', 2200)

  // geturl：几种参数形态各试一次
  show(await jget(`${BASE}/api/source/geturl?url=${encodeURIComponent(quark)}`), 'geturl?url=', 1200)
  show(await jget(`${BASE}/api/source/geturl?url=${encodeURIComponent(quark)}`, 'POST', {}), 'geturl POST ?url=', 900)
  show(await jget(`${BASE}/api/source/geturl?id=${encodeURIComponent(quark)}`), 'geturl?id=', 700)
}

// 从详情页 HTML 里找 geturl 的前端调用代码（可能有参数名）
let html = ''
try { html = readFileSync('scripts/liumingye-detail.html', 'utf8') } catch (e) {}
// 找 "geturl" 附近的调用参数
const idxs = []
let i = -1
while ((i = html.indexOf('geturl', i + 1)) >= 0) idxs.push(i)
out.push(`\n[HTML 里 geturl 出现 ${idxs.length} 次]`)
for (const j of idxs.slice(0, 6)) {
  out.push('  ---')
  out.push('  ' + html.slice(Math.max(0, j - 260), j + 260).replace(/\s+/g, ' '))
}

console.log(out.join('\n'))
