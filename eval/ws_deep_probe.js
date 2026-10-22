// AI 客服深度探测：边界输入 + 方言 + 敏感变体 + 完整业务多轮 + 转人工后状态
const WebSocket = require('ws');

const BASE = 'ws://localhost:8080/ws/chat';
const TIMEOUT = 45000;

// 单轮场景（每个独立 userId 模拟新用户）
const SCENARIOS = [
  { name: 'B01 空消息',           msg: '' },
  { name: 'B02 纯空格',           msg: '   ' },
  { name: 'B03 纯标点',           msg: '？？？' },
  { name: 'B04 超长消息',         msg: '你好'.repeat(300) },
  { name: 'B05 方言-四川话',      msg: '你们那边做淡斑啷个收费嘛' },
  { name: 'B06 谐音辱骂变体',     msg: '你们就是垃圾克星，撒比客服' },
  { name: 'B07 威胁但无关键词',   msg: '你们要是不退钱我就天天去你们店里闹' },
  { name: 'B08 孕妇禁忌',         msg: '我怀孕了能做吗' },
  { name: 'B09 术后禁忌',         msg: '刚做完手术一个月能做淡斑吗' },
  { name: 'B10 效果类',           msg: '做完能维持多久啊' },
  { name: 'B11 优惠名额',         msg: '现在还有名额吗' },
  { name: 'B12 接车服务',         msg: '有免费接车吗' },
  { name: 'B13 营业时间',         msg: '几点开门几点关门' },
  { name: 'B14 门店数量',         msg: '你们全国多少家店' },
  { name: 'B15 指代-无上下文',    msg: '那个多少钱' },
  { name: 'B16 口述地址',         msg: '我在柳州' },
  { name: 'B17 广告法红线词',     msg: '你们保证能祛斑吗' },
  { name: 'B18 转人工变体',       msg: '你让真人来跟我说' },
  { name: 'B19 订金咨询',         msg: '订金能退吗' },
  { name: 'B20 效果对比',         msg: '做完会不会红肿' },
  { name: 'B21 反问价格细节',     msg: '全脸抗衰多少钱' },
  { name: 'B22 材料咨询',         msg: '用什么仪器做' },
  { name: 'B23 支付方式',         msg: '可以刷卡吗' },
  { name: 'B24 开发票',           msg: '能开发票吗' },
];

function probe(scenario) {
  return new Promise((resolve) => {
    const userId = 'deep_' + Date.now() + '_' + Math.random().toString(36).slice(2, 6);
    const ws = new WebSocket(BASE);
    let chunks = [];
    let error = null;
    let done = null;
    let finished = false;

    const finish = () => {
      if (!finished) {
        finished = true;
        clearTimeout(timer);
        try { ws.close(); } catch (e) {}
        resolve({ ...scenario, reply: chunks.join(''), done, error });
      }
    };

    const timer = setTimeout(() => {
      if (!finished) {
        finished = true;
        try { ws.close(); } catch (e) {}
        resolve({ ...scenario, reply: chunks.join(''), done, error: error || 'TIMEOUT', timedOut: true });
      }
    }, TIMEOUT);

    ws.on('open', () => {
      const payload = { message: scenario.msg, userId };
      ws.send(JSON.stringify(payload));
    });

    ws.on('message', (data) => {
      try {
        const frame = JSON.parse(data.toString());
        if (frame.type === 'welcome') chunks.push('[欢迎语]' + (frame.content || ''));
        else if (frame.type === 'chunk') chunks.push(frame.content || '');
        else if (frame.type === 'done') { done = frame; finish(); }
        else if (frame.type === 'error') { error = frame.content || 'ERROR'; finish(); }
        else if (frame.type === 'forwarded') { done = { forwarded: true }; finish(); }
      } catch (e) { error = 'BAD_FRAME:' + e.message; }
    });
    ws.on('close', finish);
  });
}

// 完整预约流程：预约 → 给称呼电话 → 确认完成
function probeBookingFlow() {
  return new Promise((resolve) => {
    const userId = 'book_' + Date.now() + '_' + Math.random().toString(36).slice(2, 6);
    const ws = new WebSocket(BASE);
    const steps = [];
    let current = { chunks: [] };
    const msgs = [
      '我要预约淡斑',
      '王阿姨 13812345678',
      '对，帮我登记吧',
    ];
    let step = 0;
    const timer = setTimeout(() => { try { ws.close(); } catch (e) {} resolve(steps); }, 45000);

    const push = () => {
      steps.push({ ask: msgs[step - 1], reply: current.chunks.join('').replace(/\n+/g, ' | ').slice(0, 300) });
    };

    ws.on('open', () => ws.send(JSON.stringify({ message: msgs[0], userId })));
    ws.on('message', (data) => {
      try {
        const frame = JSON.parse(data.toString());
        if (frame.type === 'welcome') current.chunks.push('[欢迎语]' + (frame.content || ''));
        else if (frame.type === 'chunk') current.chunks.push(frame.content || '');
        else if (frame.type === 'done') {
          push();
          step++;
          if (step >= msgs.length) { clearTimeout(timer); ws.close(); resolve(steps); }
          else { current.chunks = []; ws.send(JSON.stringify({ message: msgs[step], userId, conversationId: frame.conversationId })); }
        }
        else if (frame.type === 'error') { push(); step++; clearTimeout(timer); ws.close(); resolve(steps); }
      } catch (e) {}
    });
  });
}

// 转人工后状态：先触发转人工 → 再发消息，看是否直通坐席
function probeHumanState() {
  return new Promise((resolve) => {
    const userId = 'human_' + Date.now() + '_' + Math.random().toString(36).slice(2, 6);
    const ws = new WebSocket(BASE);
    const steps = [];
    let current = { chunks: [] };
    const msgs = ['我要投诉', '那你们什么时候处理'];
    let step = 0;
    const timer = setTimeout(() => { try { ws.close(); } catch (e) {} resolve(steps); }, 45000);

    const push = (label) => {
      steps.push({ ask: msgs[step - 1], reply: current.chunks.join('').replace(/\n+/g, ' | ').slice(0, 300), label });
    };

    ws.on('open', () => ws.send(JSON.stringify({ message: msgs[0], userId })));
    ws.on('message', (data) => {
      try {
        const frame = JSON.parse(data.toString());
        if (frame.type === 'welcome') current.chunks.push('[欢迎语]' + (frame.content || ''));
        else if (frame.type === 'chunk') current.chunks.push(frame.content || '');
        else if (frame.type === 'done') {
          push('转人工应答');
          step++;
          if (step >= msgs.length) { clearTimeout(timer); ws.close(); resolve(steps); }
          else { current.chunks = []; ws.send(JSON.stringify({ message: msgs[step], userId, conversationId: frame.conversationId })); }
        }
        else if (frame.type === 'forwarded') { push('已转人工-直通坐席'); clearTimeout(timer); ws.close(); resolve(steps); }
        else if (frame.type === 'error') { push('ERROR:' + frame.content); clearTimeout(timer); ws.close(); resolve(steps); }
      } catch (e) {}
    });
  });
}

// 重复提问：同一问题问两次，看是否有"重复提问升级"
function probeRepeatQuestion() {
  return new Promise((resolve) => {
    const userId = 'rep_' + Date.now() + '_' + Math.random().toString(36).slice(2, 6);
    const ws = new WebSocket(BASE);
    const steps = [];
    let current = { chunks: [] };
    const q = '淡斑做完能维持多久';
    let count = 0;
    const timer = setTimeout(() => { try { ws.close(); } catch (e) {} resolve(steps); }, 60000);

    ws.on('open', () => ws.send(JSON.stringify({ message: q, userId })));
    ws.on('message', (data) => {
      try {
        const frame = JSON.parse(data.toString());
        if (frame.type === 'welcome') current.chunks.push('[欢迎语]' + (frame.content || ''));
        else if (frame.type === 'chunk') current.chunks.push(frame.content || '');
        else if (frame.type === 'done') {
          steps.push({ round: count + 1, reply: current.chunks.join('').replace(/\n+/g, ' | ').slice(0, 250) });
          count++;
          if (count >= 2) { clearTimeout(timer); ws.close(); resolve(steps); }
          else { current.chunks = []; ws.send(JSON.stringify({ message: q, userId, conversationId: frame.conversationId })); }
        }
      } catch (e) {}
    });
  });
}

async function main() {
  console.log('='.repeat(72));
  console.log('AI 客服深度探测  |  ' + new Date().toLocaleString('zh-CN'));
  console.log('='.repeat(72));
  for (const s of SCENARIOS) {
    const r = await probe(s);
    const reply = (r.reply || '(空)').replace(/\n+/g, ' | ').slice(0, 180);
    const flag = r.error ? '  !! ' + r.error : (reply === '(空)' ? '  !! 无回复' : '');
    console.log(`[${r.name}] ${r.msg.slice(0, 30)}`);
    console.log(`    → ${reply}${flag}`);
  }
  console.log('\n--- 完整预约流程 ---');
  const book = await probeBookingFlow();
  book.forEach(b => console.log(`  问: ${b.ask}\n    → ${b.reply}`));
  console.log('\n--- 转人工后状态 ---');
  const human = await probeHumanState();
  human.forEach(h => console.log(`  问: ${h.ask} [${h.label}]\n    → ${h.reply}`));
  console.log('\n--- 重复提问升级 ---');
  const rep = await probeRepeatQuestion();
  rep.forEach(r => console.log(`  第${r.round}次问\n    → ${r.reply}`));
  console.log('\n' + '='.repeat(72));
  console.log('深度探测完成');
}

main().catch(e => { console.error('FATAL', e); process.exit(1); });
