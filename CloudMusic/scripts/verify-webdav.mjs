// WebDAV 连通性 + 写入能力自检
// 用法: node scripts/verify-webdav.cjs <服务器地址> <账号> <应用密码>
const [, , server, user, pass] = process.argv;

if (!server || !user || !pass) {
  console.log('用法: node scripts/verify-webdav.cjs <服务器地址> <账号> <应用密码>');
  process.exit(1);
}

const base = server.replace(/\/+$/, '');
const auth = 'Basic ' + Buffer.from(user + ':' + pass).toString('base64');

async function req(method, path, body, extra) {
  const url = path.startsWith('http') ? path : base + path;
  const headers = Object.assign({ Authorization: auth, 'User-Agent': 'CloudMusic-Verify/1.0' }, extra || {});
  const init = { method, headers, redirect: 'manual' };
  if (body != null) {
    init.body = body;
    headers['Content-Type'] = 'application/octet-stream';
  }
  const r = await fetch(url, init);
  const text = await r.text();
  return { status: r.status, text };
}

let ok = true;
function report(title, pass, detail) {
  console.log((pass ? '  [OK]   ' : '  [FAIL] ') + title + (detail ? '  -> ' + detail : ''));
  if (!pass) ok = false;
}

const TEST_DIR = '/_cloudmusic_test';
const TEST_FILE = '/_cloudmusic_test/hello.txt';
const CONTENT = 'cloudmusic upload test ' + new Date().toISOString();

console.log('目标: ' + base);
console.log('');
console.log('1) 读取能力 (PROPFIND 根目录)');
try {
  const r = await req('PROPFIND', '/', null, { Depth: '1' });
  report('PROPFIND 根目录', r.status === 207 || r.status === 200, 'HTTP ' + r.status);
} catch (e) {
  report('PROPFIND 根目录', false, e.message);
}

console.log('');
console.log('2) 写入能力 (MKCOL 建目录 + PUT 传文件)');
let madeDir = false;
try {
  const mk = await req('MKCOL', TEST_DIR, null);
  madeDir = mk.status === 201 || mk.status === 405 || mk.status === 200;
  report('MKCOL 建测试目录', madeDir, 'HTTP ' + mk.status);
} catch (e) {
  report('MKCOL 建测试目录', false, e.message);
}

if (madeDir) {
  try {
    const put = await req('PUT', TEST_FILE, CONTENT);
    report('PUT 上传测试文件', put.status === 201 || put.status === 204 || put.status === 200, 'HTTP ' + put.status);
  } catch (e) {
    report('PUT 上传测试文件', false, e.message);
  }
  try {
    const r = await req('PROPFIND', TEST_DIR, null, { Depth: '1' });
    report('PROPFIND 能看到刚上传的文件', r.status === 207 && r.text.includes('hello.txt'), 'HTTP ' + r.status);
  } catch (e) {
    report('PROPFIND 能看到刚上传的文件', false, e.message);
  }
  try {
    const del = await req('DELETE', TEST_FILE, null);
    report('DELETE 清理测试文件', del.status === 204 || del.status === 200 || del.status === 404, 'HTTP ' + del.status);
  } catch (e) {
    report('DELETE 清理测试文件', false, e.message);
  }
  try {
    const del = await req('DELETE', TEST_DIR, null);
    report('DELETE 清理测试目录', del.status === 204 || del.status === 200 || del.status === 404, 'HTTP ' + del.status);
  } catch (e) {
    report('DELETE 清理测试目录', false, e.message);
  }
}

console.log('');
console.log(ok ? '==== 全部通过: 既能读也能写, App 的导入歌曲功能可以用 ====' : '==== 有失败项: App 里导入歌曲会失败, 看上面标 FAIL 的那一步 ====');
process.exit(ok ? 0 : 1);
