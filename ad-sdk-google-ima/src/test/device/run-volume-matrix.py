#!/usr/bin/env python3
"""Actual player -> IMA -> passive OM audit -> independent verifier on one TV.

Requires the existing local TLS fixture server and the debug consumer APK.
Every attempt gets a new directory; missing observations are explicit failures.
All creatives, tracking and verification receivers are local test fixtures.
"""
import argparse, collections, json, re, shlex, ssl, subprocess, time, traceback, urllib.request
from pathlib import Path
from urllib.parse import parse_qs

p = argparse.ArgumentParser()
p.add_argument('--serial', default='192.168.0.124:5555')
p.add_argument('--output', type=Path, required=True)
p.add_argument('--port', type=int, default=19447)
p.add_argument('--prefix', required=True)
p.add_argument('--case', action='append')
p.add_argument('--package', default='com.smart.smoke.ima.devicevalidation')
a = p.parse_args()
ROOT = Path(__file__).resolve().parents[4]
ADB = '/Users/zy/Library/Android/sdk/platform-tools/adb'
BASE = 'https://127.0.0.1:' + str(a.port)
ACTIVITY = a.package + '/com.smart.smoke.DeviceValidationActivity'
CA = ROOT/'ad-sdk-tcl/google-ima/src/test/consumer-smoke/src/debug/res/raw/fixture_ca.pem'
TLS = ssl.create_default_context(cafile=str(CA))
a.output.mkdir(parents=True, exist_ok=True)

def shell(args):
    result = subprocess.run([ADB, '-s', a.serial, 'shell', shlex.join(args)],
                            text=True, capture_output=True, timeout=25)
    if result.returncode: raise RuntimeError(result.stderr)
    return result.stdout

def device_volume(value=None):
    if value is not None: shell(['media','volume','--stream','3','--set',str(value)])
    text = shell(['media','volume','--stream','3','--get'])
    match = re.search(r'volume is (\d+) in range \[(\d+)\.\.(\d+)\]', text)
    if not match: raise RuntimeError('Cannot establish actual device volume: ' + text)
    return int(match[1]), int(match[3])

def post(path, body):
    req=urllib.request.Request(BASE+path, data=json.dumps(body).encode(),
                              headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(req, context=TLS, timeout=15) as response: return response.read()

def command(operation, value=None):
    args=['am','start','-n',ACTIVITY,'--es','command',operation]
    if value is not None: args += ['--ef','volume',str(value)]
    return shell(args)

def read_events(run):
    file=a.output/'server-events.jsonl'
    if not file.exists(): return []
    result=[]
    for line in file.read_text().splitlines():
        try:
            event=json.loads(line)
            if event.get('run_id')==run: result.append(event)
        except ValueError: pass
    return result

def verification(events):
    result=[]; seen=set()
    for item in events:
        if item.get('kind')!='omid': continue
        text=item.get('message',''); index=text.find('{"adSessionId"')
        if index<0: continue
        try: event,_=json.JSONDecoder().raw_decode(text[index:])
        except ValueError: continue
        key=json.dumps(event,sort_keys=True)
        if key not in seen: seen.add(key);result.append(event)
    return result

CASES=[
 dict(name='muted_system_audible',physical=0,reported=1,system=4),
 dict(name='audible_system_audible',physical=1,reported=0,system=4),
 dict(name='audible_system_muted',physical=1,reported=1,system=0),
 dict(name='muted_system_muted',physical=0,reported=0,system=0),
 dict(name='player_changes',physical=0,reported=1,system=4,
      actions=[(2,'volume',0.35),(5,'volume',1.0),(8,'volume',0.0)]),
 dict(name='device_changes',physical=1,reported=0,system=4,
      actions=[(3,'system',0),(7,'system',4)]),
 dict(name='paused_volume_change',physical=1,reported=0,system=4,
      actions=[(2,'pause',None),(4,'volume',0.35),(6,'resume',None)]),
 dict(name='three_ads',physical=0,reported=1,system=4,kind='local_verified_pod',ads=3),
 dict(name='three_ads_audible',physical=1,reported=0,system=4,kind='local_verified_pod',ads=3),
 dict(name='three_ads_player_changes',physical=0,reported=1,system=4,kind='local_verified_pod',ads=3,
      initial_volumes=[0,0.35,1],actions=[(15,'volume',0.35),(30,'volume',1.0),(52,'volume',0.0)]),
 dict(name='cancel',physical=0,reported=1,system=4,actions=[(3,'cancel',None)],terminal='cancelled:window_destroy'),
 dict(name='empty',physical=0,reported=1,system=4,kind='empty',terminal='failed',no_playback=True),
 dict(name='no_verifier',physical=0,reported=1,system=4,omit_verifier=True)
 ,dict(name='audit_disabled',physical=0,reported=1,system=4,audit=False,
       actions=[(2,'volume',0.0)])
]

def run_case(case):
    run=a.prefix+'-'+case['name']; folder=a.output/run
    if folder.exists(): raise RuntimeError('Refusing to overwrite attempt '+run)
    folder.mkdir(); (folder/'case.json').write_text(json.dumps(case,indent=2))
    boot_id=shell(['cat','/proc/sys/kernel/random/boot_id']).strip()
    (folder/'boot-id-before.txt').write_text(boot_id+'\n')
    system,max_volume=device_volume(min(case['system'],device_volume()[1]))
    post('/__case',dict(run_id=run,kind=case.get('kind','single'),omit_verifier=case.get('omit_verifier',False)))
    config=dict(ad_tag_url='https://pubads.g.doubleclick.net/gampad/ads?iu=/21775744923/external/omid_ad_samples&env=vp&gdfp_req=1&output=vast&sz=640x480&unviewed_position_start=1&correlator=&fixture_run='+run,
                reported_sound_mode=case['reported'],enable_fake_ctr=0,ad_load_timeout_ms=45000,ad_startup_timeout_ms=35000)
    args=['am','start','-n',ACTIVITY,'--es','run_id',run,'--es','fixture_base',BASE,
          '--es','gam_config',json.dumps(config),'--ez','physical',str(bool(case['physical'])).lower(),
          '--ez','hidden','false','--ez','audit',str(case.get('audit',True)).lower()]
    (folder/'launch.txt').write_text(shell(args)); print('START',run,flush=True)
    began=time.monotonic(); started_at=None; executed=[]; terminal=None
    while time.monotonic()-began<160:
        events=read_events(run)
        if started_at is None and any(e.get('kind')=='host_started' for e in events): started_at=time.monotonic()
        if started_at is not None:
            for index,(delay,operation,value) in enumerate(case.get('actions',[])):
                if index in executed or time.monotonic()-started_at<delay: continue
                executed.append(index)
                if operation=='system': device_volume(value)
                else: command(operation,value)
        terminals=[e for e in events if e.get('kind')=='terminal']
        if terminals:
            terminal=terminals[-1]['body'];break
        time.sleep(0.5)
    # Give the original OM finish and upload transports their actual delivery time.
    end=time.monotonic()+7
    while time.monotonic()<end: time.sleep(0.5)
    events=read_events(run); om=verification(events)
    audits=[e['body']['audit'] for e in events if e.get('kind')=='audit']
    audit=audits[-1] if audits else {}; chain=audit.get('volume_chain_audit',{})
    checks={}
    def check(name,condition): checks[name]=bool(condition)
    check('terminal',terminal is not None and terminal.get('state')==case.get('terminal','completed'))
    # The host terminal currently exposes its value under terminal or result on older fixtures.
    if terminal is not None and not checks['terminal']:
        check('terminal',case.get('terminal','completed') in [terminal.get(k) for k in ['terminal','result','status','state','outcome']])
    check('all_actions_executed',len(executed)==len(case.get('actions',[])))
    check('no_host_command_errors',not any(e.get('kind')=='host_command_error' for e in events))
    volume_actions=[value for _,operation,value in case.get('actions',[]) if operation=='volume']
    if volume_actions:
        host_values=[e['body'] for e in events if e.get('kind')=='host_volume']
        check('host_actual_volume_reads',len(host_values)==len(volume_actions) and all(
            abs(value.get('actual',-1)-expected)<0.011
            for value,expected in zip(host_values,volume_actions)))
    check('runtime_audit_received' if case.get('audit',True) else 'audit_intentionally_disabled',
          bool(audit)==case.get('audit',True))
    if case.get('no_playback'):
        check('no_false_audio_success',chain.get('status')=='unknown_missing_observation')
        check('no_false_verification',chain.get('verification_receiver_observed') is False)
        check('no_false_player_callbacks',chain.get('player_callback_count',0)==0)
    else:
        expected=float(case['physical'])
        final=next((float(v) for _,op,v in reversed(case.get('actions',[])) if op=='volume'),expected)
        patches=[e['body'] for e in events if e.get('kind')=='patch']
        vasts=[e for e in events if e.get('kind')=='vast']
        check('request_declaration_matches_initial_player',bool(patches) and patches[0].get('reported_sound_mode')==case['physical'])
        check('actual_http_vpmute',bool(vasts) and vasts[0]['query'].get('vpmute')==[str(1-case['physical'])])
        if case.get('audit',True):
            check('actual_player_snapshot',chain.get('actual_player_volume') is not None and abs(chain['actual_player_volume']-final)<0.011)
            check('IMA_real_volume_input',chain.get('ima_input_observed') and abs(chain.get('ima_input_percentage',-1)-final*100)<1.1)
            check('audit_compares_ima_input',chain.get('actual_matches_ima_input') is True)
            check('audit_compares_real_callback',chain.get('actual_matches_player_callback') is True)
            check('each_ad_has_real_volume_callback',len({e.get('media_url_sha256') for e in
                  chain.get('player_callbacks',[])})==case.get('ads',1))
        starts=[e for e in om if e.get('type')=='start']
        if case.get('omit_verifier'):
            check('verifier_was_intentionally_absent',not any(e.get('kind')=='verification' for e in events))
            check('no_claim_of_independent_delivery',chain.get('verification_receiver_observed') is False)
            check('independent_receiver_missing',not starts)
            check('missing_media_does_not_pass',chain.get('status')=='unknown_missing_observation')
        else:
            check('independent_OM_start',len(starts)==case.get('ads',1))
            initial_volumes=case.get('initial_volumes',[expected]*case.get('ads',1))
            sorted_starts=sorted(starts,key=lambda e:e['timestamp'])
            check('independent_OM_initial_player_volume',len(sorted_starts)==len(initial_volumes) and all(
                abs(e.get('data',{}).get('mediaPlayerVolume',-1)-value)<0.011
                for e,value in zip(sorted_starts,initial_volumes)))
            check('independent_OM_initial_device_volume',bool(starts) and abs(starts[0].get('data',{}).get('deviceVolume',-1)-system/max_volume)<0.011)
            if case.get('audit',True):
                check('passive_media_events_observed',chain.get('omid_media_event_count',0)>0)
                check('passive_media_equals_actual',chain.get('actual_matches_omid_media') is True)
                check('complete_to_OM_service',chain.get('complete_to_omid_service') is True)
            sequence=[]
            for event in sorted(om,key=lambda e:e['timestamp']):
                if event.get('type') not in ('start','volumeChange'): continue
                value=event.get('data',{}).get('mediaPlayerVolume')
                if not sequence or sequence[-1]!=value: sequence.append(value)
            expected_sequence=[expected]
            for _,op,value in case.get('actions',[]):
                if op=='volume' and value!=expected_sequence[-1]:expected_sequence.append(value)
            check('exact_OM_player_volume_sequence',sequence==expected_sequence)
            for value in [v for _,op,v in case.get('actions',[]) if op=='volume']:
                check('independent_OM_change_'+str(value),any(e.get('type')=='volumeChange' and abs(e.get('data',{}).get('mediaPlayerVolume',-1)-value)<0.011 for e in om))
            if case['name']=='device_changes':
                check('independent_device_mute',any(e.get('type')=='volumeChange' and e.get('data',{}).get('deviceVolume')==0 for e in om))
                check('device_change_keeps_player_volume',all(e.get('data',{}).get('mediaPlayerVolume')==1 for e in om if e.get('type')=='volumeChange'))
            expected_events=['sessionStart','impression','start','sessionFinish']
            if case.get('terminal','completed')=='completed': expected_events += ['firstQuartile','midpoint','thirdQuartile','complete']
            counts=collections.Counter(e.get('type') for e in om)
            for event in expected_events: check('OM_'+event,counts[event]==case.get('ads',1))
            per_session=collections.defaultdict(list)
            for event in om:per_session[event['adSessionId']].append(event)
            check('OM_loaded_exactly_once_in_every_session',len(per_session)==case.get('ads',1) and all(
                sum(e.get('type')=='loaded' for e in events)==1 for events in per_session.values()))
            check('OM_loaded_present_in_every_session',len(per_session)==case.get('ads',1) and all(
                any(e.get('type')=='loaded' for e in events) for events in per_session.values()))
            check('OM_duplicate_loaded_payloads_consistent',all(
                len({json.dumps(e.get('data'),sort_keys=True) for e in events if e.get('type')=='loaded'})==1
                for events in per_session.values()))
            check('OM_loaded_precedes_start_in_every_session',all(
                max(e['timestamp'] for e in events if e.get('type')=='loaded') <=
                min(e['timestamp'] for e in events if e.get('type')=='start')
                for events in per_session.values() if any(e.get('type')=='loaded' for e in events)
                and any(e.get('type')=='start' for e in events)))
            if case.get('terminal','completed').startswith('cancelled'):
                check('cancel_does_not_report_complete',counts['complete']==0)
        activeview=[parse_qs(e['query'].get('acvw',[''])[0]) for e in events if e.get('kind')=='activeview']
        check('ActiveView_HTTP_observed',bool(activeview))
        if not case.get('actions'):
            check('ActiveView_actual_audio_value',bool(activeview) and all(
                abs(float(q.get('a',['-1'])[0])-expected*system/max_volume)<0.011 for q in activeview))
        if case.get('audit',True):
            for signal in audit.get('sound_reporting_audit',[]):
                if signal.get('signal') in ['ima_core_volume_percentage','omid_device_volume',
                        'omid_media_player_volume','activeview_native_volume','activeview_media_volume']:
                    original=str(signal.get('last_physical_value','')).removeprefix('core:')
                    try: equal=abs(float(original)-float(signal.get('last_reported_value','nan')))<0.00001
                    except ValueError:equal=False
                    check('unmodified_'+signal['signal'],equal)
    boot_after=shell(['cat','/proc/sys/kernel/random/boot_id']).strip()
    (folder/'boot-id-after.txt').write_text(boot_after+'\n')
    check('device_did_not_reboot',boot_after==boot_id)
    loaded_counts=collections.Counter(e.get('adSessionId') for e in om if e.get('type')=='loaded')
    result=dict(run=run,passed=all(checks.values()),checks=checks,terminal=terminal,
                strict_loaded_once=all(count==1 for count in loaded_counts.values()) if loaded_counts else None,
                duplicate_loaded_notifications=sum(max(0,count-1) for count in loaded_counts.values()),
                independent_OM_events=om,volume_chain=chain,device_volume_start=system,
                device_volume_max=max_volume,executed_actions=executed)
    (folder/'volume-verification.json').write_text(json.dumps(result,indent=2))
    pid = shell(['pidof', a.package]).strip().split()
    if pid:
        log = subprocess.run([ADB,'-s',a.serial,'logcat','-d','--pid='+pid[0],'-v','threadtime'],
                             capture_output=True,text=True,timeout=25)
        (folder/'logcat.txt').write_text(log.stdout)
    print(('PASS' if result['passed'] else 'FAIL'),run,
          'failed='+','.join(k for k,v in checks.items() if not v),flush=True)
    return result

original=device_volume()[0]; results=[]; cleanup_error=None
try:
    for case in CASES:
        if a.case and case['name'] not in a.case: continue
        try:
            results.append(run_case(case))
        except Exception as error:
            result=dict(run=a.prefix+'-'+case['name'],passed=False,
                        checks={'infrastructure_completed':False},error=traceback.format_exc())
            results.append(result)
            folder=a.output/result['run']
            if folder.is_dir(): (folder/'infrastructure-failure.json').write_text(json.dumps(result,indent=2))
            print('FAIL',result['run'],'infrastructure='+repr(error),flush=True)
            break
finally:
    try: device_volume(original)
    except Exception: cleanup_error=traceback.format_exc()
    (a.output/(a.prefix+'-matrix.json')).write_text(json.dumps(dict(
        serial=a.serial,restored_device_volume=original if cleanup_error is None else None,
        cleanup_error=cleanup_error,passed=sum(r['passed'] for r in results),
        failed=sum(not r['passed'] for r in results),runs=results),indent=2))
raise SystemExit(0 if results and all(r['passed'] for r in results) and cleanup_error is None else 1)
