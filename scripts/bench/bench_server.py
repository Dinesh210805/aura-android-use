"""Local control room for running AndroidWorld tasks against AURA one at a time.

    androidworld-venv/Scripts/python.exe scripts/bench/bench_server.py
    -> http://localhost:8777

Why a server and not a loop over 116 tasks: a benchmark run you cannot watch is
a benchmark run you cannot trust. Each task here is started by hand, screen-
recorded start to finish, and its logs kept next to the verdict, so a failure
can be looked at rather than guessed at.

Everything a task produces lands in `bench-results/<Task>/`:

    meta.json    verdict, runtime, seed, and whether AURA had vision
    run.mp4      the screen recording (or part0.mp4, part1.mp4 - see below)
    agent.log    AURA's own log for that window
    harness.log  what the adapter subprocess printed
    checkpoint/  AndroidWorld's raw episode pickle, untouched

That directory is the single source of truth. `report.py` turns it into a
static site and reads nothing else, so a task can be re-run on its own and the
report rebuilt without touching anything that already passed.
"""

import glob
import gzip
import http.server
import json
import os
import pickle
import shutil
import socketserver
import subprocess
import sys
import threading
import time
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(
    0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..',
                    'android_world')
)

import report  # noqa: E402
import theme  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
RESULTS = os.path.join(ROOT, 'bench-results')
ADB = os.path.expandvars(r'%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe')
PORT = 8777
SERIAL = 'emulator-5554'
GOAL_TIMEOUT = 300
SEED = 42

# screenrecord refuses anything above 180s, so a long task is recorded as
# consecutive chunks and stitched afterwards. Capping the task at 180s instead
# would mean changing the benchmark to suit the recorder.
CHUNK_SECONDS = 180

_state = {'running': None, 'started': 0, 'line': ''}
_lock = threading.Lock()


def adb(*args):
  return subprocess.run(
      [ADB, '-s', SERIAL, *args], capture_output=True, text=True,
      errors='replace',
  )


def all_tasks():
  from android_world import registry
  reg = registry.TaskRegistry()
  return sorted(reg.get_registry(family=reg.ANDROID_WORLD_FAMILY))


def load_meta(task):
  path = os.path.join(RESULTS, task, 'meta.json')
  if not os.path.exists(path):
    return None
  try:
    with open(path, encoding='utf-8') as f:
      return json.load(f)
  except (OSError, ValueError):
    return None


class Recorder:
  """Screen-records for as long as a task runs, in 180-second chunks."""

  def __init__(self, out_dir):
    self._out_dir = out_dir
    self._stop = threading.Event()
    self._thread = None
    self._remote = []
    self._notes = []
    self.parts = []

  def start(self):
    adb('shell', 'rm -f /sdcard/bench_*.mp4')
    self._thread = threading.Thread(target=self._loop, daemon=True)
    self._thread.start()

  def _loop(self):
    i = 0
    while not self._stop.is_set():
      remote = '/sdcard/bench_%d.mp4' % i
      began = time.time()
      proc = subprocess.Popen(
          [ADB, '-s', SERIAL, 'shell', 'screenrecord',
           '--bit-rate', '4M', '--size', '720x1600',
           '--time-limit', str(CHUNK_SECONDS), remote],
          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
          errors='replace',
      )
      while proc.poll() is None and not self._stop.is_set():
        time.sleep(0.4)
      if proc.poll() is None:
        # SIGINT makes screenrecord write the moov atom and close the file.
        # Killing it outright leaves an mp4 no player will open.
        adb('shell', 'pkill -INT screenrecord')
        try:
          proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
          proc.kill()
      said = (proc.stdout.read() if proc.stdout else '') or ''
      lasted = time.time() - began
      self._notes.append('chunk %d: %.1fs, exit %s %s'
                         % (i, lasted, proc.returncode, said.strip()))
      self._remote.append(remote)
      i += 1
      # screenrecord dies on its own for reasons it does not always explain -
      # a display-config change is the usual one. Restarting instantly would
      # spin, producing a pile of empty files instead of one visible symptom.
      if lasted < 2 and not self._stop.is_set():
        time.sleep(1.0)

  def stop(self):
    self._stop.set()
    if self._thread:
      self._thread.join(timeout=60)
    # The device file is only complete once screenrecord has exited; pulling
    # while it still holds the handle yields a truncated, unplayable mp4.
    time.sleep(1.5)
    for i, remote in enumerate(self._remote):
      size = adb('shell', 'stat -c %%s %s 2>/dev/null' % remote).stdout.strip()
      if not size.isdigit() or int(size) < 1024:
        continue
      local = os.path.join(self._out_dir, 'part%d.mp4' % i)
      adb('pull', remote, local)
      if os.path.exists(local):
        self.parts.append(os.path.basename(local))
    adb('shell', 'rm -f /sdcard/bench_*.mp4')
    with open(os.path.join(self._out_dir, 'recorder.log'), 'w',
              encoding='utf-8') as f:
      f.write('\n'.join(self._notes))
    return self._finalise()

  def _finalise(self):
    """One part becomes run.mp4; several are concatenated if ffmpeg exists."""
    if not self.parts:
      return []
    if len(self.parts) == 1:
      os.replace(os.path.join(self._out_dir, self.parts[0]),
                 os.path.join(self._out_dir, 'run.mp4'))
      return ['run.mp4']
    if shutil.which('ffmpeg'):
      listing = os.path.join(self._out_dir, 'parts.txt')
      with open(listing, 'w', encoding='utf-8') as f:
        for p in self.parts:
          f.write("file '%s'\n" % p)
      done = subprocess.run(
          ['ffmpeg', '-y', '-f', 'concat', '-safe', '0', '-i', 'parts.txt',
           '-c', 'copy', 'run.mp4'],
          capture_output=True, cwd=self._out_dir,
      )
      os.remove(listing)
      if done.returncode == 0:
        for p in self.parts:
          os.remove(os.path.join(self._out_dir, p))
        return ['run.mp4']
    return self.parts  # no ffmpeg: the report shows them as part 1, part 2, ...


def run_task(task):
  """Runs one task with the camera rolling, and writes its result directory."""
  out = os.path.join(RESULTS, task)
  ckpt = os.path.join(out, 'checkpoint')
  shutil.rmtree(out, ignore_errors=True)
  os.makedirs(ckpt, exist_ok=True)

  started = time.time()
  adb('logcat', '-c')
  rec = Recorder(out)
  rec.start()

  proc = subprocess.run(
      [sys.executable,
       os.path.join(ROOT, 'scripts', 'bench', 'aura_androidworld.py'),
       '--tasks=%s' % task, '--checkpoint_dir=%s' % ckpt,
       '--goal_timeout=%d' % GOAL_TIMEOUT, '--task_random_seed=%d' % SEED],
      capture_output=True, text=True, errors='replace', cwd=ROOT,
  )
  videos = rec.stop()

  agent_log = adb('logcat', '-d', '-s', 'AuraAgentSpike:I').stdout
  _write(os.path.join(out, 'agent.log'), agent_log)
  _write(os.path.join(out, 'harness.log'), proc.stdout + '\n' + proc.stderr)

  meta = {
      'task': task,
      'started': started,
      'finished': time.time(),
      'videos': videos,
      'seed': SEED,
      # AURA logs this whenever perceive_screen came back without an image. It
      # means the run happened on the accessibility tree alone, so the verdict
      # describes a blind agent - the report says so rather than hiding it.
      'blind': 'no extractable image' in agent_log,
      'aura_line': _last_result_line(agent_log),
  }
  meta.update(_read_episode(ckpt))
  _write(os.path.join(out, 'meta.json'), json.dumps(meta, indent=2))
  return meta


def _write(path, text):
  with open(path, 'w', encoding='utf-8') as f:
    f.write(text)


def _last_result_line(log):
  for line in reversed(log.splitlines()):
    if 'Agent result:' in line or 'threw' in line or 'quota' in line:
      return line.split(': ', 2)[-1].strip()
  return ''


def _read_episode(ckpt):
  """AndroidWorld's own pickle is the verdict. Never second-guess it.

  The pickle is written seconds earlier by the adapter subprocess this server
  just spawned, into a directory this server created. It is our own output on
  our own disk, not third-party data, which is what makes unpickling it safe
  here. Never point this at a checkpoint someone else produced.
  """
  found = glob.glob(os.path.join(ckpt, '*.pkl.gz'))
  if not found:
    return {'success': None, 'goal': '', 'run_time': 0,
            'error': 'no episode written - the harness died before scoring'}
  with gzip.open(found[0], 'rb') as f:
    episodes = pickle.load(f)
  ep = episodes[0]
  return {
      'success': bool(ep.get('is_successful', 0)),
      'goal': ep.get('goal', ''),
      'run_time': round(float(ep.get('run_time', 0)), 1),
      'episode_length': ep.get('episode_length'),
      'error': str(ep.get('exception_info') or ''),
  }


def _worker(task):
  try:
    run_task(task)
  except Exception as exc:  # noqa: BLE001 - surfaced in the UI, not swallowed
    with _lock:
      _state['line'] = '%s crashed: %s' % (task, exc)
  finally:
    with _lock:
      _state['running'] = None


class Handler(http.server.BaseHTTPRequestHandler):

  def log_message(self, *args):
    pass

  def _send(self, code, body, ctype='application/json'):
    raw = body if isinstance(body, bytes) else body.encode('utf-8')
    self.send_response(code)
    self.send_header('Content-Type', ctype)
    self.send_header('Content-Length', str(len(raw)))
    self.end_headers()
    self.wfile.write(raw)

  def do_GET(self):  # noqa: N802 - BaseHTTPRequestHandler's naming
    path = urllib.parse.urlparse(self.path).path
    if path == '/':
      return self._send(200, PAGE, 'text/html; charset=utf-8')
    if path == '/api/tasks':
      rows = []
      for i, t in enumerate(all_tasks(), 1):
        m = load_meta(t) or {}
        rows.append({'n': i, 'task': t, 'success': m.get('success'),
                     'blind': m.get('blind'), 'goal': m.get('goal', ''),
                     'run_time': m.get('run_time')})
      with _lock:
        running = _state['running']
      return self._send(200, json.dumps({'tasks': rows, 'running': running}))
    if path.startswith('/results/'):
      rel = urllib.parse.unquote(path[len('/results/'):])
      full = os.path.normpath(os.path.join(RESULTS, rel))
      if not full.startswith(RESULTS) or not os.path.isfile(full):
        return self._send(404, b'not found', 'text/plain')
      ctype = ('video/mp4' if full.endswith('.mp4')
               else 'text/plain; charset=utf-8')
      with open(full, 'rb') as f:
        return self._send(200, f.read(), ctype)
    return self._send(404, b'not found', 'text/plain')

  def do_POST(self):  # noqa: N802
    path = urllib.parse.urlparse(self.path).path
    length = int(self.headers.get('Content-Length', 0))
    body = json.loads(self.rfile.read(length) or b'{}')
    if path == '/api/run':
      with _lock:
        if _state['running']:
          return self._send(409, json.dumps({'error': 'a task is running'}))
        _state.update(running=body['task'], started=time.time(), line='')
      threading.Thread(target=_worker, args=(body['task'],),
                       daemon=True).start()
      return self._send(202, json.dumps({'ok': True}))
    if path == '/api/report':
      out = report.build(RESULTS, os.path.join(ROOT, 'bench-site'))
      return self._send(200, json.dumps({'path': out}))
    return self._send(404, json.dumps({'error': 'unknown'}))


PAGE = r"""<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>AURA - AndroidWorld</title><style>__CSS__
.ctl { display:flex; gap:10px; align-items:center; }
.live { display:inline-flex; align-items:center; gap:7px; font-size:13px;
        color:var(--blood); font-weight:550; white-space:nowrap; }
.dot { width:8px; height:8px; border-radius:50%; background:var(--blood);
       animation: pulse 1.1s ease-in-out infinite; }
@keyframes pulse { 0%,100%{opacity:1} 50%{opacity:.25} }
.filter { border:1px solid var(--outline); background:var(--canvas-high);
          border-radius:999px; padding:7px 16px; font:inherit; font-size:13px;
          outline:none; }
.filter:focus { border-color:var(--ink); }
.row a.open { font-size:12px; color:var(--text-dim); text-decoration:none;
              font-family:var(--mono); }
.row a.open:hover { color:var(--text); }
</style></head><body><div class="wrap">
<div class="mast">
  <div><h1>AndroidWorld &middot; AURA</h1>
    <div class="sub" id="sub">loading&hellip;</div></div>
  <div class="ctl">
    <input class="filter" id="q" placeholder="filter tasks">
    <button class="ghost" onclick="mkReport()">Collective report</button>
  </div>
</div>
<div id="banner"></div>
<div class="rows" id="rows"></div>
<footer>One task at a time, screen-recorded start to finish, logs kept beside
the verdict in <code>bench-results/</code>.</footer>
</div><script>
let running = null;
function esc(s) { const d = document.createElement('div');
  d.textContent = s || ''; return d.innerHTML; }
async function load() {
  const d = await (await fetch('/api/tasks')).json();
  running = d.running;
  const done = d.tasks.filter(t => t.success === true || t.success === false);
  const pass = done.filter(t => t.success);
  const blind = done.filter(t => t.blind);
  document.getElementById('sub').textContent =
    done.length + ' of ' + d.tasks.length + ' run · ' + pass.length +
    ' passed' + (running ? ' · running ' + running : '');
  document.getElementById('banner').innerHTML = blind.length
    ? '<div class="banner"><b>' + blind.length + ' of ' + done.length +
      ' completed tasks ran without vision.</b> AURA\'s screenshot call failed,' +
      ' so it worked from the accessibility tree alone. Those verdicts describe' +
      ' a blind agent.</div>' : '';
  const q = document.getElementById('q').value.toLowerCase();
  document.getElementById('rows').innerHTML = d.tasks
    .filter(t => t.task.toLowerCase().includes(q)).map(row).join('');
}
function row(t) {
  const ran = t.success === true || t.success === false;
  const verdict = t.success === true ? '<span class="pill pass">pass</span>'
    : t.success === false ? '<span class="pill fail">fail</span>'
    : '<span class="pill none">not run</span>';
  const blind = t.blind ? '<span class="pill blind">blind</span> ' : '';
  const mid = running === t.task
    ? '<span class="live"><span class="dot"></span>recording</span>'
    : '<span>' + blind + verdict + '</span>';
  return '<div class="row' + (ran ? '' : ' pending') + '">' +
    '<span class="n">' + String(t.n).padStart(3, '0') + '</span>' +
    '<span><span class="name">' + t.task + '</span>' +
      (t.goal ? '<span class="goal">' + esc(t.goal) + '</span>' : '') +
    '</span>' + mid +
    '<button ' + (running ? 'disabled' : '') + ' onclick="run(\'' + t.task +
      '\')">' + (ran ? 'Re-run' : 'Run') + '</button></div>';
}
async function run(task) {
  running = task; load();
  await fetch('/api/run', {method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({task: task})});
}
async function mkReport() {
  const r = await (await fetch('/api/report', {method: 'POST',
    headers: {'Content-Type': 'application/json'}, body: '{}'})).json();
  alert('Static site written to:\n' + r.path +
        '\n\nOpen index.html, or host that folder as-is.');
}
document.getElementById('q').addEventListener('input', load);
load(); setInterval(load, 4000);
</script></body></html>""".replace('__CSS__', theme.CSS)


def main():
  os.makedirs(RESULTS, exist_ok=True)
  socketserver.TCPServer.allow_reuse_address = True
  with socketserver.ThreadingTCPServer(('127.0.0.1', PORT), Handler) as srv:
    print('AndroidWorld control room -> http://localhost:%d' % PORT)
    print('Results -> %s' % RESULTS)
    srv.serve_forever()


if __name__ == '__main__':
  main()
