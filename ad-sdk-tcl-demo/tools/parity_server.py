#!/usr/bin/env python3
"""Deterministic local VAST inputs for original/candidate differential tests. No upstream requests."""
from pathlib import Path
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlsplit,parse_qs
import argparse,json,time,threading,xml.etree.ElementTree as E,copy
p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);p.add_argument('--port',type=int,default=18888);a=p.parse_args()
a.output.parent.mkdir(parents=True,exist_ok=True);lock=threading.Lock()
assets=Path(__file__).resolve().parents[1]/'src/main/assets'
base=E.parse(assets/'tcl-hk-pod.xml').getroot()
def single(case):
 root=E.Element('VAST',version='3.0');ad=copy.deepcopy(base.findall('Ad')[-1]);ad.set('sequence','1');root.append(ad)
 for elem in ad.iter():
  if elem.text and 'http://127.0.0.1:18888' in elem.text:
   elem.text=elem.text.replace('http://127.0.0.1:18888/',f'http://127.0.0.1:{a.port}/{case}/')
 tracking=ad.find('.//Linear/TrackingEvents')
 for name in ['creativeView']:
  E.SubElement(tracking,'Tracking',event=name).text=f'http://127.0.0.1:{a.port}/{case}/extra/{name}'
 return root

def xml(case):
 if case=='pod':return E.tostring(base)
 if case=='empty':return b'<VAST version="3.0"/>'
 if case=='malformed':return b'<VAST><Ad><'
 if case=='wrapper' or case=='cycle':
  target='cycle' if case=='cycle' else 'wrapper-child'
  return f'<VAST version="3.0"><Ad><Wrapper><AdSystem>fixture</AdSystem><VASTAdTagURI>http://127.0.0.1:{a.port}/tag/{target}</VASTAdTagURI><Impression>http://127.0.0.1:{a.port}/{case}/outer-impression</Impression><Error>http://127.0.0.1:{a.port}/{case}/outer-error?code=[ERRORCODE]</Error><Creatives><Creative><Linear><TrackingEvents><Tracking event="start">http://127.0.0.1:{a.port}/{case}/outer-start</Tracking></TrackingEvents></Linear></Creative></Creatives></Wrapper></Ad></VAST>'.encode()
 root=single(case)
 if case=='http-single':
  for node in root.findall('.//MediaFile'):node.text=f'http://127.0.0.1:{a.port}/media/ad-3.mp4'
 if case=='unsupported':
  for node in root.findall('.//MediaFile'):node.set('type','application/unknown')
 if case=='media-fallback':
  for i,node in enumerate(root.findall('.//MediaFile')):
   if node.get('width')=='640' and node.get('bitrate')=='640':node.text=f'http://127.0.0.1:{a.port}/missing.mp4'
 return E.tostring(root)
class Handler(BaseHTTPRequestHandler):
 protocol_version='HTTP/1.1'
 def do_GET(self):
  path=urlsplit(self.path).path
  row={'time':time.time(),'path':self.path,'method':'GET','ua':self.headers.get('User-Agent'),'content_type':self.headers.get('Content-Type'),'cookie':self.headers.get('Cookie')}
  with lock,a.output.open('a') as f:f.write(json.dumps(row)+'\n')
  status=200;body=b'';ctype='text/plain';content_range=None
  if path.startswith('/tag/'):
   case=path.rsplit('/',1)[-1]
   if case=='slow':time.sleep(4);body=xml('single')
   elif case=='http-timeout':time.sleep(12);body=xml('single')
   elif case=='http-error':status=503
   else:body=xml(case)
   ctype='application/xml'
  elif path=='/media/ad-3.mp4':
   body=(assets/'ad-3.mp4').read_bytes();ctype='video/mp4'
   requested=self.headers.get('Range')
   if requested and requested.startswith('bytes='):
    start_text,end_text=requested[6:].split('-',1)
    start=int(start_text or '0');end=min(int(end_text) if end_text else len(body)-1,len(body)-1)
    if start>=len(body) or start>end:status=416;body=b''
    else:content_range=f'bytes {start}-{end}/{len(body)}';body=body[start:end+1];status=206
  elif path=='/missing.mp4':status=404
  self.send_response(status);self.send_header('Content-Type',ctype);self.send_header('Content-Length',str(len(body)))
  if content_range:self.send_header('Content-Range',content_range)
  self.end_headers()
  try:self.wfile.write(body)
  except (BrokenPipeError,ConnectionResetError):pass
 def log_message(self,*args):pass
print('Parity server ready',flush=True);ThreadingHTTPServer(('127.0.0.1',a.port),Handler).serve_forever()
