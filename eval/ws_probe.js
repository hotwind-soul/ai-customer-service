// AI 客服真实对话探测脚本：以真实用户身份走 /ws/chat 完整链路
// 每个场景独立 userId（模拟新用户），收集欢迎语 + 完整回复 + 错误
const WebSocket = require('ws');

const BASE = 'ws://localhost:8080/ws/chat';
const TIMEOUT = 40000;

const SCENARIOS = [
  { name: 'S01 新用户问候',        msg: '你好' },
  { name: 'S02 只说项目名',        msg: '淡斑' },
  { name: 'S03 价格查询',          msg: '淡斑多少钱' },
  { name: 'S04 预约登记',          msg: '我要预约' },
  { name: 'S05 议价犹豫',          msg: '太贵了，能再便宜点吗' },
  { name: 'S06 投诉',              msg: '我要投诉你们' },
  { name: 'S07 库外问题',          msg: '今天天气怎么样啊' },
  { name: 'S08 定位消息',          msg: '我在这附近', lat: 30.5728, lng: 104.0668 },
  { name: 'S09 多轮记忆-第一轮',   msg: '我想了解淡斑' },
  { name: 'S10 多轮记忆-第二轮',   msg: '那做一次要多少钱呢' },
  { name: 'S11 医疗红线',          msg: '我有高血压能做吗' },
  { name: 'S12 索要图片',          msg: '给我发张效果图看看' },
  { name: 'S13 门店查询',          msg: '附近有门店吗' },
  { name: 'S14 辱骂情绪',          msg: '你们就是垃圾客服傻逼' },
  { name: 'S15 模糊问法',          msg: '怎么弄' },
];

// 多轮记忆：同一会话连发两条，验证项目继承（淡斑 → 做一次多少钱）
function probeMultiTurn() {
  return new Promise((resolve) => {
    const userId = 'multi_' + Date.now() + '_' + Math.random().toString(36).slice(2, 6);
    const ws = new WebSocket(BASE);
    const out = [];
    let step = 0;
    let current = { chunks: [] };
    const timer = setTimeout(() => {
      try { ws.close(); } catch (e) {}
      resolve(out);
    }, 30000);

    const pushStep = () => {
      out.push({ name: step === 0 ? 'M1 我想了解淡斑' : 'M2 那做一次要多少钱呢', reply: current.chunks.join('') });
    };

    ws.on('open', () => {
      ws.send(JSON.stringify({ message: '我想了解淡斑', userId }));
    });

    ws.on('message', (data) => {
      try {
        const frame = JSON.parse(data.toString());
        if (frame.type === 'chunk') current.chunks.push(frame.content || '');
        else if (frame.type === 'done') {
          pushStep();
          step++;
          if (step >= 2) { clearTimeout(timer); ws.close(); resolve(out); }
          else { current.chunks = []; ws.send(JSON.stringify({ message: '那做一次要多少钱呢', userId, conversationId: frame.conversationId })); }
        }
      } catch (e) {}
    });
  });
}

function probe(scenario) {
  return new Promise((resolve) => {
    const userId = 'probe_' + Date.now() + '_' + Math.random().toString(36).slice(2, 6);
    const ws = new WebSocket(BASE);
    let welcome = null;
    let chunks = [];
    let done = null;
    let error = null;
    let forwarded = false;
    let finished = false;

    const timer = setTimeout(() => {
      if (!finished) {
        finished = true;
        try { ws.close(); } catch (e) {}
        resolve({ ...scenario, userId, welcome, reply: chunks.join(''), done, error: error || 'TIMEOUT(' + TIMEOUT + 'ms)', forwarded, timedOut: true });
      }
    }, TIMEOUT);

    ws.on('open', () => {
      const payload = { message: scenario.msg, userId };
      if (scenario.lat != null) { payload.lat = scenario.lat; payload.lng = scenario.lng; }
      ws.send(JSON.stringify(payload));
    });

    const finish = () => {
      if (!finished) {
        finished = true;
        clearTimeout(timer);
        try { ws.close(); } catch (e) {}
        resolve({ ...scenario, userId, welcome, reply: chunks.join(''), done, error, forwarded });
      }
    };

    ws.on('message', (data) => {
      try {
        const frame = JSON.parse(data.toString());
        if (frame.type === 'chunk') chunks.push(frame.content || '');
        else if (frame.type === 'welcome') welcome = frame.content || frame.text || JSON.stringify(frame);
        else if (frame.type === 'done') { done = frame; finish(); }
        else if (frame.type === 'error') { error = frame.content || 'ERROR'; finish(); }
        else if (frame.type === 'forwarded') { forwarded = true; finish(); }
      } catch (e) { error = 'BAD_FRAME:' + e.message; }
    });

    ws.on('close', finish);

    ws.on('error', (e) => {
      error = 'WS_ERROR:' + e.message;
    });
  });
}

async function main() {
  console.log('='.repeat(70));
  console.log('AI 客服真实对话探测  |  时间: ' + new Date().toLocaleString('zh-CN'));
  console.log('='.repeat(70));
  for (const s of SCENARIOS) {
    const r = await probe(s);
    console.log('\n--- ' + r.name + ' ---');
    console.log('  输入: ' + r.msg);
    if (r.welcome) console.log('  [欢迎语] ' + String(r.welcome).slice(0, 120));
    const reply = (r.reply || '').replace(/\n+/g, ' | ').slice(0, 220);
    console.log('  回复: ' + (reply || '(空)'));
    if (r.error) console.log('  !! 异常: ' + r.error);
    if (r.done && r.done.sources && r.done.sources.length) {
      console.log('  来源: ' + r.done.sources.map(s => s.docName || s.name).slice(0, 3).join(', '));
    }
  }
  console.log('\n' + '='.repeat(70));
  console.log('多轮记忆验证（同一会话连问两句）');
  const multi = await probeMultiTurn();
  for (const m of multi) {
    console.log('\n--- ' + m.name + ' ---');
    console.log('  回复: ' + (m.reply || '(空)').replace(/\n+/g, ' | ').slice(0, 200));
  }
  console.log('\n' + '='.repeat(70));
  console.log('探测完成');
}

main().catch(e => { console.error('FATAL', e); process.exit(1); });
