// 扒夸克前端：转存(save)之后的 task 轮询、文件直链、删除，接口和参数
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

// 夸克 web 端主 js（分享页用的那个版本）
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
    '/1/clouddrive/task',
    'task_id',
    'retry_index',
    'save_as_list',
    '/1/clouddrive/file/download',
    '/1/clouddrive/file/delete',
    '/1/clouddrive/file/detail',
    '/1/clouddrive/file/list',
    'to_pdir_fid',
    'batch_delete',
  ]) {
    let i = js.indexOf(k)
    let n = 0
    while (i >= 0 && n < 2) {
      log('')
      log('--- [' + k + '] ---')
      log(js.slice(Math.max(0, i - 420), i + 420).replace(/\s+/g, ' '))
      i = js.indexOf(k, i + 1)
      n++
    }
  }
  log('')
  log('本文件里的 /1/clouddrive 路径：')
  for (const p of [...new Set(js.match(/\/1\/clouddrive\/[a-zA-Z0-9_\/\-\.]+/g) || [])].sort()) {
    log('  ' + p)
  }
}

console.log(out.join('\n'))
