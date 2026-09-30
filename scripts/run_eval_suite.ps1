<#
.SYNOPSIS
  Drive the fixed agent task suite on the connected device, one task at a time,
  for before/after eval re-measurement (see scripts/agent_eval.py).

.DESCRIPTION
  Fires each goal at the on-device agent via the debug-only AGENT_SPIKE broadcast
  (AssistantForegroundService), which runs it through the provider/model saved in
  Settings -> Agent Brain. Tasks are sequential: the screen is single mutable
  ground truth, so we press HOME, broadcast one goal, and wait for the agent's
  "Agent result" log line before moving on.

  PREREQUISITES (the broadcast is a no-op otherwise):
    1. A debug build is installed (./gradlew :app:installDebug) and the AURA app
       has been OPENED at least once this boot, so AssistantForegroundService is
       running and its (dynamic, debug-only) AGENT_SPIKE receiver is registered.
    2. Settings -> Agent Brain has a provider + key + model selected.
    3. Screen-capture permission has been granted once (perceive_screen needs it).

  After the suite finishes (the exact command, with --after pre-filled, is
  printed at the end of the run):
    python scripts/agent_eval.py --pull --after <suiteStart> --dir _gtmp/eval_post1 --compare _gtmp/baseline_pre_phase1.json

.PARAMETER TimeoutSeconds
  Max seconds to wait for a single task to finish before moving on. Default 220
  (the worst baseline run was 157s).
#>
param(
    [int]$TimeoutSeconds = 220
)

$ErrorActionPreference = "Stop"
$ACTION = "com.aura.aura_ui.AGENT_SPIKE"

# The suite lives in ONE place, shared with agent_eval.py (which matches trace
# sessions back to tasks by this exact goal string) and with the human scoring the
# truth checks. Editing a goal here instead of there would break that join
# silently, so the list is read, never duplicated.
$tasksFile = Join-Path $PSScriptRoot "..\docs\agent-review\EVAL_TASKS.md"
if (-not (Test-Path $tasksFile)) {
    Write-Error "Suite definition not found: $tasksFile"
    exit 1
}

# Rows look like:  | 3 | `Send a message hi to Amma in WhatsApp` | in-app | ... |
# The backticks delimit the exact goal; the leading integer marks a data row.
$suite = @(
    Select-String -Path $tasksFile -Pattern '^\|\s*\d+\s*\|\s*`([^`]+)`' -AllMatches |
        ForEach-Object { $_.Matches.Groups[1].Value.Trim() }
)
if ($suite.Count -eq 0) {
    Write-Error "No tasks parsed from $tasksFile - check the table format."
    exit 1
}

# Goals must be apostrophe-free: they are single-quoted for the device shell below.
$badQuotes = $suite | Where-Object { $_ -match "'" }
if ($badQuotes) {
    Write-Error "Goal(s) contain an apostrophe and will not survive device-shell quoting:`n$($badQuotes -join "`n")"
    exit 1
}
Write-Host "Loaded $($suite.Count) task(s) from docs/agent-review/EVAL_TASKS.md" -ForegroundColor Cyan

# Confirm a device is actually connected before doing anything.
$devices = (adb devices) | Select-String "\tdevice$"
if (-not $devices) {
    Write-Error "No device in 'device' state. Run 'adb devices' and reconnect."
    exit 1
}
Write-Host "Device(s):`n$($devices -join "`n")`n" -ForegroundColor Cyan

# Window marker for agent_eval.py --after.
#
# Read from the DEVICE clock, not Get-Date. `startedAtMillis` in every trace is
# stamped on-device; if the phone's clock lags the host's, a host-derived cutoff
# sits in the future relative to every session this suite is about to write, and
# the whole run silently vanishes from the report. Same clock, same comparison.
#
# It matters because the app never clears files/mcp_logs: without --after, a pull
# folds every historical run (90+ as of 2026-08) into a fresh suite's numbers and
# drags them back toward the old value.
# Read SECONDS and multiply, rather than asking for millis with `date +%s%3N`.
# `%3N` is a GNU width modifier that not every Android `date` honours; a toybox
# build that ignores it returns a clean 10-digit seconds value, which passes any
# "is it a number?" check and lands as --after 1786596000 — January 1970 in
# millis, so the filter matches everything and silently becomes a no-op. `+%s` is
# portable everywhere, and sub-second precision is meaningless here (the first
# broadcast is seconds away). The length check below then has a fixed expectation.
$suiteStartSec = (adb shell "date +%s" | Out-String).Trim()
if ($suiteStartSec -notmatch '^\d{10,}$') {
    Write-Error "Could not read the device clock (got '$suiteStartSec'). Aborting: without a window marker the report would silently include every historical run."
    exit 1
}
$suiteStart = ([long]$suiteStartSec * 1000).ToString()
if ([long]$suiteStart -lt 1000000000000) {
    Write-Error "Device clock looks wrong: $suiteStart ms is before 2001. Fix the phone's date; a cutoff in the past makes --after a no-op."
    exit 1
}
$markerDir = Join-Path $PSScriptRoot "..\_gtmp"
if (-not (Test-Path $markerDir)) { New-Item -ItemType Directory -Path $markerDir | Out-Null }
$markerFile = Join-Path $markerDir "last_suite_start.txt"
Set-Content -Path $markerFile -Value $suiteStart -Encoding ascii
Write-Host "Suite start (device clock): $suiteStart  -> _gtmp/last_suite_start.txt`n" -ForegroundColor Cyan

$i = 0
foreach ($goal in $suite) {
    $i++
    Write-Host "[$i/$($suite.Count)] $goal" -ForegroundColor Yellow

    # Reset to a known starting screen and clear the log buffer so we only read
    # this task's lines.
    adb shell input keyevent KEYCODE_HOME | Out-Null
    Start-Sleep -Seconds 2
    adb logcat -c | Out-Null

    # Outer double-quotes group the whole remote command for adb; inner single
    # quotes quote the goal for the device shell (handles the spaces).
    adb shell "am broadcast -a $ACTION --es goal '$goal'" | Out-Null

    # Poll the agent's own tag for the completion line (it carries the guard's
    # blind/loop block counts) or a thrown-error line.
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $done = $null
    do {
        Start-Sleep -Seconds 5
        $log = adb logcat -d -s AuraAgentSpike:I 2>$null
        $done = $log | Select-String "Agent result:|Spike run threw|Agent run failed" | Select-Object -Last 1
    } while (-not $done -and (Get-Date) -lt $deadline)

    if ($done) {
        Write-Host "    -> $($done.Line.Trim())`n" -ForegroundColor Green
    } else {
        Write-Host "    -> TIMEOUT after ${TimeoutSeconds}s (task may still be running; continuing)`n" -ForegroundColor Red
    }

    # Small gap so the run logger flushes metadata.json before the next task.
    Start-Sleep -Seconds 3
}

Write-Host "Suite complete." -ForegroundColor Cyan
Write-Host "  1. Score the truth checks NOW, while you can still see the screen." -ForegroundColor Yellow
Write-Host "     Tasks + checks: docs/agent-review/EVAL_TASKS.md"
Write-Host "     Format:         scripts/eval_truth_checks.md"
Write-Host "  2. Then aggregate (--after windows the report to THIS suite run):"
Write-Host "     python scripts/agent_eval.py --pull --after $suiteStart --scores _gtmp/<station>_scored.md --save _gtmp/<station>.json"
