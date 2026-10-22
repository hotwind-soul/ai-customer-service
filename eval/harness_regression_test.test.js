const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {payload, readSettings, runHarness} = require('./harness_regression_test');

function fixture() {
  const root = path.resolve(__dirname, '../target/harness-cli-tests');
  fs.mkdirSync(root, {recursive: true});
  const output = path.join(fs.mkdtempSync(path.join(root, 'run-')), 'report.json');
  return {output, env: {AICS_ADMIN_TOKEN: 'test-token', AICS_REGRESSION_OUTPUT: output,
    AICS_REGRESSION_TIMEOUT_MS: '2000'}, log() {}};
}
const caseAt = id => ({id, name: 'case-' + id, message: 'message-' + id, expectedReplyType: 'AI'});
const ok = body => ({ok: true, status: 200, json: async () => body});
const passed = () => ({replyType: 'AI', answer: '有效回答', assertion: {passed: true, failures: []}});

test('empty suite fails and persists a failed report', async () => {
  const options = fixture();
  await assert.rejects(runHarness({...options, fetchImpl: async () => ok([])}), /没有可执行/);
  const report = JSON.parse(fs.readFileSync(options.output, 'utf8'));
  assert.equal(report.status, 'failed');
  assert.equal(report.total, 0);
  assert.ok(report.error);
});

test('payload forwards invocation and both answer assertions to the server', () => {
  const body = payload({...caseAt(1), expectedModelInvoked: true,
    expectedAnswerPoints: '  ', forbiddenAnswerPoints: '保证'});
  assert.equal(body.expectedModelInvoked, true);
  assert.equal(body.forbiddenAnswerPoints, '保证');
  assert.equal(body.runModel, false); // 由服务端共享执行器决定，客户端不复制执行策略。
});

test('modelError always overrides a passing assertion', async () => {
  const options = fixture();
  const report = await runHarness({...options, fetchImpl: async url => ok(url.includes('regression-cases')
    ? [caseAt(1)] : {...passed(), modelError: 'model unavailable'})});
  assert.equal(report.status, 'failed');
  assert.equal(report.results[0].status, 'error');
  assert.equal(report.passed, 0);
});

test('malformed verdicts cannot be treated as passing', async () => {
  for (const assertion of [{passed: 'false', failures: []}, {passed: true},
    {passed: true, failures: ['inconsistent']}, {passed: false, failures: [42]}]) {
    const report = await runHarness({...fixture(), fetchImpl: async url => ok(url.includes('regression-cases')
      ? [caseAt(1)] : {...passed(), assertion})});
    assert.equal(report.status, 'failed');
    assert.equal(report.results[0].status, 'error');
  }
});

test('each completed case is atomically persisted with explicit pending cases', async () => {
  const options = fixture();
  options.env.AICS_REGRESSION_CONCURRENCY = '1';
  const snapshots = [];
  const report = await runHarness({...options,
    onCheckpoint: () => snapshots.push(JSON.parse(fs.readFileSync(options.output, 'utf8'))),
    fetchImpl: async url => ok(url.includes('regression-cases') ? [caseAt(1), caseAt(2)] : passed())});
  assert.equal(report.status, 'passed');
  assert.equal(report.completed, 2);
  assert.ok(snapshots.some(item => item.status === 'running' && item.completed === 1 && item.pending === 1));
  assert.ok(new Date(report.finishedAt) >= new Date(report.startedAt));
  assert.ok(snapshots.every(item => item.results.every(Boolean)));
});

test('timeout aborts a hanging request and remaining cases still run', {timeout: 3000}, async () => {
  const options = fixture();
  options.env.AICS_REGRESSION_TIMEOUT_MS = '30';
  options.env.AICS_REGRESSION_CONCURRENCY = '1';
  let signal;
  const report = await runHarness({...options, fetchImpl: async (url, request) => {
    if (url.includes('regression-cases')) return ok([caseAt(1), caseAt(2)]);
    if (JSON.parse(request.body).message === 'message-1') {
      signal = request.signal;
      return new Promise(() => {});
    }
    return ok(passed());
  }});
  assert.equal(signal.aborted, true);
  assert.equal(report.failed, 1);
  assert.equal(report.passed, 1);
  assert.match(report.results[0].assertion.failures[0], /超时/);
});

test('timeout includes stalled response body parsing', {timeout: 3000}, async () => {
  const options = fixture();
  options.env.AICS_REGRESSION_TIMEOUT_MS = '30';
  const report = await runHarness({...options, fetchImpl: async url => url.includes('regression-cases')
    ? ok([caseAt(1)]) : {ok: true, status: 200, json: () => new Promise(() => {})}});
  assert.equal(report.failed, 1);
  assert.match(report.results[0].assertion.failures[0], /超时/);
});

test('concurrency is bounded and each case runs exactly once', async () => {
  const options = fixture();
  options.env.AICS_REGRESSION_CONCURRENCY = '2';
  let active = 0, peak = 0;
  const seen = [];
  const report = await runHarness({...options, fetchImpl: async (url, request) => {
    if (url.includes('regression-cases')) return ok(Array.from({length: 5}, (_, index) => caseAt(index)));
    seen.push(JSON.parse(request.body).message);
    active++;
    peak = Math.max(peak, active);
    await new Promise(resolve => setImmediate(resolve));
    active--;
    return ok(passed());
  }});
  assert.equal(report.passed, 5);
  assert.equal(new Set(seen).size, 5);
  assert.equal(seen.length, 5);
  assert.equal(peak, 2);
});

test('invalid concurrency and timeout are rejected before execution', () => {
  for (const value of ['abc', '0', '1.5', '9']) {
    assert.throws(() => readSettings({AICS_REGRESSION_CONCURRENCY: value}), /整数/);
  }
  assert.throws(() => readSettings({AICS_REGRESSION_TIMEOUT_MS: '-1'}), /整数/);
});

test('invalid JSON is an execution error', async () => {
  const report = await runHarness({...fixture(), fetchImpl: async url => url.includes('regression-cases')
    ? ok([caseAt(1)]) : {ok: true, status: 200, json: async () => {throw new Error('bad JSON');}}});
  assert.equal(report.failed, 1);
  assert.match(report.results[0].assertion.failures[0], /有效 JSON/);
});

test('login uses the same bounded request path and forwards only the admin cookie', async () => {
  const options = fixture();
  delete options.env.AICS_ADMIN_TOKEN;
  options.env.AICS_ADMIN_USERNAME = 'test-admin';
  options.env.AICS_ADMIN_PASSWORD = 'test-password';
  const report = await runHarness({...options, fetchImpl: async (url, request) => {
    assert.ok(request.signal);
    if (url.endsWith('/agent/login')) return {...ok({ok: true}),
      headers: {getSetCookie: () => ['AICS_ADMIN=test-cookie; HttpOnly; Path=/']}};
    assert.equal(request.headers.Cookie, 'AICS_ADMIN=test-cookie');
    return ok(url.includes('regression-cases') ? [caseAt(1)] : passed());
  }});
  assert.equal(report.status, 'passed');
  assert.equal(fs.readFileSync(options.output, 'utf8').includes('test-password'), false);
});
