#!/usr/bin/env python3
"""Positive real-FileLock regression over four current production entry points."""
from pathlib import Path
import argparse,hashlib,json,os,subprocess,shutil
SOURCE=Path(__file__).resolve().parent
ROOT=SOURCE.parents[3]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--work-dir',type=Path,default=ROOT/'build/identity-lock-regression')
args=parser.parse_args()
HERE=(ROOT/args.work_dir).resolve() if not args.work_dir.is_absolute() else args.work_dir.resolve()
HERE.mkdir(parents=True,exist_ok=True)
SRC=SOURCE/'src'; OUT=HERE/'classes'; SHARED=HERE/'shared-classes'
if os.environ.get('JAVA_HOME'):
    os.environ['PATH']=str(Path(os.environ['JAVA_HOME'])/'bin')+os.pathsep+os.environ.get('PATH','')
CACHE=Path(os.environ.get('GRADLE_USER_HOME',str(Path.home()/'.gradle')))/'caches/modules-2/files-2.1'
ANDROID=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Library/Android/sdk')))/'platforms/android-36/android.jar'
def pick(name):
    matches=sorted(CACHE.rglob(name))
    if not matches: raise SystemExit('Missing cached dependency: '+name+'; run the project Gradle tests first')
    return matches[0]
if not ANDROID.is_file(): raise SystemExit('Missing Android 36 android.jar; set ANDROID_HOME')
for tool in ('java','javac','javap'):
    if shutil.which(tool) is None: raise SystemExit('Missing JDK tool: '+tool+'; set JAVA_HOME')
for path in (OUT,SHARED,HERE/'cases'):
    if path.exists(): shutil.rmtree(path)
OUT.mkdir(); SHARED.mkdir()
files=['ad-sdk-tcl/src/main/java/com/smart/android/tclad/ExecutionConfig.java','ad-sdk-tcl/google-ima/src/main/java/com/smart/android/tclad/ima/GoogleImaExecutionConfig.java','app/src/main/java/com/smart/android/ad_app/Hq008ApiRequestFields.kt','ad-sdk-google-ima/src/main/java/com/smart/android/ad_app/google/ima/local/ImaSharedAdvertisingIdentity.java']
provenance={f:hashlib.sha256((ROOT/f).read_bytes()).hexdigest() for f in files}
(HERE/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
for f in files:
    target=HERE/'reviewed-source'/f;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes((ROOT/f).read_bytes())
stdlib=pick('kotlin-stdlib-2.2.21.jar')
compiler=':'.join(str(pick(n)) for n in ['kotlin-compiler-embeddable-2.2.21.jar','kotlin-stdlib-2.2.21.jar','kotlin-script-runtime-2.2.21.jar','kotlin-reflect-2.2.21.jar','kotlinx-coroutines-core-jvm-1.8.0.jar','annotations-13.0.jar'])
with (HERE/'result.log').open('w') as log:
    def run(cmd):
        log.write('$ '+' '.join(map(str,cmd))+'\n');log.flush()
        proc=subprocess.run(list(map(str,cmd)),cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
        log.write(proc.stdout);log.flush();print(proc.stdout,end='',flush=True)
        if proc.returncode: raise SystemExit(proc.returncode)
        return proc.stdout
    run(['javac','-d',OUT,*sorted((SRC/'android').rglob('*.java')),SRC/'com/smart/android/ad_app/BuildConfig.java'])
    run(['javac','-cp',ANDROID,'-d',SHARED,ROOT/files[3],SRC/'com/smart/android/ad_app/google/ima/local/ImaFirmwareInputs.java'])
    run(['javac','-cp',f'{OUT}:{ANDROID}:{stdlib}:{pick("okhttp-4.12.0.jar")}','-d',OUT,ROOT/files[0],ROOT/files[1]])
    run(['java','-cp',compiler,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',f'{OUT}:{stdlib}:{ANDROID}','-d',OUT,ROOT/files[2],SRC/'com/smart/android/ad_app/BuildFlavor.kt'])
    run(['javac','-cp',f'{OUT}:{SHARED}:{stdlib}:{ANDROID}','-d',OUT,SRC/'IdentityLockRegression.java'])
    run(['java','-cp',f'{OUT}:{SHARED}:{stdlib}:{ANDROID}','IdentityLockRegression',HERE/'cases'])
    targets=['com.smart.android.tclad.ExecutionConfig$Builder','com.smart.android.tclad.ima.GoogleImaExecutionConfig$Builder','com.smart.android.ad_app.Hq008ApiRequestFields','com.smart.android.ad_app.google.ima.local.ImaSharedAdvertisingIdentity']
    checks=[]
    bytecode_dir=HERE/'bytecode';bytecode_dir.mkdir(exist_ok=True)
    for target in targets:
        proc=subprocess.run(['javap','-classpath',f'{OUT}:{SHARED}:{stdlib}:{ANDROID}','-c','-p',target],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,check=True)
        (bytecode_dir/(target+'.txt')).write_text(proc.stdout)
        forbidden=any(value in proc.stdout for value in ['java/io/File.toPath','java/nio/file/Path','java/nio/file/StandardOpenOption','java/nio/channels/FileChannel.open:'])
        checks.append({'class':target,'no_api26_path_filechannel_open':not forbidden})
        line=('FAIL ' if forbidden else 'PASS ')+target+' has no API26 Path/FileChannel.open identity path\n'
        print(line,end='');log.write(line);log.flush()
    (HERE/'api24-bytecode-check.json').write_text(json.dumps(checks,indent=2)+'\n')
    after={f:hashlib.sha256((ROOT/f).read_bytes()).hexdigest() for f in files}
    if after!=provenance:raise SystemExit('Production source changed during regression; rerun after edits settle')
    if any(not row['no_api26_path_filechannel_open'] for row in checks):raise SystemExit(1)
