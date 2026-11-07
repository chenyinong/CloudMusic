// 扒 task 完成后的返回结构：前端用 te(r) 从 save 响应里提取，task 返回里有 save_as
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36'
const out = []
const log = (...a) => out.push(a.join(' '))

async function get(url) {
  try {
    const r = await fetch(url, { headers: { 'User-Agent': UA } })
    return await r.text()
  } catch (e) {
    return ''
  }
}

const files = [
  'https://g.alicdn.com/uc-cloud-drive-web-system/cloud-drive-web/4.6.7/share.js',
  'https://g.alicdn.com/uc-cloud-drive-web-system/cloud-drive-web/4.6.7/index.js',
]
for (const f of files) {
  const js = await get(f)
  if (!js) { log('拿不到 ' + f); continue }
  log('')
  log('==== ' + f.split('/').pop() + ' · ' + js.length + ' 字节 ====')

  for (const k of [
    'function te(',
    'save_as_list',
    '"save_as"',
    '.save_as',
    'task/save_as',
    'save_as_list?',
    'function G(e,t)',
    'tq_gap',
  ]) {
    let i = js.indexOf(k)
    let n = 0
    while (i >= 0 && n < 3) {
      log('')
      log('--- [' + k + '] @' + i + ' ---')
      log(js.slice(Math.max(0, i - 560), i + 560).replace(/\s+/g, ' '))
      i = js.indexOf(k, i + 1)
      n++
    }
  }
}

console.log(out.join('\n'))
