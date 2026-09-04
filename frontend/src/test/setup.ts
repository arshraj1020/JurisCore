import '@testing-library/jest-dom/vitest';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { cleanup } from '@testing-library/react';
import { server } from './server';
import { clearTokens } from '@/lib/auth/tokenStorage';

/**
 * `onUnhandledRequest: 'error'` on purpose.
 *
 * A test that quietly hits a URL nobody wrote a handler for is a test that proves
 * nothing: the component fails, the assertion passes for the wrong reason, and the API
 * contract drifts unnoticed. Failing loudly on an unmocked request is what keeps these
 * tests tied to the real endpoint shapes.
 */
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));

afterEach(() => {
  server.resetHandlers();
  cleanup();
  clearTokens();
  window.localStorage.clear();
});

afterAll(() => server.close());

// jsdom implements neither of these, and the modal and the toast both need them.
if (!HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.open = true;
  };
}
if (!HTMLDialogElement.prototype.close) {
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.open = false;
  };
}
