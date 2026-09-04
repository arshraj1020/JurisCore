import { afterAll, afterEach, beforeAll } from 'vitest';

/**
 * Setup for the component tests, which run in jsdom.
 *
 * A handful of files run in a real Node environment instead — `devProxy.test.ts` starts
 * the actual dev server and talks to it over the network. Everything below would be wrong
 * for those: there is no DOM to polyfill, and an MSW interceptor with
 * `onUnhandledRequest: 'error'` would reject the very requests such a test exists to make.
 * So the whole file is a no-op outside a browser-like environment.
 */
const IN_BROWSER_ENV = typeof window !== 'undefined';

if (IN_BROWSER_ENV) {
  // Imported lazily so a Node-environment test never evaluates jsdom-only modules.
  const [{ server }, { cleanup }, { clearTokens }] = await Promise.all([
    import('./server'),
    import('@testing-library/react'),
    import('@/lib/auth/tokenStorage'),
  ]);
  await import('@testing-library/jest-dom/vitest');

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

  // jsdom has no layout, so it implements no scrolling either. The tab strip scrolls its
  // selected tab into view on a narrow screen; here that is a no-op rather than a crash.
  if (!Element.prototype.scrollIntoView) {
    Element.prototype.scrollIntoView = function scrollIntoView() { /* no layout in jsdom */ };
  }

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
}
