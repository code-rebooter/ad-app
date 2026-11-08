#!/usr/bin/env python3
"""Run one explicit local VAST fixture on a connected device and retain raw evidence."""
import argparse, collections, json, shlex, ssl, subprocess, time, urllib.request
from pathlib import Path
from urllib.parse import parse_qs, quote, urlsplit

parser = argparse.ArgumentParser()
parser.add_argument('--package', default='com.smart.smoke.ima.devicevalidation'); parser.add_argument('--server-only', action='store_true'); parser.add_argument('--cancel-stage', default='patch', choices=['patch','playback']); parser.add_argument('--cancel-command', default='cancel', choices=['cancel','finish']); parser.add_argument('--expect'); parser.add_argument('--serial', required=True); parser.add_argument('--run', required=True)
parser.add_argument('--kind', default='single'); parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--physical', type=int, default=0); parser.add_argument('--reported', default='1')
parser.add_argument('--audit', type=int, default=1); parser.add_argument('--hidden', type=int, default=0)
parser.add_argument('--fake-ctr', type=int, default=0); parser.add_argument('--deny', type=int, default=0)
parser.add_argument('--delay', type=int, default=0); parser.add_argument('--cancel-after', type=int, default=0)
parser.add_argument('--callback-seconds', type=int, default=180); parser.add_argument('--wait', type=int, default=100)
args = parser.parse_args()
ROOT = Path(__file__).resolve().parents[3]
ADB = '/Users/zy/Library/Android/sdk/platform-tools/adb'
PACKAGE = args.package
BASE = 'https://127.0.0.1:19443'
DIRECTORY = args.output / args.run; DIRECTORY.mkdir(parents=True, exist_ok=True)
context = ssl.create_default_context(cafile=str(ROOT / 'ad-sdk-tcl/google-ima/src/test/consumer-smoke/src/debug/res/raw/fixture_ca.pem'))


def adb(command, timeout=15):
    return subprocess.run([ADB, '-s', args.serial, 'shell', shlex.join(command)], capture_output=True, text=True, timeout=timeout)


def server_events():
    result = []
    for line in (args.output / 'server-events.jsonl').read_text().splitlines():
        try:
            event = json.loads(line)
            if event.get('run_id') == args.run: result.append(event)
        except ValueError: pass
    return result


def terminal():
    if args.server_only:
        done = [e['body'] for e in server_events() if e['kind']=='terminal']
        return done[-1] if done else None
    result = adb(['run-as', PACKAGE, 'cat', 'files/device-validation/' + args.run + '/terminal.json'])
    try: return json.loads(result.stdout)
    except ValueError: return None


case = {'run_id': args.run, 'kind': args.kind, 'delay': args.delay}
request = urllib.request.Request(BASE + '/__case', data=json.dumps(case).encode(), headers={'Content-Type': 'application/json'})
with urllib.request.urlopen(request, context=context, timeout=10): pass
# An official Google test inventory seed exercises Core's real GAM parameter/signal generation.
# The SDK patch resolver replaces it with our localhost VAST before any ad request is sent.
seed = ('https://pubads.g.doubleclick.net/gampad/ads?iu=/21775744923/external/omid_ad_samples'
        '&env=vp&gdfp_req=1&output=vast&sz=640x480&description_url=https%3A%2F%2Fexample.com'
        '&vpa=auto&vpos=preroll&unviewed_position_start=1&correlator=&fixture_run=' + quote(args.run))
config = {'ad_tag_url': seed, 'ad_load_timeout_ms': 45000, 'ad_startup_timeout_ms': 35000, 'enable_fake_ctr': args.fake_ctr}
if args.reported != 'missing': config['reported_sound_mode'] = json.loads(args.reported)
if args.kind == 'missing_config': config = {}
command = ['am', 'start', '-n', PACKAGE + '/com.smart.smoke.DeviceValidationActivity',
           '--es', 'run_id', args.run, '--es', 'fixture_base', BASE, '--es', 'gam_config', json.dumps(config),
           '--ez', 'physical', str(bool(args.physical)).lower(), '--ez', 'audit', str(bool(args.audit)).lower(),
           '--ez', 'hidden', str(bool(args.hidden)).lower(), '--ez', 'deny', str(bool(args.deny)).lower(),
           '--el', 'callback_seconds', str(args.callback_seconds)]
print('START', args.run, args.kind, flush=True)
start = time.monotonic(); launch = adb(command)
(DIRECTORY / 'launch.txt').write_text(launch.stdout + launch.stderr)
result, cancelled, playback_seen_at = None, False, None
while time.monotonic() - start < args.wait:
    if args.cancel_stage=='playback' and args.cancel_after and not args.server_only:
        live = adb(['run-as', PACKAGE, 'cat', 'files/device-validation/' + args.run + '/events.jsonl']).stdout
        began = any(json.loads(line).get('type')=='started' for line in live.splitlines() if line.startswith('{'))
        if began and playback_seen_at is None: playback_seen_at = time.monotonic()
        cancel_ready = playback_seen_at is not None and time.monotonic() - playback_seen_at >= 2
    else:
        cancel_ready = any(e['kind']=='patch' for e in server_events()) if args.cancel_stage=='patch' else any(e['kind']=='report' and e['body']['event_type']=='AD_STARTED' for e in server_events())
    if args.cancel_after and not cancelled and time.monotonic() - start >= args.cancel_after and cancel_ready:
        cancelled_command = adb(['am', 'start', '-n', PACKAGE + '/com.smart.smoke.DeviceValidationActivity', '--es', 'command', args.cancel_command])
        (DIRECTORY/'cancel-command.txt').write_text(cancelled_command.stdout + cancelled_command.stderr)
        cancelled = True
    try: result = terminal()
    except subprocess.TimeoutExpired: result = None
    if result: break
    time.sleep(2)
if not result: result = {'outcome': 'test_wait_timeout'}
# Let the host's report queue deliver its final audit/status and preserve transport failures too.
for _ in range(35):
    events = server_events()
    final_report = ('AD_ERROR' if args.kind == 'missing_config' or args.deny or result['outcome'] == 'failed'
                    else 'AD_COMPLETED' if result['outcome'] == 'completed' else 'AD_REQUESTED')
    report_ready = any(e['kind'] == 'report' and e['body']['event_type'] == final_report for e in events)
    audit_ready = any(e['kind'] == 'audit' for e in events) or not args.audit
    if report_ready and audit_ready: break
    time.sleep(1)
if args.server_only:
    local = [{'type': 'terminal', 'fields': e['body']} for e in server_events() if e['kind']=='terminal']
else:
    result_events = adb(['run-as', PACKAGE, 'cat', 'files/device-validation/' + args.run + '/events.jsonl'])
    (DIRECTORY / 'events.jsonl').write_text(result_events.stdout)
    local = [json.loads(line) for line in result_events.stdout.splitlines() if line.startswith('{')]
events = server_events(); reports = [e['body']['event_type'] for e in events if e['kind'] == 'report']
checks = {}
expected = args.expect or ('cancelled:window_destroy' if args.cancel_after else 'completed' if args.kind in ('single','short','long','wrapper','pod','fallback','missing_config') or args.deny else 'failed')
checks['terminal_outcome'] = result['outcome'] == expected
checks['one_terminal_callback'] = sum(e['type'] == 'terminal' for e in local) == 1
checks['requested_once'] = reports.count('AD_REQUESTED') == 1
patches = [e for e in events if e['kind'] == 'patch']
checks['patch_count'] = len(patches) == (0 if args.kind == 'missing_config' or args.deny else 1)
if patches:
    body = patches[0]['body']; query = parse_qs(urlsplit(body['ad_tag_url']).query, keep_blank_values=True)
    checks['frozen_identity'] = body['request_id'] == args.run and body['channel_id'] == 'GOOGLE_IMA_DEVICE_VALIDATION' and body['sdk_version'] == '3.35.1' and bool(body.get('rdid'))
    checks['core_gam_parameters'] = bool(query.get('sdkv')) and len(body['ad_tag_url']) > 400
    checks['reported_mode'] = body['reported_sound_mode'] == (0 if args.reported in ('0','"0"') else 1)
vasts = [e for e in events if e['kind'] == 'vast']
if vasts:
    q = vasts[0]['query']; checks['backend_final_url'] = q.get('server_value') == ['a+b/c='] and q.get('x') == ['one','two'] and q.get('vpmute') == ['1' if args.reported in ('0','"0"') else '0']
normal = args.kind in ('single','short','long','wrapper','pod','fallback') and not args.cancel_after and not args.deny and expected == 'completed'
if normal:
    for event in ('AD_FILLED','AD_LOADED','AD_STARTED','AD_COMPLETED'):
        checks[event.lower() + '_once'] = reports.count(event) == 1
    checks['no_terminal_error'] = 'AD_ERROR' not in reports
else:
    if args.kind in ('empty','invalid','patch_error','missing_config') or (args.cancel_after and args.cancel_stage=='patch') or args.deny or (expected.startswith('cancelled:') and args.cancel_stage=='patch'):
        checks['no_false_fill'] = 'AD_FILLED' not in reports
    if args.kind == 'patch_error': checks['no_original_vast_on_patch_error'] = not vasts
    if args.cancel_after and args.cancel_stage=='playback': checks['fill_before_playback_cancel'] = reports.count('AD_FILLED') == 1 and reports.count('AD_STARTED') == 1 and 'AD_COMPLETED' not in reports
    if args.kind == 'media_error': checks['fill_retained_on_media_error'] = reports.count('AD_FILLED') == 1
    if args.delay > args.callback_seconds and expected == 'failed': checks['timeout_prevents_vast'] = not vasts
if args.audit:
    checks['one_audit'] = sum(e['kind'] == 'audit' for e in events) == 1
    audit_file = DIRECTORY / 'server-runtime-audit.json'
    if audit_file.exists() and normal:
        audit = json.loads(audit_file.read_text()); playback = audit['ima_playback_audit']
        checks['physical_volume'] = playback['last_physical_player_volume'] == float(args.physical)
        checks['measurement_complete'] = playback['measurement_incomplete'] is False
        completed = next((e['body']['diagnostics'] for e in events if e['kind']=='report' and e['body']['event_type']=='AD_COMPLETED'), {})
        # Native snapshots are sampled every 20s. Short ads may finish before a
        # second snapshot; also use the real typed IMA AdProgressInfo position.
        observed_position = max(playback['max_ad_position_ms'], completed.get('lastImaProgressPositionMs', -1))
        checks['media_progress'] = observed_position >= (195000 if args.kind == 'long' else 1000)
        if args.kind == 'pod': checks['two_ad_instances'] = len(playback['ad_instances']) == 2
        if args.kind == 'wrapper': checks['wrapper_and_inline_xml'] = audit['vast_response_count'] >= 2
        if args.kind == 'fallback': checks['webview_fallback_observed'] = audit['web_vast_response_count'] > 0
        if args.kind == 'long': checks['outlasted_original_request_timeout'] = observed_position > args.callback_seconds * 1000 and time.monotonic() - start > args.callback_seconds
        omid=[]
        for event in events:
            if event['kind'] != 'omid': continue
            try:
                value = json.loads(event['message'].split('::',1)[-1])
                if isinstance(value,dict): omid.append(value)
            except ValueError: pass
        if args.kind not in ('short','pod'):
            types = collections.Counter(e.get('type') for e in omid)
            checks['independent_omid_lifecycle'] = all(types[t] >= 1 for t in ('sessionStart','impression','loaded','start','firstQuartile','midpoint','thirdQuartile','complete','sessionFinish'))
            expected_volume = 0 if args.reported in ('0','"0"') else 1
            starts=[e for e in omid if e.get('type')=='start']
            checks['independent_omid_volume'] = bool(starts) and all(e['data'].get(k)==expected_volume for e in starts for k in ('deviceVolume','mediaPlayerVolume','videoPlayerVolume'))
        av=[parse_qs(e['query'].get('acvw',[''])[0]) for e in events if e['kind']=='activeview']
        if args.kind not in ('short','pod'):
            expected_value='0' if args.reported in ('0','"0"') else '1'
            checks['activeview_http_volume'] = bool(av) and all(x.get(k)==[expected_value] for x in av for k in ('a','mv','nv'))
else:
    checks['audit_disabled'] = not any(e['kind']=='audit' or e['kind']=='trace' for e in events)
verdict={'run_id':args.run,'serial':args.serial,'kind':args.kind,'outcome':result['outcome'],'seconds':round(time.monotonic()-start,2),'checks':checks,'passed':all(checks.values()),'server_reports':reports}
(DIRECTORY/'verification.json').write_text(json.dumps(verdict,ensure_ascii=False,indent=2))
print(json.dumps(verdict,ensure_ascii=False),flush=True)
raise SystemExit(0 if verdict['passed'] else 1)
