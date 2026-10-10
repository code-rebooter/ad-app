#!/usr/bin/env python3
"""Compare two ad opportunities in one process across the two isolated runtime entries."""
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit


def canonical(url):
    parts = urlsplit(url)
    query = [(key, '<requestId>' if key == 'requestId' else value)
             for key, value in parse_qsl(parts.query, keep_blank_values=True)]
    return urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(query), parts.fragment))


def read(folder):
    result = json.loads((folder / 'result.json').read_text())
    rows = [json.loads(line) for file in folder.glob('files/*/events.jsonl')
            for line in file.read_text().splitlines()]
    rows.sort(key=lambda row: row['timeMs'])
    requests = [row for row in rows if row['phase'] == 'request']
    phases = defaultdict(lambda: defaultdict(list))
    for row in requests:
        parts = urlsplit(row['url'])
        query = dict(parse_qsl(parts.query))
        event = query.get('event')
        if query.get('ad') and event in ('impression', 'start', 'firstQuartile', 'midpoint', 'thirdQuartile', 'complete'):
            group = 'begin' if event in ('impression', 'start') else event
            phases[(parts.path, query['ad'])][group].append(row['timeMs'])
    valid = True
    for events in phases.values():
        previous = None
        for group in ('begin', 'firstQuartile', 'midpoint', 'thirdQuartile', 'complete'):
            if group not in events:
                continue
            if previous is not None and previous > min(events[group]):
                valid = False
            previous = max(events[group])
    return {
        'completed': result['passed'],
        'apk_sha256': result['apk_sha256'],
        'callbacks': [row['kind'] for row in rows if row['phase'] == 'callback'
                      and not row['kind'].startswith('SOURCE ')],
        'requests': Counter(canonical(row['url']) for row in requests),
        'vast': [canonical(row['url']) for row in requests if row['kind'] == 'vast'],
        'event_order_valid': valid,
    }


p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
pairs = [
    ('empty-single', 'same-process-empty-single-reference', 'same-process-empty-single-candidate'),
    ('release-single-wrapper', 'same-process-release-reference', 'same-process-release-candidate'),
    ('release-single-wrapper-repeat', 'same-process-release-reference', 'same-process-release-candidate-repeat'),
]
results = {}
for name, reference_name, candidate_name in pairs:
    reference, candidate = read(a.output / reference_name), read(a.output / candidate_name)
    checks = {
        'both_opportunities_completed': reference['completed'] and candidate['completed'],
        'same_test_apk': reference['apk_sha256'] == candidate['apk_sha256'],
        'callbacks_identical': reference['callbacks'] == candidate['callbacks'],
        'request_urls_and_counts_identical': reference['requests'] == candidate['requests'],
        'vast_chain_identical': reference['vast'] == candidate['vast'],
        'event_phase_order_valid': reference['event_order_valid'] and candidate['event_order_valid'],
    }
    results[name] = {
        'pass': all(checks.values()), 'checks': checks,
        'reference': reference_name, 'candidate': candidate_name,
        'apk_sha256': candidate['apk_sha256'], 'callbacks': candidate['callbacks'],
        'reference_only_requests': dict(reference['requests'] - candidate['requests']),
        'candidate_only_requests': dict(candidate['requests'] - reference['requests']),
    }
summary = {
    'pass': all(result['pass'] for result in results.values()),
    'scope': 'Both entries use the isolated runtime; this tests API/address handoff and explicit host operations, not an unmodified-original baseline.',
    'cases': results,
}
(a.output / 'same-process-parity.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
if not summary['pass']:
    raise SystemExit(1)
