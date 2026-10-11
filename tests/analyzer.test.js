// Çalıştırma: npm test  (Node 18+)
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

// Tarayıcı dosyalarını ortak bir bağlamda yükle (localStorage taklidi ile)
function loadContext() {
  const store = new Map();
  const ctx = {
    console: { log() {}, warn() {}, error() {}, info() {} },
    localStorage: {
      getItem: k => (store.has(k) ? store.get(k) : null),
      setItem: (k, v) => store.set(k, String(v)),
      removeItem: k => store.delete(k)
    },
    TextEncoder, btoa: s => Buffer.from(s, 'binary').toString('base64')
  };
  vm.createContext(ctx);
  const root = path.join(__dirname, '..');
  for (const f of ['storage.js', 'analyzer.js']) {
    vm.runInContext(fs.readFileSync(path.join(root, f), 'utf8'), ctx, { filename: f });
  }
  return ctx;
}

test('extractJson: ```json bloğunu okur', () => {
  const ctx = loadContext();
  ctx.input = 'Merhaba\n```json\n{"a":1}\n```\nsonra';
  const r = vm.runInContext('extractJson(input)', ctx);
  assert.equal(r.data.a, 1);
});

test('extractJson: bloksuz ham JSON içinde metin varsa da okur', () => {
  const ctx = loadContext();
  ctx.input = 'İşte sonuç: {"scene_type":"war_map"} bitti';
  const r = vm.runInContext('extractJson(input)', ctx);
  assert.equal(r.data.scene_type, 'war_map');
});

test('extractJson: bozuk JSON için hata döner', () => {
  const ctx = loadContext();
  ctx.input = '{"a": ';
  const r = vm.runInContext('extractJson(input)', ctx);
  assert.equal(r.data, null);
  assert.ok(r.error);
});

test('Logger: kayıt ekler, filtreler ve Markdown üretir', () => {
  const ctx = loadContext();
  vm.runInContext(`Logger.info('system','ok'); Logger.error('api','a|b boom',{x:1});`, ctx);
  const all = vm.runInContext('Logger.all()', ctx);
  assert.equal(all.length, 2);
  const errs = vm.runInContext(`Logger.filter(Logger.all(), {level:'error'})`, ctx);
  assert.equal(errs.length, 1);
  const md = vm.runInContext(`Logger.toMarkdown(Logger.all())`, ctx);
  assert.match(md, /🔴 error/);
  assert.match(md, /a\\\|b boom/); // tablo içi '|' kaçışlanmış
});

test('Logger: 500 kaydı aşınca eski kayıtları siler', () => {
  const ctx = loadContext();
  vm.runInContext(`for (let i=0;i<510;i++) Logger.debug('system','m'+i);`, ctx);
  assert.equal(vm.runInContext('Logger.all().length', ctx), 500);
});
