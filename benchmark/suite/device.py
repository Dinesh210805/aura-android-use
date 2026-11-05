"""The benchmark machine: emulator, adb, the phone's screen recorder, and the bench bridge.

    androidworld-venv/Scripts/python.exe benchmark/suite/device.py up

`up()` brings everything a run needs to a known state, one step at a time, and
raises `SetupError` with the exact thing to do when a step cannot be fixed
automatically. `suite.py` shows those steps as its setup checklist.

`PhoneScreen` streams the emulator's display to the suite page.

The bench bridge is a second `aura-mcp daemon` on port 4817 with its own home
directory (`benchmark/.runtime/bridge-home`), paired with the emulator. It never
touches the user's normal bridge on 4816 or its pairing with a real phone.
- Reads/Writes: `benchmark/.runtime/bridge-home/.aura/webrtc.json` (the bench pairing
  token; keep private), adb forwards for the phone's signalling ports.
- Why adb forwards: the emulator sits behind its own NAT, so the PC cannot reach
  its signalling port directly. WebRTC itself does connect once signalling has.
"""

import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, '..', '..', 'android_world'))

import aura_androidworld  # noqa: E402

AVD = 'AndroidWorldAvd'
SERIAL = 'emulator-5554'
GRPC_PORT = 8554
# ANGLE over Direct3D, still GPU-rendered. Why not `host`: on this setup the host-GPU path
# starts failing glReadPixels (0x506) mid-session and every screenshot comes back plain
# white, which blinds the agent, the recorder and the live view alike.
EMULATOR_GPU = 'angle_indirect'
AURA_PACKAGE = 'com.aura.aura_ui.feature.debug'
SPIKE_ACTION = 'com.aura.aura_ui.AGENT_SPIKE'
# Change together: LanPolicy.PORTS on the phone and PORTS in aura-mcp-connect/src/lan.js.
PHONE_SIGNAL_PORTS = (47821, 47822, 47823, 47824, 47825)
BRIDGE_PORT = 4817
BRIDGE_HOME = os.path.join(aura_androidworld.RUNTIME, 'bridge-home')
BRIDGE_URL = 'http://127.0.0.1:%d/mcp' % BRIDGE_PORT
ADB = aura_androidworld.find_adb()

# screenrecord refuses anything above 180s, so a long task is recorded as
# consecutive chunks and stitched afterwards. Capping the task at 180s instead
# would mean changing the benchmark to suit the recorder.
CHUNK_SECONDS = 180

_bridge_proc = None


class SetupError(RuntimeError):
  """A precondition failed. The message says what to do."""


def adb(*args, timeout=60):
  return subprocess.run([ADB, '-s', SERIAL, *args], capture_output=True, text=True,
                        encoding='utf-8', errors='replace', timeout=timeout)


def _detached():
  if os.name == 'nt':
    return {'creationflags': subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP}
  return {'start_new_session': True}


# --- emulator --------------------------------------------------------------

def find_emulator():
  found = shutil.which('emulator')
  if found:
    return found
  exe = 'emulator.exe' if os.name == 'nt' else 'emulator'
  for sdk in (os.environ.get('ANDROID_HOME'), os.environ.get('ANDROID_SDK_ROOT'),
              os.path.expandvars(r'%LOCALAPPDATA%\Android\Sdk'),
              os.path.expanduser('~/Android/Sdk'), os.path.expanduser('~/Library/Android/sdk')):
    if sdk and os.path.isfile(os.path.join(sdk, 'emulator', exe)):
      return os.path.join(sdk, 'emulator', exe)
  raise SetupError('emulator not found. Install the Android Emulator or set ANDROID_HOME.')


def emulator_attached():
  out = subprocess.run([ADB, 'devices'], capture_output=True, text=True, encoding='utf-8', errors='replace').stdout
  return any(line.split()[:2] == [SERIAL, 'device'] for line in out.splitlines() if line.strip())


def boot_emulator(timeout=300):
  """Starts the AVD, with its window, unless it is already attached. Returns True if this call started it.

  Contract: booted means `sys.boot_completed` is 1. The window is where a person
  uses the phone by hand; the suite page only mirrors the display (PhoneScreen).
  Nothing here resizes that window: a host-GPU emulator window that is resized
  programmatically can stop drawing until the emulator restarts.
  """
  if emulator_attached():
    return False
  cmd = [find_emulator(), '-avd', AVD, '-no-snapshot', '-grpc', str(GRPC_PORT), '-no-audio',
         '-gpu', EMULATOR_GPU]
  os.makedirs(aura_androidworld.RUNTIME, exist_ok=True)
  log = open(os.path.join(aura_androidworld.RUNTIME, 'emulator.log'), 'w', encoding='utf-8')
  subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT, **_detached())
  deadline = time.time() + timeout
  while time.time() < deadline:
    time.sleep(3)
    if emulator_attached() and adb('shell', 'getprop sys.boot_completed').stdout.strip() == '1':
      time.sleep(5)  # launcher and system services settle after the flag flips
      return True
  raise SetupError('the emulator did not finish booting in %ds. See benchmark/.runtime/emulator.log, '
                   'and check that AVD "%s" starts from Android Studio.' % (timeout, AVD))


def kill_emulator():
  subprocess.run([ADB, '-s', SERIAL, 'emu', 'kill'], capture_output=True)


# --- AURA on the phone -----------------------------------------------------

def require_aura():
  if AURA_PACKAGE not in adb('shell', 'pm list packages %s' % AURA_PACKAGE).stdout:
    raise SetupError('AURA (%s) is not installed. Run `cd aura-android && ./gradlew '
                     ':app:installDebug`, open it once, finish onboarding and turn on its '
                     'accessibility service.' % AURA_PACKAGE)


def open_aura():
  adb('shell', 'monkey -p %s -c android.intent.category.LAUNCHER 1' % AURA_PACKAGE)
  time.sleep(4)


def _spike_receiver_listening():
  adb('logcat', '-c')
  adb('shell', 'am broadcast -a %s' % SPIKE_ACTION)  # no goal: answers, runs nothing
  time.sleep(2)
  return 'Missing --es goal' in adb('logcat', '-d', '-s', 'AuraAgentSpike:E').stdout


def require_aura_listening():
  """Makes sure AURA's debug receiver is up, opening AURA once if it is not."""
  if _spike_receiver_listening():
    return
  open_aura()
  deadline = time.time() + 40  # after a cold boot the app and its service take a while
  while time.time() < deadline:
    if _spike_receiver_listening():
      return
    time.sleep(3)
  if not _spike_receiver_listening():
    raise SetupError('AURA is not listening on %s. Install the current debug build, open AURA '
                     'once on the emulator and finish onboarding.' % SPIKE_ACTION)


def resume_control_lock():
  """Hands control back to AURA if its human-takeover lock is set.

  Why: the lock reads any screen change it did not cause as a person taking the
  phone back. AndroidWorld's adb seeding is one, so without this the agent's
  first call can be refused with paused_by_user. Explicit component, because
  Android does not deliver implicit broadcasts to manifest receivers.
  Change together: CompanionTestReceiver.runPause.
  """
  adb('shell', 'am broadcast -n %s/com.aura.aura_ui.debugtools.CompanionTestReceiver '
      '-a com.aura.aura_ui.debug.COMPANION_TEST --es mode pause --es op resume' % AURA_PACKAGE)


def start_phone_mcp():
  """Starts AURA's MCP server on the phone without touching the UI. Idempotent.

  Why a broadcast: AssistantForegroundService is not exported, so a shell cannot
  start it. Its debug-only AGENT_SPIKE receiver answers `--es mode mcp_start`.
  Change together: AssistantForegroundService.agentSpikeReceiver.
  """
  require_aura_listening()
  adb('logcat', '-c')
  adb('shell', 'am broadcast -a %s --es mode mcp_start' % SPIKE_ACTION)
  deadline = time.time() + 20
  while time.time() < deadline:
    time.sleep(1)
    if 'MCP start requested' in adb('logcat', '-d', '-s', 'AuraAgentSpike:I').stdout:
      return
  raise SetupError('AURA did not start its MCP server. Check `adb logcat -s AuraAgentSpike '
                   'AssistantService`; an older debug build lacks the mcp_start mode.')


def _ui_nodes():
  """The current screen as [(text, content-desc, (x, y) centre)]."""
  adb('shell', 'uiautomator dump /sdcard/aw_ui.xml')
  raw = adb('shell', 'cat /sdcard/aw_ui.xml').stdout
  try:
    root = ET.fromstring(raw[raw.find('<'):])
  except ET.ParseError:
    return []
  nodes = []
  for n in root.iter('node'):
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get('bounds', ''))
    if m:
      x1, y1, x2, y2 = map(int, m.groups())
      nodes.append((n.get('text', ''), n.get('content-desc', ''), ((x1 + x2) // 2, (y1 + y2) // 2)))
  return nodes


def _tap(centre):
  adb('shell', 'input tap %d %d' % centre)


def read_pairing_pin():
  """Opens AURA's MCP tab and reads the one-time PIN it shows. Raises if it cannot find it."""
  open_aura()
  tab = next((c for t, d, c in _ui_nodes() if d == 'MCP'), None)
  if tab is None:
    raise SetupError('could not find the MCP tab in AURA to read the pairing PIN.')
  _tap(tab)
  time.sleep(2)
  for _ in range(4):
    for text, _desc, _c in _ui_nodes():
      m = re.search(r'aura-mcp pair (\d{6})', text)
      if m:
        return m.group(1)
    adb('shell', 'input swipe 540 1700 540 800 300')
    time.sleep(1.5)
  raise SetupError('the MCP tab shows no pairing PIN. Is the MCP server running on the phone?')


def phone_signal_port():
  """Forwards the phone's signalling ports to this PC and returns the one AURA answers on."""
  for port in PHONE_SIGNAL_PORTS:
    adb('forward', 'tcp:%d' % port, 'tcp:%d' % port)
  for port in PHONE_SIGNAL_PORTS:
    try:
      with urllib.request.urlopen('http://127.0.0.1:%d/aura/info' % port, timeout=2) as r:
        if json.load(r).get('service') == 'aura-mcp':
          return port
    except (OSError, ValueError):
      continue
  raise SetupError('the phone\'s MCP signalling port does not answer through adb forward. '
                   'Is the MCP server running on the phone?')


# --- the bench bridge ------------------------------------------------------

def _bridge_env():
  env = dict(os.environ)
  # os.homedir() reads USERPROFILE on Windows and HOME elsewhere: this is what
  # keeps the bench pairing away from the user's own ~/.aura/webrtc.json.
  env.update(USERPROFILE=BRIDGE_HOME, HOME=BRIDGE_HOME, AURA_MCP_PORT=str(BRIDGE_PORT))
  return env


def _aura_mcp():
  exe = shutil.which('aura-mcp')
  if not exe:
    raise SetupError('aura-mcp not found. Run: npm install -g aura-mcp-connect@latest')
  return exe


def bench_paired():
  return os.path.isfile(os.path.join(BRIDGE_HOME, '.aura', 'webrtc.json'))


def pair_bench_bridge(port):
  """Pairs the bench bridge with the emulator: reads the PIN and approves on-screen.

  The approval is only tapped when the code on the phone equals the code the
  bridge printed, which is the check a person is asked to make.
  """
  os.makedirs(BRIDGE_HOME, exist_ok=True)
  pin = read_pairing_pin()
  proc = subprocess.Popen([_aura_mcp(), 'pair', pin, '--host=127.0.0.1:%d' % port],
                          env=_bridge_env(), stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          text=True, encoding='utf-8', errors='replace')
  lines = []
  reader = threading.Thread(target=lambda: lines.extend(proc.stdout), daemon=True)
  reader.start()
  deadline = time.time() + 60
  approved = False
  while time.time() < deadline and proc.poll() is None:
    time.sleep(2)
    code = re.search(r'Verification code:\s*([\d ]+)', ''.join(lines))
    if not code or approved:
      continue
    nodes = _ui_nodes()
    if any(t.strip() == code.group(1).strip() for t, _d, _c in nodes):
      button = next((c for t, _d, c in nodes if t == 'Approve'), None)
      if button:
        _tap(button)
        approved = True
  try:
    proc.wait(timeout=20)
  except subprocess.TimeoutExpired:
    proc.kill()
  reader.join(timeout=5)
  if proc.returncode != 0 or not bench_paired():
    raise SetupError('pairing the bench bridge failed:\n%s' % ''.join(lines)[-800:])


def bridge_health():
  """The bench daemon's /health as a dict, or None if it is not answering."""
  try:
    with urllib.request.urlopen('http://127.0.0.1:%d/health' % BRIDGE_PORT, timeout=2) as r:
      return json.load(r)
  except (OSError, ValueError):
    return None


def start_bridge():
  """Starts the bench daemon unless it already answers. It is stopped by stop_bridge()."""
  global _bridge_proc
  if bridge_health() is not None:
    return
  log = open(os.path.join(BRIDGE_HOME, 'daemon.log'), 'w', encoding='utf-8')
  _bridge_proc = subprocess.Popen([_aura_mcp(), 'daemon'], env=_bridge_env(), stdout=log,
                                  stderr=subprocess.STDOUT, **_detached())
  deadline = time.time() + 20
  while time.time() < deadline:
    time.sleep(1)
    if bridge_health() is not None:
      return
  raise SetupError('the bench bridge did not start. See benchmark/.runtime/bridge-home/daemon.log.')


def stop_bridge():
  if _bridge_proc and _bridge_proc.poll() is None:
    if os.name == 'nt':
      subprocess.run(['taskkill', '/PID', str(_bridge_proc.pid), '/T', '/F'], capture_output=True)
    else:
      _bridge_proc.terminate()


def wait_phone(timeout=45):
  """Opens an MCP session (the daemon connects lazily) and waits for the phone to be reached."""
  body = json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': 'initialize', 'params': {
      'protocolVersion': '2025-03-26', 'capabilities': {},
      'clientInfo': {'name': 'aura-bench-preflight', 'version': '1'}}}).encode()
  req = urllib.request.Request(BRIDGE_URL, data=body, method='POST', headers={
      'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream'})
  try:
    urllib.request.urlopen(req, timeout=30).read()
  except OSError:
    pass  # the health poll below is the verdict
  deadline = time.time() + timeout
  health = None
  while time.time() < deadline:
    health = bridge_health()
    if (health or {}).get('phone') == 'connected':
      return health
    time.sleep(2)
  raise SetupError('the bench bridge cannot reach the phone. /health says: %s. See '
                   'benchmark/.runtime/bridge-home/daemon.log.' % json.dumps(health))


def phone_reachable():
  return (bridge_health() or {}).get('phone') == 'connected'


# --- everything, in order --------------------------------------------------

def up(target='mcp', report=lambda step, state, detail='': None):
  """Brings the machine up for `target` ('mcp' or 'ondevice'). Returns True if it booted the emulator.

  Contract: calls report(step, 'running'|'ok'|'failed', detail) per step, and
  raises SetupError on the first step that cannot be fixed automatically.
  """
  def step(name, fn):
    report(name, 'running')
    try:
      detail = fn()
    except SetupError as e:
      report(name, 'failed', str(e))
      raise
    report(name, 'ok', detail if isinstance(detail, str) else '')
    return detail

  started = step('Emulator', lambda: 'started' if boot_emulator() else 'already running')
  step('AURA installed', require_aura)
  if target == 'ondevice':
    step('AURA listening', require_aura_listening)
    return started == 'started'
  step('MCP server on phone', start_phone_mcp)
  port = step('Phone reachable from PC', phone_signal_port)
  step('Bench bridge paired', lambda: 'already paired' if bench_paired()
       else (pair_bench_bridge(port) or 'paired now'))
  step('Bench bridge running', start_bridge)
  step('Bridge connected to phone', lambda: wait_phone() and 'connected')
  return started == 'started'


# --- AURA's step log: the same record its in-app session viewer shows ------

# Change together: McpSessionStore.kt (where the phone writes them) and report.steps_html
# (which reads metadata.json and screenshots/<index>.<ext>).
PHONE_SESSIONS = 'files/mcp_logs'


def aura_version():
  """AURA's versionName on the phone, e.g. "1.0.573", or "" when it cannot be read."""
  m = re.search(r'versionName=(\S+)', adb('shell', 'dumpsys package %s' % AURA_PACKAGE).stdout)
  return m.group(1) if m else ''


def session_marks(before):
  """How far the newest existing session had got when a task starts: {id: counts}.

  Contract: {sid: {'invocations': n, 'llmCalls': m}} for the newest id in `before`, or {}.
  A task's steps are the entries after these counts, plus every session that is new.
  Why: the phone closes a session only on `end_session` or after a quiet spell, so a task
  that starts soon after the last one logs into the same session. Timestamps cannot split
  them: AndroidWorld resets the phone's clock to the same date at every task start.
  Counting works because nothing calls AURA between tasks.
  """
  marks = {}
  for sid in sorted(before)[-1:]:
    r = adb('shell', 'run-as %s cat %s/%s/metadata.json' % (AURA_PACKAGE, PHONE_SESSIONS, sid))
    try:
      log = json.loads(r.stdout)
    except ValueError:
      continue
    marks[sid] = {'invocations': len(log.get('invocations') or []),
                  'llmCalls': len(log.get('llmCalls') or [])}
  return marks


def phone_sessions():
  """Ids of the session logs AURA has on the phone, one per MCP connection or agent run.

  Fails: an empty set when they cannot be listed (needs a debuggable build for run-as).
  """
  r = adb('shell', 'run-as %s ls %s' % (AURA_PACKAGE, PHONE_SESSIONS))
  return set(r.stdout.split()) if r.returncode == 0 else set()


def pull_sessions(ids, dest):
  """Copies session logs `ids` (metadata.json and screenshots) into dest/<id>/.

  Contract: one `run-as tar` over adb, so a session is copied whole or not at all;
  safe to call repeatedly while the session is still being written.
  Fails: returns False and leaves dest as it was.
  """
  import io
  import tarfile
  if not ids:
    return True
  p = subprocess.run([ADB, '-s', SERIAL, 'exec-out', 'run-as', AURA_PACKAGE, 'tar', '-cf', '-',
                      '-C', PHONE_SESSIONS, *sorted(ids)], capture_output=True, timeout=60)
  if p.returncode != 0 or not p.stdout:
    return False
  try:
    with tarfile.open(fileobj=io.BytesIO(p.stdout)) as tar:
      tar.extractall(dest, filter='data')
  except (tarfile.TarError, OSError):
    return False
  return True


# --- AURA's own log, for the suite page -------------------------------------

class AppLog:
  """Follows everything AURA's process logs (Info and up) while a task runs.

  Contract: start() begins at "now", each line goes to on_line as it arrives,
  stop() returns the whole text. It is the same log the app's own log screens are
  built from, unfiltered, so a reader sees what AURA did on every tool call.
  Fails: if AURA is not running, start() records that and follows nothing.
  """

  def __init__(self, on_line):
    self._on_line = on_line
    self._lines = []
    self._proc = None
    self._thread = None

  def start(self):
    pid = adb('shell', 'pidof %s' % AURA_PACKAGE).stdout.strip().split()
    if not pid:
      self._lines.append('(AURA was not running when the task started: no app log)')
      return
    self._proc = subprocess.Popen(
        [ADB, '-s', SERIAL, 'logcat', '-v', 'time', '-T', '1', '--pid', pid[0], '*:I'],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8',
        errors='replace')
    self._thread = threading.Thread(target=self._pump, daemon=True)
    self._thread.start()

  def _pump(self):
    for line in self._proc.stdout:
      line = line.rstrip('\n')
      if line.startswith('--------- beginning of'):
        continue
      self._lines.append(line)
      try:
        self._on_line(line)
      except Exception:  # noqa: BLE001 - a broken viewer must not lose the log
        pass

  def stop(self):
    if self._proc:
      self._proc.kill()
      self._proc.wait(timeout=10)
      self._thread.join(timeout=5)
    return '\n'.join(self._lines) + '\n'


# --- the live display, for the suite page ----------------------------------

class PhoneScreen:
  """Live JPEG frames of the emulator's display, read from its gRPC port.

  Contract: one reader thread however many pages watch; `frames()` yields each
  new frame as JPEG bytes. Reads the display from the emulator, not the phone,
  so it costs the phone nothing.
  Fails: while the emulator is down it retries every 2 s; viewers just wait.
  """

  WIDTH, HEIGHT = 432, 960  # 1080x2400 scaled by 0.4: sharp enough, ~18 KB a frame

  def __init__(self):
    self._cond = threading.Condition()
    self._frame, self._seq = None, 0
    self._thread = None

  def _loop(self):
    import io

    import grpc
    from android_env.proto import emulator_controller_pb2 as pb
    from android_env.proto import emulator_controller_pb2_grpc as pb_grpc
    from PIL import Image

    fmt = pb.ImageFormat(format=pb.ImageFormat.RGB888, width=self.WIDTH, height=self.HEIGHT)
    while True:
      try:
        channel = grpc.insecure_channel('localhost:%d' % GRPC_PORT, options=[
            ('grpc.max_receive_message_length', 16 * 1024 * 1024)])
        for img in pb_grpc.EmulatorControllerStub(channel).streamScreenshot(fmt):
          out = io.BytesIO()
          Image.frombytes('RGB', (img.format.width, img.format.height), img.image).save(
              out, 'JPEG', quality=70)
          with self._cond:
            self._frame, self._seq = out.getvalue(), self._seq + 1
            self._cond.notify_all()
      except Exception:  # noqa: BLE001 - emulator down or restarting; try again
        time.sleep(2)

  def frames(self):
    if self._thread is None or not self._thread.is_alive():
      self._thread = threading.Thread(target=self._loop, daemon=True)
      self._thread.start()
    seen = 0
    while True:
      with self._cond:
        self._cond.wait_for(lambda: self._seq != seen, timeout=10)
        if self._seq == seen or self._frame is None:
          continue
        seen, frame = self._seq, self._frame
      yield frame


# --- the phone's own screen recording -------------------------------------

class Recorder:
  """Screen-records the phone for as long as a task runs, in 180-second chunks."""

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
          [ADB, '-s', SERIAL, 'shell', 'screenrecord', '--bit-rate', '3M', '--size', '720x1600',
           '--time-limit', str(CHUNK_SECONDS), remote],
          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
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
      self._notes.append('chunk %d: %.1fs, exit %s %s' % (i, lasted, proc.returncode, said.strip()))
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
    aura_androidworld.write_text(os.path.join(self._out_dir, 'recorder.log'), '\n'.join(self._notes))
    return self._finalise()

  def _finalise(self):
    """One part becomes run.mp4; several are concatenated if ffmpeg exists."""
    if not self.parts:
      return []
    if len(self.parts) == 1:
      os.replace(os.path.join(self._out_dir, self.parts[0]), os.path.join(self._out_dir, 'run.mp4'))
      return ['run.mp4']
    if shutil.which('ffmpeg'):
      listing = os.path.join(self._out_dir, 'parts.txt')
      aura_androidworld.write_text(listing, ''.join("file '%s'\n" % p for p in self.parts))
      done = subprocess.run(['ffmpeg', '-y', '-f', 'concat', '-safe', '0', '-i', 'parts.txt',
                             '-c', 'copy', 'run.mp4'], capture_output=True, cwd=self._out_dir)
      os.remove(listing)
      if done.returncode == 0:
        for p in self.parts:
          os.remove(os.path.join(self._out_dir, p))
        return ['run.mp4']
    return self.parts  # no ffmpeg: the report shows them as part 1, part 2, ...


if __name__ == '__main__':
  if sys.argv[1:] not in (['up'], ['up', 'ondevice']):
    raise SystemExit('usage: device.py up [ondevice]')
  try:
    up('ondevice' if 'ondevice' in sys.argv else 'mcp',
       report=lambda s, st, d='': print('%-28s %-8s %s' % (s, st, d)))
    print('ready: %s' % json.dumps(bridge_health()))
  except SetupError as e:
    raise SystemExit('SETUP: %s' % e)
