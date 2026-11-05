"""The benchmark suite: one browser page to set up, pick tasks, run them and watch.

    androidworld-venv/Scripts/python.exe scripts/bench/suite.py
    -> opens http://localhost:8777

Pick a target, and the page sets the machine up (`device.up`), builds
AndroidWorld's env once, then lists every scored task with its last result. Run
one, a selection, all, the failed ones, or resume. While tasks run, the page
shows the queue, the agent's live activity and the phone's live display
(`/screen.mjpg`, from the emulator's gRPC port), and films that browser window.

Targets:
- `mcp`: Claude Code drives the phone through AURA's MCP tools (profile
  `agents/claude-code-aura-mcp.json`). Results: bench-results/claude-code-aura-mcp/<Task>/.
- `ondevice`: AURA's own agent gets the goal over the debug broadcast.
  Results: bench-results/<Task>/, the folder `referee.py` and `report.py` already use.

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
import report  # noqa: E402
import theme  # noqa: E402
from aura_androidworld import RESULTS, ROOT, SEED, write_text  # noqa: E402

PORT = 8777
PROFILE = 'claude-code-aura-mcp'
ONDEVICE_TIMEOUT_S = 300
MAX_EVENTS = 600
TARGETS = {
    'mcp': {'label': 'MCP server', 'sub': 'Claude Code drives the phone through AURA\'s MCP tools'},
    'ondevice': {'label': 'On-device agent', 'sub': 'AURA\'s own agent, running on the phone'},
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


class Suite:
  """Everything the page shows. One instance; every field is guarded by `lock`."""

  def __init__(self):
    self.lock = threading.Lock()
    self.target = None
    self.setup = []
    self.setup_busy = False
    self.ready = False
    self.env = None
    self.started_emulator = False
    self.opts = {'model': 'sonnet', 'record': 'page' if desktop.available() else 'phone',
                 'timeout': None}
    self.queue = []
    self.run_tasks = []
    self.current = None
    self.stop_after = False
    self.cancel = threading.Event()
    self.session = {'passed': 0, 'failed': 0, 'stopped': 0, 'cost': 0.0}
    self.message = ''
    self.spec = external_agent.load_profile(PROFILE, {})

  # --- where results live --------------------------------------------------

  def results_dir(self):
    return os.path.join(RESULTS, PROFILE) if self.target == 'mcp' else RESULTS

  def timeout(self):
    if self.opts['timeout']:
      return int(self.opts['timeout'])
    return self.spec.get('timeout_s', 600) if self.target == 'mcp' else ONDEVICE_TIMEOUT_S

  def rows(self):
    rows = []
    base = self.results_dir()
    for i, name in enumerate(sorted(aura_androidworld.scored_tasks()), 1):
      m = external_agent._read_json(os.path.join(base, name, 'meta.json'))
      agent = m.get('agent') or {}
      rows.append({
          'n': i, 'task': name, 'goal': m.get('goal', ''), 'success': m.get('success'),
          'validator': m.get('validator_success'), 'run_time': m.get('run_time'),
          'cost': (agent.get('usage') or {}).get('cost_usd'),
          'timed_out': agent.get('timed_out'), 'stopped': agent.get('stopped'),
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
    known = set(aura_androidworld.scored_tasks())
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
      if self.current is not None and kind == 'goal':
        self.current['goal'] = text
      elif self.current is not None:
        events = self.current['events']
        events.append({'t': round(time.time() - self.current['started'], 1), 'kind': kind,
                       'text': text})
        del events[:-MAX_EVENTS]

  def _make_agent(self, out):
    recorder = RECORDERS[self.opts['record']]
    if self.target == 'ondevice':
      return OnDeviceAgent(self.env, out, self.timeout(), recorder, self._push, self.cancel)
    spec = external_agent.load_profile(PROFILE, {'model': self.opts['model']})
    return external_agent.ExternalAgent(self.env, spec, out, self.timeout(), recorder=recorder,
                                        on_event=self._push, cancel=self.cancel)

  def _worker(self):
    tasks = aura_androidworld.scored_tasks()
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
                          'timeout': self.timeout(), 'events': []}
        if self.target == 'mcp' and not device.phone_reachable():
          self._push('info', 'bridge lost the phone; reconnecting…')
          device.wait_phone()  # raises SetupError: the run stops, nothing is filed as a FAIL
        meta = external_agent.run_one(self.env, self._make_agent, name, tasks, SEED,
                                      self.results_dir(), PROFILE if self.target == 'mcp' else 'aura')
        with self.lock:
          events = list(self.current['events'])
        write_text(os.path.join(self.results_dir(), name, 'live.log'),
                   'Goal: %s\n\n' % meta['goal'] + ''.join(
                       '%7.1fs  %-5s %s\n' % (e['t'], e['kind'], e['text']) for e in events))
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
        cur['elapsed'] = round(time.time() - cur['started'], 1)
      return {
          'target': self.target, 'targets': TARGETS, 'setup': [dict(s) for s in self.setup],
          'setup_busy': self.setup_busy, 'ready': self.ready, 'opts': dict(self.opts),
          'models': self.spec.get('models', []), 'desktop_ok': desktop.available(),
          'queue': list(self.queue), 'run_tasks': list(self.run_tasks), 'current': cur, 'session': dict(self.session),
          'message': self.message, 'timeout': self.timeout() if self.target else None,
          'tasks': self.rows() if self.target else [],
      }

  def set_options(self, opts):
    with self.lock:
      if 'model' in opts and opts['model'] in self.spec.get('models', []):
        self.opts['model'] = opts['model']
      if 'record' in opts and opts['record'] in RECORDERS:
        if opts['record'] == 'page' and not desktop.available():
          raise ValueError('page recording needs Windows and ffmpeg')
        self.opts['record'] = opts['record']
      if 'timeout' in opts:
        t = opts['timeout']
        self.opts['timeout'] = int(t) if t not in (None, '', 0) and 30 <= int(t) <= 3600 else None

  def build_report(self):
    site = os.path.join(ROOT, 'bench-site', PROFILE if self.target == 'mcp' else '')
    return report.build(self.results_dir(), site)

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
      return self._file(urllib.parse.unquote(path[len('/results/'):]))
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

  def _file(self, rel):
    """Serves a file from the current target's results, with Range so videos can seek."""
    base = os.path.abspath(SUITE.results_dir())
    full = os.path.abspath(os.path.join(base, rel))
    if not full.startswith(base + os.sep) or not os.path.isfile(full):
      return self._send(404, b'not found', 'text/plain')
    ctype = 'video/mp4' if full.endswith('.mp4') else 'text/plain; charset=utf-8'
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
      elif path == '/api/report':
        return self._json(200, {'ok': True, 'path': SUITE.build_report()})
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
