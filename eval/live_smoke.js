// 最终部署后的低成本真实通道冒烟：覆盖本轮新增的确定性合规与议价规则。
const WebSocket = require('ws');

function ask(message) {
  return new Promise((resolve) => {
    const ws = new WebSocket('ws://127.0.0.1:8080/ws/chat');
    const chunks = [];
    let finished = false;
    const timer = setTimeout(() => finish('TIMEOUT'), 45000);
    const finish = (error = null) => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      try { ws.close(); } catch (e) {}
      resolve({ message, reply: chunks.join(''), error });
    };
    ws.on('open', () => ws.send(JSON.stringify({ message })));
    ws.on('message', (data) => {
      try {
        const frame = JSON.parse(data.toString());
        if (frame.type === 'chunk') chunks.push(frame.content || '');
        else if (frame.type === 'done') finish();
        else if (frame.type === 'error') finish(frame.content || 'ERROR');
      } catch (e) {
        finish('BAD_FRAME:' + e.message);
      }
    });
    ws.on('close', () => finish(finished ? null : 'WS_CLOSED'));
    ws.on('error', (error) => finish('WS_ERROR:' + error.message));
  });
}

(async () => {
  const questions = [
    '能不能打个折',
    '200块行不行',
    '最近在吃其他药，能做吗？',
    '玻尿酸填充多少钱一支？',
    '做一次项目要多久？'
  ];
  const results = [];
  for (const question of questions) results.push(await ask(question));
  for (const result of results) {
    console.log('Q: ' + result.message);
    console.log('A: ' + (result.reply || '(空)') + (result.error ? ' [ERR:' + result.error + ']' : ''));
  }
  if (results.some(result => result.error || !result.reply)) process.exitCode = 1;
})();
