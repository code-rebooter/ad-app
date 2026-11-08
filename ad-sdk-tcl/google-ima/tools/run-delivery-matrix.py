#!/usr/bin/env python3
"""Run serial public-API scenarios on one device; run another device independently."""
import argparse,json,subprocess,sys
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--engine',choices=['google','tcl'],required=True)
p.add_argument('--prefix',required=True);p.add_argument('--output',type=Path,required=True)
p.add_argument('--package',default='com.smart.smoke.ima.devicevalidation');p.add_argument('--only',nargs='*')
p.add_argument('--port',type=int,default=19443)
a=p.parse_args();runner=Path(__file__).with_name('run-device-closure.py')
cases=[('original-pod',['--kind','local_pod']),('wrapper',['--kind','wrapper']),
       ('wrapper-empty',['--kind','wrapper_empty','--expect','failed']),
       ('inline-empty',['--kind','inline_empty','--expect','failed']),
       ('empty',['--kind','empty','--expect','failed']),('invalid',['--kind','invalid','--expect','failed']),
       ('patch-http',['--kind','patch_error','--expect','failed']),
       ('patch-json',['--kind','patch_bad_json','--expect','failed']),
       ('patch-missing',['--kind','patch_missing_url','--expect','failed']),
       ('patch-rejected',['--kind','patch_rejected','--expect','failed']),
       ('patch-redirect',['--kind','patch_redirect','--expect','failed']),
       ('media404',['--kind','media_error','--expect','failed']),
       ('media-invalid',['--kind','media_truncated','--expect','failed']),
       ('pause-resume',['--kind','local_single','--command','pause','--resume-after','5']),
       ('pause-timeout',['--kind','local_single','--command','pause','--expect','failed','--wait','120']),
       ('buffer-resume',['--kind','buffer_resume']),
       ('buffer-timeout',['--kind','buffer_stall','--expect','failed','--wait','150']),
       ('cancel-patch',['--kind','single','--delay','10','--command','cancel_patch','--command-after','1','--expect','cancelled:window_destroy' if a.engine=='google' else 'cancelled']),
       ('cancel-playing',['--kind','single','--command','release_twice','--expect','cancelled:window_destroy' if a.engine=='google' else 'cancelled']),
       ('audit-off',['--kind','single','--audit','0']),
       ('completion-release',['--kind','single','--release-on-completion']),
       ('patch-delay',['--kind','single','--delay','6']),
       ('patch-timeout',['--kind','single','--delay','35','--callback','30','--expect','failed']),
       ('long210',['--kind','long','--wait','280'])]
if a.engine=='google':
    cases += [('sound'+str(r)+str(v),['--kind','local_single','--reported',str(r),'--physical',str(v)]) for r in [0,1] for v in [0,1]]
    cases += [('startup-timeout',['--kind','startup_timeout','--startup','10000','--expect','failed','--reason','ad_startup_timeout']),
              ('vast-timeout',['--kind','vast_timeout','--load','3000','--expect','failed','--wait','100']),
              ('hidden',['--kind','single','--hidden']),
              ('sound-missing',['--kind','single','--reported','missing']),
              ('sound-string0',['--kind','single','--reported','"0"']),
              ('sound-invalid',['--kind','single','--reported','true']),
              ('fallback',['--kind','fallback']),
              ('missing-config',['--kind','single','--missing-config','--reason','gam_config_unavailable']),
              ('denied',['--kind','single','--deny','--reason','external_playback_denied'])]
else:
    cases += [('sound0',['--kind','local_single','--physical','0']),('sound1',['--kind','local_single','--physical','1']),
              ('guarantee',['--kind','guarantee']),
              ('address-http',['--kind','address_http_error','--expect','failed']),
              ('address-invalid',['--kind','address_invalid','--expect','failed']),
              ('address-rejected',['--kind','address_rejected','--expect','failed']),
              ('address-timeout',['--kind','address_timeout','--expect','failed']),
              ('cancel-address',['--kind','address_timeout','--command','cancel_address','--command-after','0','--expect','cancelled']),
              ('direct',['--kind','local_single','--direct']),
              ('patch-disabled',['--kind','local_single','--disable-patch'])]
results=[]
for label,extra in cases:
    if a.only and label not in a.only:continue
    run=a.prefix+'-'+label
    cmd=[sys.executable,str(runner),'--serial',a.serial,'--run',run,'--engine',a.engine,'--package',a.package,'--output',str(a.output),'--port',str(a.port)]+extra
    process=subprocess.run(cmd,capture_output=True,text=True)
    (a.output/(run+'.log')).write_text(process.stdout+process.stderr)
    result={'label':label,'run_id':run,'exit_code':process.returncode}
    if (a.output/run/'verification.json').exists():
        verification=json.loads((a.output/run/'verification.json').read_text());result['failed_checks']=[k for k,v in verification['checks'].items() if not v]
    results.append(result)
    (a.output/(a.prefix+'-matrix.json')).write_text(json.dumps(results,indent=2)+'\n')
    print(json.dumps(result),flush=True)
raise SystemExit(0 if all(r['exit_code']==0 for r in results) else 1)
