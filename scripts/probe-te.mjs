// 把 share.js 里 z/U/F/G/te 这一整段 dump 出来，看清 te 到底取什么
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36'
const js = await (await fetch(
  'https://g.alicdn.com/uc-cloud-drive-web-system/cloud-drive-web/4.6.7/share.js',
  { headers: { 'User-Agent': UA } }
)).text()

const out = []
const log = (...a) => out.push(a.join(' '))

// G 函数在 @473478 附近，z/U/F 在 @473025 附近。往前多找一点。
const seg = js.slice(471500, 475200)
log('=== 471500..475200 ===')
log(seg.replace(/\s+/g, ' '))

// 单独找 te 的定义候选：在这一段里所有 "function te" 或 ",te=" 或 "te=function"
log('')
log('=== 这一段里的 te 定义候选 ===')
for (const re of [/function te\(/g, /\bte\s*=\s*function/g, /\bte\s*=\s*\(/g]) {
  let m
  while ((m = re.exec(seg)) !== null) {
    log('  @' + (471500 + m.index) + '  ' + seg.slice(Math.max(0, m.index - 120), m.index + 320).replace(/\s+/g, ' '))
  }
}

console.log(out.join('\n'))
