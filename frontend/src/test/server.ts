import { setupServer } from 'msw/node';

/**
 * One server, no default handlers.
 *
 * Each test declares exactly the endpoints it exercises with `server.use(...)`, so a
 * request the test did not intend fails rather than being silently satisfied by a
 * catch-all fixture.
 */
export const server = setupServer();
