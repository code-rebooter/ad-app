#!/usr/bin/env python3
"""Verify executable isolation boundaries after D8/R8, including process-wide setters."""
from collections import Counter
import json
from pathlib import Path
import re
import subprocess
import zipfile

VENDOR_PREFIXES = ('Lcom/tcl/', 'Lcom/google/android/exoplayer2/', 'La/a/', 'Ltv/danmaku/ijk/')
GLOBAL_SETTERS = re.compile(r'(?:CookieHandler;\.setDefault:|HttpsURLConnection;\.setDefault(?:HostnameVerifier|SSLSocketFactory):|ProxySelector;\.setDefault:|Authenticator;\.setDefault:)')

# Retain only the process-default operations in the pinned original player.
# D8 merges two equivalent trailing setDataSource branches: 3 class-file TLS
# call sites become 2 DEX call sites; AAR verification checks original bytes.
ORIGINAL_NETWORK_SETTERS = {
    ('Lcom/tcl/tuniplayer_exo/f/a;', 'a(Landroid/content/Context;)Lcom/google/android/exoplayer2/upstream/HttpDataSource$Factory;', 'Ljava/net/CookieHandler;.setDefault:'): 1,
    ('Lcom/tcl/uniplayer/tuniplayer/TUniBasePlayer;', 'innerResetObj()V', 'Ljavax/net/ssl/HttpsURLConnection;.setDefaultHostnameVerifier:'): 1,
    ('Lcom/tcl/uniplayer/tuniplayer/TUniBasePlayer;', 'innerReleaseObj()V', 'Ljavax/net/ssl/HttpsURLConnection;.setDefaultHostnameVerifier:'): 1,
    ('Lcom/tcl/uniplayer/tuniplayer/TUniBasePlayer;', 'innerSetDataSource(Lcom/tcl/ff/component/uniplayer/data/VideoItem;Ljava/lang/String;)V', 'Ljavax/net/ssl/HttpsURLConnection;.setDefaultHostnameVerifier:'): 2,
}

def audit_isolation(apk, dexdump, manifests, mapping=None):
    policies = [json.loads(Path(path).read_text()) for path in manifests]
    violations = []
    if len(policies) != 3 or any(item['profile'] != 'adurl-only-v4' for item in policies):
        violations.append('Expected three adurl-only-v4 isolation manifests')
    targets = {'L' + item['className'][:-6] + ';': item for policy in policies for item in policy['changedClasses']}
    blocks = {name: [] for name in targets}
    current = None
    global_calls = []
    method_name = method_type = None
    with subprocess.Popen([str(dexdump), '-d', str(apk)], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          text=True, errors='replace') as process:
        for line in process.stdout:
            match = re.search(r"Class descriptor\s*:\s*'([^']+)'", line)
            if match:
                current = match.group(1)
                method_name = method_type = None
            if re.match(r'    #\d+\s*:', line):
                method_name = method_type = None
            match = re.search(r"name\s*:\s*'([^']+)'", line)
            if match:
                method_name = match.group(1)
            match = re.search(r"type\s*:\s*'([^']+)'", line)
            if match:
                method_type = match.group(1)
            if current in blocks:
                blocks[current].append(line)
            if current and current.startswith(VENDOR_PREFIXES) and 'invoke-' in line and GLOBAL_SETTERS.search(line):
                global_calls.append({'class': current, 'method': (method_name or '') + (method_type or ''), 'instruction': line.strip(), 'policy': 'original-player-network-behavior'})
        error = process.stderr.read()
        if process.wait():
            raise ValueError('Cannot inspect isolation DEX: ' + error)
    observed_network_setters = Counter()
    for call in global_calls:
        matched = [key for key in ORIGINAL_NETWORK_SETTERS
                   if call['class'] == key[0] and call['method'] == key[1] and key[2] in call['instruction']]
        if len(matched) != 1:
            violations.append('Unexpected process-wide network setter: ' + call['class'] + '.' + call['method'])
        else:
            observed_network_setters[matched[0]] += 1
    for key, expected in ORIGINAL_NETWORK_SETTERS.items():
        if observed_network_setters[key] != expected:
            violations.append('Original player network setter count differs: ' + key[0] + '.' + key[1]
                              + ': expected ' + str(expected) + ', observed ' + str(observed_network_setters[key]))
    renamed = {}
    if mapping:
        for line in Path(mapping).read_text().splitlines():
            match = re.fullmatch(r'([^\s]+) -> ([^\s]+):', line)
            if match:
                renamed[match.group(1).replace('.', '/')] = match.group(2).replace('.', '/')
    def mapped(signature):
        return re.sub(r'L([^;]+);', lambda m: 'L' + renamed.get(m.group(1), m.group(1)) + ';', signature)
    def code(owner, method):
        text = ''.join(blocks.get(owner, []))
        starts = list(re.finditer(r'(?m)^    #\d+\s*:', text))
        name, signature = method.split('(', 1)
        signature = mapped('(' + signature)
        for index, start in enumerate(starts):
            section = text[start.start():starts[index+1].start() if index+1<len(starts) else len(text)]
            n = re.search(r"name\s*:\s*'([^']+)'", section)
            t = re.search(r"type\s*:\s*'([^']+)'", section)
            if n and t and n.group(1)==name and t.group(1)==signature:
                return re.findall(r'\|[0-9a-f]+: (.*)', section)
        violations.append('Missing isolation method: ' + owner + '.' + method)
        return []
    checked = []
    def check(owner, method, mode):
        body = code(owner, method)
        key = owner + '.' + method
        checked.append({'method': key, 'policy': mode})
        invokes = [line for line in body if line.startswith('invoke-')]
        if mode=='denied':
            if not any(line.startswith('throw ') for line in body) or not any('Ljava/lang/UnsupportedOperationException;' in line for line in body):
                violations.append('Exception boundary missing: ' + key)
            if any(not re.search(r'(?:Ljava/lang/(?:Object|UnsupportedOperationException);\.<init>:)', line) for line in invokes):
                violations.append('Disabled boundary executes another method: ' + key)
        elif mode=='void':
            if body != ['return-void']:
                violations.append('Disabled method still executes: ' + key)
        elif mode=='false':
            if len(body)!=2 or not re.search(r'const/4 .*#int 0(?:\D|$)', body[0]) or not body[1].startswith('return '):
                violations.append('Disabled flag can become true: ' + key)
        elif mode=='null':
            if len(body)!=2 or not re.search(r'const/4 .*#int 0(?:\D|$)', body[0]) or not body[1].startswith('return-object '):
                violations.append('Disabled result is not null: ' + key)
        elif mode=='local-flag':
            if invokes or any('Proxy;' in line or 'UniTrackerImpl;' in line for line in body):
                violations.append('Disabled plugin boundary still calls code: ' + key)
    base = 'Lcom/tcl/ff/component/overseabasebusiness/'
    for owner, item in targets.items():
        if not blocks[owner]:
            violations.append('Missing isolated class: ' + owner)
        for change in item['methods']:
            method = re.sub(r'^(?:added|removed) ', '', change).replace(' [permissions only]', '')
            if change.startswith('removed '):
                continue
            if owner==base+'AdConfigFetcher;' and method.endswith(')V') and not method.startswith('<'):
                check(owner, method, 'void')
            elif owner=='Lcom/tcl/tuniplayer_base/d;':
                check(owner, method, 'local-flag' if method=='a(Z)V' else 'void')
            elif owner=='Lcom/tcl/ff/component/overseahttp/http/HttpRequester;' and method.startswith('resolveSuppliedAdAddress('):
                check(owner, method, 'null')
            elif owner==base+'adutils/AdUtil;' and method.startswith('recordAdRequestTime('):
                check(owner, method, 'void')
            elif owner==base+'adutils/SharedPreferenceUtil;' and method.startswith('savePrefString('):
                check(owner, method, 'void')
            elif owner in {base+'bi/BiInitialization;',base+'bi/BiReportUtil;', 'Lcom/tcl/ff/component/media/ima/bi/a;'} and method.endswith(')V'):
                check(owner, method, 'void')
            elif owner=='Lcom/tcl/ff/component/media/processor/api/AdsFetcher;' and method.startswith('interceptByConfig('):
                check(owner, method, 'false')
            elif owner=='Lcom/tcl/ff/component/media/processor/a/b;':
                body=code(owner,method)
                invokes=[line for line in body if line.startswith('invoke-')]
                if len(invokes)!=1 or 'HttpRequester;.resolveSuppliedAdAddress:' not in invokes[0]:
                    violations.append('Address business boundary can still use generic transport: '+owner+'.'+method)
                checked.append({'method':owner+'.'+method,'policy':'supplied-address-only'})
    config_code = code(base+'AdConfigFetcher;', '<init>()V')
    if any('Context;.getFilesDir:' in line or '/TAdConfig0/' in line for line in config_code):
        violations.append('Configuration constructor still accesses disk path')
    app_code = code('Lcom/tcl/ff/component/overseabase/base/util/AppUtils;', 'gotoWeb(Landroid/content/Context;Ljava/lang/String;)V')
    if any('com.tcl.browser' in line for line in app_code):
        violations.append('TCL browser selection/installation promotion still executes')
    markers = {}
    with zipfile.ZipFile(apk) as archive:
        for component in ('base', 'media', 'player'):
            path = 'META-INF/tcl-adurl-only/' + component + '.marker'
            markers[component] = archive.read(path).decode() if path in archive.namelist() else None
            if markers[component] != 'adurl-only-v4':
                violations.append('Packaged isolation marker differs: ' + component)
    return {'profile': 'adurl-only-v4', 'changedClassesChecked': len(targets), 'boundaryMethodsChecked': checked,
            'processWideSetterCalls': global_calls, 'markers': markers, 'violations': violations}
