#!/usr/bin/env python3
"""Summarize retained real-device evidence; never infer missing cases as passes."""
import argparse, hashlib, json
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--output', type=Path, required=True, help='Directory containing server-events.jsonl and case folders')
p.add_argument('--prefix', action='append', required=True)
p.add_argument('--apk-manifest', type=Path, required=True)
p.add_argument('--report', type=Path, required=True)
p.add_argument('--expect', action='append', default=[], metavar='PREFIX=COUNT',
               help='Explicit selected-case count for a partial regression; defaults to 14')
a = p.parse_args()
expected = dict(item.rsplit('=',1) for item in a.expect)
manifest = json.loads(a.apk_manifest.read_text())
events = [json.loads(line) for line in (a.output/'server-events.jsonl').read_text().splitlines()]
rows = []; all_ok = True; checks = 0; failed = []
for prefix in a.prefix:
    matrix = json.loads((a.output/(prefix+'-matrix.json')).read_text())
    variant = 'r8' if prefix.startswith('release-') else 'debug'
    apk = manifest[variant]
    actual_hash = hashlib.sha256(Path(apk['path']).read_bytes()).hexdigest()
    for result in matrix['runs']:
        run = result['run']; name = run.removeprefix(prefix+'-')
        started = [event for event in events if event.get('run_id') == run and event.get('kind') == 'test_started']
        observed_hashes = {event.get('body', {}).get('apk_sha256') for event in started}
        hash_ok = observed_hashes == {apk['sha256']} and actual_hash == apk['sha256']
        passed = bool(result['passed'] and hash_ok)
        checks += len(result.get('checks', {})) + 1
        if not passed:
            failed.append(dict(run=run, failed_checks=[k for k,v in result.get('checks',{}).items() if not v], apk_hash_ok=hash_ok))
        all_ok = all_ok and passed
        rows.append(dict(prefix=prefix, case=name, passed=passed, apk_hash_ok=hash_ok,
                         strict_loaded_once=result.get('strict_loaded_once'),
                         duplicate_loaded_notifications=result.get('duplicate_loaded_notifications'),
                         evidence=str((a.output/run/'volume-verification.json').resolve())))
    all_ok = all_ok and len(matrix['runs']) == int(expected.get(prefix,14)) and matrix['failed'] == 0 and not matrix.get('cleanup_error')
    all_ok = all_ok and matrix.get('restored_device_volume') == 0
summary = dict(passed=all_ok, cases=len(rows), passed_cases=sum(r['passed'] for r in rows),
               failed_cases=sum(not r['passed'] for r in rows), assertions=checks,
               failures=failed, rows=rows)
table = ['| 构建 / 用例 | 结果 | APK 哈希 | OM loaded 严格唯一 |', '|---|---|---|---|']
for row in rows:
    strict = '通过' if row['strict_loaded_once'] is True else ('不适用' if row['strict_loaded_once'] is None else '失败')
    table.append('| '+row['prefix']+' / '+row['case']+' | '+('通过' if row['passed'] else '失败')+' | '+('一致' if row['apk_hash_ok'] else '失败')+' | '+strict+' |')
a.report.parent.mkdir(parents=True, exist_ok=True)
a.report.write_text('# IMA 真实音量真机矩阵\n\n'
                    + ('全部指定用例通过。' if all_ok else '存在失败、缺失或清理未完成，不作为完整通过。')
                    + '\n\n'+f"用例 {summary['passed_cases']}/{summary['cases']}，逐用例断言及 APK 哈希检查 {checks} 项。\n\n"
                    + '\n'.join(table)+'\n')
a.report.with_suffix('.json').write_text(json.dumps(summary, indent=2))
print(json.dumps({k:v for k,v in summary.items() if k not in ('rows',)}, ensure_ascii=False))
raise SystemExit(0 if all_ok else 1)
