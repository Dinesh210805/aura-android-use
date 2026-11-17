"""How efficiently AURA's on-device agent worked each AndroidWorld task: efficiency.md.

Reads what the suite copied for every task, `<task>/meta.json` (the verdict and time) and
`<task>/agentcore/` (the lines of AURA's run log written during that task: `steps-*.jsonl`
from RunJournal.kt, `spans-*.jsonl` from Telemetry.kt), and writes one row per task plus a
totals row to `efficiency.md` in the same results folder.

  python benchmark/suite/efficiency.py [results_dir]   # default: benchmark/results/aura-on-device
  python benchmark/suite/efficiency.py --self-test

Change together: RunJournal.kt (TYPE_SEEN, NOT_RECORDED, StepPhrase.LOOKS), Telemetry.kt
(MODEL_OPERATIONS), device.pull_traces (what lands in agentcore/).
"""

import json
import os
import statistics
import sys
import tempfile

RESULTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'results', 'aura-on-device')

# StepPhrase.LOOKS in RunJournal.kt: tool calls that only read. Every other tool call is an action,
# exactly as the phone's run page counts them.
LOOKS = {
    'read_screen', 'perceive_screen', 'get_screenshot', 'read_notifications', 'get_device_status',
    'get_media_sessions', 'web_search', 'find_files', 'resolve_contact', 'lookup_app',
    'list_app_deeplinks', 'resolve_deeplink', 'wait_for', 'verify_action', 'watch_device_events',
    'browser_read', 'browser_find', 'browser_extract', 'browser_tabs', 'browser_screenshot',
    'search_history', 'skill_view', 'list_schedules', 'load_toolset', 'update_plan',
    'screens', 'screen', 'screen_diff',
}
HISTORY = {'screens', 'screen', 'screen_diff'}
MODEL_OPERATIONS = {'chat', 'generate_content', 'text_completion'}
NOT_RECORDED = '(not recorded)'

COLUMNS = ['task', 'pass', 'seconds', 'actions', 'model calls', 'input tokens', 'distinct screens',
           'revisits', 'history calls', 'notes %', 'worst repeat']


def _lines(folder, prefix):
  if not os.path.isdir(folder):
    return []
  out = []
  for name in sorted(os.listdir(folder)):
    if name.startswith(prefix) and name.endswith('.jsonl'):
      with open(os.path.join(folder, name), encoding='utf-8') as f:
        for line in f:
          try:
            out.append(json.loads(line))
          except ValueError:
            continue
  return out


def _args(record):
  try:
    value = json.loads(record.get('args') or '{}')
    return value if isinstance(value, dict) else {}
  except ValueError:
    return {}


def task_row(task, meta, steps, spans):
  """One task's numbers. Pure: `steps` and `spans` are decoded JSONL records."""
  tools = [s for s in steps if s.get('type') == 'tool']
  actions = [t for t in tools if t.get('tool') not in LOOKS]
  screens = [s.get('result') for s in steps if s.get('type') == 'seen' and s.get('result') not in (None, NOT_RECORDED)]
  calls = [s for s in spans if str(s.get('name', '')).split(' ')[0] in MODEL_OPERATIONS]
  tokens = 0
  for call in calls:
    for key, value in (call.get('attributes') or {}).items():
      if key.endswith('input_tokens'):
        try:
          tokens += int(value)
        except (TypeError, ValueError):
          pass
  # The longest streak of the same failing call, one after another: the loop signal.
  worst, streak, last = 0, 0, None
  for t in tools:
    key = (t.get('tool'), t.get('args'))
    if t.get('ok', True):
      streak, last = 0, None
      continue
    streak = streak + 1 if key == last else 1
    last = key
    worst = max(worst, streak)
  success = meta.get('success')
  return {
      'task': task,
      'pass': None if success is None else bool(success),
      'seconds': meta.get('run_time'),
      'actions': len(actions),
      'model calls': len(calls),
      'input tokens': tokens,
      'distinct screens': len(set(screens)),
      'revisits': len(screens) - len(set(screens)),
      'history calls': sum(1 for t in tools if t.get('tool') in HISTORY),
      'notes %': round(100 * sum(1 for a in actions if 'seen' in _args(a)) / len(actions)) if actions else 0,
      'worst repeat': worst,
  }


def totals(rows):
  """The pass rate over tasks with a verdict, and the median of each count."""
  judged = [r for r in rows if r['pass'] is not None]
  out = {'task': 'TOTAL (%d tasks)' % len(rows),
         'pass': '%d/%d' % (sum(1 for r in judged if r['pass']), len(judged))}
  for col in COLUMNS[2:]:
    values = [r[col] for r in rows if isinstance(r[col], (int, float))]
    out[col] = 'median %s' % (round(statistics.median(values), 1) if values else '-')
  return out


def rows_for(results):
  rows = []
  for task in sorted(os.listdir(results)):
    folder = os.path.join(results, task)
    meta_path = os.path.join(folder, 'meta.json')
    if not os.path.isfile(meta_path):
      continue
    with open(meta_path, encoding='utf-8') as f:
      meta = json.load(f)
    logs = os.path.join(folder, 'agentcore')
    rows.append(task_row(task, meta, _lines(logs, 'steps-'), _lines(logs, 'spans-')))
  return rows


def markdown(rows):
  def cell(v):
    return {True: 'PASS', False: 'FAIL', None: '-'}.get(v, v) if isinstance(v, bool) or v is None else str(v)
  lines = ['| ' + ' | '.join(COLUMNS) + ' |', '|' + '---|' * len(COLUMNS)]
  for r in rows + ([totals(rows)] if rows else []):
    lines.append('| ' + ' | '.join(cell(r[c]) for c in COLUMNS) + ' |')
  return '\n'.join(lines) + '\n'


def write(results):
  rows = rows_for(results)
  path = os.path.join(results, 'efficiency.md')
  with open(path, 'w', encoding='utf-8') as f:
    f.write('# AURA on-device efficiency\n\n' + markdown(rows))
  return path, rows


def self_test():
  root = tempfile.mkdtemp()

  def task(name, meta, steps, spans):
    logs = os.path.join(root, name, 'agentcore')
    os.makedirs(logs)
    with open(os.path.join(root, name, 'meta.json'), 'w', encoding='utf-8') as f:
      json.dump(meta, f)
    for prefix, records in (('steps', steps), ('spans', spans)):
      with open(os.path.join(logs, '%s-2026-10-15.jsonl' % prefix), 'w', encoding='utf-8') as f:
        f.write(''.join(json.dumps(r) + '\n' for r in records))

  tap = {'type': 'tool', 'tool': 'tap', 'args': '{"som_id":3,"seen":"form"}', 'ok': True}
  bad = {'type': 'tool', 'tool': 'tap', 'args': '{"som_id":9}', 'ok': False}
  task('ExpenseAdd', {'success': True, 'run_time': 40.5}, [
      {'type': 'seen', 'detail': 'S1', 'result': 'form'},
      tap,
      {'type': 'seen', 'detail': 'S2', 'result': 'list'},
      {'type': 'seen', 'detail': 'S3', 'result': 'form'},
      {'type': 'tool', 'tool': 'screens', 'args': '{"query":"taxi"}', 'ok': True},
      {'type': 'tool', 'tool': 'read_screen', 'args': '{}', 'ok': True},
      bad, bad, bad, tap, bad,
      {'type': 'seen', 'detail': 'S4', 'result': NOT_RECORDED},
  ], [
      {'name': 'chat gemini-flash', 'attributes': {'gen_ai.usage.input_tokens': 1000}},
      {'name': 'chat gemini-flash', 'attributes': {'gen_ai.usage.input_tokens': 500}},
      {'name': 'execute_tool tap', 'attributes': {}},
  ])
  task('MarkorNote', {'success': False, 'run_time': 10}, [], [])
  task('Stopped', {'success': None, 'run_time': 3}, [], [])

  path, rows = write(root)
  by = {r['task']: r for r in rows}
  e = by['ExpenseAdd']
  assert e['pass'] is True and e['seconds'] == 40.5, e
  assert e['actions'] == 6, e  # 2 taps that worked + 4 failed; screens and read_screen are looks
  assert e['model calls'] == 2 and e['input tokens'] == 1500, e
  assert e['distinct screens'] == 2 and e['revisits'] == 1, e  # NOT_RECORDED is not a screen
  assert e['history calls'] == 1, e
  assert e['notes %'] == 33, e  # 2 of 6 actions carry seen
  assert e['worst repeat'] == 3, e
  assert by['MarkorNote']['actions'] == 0 and by['MarkorNote']['pass'] is False
  t = totals(rows)
  assert t['pass'] == '1/2', t  # the stopped task has no verdict
  assert t['actions'] == 'median 0', t
  text = open(path, encoding='utf-8').read()
  assert '| ExpenseAdd | PASS | 40.5 | 6 | 2 | 1500 | 2 | 1 | 1 | 33 | 3 |' in text, text
  assert '| Stopped | - |' in text, text
  print('ok')


def main(argv):
  if argv[1:] == ['--self-test']:
    return self_test()
  results = argv[1] if len(argv) > 1 else RESULTS
  path, rows = write(results)
  print('%s: %d tasks' % (path, len(rows)))


if __name__ == '__main__':
  main(sys.argv)
