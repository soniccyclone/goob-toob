// goob-toob desktop: owns the Clojure server (bundled JRE + uberjar) and a
// cloudflared quick tunnel, then shows the admin console from the loopback
// admin port. The public port is what the tunnel exposes; it has no admin
// surface, so there is nothing to password-protect.
const { app, BrowserWindow, dialog, ipcMain, shell } = require('electron');const { spawn, spawnSync } = require('child_process');
const http = require('http');
const net = require('net');
const path = require('path');
const fs = require('fs');

const DEV = !!process.env.GOOB_DEV;
// The admin console is a static page (video plays in the system browser via
// the tunnel), so GPU compositing buys nothing here — and on flaky Windows
// GPU drivers it is the classic "blank gray window" failure. Software paint
// is always correct.
app.disableHardwareAcceleration();
const WIN = process.platform === 'win32';
const EXE = WIN ? '.exe' : '';
const ROOT = path.join(__dirname, '..'); // repo root in dev

const bin = DEV
  ? {
      java: process.env.GOOB_JAVA || path.join(ROOT, 'vendor', process.platform === 'win32' ? 'windows' : 'linux', 'jre', 'bin', `java${EXE}`),
      jar: process.env.GOOB_JAR || path.join(ROOT, 'target', 'goob-toob.jar'),
      ffmpeg: process.env.GOOB_FFMPEG || path.join(ROOT, 'vendor', process.platform === 'win32' ? 'windows' : 'linux', `ffmpeg${EXE}`),
      cloudflared: process.env.GOOB_CLOUDFLARED || path.join(ROOT, 'vendor', process.platform === 'win32' ? 'windows' : 'linux', `cloudflared${EXE}`),
    }
  : {
      java: path.join(process.resourcesPath, 'jre', 'bin', `java${EXE}`),
      jar: path.join(process.resourcesPath, 'app', 'goob-toob.jar'),
      ffmpeg: path.join(process.resourcesPath, 'ffmpeg', `ffmpeg${EXE}`),
      cloudflared: path.join(process.resourcesPath, 'cloudflared', `cloudflared${EXE}`),
    };

let server = null;
let tunnel = null;
let tunnelUrl = null;
let win = null;
let ports = {};
app.isQuitting = false;

// ---- tiny JSON config in userData -------------------------------------------
const cfgFile = () => path.join(app.getPath('userData'), 'goob-config.json');
const loadCfg = () => { try { return JSON.parse(fs.readFileSync(cfgFile(), 'utf8')); } catch { return {}; } };
const saveCfg = (c) => fs.writeFileSync(cfgFile(), JSON.stringify(c, null, 2));

const freePort = () => new Promise((res, rej) => {
  const s = net.createServer();
  s.on('error', rej);
  s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => res(p)); });
});

// ---- server child ------------------------------------------------------------
const healthOk = () => new Promise((res) => {
  const req = http.get(`http://127.0.0.1:${ports.admin}/api/admin/me`,
    (r) => { r.resume(); res(r.statusCode === 200); });
  req.on('error', () => res(false));
});

async function waitForHealth(timeoutMs = 60000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    if (await healthOk()) return;
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error('server did not come up in time');
}

async function startServer() {
  const cfg = loadCfg();
  ports = { public: await freePort(), admin: await freePort() };
  const log = fs.openSync(path.join(app.getPath('userData'), 'server.log'), 'a');
  server = spawn(bin.java, ['-jar', bin.jar], {
    env: { ...process.env, STORAGE_DIR: cfg.storageDir,
           PORT: String(ports.public), GOOB_ADMIN_PORT: String(ports.admin),
           FFMPEG_PATH: bin.ffmpeg, GOOB_VERSION: app.getVersion() },
    stdio: ['ignore', log, log],
  });
  server.on('exit', (code) => {
    if (!app.isQuitting) {
      dialog.showErrorBox('goob-toob',
        `The media server exited (code ${code}). Details: ${path.join(app.getPath('userData'), 'server.log')}`);
      app.quit();
    }
  });
  await waitForHealth();
}

// Stop the server so that nothing it started outlives it (#49). On Linux
// kill() is SIGTERM: the JVM runs its shutdown hooks and transcode/stop! ends
// any ffmpeg mid-encode. On Windows kill() is TerminateProcess, no hook runs,
// and ffmpeg.exe is not in the job object that takes java.exe down with us
// (libuv adds children with SILENT_BREAKAWAY_OK, so grandchildren escape it):
// taskkill /T walks the tree while java.exe is still alive to be its root.
function stopServer() {
  if (!server) return;
  const s = server;
  server = null;
  s.removeAllListeners('exit');
  try {
    if (WIN) {
      const r = spawnSync('taskkill', ['/PID', String(s.pid), '/T', '/F'],
                          { stdio: 'ignore', timeout: 10000, windowsHide: true });
      if (r.error || r.status !== 0) s.kill();
    } else {
      s.kill();
    }
  } catch {}
}

async function restartServer() {
  stopServer();
  await startServer();
}

// ---- cloudflared quick tunnel -------------------------------------------------
const pushTunnel = () => { if (win && !win.isDestroyed()) win.webContents.send('tunnel-url', tunnelUrl); };

function startTunnel() {
  tunnel = spawn(bin.cloudflared,
    ['tunnel', '--url', `http://127.0.0.1:${ports.public}`, '--no-autoupdate'],
    { stdio: ['ignore', 'ignore', 'pipe'] });
  let buf = '';
  tunnel.stderr.on('data', (d) => {
    buf += d.toString();
    const m = buf.match(/https:\/\/[a-z0-9-]+\.trycloudflare\.com/);
    if (m && !tunnelUrl) { tunnelUrl = m[0]; console.log('tunnel:', tunnelUrl); pushTunnel(); }
  });
  tunnel.on('exit', () => {
    if (app.isQuitting) return;
    tunnelUrl = null; pushTunnel();
    setTimeout(startTunnel, 3000); // quick tunnels are ephemeral; re-dial
  });
}

// ---- window + IPC --------------------------------------------------------------
function createWindow() {
  win = new BrowserWindow({
    width: 1280, height: 900,
    title: 'goob-toob',
    show: false,   // avoid the gray/white flash: show only once painted
    webPreferences: { preload: path.join(__dirname, 'preload.js') },
  });
  win.once('ready-to-show', () => win.show());
  win.webContents.on('did-fail-load', (e, code, desc, url, mainFrame) => {
    // transient loopback hiccup (or proxy weirdness): retry shortly
    if (mainFrame && !app.isQuitting) setTimeout(() => win && win.loadURL(`http://127.0.0.1:${ports.admin}/admin`), 1000);
  });
  win.webContents.on('render-process-gone', () => {
    if (win && !win.isDestroyed() && !app.isQuitting) win.reload();
  });
  win.setMenuBarVisibility(false);
  win.loadURL(`http://127.0.0.1:${ports.admin}/admin`);
  // target=_blank room links: open the watch page in its own window (host
  // controls come free from the admin port)
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith(`http://127.0.0.1:${ports.admin}`)) return { action: 'allow' };
    if (/^https?:/.test(url)) { shell.openExternal(url); return { action: 'deny' }; }
    return { action: 'deny' };
  });
  win.on('closed', () => { win = null; });
}

async function pickDir() {
  const r = await dialog.showOpenDialog(win, {
    title: 'Where should goob-toob keep your videos?',
    buttonLabel: 'Use this folder',
    properties: ['openDirectory', 'createDirectory'],
  });
  return r.canceled ? null : r.filePaths[0];
}

ipcMain.handle('goob:get-state', () => ({
  tunnelUrl, storageDir: loadCfg().storageDir,
  publicPort: ports.public, adminPort: ports.admin,
}));

ipcMain.handle('goob:pick-storage', async () => {
  const dir = await pickDir();
  if (!dir) return null;
  const cfg = loadCfg(); cfg.storageDir = dir; saveCfg(cfg);
  await restartServer();
  if (win && !win.isDestroyed()) win.reload();
  return dir;
});

// ---- lifecycle -----------------------------------------------------------------
// Devtools escape hatch for remote debugging (e.g. "the video just spins"):
// F12 or Ctrl/Cmd+Shift+I toggles devtools on any window; GOOB_DEVTOOLS=1
// auto-opens them on load.
app.on('web-contents-created', (e, wc) => {
  if (process.env.GOOB_DEVTOOLS) wc.once('did-finish-load', () => wc.openDevTools({ mode: 'detach' }));
  wc.on('before-input-event', (ev, input) => {
    if (input.type === 'keyDown' &&
        (input.key === 'F12' || (input.control && input.shift && input.key.toLowerCase() === 'i')))
      wc.toggleDevTools();
  });
});

// #83: a reload (or a fresh window) has no user activation, so Chromium's
// default policy parks the player until a click; the desktop shell is our
// own browser, so let it start with sound on its own, like the "lenient"
// e2e project. Must be set before the app is ready.
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => { if (win) { win.show(); win.focus(); } });

  app.whenReady().then(async () => {
    try {
      let cfg = loadCfg();
      if (!cfg.storageDir && process.env.GOOB_STORAGE) { cfg.storageDir = process.env.GOOB_STORAGE; saveCfg(cfg); }
      if (!cfg.storageDir) {
        const dir = await pickDir();
        if (!dir) { app.quit(); return; }
        cfg.storageDir = dir; saveCfg(cfg);
      }
      await startServer();
      startTunnel();
      createWindow();
    } catch (e) {
      dialog.showErrorBox('goob-toob', String(e && e.message || e));
      app.quit();
    }
  });
}

app.on('window-all-closed', () => app.quit());

app.on('before-quit', () => {
  app.isQuitting = true;
  try { if (tunnel) tunnel.kill(); } catch {}
  stopServer();
});
