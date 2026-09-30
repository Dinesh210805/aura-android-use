"""Host referee for running AndroidWorld from AURA's on-device eval cockpit.

    androidworld-venv/Scripts/python.exe scripts/bench/referee.py

The phone is the trigger; this is the referee. It has no web page - you start
tasks from Settings -> Eval on the device, and this process does the two things
the phone cannot:

    POST /prepare {"task": "MarkorCreateNote"}
        force-stops the app, wipes and re-seeds its data, pins the clock, starts
        screen recording, and returns the goal AndroidWorld generated for this
        seed.  ->  {"ok": true, "goal": "Create a note named ..."}

    POST /score {"task": "MarkorCreateNote"}
        stops recording, pulls the video and AURA's log, asks
        `task.is_successful(env)` for the verdict, tears the task down, and
        writes bench-results/<Task>/.  ->  {"ok": true, "success": false}

### Why the phone cannot do this itself

AndroidWorld never looks at what the agent did. It reads *device state* after
the fact - the row in the app's SQLite file, the note on disk, the setting that
should have flipped. That check and its mirror image (setup) are Python that
drives adb from a PC. Moving the trigger to the phone is possible; moving the
referee is not.

### It writes what report.py already reads

bench-results/<Task>/{meta.json, run.mp4, agent.log, referee.log, ...} - the
same layout bench_server.py produces, so `python scripts/bench/report.py` builds
the public site from these runs with no changes.

Port 8778, not 8777: bench_server.py's web UI can stay running alongside this so
the report page is available while a device-driven sweep is going.
"""

import http.server
import json
import os
import shutil
import socketserver
import sys
import threading
import time
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(
    0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..',
                    'android_world')
)

# Reuses the recorder, adb wrapper and results root the web UI already debugged
# rather than growing a second copy of each.
from bench_server import RESULTS, SEED, Recorder, adb, _write  # noqa: E402

PORT = 8778

# AndroidWorld prints check/cross emoji; the Windows console is cp1252 and dies
# on them, which would take the referee down mid-sweep.
if hasattr(sys.stdout, 'reconfigure'):
  sys.stdout.reconfigure(encoding='utf-8', errors='replace')
  sys.stderr.reconfigure(encoding='utf-8', errors='replace')

# AndroidWorld pins the device clock to 2023-10-15 for reproducibility, and TLS
# on a device that believes it is 2023 rejects today's certificates - AURA's
# model calls die before they are made. See aura_androidworld._shift_device_year
# for the two costs of shifting it.
DEVICE_YEAR = 2026

_env = None
_lock = threading.Lock()
# The single task in flight. /prepare fills it, /score empties it. Not a queue:
# the screen is single mutable ground truth, so two tasks at once would grade a
# device neither of them was looking at.
_current = None


def _boot():
  """Brings the emulator connection up once, and moves the clock into the present."""
  global _env
  from android_world.env import env_launcher
  import aura_androidworld

  print('connecting to the emulator...')
  _env = env_launcher.load_and_setup_env(
      console_port=5554,
      emulator_setup=False,
      adb_path=os.path.expandvars(
          r'%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe'),
  )
  aura_androidworld._shift_device_year(DEVICE_YEAR)  # noqa: SLF001 - our module
  print('ready. Start a run from the phone: Settings -> Eval -> AndroidWorld.')


def _make_task(name):
  """Instantiates one task with this run's seed, exactly as suite_utils would."""
  from android_world import registry
  from android_world import suite_utils
  reg = registry.TaskRegistry()
  suite = suite_utils.create_suite(
      reg.get_registry(family=registry.TaskRegistry.ANDROID_WORLD_FAMILY),
      n_task_combinations=1,
      seed=SEED,
      tasks=[name],
      use_identical_params=False,
  )
  instances = suite.get(name) or []
  if not instances:
    raise KeyError('%s is not in the AndroidWorld suite' % name)
  return instances[0]


def prepare(name):
  """Seeds the device for one task and starts the camera. Returns its goal."""
  global _current
  if _current is not None:
    # A prepare arriving while one is open means the previous run never scored -
    # the app was killed, or the sweep was aborted. Tear it down rather than
    # letting the stale recorder keep writing over the new task.
    _abandon('a new task was prepared before this one was scored')

  # Cleared, not merged: a re-run that kept the previous attempt's part*.mp4
  # leaves two runs' video side by side under one verdict.
  out = os.path.join(RESULTS, name)
  shutil.rmtree(out, ignore_errors=True)
  os.makedirs(out, exist_ok=True)

  task = _make_task(name)
  # Re-asserted per task: AndroidWorld's setup runs `svc wifi enable`, which
  # hands the emulator a fec0:: address. Android then prefers AAAA records, the
  # emulator has no IPv6 route, and every model call dies with ECONNREFUSED
  # having never tried IPv4.
  adb('shell', 'sysctl -w net.ipv6.conf.all.disable_ipv6=1')
  task.initialize_task(_env)
  adb('logcat', '-c')

  rec = Recorder(out)
  rec.start()
  _current = {
      'name': name, 'task': task, 'out': out, 'rec': rec,
      'started': time.time(), 'goal': task.goal,
  }
  print('prepared %s -> %s' % (name, task.goal))
  return task.goal


def score(name):
  """Stops the camera, asks AndroidWorld for the verdict, writes the result dir."""
  global _current
  if _current is None or _current['name'] != name:
    raise KeyError('%s was never prepared - nothing to score' % name)
  cur = _current
  _current = None

  videos = cur['rec'].stop()
  # `AuraAgentSpike` is not the debug receiver's tag despite the name - AuraAgent
  # and AuraVisionStrategy both log under it, so it follows the run whichever
  # door it came in by. The other two describe the cockpit path specifically:
  # the overlay that typed the goal, and the runner that queued it.
  agent_log = adb('logcat', '-d', '-s', 'AuraAgentSpike:I',
                  'AuraOverlayService:I', 'EvalRunner:I').stdout

  # The verdict, and the only thing here that is not bookkeeping. Never
  # second-guessed: whatever the task's own validator says about device state is
  # the number that gets published.
  try:
    success = bool(cur['task'].is_successful(_env) > 0.5)
    error = ''
  except Exception:  # noqa: BLE001 - recorded, not swallowed
    success, error = False, traceback.format_exc()
  finally:
    try:
      cur['task'].tear_down(_env)
    except Exception:  # noqa: BLE001 - a failed teardown must not lose a verdict
      error = (error + '\ntear_down: ' + traceback.format_exc()).strip()

  meta = {
      'task': name,
      'goal': cur['goal'],
      'started': cur['started'],
      'finished': time.time(),
      'run_time': round(time.time() - cur['started'], 1),
      'videos': videos,
      'seed': SEED,
      'success': success,
      'episode_length': 1,  # AURA runs its own loop; AndroidWorld sees one step.
      # AURA logs this whenever perceive_screen came back without an image. It
      # means the run happened on the accessibility tree alone, so the verdict
      # describes a blind agent - the report says so rather than hiding it.
      'blind': 'no extractable image' in agent_log,
      'error': error,
      'trigger': 'device',  # started from the cockpit, not bench_server's web UI
  }
  _write(os.path.join(cur['out'], 'agent.log'), agent_log)
  _write(os.path.join(cur['out'], 'meta.json'), json.dumps(meta, indent=2))
  print('scored %s -> %s' % (name, 'PASS' if success else 'FAIL'))
  return meta


def _abandon(why):
  """Drop a prepared task nobody scored, leaving the device usable."""
  global _current
  cur, _current = _current, None
  if cur is None:
    return
  print('abandoning %s: %s' % (cur['name'], why))
  try:
    cur['rec'].stop()
  except Exception:  # noqa: BLE001
    pass
  try:
    cur['task'].tear_down(_env)
  except Exception:  # noqa: BLE001
    pass


class Handler(http.server.BaseHTTPRequestHandler):

  def log_message(self, *args):
    pass  # the interesting lines are printed by prepare/score themselves

  def _send(self, payload):
    body = json.dumps(payload).encode()
    self.send_response(200)
    self.send_header('Content-Type', 'application/json')
    self.send_header('Content-Length', str(len(body)))
    self.end_headers()
    self.wfile.write(body)

  def do_POST(self):  # noqa: N802 - BaseHTTPRequestHandler's naming
    length = int(self.headers.get('Content-Length') or 0)
    try:
      body = json.loads(self.rfile.read(length) or b'{}')
    except ValueError:
      body = {}
    name = str(body.get('task') or '')
    route = self.path.strip('/')

    if route == 'ping':
      return self._send({'ok': True})

    # Serialised deliberately: prepare and score both drive one emulator, and
    # overlapping them would let one task's teardown wipe the next task's setup.
    with _lock:
      try:
        if route == 'prepare':
          return self._send({'ok': True, 'goal': prepare(name)})
        if route == 'score':
          meta = score(name)
          return self._send({
              'ok': True,
              'success': meta['success'],
              'detail': 'AndroidWorld scored this %s by inspecting device state.%s'
                        % ('PASS' if meta['success'] else 'FAIL',
                           ' Ran blind (no screenshot).' if meta['blind'] else ''),
          })
        if route == 'abandon':
          _abandon('asked to')
          return self._send({'ok': True})
      except Exception as exc:  # noqa: BLE001 - the phone needs the reason
        traceback.print_exc()
        return self._send({'ok': False, 'error': '%s: %s' % (type(exc).__name__, exc)})
    self._send({'ok': False, 'error': 'unknown route %r' % route})


class Server(socketserver.ThreadingTCPServer):
  allow_reuse_address = True
  daemon_threads = True


def _selfcheck():
  """Exercises the prepare/score state machine with fakes, no emulator needed.

  The thing worth protecting here is not the AndroidWorld call - that is one
  line and its own project's job - it is the handoff: score must refuse a task
  nobody prepared, a second prepare must not leave the first recorder running,
  and a validator that throws must still produce a meta.json rather than
  swallowing the run.
  """
  import tempfile

  global _current, _env
  _env = object()

  class FakeRec:
    def __init__(self, out):
      self.out, self.stopped = out, False

    def start(self):
      pass

    def stop(self):
      self.stopped = True
      return ['run.mp4']

  class FakeTask:
    goal = 'do the thing'

    def __init__(self, ok=True):
      self._ok, self.torn = ok, False

    def initialize_task(self, env):
      del env

    def is_successful(self, env):
      del env
      if not self._ok:
        raise RuntimeError('validator exploded')
      return 1.0

    def tear_down(self, env):
      del env
      self.torn = True

  with tempfile.TemporaryDirectory() as tmp:
    def open_task(name, ok=True):
      task = FakeTask(ok)
      rec = FakeRec(os.path.join(tmp, name))
      os.makedirs(rec.out, exist_ok=True)
      globals()['_current'] = {
          'name': name, 'task': task, 'out': rec.out, 'rec': rec,
          'started': time.time(), 'goal': task.goal,
      }
      return task, rec

    try:
      score('NeverPrepared')
    except KeyError:
      pass
    else:
      raise AssertionError('scoring an unprepared task must refuse, not invent a verdict')

    task, rec = open_task('Happy')
    meta = score('Happy')
    assert meta['success'] is True, 'a validator returning 1.0 must read as a pass'
    assert rec.stopped and task.torn, 'scoring must stop the camera and tear the task down'
    assert _current is None, 'a scored task must leave the slot free'
    assert os.path.isfile(os.path.join(rec.out, 'meta.json')), 'meta.json must be written'

    task, rec = open_task('Exploding', ok=False)
    meta = score('Exploding')
    assert meta['success'] is False and 'validator exploded' in meta['error'], \
        'a throwing validator must record the traceback, not lose the run'
    assert task.torn, 'tear_down must run even when the validator threw'

    _task, rec = open_task('Orphan')
    _abandon('selfcheck')
    assert rec.stopped and _current is None, \
        'an unscored task must be torn down, not left recording over the next one'

  print('referee self-check passed')


def main():
  if '--selfcheck' in sys.argv:
    return _selfcheck()
  os.makedirs(RESULTS, exist_ok=True)
  _boot()
  with Server(('0.0.0.0', PORT), Handler) as httpd:
    print('referee on http://localhost:%d  (the phone reaches it at 10.0.2.2:%d)'
          % (PORT, PORT))
    try:
      httpd.serve_forever()
    except KeyboardInterrupt:
      _abandon('referee stopped')
      print('\nbye')


if __name__ == '__main__':
  main()
