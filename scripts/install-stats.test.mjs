// Checks the download page's live install counts. Run: node --test scripts/install-stats.test.mjs
import assert from 'node:assert/strict';
import { generateKeyPairSync, createVerify } from 'node:crypto';
import { createRequire } from 'node:module';
import { test } from 'node:test';

const require = createRequire(import.meta.url);
const { summarize } = require('../aura-android-use-website/api/_summarize.js');
const handler = require('../aura-android-use-website/api/install-stats.js');

const row = (id, fields) => ({ id, deviceHash: '', buildType: 'release', timeZone: '', locale: '', lastSeen: '', ...fields });

test('counts one handset per device hash, using its newest row', () => {
  const stats = summarize([
    row('a', { deviceHash: 'h1', timeZone: 'Asia/Kolkata', lastSeen: '2026-01-01T00:00:00Z' }),
    row('b', { deviceHash: 'h1', timeZone: 'Europe/London', lastSeen: '2026-02-01T00:00:00Z' }),
  ], '2026-11-06');
  assert.deepEqual(stats, { asOf: '2026-11-06', installs: 1, countryCount: 1, countries: { GB: 1 } });
});

test('leaves out debug builds and keeps rows without a hash separate', () => {
  const stats = summarize([
    row('a', { buildType: 'debug', timeZone: 'Asia/Kolkata' }),
    row('b', { timeZone: 'Asia/Kolkata' }),
    row('c', { timeZone: 'Asia/Kolkata' }),
  ], '2026-11-06');
  assert.equal(stats.installs, 2);
  assert.deepEqual(stats.countries, { IN: 2 });
});

test('uses the locale region when the time zone has no country', () => {
  const stats = summarize([
    row('a', { timeZone: 'UTC', locale: 'en_IN' }),
    row('b', { timeZone: 'Etc/GMT', locale: 'ta_IN_#Latn' }),
    row('c', { timeZone: 'UTC', locale: 'hi' }),
  ], '2026-11-06');
  assert.equal(stats.installs, 3, 'a handset with no country still counts as an install');
  assert.deepEqual(stats.countries, { IN: 2 });
});

test('the endpoint signs in, asks for no personal fields and returns totals only', async () => {
  const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  process.env.FIREBASE_SERVICE_ACCOUNT = JSON.stringify({
    client_email: 'stats@example.iam.gserviceaccount.com',
    private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }),
    project_id: 'demo',
  });
  const seen = [];
  globalThis.fetch = async (url, init = {}) => {
    seen.push({ url: String(url), init });
    if (String(url).includes('oauth2')) return Response.json({ access_token: 'token' });
    const pageTwo = String(url).includes('pageToken=next');
    const doc = (id, tz) => ({
      name: `projects/demo/databases/(default)/documents/devices/${id}`,
      fields: { deviceHash: { stringValue: id }, buildType: { stringValue: 'release' }, timeZone: { stringValue: tz } },
    });
    return Response.json(pageTwo
      ? { documents: [doc('b', 'America/New_York')] }
      : { documents: [doc('a', 'Asia/Kolkata')], nextPageToken: 'next' });
  };

  const out = { headers: {} };
  const res = {
    setHeader: (k, v) => { out.headers[k.toLowerCase()] = v; },
    status(c) { out.status = c; return this; },
    json(b) { out.body = b; },
    end() {},
  };
  await handler({ method: 'GET', url: '/api/install-stats' }, res);

  assert.equal(out.status, 200);
  assert.equal(out.body.installs, 2);
  assert.deepEqual(out.body.countries, { IN: 1, US: 1 });
  assert.deepEqual(Object.keys(out.body).sort(), ['asOf', 'countries', 'countryCount', 'installs']);
  assert.match(out.headers['cache-control'], /s-maxage=\d+/);

  const assertion = new URLSearchParams(seen[0].init.body).get('assertion');
  const [head, claims, sig] = assertion.split('.');
  assert.ok(createVerify('RSA-SHA256').update(`${head}.${claims}`).verify(publicKey, Buffer.from(sig, 'base64url')));

  const reads = seen.filter((s) => s.url.includes('firestore'));
  assert.equal(reads.length, 2, 'follows the page token');
  for (const { url, init } of reads) {
    assert.equal(init.headers.Authorization, 'Bearer token');
    const fields = new URL(url).searchParams.getAll('mask.fieldPaths').sort();
    assert.deepEqual(fields, ['buildType', 'deviceHash', 'lastSeen', 'locale', 'timeZone']);
  }
});

test('a query string or other method is refused without reading Firestore', async () => {
  let calls = 0;
  globalThis.fetch = async () => { calls += 1; return Response.json({}); };
  for (const req of [{ method: 'GET', url: '/api/install-stats?x=1' }, { method: 'POST', url: '/api/install-stats' }]) {
    const out = {};
    const res = { setHeader() {}, status(c) { out.status = c; return this; }, json() {}, end() {} };
    await handler(req, res);
    assert.equal(out.status, 404);
  }
  assert.equal(calls, 0);
});
