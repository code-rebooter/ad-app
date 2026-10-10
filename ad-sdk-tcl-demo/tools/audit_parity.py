#!/usr/bin/env python3
"""Compare original-entry and adurl-entry outputs, preserving native callback multiplicity."""
from pathlib import Path
from collections import Counter,defaultdict
from urllib.parse import urlsplit,parse_qsl,urlunsplit,urlencode
import argparse,json,re
p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);p.add_argument('--reference-output',type=Path,help='Use captures from a separate, unmodified original runtime as the baseline');p.add_argument('--cases',nargs='+',help='Audit only these named cases');p.add_argument('--require-complete',action='store_true');a=p.parse_args()
cases=['single','empty','malformed','http-error','unsupported','wrapper','cycle','guarantee','media-fallback','controls','release-before-vast','release-in-flight','read-timeout','release-playing','pod']
if a.cases:
 unknown=set(a.cases)-set(cases)
 if unknown:p.error('Unknown cases: '+', '.join(sorted(unknown)))
 cases=list(dict.fromkeys(a.cases))
def canonical(url):
 parts=urlsplit(url);query=[(k,'<requestId>' if k=='requestId' else v) for k,v in parse_qsl(parts.query,keep_blank_values=True)]
 return urlunsplit((parts.scheme,parts.netloc,parts.path,urlencode(query),parts.fragment))
def read(name,root=None):
 folder=(root or a.output)/name
 if not (folder/'capture.tar').exists():return None
 files=list(folder.glob('files/*/events.jsonl'))
 if not files:return None
 file=files[0];rows=[json.loads(x) for x in file.read_text().splitlines()]
 requests=[x for x in rows if x['phase']=='request' and x['kind']!='upstream']
 callbacks=[x['kind'] for x in rows if x['phase']=='callback' and not x['kind'].startswith('SOURCE ')]
 counts=Counter(canonical(x['url']) for x in requests)
 vast=[canonical(x['url']) for x in requests if x['kind']=='vast']
 log=(folder/'logcat.txt').read_text(errors='replace')
 pids=re.findall(r'\S+ \S+\s+(\d+)\s+\d+ I TclNativeDemo: CAPTURE',log)
 metadata=folder/'run.json'
 if not pids and metadata.exists():
  pid=json.loads(metadata.read_text()).get('processPid')
  if pid:pids=[pid]
 if pids:
  log='\n'.join(line for line in log.splitlines() if re.match(r'\S+ \S+\s+'+pids[-1]+r'\s',line))
 fatal=re.findall(r'E AndroidRuntime: ((?:java|android|com|org)\.[^\n]+)',log)
 fatal_present='FATAL EXCEPTION' in log
 phases=defaultdict(lambda:defaultdict(list))
 for x in requests:
  q=dict(parse_qsl(urlsplit(x['url']).query));event=q.get('event');ad=q.get('ad')
  if ad and event in ['impression','start','firstQuartile','midpoint','thirdQuartile','complete']:
   group='begin' if event in ['impression','start'] else event
   phases[ad][group].append(x['timeMs'])
 order_valid=True
 for events in phases.values():
  prev=None
  for group in ['begin','firstQuartile','midpoint','thirdQuartile','complete']:
   if group not in events:continue
   if prev is not None and prev>min(events[group]):order_valid=False
   prev=max(events[group])
 return {'callbacks':callbacks,'requests':counts,'vast':vast,'fatal':fatal,'fatal_present':fatal_present,'order_valid':order_valid,
         'request_count':len(requests),'tracking_count':sum(x['kind']=='tracking' for x in requests)}
result={};complete=True
for case in cases:
 ref=read(case+'-reference',a.reference_output);candidate=read(case+'-candidate')
 if ref is None or candidate is None:complete=False;continue
 only_ref=ref['requests']-candidate['requests'];only_candidate=candidate['requests']-ref['requests']
 checks={'callbacks_identical':ref['callbacks']==candidate['callbacks'],'request_urls_and_counts_identical':not only_ref and not only_candidate,
  'vast_chain_identical':ref['vast']==candidate['vast'],'native_failures_identical':ref['fatal']==candidate['fatal'] and ref['fatal_present']==candidate['fatal_present'],
  'event_phase_order_valid':ref['order_valid'] and candidate['order_valid']}
 result[case]={'pass':all(checks.values()),'checks':checks,'reference_callbacks':ref['callbacks'],'candidate_callbacks':candidate['callbacks'],
  'reference_request_count':ref['request_count'],'candidate_request_count':candidate['request_count'],
  'reference_tracking_count':ref['tracking_count'],'candidate_tracking_count':candidate['tracking_count'],
  'reference_only_requests':dict(only_ref),'candidate_only_requests':dict(only_candidate),
  'reference_fatal':ref['fatal'],'candidate_fatal':candidate['fatal']}
summary={'reference_output':str(a.reference_output or a.output),'complete':complete,'cases_completed':len(result),'cases_total':len(cases),'passed':sum(x['pass'] for x in result.values()),'cases':result}
(a.output/'parity-results.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
print('Completed',len(result),'/',len(cases),'passed',summary['passed'])
for case,value in result.items():
 print(case,'PASS' if value['pass'] else 'MISMATCH',value['reference_request_count'],value['candidate_request_count'],value['candidate_callbacks'])
 if not value['pass']:print(json.dumps(value,ensure_ascii=False))
if a.require_complete and (not complete or summary['passed']!=len(cases)):raise SystemExit(1)
