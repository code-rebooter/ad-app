#!/usr/bin/env python3
"""Verify public Google/TCL APIs in the same consuming APK, retaining every attempt."""
import argparse, collections, json, shlex, ssl, subprocess, time, urllib.request
from pathlib import Path
from urllib.parse import parse_qs

p = argparse.ArgumentParser()
p.add_argument('--serial', required=True); p.add_argument('--run', required=True)
p.add_argument('--engine', choices=['google','tcl'], required=True); p.add_argument('--kind', default='local_pod')
p.add_argument('--output', type=Path, required=True); p.add_argument('--package', default='com.smart.smoke.ima.devicevalidation')
p.add_argument('--expect', default='completed'); p.add_argument('--reason'); p.add_argument('--wait', type=int, default=150)
p.add_argument('--physical', type=int, default=0); p.add_argument('--reported', default='1'); p.add_argument('--audit', type=int, default=1)
p.add_argument('--delay', type=int, default=0); p.add_argument('--startup', type=int, default=35000); p.add_argument('--load', type=int, default=45000)
p.add_argument('--callback', type=int, default=180); p.add_argument('--command'); p.add_argument('--command-after', type=int, default=2)
p.add_argument('--resume-after', type=int, default=0); p.add_argument('--direct', action='store_true')
p.add_argument('--real-patch', action='store_true'); p.add_argument('--local-seed', action='store_true')
p.add_argument('--disable-patch', action='store_true'); p.add_argument('--release-on-completion', action='store_true')
p.add_argument('--hidden', action='store_true'); p.add_argument('--fake-ctr', type=int, default=0)
p.add_argument('--port', type=int, default=19443)
p.add_argument('--missing-config', action='store_true'); p.add_argument('--deny', action='store_true')
p.add_argument('--final-physical', type=int); p.add_argument('--screenshot', action='store_true')
a = p.parse_args(); root = Path(__file__).resolve().parents[3]
adb = '/Users/zy/Library/Android/sdk/platform-tools/adb'; base = 'https://127.0.0.1:'+str(a.port)
out = a.output / a.run; out.mkdir(parents=True, exist_ok=False)
(out/'arguments.json').write_text(json.dumps(vars(a), default=str, indent=2))
def shell(args):
    return subprocess.run([adb,'-s',a.serial,'shell',shlex.join(args)], capture_output=True,text=True,timeout=20)
def events():
    result=[]
    source=out/'live-server-events.jsonl'
    if not source.exists():source=a.output/'server-events.jsonl'
    for line in source.read_text().splitlines():
        if a.run not in line:continue
        try:
            e=json.loads(line)
            if e.get('run_id')==a.run:result.append(e)
        except ValueError:pass
    return result
context=ssl.create_default_context(cafile=str(root/'ad-sdk-tcl/google-ima/src/test/consumer-smoke/src/debug/res/raw/fixture_ca.pem'))
request=urllib.request.Request(base+'/__case',data=json.dumps({'run_id':a.run,'kind':a.kind,'delay':a.delay}).encode(),headers={'Content-Type':'application/json'})
with urllib.request.urlopen(request,context=context,timeout=10):pass
activity='com.smart.smoke.'+('DeviceValidationActivity' if a.engine=='google' else 'TclDeviceValidationActivity')
command=['am','start','-n',a.package+'/'+activity,'--es','run_id',a.run,'--es','fixture_base',base,
         '--ez','physical',str(bool(a.physical)).lower(),'--ez','audit',str(bool(a.audit)).lower(),
         '--ez','hidden',str(a.hidden).lower(),'--el','callback_seconds',str(a.callback),
         '--ez','use_real_patch',str(a.real_patch).lower(),'--ez','provider',str(not a.direct).lower(),
         '--ez','disable_patch',str(a.disable_patch).lower(),'--ez','release_on_completion',str(a.release_on_completion).lower()]
command += ['--ez','deny',str(a.deny).lower()]
seed=(base+'/vast?run_id='+a.run if a.local_seed else
      'https://pubads.g.doubleclick.net/gampad/ads?iu=/21775744923/external/omid_ad_samples&env=vp&gdfp_req=1&output=vast&sz=640x480&unviewed_position_start=1&correlator=&fixture_run='+a.run)
config={'ad_tag_url':seed,'ad_load_timeout_ms':a.load,'ad_startup_timeout_ms':a.startup,'enable_fake_ctr':a.fake_ctr}
if a.reported!='missing':config['reported_sound_mode']=json.loads(a.reported)
if a.missing_config:config={}
command+=['--es','gam_config',json.dumps(config)]
start=time.monotonic(); launched=shell(command)
(out/'launch.txt').write_text(launched.stdout+launched.stderr)
if launched.returncode or 'Error:' in launched.stdout+launched.stderr:
    raise SystemExit('Device activity launch failed; see '+str(out/'launch.txt'))
print('START',a.engine,a.run,a.kind,flush=True)
sent=False; resumed=False; triggered=None; terminal=None; captured=False
while time.monotonic()-start<a.wait:
    es=events()
    terminals=[e for e in es if e['kind']=='terminal']
    if terminals:
        terminal=terminals[-1]['body'];break
    reports=[e for e in es if e['kind']=='report']
    started=any(e['body'].get('event_type')=='AD_STARTED' for e in reports)
    if a.screenshot and started and not captured:
        captured=True
        screen=subprocess.run([adb,'-s',a.serial,'exec-out','screencap','-p'],capture_output=True,timeout=20)
        if screen.returncode==0:(out/'playback.png').write_bytes(screen.stdout)
    patch_seen=any(e['kind']=='patch' for e in es)
    ready=(any(e['kind']=='vast_body_prefix' for e in es) if a.command=='cancel_download' else
           started if a.command not in ('cancel_patch','cancel_address') else patch_seen if a.command=='cancel_patch' else any(e['kind']=='address' for e in es))
    if a.command and not sent and ready:
        if triggered is None:triggered=time.monotonic()
        if time.monotonic()-triggered>=a.command_after:
            op='release_twice' if a.command.startswith('cancel_') else a.command
            result=shell(['am','start','-n',a.package+'/'+activity,'--es','command',op])
            (out/'command.txt').write_text(result.stdout+result.stderr);sent=True;triggered=time.monotonic()
    if sent and a.command=='pause' and a.resume_after and not resumed and time.monotonic()-triggered>=a.resume_after:
        shell(['am','start','-n',a.package+'/'+activity,'--es','command','resume']);resumed=True
    time.sleep(1)
# Require the host's deferred cleanup evidence and allow pending tracking/report HTTP to arrive.
deadline=time.monotonic()+35
while time.monotonic()<deadline:
    es=events()
    reports_ready=('AD_ERROR' if a.expect=='failed' or a.missing_config or a.deny else 'AD_COMPLETED' if a.expect=='completed' else None)
    report_ready=reports_ready is None or any(e['kind']=='report' and e['body'].get('event_type')==reports_ready for e in es)
    batch_required=a.engine=='google' and bool(a.audit) or a.engine=='tcl' and any(e['kind']=='report' and e['body'].get('event_type')=='AD_LOADED' for e in es)
    batch_ready=not batch_required or any(e['kind']=='batch_received' for e in es)
    if terminal and report_ready and batch_ready and any(e['kind']=='cleanup' for e in es) and (not a.audit or any(e['kind']=='audit' for e in es)):
        time.sleep(2);break
    time.sleep(1)
es=events();(out/'server-events.jsonl').write_text(''.join(json.dumps(e,ensure_ascii=False)+'\n' for e in es))
local=shell(['run-as',a.package,'cat','files/device-validation/'+a.run+'/events.jsonl'])
(out/'events.jsonl').write_text(local.stdout)
reports=[e['body'].get('event_type') for e in es if e['kind']=='report']
traces=[e['body'] for e in es if e['kind']=='trace']
patches=[e for e in es if e['kind']=='patch'];vasts=[e for e in es if e['kind']=='vast']
checks={};checks['expected_terminal']=terminal is not None and terminal.get('outcome')==a.expect
checks['one_terminal']=sum(e['kind']=='terminal' for e in es)==1
cleanup=[e['body'] for e in es if e['kind']=='cleanup']
checks['cleanup_observed']=len(cleanup)==1
if cleanup:
    checks['container_cleared']=cleanup[-1].get('child_count')==0
    checks['engine_released']=cleanup[-1].get('session_released') is True if a.engine=='google' else cleanup[-1].get('owner_released') is True
    if a.engine=='tcl' and any(e=='AD_LOADED' for e in reports):checks['native_released']=cleanup[-1].get('native_released') is True
address_error=a.kind.startswith('address_')
expected_requested=1 if a.engine=='google' or (not a.direct and a.kind!='address_timeout' and a.command!='cancel_address') else 0
checks['request_evidence']=reports.count('AD_REQUESTED')==expected_requested
expected_patches=(0 if address_error or a.command=='cancel_address' or a.real_patch or a.disable_patch or a.missing_config or a.deny else 2 if a.kind=='guarantee' else 1)
checks['patch_count']=len(patches)==expected_patches
for e in patches:
    b=e['body'];checks['patch_identity_'+str(patches.index(e))]=b.get('request_id')==a.run and b.get('sdk_version')==('3.35.1' if a.engine=='google' else '2.8.02') and bool(b.get('rdid'))
    if a.engine=='google' and not a.local_seed:
        from urllib.parse import urlsplit
        q=parse_qs(urlsplit(b['ad_tag_url']).query,keep_blank_values=True)
        checks['core_gam_parameters']=bool(q.get('sdkv')) and len(b['ad_tag_url'])>400
        checks['frozen_sound_mode']=b.get('reported_sound_mode')==(0 if a.reported in ('0','"0"') else 1)
if a.real_patch:
    checks['real_patch_ready']=any(e.get('event_type')=='AD_GAM_PATCH_READY' for e in traces) if a.engine=='google' else any(e['kind']=='execution' and e['body'].get('event')=='patch_ready' for e in es)
if a.audit:checks['one_audit']=sum(e['kind']=='audit' for e in es)==1
else:checks['audit_disabled']=not any(e['kind']=='audit' or (a.engine=='google' and e['kind']=='trace') for e in es)
if a.missing_config or a.deny:
    checks['skipped_without_false_fill']='AD_FILLED' not in reports and reports.count('AD_ERROR')==1 and not vasts
elif a.expect=='completed':
    for name in ['AD_FILLED','AD_LOADED','AD_STARTED','AD_COMPLETED']:checks[name.lower()+'_once']=reports.count(name)==1
    checks['no_error']='AD_ERROR' not in reports
    audits=[e['body'].get('audit',{}) for e in es if e['kind']=='audit']
    if a.audit and audits:
        audit=audits[-1]
        if a.engine=='google':
            playback=audit.get('ima_playback_audit',{})
            checks['physical_player_volume']=playback.get('last_physical_player_volume')==float(a.physical if a.final_physical is None else a.final_physical)
            checks['measurement_settled']=playback.get('measurement_incomplete') is False
        else:
            checks['physical_player_volume']=audit.get('last_player_snapshot',{}).get('configured_volume_percentage')==(100 if a.physical else 0)
            if a.final_physical is not None:
                probes=[e['body'] for e in es if e['kind']=='host_volume_probe']
                checks['dynamic_native_delegate_volume']=len(probes)==1 and probes[0].get('before_volume')==float(a.physical) and probes[0].get('delegate_volume')==float(a.final_physical) and 'probe_error' not in probes[0]
        if a.kind=='local_pod':
            segments=audit.get('playback',{}).get('segments',[]) if a.engine=='tcl' else audit.get('ima_playback_audit',{}).get('ad_instances',[])
            checks['exactly_three_ad_instances']=len(segments)==3
            if a.engine=='tcl':checks['all_three_segments_completed']=all(e.get('terminal_event')=='COMPLETED' and e.get('max_position_ms',-1)>1000 for e in segments) and len(segments)==3
        if a.kind=='wrapper':checks['wrapper_xml_chain']=audit.get('vast_response_count',0)>=2
        if a.kind=='fallback':checks['webview_fallback_observed']=audit.get('web_vast_response_count',0)>0
        if a.kind=='long':
            position=audit.get('ima_playback_audit',{}).get('max_ad_position_ms',-1) if a.engine=='google' else max((s.get('max_position_ms',-1) for s in audit.get('playback',{}).get('segments',[])),default=-1)
            checks['long_progress_beyond_request_deadline']=position>195000 and position>a.callback*1000
    tracks=[e for e in es if e['kind']=='tracking']
    if a.kind in ('local_pod','local_single','buffer_resume'):
        count=3 if a.kind=='local_pod' else 1
        checks['all_media_served']=all(any(e['kind']=='media' and e.get('path')=='/ad-'+str(i)+'.mp4' for e in es) for i in range(1,count+1))
        checks['all_ad_tracking_lifecycles']=all(any(e.get('event')==t and e.get('query',{}).get('ad')==[str(i)] for e in tracks) for i in range(1,count+1) for t in ['impression','start','firstQuartile','midpoint','thirdQuartile','complete'])
    if a.engine=='google' and a.kind not in ('local_pod','short','pod','guarantee') and a.audit:
        om=[]
        for e in es:
            if e['kind']=='omid':
                try:om.append(json.loads(e['message'].split('::',1)[-1]))
                except ValueError:pass
        om=[e for e in om if isinstance(e,dict)]
        types=collections.Counter(e.get('type') for e in om)
        checks['independent_omid_lifecycle']=all(types[t]>=1 for t in ['sessionStart','loaded','impression','start','firstQuartile','midpoint','thirdQuartile','complete','sessionFinish'])
        volume=0 if a.reported in ('0','"0"') else 1
        starts=[e for e in om if e.get('type')=='start']
        checks['independent_omid_volume']=bool(starts) and all(e.get('data',{}).get(k)==volume for e in starts for k in ['deviceVolume','mediaPlayerVolume','videoPlayerVolume'])
        av=[parse_qs(e['query'].get('acvw',[''])[0]) for e in es if e['kind']=='activeview']
        checks['activeview_volume']=bool(av) and all(v.get(k)==[str(volume)] for v in av for k in ['a','mv','nv'])
    if a.kind=='buffer_resume':checks['stall_recovered']=any(e['kind']=='media_stall_end' for e in es)
elif a.expect=='failed':
    checks['error_once']=reports.count('AD_ERROR')==1
    checks['no_false_completion']='AD_COMPLETED' not in reports
    if a.kind.startswith(('patch_','address_')):checks['no_vast_after_failure']=not vasts
    if a.kind in ('empty','invalid','wrapper_empty','inline_empty') or a.kind.startswith(('patch_','address_')):checks['no_false_fill']='AD_FILLED' not in reports
elif a.expect.startswith('cancelled'):
    checks['no_completion_after_cancel']='AD_COMPLETED' not in reports
    checks['no_error_after_cancel']='AD_ERROR' not in reports
    if a.command in ('cancel_patch','cancel_address'):checks['cancel_prevents_vast']=not vasts
    if a.command=='cancel_download':
        checks['cancelled_during_response_body']=any(e['kind']=='vast_body_prefix' for e in es) and any(e['kind']=='vast_body_peer_closed' for e in es)
        checks['no_fill_after_partial_xml']='AD_FILLED' not in reports
diagnostics=[e['body'] for e in es if e['kind']=='diagnostics']
if a.reason:
    details=json.dumps(diagnostics if a.engine=='tcl' else [e['body'] for e in es if e['kind']=='report'])
    checks['specific_terminal_reason']=a.reason in details
verdict={'run_id':a.run,'engine':a.engine,'serial':a.serial,'package':a.package,'kind':a.kind,'outcome':terminal,'elapsed_seconds':round(time.monotonic()-start,2),'checks':checks,'reports':reports,'passed':all(checks.values())}
(out/'verification.json').write_text(json.dumps(verdict,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(verdict,ensure_ascii=False),flush=True)
raise SystemExit(0 if verdict['passed'] else 1)
