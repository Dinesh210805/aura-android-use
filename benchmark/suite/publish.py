"""Publishes one benchmark run to the website: videos to a GitHub Release, pages by pull request.

The suite's Publish button calls `publish()`; nothing here is needed to run a benchmark.

- Reads: the run's results folder (see report.build).
- Writes: a GitHub Release `bench-<run>-<stamp>` (videos, never marked latest), and a branch
  `bench/publish-<run>-<stamp>` adding `aura-android-use-website/benchmarks/<run>/` and
  `benchmarks/index.html`, with a pull request to main. Vercel deploys main once merged.
- Fails: raises PublishError with the failing command's output. A release created before
  the failure is left in place (the error names it) so a retry can be checked against it.
- Why a worktree: pages are committed from a temporary checkout of origin/main, so the
  person's own branch and uncommitted work are never touched.
- Change together: report.video_asset (asset names), the website's `releases/latest` links
  (why releases here pass --latest=false).
"""

import datetime
import html
import json
import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import report  # noqa: E402
from aura_androidworld import ROOT, RUNTIME  # noqa: E402

SITE_DIR = 'aura-android-use-website'
BENCH_DIR = 'benchmarks'


class PublishError(Exception):
  pass


def _run(args, cwd=ROOT, timeout=900):
  p = subprocess.run(args, cwd=cwd, capture_output=True, text=True, encoding='utf-8',
                     errors='replace', timeout=timeout)
  if p.returncode != 0:
    raise PublishError('%s failed:\n%s' % (' '.join(args[:3]), (p.stderr or p.stdout)[-1500:]))
  return p.stdout.strip()


def _videos(results_dir, tasks):
  """(local path, release asset name) for every video of every result in `tasks`."""
  out = []
  for meta in report._read_results(results_dir):
    if meta.get('task') not in tasks:
      continue
    for i, video in enumerate(meta.get('videos') or []):
      path = os.path.join(results_dir, meta['task'], video)
      if os.path.isfile(path):
        out.append((path, report.video_asset(meta['task'], video, i)))
  return out


def _write_index(bench_root):
  """`benchmarks/index.html`: every published run, newest first, from each run.json."""
  runs = []
  for name in sorted(os.listdir(bench_root)):
    info = report._read_json(os.path.join(bench_root, name, 'run.json'))
    if info:
      runs.append((info.get('built', ''), name, info))
  cards = ''.join(
      '<a class="row" href="%s/index.html"><span class="n">%s</span><span class="what">'
      '<span class="name">%s</span><span class="goal">%s</span></span>'
      '<span class="num">%s<small>%d of %d tasks</small></span>'
      '<span class="pill %s">%s</span></a>'
      % (html.escape(name), html.escape(built[2:] if built else ''), html.escape(info['title']),
         html.escape(' · '.join('%s %s' % (k, v) for k, v in info.get('facts', []))),
         html.escape(built), info.get('scored', 0), info.get('total', 0),
         'pass' if info.get('rate') is not None else 'none',
         ('%.1f%%' % info['rate']) if info.get('rate') is not None else 'no runs')
      for built, name, info in sorted(runs, reverse=True))
  body = ('<p class="eyebrow">Benchmarks</p><h1>AURA on AndroidWorld</h1>'
          '<p class="lede">Google\'s AndroidWorld benchmark: 116 tasks across 20 real Android '
          'apps, each scored by checking the phone afterwards. Every task has its recording, '
          'AURA\'s step-by-step log and the raw logs.</p><div class="rows">%s</div>'
          '<footer>Harness and method: <code>benchmark/</code> in the AURA repository.</footer>'
          % (cards or '<p class="dim">Nothing published yet.</p>'))
  with open(os.path.join(bench_root, 'style.css'), 'w', encoding='utf-8') as f:
    f.write(report.theme.CSS)
  with open(os.path.join(bench_root, 'index.html'), 'w', encoding='utf-8') as f:
    f.write(report._shell('Benchmarks · AURA', body, 0))


def publish(results_dir, tasks, run, run_id, on_step=lambda text: None):
  """Publishes the run in `results_dir` as `benchmarks/<run_id>/`. Returns the PR URL.

  Contract: `tasks` and `run` are what report.build takes. Blocks for minutes (uploads);
  call it off the request thread. `on_step` gets one line per stage for the page.
  """
  stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M')
  repo = _run(['gh', 'repo', 'view', '--json', 'nameWithOwner', '-q', '.nameWithOwner'])
  tag, branch = 'bench-%s-%s' % (run_id, stamp), 'bench/publish-%s-%s' % (run_id, stamp)
  videos = _videos(results_dir, set(tasks))

  on_step('Uploading %d videos to release %s' % (len(videos), tag))
  with tempfile.TemporaryDirectory() as tmp:
    named = []
    for path, asset in videos:
      shutil.copy2(path, os.path.join(tmp, asset))
      named.append(os.path.join(tmp, asset))
    _run(['gh', 'release', 'create', tag, '--target', 'main', '--latest=false',
          '--title', '%s (%s)' % (run['title'], stamp),
          '--notes', 'Screen recordings for the benchmark run published at '
                     '/benchmarks/%s/. Not an app release.' % run_id], timeout=120)
    for i in range(0, len(named), 20):  # several per call, bounded so one call cannot hang long
      _run(['gh', 'release', 'upload', tag, *named[i:i + 20]], timeout=1800)
  video_base = 'https://github.com/%s/releases/download/%s/' % (repo, tag)

  on_step('Building pages')
  tree = os.path.join(RUNTIME, 'publish-worktree')
  if os.path.exists(tree):
    _run(['git', 'worktree', 'remove', '--force', tree])
  _run(['git', 'fetch', 'origin', 'main'])
  _run(['git', 'worktree', 'add', '-b', branch, tree, 'origin/main'])
  try:
    bench_root = os.path.join(tree, SITE_DIR, BENCH_DIR)
    out = os.path.join(bench_root, run_id)
    shutil.rmtree(out, ignore_errors=True)
    report.build(results_dir, out, tasks, run, video_base=video_base, compress=True)
    _write_index(bench_root)

    on_step('Committing and opening a pull request')
    _run(['git', 'add', os.path.join(SITE_DIR, BENCH_DIR)], cwd=tree)
    summary = json.load(open(os.path.join(out, 'run.json'), encoding='utf-8'))
    title = 'bench: publish %s (%s%% of %d scored)' % (
        run_id, summary['rate'] if summary['rate'] is not None else '–', summary['scored'])
    _run(['git', 'commit', '-m', title + '\n\nPages for /benchmarks/%s/. Videos: release %s.'
          % (run_id, tag)], cwd=tree)
    _run(['git', 'push', '-u', 'origin', branch], cwd=tree)
    return _run(['gh', 'pr', 'create', '--base', 'main', '--head', branch, '--title', title,
                 '--body', 'Publishes the benchmark run **%s** to `/benchmarks/%s/`.\n\n'
                 '- Pass rate: %s%% (%d passed of %d scored, %d tasks in the suite)\n'
                 '- Videos: release `%s` (not marked latest)\n\nMerge to deploy.\n\n'
                 '🤖 Generated with [Claude Code](https://claude.com/claude-code)'
                 % (run['title'], run_id, summary['rate'], summary['passed'],
                    summary['scored'], summary['total'], tag)], cwd=tree)
  finally:
    subprocess.run(['git', 'worktree', 'remove', '--force', tree], cwd=ROOT, capture_output=True)


def _selfcheck():
  """The index lists runs newest first from their run.json; no network, no git."""
  with tempfile.TemporaryDirectory() as tmp:
    for name, built, rate in (('old', '2026-01-01', 10.0), ('new', '2026-10-13', 55.5)):
      os.makedirs(os.path.join(tmp, name))
      json.dump({'title': 'Run %s' % name, 'built': built, 'rate': rate, 'scored': 3,
                 'total': 116, 'facts': [['Model', 'm']]},
                open(os.path.join(tmp, name, 'run.json'), 'w'))
    _write_index(tmp)
    page = open(os.path.join(tmp, 'index.html'), encoding='utf-8').read()
    assert page.index('Run new') < page.index('Run old'), 'newest run first'
    assert '55.5%' in page and 'new/index.html' in page
  print('publish self-check passed')


if __name__ == '__main__':
  _selfcheck()
