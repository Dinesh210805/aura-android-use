"""Page recording: films the browser window showing the suite, phone panel included.

The suite page shows the agent's live activity and the phone's display side by
side, so one window is the whole story. `PageRecorder` finds that window by its
title, keeps it on top while a task runs, and captures exactly its area with
ffmpeg. Windows only (Win32 window API + ffmpeg `gdigrab`); elsewhere
`available()` is False and the suite falls back to the phone's own recording.

- Fails: if the window is not found it films the primary monitor and says so in
  recorder.log; a capture ffmpeg could not finish returns no video, not a broken one.
- Why DPI awareness: at 125%/150% scaling an unaware process gets scaled
  coordinates, so the captured area would be the wrong part of the screen.
- Why topmost, not SetForegroundWindow: Windows refuses focus changes to a
  background process, so any other window could sit on top and be filmed.
"""

import ctypes
import os
import shutil
import subprocess

PAGE_TITLE = 'AURA Bench'  # Change together: <title> in suite.html.
HWND_TOPMOST, HWND_NOTOPMOST = -1, -2
SWP_NOSIZE, SWP_NOMOVE, SWP_SHOWWINDOW = 0x0001, 0x0002, 0x0040

_encoder = None


def available():
  return os.name == 'nt' and shutil.which('ffmpeg') is not None


def _user32():
  ctypes.windll.shcore.SetProcessDpiAwareness(2)
  return ctypes.windll.user32


def find_page_window():
  """The visible top-level window whose title contains PAGE_TITLE, or None."""
  user32 = _user32()
  found = []

  @ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
  def visit(hwnd, _):
    n = user32.GetWindowTextLengthW(hwnd)
    if n and user32.IsWindowVisible(hwnd) and not user32.IsIconic(hwnd):
      buf = ctypes.create_unicode_buffer(n + 1)
      user32.GetWindowTextW(hwnd, buf, n + 1)
      if PAGE_TITLE in buf.value:
        found.append(hwnd)
    return True

  user32.EnumWindows(visit, 0)
  return found[0] if found else None


def _visible_rect(hwnd):
  """The window as drawn, without the invisible resize borders GetWindowRect includes."""
  rect = (ctypes.c_long * 4)()
  ok = ctypes.windll.dwmapi.DwmGetWindowAttribute(
      ctypes.c_void_p(hwnd), 9, ctypes.byref(rect), ctypes.sizeof(rect))  # EXTENDED_FRAME_BOUNDS
  if ok != 0:
    ctypes.windll.user32.GetWindowRect(ctypes.c_void_p(hwnd), rect)
  return rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1]


def _pin(hwnd, on):
  ctypes.windll.user32.SetWindowPos(
      ctypes.c_void_p(hwnd), ctypes.c_void_p(HWND_TOPMOST if on else HWND_NOTOPMOST),
      0, 0, 0, 0, SWP_NOSIZE | SWP_NOMOVE | (SWP_SHOWWINDOW if on else 0))


def _pick_encoder():
  """Intel Quick Sync when this machine's driver actually encodes with it, else x264."""
  global _encoder
  if _encoder is None:
    probe = subprocess.run(
        ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i',
         'color=size=1920x1080:rate=12:duration=0.5', '-pix_fmt', 'nv12', '-c:v', 'h264_qsv',
         '-global_quality', '30', '-f', 'null', '-'],
        capture_output=True)
    _encoder = (['-c:v', 'h264_qsv', '-global_quality', '30'] if probe.returncode == 0
                else ['-c:v', 'libx264', '-preset', 'ultrafast', '-crf', '30'])
  return _encoder


class PageRecorder:
  """Records the suite's browser window to <out_dir>/run.mp4 between start() and stop().

  Contract: same shape as device.Recorder, so either can record a task.
  stop() returns ['run.mp4'], or [] if ffmpeg produced nothing playable.
  """

  def __init__(self, out_dir):
    self._out_dir = out_dir
    self._proc = None
    self._log = None
    self._hwnd = None

  def start(self):
    user32 = _user32()
    self._log = open(os.path.join(self._out_dir, 'recorder.log'), 'w', encoding='utf-8')
    self._hwnd = find_page_window()
    if self._hwnd:
      _pin(self._hwnd, True)
      x, y, w, h = _visible_rect(self._hwnd)
    else:
      self._log.write('No "%s" window found: filming the primary monitor instead.\n' % PAGE_TITLE)
      x, y, w, h = 0, 0, user32.GetSystemMetrics(0), user32.GetSystemMetrics(1)
    w, h = w - w % 2, h - h % 2  # H.264 needs even sides
    self._log.flush()
    self._proc = subprocess.Popen(
        ['ffmpeg', '-hide_banner', '-y', '-f', 'gdigrab', '-framerate', '12', '-draw_mouse', '0',
         '-offset_x', str(x), '-offset_y', str(y), '-video_size', '%dx%d' % (w, h),
         '-i', 'desktop', '-vf', "scale='trunc(min(1920,iw)/2)*2':-2", *_pick_encoder(),
         '-pix_fmt', 'nv12', '-movflags', '+faststart', 'run.mp4'],
        cwd=self._out_dir, stdin=subprocess.PIPE, stdout=self._log, stderr=subprocess.STDOUT)

  def stop(self):
    if self._hwnd:
      _pin(self._hwnd, False)
    if not self._proc:
      return []
    try:
      self._proc.communicate(b'q', timeout=30)  # 'q' lets ffmpeg write the index; a kill would not
    except subprocess.TimeoutExpired:
      self._proc.kill()
      self._proc.wait()
    self._log.close()
    path = os.path.join(self._out_dir, 'run.mp4')
    return ['run.mp4'] if os.path.isfile(path) and os.path.getsize(path) > 10_000 else []


if __name__ == '__main__':
  import sys
  import tempfile
  import time
  out = tempfile.mkdtemp(prefix='aw_page_')
  print('page window:', find_page_window())
  rec = PageRecorder(out)
  rec.start()
  time.sleep(float(sys.argv[1]) if len(sys.argv) > 1 else 5)
  print('videos:', rec.stop(), 'encoder:', _encoder, 'in', out)
