import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

/**
 * Where `/api` goes while you are developing.
 *
 * The application calls the API with a *relative* path (`/api/v1/...`) so that a
 * deployment can serve the app and the API from one origin behind a reverse proxy. In
 * development there is no such proxy: the page is served by Vite on port 3000 and the
 * Spring Boot application listens on 8080, so without the rule below the browser sends
 * `POST /api/v1/auth/register` to *Vite*, which knows nothing about it and answers 404
 * with an empty body. That 404 is indistinguishable from a missing record at the fetch
 * layer, and the user sees "That record does not exist." while the backend sits idle.
 *
 * `VITE_API_PROXY_TARGET` overrides the target for a backend on another port or host.
 * This affects the dev and preview servers only; it is not part of the built bundle.
 */
const API_TARGET = process.env['VITE_API_PROXY_TARGET'] ?? 'http://localhost:8080';

/** Everything the API owns. The app's own routes never start with these. */
const PROXIED = ['/api', '/actuator', '/v3/api-docs', '/swagger-ui'];

const proxy = Object.fromEntries(
  PROXIED.map((prefix) => [prefix, { target: API_TARGET, changeOrigin: true }]),
);

export default defineConfig({
  plugins: [react()],
  resolve: { alias: { '@': path.resolve(__dirname, './src') } },
  server: {
    // 3000 on purpose: the backend's default CORS allow-list
    // (juriscore.security.cors.allowed-origins) is http://localhost:3000, so a browser
    // that does reach the backend directly is already allowed to. With the proxy above,
    // requests are same-origin and CORS is not involved at all.
    port: 3000,
    strictPort: true,
    proxy,
  },
  preview: { port: 3000, strictPort: true, proxy },
});
