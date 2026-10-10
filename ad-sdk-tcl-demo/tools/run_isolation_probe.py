#!/usr/bin/env python3
"""Run Android isolation negative probes without requesting an advertisement."""
from pathlib import Path
import argparse,json,subprocess,time

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial',required=True)
p.add_argument('--adb-server-port',type=int)
p.add_argument('--package',default='com.smart.android.tclad.demo.isolated')
p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
a.output.mkdir(parents=True,exist_ok=True)
command=['/Users/zy/.cache/googleapps-build-tools/android-sdk/platform-tools/adb']+(['-P',str(a.adb_server_port)] if a.adb_server_port else [])+['-s',a.serial]
def adb(*args,check=True):return subprocess.run([*command,*args],capture_output=True,check=check,timeout=20)
adb('shell','am','force-stop',a.package)
# Remove only this demo's previous diagnostic result so it cannot count as a fresh pass.
adb('shell','run-as',a.package,'rm','-f','files/isolation-probe.json')
(a.output/'initial-directories.txt').write_bytes(adb('shell','run-as',a.package,'ls','-laR','.').stdout)
report=None
with (a.output/'logcat.txt').open('wb') as log:
    process=subprocess.Popen([*command,'logcat','-v','threadtime','-T','1','TclNativeDemo:I','AndroidRuntime:E','*:S'],stdout=log,stderr=subprocess.STDOUT)
    try:
        launch=adb('shell','am','start','-W','-n',a.package+'/com.smart.android.tclad.demo.DemoActivity','--ez','isolation_probe','true','--ez','use_default_config','true','--ez','automated','true')
        (a.output/'launch.txt').write_bytes(launch.stdout)
        deadline=time.monotonic()+30
        while time.monotonic()<deadline:
            time.sleep(1)
            result=adb('exec-out','run-as',a.package,'cat','files/isolation-probe.json',check=False)
            if result.returncode==0 and result.stdout.strip():
                try: report=json.loads(result.stdout)
                except json.JSONDecodeError: continue
                (a.output/'isolation-probe.json').write_bytes(result.stdout);break
        (a.output/'app-directories.txt').write_bytes(adb('shell','run-as',a.package,'ls','-laR','.').stdout)
        network=adb('exec-out','run-as',a.package,'cat','files/network-audit.jsonl',check=False)
        if network.returncode==0:(a.output/'network-audit.jsonl').write_bytes(network.stdout)
        print(json.dumps(report,ensure_ascii=False,indent=2),flush=True)
        if report is None or not report.get('passed'):raise RuntimeError('Fresh Android isolation probe did not pass')
    finally:
        process.terminate();process.wait(timeout=5);adb('shell','am','force-stop',a.package)
