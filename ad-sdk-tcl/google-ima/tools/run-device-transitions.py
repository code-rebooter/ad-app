#!/usr/bin/env python3
"""Exercise replacement, reentrant completion and cross-engine use in one R8 process."""
import argparse,json,shlex,ssl,subprocess,time,urllib.request
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--output',type=Path,required=True)
p.add_argument('--prefix',required=True);p.add_argument('--port',type=int,default=19444)
a=p.parse_args();root=Path(__file__).resolve().parents[3];base='https://127.0.0.1:'+str(a.port)
package='com.smart.smoke.ima.releasevalidation';adb='/Users/zy/Library/Android/sdk/platform-tools/adb'
context=ssl.create_default_context(cafile=str(root/'ad-sdk-tcl/google-ima/src/test/consumer-smoke/src/debug/res/raw/fixture_ca.pem'))
out=a.output/(a.prefix+'-transitions');out.mkdir(exist_ok=False)
def shell(args):return subprocess.check_output([adb,'-s',a.serial,'shell',shlex.join(args)],text=True,timeout=20)
def activity(engine):return package+'/com.smart.smoke.'+('DeviceValidationActivity' if engine=='google' else 'TclDeviceValidationActivity')
def register(run,kind='single',delay=0):
 request=urllib.request.Request(base+'/__case',data=json.dumps({'run_id':run,'kind':kind,'delay':delay}).encode(),headers={'Content-Type':'application/json'})
 with urllib.request.urlopen(request,context=context,timeout=10):pass
def events(run):
 result=[]
 source=a.output/run/'live-server-events.jsonl'
 if not source.exists():source=a.output/'server-events.jsonl'
 for line in source.read_text().splitlines():
  if run not in line:continue
  try:
   value=json.loads(line)
   if value.get('run_id')==run:result.append(value)
  except ValueError:pass
 return result
def wait(run,predicate,seconds=100):
 end=time.monotonic()+seconds
 while time.monotonic()<end:
  es=events(run)
  if predicate(es):return es
  time.sleep(.5)
 raise RuntimeError('Timed out: '+run)
def has_report(es,name):return any(e['kind']=='report' and e['body'].get('event_type')==name for e in es)
def launch(engine,run,replace=False):
 config={'ad_tag_url':'https://pubads.g.doubleclick.net/gampad/ads?iu=/21775744923/external/omid_ad_samples&env=vp&gdfp_req=1&output=vast&sz=640x480&unviewed_position_start=1&correlator=&fixture_run='+run,'ad_load_timeout_ms':45000,'ad_startup_timeout_ms':35000,'reported_sound_mode':1,'enable_fake_ctr':0}
 text=shell(['am','start','-n',activity(engine),'--es','run_id',run,'--es','fixture_base',base,'--es','gam_config',json.dumps(config),'--ez','physical','false','--ez','audit','true','--ez','replace_on_completion',str(replace).lower()])
 if 'Error:' in text:raise RuntimeError(text)
 (out/(run+'-launch.txt')).write_text(text)
def command(engine,name):shell(['am','start','-n',activity(engine),'--es','command',name])
def finished(run):
 wait(run,lambda es:any(e['kind']=='terminal' for e in es))
 time.sleep(3)
 return events(run)
def omid_complete(es):
 types=set()
 for e in es:
  if e['kind']=='omid':
   try:types.add(json.loads(e['message'].split('::',1)[-1]).get('type'))
   except (ValueError,AttributeError):pass
 return all(t in types for t in ['sessionStart','loaded','impression','start','firstQuartile','midpoint','thirdQuartile','complete','sessionFinish'])
checks={};runs=[]
initial_pid=shell(['pidof',package]).strip()
def check_success(engine,run,es):
 reports=[e['body'].get('event_type') for e in es if e['kind']=='report']
 checks[run+'_terminal']=len([e for e in es if e['kind']=='terminal' and e['body'].get('outcome')=='completed'])==1
 checks[run+'_status_once']=all(reports.count(x)==1 for x in ['AD_FILLED','AD_LOADED','AD_STARTED','AD_COMPLETED']) and 'AD_ERROR' not in reports
 checks[run+'_patch_once']=sum(e['kind']=='patch' for e in es)==1
 if engine=='google':checks[run+'_independent_measurement']=omid_complete(es)
 runs.append(run)
for engine in ['google','tcl']:
 old=a.prefix+'-'+engine+'-old';new=a.prefix+'-'+engine+'-new'
 register(old,delay=10);register(new);launch(engine,old)
 wait(old,lambda es:any(e['kind']=='patch' for e in es),30)
 launch(engine,new);wait(new,lambda es:has_report(es,'AD_STARTED'))
 command(engine,'release_previous')
 es=finished(new);check_success(engine,new,es)
 prior=events(old);runs.append(old)
 checks[old+'_cancel_once']=sum(e['kind']=='terminal' and e['body'].get('outcome','').startswith('cancelled') for e in prior)==1
 checks[old+'_no_late_vast']=not any(e['kind']=='vast' for e in prior)
 checks[old+'_no_late_completion']=not has_report(prior,'AD_COMPLETED')
 checks[old+'_server_returned_after_cancel']=any(e['kind']=='patch_ready' for e in prior)
 print('Verified replacement',engine,flush=True)

first=a.prefix+'-settlement';next_run=first+'-next'
register(first);register(next_run);launch('google',first,True)
es=finished(next_run);check_success('google',next_run,es)
old=events(first);check_success('google',first,old)
completed=next(e['body']['diagnostics'] for e in old if e['kind']=='report' and e['body'].get('event_type')=='AD_COMPLETED')
requested=next(e['body']['diagnostics'] for e in es if e['kind']=='report' and e['body'].get('event_type')=='AD_REQUESTED')
terminal=next(e['body'] for e in old if e['kind']=='terminal')
checks['reentrant_request_waited_for_settlement']=requested['requestCreatedAtMs']-completed['finishedAtMs']>=1400
checks['prior_callback_before_next_request']=terminal['callback_at_ms']<=requested['requestCreatedAtMs']
print('Verified reentrant completion',flush=True)

old=a.prefix+'-cross-tcl-cancel';register(old);launch('tcl',old)
wait(old,lambda es:has_report(es,'AD_STARTED'));command('tcl','cancel')
wait(old,lambda es:any(e['kind']=='terminal' for e in es));runs.append(old)
checks['cross_tcl_cancelled']=sum(e['kind']=='terminal' and e['body'].get('outcome')=='cancelled' for e in events(old))==1
for engine in ['google','tcl']:
 run=a.prefix+'-cross-'+engine;register(run);launch(engine,run)
 es=finished(run);check_success(engine,run,es)
 print('Verified cross-engine',engine,flush=True)
checks['one_process_for_both_engines']=bool(initial_pid) and shell(['pidof',package]).strip()==initial_pid
for run in runs:(out/(run+'-events.jsonl')).write_text(''.join(json.dumps(e,ensure_ascii=False)+'\n' for e in events(run)))
result={'serial':a.serial,'process_id':initial_pid,'runs':runs,'checks':checks,'passed':all(checks.values())}
(out/'verification.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result),flush=True)
raise SystemExit(0 if result['passed'] else 1)
