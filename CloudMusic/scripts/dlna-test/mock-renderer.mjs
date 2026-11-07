import dgram from 'node:dgram';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const outDir = path.join(here, 'out');
fs.mkdirSync(outDir, { recursive: true });
for (const f of fs.readdirSync(outDir)) {
    if (f.endsWith('.xml')) fs.unlinkSync(path.join(outDir, f));
}
try { fs.unlinkSync(path.join(outDir, 'headers.log')); } catch (e) { /* 第一次跑没有这个文件 */ }

const SSDP_PORT = 1900;
const UDN = 'uuid:mock-tv-0001';

let httpPort = 0;
let soapCount = 0;
const state = { hasUri: false };

const description = () => `<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>测试电视</friendlyName>
    <manufacturer>Mock</manufacturer>
    <modelName>MockTV</modelName>
    <UDN>${UDN}</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <controlURL>/control/avtransport</controlURL>
        <eventSubURL>/event/avtransport</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <controlURL>/control/renderingcontrol</controlURL>
      </service>
    </serviceList>
  </device>
</root>`;

const server = http.createServer((req, res) => {
    const url = req.url || '';

    if (req.method === 'GET' && url === '/description.xml') {
        const body = description();
        res.writeHead(200, {
            'Content-Type': 'text/xml; charset=utf-8',
            'Content-Length': Buffer.byteLength(body),
        });
        res.end(body);
        return;
    }

    // 分块传输，用来测 chunked 解析
    // 注意：只写内容，chunk 的分帧由 Node 自己加
    if (req.method === 'GET' && url === '/chunked') {
        res.writeHead(200, { 'Content-Type': 'text/plain' });
        res.write('Hello');
        res.write(' World');
        res.end();
        return;
    }

    // 故意不关连接：Content-Length 给够，但连接一直挂着
    if (req.method === 'GET' && url === '/keepalive') {
        const body = 'keep-alive-body';
        res.writeHead(200, {
            'Content-Type': 'text/plain',
            'Content-Length': Buffer.byteLength(body),
        });
        res.write(body);
        return;
    }

    if (req.method === 'POST') {
        let body = '';
        req.on('data', (c) => { body += c; });
        req.on('end', () => {
            soapCount += 1;
            fs.writeFileSync(path.join(outDir, `soap-${soapCount}.xml`), body, 'utf8');
            const action = (body.match(/<u:([A-Za-z]+)[\s>]/) || [])[1] || 'unknown';
            const soapAction = req.headers['soapaction'] || '';
            const contentType = req.headers['content-type'] || '';
            fs.appendFileSync(
                path.join(outDir, 'headers.log'),
                `${action}|${soapAction}|${contentType}\n`
            );

            // 真设备比 mock 严格多了，这几条都是踩过的坑：
            const fault = (code, desc) => {
                const env = '<?xml version="1.0" encoding="utf-8"?>' +
                    '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" ' +
                    's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><s:Fault>' +
                    `<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>` +
                    `<detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0">` +
                    `<errorCode>${code}</errorCode><errorDescription>${desc}</errorDescription>` +
                    '</UPnPError></detail></s:Fault></s:Body></s:Envelope>';
                res.writeHead(500, {
                    'Content-Type': 'text/xml; charset="utf-8"',
                    'Content-Length': Buffer.byteLength(env),
                });
                res.end(env);
            };

            // 1. SOAPACTION 头必须有，而且要写成 "服务类型#动作名"
            if (!soapAction.includes('#' + action)) {
                console.log(`MOCK_BAD_SOAPACTION [${soapAction}] action=${action}`);
                fault(401, 'Invalid Action');
                return;
            }
            // 2. Content-Type 必须是 text/xml
            if (!contentType.toLowerCase().includes('text/xml')) {
                console.log(`MOCK_BAD_CONTENTTYPE [${contentType}]`);
                fault(415, 'Unsupported Media Type');
                return;
            }
            // 3. 模拟小爱音箱：对 CurrentURIMetaData 里的 DIDL 挑得厉害，带了就拒
            if (action === 'SetAVTransportURI' && body.includes('<CurrentURIMetaData>') &&
                !body.includes('<CurrentURIMetaData></CurrentURIMetaData>')) {
                console.log('MOCK_STRICT_METADATA_REJECT');
                fault(402, 'Invalid Args');
                return;
            }
            // 4. 没设过 URI 就不许 Play（很多渲染器都这样）
            if (action === 'Play' && !state.hasUri) {
                console.log('MOCK_PLAY_WITHOUT_URI');
                fault(701, 'Transition not available');
                return;
            }
            if (action === 'SetAVTransportURI') state.hasUri = true;

            let inner;
            if (action === 'GetTransportInfo') {
                inner = '<u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">' +
                    '<CurrentTransportState>PLAYING</CurrentTransportState>' +
                    '<CurrentTransportStatus>OK</CurrentTransportStatus>' +
                    '<CurrentSpeed>1</CurrentSpeed></u:GetTransportInfoResponse>';
            } else {
                inner = `<u:${action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/>`;
            }
            const env = '<?xml version="1.0" encoding="utf-8"?>' +
                '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" ' +
                's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">' +
                `<s:Body>${inner}</s:Body></s:Envelope>`;
            res.writeHead(200, {
                'Content-Type': 'text/xml; charset="utf-8"',
                'Content-Length': Buffer.byteLength(env),
            });
            res.end(env);
        });
        return;
    }

    res.writeHead(404, { 'Content-Length': 0 });
    res.end();
});

server.listen(0, '127.0.0.1', () => {
    httpPort = server.address().port;
    console.log(`MOCK_READY http://127.0.0.1:${httpPort}`);
});

const udp = dgram.createSocket({ type: 'udp4', reuseAddr: true });
udp.on('error', (e) => {
    console.log('MOCK_SSDP_ERROR ' + e.message);
});
udp.on('message', (msg, rinfo) => {
    const text = msg.toString('latin1');
    if (!/^M-SEARCH/i.test(text)) return;
    const reply = [
        'HTTP/1.1 200 OK',
        'CACHE-CONTROL: max-age=1800',
        'EXT:',
        `LOCATION: http://127.0.0.1:${httpPort}/description.xml`,
        'SERVER: Mock/1.0 UPnP/1.0',
        'ST: urn:schemas-upnp-org:device:MediaRenderer:1',
        `USN: ${UDN}::urn:schemas-upnp-org:device:MediaRenderer:1`,
        '',
        '',
    ].join('\r\n');
    udp.send(Buffer.from(reply, 'utf8'), rinfo.port, rinfo.address);
    // 真设备一般还会往组播组里再发一份，两份都发更接近真实
    try {
        udp.send(Buffer.from(reply, 'utf8'), SSDP_PORT, '239.255.255.250');
    } catch (e) {
        // 发不出去就算了，单播那份已经给了
    }
    console.log('MOCK_SEARCH_FROM ' + rinfo.address + ':' + rinfo.port);
});
udp.bind(SSDP_PORT, () => {
    try {
        udp.addMembership('239.255.255.250');
    } catch (e) {
        console.log('MOCK_JOIN_FAILED ' + e.message);
    }
    console.log('MOCK_SSDP_READY');
});

process.on('SIGTERM', () => process.exit(0));
