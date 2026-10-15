// What-if：在後端前面加一層會 gzip 的反向代理（模擬 Nginx gzip on / Spring server.compression），驗證壓縮的實際效益
import http from 'node:http';
import zlib from 'node:zlib';

const TARGET = { host: 'localhost', port: 3000 };
const COMPRESSIBLE = /^(text\/|application\/(json|javascript|xml)|image\/svg)/;
const cache = new Map();

http.createServer((req, res) => {
  const headers = { ...req.headers, host: 'localhost:3000' };
  const wantsGzip = /\bgzip\b/.test(req.headers['accept-encoding'] || '');
  delete headers['accept-encoding'];
  const up = http.request({ ...TARGET, method: req.method, path: req.url, headers }, (ur) => {
    const type = ur.headers['content-type'] || '';
    const h = { ...ur.headers };
    if (wantsGzip && ur.statusCode === 200 && COMPRESSIBLE.test(type)) {
      const chunks = [];
      ur.on('data', (c) => chunks.push(c));
      ur.on('end', () => {
        const body = Buffer.concat(chunks);
        const key = req.url + '|' + (ur.headers['last-modified'] || '') + '|' + body.length;
        let z = cache.get(key);
        if (!z) { z = zlib.gzipSync(body, { level: 6 }); if (!/\/api\//.test(req.url)) cache.set(key, z); }
        delete h['content-length']; delete h['transfer-encoding'];
        h['content-encoding'] = 'gzip'; h['content-length'] = z.length; h['vary'] = 'Accept-Encoding';
        res.writeHead(200, h); res.end(z);
      });
    } else { res.writeHead(ur.statusCode, h); ur.pipe(res); }
  });
  up.on('error', () => { res.writeHead(502); res.end(); });
  req.pipe(up);
}).listen(3100, () => console.log('gzip proxy on :3100'));
