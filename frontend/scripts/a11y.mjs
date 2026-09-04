/**
 * A structural accessibility sweep over the built pages.
 *
 * Not a substitute for axe or for using the thing with a screen reader — it checks the
 * specific mistakes this codebase could plausibly make: a control with no accessible
 * name, an input with no label, an image with no alt, a heading level skipped, a
 * duplicated id, or text that fails contrast against its own background.
 */
import { chromium } from 'playwright';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const dist = path.join(root, 'dist');

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' };
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  let file = path.join(dist, url.pathname);
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(dist, 'index.html');
  res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] ?? 'application/octet-stream' });
  res.end(fs.readFileSync(file));
});
await new Promise((r) => server.listen(4322, r));

const { routes, envelope, page } = await import('./fixtures.mjs');

const AUDIT = () => {
  const problems = [];
  const visible = (el) => {
    const s = getComputedStyle(el);
    return s.display !== 'none' && s.visibility !== 'hidden' && el.offsetParent !== null;
  };
  const name = (el) => (
    el.getAttribute('aria-label')
    || (el.getAttribute('aria-labelledby')
      && document.getElementById(el.getAttribute('aria-labelledby'))?.textContent)
    || el.textContent
    || el.getAttribute('title')
    || ''
  ).trim();

  for (const el of document.querySelectorAll('button, a[href], [role="button"], [role="tab"]')) {
    if (visible(el) && name(el) === '') {
      problems.push(`unnamed control: <${el.tagName.toLowerCase()} class="${el.className}">`);
    }
  }

  for (const el of document.querySelectorAll('input, select, textarea')) {
    if (el.type === 'hidden' || !visible(el)) continue;
    const labelled = el.labels?.length
      || el.getAttribute('aria-label')
      || el.getAttribute('aria-labelledby');
    if (!labelled) problems.push(`unlabelled field: <${el.tagName.toLowerCase()} id="${el.id}">`);
  }

  for (const img of document.querySelectorAll('img')) {
    if (!img.hasAttribute('alt')) problems.push(`image without alt: ${img.src}`);
  }

  const ids = new Map();
  for (const el of document.querySelectorAll('[id]')) {
    ids.set(el.id, (ids.get(el.id) ?? 0) + 1);
  }
  for (const [id, count] of ids) if (count > 1) problems.push(`duplicate id: ${id} (${count})`);

  const levels = [...document.querySelectorAll('h1, h2, h3, h4')]
    .filter(visible).map((h) => Number(h.tagName[1]));
  if (levels.length && levels[0] !== 1) problems.push(`first heading is h${levels[0]}, not h1`);
  for (let i = 1; i < levels.length; i += 1) {
    if (levels[i] - levels[i - 1] > 1) {
      problems.push(`heading level jumps h${levels[i - 1]} → h${levels[i]}`);
    }
  }
  if (levels.filter((l) => l === 1).length > 1) problems.push('more than one h1');

  // Contrast, on text nodes only, against the nearest painted ancestor background.
  const luminance = (rgb) => {
    const [r, g, b] = rgb.map((v) => {
      const c = v / 255;
      return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
    });
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  };
  const parse = (value) => value.match(/\d+(\.\d+)?/g)?.slice(0, 3).map(Number) ?? null;
  const bgOf = (el) => {
    let node = el;
    while (node && node !== document.documentElement) {
      const s = getComputedStyle(node);
      const rgb = parse(s.backgroundColor);
      const alpha = Number(s.backgroundColor.match(/rgba?\([^)]*,\s*([\d.]+)\)/)?.[1] ?? '1');
      if (rgb && alpha > 0.85) return rgb;
      node = node.parentElement;
    }
    return [255, 255, 255];
  };

  for (const el of document.querySelectorAll('p, span, dt, dd, td, th, li, label, h1, h2, h3, a, button')) {
    if (!visible(el)) continue;
    const text = [...el.childNodes]
      .filter((n) => n.nodeType === 3).map((n) => n.textContent.trim()).join('');
    if (text.length < 2) continue;
    const style = getComputedStyle(el);
    const fg = parse(style.color);
    if (!fg) continue;
    const bg = bgOf(el);
    const l1 = luminance(fg);
    const l2 = luminance(bg);
    const ratio = (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
    const size = parseFloat(style.fontSize);
    const large = size >= 24 || (size >= 18.66 && Number(style.fontWeight) >= 700);
    const required = large ? 3 : 4.5;
    if (ratio < required) {
      problems.push(
        `contrast ${ratio.toFixed(2)}:1 (needs ${required}) — "${text.slice(0, 40)}" `
        + `${style.color} on rgb(${bg.join(',')})`,
      );
    }
  }

  return [...new Set(problems)];
};

const targets = [
  ['/', 'dashboard'], ['/clients', 'clients'], ['/clients/c1', 'client detail'],
  ['/cases', 'cases'], ['/cases/k1', 'case overview'], ['/cases/k1?tab=timeline', 'case timeline'],
  ['/cases/k1?tab=hearings', 'case hearings'], ['/cases/k1?tab=tasks', 'case tasks'],
  ['/cases/k1?tab=deadlines', 'case deadlines'], ['/cases/k1?tab=documents', 'case documents'],
  ['/cases/k1?tab=invoices', 'case invoices'], ['/hearings', 'diary'], ['/courts', 'courts'],
  ['/invoices', 'invoices'], ['/invoices/i1', 'invoice detail'], ['/invoices/new', 'new invoice'],
  ['/billing/settings', 'billing settings'], ['/notifications', 'notifications'],
  ['/members', 'people'], ['/audit', 'audit'], ['/profile', 'profile'],
];

// `CHROMIUM_PATH` lets this run against a Chromium that is already on the machine;
// without it, Playwright uses whichever browser `npx playwright install` put down.
const browser = await chromium.launch({
  ...(process.env['CHROMIUM_PATH'] ? { executablePath: process.env['CHROMIUM_PATH'] } : {}),
  args: ['--no-sandbox'],
});
const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
await context.addInitScript(() => window.localStorage.setItem('juriscore.refreshToken', 'seed'));
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

let total = 0;
const p = await context.newPage();
for (const [route, label] of targets) {
  await p.goto(`http://localhost:4322${route}`, { waitUntil: 'networkidle' });
  await p.waitForTimeout(150);
  const problems = await p.evaluate(AUDIT);
  if (problems.length) {
    total += problems.length;
    console.log(`\n${label} (${route})`);
    for (const problem of problems) console.log(`  · ${problem}`);
  }
}

const anon = await context.newPage();
for (const route of ['/login', '/register']) {
  await anon.goto(`http://localhost:4322${route}`, { waitUntil: 'networkidle' });
  await anon.evaluate(() => window.localStorage.clear());
  await anon.reload({ waitUntil: 'networkidle' });
  const problems = await anon.evaluate(AUDIT);
  if (problems.length) {
    total += problems.length;
    console.log(`\n${route}`);
    for (const problem of problems) console.log(`  · ${problem}`);
  }
}

console.log(total === 0 ? '\nno problems found' : `\n${total} problems`);
await browser.close();
server.close();
