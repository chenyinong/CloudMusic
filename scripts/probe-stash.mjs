// 摸 params/stash 的正确 body —— 它不校验登录态（返回 400 而不是 401），所以能离线试
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36'
const share = process.argv[2] || 'https://pan.quark.cn/s/f7871cef50e8'
const pwdId = share.split('/s/')[1].split(/[?#]/)[0]
const out = []
const log = (...a) => out.push(a.join(' '))

async function post(url, body, headers = {}) {
  try {
    const r = await fetch(url, {
      method: 'POST',
      headers: {
        'User-Agent': UA,
        Accept: 'application/json',
        'Content-Type': 'application/json',
        Referer: 'https://pan.quark.cn/',
        ...headers,
      },
      body: JSON.stringify(body),
    })
    const t = await r.text()
    let j = null
    try { j = JSON.parse(t) } catch (e) {}
    return { status: r.status, text: t, json: j }
  } catch (e) {
    return { status: 0, text: String(e) }
  }
}

async function get(url) {
  try {
    const r = await fetch(url, { headers: { 'User-Agent': UA, Accept: 'application/json' } })
    const t = await r.text()
    let j = null
    try { j = JSON.parse(t) } catch (e) {}
    return { status: r.status, text: t, json: j }
  } catch (e) {
    return { status: 0, text: String(e) }
  }
}

// 先拿 stoken
const tok = await post(
  'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc',
  { pwd_id: pwdId, passcode: '' }
)
const stoken = tok.json?.data?.stoken
log('pwd_id=' + pwdId + ' stoken=' + (stoken ? stoken.slice(0, 16) + '…' : '失败'))
if (!stoken) { console.log(out.join('\n')); process.exit(0) }

// 拿到分享里的 fid
const det = await get(
  'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc&pwd_id=' +
    encodeURIComponent(pwdId) + '&stoken=' + encodeURIComponent(stoken) +
    '&_fetch_share=1&_fetch_banner=1&_fetch_total=1&_page=1&_size=100'
)
const fid = det.json?.data?.list?.find(x => !x.dir_fid)?.fid
log('fid=' + fid + '\n')

const STASH_BASE = 'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/params/stash'
// stash 的 pwd_id 应该在 query 里
const cands = [
  ['query pwd_id，body 空', STASH_BASE + '?pr=ucpro&fr=pc&pwd_id=' + pwdId, {}],
  ['query pwd_id+stoken', STASH_BASE + '?pr=ucpro&fr=pc&pwd_id=' + pwdId + '&stoken=' + encodeURIComponent(stoken), {}],
  ['query pwd_id+stoken+body save_as', STASH_BASE + '?pr=ucpro&fr=pc&pwd_id=' + pwdId + '&stoken=' + encodeURIComponent(stoken), { save_as: { to_pdir_fid: '0' } }],
  ['query pwd_id+body 全量', STASH_BASE + '?pr=ucpro&fr=pc&pwd_id=' + pwdId, {
    stoken: stoken, fid_list: [fid], to_pdir_fid: '0', pwd_save_path: '', scene: 'link',
    save_as: { to_pdir_fid: '0' },
  }],
]
for (const [label, url, body] of cands) {
  const r = await post(url, body)
  log('stash[' + label + '] → ' + r.status + ' · ' + (r.text || '').slice(0, 240).replace(/\s+/g, ' '))
}

// form-urlencoded 形态
try {
  const r = await fetch(STASH_BASE + '?pr=ucpro&fr=pc', {
    method: 'POST',
    headers: { 'User-Agent': UA, Accept: 'application/json', 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ pwd_id: pwdId, stoken: stoken }).toString(),
  })
  const t = await r.text()
  log('stash[form-urlencoded] → ' + r.status + ' · ' + t.slice(0, 240).replace(/\s+/g, ' '))
} catch (e) {
  log('stash[form] 错误 ' + String(e))
}

// save 直接调（不带 x-clouddrive-st），看是 401 还是 400
log('')
const SAVE = 'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/save?pr=ucpro&fr=pc'
const r1 = await post(SAVE, {
  fid_list: [fid], to_pdir_fid: '0', pwd_id: pwdId, stoken: stoken, pwd_save_path: '', scene: 'link',
})
log('save（无 st 头）→ ' + r1.status + ' · ' + (r1.text || '').slice(0, 240).replace(/\s+/g, ' '))

// task 接口的调用方式（前端用 taskId / interval）
log('')
const r2 = await get(
  'https://drive-pc.quark.cn/1/clouddrive/task?pr=ucpro&fr=pc&task_id=xxx&retry_index=0&interval=1000'
)
log('task GET task_id=xxx → ' + r2.status + ' · ' + (r2.text || '').slice(0, 200).replace(/\s+/g, ' '))
const r3 = await post('https://drive-pc.quark.cn/1/clouddrive/task?pr=ucpro&fr=pc', {
  taskId: 'xxx', retry_index: 0,
})
log('task POST taskId=xxx → ' + r3.status + ' · ' + (r3.text || '').slice(0, 200).replace(/\s+/g, ' '))

console.log(out.join('\n'))
