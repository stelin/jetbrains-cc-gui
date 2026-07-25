import test from 'node:test';
import assert from 'node:assert/strict';

import { __testing, sendMessagePersistent } from './persistent-query-service.js';

/**
 * Create a Promise that can be manually resolved.
 * @returns {{ promise: Promise, resolve: Function, reject: Function }}
 */
function createDeferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function createQueryFactory() {
  const runtimes = [];
  return {
    runtimes,
    queryFn({ prompt, options }) {
      const runtime = {
        prompt,
        options,
        closed: false,
        setPermissionMode: async () => {},
        setModel: async () => {},
        setMaxThinkingTokens: async () => {},
        close() {
          this.closed = true;
        },
        async next() {
          return { done: true, value: undefined };
        }
      };
      runtimes.push(runtime);
      return runtime;
    }
  };
}

/**
 * Create a query factory that returns next() results in sequence.
 * @param {Array<(() => Promise<{done: boolean, value?: any}>) | {done: boolean, value?: any}>} steps
 */
function createSequencedQueryFactory(steps) {
  const runtimes = [];
  return {
    runtimes,
    queryFn({ prompt, options }) {
      let index = 0;
      const runtime = {
        prompt,
        options,
        closed: false,
        setPermissionMode: async () => {},
        setModel: async () => {},
        setMaxThinkingTokens: async () => {},
        close() {
          this.closed = true;
        },
        async next() {
          const step = steps[index++];
          if (!step) {
            return { done: false, value: { type: 'result', is_error: false } };
          }
          return typeof step === 'function' ? await step() : step;
        }
      };
      runtimes.push(runtime);
      return runtime;
    }
  };
}

test.beforeEach(async () => {
  await __testing.resetState();
});

test.after(async () => {
  await __testing.resetState();
});

test('anonymous runtime is isolated by runtimeSessionEpoch', async () => {
  const factory = createQueryFactory();
  __testing.setQueryFn(factory.queryFn);

  const firstContext = await __testing.buildRequestContext({
    sessionId: '',
    runtimeSessionEpoch: 'epoch-1',
    cwd: process.cwd(),
    message: 'hello'
  }, false);

  const runtime1 = await __testing.acquireRuntime(firstContext);
  const runtime1Again = await __testing.acquireRuntime(firstContext);
  assert.equal(runtime1, runtime1Again);
  assert.equal(factory.runtimes.length, 1);

  const secondContext = await __testing.buildRequestContext({
    sessionId: '',
    runtimeSessionEpoch: 'epoch-2',
    cwd: process.cwd(),
    message: 'hello again'
  }, false);

  const runtime2 = await __testing.acquireRuntime(secondContext);
  assert.notEqual(runtime1, runtime2);
  assert.equal(factory.runtimes.length, 2);
});

test('same-tab new-session isolation matches fresh runtime isolation expectations', async () => {
  const factory = createQueryFactory();
  __testing.setQueryFn(factory.queryFn);

  const firstContext = await __testing.buildRequestContext({
    sessionId: '',
    runtimeSessionEpoch: 'epoch-a',
    cwd: process.cwd(),
    message: 'first turn'
  }, false);
  const runtimeA = await __testing.acquireRuntime(firstContext);

  await __testing.resetRuntimePersistent({ runtimeSessionEpoch: 'epoch-a' });

  const secondContext = await __testing.buildRequestContext({
    sessionId: '',
    runtimeSessionEpoch: 'epoch-b',
    cwd: process.cwd(),
    message: 'new session turn'
  }, false);
  const runtimeB = await __testing.acquireRuntime(secondContext);

  assert.notEqual(runtimeA, runtimeB);
  assert.equal(factory.runtimes.length, 2);
  assert.equal(__testing.getSnapshot().anonymousRuntimeCount, 1);
});

test('resetRuntimePersistent disposes active turn runtime for interrupted old epoch before next first send', async () => {
  const factory = createQueryFactory();
  __testing.setQueryFn(factory.queryFn);

  const oldContext = await __testing.buildRequestContext({
    sessionId: '',
    runtimeSessionEpoch: 'epoch-old',
    cwd: process.cwd(),
    message: 'streaming turn'
  }, false);
  const oldRuntime = await __testing.acquireRuntime(oldContext);
  __testing.setActiveTurnRuntime(oldRuntime);

  await __testing.resetRuntimePersistent({ runtimeSessionEpoch: 'epoch-old' });

  const nextContext = await __testing.buildRequestContext({
    sessionId: '',
    runtimeSessionEpoch: 'epoch-new',
    cwd: process.cwd(),
    message: 'first send after interrupt'
  }, false);
  const nextRuntime = await __testing.acquireRuntime(nextContext);

  assert.equal(oldRuntime.closed, true);
  assert.notEqual(oldRuntime, nextRuntime);
  assert.equal(__testing.getSnapshot().activeTurnEpoch, null);
});

test('restore-history continuation keeps runtime bound to restored session after reset of prior epoch', async () => {
  const factory = createQueryFactory();
  __testing.setQueryFn(factory.queryFn);

  const oldAnonymousContext = await __testing.buildRequestContext({
    sessionId: '',
    runtimeSessionEpoch: 'epoch-stale',
    cwd: process.cwd(),
    message: 'stale anonymous'
  }, false);
  await __testing.acquireRuntime(oldAnonymousContext);
  await __testing.resetRuntimePersistent({ runtimeSessionEpoch: 'epoch-stale' });

  const restoredContext = await __testing.buildRequestContext({
    sessionId: 'hist-restore',
    runtimeSessionEpoch: 'epoch-restore',
    cwd: process.cwd(),
    message: 'restored continuation'
  }, false);
  const restoredRuntime = await __testing.acquireRuntime(restoredContext);
  const restoredRuntimeAgain = await __testing.acquireRuntime(restoredContext);

  assert.equal(restoredRuntime, restoredRuntimeAgain);
  assert.equal(__testing.getRuntimeForSession('hist-restore'), restoredRuntime);
});

test('active session runtime is not disposed by idle cleanup while a turn is executing', async () => {
  const nextDeferred = createDeferred();
  const enteredDeferred = createDeferred();
  const factory = createSequencedQueryFactory([
    async () => {
      enteredDeferred.resolve();
      return nextDeferred.promise;
    },
    { done: false, value: { type: 'result', is_error: false } }
  ]);
  __testing.setQueryFn(factory.queryFn);

  const context = await __testing.buildRequestContext({
    sessionId: 'session-active',
    runtimeSessionEpoch: 'epoch-active',
    cwd: process.cwd(),
    message: 'long running turn'
  }, false);
  const runtime = await __testing.acquireRuntime(context);
  runtime.lastUsedAt = Date.now() - (31 * 60 * 1000);

  const turnPromise = __testing.executeTurn(runtime, context);
  await enteredDeferred.promise;

  await __testing.cleanupSessionRuntimes();

  assert.equal(runtime.closed, false);
  assert.equal(__testing.getRuntimeForSession('session-active'), runtime);

  nextDeferred.resolve({ done: false, value: { type: 'assistant', message: { content: [{ type: 'text', text: 'done' }] } } });
  await turnPromise;
});

test('idle session runtime is still disposed by idle cleanup', async () => {
  const factory = createQueryFactory();
  __testing.setQueryFn(factory.queryFn);

  const context = await __testing.buildRequestContext({
    sessionId: 'session-idle',
    runtimeSessionEpoch: 'epoch-idle',
    cwd: process.cwd(),
    message: 'idle turn'
  }, false);
  const runtime = await __testing.acquireRuntime(context);
  runtime.lastUsedAt = Date.now() - (31 * 60 * 1000);

  await __testing.cleanupSessionRuntimes();

  assert.equal(runtime.closed, true);
  assert.equal(__testing.getRuntimeForSession('session-idle'), null);
});

test('active anonymous runtime is not disposed by idle cleanup while a turn is executing', async () => {
  const nextDeferred = createDeferred();
  const enteredDeferred = createDeferred();
  const factory = createSequencedQueryFactory([
    async () => {
      enteredDeferred.resolve();
      return nextDeferred.promise;
    },
    { done: false, value: { type: 'result', is_error: false } }
  ]);
  __testing.setQueryFn(factory.queryFn);

  const context = await __testing.buildRequestContext({
    sessionId: '',
    runtimeSessionEpoch: 'epoch-anon-active',
    cwd: process.cwd(),
    message: 'anonymous long running turn'
  }, false);
  const runtime = await __testing.acquireRuntime(context);
  runtime.lastUsedAt = Date.now() - (11 * 60 * 1000);

  const turnPromise = __testing.executeTurn(runtime, context);
  await enteredDeferred.promise;

  await __testing.cleanupAnonymousRuntimes();

  assert.equal(runtime.closed, false);
  assert.equal(__testing.getSnapshot().anonymousRuntimeCount, 1);

  nextDeferred.resolve({ done: false, value: { type: 'assistant', message: { content: [{ type: 'text', text: 'done' }] } } });
  await turnPromise;
});

test('executeTurn refreshes lastUsedAt while processing query events', async () => {
  const factory = createSequencedQueryFactory([
    { done: false, value: { type: 'assistant', message: { content: [{ type: 'text', text: 'partial' }] } } },
    { done: false, value: { type: 'result', is_error: false } }
  ]);
  __testing.setQueryFn(factory.queryFn);

  const context = await __testing.buildRequestContext({
    sessionId: 'session-refresh',
    runtimeSessionEpoch: 'epoch-refresh',
    cwd: process.cwd(),
    message: 'refresh lastUsedAt'
  }, false);
  const runtime = await __testing.acquireRuntime(context);
  runtime.lastUsedAt = 1;

  await __testing.executeTurn(runtime, context);

  assert.ok(runtime.lastUsedAt > 1);
});

test('abortCurrentTurn still disposes an active runtime explicitly', async () => {
  const nextDeferred = createDeferred();
  const enteredDeferred = createDeferred();
  const factory = createSequencedQueryFactory([
    async () => {
      enteredDeferred.resolve();
      return nextDeferred.promise;
    }
  ]);
  __testing.setQueryFn(factory.queryFn);

  const context = await __testing.buildRequestContext({
    sessionId: 'session-abort',
    runtimeSessionEpoch: 'epoch-abort',
    cwd: process.cwd(),
    message: 'abort me'
  }, false);
  const runtime = await __testing.acquireRuntime(context);
  const turnPromise = __testing.executeTurn(runtime, context);
  await enteredDeferred.promise;

  await __testing.abortCurrentTurn();
  nextDeferred.reject(new Error('runtime terminated'));

  await assert.rejects(turnPromise, /runtime terminated/);
  assert.equal(runtime.closed, true);
});

// ---------------------------------------------------------------------------
// Encrypted-content 400 self-repair (poisoned thinking blocks)
// ---------------------------------------------------------------------------

const ENCRYPTED_CONTENT_400 = 'API Error: 400 The encrypted content gAAA...qZM= could not be verified. '
  + 'Reason: Encrypted content could not be decrypted or parsed.';

function encryptedContentErrorResult() {
  return { done: false, value: { type: 'result', is_error: true, errors: [ENCRYPTED_CONTENT_400] } };
}

test('encrypted-content 400 disposes the poisoned runtime and retries once on a fresh one', async () => {
  let call = 0;
  const factories = [
    createSequencedQueryFactory([
      { done: false, value: { type: 'system', subtype: 'init', session_id: 'sess-enc-1' } },
      encryptedContentErrorResult()
    ]),
    createSequencedQueryFactory([
      { done: false, value: { type: 'system', subtype: 'init', session_id: 'sess-enc-1' } },
      { done: false, value: { type: 'result', is_error: false } }
    ])
  ];
  __testing.setQueryFn((args) => factories[call++].queryFn(args));

  await sendMessagePersistent({
    message: 'hi',
    sessionId: '',
    cwd: process.cwd(),
    runtimeSessionEpoch: 'epoch-enc-1'
  });

  assert.equal(call, 2, 'a fresh runtime must be created for the retry');
  const [firstFactory, secondFactory] = factories;
  assert.equal(firstFactory.runtimes[0].closed, true, 'poisoned runtime must be disposed');
  assert.equal(secondFactory.runtimes[0].closed, false, 'retry runtime survives after success');
  assert.equal(
    secondFactory.runtimes[0].options.resume,
    'sess-enc-1',
    'retry resumes the repaired session in place'
  );
});

test('encrypted-content 400 is retried at most once; a repeated failure disposes the runtime', async () => {
  let call = 0;
  const makeFailingFactory = () => createSequencedQueryFactory([
    { done: false, value: { type: 'system', subtype: 'init', session_id: 'sess-enc-2' } },
    encryptedContentErrorResult()
  ]);
  const factories = [makeFailingFactory(), makeFailingFactory(), makeFailingFactory()];
  __testing.setQueryFn((args) => factories[call++].queryFn(args));

  await sendMessagePersistent({
    message: 'hi',
    sessionId: '',
    cwd: process.cwd(),
    runtimeSessionEpoch: 'epoch-enc-2'
  });

  assert.equal(call, 2, 'no third runtime — the retry guard stops the loop');
  assert.equal(factories[0].runtimes[0].closed, true);
  assert.equal(factories[1].runtimes[0].closed, true, 'still-poisoned retry runtime is disposed too');
});

test('non-encrypted error results are not retried and keep the runtime alive', async () => {
  const factory = createSequencedQueryFactory([
    { done: false, value: { type: 'system', subtype: 'init', session_id: 'sess-other' } },
    { done: false, value: { type: 'result', is_error: true, errors: ['API Error: 429 rate limit exceeded'] } }
  ]);
  __testing.setQueryFn(factory.queryFn);

  await sendMessagePersistent({
    message: 'hi',
    sessionId: '',
    cwd: process.cwd(),
    runtimeSessionEpoch: 'epoch-other'
  });

  assert.equal(factory.runtimes.length, 1, 'no retry runtime for unrelated API errors');
  assert.equal(factory.runtimes[0].closed, false, 'runtime survives a non-poisoning error');
});
