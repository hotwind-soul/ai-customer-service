// 意图置信度与多意图真实通道冒烟；使用 Node 24 内置 WebSocket，无第三方依赖。
const scenarios = [
  {
    name: '预约加价格先答事实',
    message: '我想预约抗衰，多少钱？',
    pass: answer => /\d+(?:\.\d+)?元/.test(answer)
      && answer.includes('目前还没有替您登记') && answer.includes('确认预约')
  },
  {
    name: '议价后仍想预约',
    message: '太贵了但我还是想预约',
    pass: answer => answer.includes('不能擅自承诺额外优惠') && answer.includes('确认预约')
  },
  {
    name: '否定预约不误启动动作',
    message: '不预约直接到店能享受活动价吗',
    pass: answer => answer.includes('门店') && !answer.includes('目前还没有替您登记')
  },
  {
    name: '已说明抗衰流程不重复问项目',
    message: '做抗衰是打针还是手术？',
    pass: answer => !answer.includes('想了解什么项目') && !answer.includes('哪个项目')
      && !answer.includes('问题好像没发出来')
  },
  {
    name: '疼痛麻药使用安全回答',
    message: '做抗衰痛吗？要打麻药吗？',
    pass: answer => answer.includes('因') && answer.includes('专业人员')
      && !answer.includes('想了解什么项目')
  },
  {
    name: '模糊客服诉求不自动转接',
    message: '客服能处理这个吗？',
    pass: answer => !answer.includes('已为您排队') && !answer.includes('正在为您转接')
      && !answer.includes('已为您接入')
  }
];

function ask(message) {
  return new Promise(resolve => {
    const ws = new WebSocket('ws://127.0.0.1:8080/ws/chat');
    const chunks = [];
    let finished = false;
    const timer = setTimeout(() => finish('TIMEOUT'), 60000);
    function finish(error = null) {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      try { ws.close(); } catch (ignored) {}
      resolve({ answer: chunks.join(''), error });
    }
    ws.addEventListener('open', () => ws.send(JSON.stringify({ message })));
    ws.addEventListener('message', async event => {
      try {
        const text = typeof event.data === 'string' ? event.data : await event.data.text();
        const frame = JSON.parse(text);
        if (frame.type === 'chunk') chunks.push(frame.content || '');
        else if (frame.type === 'done') finish();
        else if (frame.type === 'error') finish(frame.content || 'ERROR');
      } catch (error) {
        finish(`BAD_FRAME:${error.message}`);
      }
    });
    ws.addEventListener('error', () => finish('WS_ERROR'));
    ws.addEventListener('close', () => { if (!finished) finish('WS_CLOSED'); });
  });
}

let failed = false;
for (const scenario of scenarios) {
  const result = await ask(scenario.message);
  const passed = !result.error && scenario.pass(result.answer);
  failed ||= !passed;
  console.log(`[${passed ? 'PASS' : 'FAIL'}] ${scenario.name}`);
  console.log(`Q: ${scenario.message}`);
  console.log(`A: ${result.answer || '(空)'}${result.error ? ` [${result.error}]` : ''}`);
}
console.log(`RESULT: ${failed ? 'FAIL' : 'PASS'}`);
if (failed) process.exitCode = 1;
