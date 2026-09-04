/**
 * Screenshots the built application at four widths against fixture data.
 *
 * Not part of the repository's dependencies or its `verify` script: this is a local
 * check that the responsive layouts actually hold, run with a --no-save Playwright.
 */
import { chromium } from 'playwright';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const dist = path.join(root, 'dist');
const out = path.join(root, '.shots');
fs.mkdirSync(out, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.json': 'application/json',
};

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  let file = path.join(dist, url.pathname);
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(dist, 'index.html');
  res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] ?? 'application/octet-stream' });
  res.end(fs.readFileSync(file));
});
await new Promise((resolve) => server.listen(4321, resolve));

const { routes, envelope, page } = await import('./fixtures.mjs');

// ------------------------------------------------------------------ capture

const targets = [
  ['dashboard', '/'],
  ['clients', '/clients'],
  ['client-detail', '/clients/c1'],
  ['cases', '/cases'],
  ['case-overview', '/cases/k1'],
  ['case-timeline', '/cases/k1?tab=timeline'],
  ['case-hearings', '/cases/k1?tab=hearings'],
  ['case-tasks', '/cases/k1?tab=tasks'],
  ['case-documents', '/cases/k1?tab=documents'],
  ['case-invoices', '/cases/k1?tab=invoices'],
  ['diary', '/hearings'],
  ['courts', '/courts'],
  ['invoices', '/invoices'],
  ['invoice-detail', '/invoices/i1'],
  ['invoice-new', '/invoices/new'],
  ['billing-settings', '/billing/settings'],
  ['notifications', '/notifications'],
  ['people', '/members'],
  ['audit', '/audit'],
  ['profile', '/profile'],
];

const widths = Number(process.argv[2] ?? 0)
  ? [[`w${process.argv[2]}`, Number(process.argv[2]), 900]]
  : [['mobile', 390, 844], ['tablet', 834, 1112], ['laptop', 1280, 800], ['desktop', 1680, 1050]];

const only = process.argv[3];

// `CHROMIUM_PATH` lets this run against a Chromium that is already on the machine;
// without it, Playwright uses whichever browser `npx playwright install` put down.
const browser = await chromium.launch({
  ...(process.env['CHROMIUM_PATH'] ? { executablePath: process.env['CHROMIUM_PATH'] } : {}),
  args: ['--no-sandbox'],
});
for (const [label, width, height] of widths) {
  const context = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 1 });
  await context.addInitScript(() => {
    window.localStorage.setItem('juriscore.refreshToken', 'seed');
  });
  await context.route('**/api/**', async (route) => {
    const url = route.request().url();
    for (const [pattern, body] of routes) {
      if (pattern.test(url)) {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body(url)) });
        return;
      }
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(envelope(page([]))) });
  });

  const p = await context.newPage();
  p.on('console', (m) => { if (m.type() === 'error') console.log(`[${label}] console: ${m.text()}`); });
  p.on('pageerror', (e) => console.log(`[${label}] pageerror: ${e.message}`));

  for (const [name, route] of targets) {
    if (only && name !== only) continue;
    await p.goto(`http://localhost:4321${route}`, { waitUntil: 'networkidle' });
    await p.waitForTimeout(250);
    await p.screenshot({ path: path.join(out, `${name}-${label}.png`), fullPage: true });
  }

  // The login screen has no session.
  const anon = await context.newPage();
  await anon.addInitScript(() => window.localStorage.clear());
  for (const [name, route] of [['login', '/login'], ['register', '/register']]) {
    if (only && name !== only) continue;
    await anon.goto(`http://localhost:4321${route}`, { waitUntil: 'networkidle' });
    await anon.evaluate(() => window.localStorage.clear());
    await anon.reload({ waitUntil: 'networkidle' });
    await anon.screenshot({ path: path.join(out, `${name}-${label}.png`), fullPage: true });
  }
  await context.close();
}
await browser.close();
server.close();
console.log('done');
