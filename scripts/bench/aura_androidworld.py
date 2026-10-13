"""Run the AndroidWorld benchmark against AURA.

AURA is an on-device agent: it has its own perception, its own action space and
its own model. It cannot be driven step-by-step through AndroidWorld's
`AndroidEnv` the way M3A is.

It doesn't need to be. AndroidWorld scores a task by inspecting *device state*
afterwards (`task.is_successful(env)`) -- it never looks at how the screen got
that way. So the adapter below is the whole integration: hand AURA the goal over
the debug `AGENT_SPIKE` broadcast, wait for it to finish, and let AndroidWorld
grade whatever the phone looks like at the end.

Setup and the deviations from a stock AndroidWorld run are in README.md next to
this file. Usage (emulator running, AURA debug build installed and set up):

    androidworld-venv/Scripts/python.exe scripts/bench/aura_androidworld.py \
        --tasks=MarkorCreateNote,SimpleCalendarAddOneEvent

Everything writes to ./runs/<timestamp>/ by default. Re-point --checkpoint_dir at
an existing run to resume it.

This module also holds the pieces `referee.py` and `bench_server.py` share:
`find_adb`, `scored_tasks`, `restore_aura_a11y` and `shift_device_year`.
"""

import functools
import os
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import types

from absl import app
from absl import flags

# The upstream clone sits in the repo root and is not vendored (see .gitignore).
sys.path.insert(
    0, os.path.join(os.path.dirname(__file__), '..', '..', 'android_world')
)

from android_world import checkpointer as checkpointer_lib  # noqa: E402
from android_world import registry  # noqa: E402
from android_world import suite_utils  # noqa: E402
from android_world.agents import base_agent  # noqa: E402
from android_world.env import device_constants  # noqa: E402
from android_world.env import env_launcher  # noqa: E402
from android_world.env import interface  # noqa: E402
from android_world.task_evals import task_eval  # noqa: E402
from android_world.task_evals.information_retrieval import information_retrieval  # noqa: E402


def _fix_apk_install_on_windows():
  """Lets android_env install its accessibility forwarder on Windows + Python 3.11.

  Why: android_env writes the APK to a `NamedTemporaryFile` and runs
  `adb install` while it is still open. Below Python 3.12 that file is opened
  delete-on-close, which Windows hides from other processes, so adb fails with
  "No such file or directory" and the env never builds. 3.12's
  `delete_on_close=False` fixes it upstream, but AndroidWorld pins
  matplotlib 3.6.1, which has no 3.12 wheels.

  ponytail: each env build leaves one ~4.5 MB APK in %TEMP%; drop this shim
  once AndroidWorld supports Python 3.12.
  """
  if os.name != 'nt' or sys.version_info >= (3, 12):
    return
  from android_env.components import adb_call_parser
  adb_call_parser.tempfile = types.SimpleNamespace(
      NamedTemporaryFile=functools.partial(tempfile.NamedTemporaryFile, delete=False))


_fix_apk_install_on_windows()


def find_adb():
  """Returns adb from PATH, else from the usual SDK locations, else bare 'adb'."""
  found = shutil.which('adb')
  if found:
    return found
  exe = 'adb.exe' if os.name == 'nt' else 'adb'
  for sdk in (os.environ.get('ANDROID_HOME'), os.environ.get('ANDROID_SDK_ROOT'),
              os.path.expandvars(r'%LOCALAPPDATA%\Android\Sdk'),
              os.path.expanduser('~/Android/Sdk'),
              os.path.expanduser('~/Library/Android/sdk')):
    if sdk and os.path.isfile(os.path.join(sdk, 'platform-tools', exe)):
      return os.path.join(sdk, 'platform-tools', exe)
  return 'adb'


_ADB = flags.DEFINE_string('adb_path', find_adb(), 'Path to adb.')
_CONSOLE_PORT = flags.DEFINE_integer('console_port', 5554, 'Emulator console port.')
_SETUP = flags.DEFINE_boolean(
    'perform_emulator_setup', False,
    'Install the benchmark apps, grant their permissions, then exit. Once per fresh AVD.',
)
_TASKS = flags.DEFINE_list('tasks', None, 'Task names; None runs every scored task.')
_INCLUDE_IR = flags.DEFINE_boolean(
    'include_information_retrieval', False,
    'Also run the information-retrieval family. Off because AURA cannot score '
    'on it yet; see README.md.',
)
_N_COMBINATIONS = flags.DEFINE_integer('n_task_combinations', 1, 'Instances per task.')
# 30 is upstream run.py's default, so our parameters match other published runs.
_SEED = flags.DEFINE_integer('task_random_seed', 30, 'Task parameter seed. Record it.')
_CHECKPOINT_DIR = flags.DEFINE_string('checkpoint_dir', '', 'Resume this run directory.')
_OUTPUT_PATH = flags.DEFINE_string('output_path', './runs', 'Where new runs go.')
_TIMEOUT = flags.DEFINE_integer('goal_timeout', 300, 'Seconds to let AURA work.')
_PACKAGE = flags.DEFINE_string(
    'aura_package', 'com.aura.aura_ui.feature.debug',
    'Installed AURA package. The spike receiver only exists in debug builds.',
)
_DEVICE_YEAR = flags.DEFINE_integer(
    'device_year', 2026, 'Year to shift the benchmark clock into. See shift_device_year.',
)

# AndroidWorld prints check/cross emoji per episode; the Windows console is
# cp1252 and dies on them mid-suite, losing the run.
if hasattr(sys.stdout, 'reconfigure'):
  sys.stdout.reconfigure(encoding='utf-8', errors='replace')
  sys.stderr.reconfigure(encoding='utf-8', errors='replace')

_BROADCAST_ACTION = 'com.aura.aura_ui.AGENT_SPIKE'
_LOG_TAG = 'AuraAgentSpike'
# AURA logs exactly one of these when a run ends.
# Change together: AssistantForegroundService.agentSpikeReceiver / runViaOverlay.
_DONE_MARKERS = ('Agent result:', 'Spike run threw', 'Agent run failed')
_AURA_A11Y_CLASS = 'com.aura.aura_ui.accessibility.AuraAccessibilityService'


def scored_tasks(include_ir=False):
  """The AndroidWorld registry minus the families AURA is not scored on.

  Contract: name -> task class. Without `include_ir`, every
  `InformationRetrieval` subclass is dropped. Those tasks are graded on
  `env.interaction_cache`, a typed answer AURA has no channel to set, and their
  seed data uses literal 2023 dates that `shift_device_year` cannot move. Run
  them and they fail for harness reasons, not agent reasons.
  """
  reg = registry.TaskRegistry().get_registry(
      family=registry.TaskRegistry.ANDROID_WORLD_FAMILY)
  return {name: cls for name, cls in reg.items()
          if include_ir or not issubclass(cls, information_retrieval.InformationRetrieval)}


def restore_aura_a11y(adb, serial, package):
  """Re-enables AURA's AccessibilityService next to AndroidWorld's forwarder.

  Why: building the env makes android_env *overwrite*
  `enabled_accessibility_services` with only its own forwarder, which silently
  switches AURA off. Both must stay on: AURA acts through its service, and some
  AndroidWorld validators read the tree through the forwarder.

  Contract: idempotent; call before every task. Building the env is not the
  only trigger: `AndroidWorldController.refresh_env` rebuilds it when the a11y
  tree stalls.
  Writes: `settings secure enabled_accessibility_services`, `accessibility_enabled`.
  """
  def sh(*args):
    return subprocess.run([adb, '-s', serial, 'shell', *args], capture_output=True,
                          text=True, errors='replace').stdout.strip()

  mine = '%s/%s' % (package, _AURA_A11Y_CLASS)
  current = [s for s in sh('settings get secure enabled_accessibility_services').split(':')
             if s and s != 'null']
  if mine not in current:
    sh('settings put secure enabled_accessibility_services %s'
       % shlex.quote(':'.join(current + [mine])))
    sh('settings put secure accessibility_enabled 1')
    time.sleep(3)  # let the system bind the service before AURA is handed a goal


def require_aura_receiver(adb, serial):
  """Exits unless AURA's debug broadcast receiver answers.

  Why: the receiver lives in `AssistantForegroundService`, which is not running
  after an install or a reboot until AURA is opened once. Without this check,
  every task would time out and be filed as a FAIL.
  Change together: the "Missing --es goal" line in
  AssistantForegroundService.agentSpikeReceiver.
  """
  def run(*args):
    return subprocess.run([adb, '-s', serial, *args], capture_output=True,
                          text=True, errors='replace').stdout

  run('logcat', '-c')
  run('shell', 'am broadcast -a %s' % _BROADCAST_ACTION)  # no goal: answers, runs nothing
  time.sleep(2)
  if 'Missing --es goal' not in run('logcat', '-d', '-s', '%s:E' % _LOG_TAG):
    raise SystemExit('AURA is not listening on %s. Install a debug build and open '
                     'AURA on the emulator once, then retry.' % _BROADCAST_ACTION)


class AuraAgent(base_agent.EnvironmentInteractingAgent):
  """Hands one goal to the on-device agent and waits for it to finish."""

  def __init__(self, env: interface.AsyncEnv, adb: str, serial: str, timeout: int,
               package: str):
    super().__init__(env, name='aura')
    self._adb = adb
    self._serial = serial
    self._timeout = timeout
    self._package = package

  def _sh(self, *args: str) -> str:
    out = subprocess.run(
        [self._adb, '-s', self._serial, *args],
        capture_output=True, text=True, errors='replace',
    )
    return out.stdout or ''

  def step(self, goal: str) -> base_agent.AgentInteractionResult:
    restore_aura_a11y(self._adb, self._serial, self._package)
    # Re-asserted every episode: AndroidWorld runs `svc wifi enable` on setup and
    # on reconnect, which hands the emulator a fec0:: address. Android then
    # prefers AAAA records, the emulator has no IPv6 route, and every model call
    # dies with ECONNREFUSED having never tried IPv4.
    self._sh('shell', 'sysctl -w net.ipv6.conf.all.disable_ipv6=1')
    # Without this, the previous task's completion line is still in logcat and
    # this episode "finishes" instantly.
    self._sh('logcat', '-c')
    # Quoted for the device shell: goals contain apostrophes ("Bob's"), and a
    # broken quote drops the goal, which then times out and reads as a FAIL.
    self._sh('shell', 'am broadcast -a %s --es goal %s'
             % (_BROADCAST_ACTION, shlex.quote(goal)))

    deadline = time.time() + self._timeout
    last = ''
    while time.time() < deadline:
      time.sleep(5)
      log = self._sh('logcat', '-d', '-s', f'{_LOG_TAG}:I')
      hits = [ln for ln in log.splitlines() if any(m in ln for m in _DONE_MARKERS)]
      if hits:
        last = hits[-1]
        break
    else:
      last = f'TIMEOUT after {self._timeout}s'
      # A timed-out run is still tapping. Stop it before AndroidWorld scores and
      # tears down, or it acts on the next task's freshly seeded screen.
      self._sh('shell', 'am broadcast -a %s --es mode cancel' % _BROADCAST_ACTION)
      time.sleep(3)

    print(f'    aura: {last.strip()}')
    # Always done after one step: AURA runs its own loop internally, so there is
    # no second turn for AndroidWorld to drive.
    return base_agent.AgentInteractionResult(done=True, data={'aura_log': last})


def shift_device_year(year: int) -> None:
  """Moves AndroidWorld's frozen clock into the present.

  AndroidWorld pins every task's device clock to 2023-10-15 so results are
  reproducible. AURA calls a cloud model, and TLS on a device that believes it
  is 2023 rejects today's certificates outright:

      Unacceptable certificate: CN=GTS Root R4, O=Google Trust Services LLC

  Every date the scored tasks use derives from `device_constants.DT`, so
  shifting the year alone keeps each task internally consistent. October is
  kept because helpers pass `end_day=31`; a 30-day month would raise.

  Costs, both accepted deliberately:
    - The weekday changes (2023-10-15 was a Sunday, 2026-10-15 is a Thursday).
    - `information_retrieval` tasks read literal 2023 dates from
      `tasks.textproto`, which this does not touch. `scored_tasks` excludes them.

  Contract: call before `load_and_setup_env`, so the first freeze already uses
  the shifted date.
  """
  device_constants.DT = device_constants.DT.replace(year=year)
  device_constants.ANDROID_DT = device_constants.DT.strftime('%m%d%H%M%y.%S')
  # Bound as a class attribute at import time, so patching the module constant
  # after the import is not enough.
  task_eval.TaskEval.device_time = device_constants.DT


def _main() -> None:
  serial = f'emulator-{_CONSOLE_PORT.value}'
  shift_device_year(_DEVICE_YEAR.value)
  env = env_launcher.load_and_setup_env(
      console_port=_CONSOLE_PORT.value,
      emulator_setup=_SETUP.value,
      adb_path=_ADB.value,
  )
  if _SETUP.value:
    env.close()
    print('Emulator setup done. Install AURA next (see README.md), then run tasks.')
    return
  require_aura_receiver(_ADB.value, serial)
  tasks = scored_tasks(_INCLUDE_IR.value)
  unknown = [t for t in (_TASKS.value or []) if t not in tasks]
  if unknown:
    raise SystemExit('Not in the scored suite: %s (information-retrieval tasks '
                     'need --include_information_retrieval)' % ', '.join(unknown))
  suite = suite_utils.create_suite(
      tasks,
      n_task_combinations=_N_COMBINATIONS.value,
      seed=_SEED.value,
      tasks=_TASKS.value,
      use_identical_params=False,
  )
  suite.suite_family = registry.TaskRegistry.ANDROID_WORLD_FAMILY

  agent = AuraAgent(env, _ADB.value, serial, _TIMEOUT.value, _PACKAGE.value)
  agent.transition_pause = None

  checkpoint_dir = _CHECKPOINT_DIR.value or checkpointer_lib.create_run_directory(
      _OUTPUT_PATH.value
  )
  print(f'Writing to {checkpoint_dir} (seed {_SEED.value}, {len(suite)} tasks)')
  suite_utils.run(
      suite,
      agent,
      checkpointer=checkpointer_lib.IncrementalCheckpointer(checkpoint_dir),
      demo_mode=False,
  )
  print(f'Done. Results in {checkpoint_dir}. Seed was {_SEED.value} -- record it.')
  env.close()


def main(argv):
  del argv
  _main()


if __name__ == '__main__':
  app.run(main)
