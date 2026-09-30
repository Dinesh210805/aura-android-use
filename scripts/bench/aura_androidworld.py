"""Run the AndroidWorld benchmark against AURA.

AURA is an on-device agent: it has its own perception, its own action space and
its own model. It cannot be driven step-by-step through AndroidWorld's
`AndroidEnv` the way M3A is.

It doesn't need to be. AndroidWorld scores a task by inspecting *device state*
afterwards (`task.is_successful(env)`) -- it never looks at how the screen got
that way. So the adapter below is the whole integration: hand AURA the goal over
the same debug broadcast `scripts/run_eval_suite.ps1` uses, wait for it to
finish, and let AndroidWorld grade whatever the phone looks like at the end.

Usage (emulator must already be running, AURA installed and set up):

    androidworld-venv/Scripts/python.exe scripts/bench/aura_androidworld.py \
        --tasks=MarkorCreateNote,SimpleCalendarAddOneEvent \
        --task_random_seed=42

Everything writes to ./runs/<timestamp>/ by default. Re-point --checkpoint_dir at
an existing run to resume it.
"""

import os
import subprocess
import sys
import time

from absl import app
from absl import flags

# The upstream clone sits next to this repo and is not vendored (see .gitignore).
sys.path.insert(
    0, os.path.join(os.path.dirname(__file__), '..', '..', 'android_world')
)

from android_world import checkpointer as checkpointer_lib  # noqa: E402
from android_world import registry  # noqa: E402
from android_world import suite_utils  # noqa: E402
from android_world.agents import base_agent  # noqa: E402
from android_world.agents import base_agent as _ba  # noqa: E402,F811
from android_world.env import device_constants  # noqa: E402
from android_world.env import env_launcher  # noqa: E402
from android_world.env import interface  # noqa: E402
from android_world.task_evals import task_eval  # noqa: E402
del _ba

_ADB = flags.DEFINE_string(
    'adb_path',
    os.path.expandvars(r'%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe'),
    'Path to adb.',
)
_CONSOLE_PORT = flags.DEFINE_integer('console_port', 5554, 'Emulator console port.')
_TASKS = flags.DEFINE_list('tasks', None, 'Task names; None runs the whole suite.')
_N_COMBINATIONS = flags.DEFINE_integer('n_task_combinations', 1, 'Instances per task.')
_SEED = flags.DEFINE_integer('task_random_seed', 42, 'Task parameter seed. Record it.')
_CHECKPOINT_DIR = flags.DEFINE_string('checkpoint_dir', '', 'Resume this run directory.')
_OUTPUT_PATH = flags.DEFINE_string('output_path', './runs', 'Where new runs go.')
_TIMEOUT = flags.DEFINE_integer('goal_timeout', 300, 'Seconds to let AURA work.')
_DEVICE_YEAR = flags.DEFINE_integer(
    'device_year',
    2026,
    'Year to shift the benchmark clock into. See _shift_device_year.',
)

# AndroidWorld prints check/cross emoji per episode; the Windows console is
# cp1252 and dies on them mid-suite, losing the run.
if hasattr(sys.stdout, 'reconfigure'):
  sys.stdout.reconfigure(encoding='utf-8', errors='replace')
  sys.stderr.reconfigure(encoding='utf-8', errors='replace')

_BROADCAST_ACTION = 'com.aura.aura_ui.AGENT_SPIKE'
_LOG_TAG = 'AuraAgentSpike'
# AURA logs exactly one of these when a run ends. Kept in sync with
# AssistantForegroundService's spike receiver.
_DONE_MARKERS = ('Agent result:', 'Spike run threw', 'Agent run failed')


class AuraAgent(base_agent.EnvironmentInteractingAgent):
  """Hands one goal to the on-device agent and waits for it to finish."""

  def __init__(self, env: interface.AsyncEnv, adb: str, timeout: int):
    super().__init__(env, name='aura')
    self._adb = adb
    self._timeout = timeout

  def _sh(self, *args: str, capture: bool = True) -> str:
    out = subprocess.run(
        [self._adb, '-s', f'emulator-{_CONSOLE_PORT.value}', *args],
        capture_output=capture,
        text=True,
        errors='replace',
    )
    return out.stdout or ''

  def step(self, goal: str) -> base_agent.AgentInteractionResult:
    # Clear the buffer first: without this, the previous task's completion line
    # is still sitting in logcat and this episode "finishes" instantly.
    # Re-assert every episode: AndroidWorld runs `svc wifi enable` on setup and
    # on reconnect, which hands the emulator a fec0:: address. Android then
    # prefers AAAA records, the emulator has no IPv6 route, and every model call
    # dies with ECONNREFUSED having never tried IPv4.
    self._sh('shell', 'sysctl -w net.ipv6.conf.all.disable_ipv6=1')
    self._sh('logcat', '-c')
    self._sh('shell', f"am broadcast -a {_BROADCAST_ACTION} --es goal '{goal}'")

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

    print(f'    aura: {last.strip()}')
    # Always done after one step: AURA runs its own loop internally, so there is
    # no second turn for AndroidWorld to drive.
    return base_agent.AgentInteractionResult(done=True, data={'aura_log': last})


def _shift_device_year(year: int) -> None:
  """Moves AndroidWorld's frozen clock into the present.

  AndroidWorld pins every task's device clock to 2023-10-15 so results are
  reproducible. AURA calls a cloud model, and TLS on a device that believes it
  is 2023 rejects today's certificates outright:

      Unacceptable certificate: CN=GTS Root R4, O=Google Trust Services LLC

  Every date in the suite derives from `device_constants.DT`, including the
  random-timestamp helpers tasks use to seed calendar and expense data, so
  shifting the year alone keeps a task internally consistent. October is kept
  because helpers pass `end_day=31`; a 30-day month would raise.

  Two known costs, both accepted deliberately:
    - The weekday changes (2023-10-15 was a Sunday, 2026-10-15 is a Thursday),
      so a task phrased "next Monday" shifts.
    - `information_retrieval` tasks read literal 2023 dates from
      `tasks.textproto`, which this does not touch. Their data will sit three
      years before the device clock. Exclude that family or read its results
      with that in mind.
  """
  device_constants.DT = device_constants.DT.replace(year=year)
  device_constants.ANDROID_DT = device_constants.DT.strftime('%m%d%H%M%y.%S')
  # Bound as a class attribute at import time, so patching the module constant
  # after the import is not enough.
  task_eval.TaskEval.device_time = device_constants.DT


def _main() -> None:
  env = env_launcher.load_and_setup_env(
      console_port=_CONSOLE_PORT.value,
      emulator_setup=False,
      adb_path=_ADB.value,
  )
  _shift_device_year(_DEVICE_YEAR.value)
  suite = suite_utils.create_suite(
      registry.TaskRegistry().get_registry(family=registry.TaskRegistry.ANDROID_WORLD_FAMILY),
      n_task_combinations=_N_COMBINATIONS.value,
      seed=_SEED.value,
      tasks=_TASKS.value,
      use_identical_params=False,
  )
  suite.suite_family = registry.TaskRegistry.ANDROID_WORLD_FAMILY

  agent = AuraAgent(env, _ADB.value, _TIMEOUT.value)
  agent.transition_pause = None

  checkpoint_dir = _CHECKPOINT_DIR.value or checkpointer_lib.create_run_directory(
      _OUTPUT_PATH.value
  )
  print(f'Writing to {checkpoint_dir} (seed {_SEED.value})')
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
