#!/usr/bin/env python3
from pathlib import Path
import subprocess,sys,json,argparse
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--adb-server-port',type=int);p.add_argument('--package',default='com.smart.android.tclad.demo');p.add_argument('--output',type=Path,required=True);a=p.parse_args()
runner=Path(__file__).with_name('run_parity_case.py')
cases=[('single','single',[]),('empty','empty',[]),('malformed','malformed',[]),('http-error','http-error',[]),('unsupported','unsupported',[]),('wrapper','wrapper',[]),('cycle','cycle',[]),('guarantee','empty',['--fallback']),('media-fallback','media-fallback',[]),('controls','single',['--controls']),('release-before-vast','slow',['--release-after','100']),('release-in-flight','slow',['--release-after-vast','100']),('read-timeout','http-timeout',[]),('release-playing','single',['--release-after','4000']),('pod','pod',['--start-delay','2000'])]
for name,case,extra in cases:
 for reference in [True,False]:
  mode='reference' if reference else 'candidate';out=a.output/f'{name}-{mode}'
  if (out/'capture.tar').exists():print('Already captured',out.name,flush=True);continue
  (a.output/'matrix-current.json').write_text(json.dumps({'case':name,'mode':mode})+'\n')
  print('RUN',name,mode,flush=True)
  cmd=[sys.executable,str(runner),'--serial',a.serial,'--package',a.package,'--output',str(out),'--case',case,*extra]
  if a.adb_server_port:cmd+=['--adb-server-port',str(a.adb_server_port)]
  if reference:cmd+=['--reference']
  subprocess.run(cmd,check=True)
(a.output/'matrix-current.json').write_text(json.dumps({'complete':True})+'\n')
