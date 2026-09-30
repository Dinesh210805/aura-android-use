"""Builds the public benchmark site out of `bench-results/`.

A pure function of that directory: whatever has been run is what appears. Re-run
one task, rebuild, and only that task's page changes. Nothing here talks to a
device, so the site can be regenerated on any machine that has the folder.

Output is plain static files - HTML, mp4, txt - so `bench-site/` can be dropped
into any host (the same public-repo route the marketing site already uses).

    python scripts/bench/report.py            # bench-results -> bench-site
"""

import html
import json
import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import theme  # noqa: E402

# Longest log tail worth inlining. A full agent log runs to tens of thousands of
# lines; the end is where the verdict is, and the raw file is linked in full.
LOG_TAIL_LINES = 400


def _read_results(results_dir):
  out = []
  if not os.path.isdir(results_dir):
    return out
  for name in sorted(os.listdir(results_dir)):
    meta_path = os.path.join(results_dir, name, 'meta.json')
    if not os.path.isfile(meta_path):
      continue
    try:
      with open(meta_path, encoding='utf-8') as f:
        out.append(json.load(f))
    except (OSError, ValueError):
      continue
  return out


def _fmt_secs(value):
  try:
    seconds = int(round(float(value)))
  except (TypeError, ValueError):
    return '-'
  return '%dm %02ds' % (seconds // 60, seconds % 60) if seconds >= 60 \
      else '%ds' % seconds


def _shell(title, body, css_depth=0):
  return (
      '<!doctype html><html lang="en"><head><meta charset="utf-8">'
      '<meta name="viewport" content="width=device-width,initial-scale=1">'
      '<title>%s</title><link rel="stylesheet" href="%sstyle.css">'
      '</head><body><div class="wrap">%s</div></body></html>'
      % (html.escape(title), '../' * css_depth, body)
  )


def _log_block(label, path, href_prefix='', tail_lines=LOG_TAIL_LINES):
  if not os.path.isfile(path):
    return ''
  with open(path, encoding='utf-8', errors='replace') as f:
    lines = f.read().splitlines()
  clipped = len(lines) > tail_lines
  shown = lines[-tail_lines:]
  rendered = []
  for line in shown:
    escaped = html.escape(line)
    # AURA prefixes errors with the logcat level; colour just those so a
    # failure is findable in a wall of text without reading every line.
    if ' E ' in line[:40] or 'threw' in line or 'Error' in line:
      escaped = '<span class="err">%s</span>' % escaped
    rendered.append(escaped)
  head = ('<summary>%s <span style="color:var(--text-dim);font-weight:400">'
          '&mdash; last %d of %d lines &middot; '
          '<a href="%s">raw</a></span></summary>'
          % (html.escape(label), len(shown), len(lines),
             html.escape(href_prefix + os.path.basename(path)))
          ) if clipped else (
              '<summary>%s <span style="color:var(--text-dim);font-weight:400">'
              '&mdash; %d lines &middot; <a href="%s">raw</a></span></summary>'
              % (html.escape(label), len(lines),
                 html.escape(href_prefix + os.path.basename(path))))
  return '<details class="log">%s<pre>%s</pre></details>' % (
      head, '\n'.join(rendered) or '(empty)')


def _task_page(meta, src_dir, dst_dir):
  os.makedirs(dst_dir, exist_ok=True)
  for fname in ('agent.log', 'harness.log', *meta.get('videos', [])):
    src = os.path.join(src_dir, fname)
    if os.path.isfile(src):
      shutil.copy2(src, os.path.join(dst_dir, fname))

  assets = '%s/' % meta.get('task', '')
  videos = ['%s%s' % (assets, v) for v in (meta.get('videos') or [])]
  if not videos:
    video_html = ('<p class="vid-note">No recording for this run &mdash; '
                  'screenrecord produced nothing.</p>')
  elif len(videos) == 1:
    video_html = '<video controls preload="metadata" src="%s"></video>' % videos[0]
  else:
    # screenrecord caps a single file at 180s, so a long run arrives in pieces
    # and ffmpeg was not available to join them.
    video_html = ''.join(
        '<video controls preload="metadata" src="%s"></video>'
        '<p class="vid-note">Part %d of %d</p>' % (v, i + 1, len(videos))
        for i, v in enumerate(videos))

  ok = meta.get('success')
  verdict = ('<span class="pill pass">pass</span>' if ok is True
             else '<span class="pill fail">fail</span>' if ok is False
             else '<span class="pill none">not scored</span>')
  blind = ('<span class="pill blind">ran blind</span>'
           if meta.get('blind') else '')

  banner = ''
  if meta.get('blind'):
    banner = ('<div class="banner"><b>AURA had no vision during this run.</b> '
              'Its screenshot call failed, so it acted on the accessibility '
              'tree alone. Read the verdict with that in mind.</div>')
  if meta.get('error'):
    banner += ('<div class="banner"><b>Harness error.</b> %s</div>'
               % html.escape(str(meta['error'])[:500]))

  body = (
      '<a class="back" href="../index.html">&larr; all tasks</a>'
      '<div class="mast"><div><h1>%s</h1><div class="sub">%s %s</div></div>'
      '</div>%s'
      '<p class="goalbox">%s</p>'
      '<div class="meta">'
      '<div><dt>Verdict</dt><dd>%s</dd></div>'
      '<div><dt>Wall clock</dt><dd>%s</dd></div>'
      '<div><dt>Seed</dt><dd>%s</dd></div>'
      '<div><dt>Agent steps</dt><dd>%s</dd></div>'
      '</div>%s%s%s'
      % (html.escape(meta.get('task', '?')), verdict, blind, banner,
         html.escape(meta.get('goal') or '(goal not recorded)'),
         'pass' if ok else 'fail' if ok is False else 'not scored',
         _fmt_secs(meta.get('run_time')),
         html.escape(str(meta.get('seed', '-'))),
         html.escape(str(meta.get('episode_length', '-'))),
         video_html,
         _log_block("AURA's log", os.path.join(src_dir, 'agent.log'), assets),
         _log_block('Harness output', os.path.join(src_dir, 'harness.log'),
                    assets, 120))
  )
  return _shell('%s - AURA on AndroidWorld' % meta.get('task', '?'), body, 1)


def _index(metas, total_tasks):
  done = [m for m in metas if m.get('success') in (True, False)]
  passed = [m for m in done if m.get('success')]
  blind = [m for m in done if m.get('blind')]
  rate = (100.0 * len(passed) / len(done)) if done else 0.0

  banner = ''
  if blind:
    banner = ('<div class="banner"><b>%d of %d completed tasks ran without '
              'vision.</b> AURA\'s screenshot call failed on those, so it '
              'acted on the accessibility tree alone. They measure a blind '
              'agent, not the shipping one.</div>' % (len(blind), len(done)))

  rows = []
  for i, m in enumerate(sorted(metas, key=lambda x: x.get('task', '')), 1):
    ok = m.get('success')
    verdict = ('<span class="pill pass">pass</span>' if ok is True
               else '<span class="pill fail">fail</span>' if ok is False
               else '<span class="pill none">not scored</span>')
    tag = '<span class="pill blind">blind</span> ' if m.get('blind') else ''
    rows.append(
        '<div class="row"><span class="n">%03d</span>'
        '<span><span class="name">%s</span><span class="goal">%s</span></span>'
        '<span>%s%s</span>'
        '<a class="open" href="t/%s.html">watch &rarr;</a></div>'
        % (i, html.escape(m.get('task', '?')),
           html.escape(m.get('goal') or ''), tag, verdict,
           html.escape(m.get('task', '?'))))

  body = (
      '<div class="mast"><div><h1>AURA on AndroidWorld</h1>'
      '<div class="sub">An on-device Android agent, scored by Google\'s '
      'AndroidWorld benchmark. Every task below was screen-recorded '
      'start to finish.</div></div></div>'
      '<div class="slab"><div><div class="big">%.1f<span class="unit">%%</span>'
      '</div></div><dl>'
      '<div><dt>Tasks run</dt><dd>%d of %d</dd></div>'
      '<div><dt>Passed</dt><dd>%d</dd></div>'
      '<div><dt>Failed</dt><dd>%d</dd></div>'
      '<div><dt>Ran blind</dt><dd>%d</dd></div>'
      '</dl></div>%s%s'
      '<div class="rows">%s</div>'
      '<footer>Scored by AndroidWorld\'s own task validators, which inspect '
      'device state after the run &mdash; not by watching what the agent did. '
      'Every verdict here is the benchmark\'s, unedited. Videos and raw logs '
      'are linked from each task page.</footer>'
      % (rate, len(done), total_tasks, len(passed),
         len(done) - len(passed), len(blind), banner,
         '' if done else '<p class="vid-note">No tasks scored yet.</p>',
         ''.join(rows) or '<div class="row"><span></span>'
                          '<span class="goal">Nothing run yet.</span>'
                          '<span></span><span></span></div>')
  )
  return _shell('AURA on AndroidWorld', body, 0)


def build(results_dir, site_dir, total_tasks=116):
  """Regenerates the whole site. Safe to call after every single task."""
  metas = _read_results(results_dir)
  shutil.rmtree(os.path.join(site_dir, 't'), ignore_errors=True)
  os.makedirs(os.path.join(site_dir, 't'), exist_ok=True)

  with open(os.path.join(site_dir, 'style.css'), 'w', encoding='utf-8') as f:
    f.write(theme.CSS)
  for meta in metas:
    task = meta.get('task')
    if not task:
      continue
    page = _task_page(meta, os.path.join(results_dir, task),
                      os.path.join(site_dir, 't', task))
    with open(os.path.join(site_dir, 't', '%s.html' % task), 'w',
              encoding='utf-8') as f:
      f.write(page)
  with open(os.path.join(site_dir, 'index.html'), 'w', encoding='utf-8') as f:
    f.write(_index(metas, total_tasks))
  return os.path.abspath(site_dir)


def _demo():
  """Self-check: a fabricated result set must render without a device."""
  import tempfile
  with tempfile.TemporaryDirectory() as tmp:
    results = os.path.join(tmp, 'results')
    os.makedirs(os.path.join(results, 'FakePass'))
    os.makedirs(os.path.join(results, 'FakeBlindFail'))
    with open(os.path.join(results, 'FakePass', 'meta.json'), 'w') as f:
      json.dump({'task': 'FakePass', 'success': True, 'goal': 'Do a thing',
                 'run_time': 42.0, 'seed': 42, 'videos': [], 'blind': False,
                 'episode_length': 1}, f)
    with open(os.path.join(results, 'FakeBlindFail', 'meta.json'), 'w') as f:
      json.dump({'task': 'FakeBlindFail', 'success': False, 'goal': 'Do <b>x</b>',
                 'run_time': 200.0, 'seed': 42, 'videos': [], 'blind': True,
                 'episode_length': 1}, f)
    with open(os.path.join(results, 'FakePass', 'agent.log'), 'w') as f:
      f.write('line one\nE Agent threw something\n')

    site = build(results, os.path.join(tmp, 'site'), total_tasks=116)
    index = open(os.path.join(site, 'index.html'), encoding='utf-8').read()
    assert '50.0' in index, 'one pass of two scored runs must read 50.0%'
    assert '1 of 2 completed tasks ran without vision' in index, \
        'the blind-run banner must appear whenever any run had no vision'
    assert '&lt;b&gt;' in index, 'a goal containing markup must be escaped'
    page = open(os.path.join(site, 't', 'FakePass.html'), encoding='utf-8').read()
    assert 'class="err"' in page, 'error lines must be marked up in the log'
    assert 'No recording for this run' in page, \
        'a run with no video must say so rather than render an empty player'
    print('report self-check passed')


if __name__ == '__main__':
  if '--demo' in sys.argv:
    _demo()
  else:
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
    print(build(os.path.join(root, 'bench-results'),
                os.path.join(root, 'bench-site')))
