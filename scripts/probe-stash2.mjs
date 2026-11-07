// 扒夸克 JS 里的 params/stash 调用 + 试各种嵌套 body
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

log('=== 1. 在夸克前端 js 里搜 params/stash ===')
const jsFiles = [
  'https://g.alicdn.com/uc-cloud-drive-web-system/cloud-drive-web/4.6.7/share.js',
  'https://g.alicdn.com/uc-cloud-drive-web-system/cloud-drive-web/4.6.7/index.js',
]
for (const f of jsFiles) {
  const js = await get(f)
  if (!js) { log('  拿不到 ' + f); continue }
  log('')
  log('  ' + f.split('/').pop() + ' · ' + js.length + ' 字节')
  for (const k of ['params/stash', 'stash_id', 'x-clouddrive-st', 'stash']) {
    let i = js.indexOf(k)
    let n = 0
    while (i >= 0 && n < 3) {
      log('')
      log('  --- [' + k + '] @' + i + ' ---')
      log('  ' + js.slice(Math.max(0, i - 500), i + 500).replace(/\s+/g, ' '))
      i = js.indexOf(k, i + 1)
      n++
    }
  }
}

log('')
log('=== 2. 试各种嵌套 body ===')
const share = 'https://pan.quark.cn/s/f7871cef50e8'
const pwdId = share.split('/s/')[1]
async function post(url, body, isForm = false) {
  try {
    const r = await fetch(url, {
      method: 'POST',
      headers: {
        'User-Agent': UA,
        Accept: 'application/json',
        'Content-Type': isForm ? 'application/x-www-form-urlencoded' : 'application/json',
        Referer: 'https://pan.quark.cn/',
      },
      body: isForm ? new URLSearchParams(body).toString() : JSON.stringify(body),
    })
    const t = await r.text()
    return { status: r.status, text: t }
  } catch (e) {
    return { status: 0, text: String(e) }
  }
}
const STASH = 'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/params/stash?pr=ucpro&fr=pc'
// 前端 U(e): body = { save_as: e }，pwd_id 要在 save_as 里面
async function post2(url, body) {
  try {
    const r = await fetch(url, {
      method: 'POST',
      headers: {
        'User-Agent': UA,
        Accept: 'application/json',
        'Content-Type': 'application/json',
        Referer: 'https://pan.quark.cn/',
      },
      body: JSON.stringify(body),
    })
    const t = await r.text()
    return { status: r.status, text: t }
  } catch (e) {
    return { status: 0, text: String(e) }
  }
}

// 先拿 stoken 和 fid
const tok = await post2('https://drive-pc.quark.cn/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc', {
  pwd_id: pwdId, passcode: '',
})
const stoken = tok.text ? JSON.parse(tok.text).data.stoken : ''
const det = await get(
  'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc&pwd_id=' +
    encodeURIComponent(pwdId) + '&stoken=' + encodeURIComponent(stoken) +
    '&_fetch_share=1&_fetch_banner=1&_fetch_total=1&_page=1&_size=100'
)
const fid = JSON.parse(det).data.list.find(x => !x.dir_fid)?.fid
log('pwd_id=' + pwdId + '  fid=' + fid + '\n')

const bodies = [
  ['save_as{pwd_id}', { save_as: { pwd_id: pwdId } }],
  ['save_as{pwd_id,fid_list,to_pdir_fid}', { save_as: { pwd_id: pwdId, fid_list: [fid], to_pdir_fid: '0' } }],
  ['save_as{pwd_id,fid_list,to_pdir_fid,pwd_save_path}', { save_as: { pwd_id: pwdId, fid_list: [fid], to_pdir_fid: '0', pwd_save_path: '' } }],
  ['save_as{全量+stoken}', { save_as: { pwd_id: pwdId, fid_list: [fid], to_pdir_fid: '0', pwd_save_path: '', stoken: stoken } }],
  ['save_as{pwdid,pwd_id}', { save_as: { pwdid: pwdId, pwd_id: pwdId, fid_list: [fid], to_pdir_fid: '0' } }],
  ['save_as{passcode}', { save_as: { pwd_id: pwdId, fid_list: [fid], to_pdir_fid: '0', pwd_save_path: '', passcode: '' } }],
]
for (const [label, body] of bodies) {
  const r = await post2(STASH, body)
  log('  ' + label + ' → ' + r.status + ' · ' + (r.text || '').slice(0, 240).replace(/\s+/g, ' '))
}

console.log(out.join('\n'))
