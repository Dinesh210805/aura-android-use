"""Run AndroidWorld against ANY agent that can be started as a command.

    androidworld-venv/Scripts/python.exe scripts/bench/external_agent.py \
        --profile claude-code-aura-mcp --tasks=MarkorCreateNote --set model=sonnet

`suite.py` is the usual way in; this CLI is the same runner without a page.
The agent is a plug-in described by a JSON profile in `agents/`; nothing here
knows about Claude Code, MCP or AURA. AndroidWorld's own runner (`suite_utils.run`)
seeds the task, asks the validator and tears down. This file only turns "run a
command" into the one `step()` AndroidWorld expects.

### The contract (what a profile's command can rely on)

Harness -> agent, as environment variables (and `{name}` placeholders in the
profile's `command`):

    AW_GOAL           the goal text. Also on stdin when the profile says "stdin": "goal".
    AW_MAX_STEPS      the stock AndroidWorld budget, int(10 * task.complexity). A hint
                      for CLIs with a turn cap; the hard limit is the wall clock.
    AW_DEADLINE_S     seconds before the process tree is killed.
    AW_OUT_DIR        a directory the agent may write into.
    AW_ANSWER_FILE    write the final answer here for information-retrieval tasks.
    AW_USAGE_FILE     optionally write {"turns","tool_calls","input_tokens",
                      "output_tokens","cost_usd"} here.
    AW_ADB_SERIAL     the emulator, for agents that use adb.

Agent -> harness: the exit code, stdout (shown live, one line at a time), and
the two optional files above.

### Rules followed from stock AndroidWorld

- The agent is handed the goal and nothing else. It is not told the task name,
  the seed or the validator, and it runs in an empty temp directory outside this
  repo so it cannot read them.
- `done` means "exit 0 before the deadline". Stock `suite_utils` scores 0 for an
  agent that never signalled done, whatever the validator says, so a timeout or a
  non-zero exit fails here too. meta.json keeps the raw validator result beside
  the gated one.
- A typed answer goes to `env.interaction_cache`, exactly where stock agents'
  `answer` action puts it.
- Same seed (30), same shifted clock, same one-instance-per-task as the on-device
  runs, so numbers are comparable.
"""

import argparse
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, '..', '..', 'android_world'))

from android_world import checkpointer as checkpointer_lib  # noqa: E402
from android_world import constants  # noqa: E402
from android_world import registry  # noqa: E402
from android_world import suite_utils  # noqa: E402
from android_world.agents import base_agent  # noqa: E402
from android_world.env import env_launcher  # noqa: E402

import aura_androidworld  # noqa: E402
import device  # noqa: E402
from aura_androidworld import RESULTS, SEED, write_text  # noqa: E402

if hasattr(sys.stdout, 'reconfigure'):
  sys.stdout.reconfigure(encoding='utf-8', errors='replace')
  sys.stderr.reconfigure(encoding='utf-8', errors='replace')

PROFILE_DIR = os.path.join(HERE, 'agents')
DEVICE_YEAR = 2026
DEFAULT_TIMEOUT_S = 600


# --- profiles --------------------------------------------------------------

class _Vars(dict):
  def __missing__(self, key):
    raise KeyError('the profile uses {%s} but nothing defines it; add it under '
                   '"defaults" or pass --set %s=VALUE' % (key, key))


def list_profiles():
  return sorted(f[:-5] for f in os.listdir(PROFILE_DIR)
                if f.endswith('.json') and not f.endswith('.mcp.json'))


def load_profile(ref, overrides):
  """Reads agents/<ref>.json (or a path). Contract: returns a validated dict."""
  path = ref if os.path.isfile(ref) else os.path.join(PROFILE_DIR, ref + '.json')
  with open(path, encoding='utf-8') as f:
    spec = json.load(f)
  command = spec.get('command')
  if not (isinstance(command, list) and command and all(isinstance(a, str) for a in command)):
    raise SystemExit('%s: "command" must be a non-empty list of strings' % path)
  spec.setdefault('name', os.path.splitext(os.path.basename(path))[0])
  spec['vars'] = _Vars(spec.get('defaults', {}))
  spec['vars'].update(overrides)
  spec['vars']['profile_dir'] = os.path.dirname(os.path.abspath(path)).replace('\\', '/')
  return spec


def render(argv, values):
  return [a.format_map(values) for a in argv]


# --- running the command ---------------------------------------------------

def kill_tree(proc):
  """Kills the process and every child. A surviving child keeps tapping into the next task."""
  if os.name == 'nt':
    subprocess.run(['taskkill', '/PID', str(proc.pid), '/T', '/F'], capture_output=True)
    return
  try:
    os.killpg(proc.pid, signal.SIGKILL)
  except ProcessLookupError:
    pass


def _pump(stream, sink, on_line):
  for line in stream:
    sink.append(line)
    if on_line:
      try:
        on_line(line.rstrip('\n'))
      except Exception:  # noqa: BLE001 - a broken viewer must not kill the run
        pass


def run_command(argv, env, cwd, stdin_text, timeout, on_line=None, cancel=None):
  """Runs argv until it exits, the deadline passes, or `cancel` is set.

  Contract: returns exit_code, timed_out, cancelled, stdout, stderr, seconds.
  stdout lines go to on_line as they arrive. On deadline or cancel the whole
  process tree is killed before this returns.
  """
  exe = shutil.which(argv[0])
  if exe is None:
    raise FileNotFoundError('agent command not found on PATH: %s' % argv[0])
  group = ({'creationflags': subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == 'nt'
           else {'start_new_session': True})
  began = time.time()
  proc = subprocess.Popen(
      [exe] + argv[1:], cwd=cwd, env=env, text=True, encoding='utf-8', errors='replace',
      stdin=subprocess.PIPE if stdin_text is not None else subprocess.DEVNULL,
      stdout=subprocess.PIPE, stderr=subprocess.PIPE, **group)
  out, err = [], []
  pumps = [threading.Thread(target=_pump, args=(proc.stdout, out, on_line), daemon=True),
           threading.Thread(target=_pump, args=(proc.stderr, err, None), daemon=True)]
  for t in pumps:
    t.start()
  if stdin_text is not None:
    try:
      proc.stdin.write(stdin_text)
      proc.stdin.close()
    except OSError:
      pass  # the agent exited before reading its goal; its exit code says why
  timed_out = cancelled = False
  while proc.poll() is None:
    if cancel is not None and cancel.is_set():
      cancelled = True
    elif time.time() - began > timeout:
      timed_out = True
    else:
      time.sleep(0.25)
      continue
    kill_tree(proc)
    break
  try:
    proc.wait(timeout=15)
  except subprocess.TimeoutExpired:
    err.append('the process tree did not exit after being killed\n')
  for t in pumps:
    t.join(timeout=5)
  return {'exit_code': proc.returncode, 'timed_out': timed_out, 'cancelled': cancelled,
          'stdout': ''.join(out), 'stderr': ''.join(err), 'seconds': round(time.time() - began, 1)}


# --- reading what the agent reports ----------------------------------------

def _short(value, n=140):
  text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
  text = ' '.join(text.split())
  return text if len(text) <= n else text[:n - 1] + '…'


def claude_events(line):
  """One line of `claude -p --output-format stream-json` -> [(kind, text)] for the live view.

  kinds: 'tool' (a call), 'ok'/'err' (its result), 'say' (Claude's text), 'done'.
  """
  try:
    d = json.loads(line)
  except ValueError:
    return []
  kind = d.get('type')
  if kind == 'result':
    return [('done', '%s · %s turns · $%.3f' % (d.get('subtype'), d.get('num_turns'),
                                                   d.get('total_cost_usd') or 0))]
  content = (d.get('message') or {}).get('content')
  if not isinstance(content, list):
    return []
  events = []
  for c in content:
    if c.get('type') == 'text' and c.get('text', '').strip():
      events.append(('say', _short(c['text'], 400)))
    elif c.get('type') == 'tool_use':
      name = c.get('name', '').replace('mcp__aura__', '')
      events.append(('tool', '%s(%s)' % (name, _short(c.get('input') or {}, 120))))
    elif c.get('type') == 'tool_result':
      body = c.get('content')
      if isinstance(body, list):
        body = ' '.join(x.get('text', '') for x in body if isinstance(x, dict))
      events.append(('err' if c.get('is_error') else 'ok', _short(body or '', 160)))
  return events


def parse_claude_stream(stdout):
  """Whole `stream-json` output -> (usage, answer). Fails open: ({}, '')."""
  result, tool_calls = None, 0
  for line in stdout.splitlines():
    try:
      d = json.loads(line)
    except ValueError:
      continue
    if d.get('type') == 'result':
      result = d
    for c in ((d.get('message') or {}).get('content') or []) if d.get('type') == 'assistant' else []:
      if c.get('type') == 'tool_use' and c.get('name', '').startswith('mcp__'):
        tool_calls += 1
  if result is None:
    return ({'tool_calls': tool_calls} if tool_calls else {}), ''
  u = result.get('usage') or {}
  usage = {
      'turns': result.get('num_turns'),
      'tool_calls': tool_calls,
      'cost_usd': result.get('total_cost_usd'),
      'input_tokens': sum(u.get(k) or 0 for k in (
          'input_tokens', 'cache_creation_input_tokens', 'cache_read_input_tokens')) or None,
      'output_tokens': u.get('output_tokens'),
      'model': ','.join(sorted((result.get('modelUsage') or {}).keys())) or None,
  }
  return {k: v for k, v in usage.items() if v is not None}, str(result.get('result') or '')


# name -> (live events per stdout line, final (usage, answer) from all of stdout)
PARSERS = {'claude-stream': (claude_events, parse_claude_stream)}
_RAW = (lambda line: [('out', line)] if line.strip() else [], lambda _: ({}, ''))


def _read_json(path):
  try:
    with open(path, encoding='utf-8') as f:
      value = json.load(f)
    return value if isinstance(value, dict) else {}
  except (OSError, ValueError):
    return {}


def _read_text(path):
  try:
    with open(path, encoding='utf-8') as f:
      return f.read().strip()
  except OSError:
    return ''


# --- the AndroidWorld agent ------------------------------------------------

class ExternalAgent(base_agent.EnvironmentInteractingAgent):
  """Hands one goal to an external command, once, and reports whether it finished.

  Contract: `step` runs the command on its first call per episode and is a no-op
  after that (returns done=False), so a failed run is not retried by
  AndroidWorld's step loop. `report` holds what happened, for meta.json.
  `recorder` is any class with start()/stop() -> [video files] (device.Recorder,
  desktop.PageRecorder). `on_event(kind, text)` receives the live view.
  """

  def __init__(self, env, spec, out_dir, timeout, recorder=device.Recorder,
               on_event=None, cancel=None):
    super().__init__(env, name=spec['name'])
    self._spec = spec
    self._out_dir = out_dir
    self._timeout = timeout
    self._recorder = recorder
    self._on_event = on_event or (lambda kind, text: None)
    self._cancel = cancel
    self._spent = False
    self.report = {}

  def reset(self, go_home=False):
    super().reset(go_home)
    # As stock M3A/T3A/SeeAct do: the env shows touch coordinates on screen,
    # which would otherwise be in every screenshot the agent reads.
    self.env.hide_automation_ui()
    self._spent = False

  def step(self, goal):
    if self._spent:
      return base_agent.AgentInteractionResult(done=False, data={})
    self._spent = True
    self._on_event('goal', goal)

    aura_androidworld.restore_aura_a11y(device.ADB, device.SERIAL, device.AURA_PACKAGE)
    # AndroidWorld's `svc wifi enable` gives the emulator an IPv6 address with no
    # route; anything on the phone that calls out then fails before trying IPv4.
    device.adb('shell', 'sysctl -w net.ipv6.conf.all.disable_ipv6=1')

    answer_file = os.path.join(self._out_dir, 'answer.txt')
    usage_file = os.path.join(self._out_dir, 'usage.json')
    values = _Vars(self._spec['vars'])
    values.update(max_steps=self._max_steps or 0, timeout_s=self._timeout,
                  out_dir=self._out_dir.replace('\\', '/'), serial=device.SERIAL)
    argv = render(self._spec['command'], values)
    env = dict(os.environ)
    env.update({str(k): str(v) for k, v in self._spec.get('env', {}).items()})
    env.update({
        'AW_GOAL': goal, 'AW_MAX_STEPS': str(self._max_steps or 0),
        'AW_DEADLINE_S': str(self._timeout), 'AW_OUT_DIR': self._out_dir,
        'AW_ANSWER_FILE': answer_file, 'AW_USAGE_FILE': usage_file,
        'AW_ADB_SERIAL': device.SERIAL,
    })
    stdin_text = goal if self._spec.get('stdin') == 'goal' else None
    live, final = PARSERS.get(self._spec.get('parser'), _RAW)

    def on_line(line):
      for kind, text in live(line):
        self._on_event(kind, text)

    workdir = tempfile.mkdtemp(prefix='aw_agent_')
    rec = self._recorder(self._out_dir)
    rec.start()
    try:
      result = run_command(argv, env, workdir, stdin_text, self._timeout, on_line, self._cancel)
    finally:
      videos = rec.stop()
      shutil.rmtree(workdir, ignore_errors=True)
    if result['timed_out'] or result['cancelled']:
      time.sleep(2)  # let the screen settle before the validator reads it

    usage, answer = final(result['stdout'])
    usage.update(_read_json(usage_file))
    answer = _read_text(answer_file) or answer
    self._env.interaction_cache = answer  # where stock agents' `answer` action puts it

    write_text(os.path.join(self._out_dir, 'agent.stdout.log'), result['stdout'])
    write_text(os.path.join(self._out_dir, 'agent.stderr.log'), result['stderr'])
    done = result['exit_code'] == 0 and not result['timed_out'] and not result['cancelled']
    self.report = {
        'done': done, 'timed_out': result['timed_out'], 'stopped': result['cancelled'],
        'exit_code': result['exit_code'], 'agent_seconds': result['seconds'], 'usage': usage,
        'answer': answer, 'videos': videos, 'command': argv, 'max_steps': self._max_steps,
    }
    return base_agent.AgentInteractionResult(
        done=done, data={'exit_code': result['exit_code'], 'timed_out': result['timed_out']})


# --- one task --------------------------------------------------------------

def run_one(env, make_agent, name, tasks, seed, out_root, profile):
  """Runs one task through AndroidWorld's runner and writes <out_root>/<name>/meta.json.

  Contract: make_agent(out_dir) returns an agent with a `report` dict after step().
  A run the user stopped is written with success None, so it counts as "not run",
  never as a FAIL.
  """
  out = os.path.join(out_root, name)
  shutil.rmtree(out, ignore_errors=True)  # cleared, not merged: one run's video per verdict
  os.makedirs(out)
  suite = suite_utils.create_suite(
      tasks, n_task_combinations=1, seed=seed, tasks=[name], use_identical_params=False)
  suite.suite_family = registry.TaskRegistry.ANDROID_WORLD_FAMILY
  instance = suite[name][0]

  raw = {}
  validate = instance.is_successful

  def spy(e):
    raw['value'] = validate(e)
    return raw['value']

  instance.is_successful = spy  # the ungated validator result, for transparency

  agent = make_agent(out)
  agent.transition_pause = None
  started = time.time()
  episode = suite_utils.run(
      suite, agent,
      checkpointer=checkpointer_lib.IncrementalCheckpointer(os.path.join(out, 'checkpoint')),
      demo_mode=False, return_full_episode_data=True)[0]

  rep = getattr(agent, 'report', {}) or {}
  success = bool(episode.get(constants.EpisodeConstants.IS_SUCCESSFUL, 0) > 0.5)
  meta = {
      'task': name, 'goal': episode.get(constants.EpisodeConstants.GOAL, ''),
      'started': started, 'finished': time.time(),
      'run_time': round(time.time() - started, 1), 'seed': seed,
      'success': None if rep.get('stopped') else success,
      'validator_success': None if 'value' not in raw else bool(raw['value'] > 0.5),
      'blind': bool(rep.get('blind')), 'episode_length': 1,
      'error': str(episode.get(constants.EpisodeConstants.EXCEPTION_INFO) or ''),
      'trigger': 'harness', 'profile': profile, 'agent': rep,
      'videos': rep.get('videos', []),
  }
  write_text(os.path.join(out, 'meta.json'), json.dumps(meta, indent=2))
  return meta


def boot_env():
  """Builds AndroidWorld's env once, with the shifted clock. Slow: do it once per session."""
  aura_androidworld.shift_device_year(DEVICE_YEAR)
  return env_launcher.load_and_setup_env(
      console_port=int(device.SERIAL.rsplit('-', 1)[1]), emulator_setup=False,
      adb_path=device.ADB)


def sweep(spec, task_names, include_ir, seed, out_root, timeout, resume):
  tasks = aura_androidworld.scored_tasks(include_ir)
  unknown = [t for t in task_names if t not in tasks]
  if unknown:
    raise SystemExit('Not in the scored suite: %s (information-retrieval tasks need '
                     '--include_ir)' % ', '.join(unknown))
  if shutil.which(spec['command'][0]) is None:
    raise SystemExit('Agent command not found on PATH: %s' % spec['command'][0])
  names = task_names or sorted(tasks)
  os.makedirs(out_root, exist_ok=True)
  try:
    device.up('mcp', report=lambda s, st, d='': print('  %-28s %-8s %s' % (s, st, d)))
  except device.SetupError as e:
    raise SystemExit('SETUP: %s' % e)

  env = boot_env()
  passed = ran = 0
  for i, name in enumerate(names, 1):
    prior = _read_json(os.path.join(out_root, name, 'meta.json'))
    if resume and prior.get('success') in (True, False):
      continue
    meta = run_one(env, lambda out: ExternalAgent(env, spec, out, timeout), name, tasks,
                   seed, out_root, spec['name'])
    ran += 1
    passed += bool(meta['success'])
    print('[%d/%d] %-40s %s  (%ss, validator=%s, done=%s)' % (
        i, len(names), name, 'PASS' if meta['success'] else 'FAIL', meta['run_time'],
        meta['validator_success'], meta['agent'].get('done')))
  print('Done: %d/%d passed this session. Results in %s. Seed %d.' % (passed, ran, out_root, seed))
  env.close()


# --- self-check ------------------------------------------------------------

def _alive(pid):
  if os.name == 'nt':
    out = subprocess.run(['tasklist', '/FI', 'PID eq %d' % pid, '/NH'],
                         capture_output=True, text=True).stdout
    return str(pid) in out
  try:
    os.kill(pid, 0)
    return True
  except OSError:
    return False


def _selfcheck():
  """Exercises the contract with fake agents. Needs no emulator and no AndroidWorld env.

  Protected here: a timeout or a stop must fail the task AND leave no process
  behind, a non-zero exit must fail, an answer must reach `interaction_cache`,
  live lines must arrive while the agent runs, and the agent must receive the
  goal but never the task name.
  """
  py = sys.executable

  class FakeRec:
    def __init__(self, out): pass
    def start(self): pass
    def stop(self): return []

  class FakeEnv:
    interaction_cache = ''
    def reset(self, go_home=False): pass
    def hide_automation_ui(self): pass

  device.adb = lambda *a, **k: subprocess.CompletedProcess(a, 0, '', '')
  aura_androidworld.restore_aura_a11y = lambda *a: None
  tmp = tempfile.mkdtemp(prefix='aw_selfcheck_')

  def agent_for(code, timeout=20, **extra):
    spec = {'name': 'fake', 'command': [py, '-I', '-c', code], 'vars': _Vars(), **extra}
    seen = []
    a = ExternalAgent(FakeEnv(), spec, tmp, timeout, recorder=FakeRec,
                      on_event=lambda k, t: seen.append((k, t)), cancel=extra.get('_cancel'))
    a.set_max_steps(30)
    return a, seen

  def stdout_log():
    return open(os.path.join(tmp, 'agent.stdout.log'), encoding='utf-8').read()

  a, seen = agent_for('import os;print(os.environ["AW_GOAL"], os.environ["AW_MAX_STEPS"], '
                      'any("MarkorCreateNote" in v for v in os.environ.values()))')
  r = a.step('make a note')
  assert r.done and 'make a note 30 False' in stdout_log(), \
      'a clean exit is done, and the agent sees the goal and the stock step budget but not the task name'
  assert ('out', 'make a note 30 False') in seen, 'stdout lines must reach the live view'
  assert a.step('x').done is False, 'a second step must not rerun the agent'

  a, _ = agent_for('import sys;sys.exit(1)')
  assert a.step('g').done is False, 'a non-zero exit must not count as done'

  a, _ = agent_for('import os;open(os.environ["AW_ANSWER_FILE"],"w").write("42")')
  a.step('g')
  assert a._env.interaction_cache == '42', 'the answer file must reach interaction_cache'

  a, _ = agent_for('import sys;print(sys.stdin.read())', stdin='goal')
  a.step('quoted "goal" & more')
  assert 'quoted "goal" & more' in stdout_log(), 'stdin must carry the goal intact, with no shell quoting'

  pidfile = os.path.join(tmp, 'child.pid')
  child = ('import subprocess,sys,time;'
           'p=subprocess.Popen([sys.executable,"-I","-c","import time;time.sleep(60)"]);'
           'open(%r,"w").write(str(p.pid));time.sleep(60)' % pidfile)
  a, _ = agent_for(child, timeout=3)
  assert a.step('g').done is False and a.report['timed_out'], 'a timeout must not count as done'
  time.sleep(1)
  assert not _alive(int(_read_text(pidfile))), 'the whole process tree must be gone after a timeout'

  cancel = threading.Event()
  threading.Timer(1.5, cancel.set).start()
  a, _ = agent_for('import time;time.sleep(60)', _cancel=cancel)
  began = time.time()
  assert a.step('g').done is False and a.report['stopped'], 'a stop must not count as done'
  assert time.time() - began < 10, 'a stop must end the agent promptly, not at the deadline'

  stream = '\n'.join(json.dumps(x) for x in [
      {'type': 'assistant', 'message': {'content': [
          {'type': 'tool_use', 'name': 'mcp__aura__tap', 'input': {'x': 1}},
          {'type': 'tool_use', 'name': 'ToolSearch', 'input': {}}]}},
      {'type': 'user', 'message': {'content': [
          {'type': 'tool_result', 'content': [{'type': 'text', 'text': 'tapped'}]}]}},
      {'type': 'result', 'subtype': 'success', 'result': 'ok', 'num_turns': 3,
       'total_cost_usd': 0.5,
       'usage': {'input_tokens': 2, 'cache_read_input_tokens': 8, 'output_tokens': 4}}])
  usage, answer = parse_claude_stream(stream)
  assert usage['input_tokens'] == 10 and usage['turns'] == 3 and answer == 'ok', \
      'stream usage must total cached and uncached input and take the final result'
  assert usage['tool_calls'] == 1, 'only MCP tool calls count, not Claude Code built-ins'
  assert claude_events(stream.splitlines()[0])[0] == ('tool', 'tap({"x": 1})'), \
      'a tool call must read as name(args) without the mcp__aura__ prefix'
  assert parse_claude_stream('not json') == ({}, ''), 'an unreadable report must fail open'

  try:
    render(['{nope}'], _Vars())
  except KeyError:
    pass
  else:
    raise AssertionError('an undefined placeholder must name itself, not render blank')
  shutil.rmtree(tmp, ignore_errors=True)
  print('external_agent self-check passed')


def main():
  p = argparse.ArgumentParser(description=__doc__.split('\n')[0])
  p.add_argument('--selfcheck', action='store_true')
  p.add_argument('--profile', help='agents/<name>.json, or a path')
  p.add_argument('--tasks', default='', help='comma-separated; default: every scored task')
  p.add_argument('--include_ir', action='store_true',
                 help='also run information-retrieval tasks (see README: they fail for harness reasons)')
  p.add_argument('--seed', type=int, default=SEED)
  p.add_argument('--timeout', type=int, help='seconds per task; default: the profile, else 600')
  p.add_argument('--set', action='append', default=[], metavar='KEY=VALUE',
                 help='fills a {placeholder} in the profile, e.g. --set model=sonnet')
  p.add_argument('--resume', action='store_true', help='skip tasks that already have a verdict')
  args = p.parse_args()
  if args.selfcheck:
    return _selfcheck()
  if not args.profile:
    p.error('--profile is required')
  spec = load_profile(args.profile, dict(kv.split('=', 1) for kv in args.set))
  timeout = args.timeout or spec.get('timeout_s') or DEFAULT_TIMEOUT_S
  sweep(spec, [t for t in args.tasks.split(',') if t], args.include_ir, args.seed,
        os.path.join(RESULTS, spec['name']), timeout, args.resume)


if __name__ == '__main__':
  main()
