// Steel Signage server — zero dependencies (Node.js 18+)
// Admin panel + REST API + media hosting for Android TV / web players.
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = parseInt(process.env.PORT || '8080', 10);
const ADMIN_USER = process.env.ADMIN_USER || 'admin';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'admin';
const DATA_DIR = path.resolve(process.env.DATA_DIR || path.join(__dirname, 'data'));
const MEDIA_DIR = path.join(DATA_DIR, 'media');
const DB_FILE = path.join(DATA_DIR, 'db.json');
const PUBLIC_DIR = path.join(__dirname, 'public');
const MAX_UPLOAD = parseInt(process.env.MAX_UPLOAD_MB || '2048', 10) * 1024 * 1024;

fs.mkdirSync(MEDIA_DIR, { recursive: true });

// ---------- tiny JSON database ----------
let db = { media: [], playlists: [], screens: [] };
if (fs.existsSync(DB_FILE)) {
  try { db = { ...db, ...JSON.parse(fs.readFileSync(DB_FILE, 'utf8')) }; }
  catch (e) { console.error('Could not read db.json, starting empty:', e.message); }
}
function save() {
  const tmp = DB_FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(db, null, 2));
  fs.renameSync(tmp, DB_FILE);
}
const id = () => crypto.randomBytes(8).toString('hex');

const TYPES = {
  '.jpg': ['image', 'image/jpeg'], '.jpeg': ['image', 'image/jpeg'], '.png': ['image', 'image/png'],
  '.gif': ['image', 'image/gif'], '.bmp': ['image', 'image/bmp'], '.webp': ['image', 'image/webp'],
  '.mp4': ['video', 'video/mp4'], '.m4v': ['video', 'video/mp4'], '.webm': ['video', 'video/webm'],
  '.mkv': ['video', 'video/x-matroska'], '.mov': ['video', 'video/quicktime'],
};
const STATIC_TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png' };

// ---------- helpers ----------
function send(res, status, body, headers = {}) {
  const isObj = typeof body === 'object' && !Buffer.isBuffer(body);
  res.writeHead(status, { 'Content-Type': isObj ? 'application/json' : 'text/plain', 'Cache-Control': 'no-store', ...headers });
  res.end(isObj ? JSON.stringify(body) : body);
}
function readJson(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', c => { data += c; if (data.length > 1e6) req.destroy(); });
    req.on('end', () => { try { resolve(data ? JSON.parse(data) : {}); } catch (e) { reject(new Error('Invalid JSON')); } });
    req.on('error', reject);
  });
}
function isAdmin(req) {
  const h = req.headers.authorization || '';
  if (!h.startsWith('Basic ')) return false;
  const [u, p] = Buffer.from(h.slice(6), 'base64').toString().split(':');
  const a = Buffer.from(`${u}:${p}`), b = Buffer.from(`${ADMIN_USER}:${ADMIN_PASSWORD}`);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}
function newPairCode() {
  let code;
  do { code = String(crypto.randomInt(100000, 999999)); } while (db.screens.some(s => s.pairCode === code));
  return code;
}
function serveFile(req, res, file, type, cache = 'no-cache') {
  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) return send(res, 404, 'Not found');
    const range = req.headers.range;
    const base = { 'Content-Type': type, 'Accept-Ranges': 'bytes', 'Cache-Control': cache };
    if (range) {
      const m = /bytes=(\d*)-(\d*)/.exec(range);
      let start = m && m[1] ? parseInt(m[1], 10) : 0;
      let end = m && m[2] ? parseInt(m[2], 10) : st.size - 1;
      if (m && !m[1] && m[2]) { start = st.size - parseInt(m[2], 10); end = st.size - 1; }
      if (start >= st.size || end >= st.size || start > end) {
        res.writeHead(416, { 'Content-Range': `bytes */${st.size}` }); return res.end();
      }
      res.writeHead(206, { ...base, 'Content-Range': `bytes ${start}-${end}/${st.size}`, 'Content-Length': end - start + 1 });
      if (req.method === 'HEAD') return res.end();
      fs.createReadStream(file, { start, end }).pipe(res);
    } else {
      res.writeHead(200, { ...base, 'Content-Length': st.size });
      if (req.method === 'HEAD') return res.end();
      fs.createReadStream(file).pipe(res);
    }
  });
}

// What a screen should play right now
function screenContent(screen) {
  const pl = db.playlists.find(p => p.id === screen.playlistId);
  const items = (pl ? pl.items : []).map(it => {
    const m = db.media.find(x => x.id === it.mediaId);
    if (!m) return null;
    return { id: m.id, name: m.name, type: m.type, url: `/media/${m.file}`, file: m.file, size: m.size,
             duration: m.type === 'image' ? (it.duration || pl.defaultDuration || 10) : 0 };
  }).filter(Boolean);
  const version = crypto.createHash('sha1').update(JSON.stringify([items, screen.orientation])).digest('hex').slice(0, 12);
  return { paired: true, screenId: screen.id, screenName: screen.name, orientation: screen.orientation || 'landscape',
           playlistName: pl ? pl.name : null, version, items };
}

// ---------- routes ----------
async function handle(req, res) {
  const url = new URL(req.url, 'http://x');
  const p = url.pathname;
  const M = req.method;

  // CORS for players (web player on other origins)
  if (p.startsWith('/api/player') || p.startsWith('/media/')) {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Range');
    if (M === 'OPTIONS') return send(res, 204, '');
  }

  // ---- media files (public by unguessable name) ----
  if (p.startsWith('/media/') && (M === 'GET' || M === 'HEAD')) {
    const name = path.basename(decodeURIComponent(p.slice(7)));
    const ext = path.extname(name).toLowerCase();
    return serveFile(req, res, path.join(MEDIA_DIR, name), (TYPES[ext] || [, 'application/octet-stream'])[1], 'public, max-age=31536000, immutable');
  }

  // ---- player API (no admin auth; device id is the secret) ----
  if (p === '/api/player/register' && M === 'POST') {
    const body = await readJson(req);
    const deviceId = String(body.deviceId || '').slice(0, 64);
    if (!/^[A-Za-z0-9-]{8,64}$/.test(deviceId)) return send(res, 400, { error: 'deviceId required' });
    let s = db.screens.find(x => x.deviceId === deviceId);
    if (!s) {
      s = { id: id(), deviceId, name: '', pairCode: newPairCode(), paired: false, playlistId: null,
            orientation: 'landscape', createdAt: Date.now(), lastSeen: Date.now(), info: body.info || {} };
      db.screens.push(s); save();
    }
    s.lastSeen = Date.now(); s.info = body.info || s.info;
    return send(res, 200, s.paired ? screenContent(s) : { paired: false, pairCode: s.pairCode });
  }
  let m = p.match(/^\/api\/player\/([A-Za-z0-9-]{8,64})\/content$/);
  if (m && M === 'GET') {
    const s = db.screens.find(x => x.deviceId === m[1]);
    if (!s) return send(res, 404, { error: 'unknown device', reRegister: true });
    s.lastSeen = Date.now();
    if (url.searchParams.get('playing')) s.nowPlaying = url.searchParams.get('playing').slice(0, 200);
    return send(res, 200, s.paired ? screenContent(s) : { paired: false, pairCode: s.pairCode });
  }

  // ---- static: player page is public, admin page requires auth ----
  if (M === 'GET' && (p === '/player' || p === '/player.html')) {
    return serveFile(req, res, path.join(PUBLIC_DIR, 'player.html'), STATIC_TYPES['.html']);
  }

  // ---- everything below is admin ----
  if (!isAdmin(req)) {
    return send(res, 401, 'Authentication required', { 'WWW-Authenticate': 'Basic realm="Steel Signage Admin"' });
  }

  if (M === 'GET' && (p === '/' || p === '/index.html')) return serveFile(req, res, path.join(PUBLIC_DIR, 'index.html'), STATIC_TYPES['.html']);

  // Media
  if (p === '/api/admin/media' && M === 'GET') return send(res, 200, db.media);
  if (p === '/api/admin/media' && M === 'POST') {
    const original = decodeURIComponent(req.headers['x-filename'] || '');
    const ext = path.extname(original).toLowerCase();
    if (!TYPES[ext]) return send(res, 400, { error: `Unsupported file type "${ext}". Use images (jpg, png, gif, bmp, webp) or videos (mp4, webm, mkv, mov).` });
    const len = parseInt(req.headers['content-length'] || '0', 10);
    if (len > MAX_UPLOAD) return send(res, 413, { error: 'File too large' });
    const mid = id();
    const file = `${mid}${ext}`;
    const dest = path.join(MEDIA_DIR, file);
    const out = fs.createWriteStream(dest);
    let size = 0, aborted = false;
    req.on('data', c => { size += c.length; if (size > MAX_UPLOAD && !aborted) { aborted = true; req.destroy(); out.destroy(); fs.rm(dest, () => {}); } });
    req.pipe(out);
    out.on('finish', () => {
      if (aborted) return;
      const item = { id: mid, name: path.basename(original), file, type: TYPES[ext][0], mime: TYPES[ext][1], size, createdAt: Date.now() };
      db.media.push(item); save();
      send(res, 201, item);
    });
    out.on('error', () => send(res, 500, { error: 'Write failed' }));
    return;
  }
  m = p.match(/^\/api\/admin\/media\/(\w+)$/);
  if (m && M === 'PUT') {
    const item = db.media.find(x => x.id === m[1]); if (!item) return send(res, 404, { error: 'not found' });
    const b = await readJson(req); if (b.name) item.name = String(b.name).slice(0, 200); save();
    return send(res, 200, item);
  }
  if (m && M === 'DELETE') {
    const item = db.media.find(x => x.id === m[1]); if (!item) return send(res, 404, { error: 'not found' });
    db.media = db.media.filter(x => x.id !== item.id);
    db.playlists.forEach(pl => { pl.items = pl.items.filter(it => it.mediaId !== item.id); });
    fs.rm(path.join(MEDIA_DIR, item.file), () => {});
    save(); return send(res, 200, { ok: true });
  }

  // Playlists
  if (p === '/api/admin/playlists' && M === 'GET') return send(res, 200, db.playlists);
  if (p === '/api/admin/playlists' && M === 'POST') {
    const b = await readJson(req);
    const pl = { id: id(), name: String(b.name || 'New playlist').slice(0, 100), defaultDuration: 10, items: [], createdAt: Date.now() };
    db.playlists.push(pl); save(); return send(res, 201, pl);
  }
  m = p.match(/^\/api\/admin\/playlists\/(\w+)$/);
  if (m && M === 'PUT') {
    const pl = db.playlists.find(x => x.id === m[1]); if (!pl) return send(res, 404, { error: 'not found' });
    const b = await readJson(req);
    if (b.name !== undefined) pl.name = String(b.name).slice(0, 100);
    if (b.defaultDuration !== undefined) pl.defaultDuration = Math.max(1, Math.min(3600, parseInt(b.defaultDuration, 10) || 10));
    if (Array.isArray(b.items)) {
      pl.items = b.items.filter(it => db.media.some(x => x.id === it.mediaId))
        .map(it => ({ mediaId: it.mediaId, duration: it.duration ? Math.max(1, Math.min(3600, parseInt(it.duration, 10))) : null }));
    }
    save(); return send(res, 200, pl);
  }
  if (m && M === 'DELETE') {
    db.playlists = db.playlists.filter(x => x.id !== m[1]);
    db.screens.forEach(s => { if (s.playlistId === m[1]) s.playlistId = null; });
    save(); return send(res, 200, { ok: true });
  }

  // Screens
  if (p === '/api/admin/screens' && M === 'GET') {
    const now = Date.now();
    return send(res, 200, db.screens.filter(s => s.paired).map(s => ({
      id: s.id, name: s.name, playlistId: s.playlistId, orientation: s.orientation, lastSeen: s.lastSeen,
      online: now - s.lastSeen < 60000, nowPlaying: s.nowPlaying || null, info: s.info || {} })));
  }
  if (p === '/api/admin/screens/pair' && M === 'POST') {
    const b = await readJson(req);
    const s = db.screens.find(x => !x.paired && x.pairCode === String(b.code || '').trim());
    if (!s) return send(res, 404, { error: 'No screen is showing that code. Check the code on the TV.' });
    s.paired = true; s.name = String(b.name || 'Screen').slice(0, 100);
    s.playlistId = db.playlists.some(x => x.id === b.playlistId) ? b.playlistId : null;
    save(); return send(res, 200, { ok: true, id: s.id });
  }
  m = p.match(/^\/api\/admin\/screens\/(\w+)$/);
  if (m && M === 'PUT') {
    const s = db.screens.find(x => x.id === m[1]); if (!s) return send(res, 404, { error: 'not found' });
    const b = await readJson(req);
    if (b.name !== undefined) s.name = String(b.name).slice(0, 100);
    if (b.playlistId !== undefined) s.playlistId = db.playlists.some(x => x.id === b.playlistId) ? b.playlistId : null;
    if (b.orientation !== undefined) s.orientation = ['landscape', 'portrait', 'portrait-flipped', 'landscape-flipped'].includes(b.orientation) ? b.orientation : 'landscape';
    save(); return send(res, 200, { ok: true });
  }
  if (m && M === 'DELETE') {
    // Unpair: screen goes back to showing a new pairing code
    const s = db.screens.find(x => x.id === m[1]); if (!s) return send(res, 404, { error: 'not found' });
    s.paired = false; s.playlistId = null; s.name = ''; s.pairCode = newPairCode();
    save(); return send(res, 200, { ok: true });
  }

  send(res, 404, { error: 'Not found' });
}

http.createServer((req, res) => {
  handle(req, res).catch(err => { console.error(err); if (!res.headersSent) send(res, 500, { error: err.message }); });
}).listen(PORT, () => {
  console.log(`Steel Signage running on http://localhost:${PORT}`);
  console.log(`  Admin panel : http://localhost:${PORT}/   (user: ${ADMIN_USER})`);
  console.log(`  Web player  : http://localhost:${PORT}/player`);
  if (ADMIN_PASSWORD === 'admin') console.log('  WARNING: set ADMIN_PASSWORD before exposing this server to the internet.');
});
