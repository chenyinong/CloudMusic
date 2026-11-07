// 扒夸克 share.js：save 转存的完整 body 结构 + cookie 规格 + 配额提示
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36'
const out = []
const log = (...a) => out.push(a.join(' '))

const SHARE_JS = 'https://g.alicdn.com/uc-cloud-drive-web-system/cloud-drive-web/4.6.7/share.js'

const r = await fetch(SHARE_JS, { headers: { 'User-Agent': UA } })
const js = await r.text()
log('share.js ' + js.length + ' 字节')

// 1. save 函数的完整定义（含上下文）
const keys = [
  '/1/clouddrive/share/sharepage/save',
  '/1/clouddrive/share/sharepage/params/stash',
  'fid_list',
  'to_pdir_fid',
  'pwd_save_path',
  'scene:',
  '__pus',
  '__puus',
  'drive-pc.quark.cn/1/clouddrive/file/download',
  '转存',
  'save_pv',
]
for (const k of keys) {
  let i = js.indexOf(k)
  let n = 0
  while (i >= 0 && n < 3) {
    log('')
    log('--- [' + k + '] @' + i + ' ---')
    log(js.slice(Math.max(0, i - 500), i + 500).replace(/\s+/g, ' '))
    i = js.indexOf(k, i + 1)
    n++
  }
  if (n === 0) log('')
  if (n === 0) log('--- [' + k + '] 没找到 ---')
}

// 2. 所有 clouddrive 路径（找转存/下载相关）
log('')
log('=== 所有 /1/clouddrive 路径 ===')
const paths = [...new Set((js.match(/\/1\/clouddrive\/[a-zA-Z0-9_\/\-\.]+/g) || []))]
for (const p of paths.sort()) log('  ' + p)

console.log(out.join('\n'))
