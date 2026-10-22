// 临时诊断：发 3 个不同"失败类"问题，打印原始回复，判断是否完全相同（相同=用户问题在 prompt 里为空）
const WebSocket = require('ws');
function ask(msg){
  return new Promise((resolve)=>{
    const ws = new WebSocket('ws://127.0.0.1:8080/ws/chat');
    let chunks=[], done=null, finished=false;
    const t=setTimeout(()=>{if(!finished){finished=true;try{ws.close()}catch(e){}resolve({reply:chunks.join(''),err:'TIMEOUT'})}},45000);
    ws.on('open',()=>ws.send(JSON.stringify({message:msg,userId:'diag_'+Date.now()})));
    ws.on('message',(d)=>{try{const f=JSON.parse(d.toString());if(f.type==='chunk')chunks.push(f.content||'');else if(f.type==='done'){done=f;finished=true;clearTimeout(t);try{ws.close()}catch(e){}resolve({reply:chunks.join(''),done})}else if(f.type==='error'){finished=true;clearTimeout(t);try{ws.close()}catch(e){}resolve({reply:chunks.join(''),err:f.content})}}catch(e){}});
    ws.on('close',()=>{if(!finished){finished=true;clearTimeout(t);resolve({reply:chunks.join(''),err:'CLOSE'})}});
    ws.on('error',(e)=>{if(!finished){finished=true;clearTimeout(t);resolve({reply:chunks.join(''),err:'WS:'+e.message})}});
  });
}
(async()=>{
  const qs=['做完能晒太阳吗？','做完能洗脸吗？','胡子去除是什么原理？'];
  for(const q of qs){
    const r=await ask(q);
    console.log('Q: '+q);
    console.log('A: '+(r.reply||'(空)')+(r.err?'  [ERR:'+r.err+']':'')+'\n');
    await new Promise(r=>setTimeout(r,800));
  }
})();
