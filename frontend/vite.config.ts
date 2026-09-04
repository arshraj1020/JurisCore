import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig({
  plugins: [react()],
  resolve: { alias: { '@': path.resolve(__dirname, './src') } },
  server: {
    // 3000 on purpose: the backend's default CORS allow-list
    // (juriscore.security.cors.allowed-origins) is http://localhost:3000, so the dev
    // server works against a stock backend without widening CORS for convenience.
    port: 3000,
    strictPort: true,
  },
  preview: { port: 3000, strictPort: true },
});
