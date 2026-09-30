"""Regenerates `app/src/main/assets/eval/androidworld.json` from AndroidWorld's registry.

    androidworld-venv/Scripts/python.exe scripts/bench/gen_androidworld_tasks.py

The registry is the only honest source for "which 116 tasks are in the suite" -
hand-maintaining that list means the cockpit and the host referee can disagree
about what a benchmark run covered, and the disagreement would be invisible.

Numbers are assigned by sorted task name starting at 1001. That is stable as
long as the suite is, and a task that disappears upstream leaves its number
retired rather than handing it to a different task - the number is a permanent
join key and also the on-disk filename of a result.
"""

import json
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
sys.path.insert(0, os.path.join(ROOT, 'android_world'))

OUT = os.path.join(ROOT, 'aura-android', 'app', 'src', 'main', 'assets',
                   'eval', 'androidworld.json')

HEADER = [
    'GENERATED - do not hand-edit. Rebuild with scripts/bench/gen_androidworld_tasks.py.',
    '',
    "Google's AndroidWorld suite (116 tasks), exposed in the on-device eval cockpit.",
    '',
    'What is different about these rows:',
    "  - 'goal' is a PLACEHOLDER, not the sentence the agent gets. AndroidWorld generates the",
    '    real goal from the run seed during setup, so it only exists at run time. The runner',
    '    replaces it once scripts/bench/referee.py hands it back.',
    "  - 'truth_check' is informational. These tasks are scored by AndroidWorld's own validator",
    '    reading device state, never by a human tapping pass/fail.',
    "  - 'aw_task' is the join key to AndroidWorld's task class, and the flag that routes this row",
    '    through the host referee.',
    '  - numbers start at 1001 so they can never collide with tasks.json (the number is a',
    '    permanent join key AND the on-disk filename).',
    '',
    'Running these needs the PC: androidworld-venv/Scripts/python.exe scripts/bench/referee.py',
]

TRUTH_CHECK = ('Scored automatically. AndroidWorld inspects device state after '
               'the run; no human verdict.')


def build():
  from android_world import registry
  reg = registry.TaskRegistry()
  names = sorted(reg.get_registry(family=registry.TaskRegistry.ANDROID_WORLD_FAMILY))
  return {
      'version': 1,
      '_comment': HEADER,
      'tasks': [
          {
              'number': 1000 + i,
              # Unique and apostrophe-free, because EvalTaskCatalog.validate
              # rejects both - a duplicated goal makes two tasks
              # indistinguishable in a trace, and an apostrophe breaks the
              # device-shell quoting the headless runner still uses.
              'goal': 'AndroidWorld: %s' % name,
              'category': 'androidworld',
              'truth_check': TRUTH_CHECK,
              'needs_human': False,
              'aw_task': name,
          }
          for i, name in enumerate(names, 1)
      ],
  }


if __name__ == '__main__':
  suite = build()
  with open(OUT, 'w', encoding='utf-8') as f:
    json.dump(suite, f, indent=2)
    f.write('\n')
  print('wrote %d tasks to %s' % (len(suite['tasks']), OUT))
