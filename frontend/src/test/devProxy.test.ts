// @vitest-environment node
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import http from 'node:http';
import path from 'node:path';
import type { AddressInfo } from 'node:net';
import type { ViteDevServer } from 'vite';

/**
 * Proves the dev server hands `/api` to the backend instead of answering it itself.
 *
 * This is the regression test for a real failure: with no proxy rule, the browser sent
 * `POST /api/v1/auth/register` to Vite on port 3000, Vite answered **404 with an empty
 * body**, and the fetch layer — which cannot tell that from a genuinely missing record —
 * reported "That record does not exist." on the registration form while Spring Boot sat
 * idle on 8080.
 *
 * It starts the *real* `vite.config.ts` rather than a fixture, with only the proxy target
 * redirected through the environment variable the config documents, so deleting the proxy
 * rule fails this test.
 */

const root = path.resolve(__dirname, '../..');

let backend: http.Server;
let vite: ViteDevServer;
let origin: string;
const received: { method: string; url: string; body: string }[] = [];

beforeAll(async () => {
  // A stand-in for Spring Boot that records what arrives.
  backend = http.createServer((request, response) => {
    let body = '';
    request.on('data', (chunk) => { body += chunk; });
    request.on('end', () => {
      received.push({ method: request.method ?? '', url: request.url ?? '', body });
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ success: true, data: { reached: 'backend' } }));
    });
  });
  await new Promise<void>((resolve) => backend.listen(0, '127.0.0.1', resolve));
  const backendPort = (backend.address() as AddressInfo).port;

  // The one knob the config exposes. Everything else comes from the file itself.
  process.env['VITE_API_PROXY_TARGET'] = `http://127.0.0.1:${backendPort}`;

  const { createServer } = await import('vite');
  vite = await createServer({
    configFile: path.join(root, 'vite.config.ts'),
    root,
    logLevel: 'silent',
    server: { port: 0, strictPort: false, host: '127.0.0.1' },
  });
  await vite.listen();
  const address = vite.httpServer?.address() as AddressInfo;
  origin = `http://127.0.0.1:${address.port}`;
}, 60_000);

afterAll(async () => {
  await vite?.close();
  await new Promise<void>((resolve) => { backend?.close(() => resolve()); });
  delete process.env['VITE_API_PROXY_TARGET'];
});

describe('the dev server', () => {
  it('forwards a registration POST to the backend rather than answering 404', async () => {
    const payload = {
      firmName: 'xyz',
      firstName: 'Arsh',
      lastName: 'Raj',
      email: 'arsh@example.test',
      password: 'correct horse battery staple',
      timezone: 'Asia/Kolkata',
    };

    const response = await fetch(`${origin}/api/v1/auth/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });

    // 404 here is the bug: it means Vite answered instead of forwarding.
    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual({ success: true, data: { reached: 'backend' } });

    const registration = received.find((entry) => entry.url === '/api/v1/auth/register');
    expect(registration).toBeDefined();
    expect(registration?.method).toBe('POST');
    // The body arrives intact — a proxy that drops it would fail validation server-side.
    expect(JSON.parse(registration?.body ?? '{}')).toEqual(payload);
  });

  it('forwards the rest of the API surface too, not just auth', async () => {
    for (const path_ of ['/api/v1/clients', '/api/v1/invoices', '/actuator/health']) {
      const response = await fetch(`${origin}${path_}`);
      expect(response.status, path_).toBe(200);
      expect(received.some((entry) => entry.url === path_), path_).toBe(true);
    }
  });

  it('still serves the application itself for in-app routes', async () => {
    // The SPA fallback must keep working; a proxy that swallowed everything would break
    // deep links like /invoices/<id>.
    const response = await fetch(`${origin}/invoices`);
    expect(response.status).toBe(200);
    expect(response.headers.get('content-type')).toContain('text/html');
    expect(received.some((entry) => entry.url === '/invoices')).toBe(false);
  });
});
