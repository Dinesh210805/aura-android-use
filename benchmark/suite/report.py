"""Builds benchmark pages out of a results folder: one run, an index and a page per task.

A pure function of that folder: whatever has been run is what appears. Nothing here talks
to a device, so pages can be rebuilt on any machine that has the folder. The suite uses it
for its local preview and for publishing to the website (`publish.py`).

    python benchmark/suite/report.py --demo     # self-check

- Reads: `<results>/<Task>/` as `run_one` and the suite write it: meta.json, the logs in
  LOGS, the videos, and `session/<id>/` (AURA's own step log, copied off the phone).
- Writes: `<site>/index.html`, `<site>/run.json`, `<site>/t/<Task>.html`, `<site>/t/<Task>/`.
- Change together: theme.py (the CSS), publish.py (reads run.json), and the phone's
  session format in `mcp/log/SessionLog.kt` (read by `steps_html`).
"""

import datetime
import html
import json
import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import theme  # noqa: E402

# Longest log tail worth inlining; the raw file is always linked in full.
LOG_TAIL_LINES = 400
# Logs a task folder can hold, in the order a task page shows them. Change together:
# suite.py writes live.log and app.log; external_agent.py the agent.* logs.
LOGS = (('Agent activity', 'live.log'), ("Claude Code's raw stream", 'agent.stdout.log'),
        ("AURA's agent log", 'agent.log'), ('AURA app log (logcat)', 'app.log'),
        ('Harness output', 'harness.log'))
# Longest tool result shown on a step card. The phone already caps what it stores.
STEP_TEXT_MAX = 4000
# Published screenshots are shrunk to this width: a phone frame at half size stays legible
# and keeps a 116-task run to a few MB in git.
SHOT_WIDTH = 540

TASK_LIST = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..',
                         'aura-android', 'app', 'src', 'main', 'assets', 'eval',
                         'androidworld.json')

ABOUT_ONDEVICE = {
    'title': 'AURA on AndroidWorld',
    'sub': ('An on-device Android agent, scored by Google\'s AndroidWorld benchmark. '
            'Every task below was screen-recorded start to finish.'),
    'method': ('Not a like-for-like leaderboard number: the information-retrieval '
               'tasks are excluded (AURA has no channel for the typed answer they '
               'grade), the device clock is moved from 2023 to 2026 so TLS to the '
               'model provider works, and AURA gets a wall-clock limit instead of '
               'AndroidWorld\'s step budget.'),
}

esc = html.escape


# --- small helpers -----------------------------------------------------------

def _read_json(path):
  try:
    with open(path, encoding='utf-8') as f:
      return json.load(f)
  except (OSError, ValueError):
    return None


def _read_results(results_dir):
  if not os.path.isdir(results_dir):
    return []
  metas = (_read_json(os.path.join(results_dir, n, 'meta.json'))
           for n in sorted(os.listdir(results_dir)))
  return [m for m in metas if m]


def _fmt_secs(value):
  try:
    seconds = int(round(float(value)))
  except (TypeError, ValueError):
    return '–'
  return '%dm %02ds' % (seconds // 60, seconds % 60) if seconds >= 60 else '%ds' % seconds


def _cost(meta):
  return ((meta.get('agent') or {}).get('usage') or {}).get('cost_usd')


def _steps(meta):
  """Phone actions used out of the stock budget, when the harness counted them."""
  agent = meta.get('agent') or {}
  if agent.get('steps_used') is not None:
    return '%s of %s' % (agent['steps_used'], agent.get('step_budget') or '–')
  return '–'


def _verdict(meta):
  ok, agent = meta.get('success'), meta.get('agent') or {}
  if ok is True:
    return '<span class="pill pass">pass</span>'
  if ok is None:
    return '<span class="pill none">not scored</span>'
  why = ('out of steps' if agent.get('out_of_steps') else
         'timeout' if agent.get('timed_out') else 'fail')
  return '<span class="pill fail">%s</span>' % why


def _scrub(text):
  """Drops this PC's home folder from published text: it names the local user, nothing more."""
  home = os.path.expanduser('~')
  return text.replace(home, '~').replace(home.replace('\\', '/'), '~') \
             .replace(home.replace('\\', '\\\\'), '~')


def _shell(title, body, depth=0):
  """A page in the website's look: header with the wordmark and theme switch."""
  up = '../' * depth
  return (
      '<!doctype html><html lang="en"><head><meta charset="utf-8">'
      '<meta name="viewport" content="width=device-width,initial-scale=1">'
      '<title>%s</title>%s<link rel="stylesheet" href="%sstyle.css">%s</head><body>'
      '<header class="site"><div class="in"><a class="brand" href="/">AURA</a>'
      '<span class="crumb">AndroidWorld benchmark</span><span style="flex:1"></span>'
      '<button class="theme" type="button" aria-label="Switch theme" onclick="auraTheme()">'
      '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
      'stroke-width="1.8" aria-hidden="true"><circle cx="12" cy="12" r="8"></circle>'
      '<path d="M12 4a8 8 0 0 1 0 16Z" fill="currentColor"></path></svg></button>'
      '</div></header><main class="wrap">%s</main></body></html>'
      % (esc(title), theme.FONTS, up, theme.THEME_SCRIPT, body))


# --- AURA's step log ---------------------------------------------------------

def _shot(session_dir, index):
  folder = os.path.join(session_dir, 'screenshots')
  for ext in ('jpg', 'png'):
    if os.path.isfile(os.path.join(folder, '%d.%s' % (index, ext))):
      return 'screenshots/%d.%s' % (index, ext)
  return None


def _pretty_args(raw):
  try:
    return json.dumps(json.loads(raw), indent=2, ensure_ascii=False)
  except (TypeError, ValueError):
    return raw or ''


def _clip(text, limit=STEP_TEXT_MAX):
  text = text or ''
  return text if len(text) <= limit else text[:limit] + '\n… (%d more characters)' % (len(text) - limit)


def _marks(inv, w, h):
  """Tap / swipe markers over the screenshot, positioned in % of the phone screen."""
  if not (w and h and inv.get('gestureType') and inv.get('tapX') is not None):
    return ''
  pts = [(inv['tapX'], inv['tapY'])]
  if inv.get('tapX2') is not None:
    pts.append((inv['tapX2'], inv['tapY2']))
  dots = ''.join('<i class="mark%s" style="left:%.2f%%;top:%.2f%%"></i>'
                 % (' end' if i else '', 100.0 * x / w, 100.0 * y / h)
                 for i, (x, y) in enumerate(pts))
  return dots


def _step_card(inv, t0, session_dir, href, w, h):
  shot = _shot(session_dir, inv['index'])
  figure = ('<figure class="shot"><a href="%s%s" target="_blank"><img loading="lazy" alt="Screen at '
            'step %d" src="%s%s"></a>%s</figure>'
            % (href, shot, inv['index'] + 1, href, shot, _marks(inv, w, h))) if shot else ''
  extra = ''
  if inv.get('gridPayload'):
    extra = ('<details><summary>What the agent read</summary><pre>%s</pre></details>'
             % esc(_clip(inv['gridPayload'])))
  return (
      '<article class="step%s"><header><span class="n">%02d</span><b>%s</b>'
      '<span class="t">+%.1fs · %d ms</span>%s</header>'
      '<div class="body%s">%s<div class="io"><h4>Input</h4><pre>%s</pre>'
      '<h4>Result</h4><pre>%s</pre>%s</div></div></article>'
      % ('' if inv.get('success', True) else ' bad', inv['index'] + 1, esc(inv.get('toolName', '?')),
         (inv.get('timestampMillis', t0) - t0) / 1000.0, inv.get('durationMs') or 0,
         '' if inv.get('success', True) else '<span class="pill fail">error</span>',
         '' if shot else ' noshot', figure, esc(_pretty_args(inv.get('argsJson'))),
         esc(_clip(inv.get('outputSummary')))
         + (' …' if inv.get('outputFullLength') else ''), extra))


def _llm_card(call, t0):
  return (
      '<article class="step llm"><header><span class="n">AI</span><b>%s</b>'
      '<span class="t">+%.1fs · %d ms · %s tokens</span></header>'
      '<div class="body noshot"><div class="io">%s<h4>Response</h4><pre>%s</pre></div></div></article>'
      % (esc(call.get('model', '?')), (call.get('timestampMillis', t0) - t0) / 1000.0,
         call.get('durationMs') or 0, call.get('totalTokens') or '?',
         ('<h4>Reasoning</h4><pre>%s</pre>' % esc(_clip(call['reasoning'])))
         if call.get('reasoning') else '', esc(_clip(call.get('response')))))


def _task_part(log, mark):
  """`log` without the entries an earlier task left in it (`mark`: device.session_marks)."""
  if not mark:
    return log
  return dict(log, invocations=(log.get('invocations') or [])[mark.get('invocations', 0):],
              llmCalls=(log.get('llmCalls') or [])[mark.get('llmCalls', 0):])


def steps_html(session_root, href='', marks=None):
  """AURA's own step log for one task, as HTML cards: tool, input, result, screenshot.

  Contract: `session_root` holds `<id>/metadata.json` per session (as the phone writes
  them); `href` is the URL prefix of that folder; `marks` (meta.json's session_marks)
  says which leading entries belong to the task before. Returns '' when nothing to show.
  """
  if not os.path.isdir(session_root):
    return ''
  out = []
  for sid in sorted(os.listdir(session_root)):
    sdir = os.path.join(session_root, sid)
    log = _read_json(os.path.join(sdir, 'metadata.json'))
    if not log:
      continue
    log = _task_part(log, (marks or {}).get(sid))
    t0 = log.get('startedAtMillis') or 0
    w, h = log.get('screenWidth'), log.get('screenHeight')
    items = [(i.get('timestampMillis', 0), 0, i) for i in log.get('invocations') or []]
    items += [(c.get('timestampMillis', 0), 1, c) for c in log.get('llmCalls') or []]
    cards = [_llm_card(x, t0) if kind else _step_card(x, t0, sdir, '%s%s/' % (href, sid), w, h)
             for _, kind, x in sorted(items, key=lambda it: (it[0], it[1]))]
    if not cards:
      continue
    outcome = log.get('outcome') or {}
    label = log.get('agentLabel') or ('AURA agent' if log.get('source') == 'agent' else 'MCP client')
    out.append('<section class="session"><p class="sess">%s · %d tool calls%s%s</p>%s</section>'
               % (esc(label), len(log.get('invocations') or []),
                  ' · ended: %s' % esc(log['endReason']) if log.get('endReason') else '',
                  ' · AURA\'s own check: %s' % esc(outcome['verdict']) if outcome.get('verdict') else '',
                  ''.join(cards)))
  return ''.join(out)


# --- pages -------------------------------------------------------------------

def _log_block(label, path, href):
  if not os.path.isfile(path):
    return ''
  with open(path, encoding='utf-8', errors='replace') as f:
    lines = f.read().splitlines()
  shown = lines[-LOG_TAIL_LINES:]
  body = '\n'.join('<span class="err">%s</span>' % esc(l)
                   if (' E/' in l[:40] or ' E ' in l[:40] or '  err ' in l[:20]) else esc(l)
                   for l in shown)
  note = ('last %d of %d lines' % (len(shown), len(lines)) if len(lines) > len(shown)
          else '%d lines' % len(lines))
  return ('<details class="log"><summary>%s <span class="dim">— %s · <a href="%s">raw</a></span>'
          '</summary><pre>%s</pre></details>'
          % (esc(label), note, esc(href + os.path.basename(path)), body or '(empty)'))


def _copy_task(src, dst, compress, videos, marks):
  """Copies a task's public files: meta, logs, videos and this task's part of AURA's step log."""
  os.makedirs(dst, exist_ok=True)
  for name in os.listdir(src):
    path = os.path.join(src, name)
    if name.endswith(('.log', '.json')) and os.path.isfile(path):
      with open(path, encoding='utf-8', errors='replace') as f:
        text = f.read()
      with open(os.path.join(dst, name), 'w', encoding='utf-8') as f:
        f.write(_scrub(text))
    elif name.endswith('.mp4') and videos:
      shutil.copy2(path, os.path.join(dst, name))
  session = os.path.join(src, 'session')
  if not os.path.isdir(session):
    return
  for sid in os.listdir(session):
    log = _read_json(os.path.join(session, sid, 'metadata.json'))
    if not log:
      continue
    log = _task_part(log, (marks or {}).get(sid))
    keep = {inv['index'] for inv in log['invocations'] or []}
    if not keep and not log.get('llmCalls'):
      continue
    sdst = os.path.join(dst, 'session', sid)
    os.makedirs(os.path.join(sdst, 'screenshots'), exist_ok=True)
    with open(os.path.join(sdst, 'metadata.json'), 'w', encoding='utf-8') as f:
      json.dump(log, f, ensure_ascii=False)
    for index in keep:
      shot = _shot(os.path.join(session, sid), index)
      if shot:
        _copy_shot(os.path.join(session, sid, shot), os.path.join(sdst, 'screenshots'), compress)


def _copy_shot(path, dst_dir, compress):
  if not compress:
    shutil.copy2(path, dst_dir)
    return
  from PIL import Image
  base = os.path.splitext(os.path.basename(path))[0]
  with Image.open(path) as im:
    im = im.convert('RGB')
    im.thumbnail((SHOT_WIDTH, SHOT_WIDTH * 3))
    im.save(os.path.join(dst_dir, base + '.jpg'), quality=72, optimize=True)


def _video_html(meta, local_prefix, video_base):
  videos = meta.get('videos') or []
  if not videos:
    return '<p class="dim">No recording for this run.</p>'
  srcs = ([video_base + video_asset(meta['task'], v, i) for i, v in enumerate(videos)]
          if video_base else [local_prefix + v for v in videos])
  return ''.join('<video controls preload="metadata" playsinline src="%s"></video>' % esc(s)
                 for s in srcs)


def video_asset(task, video, i):
  """The release-asset name for a task's i-th video: unique across one run.

  Change together: publish.py uploads under these names.
  """
  return '%s%s.mp4' % (task, '' if i == 0 else '-%d' % (i + 1))


def _task_page(meta, src, dst, run, video_base):
  task = meta.get('task', '?')
  banner = ''
  if meta.get('blind'):
    banner += ('<div class="banner"><b>AURA had no vision during this run.</b> Its screenshot '
               'call failed, so it acted on the accessibility tree alone.</div>')
  if meta.get('error'):
    banner += '<div class="banner"><b>Harness error.</b> %s</div>' % esc(str(meta['error'])[:500])
  agent = meta.get('agent') or {}
  cost = _cost(meta)
  facts = [('Verdict', _verdict(meta)), ('Time', esc(_fmt_secs(meta.get('run_time')))),
           ('Phone actions', esc(_steps(meta))),
           ('Model', esc(str(meta.get('model') or 'AURA on-device'))),
           ('AURA', esc(meta.get('aura_version') or '–')),
           ('Clock', esc(str(meta.get('device_year') or '–'))), ('Seed', esc(str(meta.get('seed', '–'))))]
  if cost is not None:
    facts.append(('API cost', '$%.3f' % cost))
  if meta.get('validator_success') is not None and meta.get('success') is False and meta['validator_success']:
    banner += ('<div class="banner"><b>The phone ended up right, but the agent did not finish.</b> '
               'AndroidWorld only counts a task the agent completed.</div>')
  answer = agent.get('answer')
  steps = steps_html(os.path.join(dst, 'session'), '%s/session/' % task)  # the trimmed, published copy
  body = (
      '<a class="back" href="../index.html">← %s</a>'
      '<p class="eyebrow">Task</p><h1 class="task">%s</h1>%s'
      '<p class="goalbox">%s</p>%s'
      '<dl class="facts">%s</dl>'
      '<div class="cols"><section><h2>Recording</h2>%s</section>'
      '<section><h2>Step by step</h2><p class="dim">What AURA logged on the phone for every '
      'tool call: the input, the result and the screen.</p>%s</section></div>'
      '<h2>Logs</h2>%s'
      % (esc(run['title']), esc(task), banner, esc(meta.get('goal') or '(goal not recorded)'),
         ('<p class="answer"><b>Final message:</b> %s</p>' % esc(answer)) if answer else '',
         ''.join('<div><dt>%s</dt><dd>%s</dd></div>' % kv for kv in facts),
         _video_html(meta, '%s/' % task, video_base),
         steps or '<p class="dim">No step log was copied for this run.</p>',
         ''.join(_log_block(label, os.path.join(src, f), '%s/' % task) for label, f in LOGS)))
  return _shell('%s · AURA on AndroidWorld' % task, body, 1)


def _index(metas, total, run):
  done = [m for m in metas if m.get('success') in (True, False)]
  passed = [m for m in done if m['success']]
  rate = 100.0 * len(passed) / len(done) if done else 0.0
  cost = sum(_cost(m) or 0 for m in metas)
  rows = ''.join(
      '<a class="row" href="t/%s.html"><span class="n">%03d</span>'
      '<span class="what"><span class="name">%s</span><span class="goal">%s</span></span>'
      '<span class="num">%s<small>%s</small></span>%s</a>'
      % (esc(m['task']), i, esc(m['task']), esc(m.get('goal') or ''),
         esc(_fmt_secs(m.get('run_time'))), esc(_steps(m)), _verdict(m))
      for i, m in enumerate(sorted(metas, key=lambda x: x.get('task', '')), 1))
  stats = [('Passed', len(passed)), ('Failed', len(done) - len(passed)),
           ('Not run', total - len(done))]
  if cost:
    stats.append(('API cost', '$%.2f' % cost))
  body = (
      '<p class="eyebrow">%s</p><h1>%s</h1><p class="lede">%s</p>'
      '<div class="slab"><div class="big">%s<span class="unit">%%</span></div><dl>%s</dl></div>'
      '<dl class="facts">%s</dl>'
      '<details class="scoring" open><summary>How this run is scored</summary><ul>%s</ul></details>'
      '<div class="rows">%s</div>'
      '<footer>Every verdict is AndroidWorld\'s own check of the phone after the run, unedited. '
      'Videos, AURA\'s step logs and the raw logs are on each task page. Method and harness: '
      '<code>benchmark/</code> in the AURA repository.</footer>'
      % (esc(run['eyebrow']), esc(run['title']), esc(run['sub']),
         ('%.1f' % rate) if done else '–',
         ''.join('<div><dt>%s</dt><dd>%s</dd></div>' % (k, v) for k, v in stats),
         ''.join('<div><dt>%s</dt><dd>%s</dd></div>' % (esc(k), esc(str(v))) for k, v in run['facts']),
         ''.join('<li>%s</li>' % esc(d) for d in run['rules']),
         rows or '<p class="dim">Nothing run yet.</p>'))
  return _shell(run['title'], body, 0), {
      'rate': round(rate, 1) if done else None, 'passed': len(passed), 'scored': len(done),
      'total': total, 'cost_usd': round(cost, 2)}


def _scored_tasks():
  """Task names in the generated `androidworld.json`: the list the on-device cockpit runs.

  Change together: gen_androidworld_tasks.py writes this file.
  """
  with open(TASK_LIST, encoding='utf-8') as f:
    return {t['aw_task'] for t in json.load(f)['tasks']}


def build(results_dir, site_dir, tasks=None, run=None, video_base=None, compress=False):
  """Regenerates one run's pages. Safe to call after every task.

  Contract: only results for `tasks` (default: the on-device scored list) appear, so a
  folder left by an excluded task cannot move the rate. `run` describes the run (title,
  sub, eyebrow, facts, rules: see `run_info`). With `video_base` the pages play videos
  from that URL prefix instead of copying them; `compress` shrinks screenshots for
  publishing. Writes `run.json` (the summary publish.py lists on the index).
  """
  tasks = set(tasks) if tasks is not None else _scored_tasks()
  run = run or run_info(ABOUT_ONDEVICE, [], [])
  metas = [m for m in _read_results(results_dir) if m.get('task') in tasks]
  shutil.rmtree(os.path.join(site_dir, 't'), ignore_errors=True)
  os.makedirs(os.path.join(site_dir, 't'))
  with open(os.path.join(site_dir, 'style.css'), 'w', encoding='utf-8') as f:
    f.write(theme.CSS)
  for meta in metas:
    src, dst = os.path.join(results_dir, meta['task']), os.path.join(site_dir, 't', meta['task'])
    _copy_task(src, dst, compress, video_base is None, meta.get('session_marks'))
    with open(os.path.join(site_dir, 't', '%s.html' % meta['task']), 'w', encoding='utf-8') as f:
      f.write(_task_page(meta, src, dst, run, video_base))
  page, summary = _index(metas, len(tasks), run)
  with open(os.path.join(site_dir, 'index.html'), 'w', encoding='utf-8') as f:
    f.write(page)
  with open(os.path.join(site_dir, 'run.json'), 'w', encoding='utf-8') as f:
    json.dump({**{k: run[k] for k in ('title', 'sub', 'eyebrow')}, **summary,
               'facts': run['facts'], 'built': datetime.date.today().isoformat()}, f, indent=2)
  return os.path.abspath(site_dir)


def run_info(about, facts, rules, eyebrow='AndroidWorld · Google'):
  """The words a run's pages carry: what was run and how it was scored."""
  return {'title': about['title'], 'sub': about['sub'], 'eyebrow': eyebrow,
          'facts': list(facts), 'rules': list(rules) or [about['method']]}


def _demo():
  """Self-check: a fabricated result set must render without a device."""
  import tempfile
  with tempfile.TemporaryDirectory() as tmp:
    results = os.path.join(tmp, 'results')

    def task(name, meta, **files):
      os.makedirs(os.path.join(results, name), exist_ok=True)
      with open(os.path.join(results, name, 'meta.json'), 'w') as f:
        json.dump({'task': name, 'videos': [], 'run_time': 42.0, 'seed': 30, **meta}, f)
      for fname, text in files.items():
        with open(os.path.join(results, name, fname.replace('_', '.')), 'w') as f:
          f.write(text)

    task('FakePass', {'success': True, 'goal': 'Do a thing that is long ' * 8},
         live_log='Goal: x\n   1.0s  err   E boom\n',
         agent_stdout_log='cwd %s\\work' % os.path.expanduser('~'))
    task('FakeFail', {'success': False, 'goal': 'Do <b>x</b>', 'blind': True,
                      'agent': {'out_of_steps': True, 'steps_used': 20, 'step_budget': 20,
                                'usage': {'cost_usd': 0.25}}})
    task('Excluded', {'success': True})
    from PIL import Image
    sess = os.path.join(results, 'FakePass', 'session', '100-a')
    os.makedirs(os.path.join(sess, 'screenshots'))
    for i in (0, 1):
      Image.new('RGB', (1080, 2400), 'white').save(os.path.join(sess, 'screenshots', '%d.png' % i))
    # Index 0 belongs to the task before (session_marks below); index 1 is this task's.
    with open(os.path.join(sess, 'metadata.json'), 'w') as f:
      json.dump({'startedAtMillis': 1000, 'screenWidth': 1080, 'screenHeight': 2400,
                 'invocations': [
                     {'index': 0, 'timestampMillis': 2000, 'toolName': 'earlier_task_tool',
                      'argsJson': '{}', 'hasScreenshot': True},
                     {'index': 1, 'timestampMillis': 3500, 'toolName': 'tap',
                      'argsJson': '{"som_id": 13}', 'outputSummary': '<ok>',
                      'hasScreenshot': True, 'gestureType': 'tap', 'tapX': 540, 'tapY': 1200}]}, f)
    meta_path = os.path.join(results, 'FakePass', 'meta.json')
    meta = json.load(open(meta_path))
    json.dump(dict(meta, session_marks={'100-a': {'invocations': 1, 'llmCalls': 0}}),
              open(meta_path, 'w'))

    site = build(results, os.path.join(tmp, 'site'), tasks=['FakePass', 'FakeFail', 'Never'])
    index = open(os.path.join(site, 'index.html'), encoding='utf-8').read()
    assert '50.0' in index, 'one pass of two scored runs reads 50.0%; an excluded task must not count'
    assert 'Excluded' not in index, 'a task outside the list must not be published'
    assert 'out of steps' in index, 'a budget fail must say so'
    assert ('Do a thing that is long ' * 8).strip() in index, 'goals are shown in full'
    assert '&lt;b&gt;' in index, 'a goal containing markup is escaped'
    summary = json.load(open(os.path.join(site, 'run.json')))
    assert summary['total'] == 3 and summary['cost_usd'] == 0.25, summary
    page = open(os.path.join(site, 't', 'FakePass.html'), encoding='utf-8').read()
    assert 'class="err"' in page, 'error lines are marked up in the log'
    assert 'FakePass/session/100-a/screenshots/1.png' in page, 'the step shows its screenshot'
    assert 'earlier_task_tool' not in page, "the task before's steps are not this task's"
    assert not os.path.exists(os.path.join(site, 't', 'FakePass', 'session', '100-a',
                                           'screenshots', '0.png')), 'nor are its screenshots'
    assert 'left:50.00%;top:50.00%' in page, 'the tap is marked where it landed'
    assert '+2.5s' in page and '&lt;ok&gt;' in page, 'step time and escaped result'
    raw = open(os.path.join(site, 't', 'FakePass', 'agent.stdout.log'), encoding='utf-8').read()
    assert os.path.expanduser('~') not in raw, 'the local home folder is scrubbed'
    nov = build(results, os.path.join(tmp, 'site2'), tasks=['FakePass'],
                video_base='https://x/dl/', compress=True)
    assert not os.path.exists(os.path.join(nov, 't', 'FakePass', 'run.mp4'))
    pub = open(os.path.join(nov, 't', 'FakePass.html'), encoding='utf-8').read()
    assert 'screenshots/1.jpg' in pub, 'published pages point at the shrunk JPEG they ship'
    with Image.open(os.path.join(nov, 't', 'FakePass', 'session', '100-a', 'screenshots',
                                 '1.jpg')) as im:
      assert im.width == SHOT_WIDTH, im.size
    assert video_asset('T', 'run.mp4', 1) == 'T-2.mp4'
    print('report self-check passed')


if __name__ == '__main__':
  if '--demo' in sys.argv:
    _demo()
  else:
    print(__doc__)
