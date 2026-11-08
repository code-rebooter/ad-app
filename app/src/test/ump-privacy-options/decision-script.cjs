const fs = require('fs'), path = require('path'), assert = require('assert');
const {createRequire} = require('module');
const root = path.resolve(__dirname,'../../../..');
let JSDOM;
try { ({JSDOM} = require('jsdom')); }
catch (error) {
 if (process.env.UMP_JS_MODULES) ({JSDOM} = createRequire(path.resolve(process.env.UMP_JS_MODULES,'package.json'))('jsdom'));
 else throw error;
}
const scriptsDir=path.resolve(process.argv[2]||path.join(root,'build/ump-privacy-options-tests/scripts'));
const outDir=path.resolve(process.argv[3]||path.join(root,'build/ump-privacy-options-tests/javascript'));
fs.mkdirSync(outDir,{recursive:true});
const oldTemplate=fs.readFileSync(path.join(__dirname,'decision-baseline.js'),'utf8');
const rows=[];
function env(html, options={}) {
 const dom=new JSDOM('<!doctype html><html><body>'+html+'</body></html>',{url:'https://fundingchoicesmessages.google.com/',runScripts:'outside-only'});
 const w=dom.window, state={now:0,deadline:15000,active:true,actions:[],prompts:0,...options};
 w.console={log(){}};
 Object.defineProperty(w.performance,'now',{value:()=>state.now});
 w.prompt=()=>{state.prompts++;const remaining=state.active?Math.max(0,state.deadline-state.now):0;if(state.promptDelay)state.now+=state.promptDelay;return String(remaining)};
 w.HTMLElement.prototype.getBoundingClientRect=function(){if(state.cancelOnLayout)state.active=false;return {width:120,height:32,left:0,top:0,right:120,bottom:32}};
 w.HTMLElement.prototype.scrollIntoView=function(){state.actions.push(['scroll',this.id||this.className]);};
 w.HTMLElement.prototype.focus=function(){state.actions.push(['focus',this.id||this.className]);};
 w.document.addEventListener('click',e=>{state.actions.push(['click',e.target.id||e.target.className]);if(state.cancelOnFirstClick)state.active=false},true);
 if(options.shadow){const s=w.document.getElementById('shadow-host').attachShadow({mode:'open'});s.innerHTML='<button class="fc-cta-do-not-consent">Reject all</button>';s.addEventListener('click',()=>state.actions.push(['shadow_click']),true)}
 if(options.scroll){for(const e of [w.document.body,w.document.documentElement]){Object.defineProperty(e,'scrollHeight',{value:2000});Object.defineProperty(e,'clientHeight',{value:400})}}
 if(options.expireOnLabel){const e=w.document.querySelector('button');Object.defineProperty(e,'innerText',{get(){state.now=20000;return e.textContent}})}
 return {dom,w,state};
}
function snapshot(e,result){return {result,actions:e.state.actions,checked:[...e.w.document.querySelectorAll('input')].map(x=>x.checked),scroll:[e.w.document.body.scrollTop,e.w.document.documentElement.scrollTop]}}
function execute(e,mode,isNew){return e.w.eval(isNew?fs.readFileSync(path.join(scriptsDir,'script-'+mode+'.js'),'utf8'):oldTemplate.replace('%%DECISION_MODE%%',mode))}
function equivalent(name,html,mode,{rounds=1,expected,...options}={}) {
 const old=env(html,options), fresh=env(html,options), outcomes=[];
 for(let i=0;i<rounds;i++){const a=execute(old,mode,false),b=execute(fresh,mode,true);assert.deepStrictEqual(snapshot(fresh,b),snapshot(old,a),name+' choices/operations changed');if(expected)assert(expected[i].test(b),name+': '+b);outcomes.push(b)}
 assert(fresh.state.prompts>0,'actual fence wrapper was not executed');old.dom.window.close();fresh.dom.window.close();rows.push({name,passed:true,outcomes});
}
function safety(name,html,mode,options,check){const e=env(html,options);const r=execute(e,mode,true);check(e,r);rows.push({name,passed:true,result:r,clicks:e.state.actions.filter(x=>x[0].includes('click')).length});e.dom.window.close()}
function prefs(checked){return '<div class="fc-data-preferences-dialog">'+Array.from({length:78},(_,i)=>`<input id="p${i}" class="fc-preference-consent" type="checkbox" ${checked?'checked':''}>`).join('')+'<button class="fc-confirm-choices">Confirm choices</button></div>'}
equivalent('direct ACCEPT unchanged','<button class="fc-cta-consent">Accept all</button>','ACCEPT_ALL',{expected:[/^clicked:240:/]});
equivalent('direct REJECT unchanged','<button class="fc-cta-do-not-consent">Reject all</button>','REJECT',{expected:[/^clicked:240:/]});
equivalent('manage-options score 90 unchanged','<button class="fc-cta-manage-options">Manage options</button>','REJECT',{expected:[/^clicked:90:/]});
equivalent('78 ACCEPT preferences and confirm unchanged',prefs(false),'ACCEPT_ALL',{rounds:2,expected:[/^clicked:95:set_accept_preferences:78/,/^clicked:170:/]});
equivalent('78 REJECT preferences and confirm unchanged',prefs(true),'REJECT',{rounds:2,expected:[/^clicked:95:cleared_reject_preferences:78/,/^clicked:170:/]});
equivalent('shadow-root selection unchanged','<div id="shadow-host"></div>','REJECT',{shadow:true,expected:[/^clicked:240:/]});
equivalent('fallback ranking unchanged','<button id="fallback">Agree</button>','ACCEPT_ALL',{expected:[/^clicked:/]});
equivalent('REJECT scrolling unchanged','<p>No choice here</p>','REJECT',{scroll:true,expected:[/^scrolling:/]});
safety('completed request cannot click','<button class="fc-cta-do-not-consent">Reject all</button>','REJECT',{active:false},(e,r)=>{assert.strictEqual(r,'inactive');assert.equal(e.state.actions.length,0)});
safety('completed request cannot clear consent preferences',prefs(true),'REJECT',{active:false},(e,r)=>{assert.equal(e.w.document.querySelectorAll('input:checked').length,78);assert.equal(e.state.actions.length,0)});
safety('cancellation during layout cannot click','<button class="fc-cta-consent">Accept all</button>','ACCEPT_ALL',{cancelOnLayout:true},(e,r)=>{assert.equal(e.state.actions.filter(x=>x[0]=='click').length,0)});
safety('cancellation between preference mutations stops the batch',prefs(true),'REJECT',{cancelOnFirstClick:true},(e,r)=>{assert.equal(e.state.actions.filter(x=>x[0]=='click').length,1);assert.equal(e.w.document.querySelectorAll('input:checked').length,77)});
safety('delayed renderer prompt reply does not extend deadline','<button class="fc-cta-consent">Accept all</button>','ACCEPT_ALL',{promptDelay:20000},(e,r)=>{assert.strictEqual(r,'inactive');assert.equal(e.state.actions.length,0)});
safety('deadline passed during target description cannot click','<button class="fc-cta-consent">Accept all</button>','ACCEPT_ALL',{expireOnLabel:true},(e,r)=>{assert.equal(e.state.actions.filter(x=>x[0]=='click').length,0)});
fs.writeFileSync(path.join(outDir,'summary.json'),JSON.stringify({passed:true,cases:rows.length,rows},null,2));console.log(JSON.stringify({passed:true,cases:rows.length,rows},null,2));
