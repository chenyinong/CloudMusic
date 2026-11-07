/**
 * 坚果云(WebDAV)链路自检脚本 —— 零依赖，只需 Node.js
 *
 * 用法：
 *   node scripts/verify-webdav.cjs
 *   或 set NUT_ACCOUNT=你的邮箱 && set NUT_PASSWORD=应用密码 && node scripts/verify-webdav.cjs
 *
 * 依次验证：1) PROPFIND 列目录  2) 递归找音频  3) Range 拉流(206=进度条可拖动)
 */
const https = require('https');
const readline = require('readline');

const DEFAULT_SERVER = 'https://dav.jianguoyun.com/dav/';
const AUDIO_EXT = new Set([
  'mp3', 'flac', 'm4a', 'm4b', 'aac', 'ogg', 'oga', 'opus',
  'wav', 'wma', 'ape', 'aiff', 'aif', 'alac', 'dsf', 'dff'
]);

function ask(question) {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  return new Promise((resolve) => rl.question(question, (a) => { rl.close(); resolve(a.trim()); }));
}

function request(method, url, opts) {
  const o = opts || {};
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const h = { 'User-Agent': 'CloudMusic-Verify/1.0' };
    if (o.headers) Object.assign(h, o.headers);
    if (o.auth) h['Authorization'] = 'Basic ' + Buffer.from(o.auth).toString('base64');
    if (o.range) h['Range'] = o.range;

    const req = https.request({
      hostname: u.hostname,
      port: u.port || 443,
      path: u.pathname + u.search,
      method: method,
      headers: h,
      timeout: 30000
    }, (res) => {
      const chunks = [];
      let size = 0;
      res.on('data', (d) => { size += d.length; if (size <= 2097152) chunks.push(d); });
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks) }));
    });
    req.on('error', reject);
    req.on('timeout', function () { this.destroy(new Error('请求超时')); });
    if (o.body) req.write(o.body);
    req.end();
  });
}

function decodeName(href) {
  const seg = href.replace(/\/$/, '').split('/').pop() || '';
  try { return decodeURIComponent(seg); } catch (e) { return seg; }
}

function isAudio(href, contentType) {
  if (contentType && contentType.startsWith('audio/')) return true;
  const m = decodeName(href).match(/\.([a-z0-9]+)$/i);
  return !!m && AUDIO_EXT.has(m[1].toLowerCase());
}

function parseMultistatus(xml) {
  const out = [];
  const re = /<D:response>([\s\S]*?)<\/D:response>/g;
  let m;
  while ((m = re.exec(xml)) !== null) {
    const block = m[1];
    const hrefM = block.match(/<D:href>([\s\S]*?)<\/D:href>/);
    if (!hrefM) continue;
    const href = hrefM[1].trim();
    const lenM = block.match(/<D:getcontentlength>([\s\S]*?)<\/D:getcontentlength>/);
    const ctM = block.match(/<D:getcontenttype>([\s\S]*?)<\/D:getcontenttype>/);
    out.push({
      href: href,
      isDir: /<D:collection\s*\/?>/.test(block),
      size: lenM ? parseInt(lenM[1].trim(), 10) || 0 : 0,
      contentType: ctM ? ctM[1].trim() : '',
      name: decodeName(href)
    });
  }
  return out;
}

function serverUrl(server, href) {
  const base = server.endsWith('/') ? server : server + '/';
  return new URL(href.replace(/^\//, ''), base).toString();
}

async function listDir(server, auth, href) {
  const xml = '<?xml version="1.0" encoding="utf-8"?>' +
    '<D:propfind xmlns:D="DAV:"><D:prop><D:displayname/><D:getcontentlength/><D:getcontenttype/>' +
    '<D:getlastmodified/><D:resourcetype/></D:prop></D:propfind>';
  const res = await request('PROPFIND', serverUrl(server, href), {
    auth: auth,
    headers: { Depth: '1', 'Content-Type': 'application/xml; charset=utf-8' },
    body: xml
  });
  if (res.status === 401 || res.status === 403) throw new Error('认证失败(401/403)：账号或应用密码不正确');
  if (res.status !== 207 && res.status !== 200) throw new Error('PROPFIND 返回 ' + res.status);
  return parseMultistatus(res.body.toString('utf8'));
}

(async function main() {
  console.log('==== 坚果云 WebDAV 链路自检 ====\n');

  const account = process.env.NUT_ACCOUNT || await ask('账号(注册邮箱): ');
  const password = process.env.NUT_PASSWORD || await ask('应用密码(输入不回显属正常): ');
  const server = process.env.NUT_SERVER || DEFAULT_SERVER;
  const auth = account + ':' + password;

  console.log('\n[1/3] 测试 PROPFIND 列目录...');
  let root;
  try {
    root = await listDir(server, auth, '/');
  } catch (e) {
    console.error('  [失败] ' + e.message);
    console.error('\n排查建议:');
    console.error('  1) 应用密码是在「账户信息 -> 安全选项 -> 第三方应用管理」里新生成的那个');
    console.error('  2) 应用密码不是登录密码');
    console.error('  3) 服务器地址应为 ' + server);
    process.exit(1);
  }
  console.log('  [OK] 连接成功，根目录返回 ' + root.length + ' 个条目');

  console.log('\n[2/3] 递归扫描音频文件...');
  const found = [];
  const queue = ['/'];
  let visited = 0;
  while (queue.length && visited < 300) {
    const href = queue.shift();
    visited++;
    let entries;
    try { entries = await listDir(server, auth, href); } catch (e) { continue; }
    for (const e of entries) {
      if (e.isDir) {
        if (e.href !== href && e.href.split('/').filter(Boolean).length <= 6) queue.push(e.href);
      } else if (isAudio(e.href, e.contentType)) {
        found.push(e);
      }
    }
    process.stdout.write('\r  扫描中... 已找到 ' + found.length + ' 首');
  }
  console.log('\n  [OK] 扫描完成，共找到 ' + found.length + ' 个音频文件');
  found.slice(0, 15).forEach(function (f, i) {
    console.log('      ' + String(i + 1) + '. ' + f.name + '  (' + (f.size / 1048576).toFixed(1) + ' MB)');
  });
  if (found.length > 15) console.log('      ... 还有 ' + (found.length - 15) + ' 个');

  if (!found.length) {
    console.log('\n没找到音频文件。确认网盘里有 mp3/flac/m4a 等格式，且在顶层或浅层目录。');
    process.exit(0);
  }

  console.log('\n[3/3] 测试 Range 拉流(决定进度条能不能拖动)...');
  const target = found[0];
  const res = await request('GET', serverUrl(server, target.href), {
    auth: auth,
    range: 'bytes=0-65535'
  });
  if (res.status === 206) {
    console.log('  [OK] 返回 206 Partial Content，Range 正常');
    console.log('      Content-Range: ' + res.headers['content-range']);
    console.log('      Content-Type : ' + res.headers['content-type']);
    console.log('      实际收到 ' + res.body.length + ' 字节');
    console.log('\n==== 全部通过，可以开始做 App 了 ====');
  } else if (res.status === 200) {
    console.log('  [警告] 返回 200 而不是 206，说明该服务器忽略了 Range');
    console.log('         播放仍可用，但进度条拖动可能不准。');
  } else {
    console.log('  [失败] 返回 ' + res.status);
  }
})();