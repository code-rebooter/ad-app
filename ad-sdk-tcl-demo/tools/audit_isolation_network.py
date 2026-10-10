#!/usr/bin/env python3
"""Audit demo-recorded HTTP/HTTPS route attempts and XML-derived advertisement tracking."""
from pathlib import Path
from collections import Counter
from urllib.parse import urlsplit
import argparse,json,xml.etree.ElementTree as E
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
summary={}
for folder in sorted(a.output.iterdir()):
    captures=list(folder.glob('files/*/events.jsonl')) if folder.is_dir() else []
    if not captures:continue
    rows=[json.loads(line) for capture in captures for line in capture.read_text().splitlines()]
    rows.sort(key=lambda row:row['timeMs'])
    start=rows[0]['timeMs']
    for metadata in [folder/'run.json',folder/'result.json']:
        if metadata.exists():
            start=min(start,json.loads(metadata.read_text()).get('launchTimeMs',start))
    record=folder/'network-audit.jsonl'
    routes=[json.loads(line) for line in record.read_text().splitlines() if line.strip()] if record.exists() else []
    routes=[row for row in routes if row['timeMs']>=start]
    classes=Counter();unknown=[]
    for row in routes:
        if any('LiveCapture.intercept' in item for item in row['stack']):kind='original-vast-and-tracking-http'
        elif any('com.google.android.exoplayer2.upstream.' in item or 'com.tcl.tuniplayer_exo.f.g.c.' in item for item in row['stack']):kind='original-media-http'
        else:kind='unclassified';unknown.append(row)
        classes[kind]+=1
    xml_hosts=set()
    for xml in (xml for capture in captures for xml in capture.parent.glob('vast-*.xml')):
        try:root=E.fromstring(xml.read_bytes())
        except E.ParseError:continue
        for node in root.iter():
            text=(node.text or '').strip()
            if text.startswith(('http://','https://')):xml_hosts.add(urlsplit(text).netloc)
    tracking=[row for row in rows if row['phase']=='request' and row['kind']=='tracking']
    unmatched=[row['url'] for row in tracking if urlsplit(row['url']).netloc not in xml_hosts]
    directories=(folder/'app-directories.txt').read_text() if (folder/'app-directories.txt').exists() else ''
    forbidden=[name for name in ['bi_component_db.db','tcl_ad.xml','media_ad_sp.xml','TAdConfig0','exoplayer_internal.db'] if name in directories]
    checks={'all_routes_from_advertising_or_media_clients':not unknown,'no_initialization_network_attempts':not any(row['phase']=='initialization' for row in routes),
        'no_upstream_request':not any(row['phase']=='request' and row['kind']=='upstream' for row in rows),
        'tracking_origins_present_in_returned_xml':not unmatched,'no_forbidden_files':not forbidden}
    summary[folder.name]={'pass':all(checks.values()),'checks':checks,'route_attempts':len(routes),'route_sources':dict(classes),
        'origins':dict(Counter(urlsplit(row['uri']).netloc for row in routes)),
        'vast_requests':sum(row['phase']=='request' and row['kind']=='vast' for row in rows),'tracking_requests':len(tracking),
        'response_statuses':dict(Counter(row['status'] for row in rows if row['phase']=='response')),
        'request_failures':[row['detail'] for row in rows if row['phase']=='failure'],
        'unclassified_routes':unknown,'tracking_not_found_in_xml':unmatched,'forbidden_files':forbidden}
result={'pass':bool(summary) and all(row['pass'] for row in summary.values()),'coverage':'ProxySelector HTTP/HTTPS route selection, full original HttpRequester capture, and app-directory snapshots. Media URLs may redirect to CDN hosts. This does not claim raw-socket packet capture.','cases':summary}
(a.output/'network-isolation-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print('network isolation',result['pass'],'cases',len(summary))
for name,row in summary.items():print(name,row['pass'],row['vast_requests'],row['tracking_requests'],row['route_sources'])
if not result['pass']:raise SystemExit(1)
