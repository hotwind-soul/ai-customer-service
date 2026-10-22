// 截图场景真实通道回归：预约收集手机号时追问是否扣费，应先回答问题且保留同一会话。
const ws = new WebSocket('ws://127.0.0.1:8080/ws/chat');
const turns = ['预约', '会不会动钱'];
const results = [];
let turn = 0;
let chunks = [];
let conversationId = null;
let finished = false;

const timer = setTimeout(() => finish('TIMEOUT'), 45000);

function finish(error = null) {
  if (finished) return;
  finished = true;
  clearTimeout(timer);
  try { ws.close(); } catch (ignored) {}

  for (const result of results) {
    console.log(`Q: ${result.message}`);
    console.log(`A: ${result.reply}`);
  }
  if (error) {
    console.error(error);
    process.exitCode = 1;
    return;
  }

  const first = results[0] && results[0].reply;
  const second = results[1] && results[1].reply;
  const sameConversation = results.length === 2 && results[0].conversationId
    && results[0].conversationId === results[1].conversationId;
  const passed = results.length === 2
    && sameConversation
    && first.includes('称呼') && first.includes('手机号')
    && second.includes('不会自动扣款')
    && !second.includes('不方便留电话')
    && !second.includes('转人工');
  console.log(`RESULT: ${passed ? 'PASS' : 'FAIL'}`);
  if (!passed) process.exitCode = 1;
}

function sendTurn() {
  chunks = [];
  ws.send(JSON.stringify({ message: turns[turn], conversationId }));
}

ws.addEventListener('open', sendTurn);
ws.addEventListener('message', async event => {
  try {
    const payload = typeof event.data === 'string' ? event.data : await event.data.text();
    const frame = JSON.parse(payload);
    if (frame.type === 'chunk') chunks.push(frame.content || '');
    if (frame.type === 'error') return finish(frame.content || 'ERROR');
    if (frame.type !== 'done') return;

    if (frame.conversationId) conversationId = frame.conversationId;
    results.push({ message: turns[turn], reply: chunks.join(''), conversationId });
    turn += 1;
    if (turn >= turns.length) finish();
    else sendTurn();
  } catch (error) {
    finish(`BAD_FRAME: ${error.message}`);
  }
});
ws.addEventListener('error', error => finish(`WS_ERROR: ${error.message || 'connection failed'}`));
ws.addEventListener('close', () => {
  if (!finished) finish('WS_CLOSED');
});
