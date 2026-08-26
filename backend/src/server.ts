/**
 * The endpoint (Epic 2 story 0).
 *
 * `POST /v1/mesocycle-proposal` takes the Epic 1 meso-request payload **verbatim** as the
 * body — the same bytes `MesoRequestAssembler` produced, with nothing wrapped around them —
 * and returns either a validated Mesocycle Plan or one named error. The request id travels
 * in a header precisely so the body can stay byte-identical to the assembled payload.
 *
 * **Abandoned generations (implementor open point 2): cancel propagation, with an in-flight
 * join by request id.** When the athlete presses back, the app cancels its HTTP call, the
 * socket closes, and the abort reaches the provider — no orphaned generation burning
 * subscription usage on a plan nobody will see. The in-flight map covers the other half:
 * a duplicate request id arriving while one is still running joins it instead of starting a
 * second generation, so a double tap costs one generation. A generation is only aborted once
 * every waiter has gone away.
 */

import { createHash, timingSafeEqual } from 'node:crypto';
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import { randomUUID } from 'node:crypto';

import type { Config } from './config.js';
import { fieldErrors, validateRequestPayload } from './contract.js';
import { EngineError, isEngineError } from './errors.js';
import { type MesocyclePlan, generatePlan } from './generate.js';
import type { Provider } from './provider/index.js';

const PATH = '/v1/mesocycle-proposal';
const SECRET_HEADER = 'x-cadentic-secret';
const REQUEST_ID_HEADER = 'x-request-id';
const MAX_BODY_BYTES = 256 * 1024;

export type Logger = (event: Record<string, unknown>) => void;

export interface ServerDeps {
  config: Config;
  provider: Provider;
  log?: Logger;
}

interface InFlight {
  promise: Promise<MesocyclePlan>;
  controller: AbortController;
  waiters: number;
}

export function createEngineServer({ config, provider, log = defaultLog }: ServerDeps): Server {
  const inFlight = new Map<string, InFlight>();
  const secretDigest = sha256(config.sharedSecret);

  return createServer((req, res) => {
    handle(req, res).catch((e) => {
      // Nothing should reach here; if it does, the app still gets a named error.
      log({ event: 'unhandled', error: String(e) });
      send(res, new EngineError('provider-unreachable', 'The backend failed unexpectedly.'));
    });
  });

  async function handle(req: IncomingMessage, res: ServerResponse): Promise<void> {
    if (req.method === 'GET' && req.url === '/healthz') {
      // Deliberately says nothing about credentials, only that the process is up and which
      // mode it is serving — enough to diagnose, nothing worth leaking.
      return sendJson(res, 200, { ok: true, mode: config.mode, model: config.model });
    }

    if (req.url?.split('?')[0] !== PATH) return sendJson(res, 404, { error: { code: 'payload-invalid', message: 'No such endpoint.' } });
    if (req.method !== 'POST') return sendJson(res, 405, { error: { code: 'payload-invalid', message: 'Use POST.' } });

    if (!secretOk(req, secretDigest)) {
      log({ event: 'refused', reason: 'shared-secret' });
      return send(res, new EngineError('unauthorized', 'Missing or incorrect shared secret.'));
    }

    const requestId = headerValue(req, REQUEST_ID_HEADER) || randomUUID();

    let payload: any;
    try {
      payload = JSON.parse(await readBody(req));
    } catch (e) {
      return send(
        res,
        isEngineError(e) ? e : new EngineError('payload-invalid', 'The request body is not JSON.'),
      );
    }

    if (!validateRequestPayload(payload)) {
      const fields = fieldErrors(validateRequestPayload.errors);
      log({ event: 'rejected', requestId, fields });
      return send(
        res,
        new EngineError('payload-invalid', 'The meso-request payload is not valid.', { fields }),
      );
    }

    const started = Date.now();
    try {
      const plan = await run(requestId, payload, res);
      log({ event: 'generated', requestId, ms: Date.now() - started });
      sendJson(res, 200, plan);
    } catch (e) {
      const error = isEngineError(e)
        ? e
        : new EngineError('provider-unreachable', 'The backend failed to generate a plan.');
      log({ event: 'failed', requestId, code: error.code, ms: Date.now() - started });
      send(res, error);
    }
  }

  /** Joins an in-flight generation for the same request id, or starts one. */
  function run(requestId: string, payload: unknown, res: ServerResponse): Promise<MesocyclePlan> {
    let entry = inFlight.get(requestId);

    if (!entry) {
      const controller = new AbortController();
      const timer = setTimeout(
        () => controller.abort(new EngineError('timeout', 'Generation exceeded the time budget.')),
        config.requestTimeoutMs,
      );

      const promise = generatePlan({
        payload,
        provider,
        startDateWindowDays: config.startDateWindowDays,
        signal: controller.signal,
        log: (e) => log({ requestId, ...e }),
        logRaw: config.logRawResponses,
      })
        .finally(() => {
          clearTimeout(timer);
          inFlight.delete(requestId);
        });

      entry = { promise, controller, waiters: 0 };
      inFlight.set(requestId, entry);
    }

    const active = entry;
    active.waiters += 1;

    // The athlete pressing back cancels the app's call, which closes this socket. Watch the
    // *response*, not the request: `IncomingMessage` emits 'close' as soon as the body has
    // been consumed, so a listener there fires on every request and never means what it
    // looks like. `ServerResponse` emits 'close' when the response finishes **or** when the
    // connection dies first, and `writableFinished` tells the two apart.
    //
    // Only the last waiter's departure aborts: a duplicate request id that joined an
    // in-flight generation must not take it down for the request still holding the line.
    let released = false;
    const release = () => {
      if (released) return;
      released = true;
      active.waiters -= 1;
    };
    const onClose = () => {
      if (res.writableFinished) return release();
      release();
      if (active.waiters <= 0) {
        log({ event: 'cancelled', requestId });
        active.controller.abort(new EngineError('timeout', 'The client went away.'));
      }
    };
    res.once('close', onClose);

    return active.promise.then(
      (plan) => plan,
      (e) => {
        // An abort carries the reason it was aborted with, so a budget overrun reads as
        // `timeout` rather than as a generic provider failure.
        const reason = active.controller.signal.reason;
        throw isEngineError(reason) && !isEngineError(e) ? reason : e;
      },
    );
  }
}

// --- HTTP plumbing ---------------------------------------------------------

function headerValue(req: IncomingMessage, name: string): string {
  const v = req.headers[name];
  return Array.isArray(v) ? (v[0] ?? '') : (v ?? '');
}

const sha256 = (s: string): Buffer => createHash('sha256').update(s, 'utf8').digest();

/** Digest-then-compare so the check is constant-time regardless of what was sent. */
function secretOk(req: IncomingMessage, expected: Buffer): boolean {
  const supplied = headerValue(req, SECRET_HEADER);
  if (!supplied) return false;
  return timingSafeEqual(sha256(supplied), expected);
}

function readBody(req: IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    req.on('data', (c: Buffer) => {
      size += c.length;
      if (size > MAX_BODY_BYTES) {
        reject(new EngineError('payload-invalid', 'The request body is too large.'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function sendJson(res: ServerResponse, status: number, body: unknown): void {
  if (res.writableEnded) return;
  const text = JSON.stringify(body);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(text),
  });
  res.end(text);
}

function send(res: ServerResponse, error: EngineError): void {
  if (error.detail.retryAfterSeconds !== undefined && !res.writableEnded) {
    res.setHeader('retry-after', String(error.detail.retryAfterSeconds));
  }
  sendJson(res, error.status, error.toBody());
}

const defaultLog: Logger = (event) => {
  process.stderr.write(`${JSON.stringify({ t: new Date().toISOString(), ...event })}\n`);
};
