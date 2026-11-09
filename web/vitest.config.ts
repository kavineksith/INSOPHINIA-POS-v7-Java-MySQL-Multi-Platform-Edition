import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Vitest configuration — runs under jsdom so React Testing Library can mount
// components without a real browser. Config is separate from vite.config.ts
// so the production build stays clean.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    exclude: ['node_modules', 'dist', 'cypress'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      exclude: ['src/test/**', 'src/main.tsx', 'src/**/*.d.ts'],
    },
  },
});
