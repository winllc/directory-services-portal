import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The API runs as a separate service; the dev server and `vite preview` forward /api to it.
const apiProxy = {
  '/api': { target: process.env.API_URL ?? 'http://localhost:3001', changeOrigin: false },
};

export default defineConfig({
  plugins: [react()],
  server: { port: 5173, proxy: apiProxy },
  preview: { port: 8080, proxy: apiProxy },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.tsx', 'src/**/*.test.ts'],
  },
});
