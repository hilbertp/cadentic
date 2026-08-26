/**
 * The endpoint end to end (Epic 2 story 0), over a real socket with a fake provider.
 */

import assert from 'node:assert/strict';
import type { Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { after, test } from 'node:test';

import { loadConfig } from '../src/config.js';
import { MessagesApiProvider } from '../src/provider/messagesApi.js';
import type { Provider } from '../src/provider/types.js';
import { createEngineServer } from '../src/server.js';
import { EngineError } from '../src/errors.js';
import { FakeProvider, payload, planDraft } from './fixtures.js';

const SECRET = 'test-secret-that-is-long-enough';

const config = (over: Record<string, string> = {}) =>
  loadConfig({ CADENTIC_SHARED_SECRET: SECRET, PORT: '0', ...over } as NodeJS.ProcessEnv);

const servers: Server[] = [];

async function start(provider: Provider, over: Record<string, string> = {}) {
  const server = createEngineServer({ config: config(over), provider, log: () => {} });
  servers.push(server);
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  const { port } = server.address() as AddressInfo;
  return `http://127.0.0.1:${port}`;
}

after(() => {
  // close() alone leaves keep-alive sockets holding the event loop open, and the run hangs
  // long after the last assertion.
  for (const s of servers) {
    s.closeAllConnections();
    s.close();
  }
});

const post = (base: string, body: unknown, headers: Record<string, string> = {}, signal?: AbortSignal) =>
  fetch(`${base}/v1/mesocycle-proposal`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-cadentic-secret': SECRET, ...headers },
    body: typeof body === 'string' ? body : JSON.stringify(body),
    signal,
  });

test('a valid payload comes back as a stamped plan', async () => {
  const base = await start(new FakeProvider([{ value: planDraft() }]));
  const res = await post(base, payload());
  assert.equal(res.status, 200);
  const plan: any = await res.json();
  assert.equal(plan.schemaVersion, 1);
  assert.equal(plan.durationWeeks, 8);
  assert.equal(plan.generatedBy.mode, 'max-plan-oauth');
});

test('a request without the shared secret is refused', async () => {
  const base = await start(new FakeProvider([{ value: planDraft() }]));
  const res = await fetch(`${base}/v1/mesocycle-proposal`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload()),
  });
  assert.equal(res.status, 401);
  assert.equal((await res.json()).error.code, 'unauthorized');
});

test('a wrong shared secret is refused', async () => {
  const base = await start(new FakeProvider([{ value: planDraft() }]));
  const res = await post(base, payload(), { 'x-cadentic-secret': 'nope' });
  assert.equal(res.status, 401);
});

test('an invalid payload comes back as payload-invalid with named fields', async () => {
  const base = await start(new FakeProvider([]));
  const bad: any = payload();
  delete bad.goals;
  const res = await post(base, bad);
  assert.equal(res.status, 400);
  const body: any = await res.json();
  assert.equal(body.error.code, 'payload-invalid');
  assert.ok(body.error.fields.some((f: string) => f.includes('goals')));
});

test('a body that is not JSON is payload-invalid, not a crash', async () => {
  const base = await start(new FakeProvider([]));
  const res = await post(base, 'not json at all');
  assert.equal(res.status, 400);
  assert.equal((await res.json()).error.code, 'payload-invalid');
});

test('a rate-limited provider surfaces as 429 with the code intact', async () => {
  const base = await start(
    new FakeProvider([new EngineError('rate-limited', 'usage window', { retryAfterSeconds: 60 })]),
  );
  const res = await post(base, payload());
  assert.equal(res.status, 429);
  assert.equal(res.headers.get('retry-after'), '60');
  const body: any = await res.json();
  assert.equal(body.error.code, 'rate-limited');
  assert.equal(body.error.retryAfterSeconds, 60);
});

test('an auth failure never leaks a stack trace', async () => {
  const base = await start(
    new FakeProvider([new EngineError('auth-failed', 'The backend\'s Claude credential was rejected.')]),
  );
  const res = await post(base, payload());
  assert.equal(res.status, 502);
  const text = await res.text();
  assert.ok(!text.includes('at '), text);
  assert.ok(!/\.ts:\d+/.test(text), text);
});

test('an unexpected provider throw still reaches the app as a named error', async () => {
  const base = await start(new FakeProvider([new Error('kaboom: /Users/someone/secret/path.ts:12')]));
  const res = await post(base, payload());
  assert.equal(res.status, 502);
  const body: any = await res.json();
  assert.equal(body.error.code, 'provider-unreachable');
  assert.ok(!JSON.stringify(body).includes('secret/path'));
});

test('Mode B returns provider-not-available until story 6 ships', async () => {
  const base = await start(new MessagesApiProvider(), { AUTH_MODE: 'B' });
  const res = await post(base, payload());
  assert.equal(res.status, 501);
  assert.equal((await res.json()).error.code, 'provider-not-available');
});

test('the request is identical in both modes — only the backend config differs', async () => {
  const a = new FakeProvider([{ value: planDraft() }]);
  const baseA = await start(a, { AUTH_MODE: 'A' });
  const b = await start(new MessagesApiProvider(), { AUTH_MODE: 'B' });
  const body = JSON.stringify(payload());
  const headers = { 'content-type': 'application/json', 'x-cadentic-secret': SECRET };
  const [resA, resB] = await Promise.all([
    fetch(`${baseA}/v1/mesocycle-proposal`, { method: 'POST', headers, body }),
    fetch(`${b}/v1/mesocycle-proposal`, { method: 'POST', headers, body }),
  ]);
  assert.equal(resA.status, 200);
  assert.equal(resB.status, 501);
});

test('a duplicate request id joins the in-flight generation instead of doubling it', async () => {
  let release: () => void = () => {};
  const gate = new Promise<void>((r) => (release = r));
  let calls = 0;
  const slow: Provider = {
    mode: 'max-plan-oauth',
    async ask() {
      calls += 1;
      await gate;
      return { value: planDraft(), model: 'fake-model', raw: '' };
    },
  };

  const base = await start(slow);
  const headers = { 'x-request-id': 'req-1' };
  const both = Promise.all([post(base, payload(), headers), post(base, payload(), headers)]);
  // Give both requests time to arrive before the provider is allowed to answer.
  await new Promise((r) => setTimeout(r, 50));
  release();
  const [one, two] = await both;

  assert.equal(calls, 1, 'one generation, not two');
  assert.equal(one.status, 200);
  assert.equal(two.status, 200);
  assert.deepEqual(await one.json(), await two.json());
});

test('a client that goes away cancels the generation', async () => {
  let aborted = false;
  const watching: Provider = {
    mode: 'max-plan-oauth',
    ask({ signal }) {
      return new Promise((_, reject) => {
        signal.addEventListener('abort', () => {
          aborted = true;
          reject(new EngineError('timeout', 'cancelled'));
        });
      });
    },
  };

  const base = await start(watching);
  const controller = new AbortController();
  const inFlight = post(base, payload(), {}, controller.signal).catch(() => 'aborted');
  await new Promise((r) => setTimeout(r, 50));
  controller.abort();
  await inFlight;
  await new Promise((r) => setTimeout(r, 50));

  assert.ok(aborted, 'the provider saw the abort');
});

test('a generation that outruns the budget is a timeout', async () => {
  const never: Provider = {
    mode: 'max-plan-oauth',
    ask({ signal }) {
      return new Promise((_, reject) => {
        signal.addEventListener('abort', () => reject(signal.reason ?? new Error('aborted')));
      });
    },
  };
  const base = await start(never, { REQUEST_TIMEOUT_MS: '100' });
  const res = await post(base, payload());
  assert.equal(res.status, 504);
  assert.equal((await res.json()).error.code, 'timeout');
});

test('healthz says what is running without saying anything about credentials', async () => {
  const base = await start(new FakeProvider([]));
  const res = await fetch(`${base}/healthz`);
  const body: any = await res.json();
  assert.equal(body.ok, true);
  assert.equal(body.mode, 'A');
  const text = JSON.stringify(body).toLowerCase();
  assert.ok(!text.includes('token') && !text.includes('secret'));
});
