// 用 jsdom + Vue 3 真实渲染 admin.html，捕获 "Cannot convert object to primitive value"
// 用法：node render_admin.js
const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

// 1. 读 admin.html（相对脚本所在目录解析，任何机器 checkout 后可直接运行）
const candidates = [
  path.join(__dirname, 'src/main/resources/static/admin.html'),
  path.join(__dirname, 'target/classes/static/admin.html'),
];
const htmlPath = candidates.find(p => fs.existsSync(p));
console.log('[使用文件]', htmlPath);
const html = fs.readFileSync(htmlPath, 'utf8');

// 2. Vue 3 IIFE 全局构建（会在 globalThis.Vue 暴露）
const vueSource = fs.readFileSync(path.join(__dirname, 'node_modules/vue/dist/vue.global.js'), 'utf8');

// 3. 提取 <script>...</script> 块（去除带 src 的）
const scriptRe = /<script(?![^>]*src=)[^>]*>([\s\S]*?)<\/script>/g;
const scripts = [];
let m;
while ((m = scriptRe.exec(html))) scripts.push(m[1]);
console.log('[提取到 script 块]', scripts.length, '个');

// 4. jsdom
const dom = new JSDOM('<!DOCTYPE html><html><head></head><body></body></html>', {
  url: 'http://localhost:8080/admin.html',
  runScripts: 'outside-only',
  pretendToBeVisual: true,
});

// 5. 注入 Vue + 模拟浏览器 API（Vue 用 IIFE 在 globalThis 暴露，这里跳过直接赋值）
const w = dom.window;
// 先在 jsdom window 里执行 Vue IIFE，让 window.Vue 可用
try { w.eval(vueSource); } catch (e) { console.error('[Vue IIFE eval 错误]', e.message); }
console.log('[window.Vue 存在]', typeof w.Vue, Object.keys(w.Vue || {}).slice(0, 8));

// 5.5 预加载 common.js（全局 AICS 定义处）。不加载它，内联脚本会报 "AICS is not defined"，
//     这正是此前全量挂载验证跑不起来的根因（脚手架限制，非页面代码问题）。
try {
  const commonPath = path.join(path.dirname(htmlPath), 'js', 'common.js');
  const commonSource = fs.readFileSync(commonPath, 'utf8');
  w.eval(commonSource);
  console.log('[common.js 已加载]', commonPath, '→ window.AICS =', typeof w.AICS);
} catch (e) {
  console.error('[common.js 加载失败]', e.message);
}

// 模拟已登录（必须显式从环境注入测试 token，不在脚本中保留默认凭证）
const adminToken = process.env.AICS_TEST_ADMIN_TOKEN;
if (!adminToken) throw new Error('请先设置测试变量 AICS_TEST_ADMIN_TOKEN（管理员登录令牌）');
const _store = { 'aics_admin_token': adminToken };
Object.defineProperty(w, 'localStorage', { value: { getItem: k => _store[k] ?? null, setItem: (k,v) => _store[k]=v, removeItem: k => delete _store[k] } });
// jsdom 没有 fetch，把 Node 22 的全局 fetch 注入，并把相对 URL 转绝对
w.fetch = async (url, opts) => {
  const abs = /^https?:/.test(url) ? url : new URL(url, w.location.href).href;
  const r = await globalThis.fetch(abs, opts);
  console.log('[fetch]', r.status, abs.split('localhost:8080')[1] || abs);
  return r;
};

// localStorage 已经在 jsdom 默认存在；缺失时 mock
if (!w.localStorage) {
  Object.defineProperty(w, 'localStorage', { value: { getItem: () => '', setItem: () => {}, removeItem: () => {} } });
}
// navigator.clipboard 模拟
if (!w.navigator.clipboard) {
  Object.defineProperty(w.navigator, 'clipboard', { value: undefined });
}
// URLSearchParams jsdom 默认有

// 6. 把 admin.html 的 <body> 内容塞进 jsdom
const bodyMatch = html.match(/<body[^>]*>([\s\S]*?)<\/body>/);
const headMatch = html.match(/<head[^>]*>([\s\S]*?)<\/head>/);
if (headMatch) w.document.head.innerHTML = headMatch[1];
if (bodyMatch) w.document.body.innerHTML = bodyMatch[1];

// 7. 错误捕获
w.addEventListener('error', (e) => {
  console.error('[window error]', e.message, '\n', e.error?.stack || '');
});
process.on('uncaughtException', (e) => console.error('[uncaught]', e));
process.on('unhandledRejection', (e) => console.error('[unhandled rejection]', e));

// 8. 执行 script
for (const s of scripts) {
  try {
    // eval 在 window 作用域执行（Vue createApp 需 window.Vue）
    w.eval(s);
  } catch (e) {
    console.error('[script 抛错]', e.message);
    console.error(e.stack);
  }
}

// 9. 等待异步 + Vue 渲染
(async () => {
  // 触发初始数据加载（loadKb 等）
  await new Promise(r => setTimeout(r, 2500));
  console.log('\n========== 初始加载后 ==========');
  console.log('document.title:', w.document.title);
  console.log('#app 前 200 字:', (w.document.getElementById('app')?.innerHTML || '').slice(0, 200));

  // 遍历所有 tab（模拟用户点击每个标签页）
  try {
    w.eval(`
      const appEl = document.getElementById('app');
      const appInst = appEl.__vue_app__;
      const proxy = appInst && appInst._instance && appInst._instance.proxy;
      console.log('[找到 proxy]', !!proxy, 'tab=', proxy && proxy.tab, 'typeof switchTab=', typeof (proxy && proxy.switchTab));
      if (proxy) {
        const tabs = (proxy.tabs || []).map(t => t.key);
        console.log('[所有 tab]', tabs.join(', '));
        for (const k of tabs) {
          try { proxy.switchTab(k); console.log('[切换]', k); }
          catch (e) { console.error('[switchTab(' + k + ') 错误]', e.message); }
        }
        console.log('[已遍历全部 tab]');
      }
    `);
  } catch (e) {
    console.error('[遍历 tab 错误]', e.message, e.stack);
  }

  await new Promise(r => setTimeout(r, 2500));
  console.log('\n========== 遍历 tab 后 ==========');
  console.log('document.title:', w.document.title);
  if (w.document.title.startsWith('⚠')) {
    console.log('\n⚠ 真实运行时报错:', w.document.title);
  } else {
    console.log('✓ 无错误标题（errorHandler 未触发）');
  }
})();
