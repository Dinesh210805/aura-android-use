// Captures every AURA MCP tool on a real phone for the website's /mcp-tools page: the real
// result, a before/after screenshot of each action, a clip of each action and one walkthrough
// video of the whole run.
//
// Usage (bridge daemon running, phone paired, AURA's MCP Center running, phone on USB adb):
//   REDACT="Dinesh=you;Kolkata=City" node scripts/capture_mcp_tools.mjs
//
// - Contract: nothing personal is shown. The run only visits Settings, Settings search, the Clock
//   timer, Google Photos showing a demo picture (its strip of the owner's other photos is blacked
//   out via MASKS, in shots and video), Files by Google playing a demo tone, and AURA's
//   browser on the-internet.herokuapp.com (a public test site). It pushes those two demo files
//   itself. Notifications are read only from com.android.shell (a demo one it posts) and from AURA.
//   resolve_contact's candidates are real people, so their names and numbers are replaced with
//   placeholders before anything is written. The status bar is put in Android demo mode (fixed
//   clock, no notification icons) for the run and restored after.
// - Writes: aura-android-use-website/mcp-tools-data.js and media/mcp-tools/* (webp shots, mp4 clips,
//   walkthrough.mp4). Needs adb, ffmpeg and scrcpy on PATH (or SCRCPY=<path to scrcpy.exe>).
// - Fails: stops with the bridge's own message when the phone is unreachable; always stops the
//   screen recorder and leaves demo mode.
import { execFileSync, spawn } from 'node:child_process';
import { mkdirSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const URL = process.env.AURA_MCP_URL || 'http://127.0.0.1:4816/mcp';
const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const SITE = join(ROOT, 'aura-android-use-website');
const MEDIA = join(SITE, 'media', 'mcp-tools');
const RAW = join(tmpdir(), 'aura-mcp-capture');
const REDACT = (process.env.REDACT || '').split(';').filter(Boolean).map((p) => p.split('='));
const MAX_TEXT = 9000;
const SHELL_PKG = 'com.android.shell';
// Packages whose events may appear in watch_device_events output; anything else is dropped.
const SAFE_PKGS = /^(com\.android\.settings|com\.android\.settings\.intelligence|com\.oneplus\.deskclock|com\.android\.systemui|com\.android\.shell|com\.aura\.|android$)/;

// ---------- adb / ffmpeg ----------
const adb = (...a) => execFileSync('adb', a, { maxBuffer: 64 << 20 });
const sh = (cmd) => adb('shell', cmd).toString();
const ff = (...a) => execFileSync('ffmpeg', ['-y', '-loglevel', 'error', ...a]);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function demoMode(on) {
  const b = (extra) => sh(`am broadcast -a com.android.systemui.demo -e command ${extra}`);
  if (!on) return b('exit');
  sh('settings put global sysui_demo_allowed 1');
  b('enter');
  b('clock -e hhmm 1200');
  b('notifications -e visible false');
  b('battery -e level 100 -e plugged false');
  b('network -e wifi show -e level 4 -e mobile show -e level 4 -e datatype none');
}

/**
 * Records the screen with scrcpy into one .mkv (it survives being killed, unlike mp4).
 * Why not adb screenrecord: some OEM builds (OnePlus, Android 16) deny it opening any output file.
 */
const rec = {
  proc: null, t0: 0, local: join(RAW, 'run.mkv'),
  async start() {
    // Killing scrcpy loses its unflushed tail, so it stops itself at --time-limit instead; the
    // run takes about 150 s. Raise CAPTURE_SECONDS if the run grows.
    this.proc = spawn(process.env.SCRCPY || 'scrcpy', ['--no-window', '--no-audio', '--no-control', '--max-size', '1440',
      '--video-bit-rate', '8M', '--time-limit', process.env.CAPTURE_SECONDS || '200', '--record', this.local]);
    await new Promise((resolve, reject) => {
      const on = (d) => { if (/Recording started/.test(d)) { this.t0 = Date.now(); resolve(); } };
      this.proc.stdout.on('data', on);
      this.proc.stderr.on('data', on);
      this.proc.on('exit', (c) => reject(new Error(`scrcpy exited (${c}) before recording started`)));
    });
  },
  /** Waits for scrcpy to reach its time limit and finish the file. */
  async stop() {
    console.log('  waiting for the recorder to reach its time limit…');
    await new Promise((r) => (this.proc.exitCode !== null || this.proc.signalCode ? r() : this.proc.on('exit', r)));
  },
};

/** Time windows (host ms) whose screen has a band of private content; y0/y1 are fractions of height. */
const MASKS = [];
const SHOT_AT = {};
const maskAt = (t) => MASKS.find((m) => t >= m.t0 && t <= m.t1);
const band = (m) => `drawbox=x=0:y=ih*${m.y0}:w=iw:h=ih*${(m.y1 - m.y0).toFixed(3)}:color=black:t=fill`;

let shotN = 0;
/** Grabs the screen now; converted to webp (and masked) at the end. */
function shot(tag) {
  const name = `s${String(++shotN).padStart(3, '0')}-${tag}`;
  writeFileSync(join(RAW, `${name}.png`), adb('exec-out', 'screencap', '-p'));
  SHOT_AT[`${name}.png`] = Date.now();
  return name;
}

// ---------- MCP ----------
let session = null;
let rpcId = 0;
async function rpc(method, params, notify = false) {
  const headers = { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream' };
  if (session) headers['mcp-session-id'] = session;
  const body = { jsonrpc: '2.0', method, params };
  if (!notify) body.id = ++rpcId;
  const res = await fetch(URL, { method: 'POST', headers, body: JSON.stringify(body) });
  session = res.headers.get('mcp-session-id') || session;
  if (notify) return null;
  const raw = await res.text();
  // Streamable HTTP answers either as plain JSON or as one SSE `data:` event.
  const json = raw.trimStart().startsWith('{')
    ? JSON.parse(raw)
    : JSON.parse(raw.split('\n').filter((l) => l.startsWith('data:')).map((l) => l.slice(5)).join(''));
  if (json.error) throw new Error(json.error.message);
  return json.result;
}

const RULES = [
  [/"device_next_alarm":"[^"]*"/g, '"device_next_alarm":"(hidden for this page)"'],
  // The Clock app keeps the world-clock cities (the owner's time zones) off screen.
  [/^(OFFSCREEN[^:]*:).*(Today|Tomorrow|Yesterday|Standard Time|Daylight Time).*$/gm, '$1 (world-clock cities hidden for this page)'],
  // Status-bar labels name the mobile carrier.
  [/\b[A-Za-z][\w&.-]*(, (one|two|three|four|five) bars?\.)/g, 'carrier$1'],
];
const scrub = (s) => RULES.reduce((t, [re, to]) => t.replace(re, to), REDACT.reduce((t, [from, to]) => t.split(from).join(to ?? '…'), s));

/** resolve_contact returns real people from the phone: keep the shape, replace every value. */
function fakeContacts(text) {
  try {
    const j = JSON.parse(text);
    (j.candidates || []).forEach((c, i) => Object.assign(c, {
      contact_id: String(1000 + i), display_name: `Contact ${'ABCD'[i] || i}`, phone_number: `+00 00000 0000${i}`,
    }));
    if (j.contact) Object.assign(j.contact, { display_name: 'Contact A', phone_number: '+00 00000 00000' });
    return JSON.stringify(j);
  } catch { return '{"note":"(contact result withheld)"}'; }
}

/** watch_device_events may carry other apps' text: keep only events from the demo's own apps. */
function safeEvents(text) {
  try {
    const j = JSON.parse(text);
    for (const k of Object.keys(j)) {
      if (Array.isArray(j[k])) j[k] = j[k].filter((e) => typeof e !== 'object' || !e || SAFE_PKGS.test(String(e.package_name ?? e.package ?? e.pkg ?? 'android')));
    }
    return JSON.stringify(j);
  } catch { return '{"note":"(event list withheld)"}'; }
}

const calls = [];
let imgN = 0;
const ACTS = new Set(['tap', 'swipe', 'scroll', 'set_value', 'run_steps', 'type_text', 'press_enter', 'press_key', 'launch_app',
  'open_deeplink', 'open_file', 'media_control', 'system_intent', 'notification_action', 'dismiss_notification', 'volume',
  'browser_open', 'browser_act', 'browser_upload', 'browser_tabs', 'browser_handoff', 'browser_close']);

/**
 * Calls [tool], records it for the page and returns its text. Actions get a screenshot before and
 * after and a clip; `o.shots` overrides that. `o.filter` rewrites each text block before it is kept.
 */
async function call(tool, args = {}, note = '', o = {}) {
  const shots = o.shots ?? ACTS.has(tool);
  const before = shots ? shot('before') : null;
  let t0 = Date.now();
  let r = await rpc('tools/call', { name: tool, arguments: args });
  if (r.isError && /paused_by_user/.test(r.content?.[0]?.text || '')) {
    // Someone touched the phone (a ringing alarm, a notification). AURA steps aside until the
    // phone has been still for a while; wait that out and retry once, so the page shows the tool.
    console.log(`  … paused by a touch on the phone; waiting for AURA to resume`);
    for (let i = 0; i < 40 && /paused_by_user/.test((await rpc('tools/call', { name: 'get_device_status', arguments: {} })).content?.[0]?.text || ''); i++) await sleep(3000);
    t0 = Date.now();
    r = await rpc('tools/call', { name: tool, arguments: args });
  }
  const t1 = Date.now();
  if (shots && o.settle) await sleep(o.settle);
  const after = shots ? shot('after') : null;
  const blocks = [];
  for (const c of r.content || []) {
    if (c.type === 'text') blocks.push({ type: 'text', text: scrub(o.filter ? o.filter(c.text) : c.text).slice(0, MAX_TEXT) });
    else if (c.type === 'image') {
      const file = `img-${++imgN}-${tool}.png`;
      writeFileSync(join(RAW, file), Buffer.from(c.data, 'base64'));
      blocks.push({ type: 'image', raw: file });
    }
  }
  calls.push({ tool, args: JSON.parse(scrub(JSON.stringify(args))), note, isError: !!r.isError, ms: t1 - t0, t0, t1, blocks, before, after });
  console.log(`${r.isError ? '✗' : '✓'} ${tool} ${JSON.stringify(args).slice(0, 70)} (${t1 - t0} ms)`);
  return (r.content || []).filter((c) => c.type === 'text').map((c) => c.text).join('\n');
}

/** som_id of the first (or last) row in a read_screen table whose flags and label match. */
function som(text, { flags = /./, label = /(?:)/, last = false } = {}) {
  const hits = [];
  for (const line of text.split('\n')) {
    const m = line.match(/^(\d+)\s+(\S+)\s+(\S+)\s*(.*)$/);
    if (m && flags.test(m[3]) && label.test(m[4])) hits.push({ id: Number(m[1]), flags: m[3], label: m[4].trim() });
  }
  return last ? hits[hits.length - 1] : hits[0];
}
const json = (text) => { try { return JSON.parse(text.split('\n')[0]); } catch { return {}; } };

// ---------- the run ----------
async function seeing() {
  await call('get_device_status', {}, 'Facts about the phone. Read-only.');
  await call('lookup_app', { app_name: 'Settings' }, 'Name to package, without opening anything.');
  await call('list_app_deeplinks', { package_name: 'com.android.settings' }, 'Screens inside Settings that can be opened directly.');
  await call('resolve_deeplink', { uri: 'android.settings.DISPLAY_SETTINGS' }, 'Checks where a link would lead, without opening it.');
  await call('open_deeplink', { uri: 'android.settings.DISPLAY_SETTINGS', package_name: 'com.android.settings' },
    'Opens Display settings directly. Every screen-changing tool ends with a post_action_observation block.');
  const full = await call('read_screen', {}, 'The default look: grid, table of what can be acted on, and a screenshot with every box numbered.', { shots: true });
  await call('read_screen', { query: 'bright' }, 'Only rows whose label contains the text.');
  const sw = som(full, { flags: /[co]/ });
  if (sw) {
    const value = sw.flags.includes('c') ? 'on' : 'off';
    await call('set_value', { som_id: sw.id, value }, `This switch is already ${value}, so nothing is touched: changed is false.`);
  }
  await call('scroll', { direction: 'down' }, 'Returns what came into view with current som_ids, so the next tap needs no extra look.');
  await call('read_screen', { changes_only: true }, 'Only what changed since the last look. The scroll counted as a look, so: nothing.');
  await call('run_steps', { steps: [{ tool: 'scroll', args: { direction: 'up' } }, { tool: 'scroll', args: { direction: 'up' } }] },
    'Two actions in one call. The second scroll finds the top of the list: moved false, at_end true.');
  await call('read_screen', { query: 'bright' }, 'A fresh look before acting again.');
  await call('tap', { som_id: 999 }, 'A som_id that is not on the screen is refused before any touch.', { shots: false });
  await call('wait_for', { condition: 'Display settings are showing', expect: 'Dark mode', timeout_ms: 3000 }, 'Waits until the text is on screen.');
  await call('verify_action', { expected: 'Display settings are open' }, 'A cheap check of the current screen against an expectation.');
  await call('perceive_screen', { description: 'the Display settings page' }, 'The heavier look: screenshot plus on-device detection, for things the tree cannot describe.');
  await call('request_screen_capture_permission', {}, 'Permission was granted earlier, so no dialog appears; on a fresh install the user taps Allow once.');
  await call('get_screenshot', {}, 'A plain picture of the screen, no boxes. For looking, not for targeting.');
  // Post a demo notification while watching, so the batch has something to show.
  setTimeout(() => sh(`cmd notification post -t 'AURA demo' aura_watch 'A notification posted during watch_device_events'`), 1200);
  await call('watch_device_events', { timeout_seconds: 4, max_events: 12 },
    'Blocks up to 4 s collecting what changed. A demo notification was posted 1.2 s in. Events from other apps are left out of this page.', { filter: safeEvents });
}

async function typing() {
  await call('clipboard', { action: 'write', text: 'Copied by AURA for the website demo' }, 'Puts text on the clipboard.');
  await call('clipboard', { action: 'read' }, 'Reads it back. Android only lets an app read the clipboard in some states, so this can come back empty.');
  await call('open_deeplink', { uri: 'android.settings.APP_SEARCH_SETTINGS', package_name: 'com.android.settings' }, 'Opens Settings search directly.', { settle: 600 });
  const search = await call('read_screen', {}, 'A full look at the search page: flag e marks the text field.');
  const field = som(search, { flags: /e/ });
  if (!field) return;
  await call('type_text', { som_id: field.id, text: 'battery' }, 'Types, then reads the field back: field_text is what it really holds.');
  await call('read_screen', { changes_only: true }, 'Only what changed: the typed text and the results that appeared.');
  const again = await call('read_screen', { query: 'battery' }, 'A fresh look to target the field again.');
  const f2 = som(again, { flags: /e/ });
  if (f2) await call('set_value', { som_id: f2.id, value: 'display' }, 'Sets the field to exact text, replacing what was there, and confirms it.');
  await call('type_text', { text: '4111 1111 1111 1111' }, 'A card number is refused before a single key is typed.', { shots: false });
  await call('press_enter', {}, 'Presses the keyboard\'s Enter key, as a person submitting the search would.');
  const results = await call('read_screen', { query: 'display' }, 'Find a result to open: Display & brightness.');
  const hit = som(results, { flags: /\*/, label: /^Display & brightness/ });
  if (hit) {
    await call('tap', { som_id: hit.id }, 'Opens a search result. The reply says where the finger went (x, y) and how: via.', { settle: 500 });
    await call('press_key', { key: 'back' }, 'One screen back.');
  }
  await call('press_key', { key: 'back' }, 'Back out of search.');
}

async function apps() {
  sh('am start -a android.intent.action.SHOW_TIMERS'); // so Clock opens on its Timer tab
  await sleep(1200);
  sh('am start -a android.settings.DISPLAY_SETTINGS');
  await sleep(1200);
  await call('launch_app', { app_name: 'Clock' }, 'Opens an app by name; it is resolved on the phone.', { settle: 500 });
  const clock = await call('read_screen', {}, 'The Timer tab: the hour, minute and second wheels are flagged S (scrolls).', { shots: true });
  // Wheels are the scrollers after the page-level ones (som 1, 2); the last is the minutes wheel.
  const wheels = clock.split('\n').map((l) => l.match(/^(\d+)\s+\S+\s+(\S+)/)).filter((m) => m && /S/.test(m[2]) && +m[1] > 2);
  const wheel = wheels[wheels.length - 1];
  if (wheel) await call('swipe', { som_id: +wheel[1], direction: 'up' }, 'Swipes up across the minutes wheel. The reply gives the exact finger path, x1,y1 to x2,y2.', { settle: 600 });
  await call('system_intent', { action: 'set_timer', seconds: 600, label: 'AURA demo' }, 'Starts a 10-minute timer directly, with no screen navigation. (The phone\'s next alarm is hidden on this page.)', { settle: 1500 });
  const running = await call('read_screen', { query: 'Reset' }, 'The Clock app comes forward with the timer running.', { shots: true });
  const reset = som(running, { flags: /\*/, label: /^Reset$/ }) || som(running, { label: /^Reset$/ });
  if (reset) await call('tap', { som_id: reset.id }, 'Resets the demo timer before it can ring.', { settle: 600 });
}

async function notifications() {
  sh(`cmd notification post -S bigtext -t 'AURA demo' aura_demo 'Your parcel is out for delivery (a demo notification)'`);
  await sleep(800);
  const list = await call('read_notifications', { package_name: SHELL_PKG }, 'Reads notifications from one app: here the demo one posted for this page.');
  const key = (json(list).notifications || []).find((n) => /AURA demo/.test(n.title || ''))?.key;
  if (key) await call('dismiss_notification', { key }, 'Clears it by key.', { shots: false });
  const aura = await call('read_notifications', { package_name: 'com.aura.aura_ui.feature.debug', include_ongoing: true },
    "AURA's own status notification, which has buttons. Ongoing ones need include_ongoing.");
  const ready = (json(aura).notifications || []).find((n) => (n.actions || []).some((a) => a.title === 'Hide'));
  if (ready) await call('notification_action', { key: ready.key, action: 'Hide' }, "Presses a button on a notification without opening the shade. (On a message, action Reply with reply_text sends a reply.)", { shots: false });
}

async function files() {
  const found = await call('find_files', { query: 'aura-demo' }, 'Searches the media index by name. Both hits are the demo files pushed for this page.');
  const card = (json(found).files || []).find((f) => /card/.test(f.display_name));
  if (!card) return;
  await call('open_file', { uri: card.uri }, 'Opens it in its default app. With several viewers installed, Android asks first.', { settle: 600 });
  const chooser = await call('read_screen', { query: 'Photos' }, 'The "Open with" chooser, read like any screen.');
  const photos = som(chooser, { flags: /\*/, label: /^Photos$/, last: true });
  if (photos) {
    // Google Photos shows a strip of the owner's other photos under the picture: black it out.
    const mask = { t0: Date.now(), t1: Infinity, y0: 0.82, y1: 0.91 };
    MASKS.push(mask);
    await call('tap', { som_id: photos.id }, 'Picks Google Photos. (Its strip of other photos is blacked out on this page.)', { settle: 1500 });
    await call('press_key', { key: 'back' }, 'Back out of the viewer.', { settle: 800 });
    mask.t1 = Date.now();
  }
}

async function media() {
  // Setup by adb, not a tool: play the demo tone in Files by Google so there is a media session.
  sh('am force-stop com.google.android.apps.nbu.files');
  const tone = (sh(`content query --uri content://media/external/audio/media --projection _id --where "_display_name='aura-demo-tone.mp3'"`).match(/_id=(\d+)/) || [])[1];
  sh(`am start -a android.intent.action.VIEW -d content://media/external/audio/media/${tone} -t audio/mpeg -p com.google.android.apps.nbu.files`);
  await sleep(3000);
  await call('get_media_sessions', {}, 'What is playing: the demo tone, started in Files by Google for this page.', { shots: true });
  await call('volume', { action: 'up' }, 'Media volume up one notch; Android shows its volume bar.', { settle: 800 });
  await call('volume', { action: 'down' }, 'And back down, where it was.', { settle: 800 });
  await call('media_control', { command: 'pause' }, 'Pauses it without touching the screen.', { settle: 800 });
  await call('get_media_sessions', {}, 'Files by Google ends its media session as soon as it pauses, so there is nothing left to list (other players keep a paused session).');
  await call('press_key', { key: 'back' }, 'Leaves the player.');
}

async function contacts() {
  await call('resolve_contact', { name: 'Aurabot Demo' }, 'This phone has no contact by that name, yet the fuzzy match still offered four people. Their names and numbers are replaced with placeholders on this page.', { filter: fakeContacts });
}

async function browser() {
  const o = { max_elements: 20, max_text_chars: 700 };
  const page = await call('browser_open', { url: 'https://the-internet.herokuapp.com/upload', ...o }, "Opens a page in AURA's own browser, a window over the phone, and returns its text and numbered elements (el_id).", { settle: 3500 });
  const gen = json(page).generation;
  const found = await call('find_files', { query: 'aura-demo-card', kind: 'image' }, 'Gets a file to upload.');
  const card = (json(found).files || [])[0];
  const input = (json(page).elements || []).find((e) => e.role === 'input');
  if (card && input) await call('browser_upload', { el_id: input.el_id, file: card.uri, generation: gen, ...o }, 'Attaches the demo picture to the page\'s file input.', { settle: 800 });
  await call('browser_screenshot', {}, 'A picture of the page, when text is not enough. The file name now shows next to the button.');
  await call('browser_tabs', { action: 'open', url: 'https://the-internet.herokuapp.com/dynamic_loading/2' }, 'A second tab, keeping the first.', { settle: 2500 });
  const start = await call('browser_find', { text: 'Start', ...o }, 'Finds a button by its text and returns its el_id.');
  const sj = json(start);
  const btn = (sj.elements || []).find((e) => /start/i.test(`${e.label} ${e.value || ''}`));
  if (btn) await call('browser_act', { action: 'click', el_id: btn.el_id, generation: sj.generation, ...o }, 'Clicks it. The page starts loading.', { settle: 500 });
  await call('browser_wait', { text: 'Hello World!', timeout_ms: 10000, ...o }, 'Waits for the text to appear instead of polling.');
  await call('browser_tabs', { action: 'list' }, 'Both tabs.');
  await call('browser_tabs', { action: 'switch', index: 0 }, 'Back to the first, as it was.', { settle: 800 });
  await call('browser_read', o, 'Reads the active page again without navigating.');
  await call('browser_open', { url: 'https://the-internet.herokuapp.com/tables', ...o }, 'A page with a table.', { settle: 2000 });
  await call('browser_extract', o, 'The repeated rows as data, with the total count.');
  await call('browser_handoff', { prompt: 'Demo: AURA hands this page to you for a moment', ...o }, 'Hands the page to the person for a step only they can do (signing in, a code). The window shows the prompt.', { settle: 2500 });
  await call('browser_handoff', { check: true, ...o }, 'Polls the handoff. There is no login form here and nobody touched the window for 2 s, so AURA takes the page back: waiting_for_user false, ended_by auto.', { settle: 500 });
  const login = await call('browser_open', { url: 'https://the-internet.herokuapp.com/login', ...o }, 'A login form.', { settle: 2000 });
  const lj = json(login);
  const user = (lj.elements || []).find((e) => /user/i.test(`${e.label} ${e.name || ''}`));
  if (user) await call('browser_act', { action: 'type', el_id: user.el_id, value: 'tomsmith', generation: lj.generation, ...o }, 'Types into a field (the test site\'s public demo user name).', { settle: 600 });
  await call('browser_act', { action: 'scroll_down', ...o }, 'Scrolls the page.', { settle: 600 });
  await call('browser_close', {}, 'Closes the browser session and its window.', { settle: 800 });
}

function cutMedia() {
  // Shots and returned images → webp.
  // Images returned by tools carry no time, so they are masked by the window they were taken in too.
  const imgAt = {};
  calls.forEach((c) => c.blocks.forEach((b) => { if (b.raw) imgAt[b.raw] = c.t1; }));
  for (const f of readdirSync(RAW).filter((f) => f.endsWith('.png'))) {
    const m = maskAt(SHOT_AT[f] ?? imgAt[f] ?? 0);
    ff('-i', join(RAW, f), '-vf', `${m ? `${band(m)},` : ''}scale=540:-2`, '-quality', '78', join(MEDIA, f.replace(/\.png$/, '.webp')));
  }
  // scrcpy only writes a frame when the screen changes; fps=30 refills the gaps so seeking is exact.
  const cfr = join(RAW, 'run.mp4');
  const masks = MASKS.map((m) => `${band(m)}:enable='between(t,${((m.t0 - rec.t0) / 1000 - 1).toFixed(2)},${((m.t1 - rec.t0) / 1000 + 1).toFixed(2)})'`);
  ff('-i', rec.local, '-vf', ['fps=30', ...masks, 'scale=720:-2'].join(','), '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '20', '-an', cfr);
  let clipN = 0;
  for (const c of calls) {
    if (c.before) c.before = `media/mcp-tools/${c.before}.webp`;
    if (c.after) c.after = `media/mcp-tools/${c.after}.webp`;
    c.blocks.forEach((b) => { if (b.raw) { b.src = `media/mcp-tools/${b.raw.replace(/\.png$/, '.webp')}`; delete b.raw; } });
    c.at = Math.max(0, Math.round((c.t0 - rec.t0) / 100) / 10);
    if (c.before && c.after && ACTS.has(c.tool) && !c.isError) {
      const len = Math.min(10, (c.t1 - c.t0) / 1000 + 2.4);
      const file = `clip-${String(++clipN).padStart(2, '0')}-${c.tool}.mp4`;
      ff('-ss', Math.max(0, c.at - 0.6).toFixed(2), '-i', cfr, '-t', len.toFixed(2), '-vf', 'scale=360:-2', '-c:v', 'libx264', '-preset', 'slow', '-crf', '30', '-an', '-movflags', '+faststart', join(MEDIA, file));
      c.clip = `media/mcp-tools/${file}`;
    }
    delete c.t0; delete c.t1;
  }
  ff('-i', cfr, '-t', String(calls[calls.length - 1].at + 4), '-vf', 'scale=360:-2', '-c:v', 'libx264', '-preset', 'slow', '-crf', '31', '-an', '-movflags', '+faststart', join(MEDIA, 'walkthrough.mp4'));
  ff('-ss', '2', '-i', cfr, '-frames:v', '1', '-vf', 'scale=540:-2', '-quality', '78', join(MEDIA, 'walkthrough.webp'));
}

async function main() {
  rmSync(RAW, { recursive: true, force: true });
  mkdirSync(RAW, { recursive: true });
  rmSync(MEDIA, { recursive: true, force: true });
  mkdirSync(MEDIA, { recursive: true });
  await rpc('initialize', { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'site-capture', version: '2' } });
  await rpc('notifications/initialized', {}, true);
  const listed = (await rpc('tools/list', {})).tools;

  demoMode(true);
  sh('am start -a android.settings.DISPLAY_SETTINGS');
  await sleep(1500);
  await rec.start();
  await sleep(1500);
  try {
    for (const part of [seeing, typing, apps, notifications, files, media, contacts, browser]) {
      console.log(`\n— ${part.name}`);
      try { await part(); } catch (e) { console.error(`  ${part.name} stopped: ${e.message}`); }
    }
    await call('end_session', { reason: 'website demo capture finished', outcome: 'success' }, 'Marks the task finished.');
  } finally {
    await rec.stop();
    demoMode(false);
  }
  cutMedia();

  const data = {
    captured: new Date().toISOString(),
    walkthrough: { src: 'media/mcp-tools/walkthrough.mp4', poster: 'media/mcp-tools/walkthrough.webp' },
    screen: { w: 1240, h: 2772 },
    tools: listed.map((t) => ({ name: t.name, description: scrub(t.description || ''), schema: JSON.parse(scrub(JSON.stringify(t.inputSchema || {}))) })),
    calls,
  };
  writeFileSync(join(SITE, 'mcp-tools-data.js'),
    `// Generated by scripts/capture_mcp_tools.mjs from a real phone. Do not edit by hand.\nwindow.MCP_TOOLS = ${JSON.stringify(data, null, 1)};\n`);
  console.log(`\nwrote ${calls.length} calls for ${listed.length} tools`);
}

main().catch((e) => { console.error(`capture failed: ${e.message}`); process.exit(1); });
