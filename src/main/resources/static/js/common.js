/* AICS 前端公共工具 — 时间格式化、颜色处理等 */
(function (global) {
  // P2：全局错误捕获（仅收集与展示，不打断用户）。调试模式（URL ?debug=1 或 localStorage.aics_debug=1）
  // 下在页面左下角显示报错浮层，便于线上定位 JS 问题（此前靠人工复现）。如需上报服务端，再加 /api/client-error 端点。
  function aicsDebugOn() {
    try { return /[?&]debug=1/.test(location.search) || (window.localStorage && localStorage.getItem('aics_debug') === '1'); } catch (_) { return false; }
  }
  function aicsShowTip(msg) {
    try {
      if (!document.body) return;
      var box = document.getElementById('aics-error-tip');
      if (!box) {
        box = document.createElement('div');
        box.id = 'aics-error-tip';
        box.style.cssText = 'position:fixed;left:8px;bottom:8px;max-width:60vw;z-index:99999;background:rgba(220,20,60,.92);color:#fff;font:12px/1.5 monospace;padding:6px 10px;border-radius:6px;white-space:pre-wrap;cursor:pointer';
        box.onclick = function () { box.remove(); };
        document.body.appendChild(box);
      }
      box.textContent = '⚠ 前端报错(debug): ' + msg;
    } catch (_) {}
  }
  window.addEventListener('error', function (e) {
    try {
      var detail = (e.error && e.error.stack) ? e.error.stack : (e.message || 'unknown');
      console.error('[AICS global error]', detail);
      if (aicsDebugOn() && e.message) aicsShowTip(e.message);
    } catch (_) {}
  });
  window.addEventListener('unhandledrejection', function (e) {
    try {
      var r = e.reason, detail = (r && r.stack) ? r.stack : (r && r.message) ? r.message : String(r);
      console.error('[AICS unhandled rejection]', detail);
      if (aicsDebugOn()) aicsShowTip(detail);
    } catch (_) {}
  });

const AICS = {
  pad(n) { return String(n).padStart(2, '0'); },

  // 解析后端各种时间格式（ISO、SQL、UTC 偏移等）
  parseDate(value) {
    if (!value) return null;
    if (value instanceof Date) return isNaN(value.getTime()) ? null : value;
    const s = String(value).trim();
    const d = new Date(s);
    return isNaN(d.getTime()) ? null : d;
  },

  // YYYY-MM-DD HH:mm（表格、记录、预约等正式场景）
  formatDateTime(value) {
    const d = this.parseDate(value);
    if (!d) return '';
    const p = this.pad;
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
  },

  // MM-DD HH:mm（聊天、队列等近场场景）
  formatShortDateTime(value) {
    const d = this.parseDate(value);
    if (!d) return '';
    const p = this.pad;
    return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
  },

  // MM/DD（图表坐标轴标签）
  formatMonthDay(value) {
    const d = this.parseDate(value);
    if (!d) return '';
    const p = this.pad;
    return `${p(d.getMonth() + 1)}/${p(d.getDate())}`;
  },

  // YYYY-MM-DD（有效期、日期选择器等仅日期场景）
  formatDate(value) {
    const d = this.parseDate(value);
    if (!d) return '';
    const p = this.pad;
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
  },

  // 把任意时间字符串转成 YYYY-MM-DD，供按日聚合/图表数据清洗
  toDayString(value) {
    return this.formatDate(value);
  }
};

// 暴露到全局，供 admin.html / agent.html / index.html 的 Vue 模板直接以 AICS.xxx 引用。
// 浏览器里 global 不存在，必须显式挂到 window / globalThis，否则 IIFE 内的 const 对外不可见。
if (typeof globalThis !== 'undefined') globalThis.AICS = AICS;
if (typeof window !== 'undefined') window.AICS = AICS;
if (typeof global !== 'undefined' && global && typeof global.AICS === 'undefined') global.AICS = AICS;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : this));
