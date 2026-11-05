"""The benchmark suite: one browser page to set up, pick tasks, run them and watch.

    androidworld-venv/Scripts/python.exe benchmark/suite/suite.py
    -> opens http://localhost:8777

Pick a target, and the page sets the machine up (`device.up`), builds
AndroidWorld's env once, then lists every scored task with its last result. Run
one, a selection, all, the failed ones, or resume. While tasks run, the page
shows the queue, the agent's live activity and the phone's live display
(`/screen.mjpg`, from the emulator's gRPC port), and films that browser window.

Targets:
- `mcp`: Claude Code drives the phone through AURA's MCP tools (profile
  `agents/claude-code-aura-mcp.json`). Results: benchmark/results/claude-code-aura-mcp/<model>/<Task>/,
  one folder per model so a pass rate never mixes models.
- `ondevice`: AURA's own agent gets the goal over the debug broadcast.
  Results: benchmark/results/aura-on-device/<Task>/, the folder `referee.py` and `report.py` already use.

Every verdict is AndroidWorld's validator through `external_agent.run_one`; see
README.md for the rules and how the two targets differ.
"""

import json
import os
import shutil
import socketserver
import sys
import threading
import time
import traceback
import urllib.parse
import webbrowser
import http.server

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import aura_androidworld  # noqa: E402
import desktop  # noqa: E402
import device  # noqa: E402
import external_agent  # noqa: E402
import publish  # noqa: E402
import report  # noqa: E402
import theme  # noqa: E402
from aura_androidworld import ONDEVICE_RESULTS, RESULTS, RUNTIME, SEED, write_text  # noqa: E402

PREVIEW = os.path.join(RUNTIME, 'preview')  # local copy of the pages publish would push

PORT = 8777
PROFILE = 'claude-code-aura-mcp'
ONDEVICE_TIMEOUT_S = 300
MAX_EVENTS = 600
MAX_APP_LINES = 2000
SESSION_PULL_S = 4
CONTENT_TYPES = {'.mp4': 'video/mp4', '.html': 'text/html; charset=utf-8', '.css': 'text/css',
                 '.json': 'application/json', '.png': 'image/png', '.jpg': 'image/jpeg'}
# Per target: which tasks, which clock, and what to tell readers about both.
# Change together: the deviations table in README.md.
TARGETS = {
    'mcp': {
        'label': 'MCP server',
        'sub': 'Claude Code drives the phone through AURA\'s MCP tools',
        'include_ir': True, 'device_year': None,
        'deviations': [
            'All 116 tasks, AndroidWorld\'s own 2023-10-15 clock, seed 30: the stock suite.',
            'Step budget is stock (10 x complexity), counted as actions on the phone; looking is free.',
            'A 20-minute wall-clock limit only guards against a hung run.',
            'Claude Code is stock except: no shell, and no tools that act outside the run.',
        ],
        'about': {
            'title': 'Claude Code + AURA MCP on AndroidWorld',
            'sub': ('Claude Code operating an Android phone only through AURA\'s MCP server, '
                    'scored by Google\'s AndroidWorld benchmark. Every task was recorded.'),
            'method': ('The stock suite: all 116 tasks, AndroidWorld\'s own clock and seed 30. '  
                       'The step budget is AndroidWorld\'s (10 x complexity), counted as '
                       'actions on the phone. Claude Code is stock apart from having no '
                       'shell and no tools that act outside the run; each run records the '
                       'tools it had.'),
        },
    },
    'ondevice': {
        'label': 'On-device agent',
        'sub': 'AURA\'s own agent, running on the phone',
        'include_ir': False, 'device_year': 2026,
        'deviations': [
            '91 tasks: the 25 information-retrieval tasks need a typed answer AURA cannot give.',
            'Device clock moved from 2023 to 2026, so the phone\'s calls to its model pass TLS.',
            'A wall-clock limit instead of a step budget: AURA\'s steps happen on the phone.',
        ],
        'about': report.ABOUT_ONDEVICE,
    },
}


class NullRecorder:
  def __init__(self, out_dir):
    del out_dir

  def start(self):
    pass

  def stop(self):
    return []


RECORDERS = {'page': desktop.PageRecorder, 'phone': device.Recorder, 'off': NullRecorder}
SCREEN = device.PhoneScreen()


class OnDeviceAgent(aura_androidworld.AuraAgent):
  """AuraAgent with a camera and a log file, so it reports like ExternalAgent does."""

  def __init__(self, env, out_dir, timeout, recorder, on_event, cancel):
    super().__init__(env, device.ADB, device.SERIAL, timeout, device.AURA_PACKAGE,
                     cancel=cancel, on_log=lambda line: on_event('log', line))
    self._out_dir = out_dir
    self._recorder = recorder
    self._on_event = on_event

  def step(self, goal):
    self._on_event('goal', goal)
    rec = self._recorder(self._out_dir)
    rec.start()
    try:
      return super().step(goal)
    finally:
      self.report['videos'] = rec.stop()
      write_text(os.path.join(self._out_dir, 'agent.log'), self.report.pop('log', ''))


def planned_goals(target):
  """Every task's exact goal for `target`, before anything runs.

  Contract: the same text the agent will be given. AndroidWorld seeds each task's
  parameters from (SEED, task name), and they derive from the device clock, so this
  builds the suite once on the target's clock. Runs offline in about 2 s.
  """
  from android_world import suite_utils
  aura_androidworld.set_device_year(target['device_year'])
  built = suite_utils.create_suite(aura_androidworld.scored_tasks(target['include_ir']),
                                   n_task_combinations=1, seed=SEED)
  return {name: instances[0].goal for name, instances in built.items()}


class Suite:
  """Everything the page shows. One instance; every field is guarded by `lock`."""

  def __init__(self):
    self.lock = threading.Lock()
    self.target = None
    self.setup = []
    self.setup_busy = False
    self.ready = False
    self.env = None
    self.publishing = {}  # state of the Publish button's job, shown on the page
    self.started_emulator = False
    self.spec = external_agent.load_profile(PROFILE, {})
    self.opts = {'model': self.spec['vars']['model'],
                 'record': 'page' if desktop.available() else 'phone', 'timeout': None}
    self.queue = []
    self.run_tasks = []
    self.current = None
    self.stop_after = False
    self.cancel = threading.Event()
    self.session = {'passed': 0, 'failed': 0, 'stopped': 0, 'cost': 0.0}
    self.message = ''
    self.goals = {}  # task -> its exact goal at SEED, shown before it runs

  # --- where results live --------------------------------------------------

  def task_names(self):
    return sorted(aura_androidworld.scored_tasks(TARGETS[self.target]['include_ir']))

  def results_dir(self):
    if self.target == 'mcp':
      return os.path.join(RESULTS, PROFILE, self.opts['model'])
    return ONDEVICE_RESULTS

  def timeout(self):
    if self.opts['timeout']:
      return int(self.opts['timeout'])
    return self.spec.get('timeout_s', 600) if self.target == 'mcp' else ONDEVICE_TIMEOUT_S

  def rows(self):
    rows = []
    base = self.results_dir()
    for i, name in enumerate(self.task_names(), 1):
      m = external_agent._read_json(os.path.join(base, name, 'meta.json'))
      agent = m.get('agent') or {}
      rows.append({
          'n': i, 'task': name, 'goal': m.get('goal') or self.goals.get(name, ''),
          'success': m.get('success'),
          'validator': m.get('validator_success'), 'run_time': m.get('run_time'),
          'cost': (agent.get('usage') or {}).get('cost_usd'),
          'timed_out': agent.get('timed_out'), 'stopped': agent.get('stopped'),
          'out_of_steps': agent.get('out_of_steps'), 'steps_used': agent.get('steps_used'),
          'step_budget': agent.get('step_budget'),
          'blind': m.get('blind'), 'videos': m.get('videos') or [],
          'error': (m.get('error') or '')[-300:],
      })
    return rows

  # --- setup ---------------------------------------------------------------

  def choose(self, target):
    with self.lock:
      if self.queue or self.current or self.setup_busy:
        raise RuntimeError('busy: stop the run first')
      if target not in TARGETS:
        raise ValueError('unknown target %r' % target)
      self.target, self.ready, self.message = target, False, ''
    goals = planned_goals(TARGETS[target])
    with self.lock:
      self.goals = goals
    self.start_setup()

  def start_setup(self):
    with self.lock:
      if self.setup_busy:
        return
      self.setup_busy, self.setup = True, []
    threading.Thread(target=self._setup, daemon=True).start()

  def _report_step(self, step, state, detail=''):
    with self.lock:
      for s in self.setup:
        if s['step'] == step:
          s.update(state=state, detail=detail)
          return
      self.setup.append({'step': step, 'state': state, 'detail': detail})

  def _setup(self):
    try:
      started = device.up(self.target, report=self._report_step)
      self.started_emulator = self.started_emulator or started
      if self.env is None:
        self._report_step('AndroidWorld ready', 'running', 'first time takes a minute or two')
        self.env = external_agent.boot_env()
      self._report_step('AndroidWorld ready', 'ok')
      with self.lock:
        self.ready = True
    except device.SetupError:
      pass  # the failed step already carries the message
    except Exception as e:  # noqa: BLE001 - shown on the page, not swallowed
      self._report_step('Unexpected error', 'failed', '%s\n%s' % (e, traceback.format_exc()[-800:]))
    finally:
      with self.lock:
        self.setup_busy = False

  # --- running -------------------------------------------------------------

  def run(self, tasks):
    known = set(self.task_names())
    tasks = [t for t in tasks if t in known]
    with self.lock:
      if not self.ready:
        raise RuntimeError('setup has not finished')
      if self.queue or self.current:
        raise RuntimeError('a run is already going')
      if not tasks:
        raise ValueError('no tasks to run')
      self.queue, self.run_tasks = list(tasks), list(tasks)
      self.stop_after, self.message = False, ''
      self.cancel.clear()
    threading.Thread(target=self._worker, daemon=True).start()

  def stop(self, now):
    with self.lock:
      self.stop_after = True
      if now:
        self.cancel.set()

  def _push(self, kind, text):
    with self.lock:
      if self.current is None:
        return
      if kind == 'goal':
        # The goal arrives once run_one has cleared the task folder, so from here on
        # live.log can be written as it happens and opened mid-run.
        self.current['goal'] = text
        self.current['dir'] = os.path.join(self.results_dir(), self.current['task'])
        self._append_live('Goal: %s\n\n' % text)
      elif kind == 'steps':
        self.current['steps'] = text
      elif kind == 'app':
        self.current['applog'].append(text)
        del self.current['applog'][:-MAX_APP_LINES]
      else:
        events = self.current['events']
        events.append({'t': round(time.time() - self.current['started'], 1), 'kind': kind,
                       'text': text})
        del events[:-MAX_EVENTS]
        self._append_live('%7.1fs  %-5s %s\n' % (events[-1]['t'], kind, text))

  def _append_live(self, line):
    """Appends to the current task's live.log. Caller holds the lock."""
    if self.current.get('dir'):
      with open(os.path.join(self.current['dir'], 'live.log'), 'a', encoding='utf-8') as f:
        f.write(line)

  def _pull_steps(self, name, before, marks, done):
    """Copies AURA's session logs for task `name` off the phone every few seconds.

    Contract: copies the sessions that are new since the task started (`before`) and the
    ones in `marks` (an older session the task may continue). Readers skip the entries
    `marks` counts, so each task shows only its own steps (see device.session_marks).
    """
    while True:
      finished = done.wait(SESSION_PULL_S)
      with self.lock:
        cur = self.current or {}
        folder = cur.get('dir') if cur.get('task') == name else None
      if folder:
        ids = (device.phone_sessions() - before) | set(marks)
        device.pull_sessions(ids, os.path.join(folder, 'session'))
      if finished:
        return

  def _make_agent(self, out):
    recorder = RECORDERS[self.opts['record']]
    if self.target == 'ondevice':
      return OnDeviceAgent(self.env, out, self.timeout(), recorder, self._push, self.cancel)
    spec = external_agent.load_profile(PROFILE, {'model': self.opts['model']})
    return external_agent.ExternalAgent(self.env, spec, out, self.timeout(), recorder=recorder,
                                        on_event=self._push, cancel=self.cancel)  

  def _worker(self):
    target = TARGETS[self.target]
    tasks = aura_androidworld.scored_tasks(target['include_ir'])
    if self.opts['record'] == 'page' and desktop.find_page_window() is None:
      with self.lock:
        self.message = ('No browser window titled "AURA Bench" is open, so the video will show '
                        'the whole primary screen. Keep this tab in front in its own window.')
    try:
      while True:
        with self.lock:
          if not self.queue or self.stop_after:
            break
          name = self.queue[0]
          self.current = {'task': name, 'goal': '', 'started': time.time(),
                          'timeout': self.timeout(), 'events': [], 'applog': [], 'steps': ''}
        if self.target == 'mcp' and not device.phone_reachable():
          self._push('info', 'bridge lost the phone; reconnecting…')
          device.wait_phone()  # raises SetupError: the run stops, nothing is filed as a FAIL
        app_log = device.AppLog(lambda line: self._push('app', line))
        app_log.start()
        before, done = device.phone_sessions(), threading.Event()
        marks = device.session_marks(before)
        with self.lock:
          self.current['session_marks'] = marks
        puller = threading.Thread(target=self._pull_steps, args=(name, before, marks, done),
                                  daemon=True)
        puller.start()
        try:
          meta = external_agent.run_one(
              self.env, self._make_agent, name, tasks, SEED, self.results_dir(),
              PROFILE if self.target == 'mcp' else 'aura',
              self.opts['model'] if self.target == 'mcp' else None, target['device_year'],
              extra={'aura_version': device.aura_version(), 'session_marks': marks})
        finally:
          app_text = app_log.stop()
          done.set()
          puller.join()
        write_text(os.path.join(self.results_dir(), name, 'app.log'), app_text)
        with self.lock:
          key = 'stopped' if meta['success'] is None else 'passed' if meta['success'] else 'failed'
          self.session[key] += 1
          self.session['cost'] += ((meta['agent'] or {}).get('usage') or {}).get('cost_usd') or 0
          self.queue.pop(0)
    except device.SetupError as e:
      with self.lock:
        self.message = 'Run stopped: %s' % e
    except Exception as e:  # noqa: BLE001 - shown on the page, not swallowed
      with self.lock:
        self.message = 'Run crashed: %s\n%s' % (e, traceback.format_exc()[-800:])
    finally:
      with self.lock:
        self.queue, self.current, self.stop_after = [], None, False
        self.cancel.clear()

  def state(self):
    with self.lock:
      cur = dict(self.current) if self.current else None
      if cur:
        cur['events'] = list(cur['events'])
        cur['applog'] = cur['applog'][-500:]
        cur['elapsed'] = round(time.time() - cur['started'], 1)
      return {
          'target': self.target, 'targets': TARGETS, 'setup': [dict(s) for s in self.setup],
          'setup_busy': self.setup_busy, 'ready': self.ready, 'opts': dict(self.opts),
          'models': self.spec.get('models', []), 'desktop_ok': desktop.available(),
          'queue': list(self.queue), 'run_tasks': list(self.run_tasks), 'current': cur, 'session': dict(self.session),
          'message': self.message, 'timeout': self.timeout() if self.target else None,
          'tasks': self.rows() if self.target else [],
          'deviations': TARGETS[self.target]['deviations'] if self.target else [],
          'publishing': dict(self.publishing),
          'stopping': 'now' if self.cancel.is_set() else 'after' if self.stop_after else '',
      }

  def set_options(self, opts):
    with self.lock:
      if 'model' in opts and opts['model'] in self.spec.get('models', []):
        if self.queue or self.current:
          raise RuntimeError('cannot change the model during a run')
        self.opts['model'] = opts['model']
      if 'record' in opts and opts['record'] in RECORDERS:
        if opts['record'] == 'page' and not desktop.available():
          raise ValueError('page recording needs Windows and ffmpeg')
        self.opts['record'] = opts['record']
      if 'timeout' in opts:
        t = opts['timeout']
        self.opts['timeout'] = int(t) if t not in (None, '', 0) and 30 <= int(t) <= 3600 else None

  def run_id(self):
    """The run's folder name on the website: one per agent and model, replaced on republish."""
    return '%s-%s' % (PROFILE, self.opts['model']) if self.target == 'mcp' else 'aura-on-device'

  def run_info(self):
    """What the run's pages say about it: agent, model, AURA build, clock, seed, rules."""
    target, mcp = TARGETS[self.target], self.target == 'mcp'
    metas = report._read_results(self.results_dir())
    builds = sorted({m['aura_version'] for m in metas if m.get('aura_version')})
    facts = [('Agent', 'Claude Code via AURA MCP' if mcp else 'AURA on-device agent')]
    if mcp:
      facts.append(('Model', self.opts['model']))
    facts += [('AURA', ', '.join(builds) or 'not recorded'),
              ('Clock', '2023-10-15 (stock)' if mcp else '2026-10-15 (shifted)'), ('Seed', SEED)]
    about = dict(target['about'], title='Claude Code + AURA MCP' if mcp else 'AURA on-device agent')
    return report.run_info(about, facts, target['deviations'],
                           eyebrow='AndroidWorld · %s' % (self.opts['model'] if mcp else 'on-device'))

  def build_preview(self):
    """Builds the run's pages as the website will show them; returns their local URL."""
    report.build(self.results_dir(), os.path.join(PREVIEW, self.run_id()), self.task_names(),
                 self.run_info())
    return '/preview/%s/index.html' % self.run_id()

  def start_publish(self):
    with self.lock:
      if self.queue or self.current:
        raise RuntimeError('wait for the run to finish before publishing')
      if self.publishing.get('state') == 'running':
        raise RuntimeError('already publishing')
      self.publishing = {'state': 'running', 'detail': 'Starting', 'url': ''}
    args = (self.results_dir(), self.task_names(), self.run_info(), self.run_id())
    threading.Thread(target=self._publish, args=args, daemon=True).start()

  def _publish(self, *args):
    def step(text):
      with self.lock:
        self.publishing['detail'] = text
    try:
      url = publish.publish(*args, on_step=step)
      result = {'state': 'done', 'detail': 'Pull request opened. Merge it to put the run live.',
                'url': url}
    except Exception as e:  # noqa: BLE001 - shown on the page
      result = {'state': 'failed', 'detail': str(e)[-1500:], 'url': ''}
    with self.lock:
      self.publishing = result

  def shutdown(self):
    self.stop(now=True)
    device.stop_bridge()
    if self.env is not None:
      try:
        self.env.close()
      except Exception:  # noqa: BLE001
        pass
    if self.started_emulator:
      device.kill_emulator()


SUITE = None
PAGE_PATH = os.path.join(HERE, 'suite.html')


class Handler(http.server.BaseHTTPRequestHandler):

  def log_message(self, *args):
    pass

  def _send(self, code, body, ctype='application/json', headers=None):
    raw = body if isinstance(body, bytes) else body.encode('utf-8')
    self.send_response(code)
    self.send_header('Content-Type', ctype)
    self.send_header('Content-Length', str(len(raw)))
    for k, v in (headers or {}).items():
      self.send_header(k, v)
    self.end_headers()
    self.wfile.write(raw)

  def _json(self, code, payload):
    self._send(code, json.dumps(payload))

  def do_GET(self):  # noqa: N802 - BaseHTTPRequestHandler's naming
    path = urllib.parse.urlparse(self.path).path
    if path == '/':
      with open(PAGE_PATH, encoding='utf-8') as f:
        return self._send(200, f.read(), 'text/html; charset=utf-8')
    if path == '/theme.css':
      return self._send(200, theme.CSS, 'text/css; charset=utf-8')
    if path == '/api/state':
      return self._json(200, SUITE.state())
    if path.startswith('/results/'):
      return self._file(SUITE.results_dir(), urllib.parse.unquote(path[len('/results/'):]))
    if path.startswith('/preview/'):
      return self._file(PREVIEW, urllib.parse.unquote(path[len('/preview/'):]))
    if path == '/api/steps':
      task = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query).get('task', [''])[0]
      if task not in SUITE.task_names():
        return self._send(404, b'unknown task', 'text/plain')
      folder = os.path.join(SUITE.results_dir(), task)
      with SUITE.lock:
        cur = SUITE.current or {}
        marks = cur.get('session_marks') if cur.get('task') == task else None
      if marks is None:
        marks = (external_agent._read_json(os.path.join(folder, 'meta.json')) or {}).get('session_marks')
      body = report.steps_html(os.path.join(folder, 'session'),
                               '/results/%s/session/' % urllib.parse.quote(task), marks)
      return self._send(200, body, 'text/html; charset=utf-8')
    if path == '/screen.mjpg':
      return self._stream_screen()
    self._send(404, b'not found', 'text/plain')

  def _stream_screen(self):
    """The phone's display as an MJPEG stream: an <img> shows it live, no script needed."""
    self.send_response(200)
    self.send_header('Content-Type', 'multipart/x-mixed-replace; boundary=frame')
    self.send_header('Cache-Control', 'no-store')
    self.end_headers()
    try:
      for jpeg in SCREEN.frames():
        self.wfile.write(b'--frame\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\n\r\n'
                         % len(jpeg) + jpeg + b'\r\n')
    except (ConnectionError, OSError):
      pass  # the page closed or reloaded

  def _file(self, base, rel):
    """Serves a file under `base`, with Range so videos can seek."""
    base = os.path.abspath(base)
    full = os.path.abspath(os.path.join(base, rel))
    if not full.startswith(base + os.sep) or not os.path.isfile(full):
      return self._send(404, b'not found', 'text/plain')
    ctype = CONTENT_TYPES.get(os.path.splitext(full)[1].lower(), 'text/plain; charset=utf-8')
    size = os.path.getsize(full)
    rng = self.headers.get('Range', '')
    start, end = 0, size - 1
    if rng.startswith('bytes='):
      a, _, b = rng[6:].partition('-')
      start = int(a) if a.isdigit() else 0
      end = min(int(b), size - 1) if b.isdigit() else size - 1
    with open(full, 'rb') as f:
      f.seek(start)
      chunk = f.read(end - start + 1)
    if rng:
      return self._send(206, chunk, ctype, {'Accept-Ranges': 'bytes',
                                            'Content-Range': 'bytes %d-%d/%d' % (start, end, size)})
    self._send(200, chunk, ctype, {'Accept-Ranges': 'bytes'})

  def do_POST(self):  # noqa: N802
    path = urllib.parse.urlparse(self.path).path
    try:
      body = json.loads(self.rfile.read(int(self.headers.get('Content-Length') or 0)) or b'{}')
      if path == '/api/target':
        SUITE.choose(body.get('target'))
      elif path == '/api/setup':
        SUITE.start_setup()
      elif path == '/api/options':
        SUITE.set_options(body)
      elif path == '/api/run':
        SUITE.run(body.get('tasks') or [])
      elif path == '/api/stop':
        SUITE.stop(bool(body.get('now')))
      elif path == '/api/preview':
        return self._json(200, {'ok': True, 'url': SUITE.build_preview()})
      elif path == '/api/publish':
        SUITE.start_publish()
      else:
        return self._json(404, {'error': 'unknown route'})
      return self._json(200, {'ok': True})
    except (RuntimeError, ValueError, KeyError) as e:
      return self._json(409, {'error': str(e)})


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
  allow_reuse_address = True
  daemon_threads = True


def main():
  global SUITE
  if shutil.which('claude') is None:
    print('note: `claude` is not on PATH; the MCP target will fail its runs until it is.')
  SUITE = Suite()
  os.makedirs(RESULTS, exist_ok=True)
  # Loopback only: these routes start the emulator, drive adb and spend API credit.
  with Server(('127.0.0.1', PORT), Handler) as srv:
    url = 'http://localhost:%d' % PORT
    print('AURA Bench -> %s   (Ctrl+C to quit)' % url)
    webbrowser.open(url)
    try:
      srv.serve_forever()
    except KeyboardInterrupt:
      print('\nstopping…')
      SUITE.shutdown()


if __name__ == '__main__':
  main()
