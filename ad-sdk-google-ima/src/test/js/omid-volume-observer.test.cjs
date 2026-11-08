const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const java = fs.readFileSync(path.join(__dirname,
  '../../main/java/com/smart/android/googleima/internal/GoogleImaRuntimeBridge.java'), 'utf8');
const source = java.split('private static final String OMID_MEDIA_VOLUME_SCRIPT = """')[1].split('""";')[0];
let passed = 0;
function test(name, body) { body(); passed++; process.stdout.write('PASS ' + name + '\n'); }
function fixture(late = false) {
  const observed = [], forwarded = [], metadata = {volume: 0.25, untouched: true};
  let receiver;
  const service = {handleExportedMessage(message) { forwarded.push(message); return message; }};
  const window = {
    __smartImaAudit: {onOmidMediaEvent(...args) { observed.push(args); }, onActiveViewMediaVolume() {}},
    ima: {common: {getVideoMetadata() { return metadata; }}},
    addEventListener(type, callback) { receiver = callback; },
    setInterval(callback) { this.tick = callback; return 1; }, clearInterval() {},
    omid: late ? undefined : {v1_SessionServiceCommunication: service}
  };
  const context = vm.createContext({window}); vm.runInContext(source, context);
  return {window, observed, forwarded, metadata, service, context, receive(message) { receiver({data: message}); }};
}
function message(method, value, guid) {
  return {omid_message_method: method, omid_message_guid: guid,
    omid_message_args: method.endsWith('.start') ? [20, value] : [value], preserved: true};
}
test('installation emits no measurement events', () => {
  const f = fixture(); assert.equal(f.observed.length, 0); assert.equal(f.forwarded.length, 0);
});
test('start retains original identity and all arguments', () => {
  const f = fixture(), m = message('SessionService.start', 0, 'start');
  const original = JSON.stringify(m);
  assert.equal(f.service.handleExportedMessage(m), m);
  assert.equal(f.forwarded[0], m); assert.equal(JSON.stringify(m), original);
  assert.equal(f.observed[0][1], 0);
});
test('volume changes preserve fractional and endpoint values', () => {
  const f = fixture();
  [0, 0.35, 1].forEach((value, i) => {
    const m = message('SessionService.volumeChange', value, String(i));
    assert.equal(f.service.handleExportedMessage(m), m);
  });
  assert.deepEqual(f.observed.map(x => x[1]), [0, 0.35, 1]);
});
test('string encoded arguments are observed without rewriting encoding', () => {
  const f = fixture(), m = message('SessionService.start', 0.5, 'encoded');
  m.omid_message_args = JSON.stringify(m.omid_message_args);
  const original = m.omid_message_args; f.service.handleExportedMessage(m);
  assert.equal(m.omid_message_args, original); assert.equal(f.observed[0][1], 0.5);
});
test('capture and service paths do not double count one GUID', () => {
  const f = fixture(), m = message('SessionService.start', 1, 'same');
  f.receive(m); f.service.handleExportedMessage(m); assert.equal(f.observed.length, 1);
});
test('invalid and missing media volumes are not manufactured', () => {
  const f = fixture();
  [null, undefined, '0', -1, 2, NaN, Infinity].forEach((value, i) =>
    f.service.handleExportedMessage(message('SessionService.start', value, String(i))));
  assert.equal(f.observed.length, 0); assert.equal(f.forwarded.length, 7);
});
test('metadata preserves the original object and value', () => {
  const f = fixture(); assert.equal(f.window.ima.common.getVideoMetadata(), f.metadata);
  assert.equal(f.metadata.volume, 0.25);
});
test('delayed OM service is observed without creating its events', () => {
  const f = fixture(true); f.window.omid = {v1_SessionServiceCommunication: f.service};
  f.window.tick(); assert.equal(f.forwarded.length, 0);
  f.service.handleExportedMessage(message('SessionService.start', 0, 'late'));
  assert.equal(f.observed.length, 1);
});
test('reinstallation is idempotent', () => {
  const f = fixture(); vm.runInContext(source, f.context);
  f.service.handleExportedMessage(message('SessionService.start', 1, 'once'));
  assert.equal(f.forwarded.length, 1); assert.equal(f.observed.length, 1);
});
test('first message installs a service arriving after the polling window', () => {
  const f = fixture(true);
  for (let i = 0; i < 201; i++) f.window.tick();
  f.window.omid = {v1_SessionServiceCommunication: f.service};
  f.receive(message('SessionService.start', 0, 'very-late'));
  f.service.handleExportedMessage(message('SessionService.volumeChange', 0.5, 'later-change'));
  assert.deepEqual(f.observed.map(x => x[1]), [0, 0.5]);
  assert.equal(f.forwarded.length, 1);
});
test('observer bridge failures do not block the SDK message', () => {
  const f = fixture(); f.window.__smartImaAudit.onOmidMediaEvent = () => { throw Error('offline'); };
  const m = message('SessionService.start', 0, 'failed');
  assert.equal(f.service.handleExportedMessage(m), m); assert.equal(f.forwarded.length, 1);
});
console.log(JSON.stringify({passed, failed: 0}));
