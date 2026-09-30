/* AURA — scroll film engine. window.AuraFilm.mount(root)
   Sound design: SFX_ENABLED is the code-level master switch. window.AuraSound is the one user switch: the header
   button and the Rapido video button both show AuraSound.state() and call AuraSound.press().
   - Contract: state() is 'on' (audible), 'tap' (on, but the browser blocks audio until the first click or key) or 'off'.
   - Why sessionStorage: every visit starts with sound on; turning it off lasts for this tab only.
   Haptics use navigator.vibrate where supported. */
(function () {
  'use strict';
  // Why: support.js runs the page's scripts again when it mounts. A second run would build a second sound
  // engine, so the header would mute one while the film plays through the other.
  if (window.AuraSound) return;
  var SFX_ENABLED = true;

  var TAU = Math.PI * 2, D = Math.PI / 180;
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  function lerp(a, b, t) { return a + (b - a) * t; }
  function ss(a, b, v) { var t = clamp((v - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); }
  function eio(t) { return t < .5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2; }
  var seed = 11; function rnd() { seed = (seed * 16807) % 2147483647; return (seed - 1) / 2147483646; }

  /* ---------------- sound design ---------------- */
  var Snd = (function () {
    var allowed = window.AURA_SFX != null ? !!window.AURA_SFX : SFX_ENABLED;
    var on = allowed; try { if (sessionStorage.getItem('aura-sound') === 'off') on = false; } catch (e) {}
    var ac = null, master = null, wet = null, noise = null, listeners = [];
    // iOS: without this the ringer switch silences Web Audio.
    try { if (navigator.audioSession) navigator.audioSession.type = 'playback'; } catch (e) {}
    function unlocked() { return !!ac && ac.state === 'running'; }
    var firstGestureAt = 0;
    function notify() { listeners.forEach(function (f) { f(); }); }
    function build() {
      if (ac || !allowed) return;
      try {
        ac = new (window.AudioContext || window.webkitAudioContext)();
        var comp = ac.createDynamicsCompressor(); comp.threshold.value = -20; comp.ratio.value = 3; comp.connect(ac.destination);
        ac.onstatechange = notify;
        master = ac.createGain(); master.gain.value = on ? .4 : 0; master.connect(comp);
        var len = ac.sampleRate, b = ac.createBuffer(1, len, ac.sampleRate), d = b.getChannelData(0);
        for (var i = 0; i < len; i++) d[i] = Math.random() * 2 - 1; noise = b;
        var il = Math.floor(ac.sampleRate * 2.2), ir = ac.createBuffer(2, il, ac.sampleRate);
        for (var c = 0; c < 2; c++) { var ch = ir.getChannelData(c); for (var k = 0; k < il; k++) ch[k] = (Math.random() * 2 - 1) * Math.pow(1 - k / il, 3.2); }
        var cv = ac.createConvolver(); cv.buffer = ir; var lp = ac.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.value = 5200;
        wet = ac.createGain(); wet.gain.value = .55; wet.connect(cv); cv.connect(lp); lp.connect(master);
      } catch (e) { allowed = false; ac = null; }
    }
    // Only these events grant the user activation that lets audio start; pointerdown and touchstart fire before it.
    function gesture() {
      if (!firstGestureAt) firstGestureAt = Date.now();
      var was = unlocked(); build();
      if (ac && ac.state === 'suspended') { var pr = ac.resume(); if (pr && pr.then) pr.then(notify, function () {}); }
      if (unlocked() !== was) notify();
    }
    ['pointerup', 'touchend', 'click', 'keydown'].forEach(function (ev) { window.addEventListener(ev, gesture, { passive: true }); });
    var quiet = false;
    function live() { return on && unlocked() && !quiet && !document.hidden; }
    function buzz(p) { if (on && unlocked() && navigator.vibrate) try { navigator.vibrate(p); } catch (e) {} }
    function out(g, send) { g.connect(master); if (send) { var sg = ac.createGain(); sg.gain.value = send; g.connect(sg); sg.connect(wet); } }
    function bell(t, f, peak, att, dur, send) {
      [[1, 1], [2.001, .18], [3.01, .05]].forEach(function (pp) {
        var o = ac.createOscillator(), g = ac.createGain(); o.type = 'sine'; o.frequency.value = f * pp[0];
        g.gain.setValueAtTime(0.0001, t); g.gain.linearRampToValueAtTime(peak * pp[1], t + att); g.gain.exponentialRampToValueAtTime(0.0001, t + att + dur * (pp[0] > 1 ? .45 : 1));
        o.connect(g); out(g, send); o.start(t); o.stop(t + att + dur + .1);
      });
    }
    function tone(t, freq, dur, peak) { var o = ac.createOscillator(), g = ac.createGain(); o.type = 'sine'; o.frequency.setValueAtTime(freq, t); g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(peak, t + .004); g.gain.exponentialRampToValueAtTime(0.0001, t + .004 + dur); o.connect(g); out(g, .12); o.start(t); o.stop(t + dur + .05); }
    function burst(t, ftype, f, Q, peak, dec, send) {
      var s = ac.createBufferSource(), fl = ac.createBiquadFilter(), g = ac.createGain(); s.buffer = noise;
      fl.type = ftype; fl.frequency.value = f; if (Q) fl.Q.value = Q;
      g.gain.setValueAtTime(0.0001, t); g.gain.linearRampToValueAtTime(peak, t + .0008); g.gain.exponentialRampToValueAtTime(0.0001, t + .0008 + dec);
      s.connect(fl); fl.connect(g); out(g, send); s.start(t, Math.random() * .8, dec + .05);
    }
    function thunk(t, k, f0) {
      var lo = f0 ? f0 / 95 : 1;
      burst(t, 'bandpass', 1500 * lo, 3, .045 * k, .012, .06);
      burst(t + .028, 'bandpass', 3900 * lo, 6, .026 * k, .01, .08);
    }
    var last = {};
    function gap(k, s) { var n = ac.currentTime; if (last[k] && n - last[k] < s) return false; last[k] = n; return true; }
    return {
      state: function () { return !(on && allowed) ? 'off' : unlocked() ? 'on' : 'tap'; },
      set: function (v) { on = !!v && allowed; try { sessionStorage.setItem('aura-sound', on ? 'on' : 'off'); } catch (e) {} if (on) gesture(); if (master) master.gain.setTargetAtTime(on ? .4 : 0, ac.currentTime, .08); notify(); },
      // A press while blocked means "let me hear it", never "mute". The same tap unlocks audio a moment
      // before the button's click handler runs, so a press right after the unlock still counts as blocked.
      press: function () { this.set(this.state() !== 'on' || Date.now() - firstGestureAt < 800); },
      onChange: function (f) { listeners.push(f); },
      quiet: function (q) { quiet = !!q; },
      blip: function (f) { if (!live() || !gap('blip', .06)) return; tone(ac.currentTime, f || 1400, .03, .011); buzz(6); },
      key: function () {
        if (!live() || !gap('key', .05)) return; var t = ac.currentTime + Math.random() * .01, r = .9 + Math.random() * .2, v = .7 + Math.random() * .4;
        burst(t, 'highpass', 1900 * r, .7, .045 * v, .006, .03);
        burst(t + .002, 'bandpass', 430 * r, 3.5, .085 * v, .03, .05);
        burst(t + .006, 'lowpass', 900, .7, .035 * v, .015, 0);
        burst(t + .075 + Math.random() * .04, 'highpass', 3200 * r, .7, .016 * v, .005, 0);
        buzz(3);
      },
      tick: function (pos) { if (!live() || !gap('tick', .022)) return; var t = ac.currentTime; burst(t, 'bandpass', 2600 + pos * 1400, 6, .03, .012, .05); burst(t, 'bandpass', 190 + pos * 60, 5, .028, .02, 0); buzz(2); },
      latch: function (soft) { if (!live() || !gap('latch', .6)) return; thunk(ac.currentTime, soft ? .6 : 1); buzz(soft ? 8 : [10, 40, 14]); },
      chime: function () { if (!live() || !gap('latch', .6)) return; thunk(ac.currentTime, .6); buzz(8); },
      deny: function () { if (!live() || !gap('deny', 1)) return; var t = ac.currentTime; thunk(t, .9, 70); thunk(t + .12, .8, 55); buzz([24, 60, 24]); },
      open: function () {}, settle: function () {}, close: function () {}, shimmer: function () {}
    };
  })();
  window.AuraSound = Snd;
  // One wording for every sound button: the text says what you hear now, the aria-label says what a press does.
  window.AuraSoundLabel = function (st) {
    return st === 'on' ? { text: 'Sound on', aria: 'Sound is on. Turn sound off' }
      : st === 'tap' ? { text: 'Tap for sound', aria: 'Sound is blocked until you tap. Turn sound on' }
      : { text: 'Sound off', aria: 'Sound is off. Turn sound on' };
  };

  /* ---------------- phone geometry ---------------- */
  var SW = .92, SH = 1.96, SR = .11, PW = 1, PH = 2.06, PR = .15;
  var Z0 = [.07, .074, .078, .02, 0, -.02, 0], ZE = [1.5, 1, .5, 0, -.5, -1, -1.5];
  var NAMES = ['glass · your apps', 'eyes', 'hands', 'safety gate', 'MCP server', 'AURA agent', 'frame'];
  var TAP = { x: -.1, y: .08 }, HUB = { x: -.1, y: .55 }, GATE = { x: 0, y: .3 }, BANK = { x: .1, y: -.14 };
  var LENS = [1.5, 1.3, 2.1, 1.8, 2.8, 8, 3.4, 2.8, 3.2, 1.2];
  var PHASE = ['perceive_screen · 22 things to touch', 'the agent picks 7', 'tap {"som_id": 7}', 'verify_action · did the screen change?'];
  var CALLS = ['perceive_screen → 22 elements', 'tap {"som_id": 7}', 'verify_action → screen changed', 'perceive_screen → 8 elements', 'tap {"som_id": 3}', 'verify_action → done'];
  var K = [
    { yaw: -16, pitch: 6, roll: 0, ex: 0, sc: .72, ox: 0, oy: .13, f: -1, dim: 1 },
    { yaw: 18, pitch: 6, roll: 0, ex: 0, sc: .9, ox: .22, oy: 0, f: -1, dim: 1 },
    { yaw: -40, pitch: -20, roll: 0, ex: 1, sc: .68, ox: .17, oy: .04, f: 4, dim: 1 },
    { yaw: -32, pitch: -14, roll: 0, ex: 1, sc: .7, ox: .17, oy: .02, f: 4, dim: 1 },
    { yaw: -9, pitch: 5, roll: 0, ex: .16, sc: 1.02, ox: .17, oy: 0, f: 1, dim: 1 },
    { yaw: -6, pitch: 2, roll: 0, ex: 0, sc: .62, ox: .26, oy: .02, f: -1, dim: 1 },
    { yaw: -8, pitch: 3, roll: 0, ex: 0, sc: .58, ox: .02, oy: -.02, f: -1, dim: 1 },
    { yaw: 0, pitch: 0, roll: 0, ex: 0, sc: .92, ox: .18, oy: -.02, f: -1, dim: 1 },
    { yaw: -14, pitch: 8, roll: 0, ex: 0, sc: .95, ox: .2, oy: 0, f: -1, dim: 1 },
    { yaw: -12, pitch: 6, roll: 0, ex: 0, sc: 1, ox: .17, oy: 0, f: -1, dim: 1 }
  ];
  var CLIENTS = [['Claude', 'claude'], ['Cursor', 'cursor'], ['VS Code', 'vscode'], ['any MCP app', 'mcp']];
  var CATS = [
    ['seeing', 'Reads the screen and numbers everything you can touch.', ['perceive_screen', 'read_screen', 'get_screenshot', 'request_screen_capture_permission', 'watch_device_events', 'wait_for']],
    ['touching', 'Taps, swipes and scrolls like a finger.', ['tap', 'double_tap', 'long_press', 'swipe', 'scroll_up', 'scroll_down', 'scroll_left', 'scroll_right', 'scroll_to']],
    ['typing', 'Types into any field, even in React Native and Flutter apps.', ['type_text', 'press_enter']],
    ['apps', 'Opens apps and moves between them.', ['launch_app', 'lookup_app', 'open_recent_apps', 'press_home', 'press_back']],
    ['browser', 'Drives its own browser, tab by tab.', ['browser_open', 'browser_read', 'browser_act', 'browser_find', 'browser_extract', 'browser_tabs', 'browser_wait', 'browser_screenshot', 'browser_upload', 'browser_handoff', 'browser_close', 'web_search']],
    ['notifications', 'Reads, replies to and clears notifications.', ['read_notifications', 'notification_action', 'dismiss_notification']],
    ['media', 'Controls music, video and volume.', ['get_media_sessions', 'media_control', 'volume_up', 'volume_down', 'mute']],
    ['contacts', 'Finds the right person on the phone.', ['resolve_contact']],
    ['files', 'Finds and opens your files.', ['find_files', 'open_file']],
    ['deep links', 'Jumps straight to the right screen.', ['list_app_deeplinks', 'resolve_deeplink', 'open_deeplink']],
    ['system', 'Alarms, calls, sharing, and checking its own work.', ['system_intent', 'get_device_status', 'validate_action', 'verify_action', 'connect_device', 'get_usage_guide', 'echo', 'end_session']]
  ];

  function rr(w, h, r, step, cx, cy) {
    cx = cx || 0; cy = cy || 0;
    var out = [], hw = w / 2, hh = h / 2;
    function seg(x1, y1, x2, y2) { var n = Math.max(1, Math.round(Math.hypot(x2 - x1, y2 - y1) / step)); for (var i = 0; i < n; i++) { var t = i / n; out.push([cx + lerp(x1, x2, t), cy + lerp(y1, y2, t)]); } }
    function arc(ax, ay, a0) { var n = Math.max(2, Math.round(r * Math.PI / 2 / step)); for (var i = 0; i < n; i++) { var a = a0 + i / n * Math.PI / 2; out.push([cx + ax + Math.cos(a) * r, cy + ay + Math.sin(a) * r]); } }
    seg(-hw + r, hh, hw - r, hh); seg(hw - r, -hh, -hw + r, -hh); seg(-hw, -hh + r, -hw, hh - r); seg(hw, hh - r, hw, -hh + r);
    arc(hw - r, hh - r, 0); arc(-hw + r, hh - r, Math.PI / 2); arc(-hw + r, -hh + r, Math.PI); arc(hw - r, -hh + r, Math.PI * 1.5);
    return out;
  }
  function inRR(x, y, w, h, r) { var dx = Math.max(Math.abs(x) - (w / 2 - r), 0), dy = Math.max(Math.abs(y) - (h / 2 - r), 0); return dx * dx + dy * dy <= r * r; }

  var BOXA = [], BOXB = [];
  function buildPhone(q) {
    var L = [[], [], [], [], [], [], []];
    function add(l, x, y, o) { o = o || {}; L[l].push({ x: x, y: y, z: o.z || 0, s: o.s == null ? 1 : o.s, a: o.a == null ? 1 : o.a, g: o.g || 0, k: o.k == null ? -1 : o.k, rx: rnd() * 2 - 1, ry: rnd() * 2 - 1, rz: rnd() * 2 - 1 }); }
    function outline(l, w, h, r, step, cx, cy, o) { rr(w, h, r, step * q, cx, cy).forEach(function (p) { add(l, p[0], p[1], o); }); }
    function fill(l, w, h, r, sp, cx, cy, fn, o) {
      sp *= q;
      for (var y = -h / 2 + sp / 2; y < h / 2; y += sp) for (var x = -w / 2 + sp / 2; x < w / 2; x += sp) {
        if (!inRR(x, y, w, h, r)) continue;
        var s = fn ? fn(x / w + .5, y / h + .5) : 1; if (s < .2) continue;
        var o2 = Object.assign({}, o || {}); o2.s = s * (o && o.s ? o.s : 1); add(l, cx + x, cy + y, o2);
      }
    }
    function line(l, x1, y1, x2, y2, step, o) { var n = Math.max(1, Math.round(Math.hypot(x2 - x1, y2 - y1) / (step * q))); for (var i = 0; i <= n; i++) { var t = i / n; add(l, lerp(x1, x2, t), lerp(y1, y2, t), o); } }
    function circ(l, cx, cy, r, step, o) { var n = Math.max(6, Math.round(TAU * r / (step * q))); for (var i = 0; i < n; i++) { var a = i / n * TAU; add(l, cx + Math.cos(a) * r, cy + Math.sin(a) * r, o); } }
    function disc(l, cx, cy, r, sp, o) { sp *= q; for (var y = -r; y <= r; y += sp) for (var x = -r; x <= r; x += sp) if (x * x + y * y <= r * r) add(l, cx + x, cy + y, o); }
    function slab(l, w, h, r, step, o) { var a = Object.assign({ s: .6, a: .5 }, o || {}); outline(l, w, h, r, step, 0, 0, Object.assign({}, a, { z: .012 })); outline(l, w, h, r, step * 1.6, 0, 0, Object.assign({}, a, { z: -.012, a: a.a * .55 })); }

    rr(PW, PH, PR, .016 * q).forEach(function (p, i) {
      add(6, p[0], p[1], { z: .065, s: .9 });
      add(6, p[0], p[1], { z: -.065, s: .8, a: .45 });
      if (i % 9 === 0) for (var j = 1; j < 4; j++) add(6, p[0], p[1], { z: .065 - j * .0325, s: .7, a: .45 });
    });
    line(6, .515, .56, .515, .32, .02, { s: 1 }); line(6, .515, .2, .515, .1, .02, { s: 1 });

    slab(0, SW, SH, SR, .016, { s: .8, a: .85 });
    line(0, -.12, -.92, .12, -.92, .014, { s: .9 });
    for (var sy = -.95; sy < .95; sy += .03 * q) for (var sx = -.44; sx < .44; sx += .03 * q) { var band = sx * .8 + sy * .35 - .18; if (Math.abs(band) < .05 && inRR(sx, sy, SW, SH, SR)) add(0, sx, sy, { g: 4, s: 1.2, a: .4, z: .014 }); }
    var A = { g: 1 }, B = { g: 2 };
    line(0, -.38, .9, -.3, .9, .02, { g: 1, s: .8 }); line(0, .28, .9, .38, .9, .02, { g: 1, s: .8 });
    fill(0, .78, .26, .05, .02, 0, .62, function (u, v) { var w = .5 + .5 * Math.sin(u * 7.5 + v * 2.4); return .25 + 1 * w * (1 - u * .55); }, A);
    BOXA.push([0, .62, .84, .32, 1]);
    var PAT = [
      function () { return 1; },
      function (u, v) { return 1.3 - Math.hypot(u - .5, v - .5) * 2.4; },
      function (u, v) { var d = Math.hypot(u - .5, v - .5); return d > .26 ? 1 : .35; },
      function (u) { return .3 + u * .9; },
      function (u, v) { return (Math.abs(u - .5) < .15 || Math.abs(v - .5) < .15) ? 1.1 : .32; },
      function (u, v) { return .3 + .85 * v; },
      function (u, v) { return ((Math.round(u * 6) + Math.round(v * 6)) % 2) ? 1 : .3; }
    ];
    var cols = [-.3, -.1, .1, .3], rows = [.3, .08, -.14, -.36], n = 2, pi = 0;
    rows.forEach(function (y) { cols.forEach(function (x) { fill(0, .14, .14, .038, .02, x, y, PAT[pi++ % PAT.length], A); BOXA.push([x, y, .18, .18, n++]); }); });
    outline(0, .72, .075, .0375, .016, 0, -.56, { g: 1, s: .8 }); circ(0, -.31, -.56, .014, .01, { g: 1, s: .8 });
    BOXA.push([0, -.56, .77, .11, n++]);
    outline(0, .84, .2, .07, .03, 0, -.74, { g: 1, s: .6, a: .6 });
    cols.forEach(function (x) { fill(0, .13, .13, .038, .02, x, -.74, PAT[pi++ % PAT.length], A); BOXA.push([x, -.74, .165, .165, n++]); });
    line(0, -.34, .86, -.38, .82, .012, B); line(0, -.38, .82, -.34, .78, .012, B);
    line(0, -.26, .82, .06, .82, .02, { g: 2, s: 1.1 });
    outline(0, .8, .08, .04, .016, 0, .68, { g: 2, s: .8 }); line(0, -.3, .68, -.05, .68, .02, { g: 2, s: .7 });
    BOXB.push([-.36, .82, .11, .12, 1]); BOXB.push([0, .68, .86, .13, 2]);
    [.5, .28, .06, -.16, -.38].forEach(function (y, i) {
      fill(0, .14, .14, .03, .02, -.31, y, PAT[(i + 2) % PAT.length], B);
      line(0, -.19, y + .035, .3, y + .035, .02, { g: 2, s: .95 });
      line(0, -.19, y - .01, .12, y - .01, .02, { g: 2, s: .65 });
      line(0, -.19, y - .05, 0, y - .05, .02, { g: 2, s: .5 });
      BOXB.push([0, y, .86, .19, 3 + i]);
    });
    outline(0, .6, .1, .05, .016, 0, -.62, B); line(0, -.12, -.62, .12, -.62, .02, { g: 2, s: 1 });
    BOXB.push([0, -.62, .66, .15, 8]);

    slab(1, SW, SH, SR, .038);
    BOXA.forEach(function (b, i) { outline(1, b[2], b[3], .022, .021, b[0], b[1], { g: 1, k: i, s: .75 }); });
    BOXB.forEach(function (b, i) { outline(1, b[2], b[3], .022, .021, b[0], b[1], { g: 2, k: 100 + i, s: .75 }); });

    slab(2, SW, SH, SR, .038);
    circ(2, TAP.x, TAP.y, .035, .011, { s: .9 }); circ(2, TAP.x, TAP.y, .065, .015, { s: .8, a: .8 });
    var d = [.423, -.906], nn = [.906, .423], fr = .095, c = [TAP.x + d[0] * fr, TAP.y + d[1] * fr];
    for (var a = -90; a <= 90; a += 12) { var t = a * D; add(2, c[0] + (-d[0] * Math.cos(t) + nn[0] * Math.sin(t)) * fr, c[1] + (-d[1] * Math.cos(t) + nn[1] * Math.sin(t)) * fr, { s: 1, g: 6 }); }
    line(2, c[0] + nn[0] * fr, c[1] + nn[1] * fr, c[0] + nn[0] * fr + d[0] * .85, c[1] + nn[1] * fr + d[1] * .85, .017, { s: 1, g: 6 });
    line(2, c[0] - nn[0] * fr, c[1] - nn[1] * fr, c[0] - nn[0] * fr + d[0] * .85, c[1] - nn[1] * fr + d[1] * .85, .017, { s: 1, g: 6 });

    slab(3, SW, SH, SR, .02, { s: .75, a: .7 });
    outline(3, .7, .86, .06, .03, 0, -.38, { s: .6, a: .55 });
    for (var gy = -.9; gy <= .9; gy += .09) for (var gx = -.405; gx <= .405; gx += .09) if (inRR(gx, gy, SW, SH, SR) && !inRR(gx, gy + .38, .74, .9, .06) && Math.hypot(gx - GATE.x, gy - GATE.y) > .3) add(3, gx, gy, { s: .4, a: .45 });
    var V = [[0, .22], [.2, 0], [0, -.22], [-.2, 0]], V2 = [[0, .13], [.12, 0], [0, -.13], [-.12, 0]];
    for (var vi = 0; vi < 4; vi++) { line(3, GATE.x + V[vi][0], GATE.y + V[vi][1], GATE.x + V[(vi + 1) % 4][0], GATE.y + V[(vi + 1) % 4][1], .012, { s: 1.15 }); line(3, GATE.x + V2[vi][0], GATE.y + V2[vi][1], GATE.x + V2[(vi + 1) % 4][0], GATE.y + V2[(vi + 1) % 4][1], .015, { s: .85 }); }
    disc(3, GATE.x, GATE.y, .028, .014, { s: 1 });

    var poly = [[-.42, .95], [.42, .95], [.42, -.95], [.28, -.95], [.28, .18], [-.42, .18]];
    for (var pi2 = 0; pi2 < poly.length; pi2++) line(4, poly[pi2][0], poly[pi2][1], poly[(pi2 + 1) % poly.length][0], poly[(pi2 + 1) % poly.length][1], .016, { s: .85 });
    outline(4, .28, .28, .03, .012, HUB.x, HUB.y, { s: 1.1, g: 3 }); outline(4, .16, .16, .015, .013, HUB.x, HUB.y, { s: .9, g: 3 });
    disc(4, HUB.x, HUB.y, .045, .015, { s: 1.1, g: 3 });
    fill(4, .2, .1, .015, .022, .2, .66, null, { s: .7 });
    outline(4, .14, .05, .01, .012, .2, .36, { s: .8 }); line(4, .14, .36, .26, .36, .02, { s: .6 });
    for (var ci = 0; ci < 34; ci++) { var cx2 = -.38 + rnd() * .76, cy2 = .22 + rnd() * .7; if (Math.hypot(cx2 - HUB.x, cy2 - HUB.y) < .2 || (Math.abs(cx2 - .2) < .13 && Math.abs(cy2 - .66) < .08)) continue; add(4, cx2, cy2, { s: 1.1, a: .8 }); add(4, cx2 + .018, cy2, { s: 1.1, a: .8 }); }
    [[-.24, .55, -.4, .55], [HUB.x, .69, HUB.x, .93], [.04, .55, .1, .55], [HUB.x, .41, HUB.x, .24]].forEach(function (s) { line(4, s[0], s[1], s[2], s[3], .025, { s: .55, a: .6 }); });
    line(4, .35, .18, .35, -.8, .03, { s: .6, a: .7 }); line(4, .31, .18, .31, -.8, .03, { s: .5, a: .5 });
    outline(4, .7, .16, .03, .02, -.03, -.86, { s: .8 }); outline(4, .18, .05, .025, .012, -.03, -.93, { s: 1 });

    slab(5, SW, SH, SR, .045, { s: .5, a: .4 });
    outline(5, .52, .16, .06, .015, 0, -.74, { s: .9 });
    for (var yy = -.78; yy <= -.7; yy += .026 * q) for (var xx = -.2; xx <= .2; xx += .026 * q) add(5, xx, yy, { s: .7, a: .8 });
    circ(5, .34, -.74, .022, .01, { s: .9 });
    circ(5, 0, .52, .1, .012, { s: .9 }); circ(5, 0, .52, .055, .011, { s: .8 }); circ(5, 0, .52, .02, .01, { s: .9 });
    return L;
  }

  /* ---------------- laptop ---------------- */
  var LAP = { w: 3.3, h: 2.02, dw: 3.1, dh: 3.1 * 9 / 16 };
  function buildLaptop(q) {
    var P = [];
    function add(x, y, s, a, g) { P.push({ x: x, y: y, s: s || 1, a: a == null ? 1 : a, g: g || 0 }); }
    function line(x1, y1, x2, y2, step, s, a, g) { var n = Math.max(1, Math.round(Math.hypot(x2 - x1, y2 - y1) / (step * q))); for (var i = 0; i <= n; i++) { var t = i / n; add(lerp(x1, x2, t), lerp(y1, y2, t), s, a, g); } }
    rr(LAP.w, LAP.h, .1, .016 * q).forEach(function (p) { add(p[0], p[1], 1); });
    rr(LAP.w - .05, LAP.h - .05, .085, .03 * q).forEach(function (p) { add(p[0], p[1], .55, .5); });
    var sp = .028 * q;
    for (var y = -LAP.h / 2 + .04; y < LAP.h / 2 - .03; y += sp) for (var x = -LAP.w / 2 + .04; x < LAP.w / 2 - .03; x += sp) {
      if (!inRR(x, y, LAP.w - .07, LAP.h - .07, .08)) continue;
      if (Math.abs(x) < LAP.dw / 2 + .025 && Math.abs(y) < LAP.dh / 2 + .025) continue;
      add(x, y, .55, .6);
    }
    rr(LAP.dw, LAP.dh, .015, .02 * q).forEach(function (p) { add(p[0], p[1], .7, .9); });
    for (var gy = -LAP.dh / 2 + .06; gy < LAP.dh / 2 - .03; gy += .085 * q) for (var gx = -LAP.dw / 2 + .06; gx < LAP.dw / 2 - .03; gx += .085 * q) add(gx, gy, .4, .22, 5);
    add(0, LAP.h / 2 - .035, 1.1);
    var y0 = -LAP.h / 2 - .025, y1 = y0 - .4;
    line(-1.55, y0, 1.55, y0, .016, 1.05);
    line(-1.62, y0 - .012, -1.98, y1, .018, .9); line(1.62, y0 - .012, 1.98, y1, .018, .9);
    line(-1.98, y1, 1.98, y1, .016, 1); line(-1.9, y1 - .04, 1.9, y1 - .04, .022, .7, .6);
    for (var r = 0; r < 5; r++) {
      var ky = y0 - .06 - r * .05, half = lerp(1.42, 1.66, r / 4), nk = 14 - (r === 4 ? 4 : 0), kw = (half * 2) / nk;
      for (var k = 0; k < nk; k++) { var kx = -half + kw * (k + .5), ww = (r === 4 && k === 5) ? kw * 4 : kw * .72; if (r === 4 && k > 5 && k < 9) continue; for (var j = -1; j <= 1; j++) add(kx + j * ww / 3, ky, .6, .7); }
    }
    var ty = y1 + .07; line(-.46, ty + .045, .46, ty + .045, .024, .6, .6); line(-.5, ty - .045, .5, ty - .045, .024, .6, .6); line(-.46, ty + .045, -.5, ty - .045, .02, .6, .6); line(.46, ty + .045, .5, ty - .045, .02, .6, .6);
    return P;
  }

  /* dot hands: a union of tapered capsules, filled and outlined like the phone */
  var HAND1 = [
    [-3.2, -0.42, -0.72, -0.12, .32, .245], [-0.74, -0.1, -0.12, 0.12, .25, .215],
    [-0.08, 0.06, 0.34, 0.28, .072, .064], [0.34, 0.28, 0.64, 0.5, .064, .052],
    [-0.14, 0.16, 0.14, 0.44, .076, .068], [0.14, 0.44, 0.22, 0.72, .068, .056],
    [-0.25, 0.22, -0.03, 0.49, .072, .064], [-0.03, 0.49, 0.01, 0.73, .064, .052],
    [-0.37, 0.24, -0.22, 0.46, .062, .056], [-0.22, 0.46, -0.2, 0.63, .056, .046],
    [-0.52, 0.06, -0.22, 0.28, .09, .078], [-0.22, 0.28, 0.02, 0.36, .078, .062]
  ], HTIP1 = [0.68, 0.53];
  var HAND2 = [
    [-3.2, 0.3, -0.7, 0.05, .3, .24], [-0.72, 0.05, -0.1, -0.02, .24, .2],
    [-0.5, -0.15, -0.2, -0.32, .085, .07], [-0.2, -0.32, 0.02, -0.36, .07, .055],
    [-0.1, -0.06, 0.35, -0.1, .07, .062], [0.35, -0.1, 0.7, -0.08, .062, .05],
    [-0.12, 0.06, 0.2, 0.1, .072, .066], [0.2, 0.1, 0.26, 0.28, .066, .058], [0.26, 0.28, 0.12, 0.34, .058, .05],
    [-0.16, 0.16, 0.12, 0.22, .068, .062], [0.12, 0.22, 0.14, 0.38, .062, .052],
    [-0.24, 0.22, 0.0, 0.3, .058, .052], [0.0, 0.3, -0.02, 0.42, .052, .044]
  ], HTIP2 = [0.74, -0.08];
  function capD(px, py, c) { var dx = c[2] - c[0], dy = c[3] - c[1], t = clamp(((px - c[0]) * dx + (py - c[1]) * dy) / (dx * dx + dy * dy), 0, 1); return Math.hypot(px - c[0] - dx * t, py - c[1] - dy * t) - lerp(c[4], c[5], t); }
  function smin(a, b, k) { var hh = clamp(.5 + .5 * (b - a) / k, 0, 1); return lerp(b, a, hh) - k * hh * (1 - hh); }
  function handD(set, px, py) { var d = capD(px, py, set[0]); for (var k = 1; k < set.length; k++) d = smin(d, capD(px, py, set[k]), .045); return d; }
  // halftone hand: tube-lit SDF, ordered dither, loose particles at the edges
  function buildHand(set, q) {
    var P = [], sp = .024 * q, e = .008, Lx = -.5, Ly = -.86, BY = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5];
    for (var y = -1.3, iy = 0; y < .95; y += sp, iy++) for (var x = -3.3, ix = 0; x < .95; x += sp, ix++) {
      var d = handD(set, x, y); if (d > .12) continue;
      var rr1 = ((((ix * 73856093) ^ (iy * 19349663)) >>> 0) % 1000) / 1000;
      if (d > 0) { if (rr1 < (1 - d / .12) * .16) P.push([x + (rr1 - .5) * sp, y, .35, .45, rr1 * 6.28, 1]); continue; }
      var nx = handD(set, x + e, y) - handD(set, x - e, y), ny = handD(set, x, y + e) - handD(set, x, y - e), nl = Math.hypot(nx, ny) || 1;
      var lit = .5 + .5 * ((nx * Lx + ny * Ly) / nl), depth = Math.pow(clamp(-d / .1, 0, 1), .5);
      var tone = clamp(.2 + .8 * lit * (.4 + .6 * depth), 0, 1);
      if (x < -1.9) tone *= clamp((x + 3.3) / 1.4, 0, 1);
      var th = (BY[(iy & 3) * 4 + (ix & 3)] + .5) / 16;
      if (tone < th * .85) { if (rr1 < .05) P.push([x, y, .3, .4, rr1 * 6.28, 1]); continue; }
      P.push([x, y, .45 + .8 * tone, .5 + .5 * tone, rr1 * 6.28, 0]);
    }
    return P;
  }

  function loadImg(src) { var im = new Image(); im.src = src; return im; }
  function fmt(t) { t = Math.max(0, Math.floor(t || 0)); return Math.floor(t / 60) + ':' + ('0' + (t % 60)).slice(-2); }
  function bez(a, c, b, t) { var u = 1 - t; return [u * u * a[0] + 2 * u * t * c[0] + t * t * b[0], u * u * a[1] + 2 * u * t * c[1] + t * t * b[1]]; }

  function mount(root) {
    var story = root.querySelector('[data-story]'), stage = root.querySelector('[data-stage]'), cv = root.querySelector('[data-canvas]');
    if (!story || !stage || !cv) return { destroy: function () {} };
    var ctx = cv.getContext('2d');
    function $(s) { return root.querySelector(s); }
    function $$(s) { return Array.prototype.slice.call(root.querySelectorAll(s)); }
    var caps = []; $$('[data-cap]').forEach(function (e) { caps[+e.getAttribute('data-cap')] = e; });
    var ai = $('[data-ai]'), steps = $$('[data-step]'), phaseEl = $('[data-phase]'), code = $('[data-code]'), hdr = $('header');
    var lapVid = $('[data-lapvid]'), lapV = lapVid && lapVid.querySelector('video'), vidTime = $('[data-vidtime]');
    var rap = $('[data-rapido]'), rapV = rap && rap.querySelector('video'), rapMute = $('[data-rap-mute]'), rapSeek = $('[data-rap-seek]'), rapTime = $('[data-rap-time]'), seeking = false, rapLbl = '';
    if (rapMute) rapMute.addEventListener('click', function () { Snd.press(); });
    if (rapSeek && rapV) {
      rapSeek.addEventListener('input', function () { seeking = true; if (rapV.duration) rapV.currentTime = rapSeek.value / 1000 * rapV.duration; });
      rapSeek.addEventListener('change', function () { seeking = false; });
    }
    var agentRows = $$('[data-agent]'), agentsBox = $('[data-agents]');
    var words = $$('[data-word]'), heroChrome = $$('[data-hero]'), calls = $('[data-calls]'), callRows = calls ? Array.prototype.slice.call(calls.children) : [];
    var mq = matchMedia('(prefers-reduced-motion: reduce)'), reduced = mq.matches;
    function onMq(e) { reduced = e.matches; } if (mq.addEventListener) mq.addEventListener('change', onMq);
    var small = window.innerWidth < 760, q = small ? 1.3 : 1;
    var L = buildPhone(q), LAPP = buildLaptop(q);
    var ORB = [], ON = small ? 520 : 1100; for (var oi = 0; oi < ON; oi++) { var yv = 1 - (oi + .5) / ON * 2, rad = Math.sqrt(1 - yv * yv), th = oi * 2.39996; ORB.push([Math.cos(th) * rad, yv, Math.sin(th) * rad]); }
    var LOGO = {}; ['claude', 'cursor', 'vscode', 'mcp'].forEach(function (k) { LOGO[k] = loadImg('logos/' + k + '.svg'); });
    var TINT = { cursor: 1, mcp: 1 }, tintCache = {};
    // Lucide "hand" (ISC licence), 24x24 line icon: the wave inside the connector box
    var HAND = typeof Path2D !== 'undefined' ? new Path2D('M18 11V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2M14 10V4a2 2 0 0 0-2-2a2 2 0 0 0-2 2v2M10 10.5V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2v8M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15') : null;
    var total = 0, cum = []; LENS.forEach(function (l) { cum.push(total); total += l; });
    var W = 0, H = 0, dpr = 1, vh = 0, mobile = false, col = {};
    function readColors() { var cs = getComputedStyle(document.documentElement); col.ink = cs.getPropertyValue('--ink').trim() || '#0b0c0f'; col.ink2 = cs.getPropertyValue('--ink2').trim() || '#555'; col.paper = cs.getPropertyValue('--paper').trim() || '#e7e9ec'; col.red = cs.getPropertyValue('--red').trim() || '#ff2b1c'; col.line = cs.getPropertyValue('--line').trim() || col.ink2; tintCache = {}; }
    readColors();
    function logoFor(k) {
      var im = LOGO[k]; if (!im || !im.complete || !im.naturalWidth) return null;
      if (!TINT[k]) return im;
      var key = k + col.ink; if (tintCache[key]) return tintCache[key];
      var c = document.createElement('canvas'); c.width = 96; c.height = 96; var x = c.getContext('2d');
      x.drawImage(im, 0, 0, 96, 96); x.globalCompositeOperation = 'source-in'; x.fillStyle = col.ink; x.fillRect(0, 0, 96, 96);
      tintCache[key] = c; return c;
    }
    var themeObs = new MutationObserver(readColors); themeObs.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme', 'style'] });
    var sections = [];
    function resize() {
      var nvh = window.innerHeight; W = stage.clientWidth || window.innerWidth; mobile = W < 760;
      if (!nvh || !W) { vh = 0; return; }
      if (!vh || !mobile || Math.abs(nvh - vh) > 140) { vh = nvh; story.style.height = ((total + 1) * vh) + 'px'; stage.style.height = vh + 'px'; }
      H = vh; dpr = Math.min(window.devicePixelRatio || 1, mobile ? 1.75 : 2);
      cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr);
      sections.forEach(function (s) { s.resize && s.resize(); });
    }
    var mx = 0, mxs = 0; function onPm(e) { mx = e.clientX / window.innerWidth - .5; } window.addEventListener('pointermove', onPm, { passive: true });

    function key(i) {
      var k = Object.assign({}, K[i]);
      if (!mobile && W < 1280 && k.ox > .1 && i !== 5 && i !== 6) k.ox = Math.max(k.ox, .2);
      if (!mobile && i === 6 && W < 1100) k.dim = 0;

    if (mobile) {
        k.ox = i === 1 ? .2 : 0; k.oy = -.14;
        if (i === 0) { k.sc = .62; k.oy = .16; } // sits below the hero headline
        if (k.ex > .5) { k.sc = .6; k.oy = -.17; k.ox = .06; }
        if (i === 5) { k.sc = .42; k.oy = .24; k.ox = 0; } // lower, so the connector box fits between laptop and phone
        if (i === 6) k.dim = 0;
        if (i === 7) { k.sc = .7; k.oy = -.1; }
      }
      return k;
    }
    function fwOf(f, l) { return f === -1 ? 1 : (l === f ? 1 : .2); }
    function stateAt(i, p) {
      var b = key(i), fw = [], l;
      if (reduced || i === 0 || p >= .3) { for (l = 0; l < 7; l++) fw[l] = fwOf(b.f, l); b.fw = fw; return b; }
      var a = key(i - 1), t = eio(p / .3), o = {};
      for (var n in b) o[n] = lerp(a[n], b[n], t);
      for (l = 0; l < 7; l++) fw[l] = lerp(fwOf(a.f, l), fwOf(b.f, l), t);
      o.fw = fw; o.f = t > .5 ? b.f : a.f; return o;
    }
    function capA(j, i, p) {
      if (j !== i) return 0; if (reduced) return 1;
      var fin = j === 0 ? 1 : ss(.05, .22, p), fout = j === LENS.length - 1 ? 1 : 1 - ss(.88, 1, p);
      return Math.min(fin, fout);
    }
    function vis(el, a) { if (!el) return; el.style.opacity = a.toFixed(3); el.style.visibility = a < .01 ? 'hidden' : 'visible'; el.style.pointerEvents = a > .5 ? 'auto' : 'none'; }

    var s = null, last = performance.now(), t0 = last, raf = 0, lastPhase = -1, tapCycle = -1, flags = {}, prevEx = 0, prevScat = 0, callIdx = 0, prevStep = -1, agentIdx = 0;
    var NB = 10, buckets = [], sqB = []; for (var b = 0; b < NB; b++) { buckets.push([]); sqB.push([]); }

    function sceneVars(i, p, time) {
      var v = { swap: 0, eyesOn: 0, handsOn: 0, popA: null, popB: null, pick: 0, tap: null, clients: 0, live: 0, glow: 0, wave: 0, scatter: 0, phase: -1,
        srv: 0, lap: 0, pin: 0, typed: 0, approve: 0, stream: 0, zoom: 0, vidA: 0, orb: 0, words: 0, phoneA: 1, screenOff: 0, safe: null, bed: 0 };
      function loopTap(period, a) {
        var cyc = Math.floor(time / period), ph = (time % period) / period;
        v.tap = { ph: ph / .8, a: a * (1 - ss(.72, .8, ph)) };
        if (cyc !== tapCycle) { tapCycle = cyc; return true; }
        return false;
      }
      if (i === 0) {
        var per = 3.2, ph0 = (time % per) / per, par = Math.floor(time / per) % 2;
        if (loopTap(per, 1) && !reduced) { callIdx = (callIdx + 3) % CALLS.length; agentIdx++; Snd.blip(1200); }
        var sw = ss(.14, .3, ph0); v.swap = reduced ? 0 : (par ? 1 - sw : sw);
      }
      if (i === 3) { v.srv = reduced ? 1 : ss(.18, .55, p); v.glow = v.srv; }
      if (i === 2) { v.clients = ss(.22, .45, p); v.live = ss(.45, .6, p); v.glow = ss(.2, .5, p); if (p > .58) loopTap(2.6, ss(.58, .66, p)); v.wave = .2; v.bed = 1; }
      if (i === 4) {
        var u = reduced ? .5 : clamp((p - .26) / .62, 0, 1);
        v.eyesOn = ss(.08, .26, p);
        var fadeA = 1 - ss(.6, .7, u);
        v.popA = function (k) { var st = k / 22 * .2; return ss(st, st + .05, u) * fadeA; };
        var popped = Math.floor(clamp(u / .2, 0, 1) * 22); if (popped !== flags.pop && u < .22) { flags.pop = popped; if (popped > 0) Snd.blip(1300 + popped * 30); }
        v.pick = ss(.25, .32, u) * (1 - ss(.62, .68, u));
        if (u > .45 && u < .72) { v.tap = { ph: (u - .45) / .25, a: 1 - ss(.66, .72, u) }; if (!flags.t4) { flags.t4 = 1; Snd.blip(900); } } else if (u < .4) flags.t4 = 0;
        v.handsOn = ss(.38, .47, u) * (1 - ss(.6, .7, u)) * .9;
        v.swap = ss(.62, .72, u);
        v.popB = function (k) { var st = .74 + k * .025; return ss(st, st + .05, u); };
        v.phase = reduced ? 1 : (u < .25 ? 0 : u < .45 ? 1 : u < .65 ? 2 : 3);
      }
      if (i === 5) {
        if (reduced) { v.lap = 1; v.pin = 1; v.typed = 1; v.approve = 1; v.stream = 1; v.zoom = 1; v.vidA = 1; }
        else {
          v.lap = ss(.0, .06, p) * (1 - ss(.93, 1, p)); v.pin = ss(.06, .1, p); v.typed = ss(.1, .18, p); v.approve = ss(.19, .22, p); v.stream = ss(.22, .28, p);
          v.zoom = ss(.3, .44, p) * (1 - ss(.88, .96, p)); v.vidA = ss(.3, .38, p) * (1 - ss(.86, .91, p));
          v.hub = ss(.17, .22, p); // connector box shows once the phone has settled under the laptop
          var tch = Math.floor(v.typed * 22); if (tch !== flags.ty && v.typed > 0 && v.typed < 1) { flags.ty = tch; Snd.key(); }
        }
        v.screenOff = v.pin; v.phoneA = 1 - v.zoom;
        if (v.approve > .9 && !flags.c5) { flags.c5 = 1; Snd.chime(); } if (v.approve < .3) flags.c5 = 0;
      }
      if (i === 6) { v.screenOff = 1; v.orb = reduced ? 1 : ss(.04, .18, p) * (1 - ss(.92, 1, p)); v.words = reduced ? 1 : ss(.1, .42, p); }
      if (i === 7) {
        var sf = { agentIn: ss(.08, .26, p), blocked: ss(.28, .34, p), humanIn: ss(.46, .56, p), pause: ss(.58, .62, p), agentOut: ss(.62, .8, p) };
        if (reduced) sf = { agentIn: 1, blocked: 1, humanIn: 1, pause: 1, agentOut: 0 };
        v.safe = sf;
        if (sf.blocked > .5 && !flags.b7) { flags.b7 = 1; Snd.deny(); } if (sf.blocked < .2) flags.b7 = 0;
        if (sf.pause > .5 && !flags.p7) { flags.p7 = 1; Snd.blip(800); } if (sf.pause < .2) flags.p7 = 0;
      }
      if (i === 8) v.scatter = reduced ? 0 : ss(.06, .26, p) * (1 - ss(.84, .98, p));
      if (i === 9) loopTap(2.2, 1);
      if (reduced && v.tap) v.tap.ph = .15;
      return v;
    }

    function draw(st, v, time, intro, i, p) {
      var Wp = cv.width, Hp = cv.height;
      ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.clearRect(0, 0, Wp, Hp);
      var out = {};
      var unit = (mobile ? Math.min(H * .19, W * .42) : Math.min(H * .3, W * .34)) * dpr;
      var U = unit * st.sc, cx = Wp * (.5 + st.ox + (i === 5 ? v.zoom * .45 : 0)), cy = Hp * (.5 + st.oy), cam = 7;
      var idle = reduced ? 0 : 1;
      var yaw = (st.yaw + idle * (Math.sin(time * .35) * 3 + mxs * 6)) * D, pitch = (st.pitch + idle * Math.cos(time * .27) * 1.5) * D, roll = st.roll * D;
      var cr = Math.cos(roll), sr = Math.sin(roll), cyw = Math.cos(yaw), syw = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
      function P(x, y, z) {
        var x1 = x * cr - y * sr, y1 = x * sr + y * cr;
        var x2 = x1 * cyw + z * syw, z2 = -x1 * syw + z * cyw;
        var y3 = y1 * cp - z2 * sp, z3 = y1 * sp + z2 * cp;
        var k = cam / (cam - z3);
        return [cx + x2 * U * k, cy - y3 * U * k, z3, k];
      }
      var lz = [], la = [], l;
      for (l = 0; l < 7; l++) lz[l] = Z0[l] + st.ex * ZE[l];
      var srvZ = v.srv * 1.15 * (1 - ss(.3, .8, st.ex)), srvY = v.srv * .12 * (1 - ss(.3, .8, st.ex));
      var exA = ss(.3, .8, st.ex);
      la[0] = 1; la[6] = 1; la[1] = Math.max(exA, v.eyesOn); la[2] = Math.max(exA, v.handsOn); la[3] = exA; la[4] = Math.max(exA, v.srv); la[5] = exA;
      var scat = Math.max(v.scatter, intro * .8);
      var fd = st.f >= 0 ? P(0, 0, lz[st.f])[2] : 0, dofAmt = st.f >= 0 ? st.ex : 0;
      var dotR = U * .0056, glowS = 1 + .35 * Math.max(v.glow, v.srv * .6) * (.5 + .5 * Math.sin(time * 3));
      for (var bb = 0; bb < NB; bb++) { buckets[bb].length = 0; sqB[bb].length = 0; }
      function push(x, y, r, a) { if (a < .02) return; var bi = Math.min(NB - 1, (a * NB) | 0); buckets[bi].push(x, y, r); }
      function sq(x, y, r, a) { if (a < .02) return; var bi = Math.min(NB - 1, (a * NB) | 0); sqB[bi].push(x, y, r); }
      var dim = st.dim * v.phoneA;
      if (dim > .01) for (l = 0; l < 7; l++) {
        var base = la[l] * st.fw[l] * dim; if (base < .01) continue;
        var pts = L[l];
        for (var pi = 0; pi < pts.length; pi++) {
          var pt = pts[pi], a = base * pt.a;
          if (pt.g === 1) a *= (1 - v.swap) * (1 - v.screenOff); else if (pt.g === 2) a *= v.swap * (1 - v.screenOff); else if (pt.g === 4) a *= exA; else if (pt.g === 6) a *= i === 0 ? 0 : (1 - exA);
          if (l === 1 && pt.k >= 0) a *= pt.k >= 100 ? (v.popB ? v.popB(pt.k - 100) : 1) : (v.popA ? v.popA(pt.k) : 1);
          if (a < .02) continue;
          var x = pt.x, y = pt.y, z = lz[l] + pt.z;
          if (l === 4 && v.srv > 0) { z += srvZ; y += srvY; }
          if (scat > 0) { x += pt.rx * scat * 1.7; y += pt.ry * scat * 1.7; z += pt.rz * scat * 1.7; a *= 1 - .55 * scat; }
          var qq = P(x, y, z);
          if (dofAmt > 0) a *= 1 - Math.min(.55, Math.abs(qq[2] - fd) * .2) * dofAmt;
          var r = dotR * pt.s * qq[3];
          if (pt.g === 3) r *= glowS;
          if (l === 1 && pt.k === 6) r *= 1 + v.pick * .7;
          push(qq[0], qq[1], r, a);
        }
      }
      var wv = Math.max(v.wave, st.ex * .25) * la[5] * st.fw[5] * dim;
      if (wv > .01) for (var b2 = 0; b2 < 22; b2++) {
        var bx = -.33 + b2 * (.66 / 21), en = Math.sin(Math.PI * (b2 + .5) / 22);
        var hg = (.04 + .3 * Math.abs(Math.sin(time * 2.3 + b2 * .55) * Math.sin(time * 1.3 + b2 * .21))) * en * Math.max(.25, v.wave);
        var cnt = Math.round(hg / .028);
        for (var j = -cnt; j <= cnt; j++) { var q2 = P(bx, -.1 + j * .028, lz[5]); push(q2[0], q2[1], dotR * q2[3] * .95, wv * (1 - Math.abs(j) / (cnt + 2) * .5)); }
      }
      // laptop
      var lapR = null;
      if (i === 5 && v.lap > .01) {
        // phones: the zoomed video runs full width at 16:9 and sits just above the caption card
        // (the header slides away meanwhile); the empty space goes to the top
        var cTop5 = mobile && caps[5] ? caps[5].getBoundingClientRect().top : H;
        var LU0 = mobile ? W * .8 / LAP.w : Math.min(W * .36 / LAP.w, H * .36 / LAP.h), LU1 = mobile ? W / LAP.dw : Math.min(W / LAP.dw, (H - 16) / LAP.dh);
        var lx0 = mobile ? W * .5 : W * .29, ly0 = mobile ? H * .25 : H * .43, lx1 = W * .5;
        var ly1 = mobile ? Math.max(LAP.dh * LU1 / 2 + 8, cTop5 - 16 - LAP.dh * LU1 / 2) : H * .5;
        var z = eio(v.zoom), LU = lerp(LU0, LU1, z), lx = lerp(lx0, lx1, z), ly = lerp(ly0, ly1, z);
        var lr = Math.max(.9, LU * .0062) * dpr;
        for (var k2 = 0; k2 < LAPP.length; k2++) {
          var lp = LAPP[k2], la2 = lp.a * v.lap * (lp.g === 5 ? 1 - v.vidA : 1); if (la2 < .02) continue;
          var X = (lx + lp.x * LU) * dpr, Y = (ly - lp.y * LU) * dpr; if (X < -10 || X > Wp + 10 || Y < -10 || Y > Hp + 10) continue;
          push(X, Y, lr * lp.s, la2);
        }
        lapR = { x: lx - LAP.dw / 2 * LU, y: ly - LAP.dh / 2 * LU, w: LAP.dw * LU, h: LAP.dh * LU, LU: LU, lx: lx, ly: ly };
      }
      // voice orb, placed above the caption so they never overlap
      if (i === 6 && v.orb > .01 && !mobile && W >= 1100) {
        var oc = P(0, .06, lz[0]), R = U / dpr * .3, oxC = oc[0] / dpr, oyC = oc[1] / dpr;
        {
          var spk = reduced ? .5 : Math.abs(Math.sin(time * 4.3) * Math.sin(time * 1.1 + 1)) * (v.words > 0 && v.words < 1 ? 1 : .25) + .1;
          var rt = time * .35, cr2 = Math.cos(rt), sr2 = Math.sin(rt), tl = -.35, ct = Math.cos(tl), st2 = Math.sin(tl);
          for (var k3 = 0; k3 < ORB.length; k3++) {
            var o = ORB[k3], lat = Math.asin(o[1]), lon = Math.atan2(o[2], o[0]);
            var disp = 1 + spk * (.14 * Math.sin(lat * 5 + time * 6) * Math.cos(lon * 3 - time * 2.2) + .08 * Math.sin(lon * 7 + time * 4.6));
            var X1 = o[0] * cr2 - o[2] * sr2, Z1 = o[0] * sr2 + o[2] * cr2, Y1 = o[1] * ct - Z1 * st2, Z2 = o[1] * st2 + Z1 * ct;
            var kk = 3 / (3 - Z2 * .6);
            push((oxC + X1 * R * disp * kk) * dpr, (oyC - Y1 * R * disp * kk) * dpr, (1 + .9 * (Z2 + 1) / 2) * dpr * (mobile ? .8 : 1.05), v.orb * (.25 + .75 * (Z2 + 1) / 2));
          }
        }
      }
      ctx.fillStyle = col.ink;
      for (bb = 0; bb < NB; bb++) {
        var bk = buckets[bb]; if (!bk.length) continue;
        ctx.globalAlpha = (bb + .5) / NB; ctx.beginPath();
        for (var m = 0; m < bk.length; m += 3) { ctx.moveTo(bk[m] + bk[m + 2], bk[m + 1]); ctx.arc(bk[m], bk[m + 1], bk[m + 2], 0, TAU); }
        ctx.fill();
      }
      for (bb = 0; bb < NB; bb++) { var sb = sqB[bb]; if (!sb.length) continue; ctx.globalAlpha = (bb + .5) / NB; ctx.beginPath(); for (var m2 = 0; m2 < sb.length; m2 += 3) ctx.rect(sb[m2] - sb[m2 + 2] / 2, sb[m2 + 1] - sb[m2 + 2] / 2, sb[m2 + 2], sb[m2 + 2]); ctx.fill(); }
      ctx.globalAlpha = 1;
      function mono(px, w) { return (w || 500) + ' ' + Math.round(px * dpr) + 'px "Geist Mono", ui-monospace, monospace'; }
      function chip(txt, x, y, a, align, inv) {
        if (a < .02) return;
        ctx.font = mono(11); var tw = ctx.measureText(txt).width, pw = tw + 16 * dpr, ph = 22 * dpr, x0 = align === 'right' ? x - pw : align === 'center' ? x - pw / 2 : x;
        x0 = clamp(x0, 8 * dpr, cv.width - pw - 8 * dpr); // never let a label run off a narrow screen
        ctx.globalAlpha = a; ctx.fillStyle = inv ? col.ink : col.paper; ctx.strokeStyle = col.ink; ctx.lineWidth = 1 * dpr;
        ctx.beginPath(); if (ctx.roundRect) ctx.roundRect(x0, y - ph / 2, pw, ph, 6 * dpr); else ctx.rect(x0, y - ph / 2, pw, ph); ctx.fill(); if (!inv) ctx.stroke();
        ctx.fillStyle = inv ? col.paper : col.ink; ctx.textAlign = 'left'; ctx.textBaseline = 'middle'; ctx.fillText(txt, x0 + 8 * dpr, y + .5 * dpr); ctx.globalAlpha = 1;
        return [x0, pw];
      }
      function tags(list, g, popFn, isB) {
        var ga = (g === 1 ? 1 - v.swap : v.swap) * la[1] * st.fw[1] * dim * (1 - scat);
        if (ga < .03) return;
        var fs = clamp(U * .032, 8.5 * dpr, 12 * dpr);
        ctx.font = '600 ' + Math.round(fs) + 'px "Geist Mono", ui-monospace, monospace'; ctx.textBaseline = 'middle'; ctx.textAlign = 'center';
        list.forEach(function (bx, k) {
          var a = ga * (popFn ? popFn(k) : 1); if (a < .03) return;
          var q = P(bx[0] - bx[2] / 2, bx[1] + bx[3] / 2, lz[1]);
          var picked = !isB && bx[4] === 7 ? v.pick : 0, sc = 1 + picked * .45;
          var txt = String(bx[4]), w = (ctx.measureText(txt).width + fs * .7) * sc, h = fs * 1.35 * sc;
          ctx.globalAlpha = a; ctx.fillStyle = col.ink; ctx.fillRect(q[0] - 1, q[1] - h / 2, w, h);
          ctx.fillStyle = col.paper; ctx.save(); ctx.translate(q[0] - 1 + w / 2, q[1] + fs * .05); ctx.scale(sc, sc); ctx.fillText(txt, 0, 0); ctx.restore();
          if (picked > .02) {
            var c1 = P(bx[0] - bx[2] / 2, bx[1] + bx[3] / 2, lz[1]), c2 = P(bx[0] + bx[2] / 2, bx[1] + bx[3] / 2, lz[1]), c3 = P(bx[0] + bx[2] / 2, bx[1] - bx[3] / 2, lz[1]), c4 = P(bx[0] - bx[2] / 2, bx[1] - bx[3] / 2, lz[1]);
            ctx.globalAlpha = a * picked; ctx.strokeStyle = col.ink; ctx.lineWidth = 1.6 * dpr;
            ctx.beginPath(); ctx.moveTo(c1[0], c1[1]); ctx.lineTo(c2[0], c2[1]); ctx.lineTo(c3[0], c3[1]); ctx.lineTo(c4[0], c4[1]); ctx.closePath(); ctx.stroke();
          }
        });
        ctx.globalAlpha = 1;
      }
      if (dim > .01) { tags(BOXA, 1, v.popA, false); tags(BOXB, 2, v.popB, true); }

      var labA = ss(.45, .9, st.ex) * dim * (1 - scat);
      if (labA > .02) {
        ctx.font = mono(11); ctx.textAlign = 'left'; ctx.textBaseline = 'middle'; ctx.lineWidth = 1 * dpr;
        for (l = 0; l < 7; l++) {
          var q3 = P(SW / 2, SH / 2 - .04, lz[l]), on = st.fw[l] > .6;
          ctx.globalAlpha = labA; ctx.strokeStyle = on ? col.ink : col.ink2; ctx.fillStyle = on ? col.ink : col.ink2;
          ctx.beginPath(); ctx.moveTo(q3[0] + 4 * dpr, q3[1]); ctx.lineTo(q3[0] + 28 * dpr, q3[1]); ctx.stroke();
          ctx.fillText(NAMES[l], q3[0] + 34 * dpr, q3[1]);
        }
        ctx.globalAlpha = 1;
      }
      // screen-space bounds of every layer, so labels and the connector can sit beside the drawing, not on it
      function phoneBox() {
        var b = { t: 1e9, b: -1e9, l: 1e9, r: -1e9 };
        for (var l2 = 0; l2 < 7; l2++) for (var c4 = 0; c4 < 4; c4++) {
          var q4 = P((c4 & 1 ? 1 : -1) * PW / 2, (c4 & 2 ? 1 : -1) * PH / 2, lz[l2]);
          b.t = Math.min(b.t, q4[1]); b.b = Math.max(b.b, q4[1]); b.l = Math.min(b.l, q4[0]); b.r = Math.max(b.r, q4[0]);
        }
        return b;
      }
      // the connector box: a hand waves hello, then it turns into the MCP mark (logoA 0 -> 1)
      function hubBox(x, y, s, a, logoA) {
        if (a < .02) return;
        ctx.globalAlpha = a; ctx.fillStyle = col.paper; ctx.strokeStyle = col.ink; ctx.lineWidth = 1.2 * dpr;
        ctx.beginPath(); if (ctx.roundRect) ctx.roundRect(x - s / 2, y - s / 2, s, s, s * .26); else ctx.rect(x - s / 2, y - s / 2, s, s); ctx.fill(); ctx.stroke();
        var ha = a * (1 - logoA);
        if (HAND && ha > .02) {
          var hk = s * .5 / 24, wave = reduced ? 0 : Math.sin(time * 10) * .38;
          ctx.save(); ctx.globalAlpha = ha; ctx.translate(x, y + s * .22); ctx.rotate(wave); ctx.scale(hk, hk); ctx.translate(-12, -21);
          ctx.strokeStyle = col.ink; ctx.lineWidth = 1.9; ctx.lineCap = 'round'; ctx.lineJoin = 'round'; ctx.stroke(HAND); ctx.restore();
        }
        var mi = logoFor('mcp');
        if (mi && logoA > .02) { var ms = s * .54 * (.7 + .3 * eio(logoA)); ctx.globalAlpha = a * logoA; ctx.drawImage(mi, x - ms / 2, y - ms / 2, ms, ms); }
        ctx.globalAlpha = 1;
      }
      function cub(p0, p1, p2, p3, t) { var u = 1 - t; return [u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0], u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1]]; }
      function pulse(pt, a) { ctx.globalAlpha = a; ctx.fillStyle = col.red; ctx.beginPath(); ctx.arc(pt[0], pt[1], 3 * dpr, 0, TAU); ctx.fill(); ctx.globalAlpha = 1; }

      if (v.srv > .02) {
        if (mobile) { // phones: both labels sit under the drawing, not across it
          var bb3 = phoneBox(), ly3 = bb3.b + 22 * dpr;
          chip('MCP server · on this phone', Wp / 2, ly3, v.srv * dim, 'center', true);
          chip('127.0.0.1:4816 · no backend', Wp / 2, ly3 + 28 * dpr, v.srv * dim, 'center');
        } else {
          var sq = P(HUB.x + .21, HUB.y + srvY + .12, lz[4] + srvZ);
          chip('MCP server · on this phone', sq[0] + 10 * dpr, sq[1], v.srv * dim, 'left', true);
          chip('127.0.0.1:4816 · no backend', sq[0] + 10 * dpr, sq[1] + 28 * dpr, v.srv * dim, 'left');
        }
      }
      // AI clients -> one connector box -> the MCP server layer inside the phone
      if (v.clients > .01) {
        var srvP = P(HUB.x, HUB.y, lz[4]), bb = phoneBox(), HS = (mobile ? 42 : 48) * dpr, isz = 28 * dpr;
        var capT = caps[2] ? caps[2].getBoundingClientRect().top * dpr : Hp, below = capT - bb.b, above = bb.t - 70 * dpr;
        // wide screens: clients in a column left of the phone; otherwise a row under (or over) the drawing
        var mode = !mobile && bb.l > 330 * dpr ? 'left' : below >= 150 * dpr ? 'below' : 'above', hc, nodes = [], j3;
        if (mode === 'left') {
          hc = [bb.l - 80 * dpr, clamp(srvP[1], 110 * dpr, capT - 130 * dpr)];
          for (j3 = 0; j3 < 4; j3++) nodes.push([hc[0] - 130 * dpr, hc[1] + (j3 - 1.5) * 58 * dpr]);
        } else {
          var dirY = mode === 'below' ? 1 : -1, edge = mode === 'below' ? bb.b : bb.t, room = mode === 'below' ? below : above, spc = Math.min(84 * dpr, Wp * .2);
          hc = [Wp / 2, edge + dirY * room * .28];
          for (j3 = 0; j3 < 4; j3++) nodes.push([Wp / 2 + (j3 - 1.5) * spc, edge + dirY * room * .68]);
        }
        var hIn = mode === 'left' ? [hc[0] - HS / 2, hc[1]] : [hc[0], hc[1] + dirY * HS / 2];
        var hOut = mode === 'left' ? [hc[0] + HS / 2, hc[1]] : [hc[0], hc[1] - dirY * HS / 2];
        var ca = v.clients * dim;
        // hub -> server: one clean line into the phone
        ctx.globalAlpha = ca * .8; ctx.strokeStyle = col.ink; ctx.lineWidth = 1.2 * dpr;
        ctx.beginPath(); ctx.moveTo(hOut[0], hOut[1]); ctx.lineTo(srvP[0], srvP[1]); ctx.stroke();
        ctx.globalAlpha = ca; ctx.fillStyle = col.ink; ctx.beginPath(); ctx.arc(srvP[0], srvP[1], 3 * dpr, 0, TAU); ctx.fill();
        if (v.live > .5 && !reduced) pulse([lerp(hOut[0], srvP[0], (time * .9) % 1), lerp(hOut[1], srvP[1], (time * .9) % 1)], ca);
        ctx.font = mono(mobile ? 11 : 12); ctx.textBaseline = 'middle';
        CLIENTS.forEach(function (cl, j2) {
          var nd = nodes[j2], a = ca * ss(j2 * .12, j2 * .12 + .5, v.clients), isLive = j2 === 0 && v.live > .01;
          var e0 = mode === 'left' ? [nd[0] + isz / 2 + 4 * dpr, nd[1]] : [nd[0], nd[1] - dirY * (isz / 2 + 4 * dpr)];
          var m1 = mode === 'left' ? [lerp(e0[0], hIn[0], .5), e0[1]] : [e0[0], lerp(e0[1], hIn[1], .5)];
          var m2 = mode === 'left' ? [lerp(e0[0], hIn[0], .5), hIn[1]] : [hIn[0], lerp(e0[1], hIn[1], .5)];
          ctx.globalAlpha = a * .7; ctx.strokeStyle = isLive ? col.red : col.ink2; ctx.lineWidth = (isLive ? 1.4 : 1) * dpr;
          ctx.beginPath(); ctx.moveTo(e0[0], e0[1]); ctx.bezierCurveTo(m1[0], m1[1], m2[0], m2[1], hIn[0], hIn[1]); ctx.stroke();
          if (isLive && !reduced) pulse(cub(e0, m1, m2, hIn, (time * .9) % 1), a * v.live);
          ctx.globalAlpha = a; ctx.fillStyle = '#fff'; ctx.strokeStyle = isLive ? col.red : col.line; ctx.lineWidth = 1 * dpr;
          ctx.beginPath(); if (ctx.roundRect) ctx.roundRect(nd[0] - isz / 2 - 3 * dpr, nd[1] - isz / 2 - 3 * dpr, isz + 6 * dpr, isz + 6 * dpr, 9 * dpr); else ctx.rect(nd[0] - isz / 2, nd[1] - isz / 2, isz, isz); ctx.fill(); ctx.stroke();
          var im = LOGO[cl[1]]; if (im && im.complete && im.naturalWidth) ctx.drawImage(im, nd[0] - isz * .34, nd[1] - isz * .34, isz * .68, isz * .68);
          ctx.fillStyle = col.ink;
          if (mode === 'left') { ctx.textAlign = 'right'; ctx.fillText(cl[0], nd[0] - isz / 2 - 12 * dpr, nd[1]); }
          else { ctx.textAlign = 'center'; ctx.fillText(cl[0], nd[0], nd[1] + dirY * 30 * dpr); }
        });
        hubBox(hc[0], hc[1], HS, ca, v.live);
      }
      // pairing: PIN on the phone, typed on the laptop, approve, then the link
      if (i === 5 && lapR) {
        var pa = v.pin * (1 - v.zoom) * v.phoneA;
        if (pa > .01) {
          var pc = P(0, .12, lz[0]), fs2 = Math.max(14, U / dpr * .1);
          ctx.globalAlpha = pa; ctx.fillStyle = col.ink2; ctx.textAlign = 'center'; ctx.textBaseline = 'middle'; ctx.font = mono(10);
          ctx.fillText(v.approve > .5 ? 'Connected' : 'Pairing code', pc[0], pc[1] - fs2 * 1.3 * dpr);
          ctx.fillStyle = col.ink; ctx.font = '800 ' + Math.round(fs2 * dpr) + 'px Doto, "Geist Mono", monospace';
          ctx.fillText(v.approve > .5 ? '✓' : '482 913', pc[0], pc[1]);
          if (v.typed > .95) {
            var ap = P(0, -.25, lz[0]), aw = U * .5, ah = U * .11;
            ctx.globalAlpha = pa * ss(.95, 1, v.typed); ctx.fillStyle = v.approve > .5 ? col.ink : col.paper; ctx.strokeStyle = col.ink; ctx.lineWidth = 1 * dpr;
            ctx.beginPath(); if (ctx.roundRect) ctx.roundRect(ap[0] - aw / 2, ap[1] - ah / 2, aw, ah, ah / 2); else ctx.rect(ap[0] - aw / 2, ap[1] - ah / 2, aw, ah); ctx.fill(); ctx.stroke();
            ctx.fillStyle = v.approve > .5 ? col.paper : col.ink; ctx.font = mono(10, 600); ctx.fillText(v.approve > .5 ? 'Approved' : 'Approve', ap[0], ap[1] + .5 * dpr);
          }
          ctx.globalAlpha = 1;
        }
        var ta2 = v.lap * (1 - v.vidA);
        if (ta2 > .01) {
          var cmd = '$ aura-mcp pair 482913', shown = cmd.slice(0, Math.floor(v.typed * cmd.length));
          ctx.globalAlpha = ta2; ctx.fillStyle = col.ink; ctx.font = mono(Math.max(9, lapR.LU * .075)); ctx.textAlign = 'left'; ctx.textBaseline = 'top';
          var tx = (lapR.x + lapR.w * .06) * dpr, ty = (lapR.y + lapR.h * .1) * dpr, lh = Math.max(13, lapR.LU * .12) * dpr;
          ctx.fillText(shown + (v.typed < 1 && Math.floor(time * 2) % 2 ? '▍' : ''), tx, ty);
          if (v.approve > .5) { ctx.fillStyle = col.ink2; ctx.fillText('paired with this phone · waiting for your AI', tx, ty + lh); }
          ctx.globalAlpha = 1;
        }
        // laptop -> connector box -> phone. The box waves while pairing, becomes MCP once approved.
        var ha5 = (reduced ? 1 : v.hub) * (1 - v.zoom);
        if (ha5 > .01) {
          var A2 = mobile ? [lapR.lx * dpr, (lapR.ly + (LAP.h / 2 + .6) * lapR.LU) * dpr + 12 * dpr] : [(lapR.lx + LAP.w / 2 * lapR.LU + 10) * dpr, lapR.ly * dpr];
          var B2 = mobile ? P(0, PH / 2 + .04, lz[0]) : P(-PW / 2 - .04, 0, lz[0]);
          var gap5 = mobile ? B2[1] - A2[1] : B2[0] - A2[0], HS5 = clamp(gap5 - 24 * dpr, 30 * dpr, 48 * dpr);
          var hc5 = [lerp(A2[0], B2[0], .5), lerp(A2[1], B2[1], .5)];
          var i5 = mobile ? [hc5[0], hc5[1] - HS5 / 2] : [hc5[0] - HS5 / 2, hc5[1]], o5 = mobile ? [hc5[0], hc5[1] + HS5 / 2] : [hc5[0] + HS5 / 2, hc5[1]];
          var r1 = ss(0, .5, v.stream), r2 = ss(.5, 1, v.stream);
          ctx.globalAlpha = ha5 * .8; ctx.strokeStyle = col.ink; ctx.lineWidth = 1.2 * dpr; ctx.beginPath();
          if (r1 > 0) { ctx.moveTo(A2[0], A2[1]); ctx.lineTo(lerp(A2[0], i5[0], r1), lerp(A2[1], i5[1], r1)); }
          if (r2 > 0) { ctx.moveTo(o5[0], o5[1]); ctx.lineTo(lerp(o5[0], B2[0], r2), lerp(o5[1], B2[1], r2)); }
          ctx.stroke();
          if (r2 > .99 && !reduced) { // one red packet, laptop to phone, through the box
            var pt5 = (time * .7) % 1, seg = pt5 < .5 ? [A2, i5, pt5 * 2] : [o5, B2, pt5 * 2 - 1];
            pulse([lerp(seg[0][0], seg[1][0], seg[2]), lerp(seg[0][1], seg[1][1], seg[2])], ha5);
          }
          hubBox(hc5[0], hc5[1], HS5, ha5, v.stream); // waves while pairing, MCP mark once the link is up
          ctx.globalAlpha = ha5 * ss(.5, 1, v.stream); ctx.font = mono(11); ctx.fillStyle = col.ink2; ctx.textBaseline = 'middle';
          if (mobile) { ctx.textAlign = 'left'; ctx.fillText('MCP · encrypted', hc5[0] + HS5 / 2 + 10 * dpr, hc5[1]); }
          else { ctx.textAlign = 'center'; ctx.fillText('MCP · encrypted, phone to laptop', hc5[0], hc5[1] + HS5 / 2 + 16 * dpr); }
          ctx.globalAlpha = 1;
        }
        var lba = v.lap * (1 - v.zoom);
        if (lba > .01) {
          ctx.globalAlpha = lba; ctx.font = mono(11); ctx.fillStyle = col.ink2; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
          ctx.fillText('your laptop · Claude Code', lapR.lx * dpr, (lapR.ly + (LAP.h / 2 + .6) * lapR.LU) * dpr);
          if (!mobile) { var pb = P(0, -PH / 2 - .14, lz[0]); ctx.fillText('your phone · AURA', pb[0], pb[1]); }
          ctx.globalAlpha = 1;
        }
      }
      // safety: an intent line from the agent, blocked at the gate; your tap on Pause wins
      if (v.safe) {
        var sf2 = v.safe, bq2 = P(BANK.x, BANK.y, lz[0]), pp = P(0, -.56, lz[0]);
        var scrL = P(-SW / 2, 0, lz[0])[0], scrR = P(SW / 2, 0, lz[0])[0], scrT = P(0, SH / 2, lz[0])[1];
        var ain = sf2.agentIn * (1 - sf2.agentOut);
        var agX = mobile ? W * .5 * dpr : scrL - 40 * dpr, agY = mobile ? scrT - 28 * dpr : bq2[1] - 70 * dpr;
        var agTxt = sf2.agentOut > .5 ? 'AI agent · waiting for you' : 'AI agent · launch_app("Bank")';
        chip(agTxt, agX, agY, ss(0, .4, sf2.agentIn), mobile ? 'center' : 'right');
        if (ain > .01) {
          var sx0 = mobile ? agX : agX + 2 * dpr, sy0 = agY + (mobile ? 11 * dpr : 0), ex0 = bq2[0], ey0 = bq2[1];
          var reach2 = ain * (sf2.blocked > .5 ? 1 : 1), exx = lerp(sx0, ex0, reach2), eyy = lerp(sy0, ey0, reach2);
          ctx.globalAlpha = ain; ctx.strokeStyle = col.ink; ctx.lineWidth = 1.2 * dpr; ctx.setLineDash([3 * dpr, 4 * dpr]); ctx.lineDashOffset = -time * 30 * dpr;
          ctx.beginPath(); ctx.moveTo(sx0, sy0); ctx.lineTo(exx, eyy); ctx.stroke(); ctx.setLineDash([]); ctx.globalAlpha = 1;
        }
        if (sf2.blocked > .01) {
          var gsz = .15 * U * (1 + (1 - sf2.blocked) * .6), ba = sf2.blocked * (1 - sf2.agentOut * .7);
          ctx.globalAlpha = ba; ctx.strokeStyle = col.ink; ctx.lineWidth = 1.4 * dpr;
          ctx.beginPath(); ctx.moveTo(bq2[0], bq2[1] - gsz); ctx.lineTo(bq2[0] + gsz, bq2[1]); ctx.lineTo(bq2[0], bq2[1] + gsz); ctx.lineTo(bq2[0] - gsz, bq2[1]); ctx.closePath(); ctx.stroke();
          var xs = 8 * dpr; ctx.strokeStyle = col.red; ctx.lineWidth = 2.6 * dpr; ctx.lineCap = 'round';
          ctx.beginPath(); ctx.moveTo(bq2[0] - xs, bq2[1] - xs); ctx.lineTo(bq2[0] + xs, bq2[1] + xs); ctx.moveTo(bq2[0] + xs, bq2[1] - xs); ctx.lineTo(bq2[0] - xs, bq2[1] + xs); ctx.stroke(); ctx.lineCap = 'butt';
          ctx.globalAlpha = 1;
          chip('blocked · banking_or_payment_app', mobile ? W * .5 * dpr : scrL - 40 * dpr, mobile ? scrT - 56 * dpr : bq2[1] - 40 * dpr, ba, mobile ? 'center' : 'right');
          chip('Bank', bq2[0], bq2[1] + gsz + 14 * dpr, ba, 'center');
        }
        var pillA = ss(0, .5, sf2.humanIn);
        if (pillA > .01) {
          var ptxt = sf2.pause > .5 ? 'Paused · you drive' : 'Pause';
          ctx.font = mono(12, 600); var pw2 = ctx.measureText(ptxt).width + 36 * dpr, ph2 = 30 * dpr;
          ctx.globalAlpha = pillA; ctx.fillStyle = col.ink; ctx.beginPath(); if (ctx.roundRect) ctx.roundRect(pp[0] - pw2 / 2, pp[1] - ph2 / 2, pw2, ph2, ph2 / 2); else ctx.rect(pp[0] - pw2 / 2, pp[1] - ph2 / 2, pw2, ph2); ctx.fill();
          ctx.fillStyle = col.paper; ctx.textAlign = 'center'; ctx.textBaseline = 'middle'; ctx.fillText(ptxt, pp[0], pp[1] + .5 * dpr); ctx.globalAlpha = 1;
          if (!reduced && sf2.humanIn > .9) { var rp2 = sf2.pause < 1 ? sf2.pause : (time % 1.6) / 1.6; ctx.globalAlpha = (1 - rp2) * .8; ctx.strokeStyle = col.ink; ctx.lineWidth = 1.2 * dpr; ctx.beginPath(); ctx.arc(pp[0] + pw2 / 2 - 14 * dpr, pp[1], (8 + rp2 * 30) * dpr, 0, TAU); ctx.stroke(); ctx.globalAlpha = 1; }
          if (!mobile) chip('you', scrR + 40 * dpr, pp[1], pillA, 'left');
        }
      }
      // red tap
      var tapQ = P(TAP.x, TAP.y, lz[0]);
      if (v.tap && v.tap.a > .01) {
        var ta = v.tap.a * dim * (1 - scat);
        ctx.fillStyle = col.red; ctx.globalAlpha = ta; ctx.beginPath(); ctx.arc(tapQ[0], tapQ[1], 5.5 * dpr * tapQ[3], 0, TAU); ctx.fill();
        ctx.strokeStyle = col.red; ctx.lineWidth = 1.5 * dpr;
        for (var rn = 0; rn < 2; rn++) { var qv = v.tap.ph - rn * .28; if (qv <= 0 || qv >= 1) continue; ctx.globalAlpha = ta * (1 - qv) * .9; ctx.beginPath(); ctx.arc(tapQ[0], tapQ[1], (7 + qv * 42) * dpr, 0, TAU); ctx.stroke(); }
        ctx.globalAlpha = 1;
      }
      out.lap = lapR; out.U = U; out.tap = [tapQ[0] / dpr, tapQ[1] / dpr];
      return out;
    }

    function playVid(vd, on, restart) {
      if (!vd) return;
      if (on && !reduced) {
        if (vd.paused) {
          if (restart) try { vd.currentTime = 0; } catch (e) {}
          var rate = +vd.getAttribute('data-rate') || 1; if (vd.playbackRate !== rate) vd.playbackRate = rate;
          var pr = vd.play(); if (pr && pr.catch) pr.catch(function () { if (!vd.muted) { vd.muted = true; vd.play().catch(function () {}); } });
        }
      } else if (!vd.paused) vd.pause();
    }
    function place(el, r) { el.style.left = r.x.toFixed(1) + 'px'; el.style.top = r.y.toFixed(1) + 'px'; el.style.width = r.w.toFixed(1) + 'px'; el.style.height = r.h.toFixed(1) + 'px'; }

    var lapPlaying = false, rapPlaying = false;
    function storyFrame(now, dt) {
      var top = story.getBoundingClientRect().top, sT = -top / vh;
      Snd.quiet(sT < -.05 || sT > total - .05);
      if (s === null || reduced) s = sT; else s += (sT - s) * (1 - Math.pow(.88, dt * 60));
      if (sT > total + 1.3 || sT < -1.3) {
        playVid(lapV, false); playVid(rapV, false); lapPlaying = rapPlaying = false;
        if (hdr && hdr._hidden) { hdr._hidden = false; hdr.style.transform = ''; }
        return;
      }
      mxs += (mx - mxs) * .05;
      var time = reduced ? 2 : now / 1000;
      var intro = reduced ? 0 : 1 - eio(ss(.1, 2, (now - t0) / 1000));
      var sc = clamp(s, 0, total - 1e-4), i = 0; while (i < LENS.length - 1 && sc >= cum[i + 1]) i++;
      var p = (sc - cum[i]) / LENS[i];
      var st = stateAt(i, p), v = sceneVars(i, p, time);
      var o = draw(st, v, time, intro, i, p);
      // sound follows motion: servo from the explode speed, sand from the dissolve
      var stepN = Math.floor(st.ex * 22); if (stepN !== prevStep) { if (prevStep >= 0) Snd.tick(st.ex); prevStep = stepN; }
      if (prevEx < .97 && st.ex >= .97) Snd.latch();
      if (prevEx > .03 && st.ex <= .03) Snd.latch(true);
      prevEx = st.ex; prevScat = v.scatter;
      for (var j = 0; j < caps.length; j++) { var el = caps[j]; if (!el) continue; var a = capA(j, i, p); if (j === 0) a *= 1 - intro * .9; if (j === 5 && !reduced) a *= ss(.4, .5, p); vis(el, a); el.style.transform = 'translate3d(0,' + ((1 - a) * 18).toFixed(1) + 'px,0)'; }
      var hA = i === 0 ? (1 - ss(.55, .95, p)) * (1 - intro) : 0;
      heroChrome.forEach(function (e) { vis(e, hA); });
      if (agentsBox && mobile) vis(agentsBox, 0);

      if (agentRows.length && agentRows._a !== agentIdx) { agentRows._a = agentIdx; var actR = agentIdx % agentRows.length; agentRows.forEach(function (row, jj) { var s3 = row.querySelector('[data-ast]'); row.style.opacity = jj === actR ? '1' : '.6'; row.style.borderColor = jj === actR ? 'var(--red)' : ''; if (s3) { s3.textContent = jj === actR ? 'calling tools' : 'connected'; s3.style.color = jj === actR ? 'var(--red)' : 'var(--ink2)'; } }); }

      if (calls) {
        var cb = caps[0] ? caps[0].getBoundingClientRect().bottom + 28 : H * .34, cTop = Math.max(H * .34, cb), room = W >= 1100 && cTop + 150 < H * .62;
        if (room) calls.style.top = Math.round(cTop) + 'px';
        vis(calls, mobile || !room ? 0 : hA);
        if (calls._idx !== callIdx) { calls._idx = callIdx; callRows.forEach(function (row, k) { var c = CALLS[(callIdx + k) % CALLS.length]; row.textContent = c; row.style.opacity = k === 2 ? '1' : (k < 2 ? '.4' : '.18'); row.style.color = k === 2 ? 'var(--ink)' : 'var(--ink2)'; }); }
        
      }
      if (ai) { var aa = capA(1, i, p); vis(ai, aa); ai.style.transform = 'translate3d(' + ((1 - aa) * -30).toFixed(1) + 'px,' + (reduced ? 0 : Math.sin(time * 1.2) * 8).toFixed(1) + 'px,0)'; }
      if (i === 4 && v.phase !== lastPhase) {
        if (lastPhase !== -1 || v.phase > 0) Snd.blip(1400);
        lastPhase = v.phase;
        steps.forEach(function (e, k) { e.style.opacity = k === v.phase ? '1' : (k < v.phase ? '.34' : '.18'); });
        if (phaseEl) phaseEl.textContent = PHASE[v.phase];
      }
      if (lapVid) {
        if (i === 5 && o.lap) { place(lapVid, o.lap); lapVid.style.borderRadius = (Math.max(0, 1 - v.zoom) * 6).toFixed(1) + 'px'; }
        vis(lapVid, i === 5 ? v.vidA : 0);
        var want = i === 5 && v.vidA > .2;
        if (want && !lapPlaying) { playVid(lapV, true, true); lapPlaying = true; } else if (!want && lapPlaying) { playVid(lapV, false); lapPlaying = false; }
        if (vidTime && lapV) vidTime.textContent = fmt(lapV.currentTime) + ' / ' + fmt(lapV.duration || 59);
      }
      // phones: the header gets out of the way while the demo video is full width
      var hideHdr = mobile && i === 5 && v.zoom > .5;
      if (hdr && hdr._hidden !== hideHdr) { hdr._hidden = hideHdr; hdr.style.transition = 'transform .35s cubic-bezier(.2,.7,.2,1)'; hdr.style.transform = hideHdr ? 'translateY(-100%)' : ''; }
      if (rap) {
        var ra = i === 6 ? v.orb : 0;
        if (ra > 0) {
          var narrowR = mobile || W < 1100;
          var vf = caps[6] && caps[6].querySelector('[data-vfeat]'); if (vf) vf.style.display = (narrowR || H < 760) ? 'none' : 'grid';
          var vdesc = caps[6] && caps[6].querySelector('[data-vdesc]');
          if (mobile) { if (vdesc) vdesc.style.display = 'none'; var c6t = caps[6] ? caps[6].getBoundingClientRect().top : H * .55; rap.style.left = '20px'; rap.style.right = '20px'; rap.style.top = '72px'; rap.style.maxWidth = 'none'; rap.style.alignItems = 'center'; rap.style.height = Math.max(Math.round(H * .4), Math.round(c6t - 72 - 14)) + 'px'; }
          else if (narrowR) { if (vdesc) vdesc.style.display = ''; var c6r2 = caps[6] ? caps[6].getBoundingClientRect().right : W * .5; rap.style.left = Math.round(c6r2 + 24) + 'px'; rap.style.right = '20px'; rap.style.top = '72px'; rap.style.maxWidth = 'none'; rap.style.alignItems = 'center'; rap.style.height = Math.round(H - 72 - 40) + 'px'; }
          else { if (vdesc) vdesc.style.display = ''; var c6r = caps[6] ? caps[6].getBoundingClientRect().right : W * .45; rap.style.left = Math.round(c6r + 32) + 'px'; rap.style.right = Math.round(W * .05) + 'px'; rap.style.maxWidth = 'none'; rap.style.alignItems = 'flex-end'; rap.style.top = Math.round(H * .12) + 'px'; rap.style.height = Math.round(H * .76) + 'px'; }
        }
        vis(rap, ra);
        var wantR = i === 6 && ra > .3;
        if (rapV) {
          var sst = Snd.state(), soundOK = sst === 'on';
          if (rapV.muted === soundOK) rapV.muted = !soundOK;
          if (rapMute && rapLbl !== sst) { rapLbl = sst; var sl = window.AuraSoundLabel(sst); rapMute.textContent = sl.text; rapMute.setAttribute('aria-label', sl.aria); rapMute.setAttribute('aria-pressed', String(sst === 'on')); rapMute.style.background = sst === 'tap' ? 'var(--ink)' : 'var(--paper)'; rapMute.style.color = sst === 'tap' ? 'var(--paper)' : 'var(--ink)'; }
          if (rapSeek && !seeking && rapV.duration) rapSeek.value = String(Math.round(rapV.currentTime / rapV.duration * 1000));
          if (rapTime) rapTime.textContent = fmt(rapV.currentTime) + ' / ' + fmt(rapV.duration);
          if (wantR && !rapPlaying) { playVid(rapV, true, true); rapPlaying = true; } else if (!wantR && rapPlaying) { playVid(rapV, false); rapPlaying = false; }
        }
      }
      var nw = Math.round(v.words * words.length);
      if (i === 6) words.forEach(function (w, k) { var on = k < nw; if (w._on !== on) { w._on = on; w.style.opacity = on ? '1' : '.18'; } });
      if (code) vis(code, i === 8 ? (reduced ? 1 : v.scatter) : 0);
    }

    /* ---------- sections below the film ---------- */
    function secProg(el) { var r = el.getBoundingClientRect(); var span = r.height - window.innerHeight; return { p: span > 0 ? clamp(-r.top / span, 0, 1) : 0, vis: r.bottom > 0 && r.top < window.innerHeight, r: r }; }

    $$('[data-ticker]').forEach(function (el) {
      var inner = el.firstElementChild; if (!inner) return; inner.innerHTML += inner.innerHTML; var x = 0;
      sections.push({ frame: function (t, dt) { if (reduced || (el.parentElement && el.parentElement.style.visibility === 'hidden')) return; x -= dt * 26; var half = inner.scrollWidth / 2; if (-x > half) x += half; inner.style.transform = 'translate3d(' + x.toFixed(1) + 'px,0,0)'; } });
    });

    // tools: a split-flap board, one category at a time, readable once it settles
    var bsec = $('[data-boardsec]');
    if (bsec) {
      var bName = $('[data-bd-name]'), bDesc = $('[data-bd-desc]'), bTools = $('[data-bd-tools]'), bCount = $('[data-bd-count]'), bCats = $$('[data-cat]');
      var cur = -1, flips = [], GL = 'abcdefghijklmnopqrstuvwxyz_';
      function scramble(el, text, delay) { flips.push({ el: el, text: text, start: performance.now() + delay, dur: 380 }); }
      function show(k) {
        cur = k; var c = CATS[k], sum = 0; for (var i2 = 0; i2 <= k; i2++) sum += CATS[i2][2].length;
        bCats.forEach(function (e, j) { e.style.color = j === k ? 'var(--ink)' : 'var(--ink2)'; e.style.opacity = j === k ? '1' : (j < k ? '.55' : '.32'); e.style.fontWeight = j === k ? '600' : '400'; });
        if (bName) { bName.textContent = c[0]; }
        if (bDesc) bDesc.textContent = c[1];
        if (bCount) bCount.textContent = sum + ' / 56';
        if (bTools) {
          bTools.innerHTML = '';
          c[2].forEach(function (tn, j) {
            var sp = document.createElement('span'); sp.textContent = tn;
            sp.style.cssText = 'display:inline-block;font-family:"Geist Mono",monospace;font-size:clamp(15px,1.6vw,22px);padding:8px 14px;border-radius:8px;border:1px solid var(--line);background:var(--paper);white-space:nowrap';
            bTools.appendChild(sp); if (!reduced) scramble(sp, tn, j * 45);
          });
        }
        Snd.blip(1500);
      }
      sections.push({ frame: function (t, dt, now) {
        var sp2 = secProg(bsec); if (!sp2.vis) return;
        var k = Math.min(CATS.length - 1, Math.floor(sp2.p * CATS.length * .999));
        if (k !== cur) show(k);
        for (var f = flips.length - 1; f >= 0; f--) {
          var fl = flips[f], e = (now - fl.start) / fl.dur;
          if (e < 0) { fl.el.style.opacity = '0'; continue; }
          fl.el.style.opacity = '1';
          if (e >= 1) { fl.el.textContent = fl.text; flips.splice(f, 1); continue; }
          var n2 = Math.floor(e * fl.text.length), outS = fl.text.slice(0, n2);
          for (var c2 = n2; c2 < fl.text.length; c2++) outS += fl.text[c2] === '_' ? '_' : GL[(Math.random() * 26) | 0];
          fl.el.textContent = outS;
        }
      } });
    }

    // real apps: a constellation of app tiles; AURA's line reaches for one at a time
    var cst = $('[data-constel]');
    if (cst) {
      var tiles = $$('[data-ci]'), csvg = cst.querySelector('[data-csvg]'), talk = $('[data-ctalk]'), capEl = $('[data-mq-cap]'), capApp = $('[data-mq-app]');
      var act = -1, nextAt = 0, actT = 0, actPath = null, actLen = 0, cW = 0, cH = 0, pts = [], NS = 'http://www.w3.org/2000/svg';
      // Phones keep the desktop constellation (tiles around the headline) at 80% size, using data-mx/my
      // percentages nudged so every tile and label stays on a narrow screen.
      var ctext = cst.querySelector('[data-ctext]'), TSC = function () { return mobile ? .8 : 1; };
      function tileP(el, a) { return +el.getAttribute((mobile && el.hasAttribute('data-m' + a) ? 'data-m' : 'data-') + a); }
      function tileXY(k) { return [tileP(tiles[k], 'x') / 100 * cW, tileP(tiles[k], 'y') / 100 * cH]; }
      function curve(a, b, bend) { var mx2 = (a[0] + b[0]) / 2, my2 = (a[1] + b[1]) / 2, dx = b[0] - a[0], dy = b[1] - a[1]; return 'M' + a[0].toFixed(1) + ' ' + a[1].toFixed(1) + ' Q' + (mx2 - dy * bend).toFixed(1) + ' ' + (my2 + dx * bend).toFixed(1) + ' ' + b[0].toFixed(1) + ' ' + b[1].toFixed(1); }
      function talkXY() { var cr = cst.getBoundingClientRect(), tr = talk ? talk.getBoundingClientRect() : null; return tr ? [tr.left - cr.left + tr.width / 2, tr.top - cr.top + tr.height / 2] : [cW / 2, cH * .75]; }
      function buildC() {
        cst.style.height = mobile ? Math.max(620, (ctext ? ctext.offsetHeight : 260) + 400) + 'px' : 'clamp(720px,100svh,960px)';
        cW = cst.clientWidth; cH = cst.clientHeight;
        tiles.forEach(function (el) {
          el.style.left = tileP(el, 'x') + '%'; el.style.top = tileP(el, 'y') + '%';
          // tiles on the right edge hang their label to the left so it stays on screen
          var lb = el.querySelector('span[style*="bottom"]'); if (lb) lb.style.transform = mobile && tileP(el, 'x') > 70 ? 'translateX(-70%)' : 'translateX(-30%)';
        });
        if (!csvg) return;
        var html = '<defs>';
        tiles.forEach(function (el, k) { var ar = el.getAttribute('data-arc'); if (ar == null) return; var a = tileXY(k), b = tileXY(+ar); html += '<linearGradient id="cg' + k + '" gradientUnits="userSpaceOnUse" x1="' + a[0] + '" y1="' + a[1] + '" x2="' + b[0] + '" y2="' + b[1] + '"><stop offset="0" stop-color="currentColor" stop-opacity="0"/><stop offset=".55" stop-color="currentColor" stop-opacity=".9"/><stop offset="1" stop-color="currentColor" stop-opacity="0"/></linearGradient>'; });
        html += '</defs>';
        tiles.forEach(function (el, k) { var ar = el.getAttribute('data-arc'); if (ar == null) return; html += '<path d="' + curve(tileXY(k), tileXY(+ar), .22) + '" fill="none" stroke="url(#cg' + k + ')" stroke-width="1.3"/>'; });
        html += '<path data-cact="" fill="none" stroke="var(--red)" stroke-width="1.6" stroke-linecap="round"/><circle data-cdot="" r="4" fill="var(--red)" opacity="0"/>';
        csvg.innerHTML = html; csvg.style.color = 'var(--ink)';
        actPath = csvg.querySelector('[data-cact]'); act = -1; nextAt = 0;
      }
      function setAct(k, now) {
        act = k; actT = now; var el = tiles[k];
        if (actPath) { actPath.setAttribute('d', curve(talkXY(), tileXY(k), (tileXY(k)[0] < cW / 2 ? -.18 : .18))); actLen = actPath.getTotalLength ? actPath.getTotalLength() : 600; actPath.style.strokeDasharray = actLen + ' ' + actLen; actPath.style.strokeDashoffset = actLen; }
        if (capEl) { capEl.textContent = el.getAttribute('data-prompt'); }
        if (capApp) capApp.textContent = el.getAttribute('data-label');
        tiles.forEach(function (t, j2) { t.style.boxShadow = j2 === k ? '0 0 0 2px var(--red),0 26px 46px -20px rgba(0,0,0,.5)' : ''; });
        Snd.blip(1400);
      }
      if (document.fonts) document.fonts.ready.then(function () { cW = 0; }); // headline height changes once fonts land; frame() rebuilds
      tiles.forEach(function (el, k) { el.addEventListener('click', function () { setAct(k, performance.now()); nextAt = performance.now() + 5200; }); });
      sections.push({ resize: buildC, frame: function (t, dt, now) {
        var r = cst.getBoundingClientRect(), vhh = window.innerHeight; if (r.bottom < 0 || r.top > vhh) return;
        if (!cW) buildC();
        var e = reduced ? 1 : eio(clamp((vhh - r.top) / (vhh * .75), 0, 1));
        tiles.forEach(function (el, k) {
          var xy = tileXY(k), dx = (cW / 2 - xy[0]) * (1 - e), dy = (cH / 2 - xy[1]) * (1 - e), rot = +el.getAttribute('data-r') * e;
          var fl = reduced ? 0 : Math.sin(t * .7 + k * 1.7) * 6, sc = (.55 + .45 * e) * (k === act ? 1.1 : 1) * TSC();
          el.style.transform = 'translate(-50%,-50%) translate3d(' + dx.toFixed(1) + 'px,' + (dy + fl).toFixed(1) + 'px,0) rotate(' + rot.toFixed(1) + 'deg) scale(' + sc.toFixed(3) + ')';
          el.style.opacity = e.toFixed(3);
        });
        if (csvg) csvg.style.opacity = ss(.6, 1, e).toFixed(3);
        if (e > .95 && now > nextAt) { setAct((act + 1) % tiles.length, now); nextAt = now + 3000; }
        if (actPath && act >= 0) {
          var dr = reduced ? 1 : ss(0, .7, (now - actT) / 1000); actPath.style.strokeDashoffset = (actLen * (1 - dr)).toFixed(1);
          var dot = csvg.querySelector('[data-cdot]'); if (dot && actPath.getPointAtLength) { var pp = actPath.getPointAtLength(actLen * dr); dot.setAttribute('cx', pp.x); dot.setAttribute('cy', pp.y); dot.setAttribute('opacity', dr < 1 ? '1' : '0'); }
        }
      } });
    }

    var gsec = $('[data-gatesec]');
    if (gsec) {
      var gRules = $$('[data-rule]'), gList = $('[data-rules]'), gReq = $('[data-g-req]'), gSrc = $('[data-g-src]'), gV = $('[data-g-verdict]'), gWhy = $('[data-g-why]'), gT = $('[data-g-title]'), gD = $('[data-g-desc]'), gC = $('[data-g-count]');
      var gCur = -1, gT0 = 0, gStamp = false;
      function gShow(k, now) {
        gCur = k; gT0 = now; gStamp = false; var li = gRules[k];
        gRules.forEach(function (e, j2) { e.style.opacity = j2 === k ? '1' : (j2 < k ? '.55' : '.32'); });
        gSrc.textContent = li.getAttribute('data-src'); gV.textContent = li.getAttribute('data-v'); gV.style.color = li.getAttribute('data-red') === '1' ? '#FF2B1C' : '#F5F5F2';
        gWhy.textContent = li.getAttribute('data-why'); gT.textContent = li.getAttribute('data-t'); gD.textContent = li.getAttribute('data-d'); gC.textContent = (k + 1) + ' / ' + gRules.length;
        Snd.blip(1300);
      }
      sections.push({ frame: function (t, dt, now) {
        var sp2 = secProg(gsec); if (!sp2.vis || !gRules.length) return;
        if (gList) gList.style.display = (window.innerWidth < 800 || window.innerHeight < 720) ? 'none' : 'grid'; // below 800px the grid is one column and the list would push the card off screen
        var k = Math.min(gRules.length - 1, Math.floor(sp2.p * gRules.length * .999)); if (k !== gCur) gShow(k, now);
        var e = (now - gT0) / 1000, full = gRules[k].getAttribute('data-req'), n = reduced ? full.length : Math.min(full.length, Math.floor(e * 38));
        var txt = full.slice(0, n) + (n < full.length ? '▍' : ''); if (gReq.textContent !== txt) gReq.textContent = txt;
        var ts = full.length / 38 + .25, sa = reduced ? 1 : clamp((e - ts) / .2, 0, 1);
        gV.style.opacity = sa.toFixed(3); gV.style.transform = 'scale(' + (1.3 - .3 * eio(sa)).toFixed(3) + ') rotate(' + (-5 * (1 - sa)).toFixed(2) + 'deg)';
        gWhy.style.opacity = (reduced ? 1 : clamp((e - ts - .15) / .3, 0, 1)).toFixed(3);
        if (sa >= 1 && !gStamp) { gStamp = true; if (gRules[k].getAttribute('data-red') === '1') Snd.deny(); else Snd.latch(true); }
      } });
    }

    $$('[data-qrow]').forEach(function (el) {
      var inner = el.firstElementChild; if (!inner) return; inner.innerHTML += inner.innerHTML; var dir = +el.getAttribute('data-qrow') || 1;
      sections.push({ frame: function (t) {
        var r = el.getBoundingClientRect(); if (r.bottom < 0 || r.top > window.innerHeight) return;
        var half = inner.scrollWidth / 2, sc = window.scrollY * .25 + (reduced ? 0 : t * 16);
        var x = -((sc * dir) % half + half) % half; inner.style.transform = 'translate3d(' + x.toFixed(1) + 'px,0,0)';
      } });
    });

    $$('[data-hsec]').forEach(function (sec) {
      var track = sec.querySelector('[data-htrack]'), vids = Array.prototype.slice.call(sec.querySelectorAll('video'));
      if (!track) return;
      // data-hstack sections drop the sideways pin on phones and read top to bottom (mobile.css lays them out)
      var stacked = function () { return mobile && sec.hasAttribute('data-hstack'); };
      sections.push({
        resize: function () {
          if (stacked()) { sec.style.height = ''; track.style.transform = ''; return; }
          var extra = Math.max(0, track.scrollWidth - window.innerWidth); sec.style.height = (window.innerHeight + extra * 1.6) + 'px';
        },
        frame: function () {
          if (stacked()) { vids.forEach(function (vd) { var rv = vd.getBoundingClientRect(); playVid(vd, rv.bottom > 0 && rv.top < window.innerHeight); }); return; }
          var sp2 = secProg(sec), extra = Math.max(0, track.scrollWidth - window.innerWidth);
          track.style.transform = 'translate3d(' + (-sp2.p * extra).toFixed(1) + 'px,0,0)';
          var clk = sec.querySelector('[data-hclock]'); if (clk) { var ct = fmt(sp2.p * 300); if (clk.textContent !== ct) clk.textContent = ct; }
          vids.forEach(function (vd) { var rr2 = vd.getBoundingClientRect(); playVid(vd, sp2.vis && rr2.right > 0 && rr2.left < window.innerWidth); });
        }
      });
    });

    $$('video[data-inview]').forEach(function (vd) { sections.push({ frame: function () { var r = vd.getBoundingClientRect(); playVid(vd, r.bottom > 0 && r.top < window.innerHeight); } }); });

    $$('[data-term]').forEach(function (term) {
      var lines = Array.prototype.slice.call(term.querySelectorAll('[data-tl]')), started = false, tStart = 0, lastLen = 0;
      lines.forEach(function (ln) { ln._full = ln.getAttribute('data-tl'); ln.textContent = reduced ? ln._full : ''; });
      sections.push({ frame: function (t, dt, now) {
        if (reduced) return;
        var r = term.getBoundingClientRect(); if (!started) { if (r.top < window.innerHeight * .75 && r.bottom > 0) { started = true; tStart = now; } else return; }
        var el = (now - tStart) / 1000, acc = 0, typedNow = 0;
        lines.forEach(function (ln) {
          var isOut = ln.hasAttribute('data-out'), dur = isOut ? .5 : ln._full.length / 15 + .5;
          var f = clamp((el - acc) / dur, 0, 1); acc += dur;
          var txt = isOut ? (f > .5 ? ln._full : '') : ln._full.slice(0, Math.floor(f * ln._full.length));
          typedNow += txt.length;
          if (ln.textContent !== txt) ln.textContent = txt;
        });
        if (typedNow > lastLen) { lastLen = typedNow; if (r.bottom > 0 && r.top < window.innerHeight) Snd.key(); }
      } });
    });

    $$('[data-tests]').forEach(function (box) {
      var rows = Array.prototype.slice.call(box.querySelectorAll('[data-ts]')), k = -1, next = 0;
      function paint() { rows.forEach(function (row, j) { var st2 = row.querySelector('[data-st]'), dot = row.querySelector('[data-dot]'); row.style.opacity = j <= k ? '1' : '.35'; if (st2) st2.textContent = j < k ? 'pass' : j === k ? 'running' : 'queued'; if (dot) dot.style.background = j === k ? 'var(--red)' : j < k ? 'var(--ink)' : 'transparent'; }); }
      if (reduced) { k = rows.length; paint(); return; }
      sections.push({ frame: function (t, dt, now) {
        var r = box.getBoundingClientRect(); if (r.bottom < 0 || r.top > window.innerHeight) return;
        if (now > next) { k++; if (k > rows.length + 1) k = -1; next = now + (k >= rows.length ? 2400 : 1200); paint(); if (k >= 0 && k < rows.length) Snd.blip(); }
      } });
    });

    var io = null, rev = $$('[data-reveal]');
    if ('IntersectionObserver' in window && !reduced) {
      rev.forEach(function (el) { el.style.opacity = '0'; el.style.transform = 'translate3d(0,40px,0)'; el.style.transition = 'opacity 1s cubic-bezier(.2,.7,.2,1), transform 1.1s cubic-bezier(.2,.7,.2,1)'; });
      io = new IntersectionObserver(function (es) { es.forEach(function (e) { if (e.isIntersecting) { e.target.style.opacity = '1'; e.target.style.transform = 'none'; io.unobserve(e.target); } }); }, { rootMargin: '0px 0px -8% 0px' });
      rev.forEach(function (el) { io.observe(el); });
    }

    var dock = $('[data-dock]'), dockW = $('[data-dockwrap]'), dctx = dock && dock.getContext('2d'), dockA = 0, dvel = 0, lastSY = window.scrollY;
    function dockFrame(now, dt) {
      if (!dock || !vh) return;
      var want = story.getBoundingClientRect().bottom < vh * .4 ? 1 : 0;
      dockA += (want - dockA) * (1 - Math.pow(.86, dt * 60)); vis(dockW, dockA > .995 ? 1 : dockA);
      if (dockA < .01) return;
      var sy = window.scrollY, vel = (sy - lastSY) / Math.max(dt, .001); lastSY = sy; dvel += (clamp(vel / 1800, -1, 1) - dvel) * .08;
      var dp = Math.min(window.devicePixelRatio || 1, 2), cw = Math.round(dock.clientWidth * dp), chh = Math.round(dock.clientHeight * dp);
      if (dock.width !== cw || dock.height !== chh) { dock.width = cw; dock.height = chh; }
      dctx.setTransform(1, 0, 0, 1, 0, 0); dctx.clearRect(0, 0, cw, chh);
      var t = now / 1000, U2 = chh / 2.5, cx2 = cw / 2, cy2 = chh / 2;
      var yaw = ((reduced ? 0 : Math.sin(t * .6) * 10) + dvel * 40) * D, roll = -10 * D, cyw = Math.cos(yaw), syw = Math.sin(yaw), cr = Math.cos(roll), sr = Math.sin(roll);
      var sw2 = reduced ? 0 : ss(.4, .6, Math.abs(((t / 5.2) % 1) * 2 - 1));
      dctx.fillStyle = col.ink;
      [0, 6].forEach(function (l) {
        var pts = L[l]; dctx.globalAlpha = .85; dctx.beginPath();
        for (var k = 0; k < pts.length; k++) {
          var pt = pts[k]; if (pt.g === 4 || pt.g === 6) continue;
          if ((pt.g === 1 && sw2 > .5) || (pt.g === 2 && sw2 <= .5)) continue;
          var x1 = pt.x * cr - pt.y * sr, y1 = pt.x * sr + pt.y * cr, z = Z0[l] + pt.z, x2 = x1 * cyw + z * syw, z2 = -x1 * syw + z * cyw, kk = 7 / (7 - z2);
          var X = cx2 + x2 * U2 * kk, Y = cy2 - y1 * U2 * kk, r = Math.max(.55 * dp, U2 * .009 * pt.s);
          dctx.moveTo(X + r, Y); dctx.arc(X, Y, r, 0, TAU);
        }
        dctx.fill();
      });
      if (!reduced) { var tp2 = (t % 2.6) / 2.6, tx2 = cx2 + (TAP.x * cr - TAP.y * sr) * U2, ty2 = cy2 - (TAP.x * sr + TAP.y * cr) * U2; dctx.fillStyle = col.red; dctx.strokeStyle = col.red; dctx.globalAlpha = 1 - ss(.6, .8, tp2); dctx.beginPath(); dctx.arc(tx2, ty2, 2.4 * dp, 0, TAU); dctx.fill(); dctx.globalAlpha = (1 - tp2) * .8; dctx.lineWidth = dp; dctx.beginPath(); dctx.arc(tx2, ty2, (3 + tp2 * 14) * dp, 0, TAU); dctx.stroke(); }
      dctx.globalAlpha = 1;
    }
    function tick(now) {
      raf = requestAnimationFrame(tick);
      var dt = Math.min(.05, (now - last) / 1000); last = now;
      if (!vh || !W || cv.width === 0) { resize(); if (!vh) return; }
      storyFrame(now, dt);
      Snd.quiet(false);
      var t = now / 1000;
      for (var k = 0; k < sections.length; k++) sections[k].frame && sections[k].frame(t, dt, now);
    }
    resize(); window.addEventListener('resize', resize);
    setTimeout(resize, 400);
    var ro = null; if ('ResizeObserver' in window) { var rw = 0; ro = new ResizeObserver(function () { var w = document.documentElement.clientWidth; if (w !== rw) { rw = w; resize(); } }); ro.observe(document.documentElement); }
    raf = requestAnimationFrame(tick);
    return {
      destroy: function () {
        cancelAnimationFrame(raf); window.removeEventListener('resize', resize); window.removeEventListener('pointermove', onPm);
        themeObs.disconnect(); if (io) io.disconnect(); if (ro) ro.disconnect(); if (mq.removeEventListener) mq.removeEventListener('change', onMq);
      }
    };
  }
  window.AuraFilm = { mount: mount };
})();
