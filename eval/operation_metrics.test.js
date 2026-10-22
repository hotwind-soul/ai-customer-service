const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
let options;
vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/admin-app.js','utf8'), {
 Vue:{createApp(value){options=value;return {config:{globalProperties:{}},mount(){}};}}, AICS:{}, console
});
const methods=options.methods;
test('missing metrics stay distinct from measured zero',()=>{
 assert.equal(methods.reportRate(null),'暂无数据');
 assert.equal(methods.reportRate(undefined),'暂无数据');
 assert.equal(methods.reportRate(0),'0.0%');
 assert.equal(methods.reportRate(2.24),'2.2%');
 assert.equal(methods.reportDuration(null),'暂无数据');
 assert.equal(methods.reportDuration(0),'0 毫秒');
 assert.equal(methods.reportDuration(1850),'1.85 秒');
});
test('late response for old date range never overwrites newer report',async()=>{
 let resolveOld; const pending=new Promise(resolve=>resolveOld=resolve);
 const context={range:'?old',reportQuery(){return this.range;},api(url){return url.includes('?old')?pending:Promise.resolve(url);},loadReportRealtime:async()=>{}};
 const old=methods.loadReport.call(context);
 context.range='?new';
 await methods.loadReport.call(context);
 resolveOld('old data'); await old;
 assert.equal(context.report,'/report/overview?new');
 assert.equal(context.daily,'/report/daily?new');
});
test('realtime refresh is separate from date filters and releases loading after failure',async()=>{
 let calls=0; const context={reportRealtimeLoading:false,reportRealtime:{queueOverdue:2},showToast(){},async api(url){assert.equal(url,'/report/realtime');calls++;throw new Error('offline');}};
 await methods.loadReportRealtime.call(context);
 assert.equal(calls,1); assert.equal(context.reportRealtimeLoading,false);
 assert.equal(context.reportRealtime.queueOverdue,2);
});
