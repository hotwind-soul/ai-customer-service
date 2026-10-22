// 数据库驱动的 Harness 回归：网页、CLI 与发布门禁共用服务端执行策略。
// AICS_ADMIN_TOKEN=... node eval/harness_regression_test.js
// 或设置 AICS_ADMIN_USERNAME / AICS_ADMIN_PASSWORD。
// 可选：AICS_BASE_URL、AICS_REGRESSION_CONCURRENCY(1..8)、
// AICS_REGRESSION_TIMEOUT_MS(默认120000)、AICS_REGRESSION_OUTPUT。
const fs = require('node:fs');
const path = require('node:path');

function readSettings(env) {
  function integer(name, fallback, max) {
    const value = env[name] == null || env[name] === '' ? fallback : Number(env[name]);
    if (!Number.isInteger(value) || value < 1 || value > max) {
      throw new Error(name + ' 必须是 1 到 ' + max + ' 的整数');
    }
    return value;
  }
  return {
    base: (env.AICS_BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, ''),
    concurrency: integer('AICS_REGRESSION_CONCURRENCY', 4, 8),
    timeoutMs: integer('AICS_REGRESSION_TIMEOUT_MS', 120000, 900000),
    output: path.resolve(__dirname, env.AICS_REGRESSION_OUTPUT || 'harness_regression_results.json'),
  };
}

function payload(testCase) {
  return {
    message: testCase.message, history: testCase.history || '', profile: '',
    // 服务端根据断言决定是否生成，客户端仅提交完整的期望值。
    runModel: false,
    expectedReplyType: testCase.expectedReplyType || 'AI',
    expectedAnswerPoints: testCase.expectedAnswerPoints || '',
    forbiddenAnswerPoints: testCase.forbiddenAnswerPoints || '',
    expectedAuthorityDomain: testCase.expectedAuthorityDomain || '',
    expectedAuthoritySource: testCase.expectedAuthoritySource || '',
    expectedModelInvoked: testCase.expectedModelInvoked ?? null,
  };
}

async function runHarness({env = process.env, fetchImpl = globalThis.fetch, log = console.log,
                            onCheckpoint = () => {}} = {}) {
  const settings = readSettings(env);
  const report = {startedAt: new Date().toISOString(), finishedAt: null, base: settings.base,
    source: 'ai_regression_case', status: 'running', total: 0, completed: 0,
    passed: 0, failed: 0, pending: 0, results: []};

  function checkpoint() {
    report.completed = report.results.filter(row => row.status !== 'pending').length;
    report.passed = report.results.filter(row => row.status === 'passed').length;
    report.failed = report.completed - report.passed;
    report.pending = report.total - report.completed;
    fs.mkdirSync(path.dirname(settings.output), {recursive: true});
    const temporary = settings.output + '.' + process.pid + '.tmp';
    fs.writeFileSync(temporary, JSON.stringify(report, null, 2), 'utf8');
    fs.renameSync(temporary, settings.output);
    onCheckpoint(report);
  }

  async function request(endpoint, headers = {}, options = {}) {
    const abort = new AbortController();
    let timer;
    const deadline = new Promise((_, reject) => {
      timer = setTimeout(() => {
        const error = new Error('请求超时（' + settings.timeoutMs + ' 毫秒）：' + endpoint);
        abort.abort(error);
        reject(error);
      }, settings.timeoutMs);
    });
    try {
      return await Promise.race([deadline, (async () => {
        const response = await fetchImpl(settings.base + endpoint, {
          ...options, headers: {...headers, ...(options.headers || {})}, signal: abort.signal,
        });
        let body;
        try { body = await response.json(); }
        catch (_) { throw new Error('接口未返回有效 JSON（HTTP ' + response.status + '）'); }
        if (!response.ok || body?.error) {
          throw new Error(body?.error || body?.message || 'HTTP ' + response.status);
        }
        return {body, response};
      })()]);
    } finally {
      clearTimeout(timer);
    }
  }

  async function authHeaders() {
    const token = String(env.AICS_ADMIN_TOKEN || '').trim();
    if (token) return {'X-Admin-Token': token};
    const username = String(env.AICS_ADMIN_USERNAME || '').trim();
    const password = String(env.AICS_ADMIN_PASSWORD || '');
    if (!username || !password) {
      throw new Error('请设置 AICS_ADMIN_TOKEN，或设置 AICS_ADMIN_USERNAME 与 AICS_ADMIN_PASSWORD');
    }
    const {body, response} = await request('/agent/login', {}, {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({username, password, portal: 'admin'}),
    });
    if (body.ok !== true) throw new Error('管理员登录失败');
    const cookies = typeof response.headers.getSetCookie === 'function'
      ? response.headers.getSetCookie() : [response.headers.get('set-cookie') || ''];
    const cookie = cookies.map(value => String(value).split(';')[0])
      .find(value => value.startsWith('AICS_ADMIN='));
    if (!cookie) throw new Error('管理员登录响应中没有 AICS_ADMIN Cookie');
    return {Cookie: cookie};
  }

  try {
    checkpoint();
    const headers = await authHeaders();
    const {body: cases} = await request('/api/quality/regression-cases?includeDisabled=false', headers);
    if (!Array.isArray(cases) || cases.length === 0) throw new Error('没有可执行的启用回归用例');
    report.total = cases.length;
    report.results = cases.map(testCase => ({id: testCase.id, name: testCase.name,
      message: testCase.message, expectedReplyType: testCase.expectedReplyType, status: 'pending'}));
    checkpoint();
    let cursor = 0;
    async function worker() {
      while (cursor < cases.length) {
        const index = cursor++;
        const testCase = cases[index];
        const started = performance.now();
        let result;
        try {
          const {body: run} = await request('/api/chat/debug/run', headers, {
            method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(payload(testCase)),
          });
          if (run.modelError) throw new Error(run.modelError);
          if (typeof run.assertion?.passed !== 'boolean' || !Array.isArray(run.assertion.failures)
              || run.assertion.failures.some(reason => typeof reason !== 'string')
              || (run.assertion.passed && run.assertion.failures.length > 0)) {
            throw new Error('服务端返回了无效的断言结果');
          }
          result = {...report.results[index], status: run.assertion.passed ? 'passed' : 'failed',
            actualReplyType: run.replyType, answer: run.answer || '', assertion: run.assertion,
            turnTrace: run.turnTrace || null, durationMs: Math.round(performance.now() - started)};
        } catch (error) {
          result = {...report.results[index], status: 'error', actualReplyType: 'ERROR', answer: '',
            assertion: {passed: false, failures: [error.message || String(error)]}, turnTrace: null,
            durationMs: Math.round(performance.now() - started)};
        }
        report.results[index] = result;
        checkpoint();
        log('[' + (index + 1) + '/' + cases.length + '] ' + testCase.name + ': ' + result.status.toUpperCase()
          + (result.assertion.passed ? '' : ' ' + result.assertion.failures.join('；')));
      }
    }
    await Promise.all(Array.from({length: Math.min(settings.concurrency, cases.length)}, () => worker()));
    report.status = report.failed === 0 ? 'passed' : 'failed';
    report.finishedAt = new Date().toISOString();
    checkpoint();
    log('Harness 回归：' + report.passed + '/' + report.total + ' 通过；结果：' + settings.output);
    return report;
  } catch (error) {
    report.status = 'failed';
    report.error = error.message || String(error);
    report.finishedAt = new Date().toISOString();
    checkpoint();
    throw error;
  }
}

module.exports = {payload, readSettings, runHarness};
if (require.main === module) {
  runHarness().then(report => {process.exitCode = report.status === 'passed' ? 0 : 1;})
    .catch(error => {console.error('FATAL', error.message || error); process.exitCode = 1;});
}
