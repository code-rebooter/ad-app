#!/usr/bin/env python3
"""Local TLS/VAST/media/OMID fixtures for real-device tests of the migrated SDK."""
import argparse, json, re, socket, ssl, threading, time
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, urlsplit
from xml.sax.saxutils import escape

LOCK = threading.RLock()
CASES, HITS = {}, {}
ARGS = None
BASE = ''


def existing_vast(run, kind):
    """Keep the user's saved pod and media choices; relocate only fixture URLs."""
    root = ET.fromstring((ARGS.local_vast / 'vast.xml').read_text())
    for element in root.iter():
        text = (element.text or '').strip()
        if text.startswith(('http://', 'https://')):
            source = urlsplit(text)
            if source.hostname in ('192.168.0.141', '127.0.0.1', 'localhost'):
                element.text = BASE + source.path + '?' + source.query + ('&' if source.query else '') + 'run_id=' + quote(run)
    if kind in ('local_single', 'buffer_resume', 'buffer_stall', 'startup_timeout', 'media_truncated'):
        for ad in list(root)[1:]: root.remove(ad)
        root[0].attrib.pop('sequence', None)
    if kind == 'pod_media_error':
        for media in root[1].iter('MediaFile'): media.text = BASE + '/missing.mp4?run_id=' + quote(run)
    # The original pod is served without adding measurement. Separate cases
    # explicitly add the same independent verifier used in the existing suite.
    if kind != 'local_pod':
        root.set('version', '4.1')
        for index, ad in enumerate(root.findall('Ad'), 1):
            original = ET.fromstring('<Ad>' + inline(run, index).split('<InLine>', 1)[1].split('</InLine>', 1)[0] + '</Ad>')
            target = ad.find('InLine')
            for tag in ('AdVerifications', 'Extensions'):
                target.append(original.find(tag))
    return ET.tostring(root, encoding='unicode')


def record(kind, **values):
    with LOCK:
        entry = json.dumps({'at': time.time(), 'kind': kind, **values}, ensure_ascii=False) + '\n'
        with (ARGS.output / 'server-events.jsonl').open('a') as stream:
            stream.write(entry)
        run = values.get('run_id', '')
        if re.fullmatch(r'[A-Za-z0-9._-]+', run) and run not in ('.','..'):
            directory = ARGS.output / run; directory.mkdir(exist_ok=True)
            with (directory / 'live-server-events.jsonl').open('a') as stream: stream.write(entry)


def inline(run, index=1, seconds=20, media='video.mp4', sequence=''):
    def url(path): return BASE + path + '?run_id=' + quote(run) + '&ad=' + str(index)
    tracks = ''.join('<Tracking event="' + event + '"><![CDATA[' + url('/pixel/' + event) + ']]></Tracking>'
                     for event in ('start', 'firstQuartile', 'midpoint', 'thirdQuartile', 'complete', 'mute', 'unmute', 'pause', 'resume'))
    extension = (ARGS.activeview.read_text().replace('http://127.0.0.1:18769', BASE)
                 .replace('?run=RUN_ID', '?run_id=' + quote(run)))
    duration = f'{seconds // 3600:02}:{seconds // 60 % 60:02}:{seconds % 60:02}.000'
    return f'''<Ad id="fixture-{escape(run)}-{index}" {sequence}><InLine><AdSystem version="1">Local SDK regression</AdSystem>
<AdTitle>IMA SDK device fixture</AdTitle><AdServingId>{escape(run)}-{index}</AdServingId>
<Error><![CDATA[{url('/pixel/error')}&code=[ERRORCODE]]]></Error><Impression><![CDATA[{url('/pixel/impression')}]]></Impression>
<AdVerifications><Verification vendor="iabtechlab.com-omid"><JavaScriptResource apiFramework="omid" browserOptional="false"><![CDATA[{url('/verification.js')}]]></JavaScriptResource>
<TrackingEvents><Tracking event="verificationNotExecuted"><![CDATA[{url('/pixel/not-executed')}&reason=[REASON]]]></Tracking></TrackingEvents>
<VerificationParameters><![CDATA[{{"run":"{run}","ad":{index}}}]]></VerificationParameters></Verification></AdVerifications>
<Creatives><Creative id="fixture-creative-{index}"><UniversalAdId idRegistry="unknown">fixture-creative-{index}</UniversalAdId><Linear><Duration>{duration}</Duration>
<TrackingEvents>{tracks}</TrackingEvents><VideoClicks><ClickThrough><![CDATA[{url('/landing')}]]></ClickThrough><ClickTracking><![CDATA[{url('/pixel/click')}]]></ClickTracking></VideoClicks>
<MediaFiles><MediaFile delivery="progressive" type="video/mp4" width="854" height="480"><![CDATA[{url('/' + media)}]]></MediaFile></MediaFiles>
</Linear></Creative></Creatives><Extensions>{extension}</Extensions></InLine></Ad>'''


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_): pass
    def reply(self, body=b'', status=200, mime='application/json', headers=None):
        if not isinstance(body, bytes): body = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', mime); self.send_header('Content-Length', str(len(body)))
        self.send_header('Connection', 'close'); self.close_connection = True
        self.send_header('Access-Control-Allow-Origin', self.headers.get('Origin', '*'))
        self.send_header('Access-Control-Allow-Credentials', 'true')
        self.send_header('Access-Control-Allow-Methods', 'GET, HEAD, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', '*'); self.send_header('Cache-Control', 'no-store')
        for key, value in (headers or {}).items(): self.send_header(key, value)
        self.end_headers()
        if self.command != 'HEAD':
            try: self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError, ssl.SSLError): pass
    def do_OPTIONS(self): self.reply(b'', 204, 'text/plain')
    def do_HEAD(self): self.do_GET()
    def do_POST(self):
        route = urlsplit(self.path).path
        raw = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        try: body = json.loads(raw)
        except ValueError: self.reply({'error': 'bad json'}, 400); return
        run = body.get('request_id', body.get('run_id', parse_qs(urlsplit(self.path).query).get('run_id', ['unknown'])[0]))
        if route == '/__case':
            with LOCK: CASES[body['run_id']] = body
            record('case', body=body); self.reply({'ok': True}); return
        if route == '/batch':
            for item in body.get('events', []): record(item['kind'],run_id=run,body=item['body'])
            record('batch_received',run_id=run,count=len(body.get('events',[])))
            self.reply({'code':200}); return
        if route == '/address':
            case = CASES.get(run, {}); kind = case.get('kind', '')
            record('address', run_id=run, body=body)
            if kind == 'address_timeout': time.sleep(12)
            if kind == 'address_http_error': self.reply({'code': 503}, 503); return
            if kind == 'address_invalid': self.reply(b'{invalid'); return
            if kind == 'address_rejected': self.reply({'code': 400, 'data': {}}); return
            self.reply({'code': 100000, 'data': {'adsUrl': BASE + '/vast?run_id=' + quote(run),
                'beaconingType': 2, 'adType': 1, 'adPlatformCode': '0', 'adUnitID': 'local-unit',
                'guaranteeLink': BASE + '/inner?run_id=' + quote(run) + '&fixture_fallback=1' if kind == 'guarantee' else ''}}); return
        if route.endswith('/google-gam/patch'):
            case = CASES.get(run, {})
            record('patch', run_id=run, body=body)
            if case.get('delay'): time.sleep(case['delay'])
            if case.get('kind') == 'patch_error':
                self.reply({'code': 400, 'msg': 'fixture patch rejection', 'data': {}}, 400); return
            if case.get('kind') == 'patch_bad_json': self.reply(b'{broken', mime='application/json'); return
            if case.get('kind') == 'patch_missing_url': self.reply({'code': 200, 'data': {}}); return
            if case.get('kind') == 'patch_rejected': self.reply({'code': 403, 'msg': 'fixture business rejection'}); return
            if case.get('kind') == 'patch_redirect': self.reply(b'', 302, headers={'Location': BASE + '/redirect-target'}); return
            path = '/inner' if 'fixture_fallback=1' in body.get('ad_tag_url', '') else '/vast'
            final = BASE + path + '?run_id=' + quote(run) + '&server_value=a%2Bb%2Fc%3D&x=one&x=two&vpmute=wrong'
            record('patch_ready', run_id=run, url=final)
            self.reply({'code': 200, 'data': {'ad_tag_url': final}}); return
        record(route.strip('/'), run_id=run, body=body)
        if route == '/audit':
            directory = ARGS.output / run; directory.mkdir(exist_ok=True)
            (directory / 'server-runtime-audit.json').write_text(json.dumps(body['audit'], ensure_ascii=False, indent=2))
        self.reply({'code': 200, 'data': {}})
    def do_GET(self):
        parsed = urlsplit(self.path); query = parse_qs(parsed.query, keep_blank_values=True)
        run = query.get('run_id', ['unknown'])[0]; case = CASES.get(run, {}); kind = case.get('kind', 'single')
        if parsed.path == '/health': self.reply({'ok': True}); return
        if parsed.path == '/landing': self.reply(b'<html><body>Local SDK click fixture</body></html>', mime='text/html'); return
        if parsed.path == '/log':
            record('omid', run_id=run, transport=query.get('transport', ['omid'])[0], attempt=query.get('attempt', ['1'])[0], message=query.get('msg', [''])[0]); self.reply(b'', 204, 'text/plain'); return
        if parsed.path == '/activeview':
            record('activeview', run_id=run, query=query); self.reply(b'', 204, 'text/plain'); return
        if parsed.path.startswith('/pixel/'):
            record('tracking', run_id=run, event=parsed.path.rsplit('/', 1)[-1], query=query)
            self.reply(b'', 204, 'text/plain'); return
        if parsed.path == '/track':
            record('tracking', run_id=run, event=query.get('event', [''])[0], query=query)
            self.reply(b'', 204, 'text/plain'); return
        if parsed.path in ('/vast', '/inner'):
            with LOCK:
                HITS[run] = HITS.get(run, 0) + 1; hit = HITS[run]
            record('vast', run_id=run, query=query, path=parsed.path, hit=hit, user_agent=self.headers.get('User-Agent'))
            if kind == 'slow_vast':
                prefix = b'<VAST version="4.1"><!--' + b'x' * (270*1024) + b'-->'
                tail = b'</VAST>'
                self.send_response(200); self.send_header('Content-Type','application/xml')
                self.send_header('Content-Length',str(len(prefix)+len(tail))); self.send_header('Connection','close'); self.end_headers()
                self.close_connection = True
                try:
                    self.wfile.write(prefix); self.wfile.flush(); record('vast_body_prefix',run_id=run,bytes=len(prefix))
                    self.connection.settimeout(12)
                    if not self.connection.recv(1): record('vast_body_peer_closed',run_id=run)
                except (ConnectionResetError, BrokenPipeError, ssl.SSLError): record('vast_body_peer_closed',run_id=run)
                except socket.timeout:
                    record('vast_body_timeout',run_id=run)
                    try: self.wfile.write(tail); self.wfile.flush()
                    except (OSError,ssl.SSLError): pass
                return
            if kind == 'vast_http_error': self.reply(b'fixture unavailable', 503, 'text/plain'); return
            if kind == 'vast_timeout': time.sleep(12)
            if kind == 'fallback' and hit == 1:
                self.connection.shutdown(socket.SHUT_RDWR); self.connection.close(); return
            if kind == 'wrapper_empty' and parsed.path == '/inner': xml = '<VAST version="4.1" />'
            elif kind == 'inline_empty': xml = '<VAST version="4.1"><Ad><InLine><AdSystem>fixture</AdSystem><AdTitle>no media</AdTitle><Creatives/></InLine></Ad></VAST>'
            elif kind == 'guarantee' and parsed.path == '/vast': xml = '<VAST version="4.1" />'
            elif kind.startswith('local_') or kind in ('buffer_resume','buffer_stall','startup_timeout','media_truncated','pod_media_error'):
                xml = existing_vast(run, kind)
            elif kind == 'empty': xml = '<VAST version="4.1" />'
            elif kind == 'invalid': xml = '<VAST invalid xml'
            elif kind == 'pod': xml = '<VAST version="4.1">' + inline(run, 1, 8, 'short.mp4', 'sequence="1"') + inline(run, 2, 8, 'short.mp4', 'sequence="2"') + '</VAST>'
            elif kind in ('wrapper','wrapper_empty') and parsed.path == '/vast':
                xml = f'<VAST version="4.1"><Ad><Wrapper><AdSystem>fixture</AdSystem><VASTAdTagURI><![CDATA[{BASE}/inner?run_id={quote(run)}]]></VASTAdTagURI><Impression><![CDATA[{BASE}/pixel/wrapper_impression?run_id={quote(run)}]]></Impression><Creatives/></Wrapper></Ad></VAST>'
            else:
                seconds = 210 if kind == 'long' else 8 if kind == 'short' else 20
                media = 'missing.mp4' if kind == 'media_error' else 'long.mp4' if kind == 'long' else 'short.mp4' if kind == 'short' else 'video.mp4'
                xml = '<VAST version="4.1">' + inline(run, 1, seconds, media) + '</VAST>'
            if case.get('omit_verifier'):
                tree = ET.fromstring(xml)
                for parent in tree.iter():
                    for child in list(parent):
                        if child.tag == 'AdVerifications': parent.remove(child)
                xml = ET.tostring(tree, encoding='unicode')
            directory = ARGS.output / run; directory.mkdir(exist_ok=True)
            (directory / ('wrapper.xml' if parsed.path == '/vast' and kind == 'wrapper' else 'vast.xml')).write_text(xml)
            self.reply(xml.encode(), mime='application/xml'); return
        if parsed.path == '/verification.js':
            script = ARGS.verification.read_text().replace('http://localhost:66/sendmessage?msg=', BASE + '/log?run_id=' + quote(run) + '&msg=')
            if ARGS.direct_verification_log:
                # Observe the *same real verification callbacks* through an
                # independent HTTP path as well as OMID sendUrl. This separates
                # callback loss from sendUrl teardown/transport loss.
                script = script.replace('this.verificationClient_.sendUrl(a);', """
                    (function(url) {
                      function send(attempt) {
                        function retry() { if (attempt < 4) setTimeout(function(){send(attempt+1);}, 250); }
                        var target = url + '&transport=direct&attempt=' + attempt;
                        try {
                          if (typeof fetch === 'function') {
                            fetch(target, {mode:'cors', cache:'no-store', keepalive:true})
                              .then(function(response){if(!response.ok) retry();}).catch(retry);
                          } else {
                            var request = new XMLHttpRequest(); request.open('GET', target, true); request.timeout=4000;
                            request.onerror=retry; request.ontimeout=retry;
                            request.onload=function(){if(request.status<200 || request.status>=300) retry();}; request.send();
                            (window.__fixtureRequests=window.__fixtureRequests||[]).push(request);
                          }
                        } catch (ignored) { retry(); }
                      }
                      send(1);
                    })(a);
                    this.verificationClient_.sendUrl(a);
                """)
            record('verification', run_id=run); self.reply(script.encode(), mime='application/javascript'); return
        if parsed.path in ('/video.mp4','/short.mp4','/long.mp4'):
            media_file = ARGS.output / parsed.path.lstrip('/')
        elif parsed.path in ('/ad-1.mp4','/ad-2.mp4','/ad-3.mp4'):
            media_file = ARGS.local_vast / parsed.path.lstrip('/')
        else: media_file = None
        if media_file is not None:
            content = media_file.read_bytes(); start, end, code = 0, len(content) - 1, 200
            match = re.match(r'bytes=(\d+)-(\d*)', self.headers.get('Range', ''))
            if match:
                start = int(match[1]); end = min(end, int(match[2])) if match[2] else end; code = 206
            headers = {'Accept-Ranges': 'bytes'}
            if code == 206: headers['Content-Range'] = f'bytes {start}-{end}/{len(content)}'
            record('media', run_id=run, path=parsed.path, start=start, end=end)
            if kind == 'startup_timeout': time.sleep(12)
            if kind in ('buffer_resume','buffer_stall'):
                self.send_response(code)
                self.send_header('Content-Type', 'video/mp4'); self.send_header('Content-Length', str(end-start+1))
                self.send_header('Connection', 'close')
                for key, value in headers.items(): self.send_header(key, value)
                self.end_headers(); self.close_connection = True
                data = content[start:end+1]
                try:
                    prefix = min(len(data), 192*1024) if kind == 'buffer_resume' or start == 0 else 0
                    self.wfile.write(data[:prefix]); self.wfile.flush()
                    record('media_stall_begin', run_id=run)
                    if kind == 'buffer_resume': time.sleep(10)
                    else:
                        # Do not hand each retry another 192 KiB of usable video.
                        # A slow trickle avoids a socket-read timeout while keeping
                        # decoded playback stalled, so the SDK guard is exercised.
                        for tick in range(17):
                            time.sleep(5)
                            self.wfile.write(data[prefix:prefix+256]); self.wfile.flush(); prefix += 256
                    self.wfile.write(data[prefix:]); self.wfile.flush()
                    record('media_stall_end', run_id=run)
                except (BrokenPipeError, ConnectionResetError, ssl.SSLError): pass
                return
            if kind == 'media_truncated': content = b'not an mp4 file'; start, end = 0, len(content)-1
            self.reply(content[start:end+1], code, 'video/mp4', headers); return
        self.reply(b'not found', 404, 'text/plain')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cert', type=Path, required=True); parser.add_argument('--key', type=Path, required=True)
    parser.add_argument('--verification', type=Path, required=True); parser.add_argument('--activeview', type=Path, required=True)
    parser.add_argument('--local-vast', type=Path, help='Directory containing the existing vast.xml and ad-1/2/3.mp4')
    parser.add_argument('--direct-verification-log', action='store_true', help='Also collect actual verifier callbacks with independent image HTTP')
    parser.add_argument('--port', type=int, default=19443); ARGS = parser.parse_args()
    ARGS.output.mkdir(parents=True, exist_ok=True); BASE = 'https://127.0.0.1:' + str(ARGS.port)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); context.load_cert_chain(ARGS.cert, ARGS.key)
    class ThreadedTlsServer(ThreadingHTTPServer):
        request_queue_size = 128
        # Handshake in the connection worker: a slow Android TLS client must not
        # prevent accept() from servicing the other device's media/tracking/report sockets.
        def process_request_thread(self, request, client_address):
            try:
                request.settimeout(15)
                secured = context.wrap_socket(request, server_side=True)
            except (ssl.SSLError, OSError) as error:
                record('tls_connection_error', error=str(error))
                request.close()
                return
            super().process_request_thread(secured, client_address)
    server = ThreadedTlsServer(('127.0.0.1', ARGS.port), Handler)
    server.serve_forever()
