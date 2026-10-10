#!/usr/bin/env python3
"""Verify two host-supplied tags in one process without upstream advertising traffic."""
from pathlib import Path
import argparse,json,subprocess,time,tarfile,hashlib
from urllib.parse import urlsplit
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--release-between-ads',action='store_true');p.add_argument('--first-case',choices=['empty','single'],default='empty');p.add_argument('--second-case',choices=['single','wrapper'],default='single');
p.add_argument('--reference',action='store_true');p.add_argument('--serial',required=True);p.add_argument('--adb-server-port',type=int);p.add_argument('--output',type=Path,required=True)
a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
if (a.output/'result.json').exists():p.error('Output already contains a result; choose a new directory')
expected_finished=2 if a.first_case=='empty' else 4
expected_tracking=(0 if a.first_case=='empty' else 24)+(26 if a.second_case=='wrapper' else 24)
package='com.smart.android.tclad.demo.isolated'
command=['/Users/zy/.cache/googleapps-build-tools/android-sdk/platform-tools/adb']+(['-P',str(a.adb_server_port)] if a.adb_server_port else [])+['-s',a.serial]
def adb(*args,check=True):return subprocess.run([*command,*args],capture_output=True,check=check,timeout=20)
def folders():return {x for x in adb('shell','run-as',package,'ls','files').stdout.decode().splitlines() if x.startswith('live-')}
adb('shell','am','force-stop',package);previous=folders()
metadata={'launchTimeMs':int(time.time()*1000),'release_between_ads':a.release_between_ads,'reference':a.reference,'first_case':a.first_case,'second_case':a.second_case,'apk_sha256':hashlib.sha256(Path('ad-sdk-tcl-demo/build/outputs/apk/debug/ad-sdk-tcl-demo-debug.apk').read_bytes()).hexdigest()}
with (a.output/'logcat.txt').open('wb') as log:
    proc=subprocess.Popen([*command,'logcat','-v','threadtime','-T','1'],stdout=log,stderr=subprocess.STDOUT)
    try:
        launch=adb('shell','am','start','-W','-n',package+'/com.smart.android.tclad.demo.DemoActivity','--ez','automated','true','--ez','use_default_config','true','--es','case_name','reuse-first','--es','vast_url','http://127.0.0.1:18888/tag/'+a.first_case,'--es','second_vast_url','http://127.0.0.1:18888/tag/'+a.second_case,*(['--ez','reference','true'] if a.reference else []),*(['--ez','release_between_ads','true'] if a.release_between_ads else []))
        (a.output/'launch.txt').write_bytes(launch.stdout)
        metadata['processPid']=adb('shell','pidof',package).stdout.decode().strip()
        (a.output/'power-after-launch.txt').write_bytes(adb('shell','dumpsys','power').stdout)
        rows=[];names=set();deadline=time.monotonic()+60;screenshot=False
        while time.monotonic()<deadline:
            time.sleep(1);names=folders()-previous;rows=[]
            for name in names:
                data=adb('exec-out','run-as',package,'cat','files/'+name+'/events.jsonl',check=False).stdout
                rows.extend(json.loads(x) for x in data.splitlines(keepends=True) if x.endswith(b'\n'))
            callbacks=[x['kind'] for x in rows if x['phase']=='callback']
            if len(names)>=2 and not screenshot and sum(x.startswith('STARTED durationSeconds=') for x in callbacks)>=(1 if a.first_case=='empty' else 2):
                (a.output/'playing-second.png').write_bytes(adb('exec-out','screencap','-p').stdout);screenshot=True
            if callbacks.count('FINISHED')>=expected_finished:time.sleep(3);break
            if len(names)>=2 and sum(x.startswith('ERROR') for x in callbacks)>=2:time.sleep(3);break
        for name in names:
            archive=a.output/(name+'.tar');archive.write_bytes(adb('exec-out','run-as',package,'tar','-cf','-','files/'+name).stdout)
            with tarfile.open(archive) as tar:tar.extractall(a.output,filter='data')
        rows=[]
        for file in a.output.glob('files/*/events.jsonl'):rows.extend(json.loads(line) for line in file.read_text().splitlines())
        rows.sort(key=lambda x:x['timeMs'])
        requests=[urlsplit(x['url']).path for x in rows if x['phase']=='request' and x['kind']=='vast']
        callbacks=[x['kind'] for x in rows if x['phase']=='callback']
        expected_paths=['/tag/'+a.first_case,'/tag/'+a.second_case]+(['/tag/wrapper-child'] if a.second_case=='wrapper' else [])
        tracking=sum(x['phase']=='request' and x['kind']=='tracking' for x in rows)
        failures=[x for x in rows if x['phase']=='failure' or (x['phase']=='response' and x['status']>=400)]
        passed=requests==expected_paths and callbacks.count('FINISHED')==expected_finished and tracking==expected_tracking and not failures
        result={**metadata,'passed':passed,'vast_paths':requests,'callbacks':callbacks,'tracking_count':tracking,'expected_tracking_count':expected_tracking,'network_failures':failures}
        (a.output/'result.json').write_text(json.dumps(result,indent=2)+'\n')
        (a.output/'app-directories.txt').write_bytes(adb('shell','run-as',package,'ls','-laR','.').stdout)
        (a.output/'network-audit.jsonl').write_bytes(adb('exec-out','run-as',package,'cat','files/network-audit.jsonl').stdout)
        print(json.dumps(result,indent=2),flush=True)
        if not passed:raise SystemExit(1)
    finally:
        proc.terminate();proc.wait(timeout=5);adb('shell','am','force-stop',package)
