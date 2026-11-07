// 摸清 liumingye 的分享到底是「单首音频」还是「压缩包」
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36'
const BASE = 'https://so.liumingye.cn'
const out = []
const log = (...a) => out.push(a.join(' '))

async function jget(url, method = 'GET', body = null) {
  try {
    const r = await fetch(url, {
      method: method,
      headers: {
        'User-Agent': UA,
        Accept: 'application/json',
        Referer: BASE + '/',
        ...(body ? { 'Content-Type': 'application/json' } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    })
    const t = await r.text()
    try { return JSON.parse(t) } catch (e) { return { _raw: t.slice(0, 200) } }
  } catch (e) {
    return { _err: String(e) }
  }
}

// 夸克侧：解析分享，看第一个文件是什么
async function quarkPeek(shareUrl) {
  const pwdId = shareUrl.split('/s/')[1].split(/[?#]/)[0]
  const tok = await jget(
    'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc',
    'POST',
    { pwd_id: pwdId, passcode: '' }
  )
  const stoken = tok?.data?.stoken
  if (!stoken) return { err: 'stoken 失败' }
  const d = await jget(
    'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc&pwd_id=' +
      encodeURIComponent(pwdId) + '&stoken=' + encodeURIComponent(stoken) +
      '&_fetch_share=1&_fetch_banner=1&_fetch_total=1&_page=1&_size=100'
  )
  const list = d?.data?.list || []
  return {
    files: list.map(x => ({
      name: x.file_name,
      size: x.size,
      dir: !!x.dir_fid,
      fid: x.fid,
    })),
  }
}

for (const kw of ['稻香', '晴天']) {
  log('')
  log('======== 搜索：' + kw + ' ========')
  const s = await jget(BASE + '/api/music/search?q=' + encodeURIComponent(kw))
  const hits = (s?.data || []).slice(0, 8)
  for (const h of hits) {
    const d = await jget(BASE + '/api/music/' + h.id)
    log('')
    log('--- ' + h.title + ' / ' + h.artist + ' · quality=[' + (h.quality || []).join(',') + ']')
    log('    playUrl=' + JSON.stringify(d?.playUrl))
    const dls = d?.downloads || []
    for (const x of dls) {
      const host = x.url.split('/')[2] || '?'
      log('    · ' + x.quality + ' → ' + host + ' ' + x.url.slice(0, 60))
      if (/quark/.test(x.url)) {
        const peek = await quarkPeek(x.url)
        if (peek.err) log('        解析失败: ' + peek.err)
        else {
          const names = peek.files.map(f => f.name + (f.dir ? '/' : '') + ' (' + f.size + ')')
          log('        分享内容: ' + (names.length ? names.join(' | ') : '空'))
          const hasAudio = peek.files.some(f => /\.(mp3|flac|wav|m4a|aac|ogg)$/i.test(f.name))
          const hasArchive = peek.files.some(f => /\.(rar|zip|7z)$/i.test(f.name))
          log('        → ' + (hasAudio ? '含音频文件' : '无音频') + ' / ' + (hasArchive ? '含压缩包' : '无压缩包'))
        }
      }
    }
    if (d?.lyrics) log('    歌词 ' + d.lyrics.length + ' 字')
  }
}

console.log(out.join('\n'))
