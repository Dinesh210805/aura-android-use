const { createSign } = require('node:crypto');
const { summarize } = require('./_summarize.js');

const CACHE_SECONDS = 6 * 60 * 60;
const FIELDS = ['buildType', 'deviceHash', 'lastSeen', 'locale', 'timeZone'];
const PAGE_SIZE = 300;
const MAX_PAGES = 200;
const TIMEOUT_MS = 8000;

const base64url = (value) => Buffer.from(value).toString('base64url');

/** Exchanges the service account for a short-lived Firestore access token. */
async function accessToken({ client_email: email, private_key: key }) {
  const now = Math.floor(Date.now() / 1000);
  const claims = {
    iss: email,
    scope: 'https://www.googleapis.com/auth/datastore',
    aud: 'https://oauth2.googleapis.com/token',
    iat: now,
    exp: now + 300,
  };
  const unsigned = `${base64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }))}.${base64url(JSON.stringify(claims))}`;
  const signature = createSign('RSA-SHA256').update(unsigned).sign(key, 'base64url');
  const response = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion: `${unsigned}.${signature}`,
    }),
    signal: AbortSignal.timeout(TIMEOUT_MS),
  });
  if (!response.ok) throw new Error(`token request failed: ${response.status}`);
  return (await response.json()).access_token;
}

/**
 * Reads every `devices/{uid}` row, limited to the fields in [FIELDS].
 * - Why the mask: rows also hold the owner's users' emails and names, and a field that is never
 *   fetched cannot leak from here.
 * - ponytail: reads every row on each refresh, at about one read per install. Past a few thousand
 *   installs, keep a running total instead.
 */
async function readRows(projectId, token) {
  const rows = [];
  let pageToken = '';
  for (let page = 0; page < MAX_PAGES; page += 1) {
    const url = new URL(`https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/devices`);
    url.searchParams.set('pageSize', PAGE_SIZE);
    FIELDS.forEach((field) => url.searchParams.append('mask.fieldPaths', field));
    if (pageToken) url.searchParams.set('pageToken', pageToken);
    const response = await fetch(url, {
      headers: { Authorization: `Bearer ${token}` },
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
    if (!response.ok) throw new Error(`Firestore read failed: ${response.status}`);
    const body = await response.json();
    for (const doc of body.documents || []) {
      const f = doc.fields || {};
      rows.push({
        id: doc.name.split('/').pop(),
        deviceHash: f.deviceHash?.stringValue || '',
        buildType: f.buildType?.stringValue || '',
        timeZone: f.timeZone?.stringValue || '',
        locale: f.locale?.stringValue || '',
        lastSeen: f.lastSeen?.timestampValue || '',
      });
    }
    pageToken = body.nextPageToken;
    if (!pageToken) return rows;
  }
  throw new Error('too many pages of devices');
}

/**
 * `GET /api/install-stats`: public install totals for the download page, in the same shape as
 * install-stats.json.
 * - Contract: totals only, cached by the CDN for [CACHE_SECONDS].
 * - Why a query string gets a 404: the CDN caches per URL, so `?x=1`, `?x=2` would each reach
 *   Firestore and use up the free reads that the app's block check shares.
 * - Fails: closed with a 503 when `FIREBASE_SERVICE_ACCOUNT` is missing or a read fails. The page
 *   then falls back to install-stats.json.
 * - Reads: `FIREBASE_SERVICE_ACCOUNT` (the JSON key of a service account with Cloud Datastore
 *   Viewer), and Firestore `devices/{uid}`.
 */
module.exports = async function handler(req, res) {
  if (req.method !== 'GET' || req.url.includes('?')) return res.status(404).end();
  try {
    const account = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
    const rows = await readRows(account.project_id, await accessToken(account));
    res.setHeader('Cache-Control', `public, s-maxage=${CACHE_SECONDS}, stale-while-revalidate=86400`);
    return res.status(200).json(summarize(rows, new Date().toISOString().slice(0, 10)));
  } catch (error) {
    console.error('install-stats:', error.message);
    res.setHeader('Cache-Control', 'no-store');
    return res.status(503).json({ error: 'unavailable' });
  }
};
