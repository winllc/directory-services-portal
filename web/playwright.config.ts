import { defineConfig, devices } from '@playwright/test';

/**
 * Accessibility checks (axe-core) of the main pages, against the built client (vite preview) and
 * the API with its demo directory and a test license (backend A11yDemoServer). CI starts both
 * servers itself; locally, running ones are reused.
 */
const ci = !!process.env.CI;

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  fullyParallel: false,
  workers: 1,
  forbidOnly: ci,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  use: {
    baseURL: 'http://localhost:8080',
    trace: 'retain-on-failure',
  },
  // The client follows the system's colour scheme: both are checked.
  projects: [
    { name: 'light', use: { ...devices['Desktop Chrome'], colorScheme: 'light' } },
    { name: 'dark', use: { ...devices['Desktop Chrome'], colorScheme: 'dark' } },
  ],
  webServer: [
    {
      command: 'cd ../backend && ./mvnw -B -q spring-boot:test-run -Dspring-boot.run.main-class=com.winllc.dsp.A11yDemoServer',
      url: 'http://localhost:3001/api/health',
      timeout: 300_000,
      reuseExistingServer: !ci,
      stdout: 'ignore',
      stderr: 'pipe',
    },
    {
      command: 'npx vite preview --port 8080 --strictPort',
      url: 'http://localhost:8080/',
      timeout: 60_000,
      reuseExistingServer: !ci,
    },
  ],
});
