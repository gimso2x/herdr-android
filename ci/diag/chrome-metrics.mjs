// Same terminal metrics as TerminalDiagTest, from the runner's headless Google Chrome, to compare
// Chrome with the Android WebView. Runs the SAME probe/metrics assets as the WebView (narrowed guard:
// interact role kept, attach/resize sent, input/keys/submit/pending-action/secret/take-over frames
// and non-GET requests dropped). Prints METRICS ONLY; takes no screenshots.
//
//   CF_COOKIE_FILE=<file with the CF_Authorization value> node ci/diag/chrome-metrics.mjs
import fs from 'node:fs';
import puppeteer from 'puppeteer-core';

const BASE = 'https://herdr.example.com';
const HOST = 'herdr.example.com';
const probe = fs.readFileSync('app/src/debug/assets/diag_probe.js', 'utf8');
const metricsSource = fs.readFileSync('app/src/debug/assets/diag_metrics.js', 'utf8');
const cookie = fs.readFileSync(process.env.CF_COOKIE_FILE, 'utf8').trim();
if (!cookie) throw new Error('empty cookie file');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (scenario, key, value) => {
  const text = typeof value === 'string' ? value : JSON.stringify(value);
  for (let i = 0; i < text.length; i += 3000) console.log(`${scenario} ${key}${i ? `#${i / 3000}` : ''}=${text.slice(i, i + 3000)}`);
};

const session = await fetch(`${BASE}/api/session`, { headers: { cookie: `CF_Authorization=${cookie}` } });
if (!session.ok) throw new Error(`/api/session answered ${session.status}`);
const snapshot = (await session.json()).snapshot;
const paneId = snapshot.focused_pane_id || snapshot.panes[0].pane_id;
log('setup', 'snapshot', `panes=${snapshot.panes.length} workspaces=${snapshot.workspaces.length}`);

const browser = await puppeteer.launch({
  executablePath: process.env.CHROME_PATH || '/usr/bin/google-chrome',
  headless: true,
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
});
const version = (await browser.version()).replace('HeadlessChrome', 'Chrome');
log('setup', 'chrome', version);
const major = version.split('/')[1];
const profiles = [
  {
    name: 'mobile-webview-ua',
    viewport: { width: 360, height: 740, deviceScaleFactor: 2.625, isMobile: true, hasTouch: true },
    ua: `Mozilla/5.0 (Linux; Android 14; sdk_gphone64_x86_64 Build/UE1A.230829.036; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/${major} Mobile Safari/537.36`,
  },
  { name: 'desktop', viewport: { width: 1280, height: 800 }, ua: null },
];
const encoded = encodeURIComponent(paneId);
const scenarios = [
  { name: 'root', path: '/' },
  { name: 'deeplink-chat', path: `/?machine=local&pane=${encoded}&view=chat` },
  { name: 'direct-terminal', path: `/?machine=local&pane=${encoded}&view=terminal` },
];

for (const profile of profiles) {
  for (const scenario of scenarios) {
    const name = `${profile.name}/${scenario.name}`;
    const context = await browser.createBrowserContext(); // fresh storage per scenario
    const page = await context.newPage();
    try {
      if (profile.ua) await page.setUserAgent(profile.ua);
      await page.setViewport(profile.viewport);
      await page.setCookie({ name: 'CF_Authorization', value: cookie, domain: HOST, path: '/', secure: true, httpOnly: true });
      await page.evaluateOnNewDocument(probe);
      await page.goto(BASE + scenario.path, { waitUntil: 'domcontentloaded', timeout: 60000 });
      const started = Date.now();
      let last = null;
      let tick = -1;
      for (;;) {
        const m = await page.evaluate(`\n${metricsSource}\n`).catch(() => null);
        if (m) last = m;
        const elapsed = Date.now() - started;
        if (m && Math.floor(elapsed / 5000) !== tick) {
          tick = Math.floor(elapsed / 5000);
          log(name, 'timeline', `t=${Math.round(elapsed / 1000)}s xterms=${m.xtermCount} anyRows=${m.anyXtermRows} anyTall=${m.anyXtermTall} transcriptChildren=${m.chat.transcriptChildren} ws=${(m.ws || []).map((s) => JSON.stringify(s.recv)).join('|')}`);
        }
        if ((m && (m.anyXtermRows || m.chat.transcriptChildren > 0)) || elapsed >= 35000) break;
        await sleep(1000);
      }
      if (!last) { log(name, 'metrics', 'unavailable'); continue; }
      for (const key of ['env', 'units', 'fixes', 'chain', 'appHeightVarComputed', 'page', 'layout', 'xtermCount', 'xterms', 'anyXtermTall', 'anyXtermRows', 'chat', 'ws', 'diag']) log(name, key, last[key]);
      log(name, 'verdict', `xterms=${last.xtermCount} anyTall=${last.anyXtermTall} anyRows=${last.anyXtermRows}`);
    } catch (error) {
      log(name, 'error', String(error && error.message).slice(0, 200));
    } finally {
      await context.close();
    }
  }
}
await browser.close();
