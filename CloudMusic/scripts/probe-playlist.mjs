// 歌单接口可行性验证（2026-11 实测）
//
// 结论：
//   网易云歌单  ✅ 清单 + 音频都能拿
//   QQ 歌单      ✅ 清单能拿  ❌ 音频拿不到（播放地址接口全线 500005）
//
// 用法: node scripts/probe-playlist.mjs [网易云歌单id] [QQ歌单disstid]
const UA = 'Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36';

async function get(url, referer) {
  try {
    const r = await fetch(url, { headers: { 'User-Agent': UA, Referer: referer || '' }, redirect: 'manual' });
    return { status: r.status, buf: Buffer.from(await r.arrayBuffer()), location: r.headers.get('location') };
  } catch (e) {
    return { status: 0, buf: Buffer.alloc(0), location: null };
  }
}
async function postJson(url, obj, referer, ua) {
  const full = url + '?data=' + encodeURIComponent(JSON.stringify(obj));
  const r = await fetch(full, { headers: { 'User-Agent': ua || UA, Referer: referer || '', Origin: 'https://y.qq.com' } });
  return { status: r.status, text: await r.text() };
}

async function netease(playlistId) {
  console.log('\n========== 网易云歌单 ' + playlistId + ' ==========');
  const r = await get(`https://music.163.com/api/v6/playlist/detail?id=${playlistId}&n=1000&s=0`, 'https://music.163.com/');
  const pl = JSON.parse(r.buf.toString('utf8')).playlist || {};
  console.log(`歌单名: ${pl.name}   声明曲目数: ${pl.trackCount}   trackIds: ${(pl.trackIds || []).length}   tracks: ${(pl.tracks || []).length}`);
  console.log('说明: tracks 只返回前 N 首完整信息，trackIds 才是全量 -> 批量详情要另外调');

  const ids = (pl.trackIds || []).map(t => t.id);
  const all = [];
  for (let i = 0; i < ids.length; i += 50) {
    const batch = ids.slice(i, i + 50);
    const rr = await get('https://music.163.com/api/song/detail/?ids=' + encodeURIComponent(JSON.stringify(batch)), 'https://music.163.com/');
    try {
      for (const s of JSON.parse(rr.buf.toString('utf8')).songs || []) {
        all.push({ id: s.id, title: s.name, artists: (s.artists || []).map(a => a.name).join(' / '),
          album: (s.album && s.album.name) || '', sec: Math.round((s.duration || 0) / 1000) });
      }
    } catch (e) { console.log('  第 ' + i + ' 批失败: ' + e.message); }
  }
  console.log(`批量详情拿到 ${all.length} / ${ids.length} 首`);
  for (const s of all.slice(0, 3)) console.log(`   ${s.id} | ${s.title} | ${s.artists} | ${s.album} | ${s.sec}s`);

  console.log('\n下载测试（前 3 首）:');
  for (const s of all.slice(0, 3)) {
    const loc = await get(`https://music.163.com/song/media/outer/url?id=${s.id}.mp3`, 'https://music.163.com/');
    const target = (loc.location || '').replace('http://', 'https://');
    if (!target) { console.log(`   ${s.title} -> 拿不到地址 (HTTP ${loc.status})`); continue; }
    const audio = await get(target, 'https://music.163.com/');
    const sec = audio.buf.length / 16000;
    console.log(`   ${s.title} -> ${(audio.buf.length / 1024).toFixed(0)}KB HTTP${audio.status} 标称${s.sec}s 实得${sec.toFixed(0)}s ${sec < s.sec * 0.75 ? '* 只有片段' : 'OK'}`);
  }
}async function qq(disstid) {
  console.log('\n========== QQ 歌单 ' + disstid + ' ==========');
  // 关键：Referer 必须是 i2.y.qq.com 的歌单页，comm.cv 要带对，否则返回 500003
  const REF = 'https://i2.y.qq.com/n3/other/pages/details/playlist.html';
  const UA2 = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148';
  const all = [];
  let begin = 0, total = 0, name = '', guard = 0;
  for (;;) {
    const r = await postJson('https://u.y.qq.com/cgi-bin/musicu.fcg', {
      comm: { cv: '10020000' },
      req_0: { module: 'music.srfDissInfo.aiDissInfo', method: 'uniform_get_Dissinfo',
        param: { disstid: Number(disstid), enc_host_uin: '', tag: 1, userinfo: 1, song_begin: begin, song_num: 100, orderlist: 1 } },
    }, REF, UA2);
    const j = JSON.parse(r.text);
    const b = j.req_0 || {};
    if (b.code !== 0) { console.log('  失败 code=' + b.code + ' subcode=' + b.subcode); return; }
    const d = b.data || {};
    if (!name) { name = (d.dirinfo || {}).title || ''; total = d.total_song_num || 0;
      console.log(`歌单名: ${name}   声明曲目数: ${total}`); }
    const list = d.songlist || [];
    for (const s of list) {
      all.push({ mid: s.songmid || s.mid, title: (s.title || '') + (s.title_hilight || ''),
        artists: (s.singer || []).map(x => x.name).join(' / '),
        album: (s.album && s.album.name) || '', sec: s.interval || 0 });
    }
    begin += list.length;
    if (!list.length || !d.hasmore || begin >= total || ++guard > 30) break;
  }
  console.log(`实际拿到 ${all.length} / ${total} 首`);
  for (const s of all.slice(0, 4)) console.log(`   ${s.mid} | ${s.title} | ${s.artists} | ${s.album} | ${s.sec}s`);

  console.log('\n播放地址测试（这步决定了能不能下）:');
  for (const s of all.slice(0, 2)) {
    const r = await postJson('https://u.y.qq.com/cgi-bin/musicu.fcg', {
      comm: { cv: '10020000' },
      req_0: { module: 'music.pf_song_detail_svr', method: 'get_song_url_yqq',
        param: { song_type: 0, song_mid: s.mid, guid: '1000000000' } },
    }, REF, UA2);
    try {
      const b = JSON.parse(r.text).req_0 || {};
      const d = b.data || {};
      console.log(`   ${s.title} -> code=${b.code} subcode=${b.subcode} midurlinfo=${(d.midurlinfo || []).length} sip=${(d.sip || []).length}`);
    } catch (e) { console.log('   解析失败'); }
  }
  console.log('   （code 500005 = 拿不到播放地址，QQ 音乐这一路下不了）');
}

const nId = process.argv[2] || '3778678';
const qId = process.argv[3] || '8919002463';
await netease(nId);
await new Promise(r => setTimeout(r, 3000));
await qq(qId);