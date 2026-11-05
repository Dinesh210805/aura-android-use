"""One stylesheet in the website's look, shared by the suite page and the published benchmark.

Tokens and type are the website's (`aura-android-use-website/download.html`): paper and ink
greys, signal red used only for attention (a failure, a live recording dot), Geist for
text, Geist Mono for labels and numbers, Doto for the big display figures. Light and dark
follow the site: `html[data-theme]`, remembered in localStorage under `aura-theme`.

- Change together: the website's `:root` tokens, if the site's palette changes.
"""

FONTS = (
    '<link rel="preconnect" href="https://fonts.googleapis.com">'
    '<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>'
    '<link href="https://fonts.googleapis.com/css2?family=Doto:wght@500..900&family=Geist:'
    'wght@300..700&family=Geist+Mono:wght@400..600&display=swap" rel="stylesheet">'
)

# The site's own theme bootstrap and switch, so a reader's choice carries across pages.
THEME_SCRIPT = (
    "<script>try{var t=localStorage.getItem('aura-theme');if(!t)t=matchMedia('(prefers-color-"
    "scheme: dark)').matches?'dark':'light';document.documentElement.setAttribute('data-theme',"
    "t);}catch(e){document.documentElement.setAttribute('data-theme','light');}"
    "function auraTheme(){var t=document.documentElement.getAttribute('data-theme')==='dark'?"
    "'light':'dark';document.documentElement.setAttribute('data-theme',t);try{localStorage."
    "setItem('aura-theme',t);}catch(e){}}</script>"
)

_LIGHT = """
  --canvas: #E7E9EC;  --canvas-high: #F3F4F6;  --outline: rgba(11,12,15,.15);
  --chip: rgba(11,12,15,.055);
  --text: #0B0C0F;    --text-dim: #4C5058;
  --ink: #0B0C0F;     --ink-soft: #1A1B1F;     --on-ink: #F3F4F6;
  --on-ink-dim: #9CA1AA; --outline-ink: rgba(255,255,255,.16);
  --red: #FF2B1C;     --blood: #C81E10;        --blood-soft: rgba(255,43,28,.12);
  --pass: #11774A;    --pass-soft: rgba(17,119,74,.12);
  --hdr: rgba(231,233,236,.78);
  color-scheme: light;
"""
_DARK = """
  --canvas: #000000;  --canvas-high: #0D0D0F;  --outline: rgba(255,255,255,.16);
  --chip: rgba(255,255,255,.07);
  --text: #F3F4F6;    --text-dim: #9CA1AA;
  --ink: #16171A;     --ink-soft: #1F2024;     --on-ink: #F3F4F6;
  --on-ink-dim: #9CA1AA; --outline-ink: rgba(255,255,255,.14);
  --red: #FF2B1C;     --blood: #FF6A5E;        --blood-soft: rgba(255,43,28,.16);
  --pass: #45C987;    --pass-soft: rgba(69,201,135,.14);
  --hdr: rgba(0,0,0,.72);
  color-scheme: dark;
"""

CSS = """
:root {%s
  --r-card: 20px; --r-small: 14px; --r-chip: 10px;
  --mono: "Geist Mono", ui-monospace, "Cascadia Mono", Consolas, monospace;
  --sans: "Geist", ui-sans-serif, system-ui, sans-serif;
  --display: "Doto", "Geist Mono", monospace;
}
html[data-theme="dark"] {%s}
@media (prefers-color-scheme: dark) { html:not([data-theme]) {%s} }
* { box-sizing: border-box; }
html { background: var(--canvas); }
body { margin: 0; background: var(--canvas); color: var(--text); font-family: var(--sans);
       font-size: 15px; line-height: 1.5; -webkit-font-smoothing: antialiased; }
a { color: inherit; }
a:hover { color: var(--text-dim); }
:focus-visible { outline: 2px solid var(--text); outline-offset: 3px; border-radius: 4px; }
code { font-family: var(--mono); font-size: .92em; }
.dim { color: var(--text-dim); }

/* ---- site header (published pages) ------------------------------------ */
header.site { position: sticky; top: 0; z-index: 20; background: var(--hdr);
              backdrop-filter: blur(14px); -webkit-backdrop-filter: blur(14px);
              border-bottom: 1px solid var(--outline); }
header.site .in { max-width: 1240px; margin: 0 auto; height: 60px; display: flex;
                  align-items: center; gap: 14px; padding: 0 clamp(16px, 4vw, 40px); }
header.site .brand { font-weight: 650; font-size: 16px; text-decoration: none; }
header.site .crumb { font-family: var(--mono); font-size: 12px; color: var(--text-dim);
                     text-transform: uppercase; letter-spacing: .07em; }
button.theme { width: 36px; height: 36px; padding: 0; display: grid; place-items: center;
               background: transparent; color: var(--text); border: 1px solid var(--outline); }
button.theme:hover { background: var(--chip); }

.wrap { max-width: 1240px; margin: 0 auto; padding: clamp(32px, 6vw, 72px) clamp(16px, 4vw, 40px) 96px; }
.eyebrow { font-family: var(--mono); font-size: 12px; letter-spacing: .08em;
           text-transform: uppercase; color: var(--text-dim); margin: 0 0 10px; }
h1 { font-family: var(--display); font-weight: 850; font-size: clamp(40px, 6vw, 84px);
     line-height: .95; letter-spacing: -.01em; margin: 0; }
h1.task { font-family: var(--mono); font-weight: 560; font-size: clamp(26px, 3.6vw, 40px);
          letter-spacing: -.02em; overflow-wrap: anywhere; }
h2 { font-size: 13px; font-family: var(--mono); font-weight: 560; text-transform: uppercase;
     letter-spacing: .08em; color: var(--text-dim); margin: 40px 0 12px; }
.lede { font-size: clamp(16px, 1.6vw, 19px); color: var(--text-dim); max-width: 760px; margin: 16px 0 0; }

/* ---- score slab ------------------------------------------------------- */
.slab { background: var(--ink); color: var(--on-ink); border-radius: var(--r-card);
        padding: 26px 30px; display: flex; gap: 40px; flex-wrap: wrap;
        align-items: flex-end; margin: 32px 0 14px; }
.slab .big { font-family: var(--display); font-size: clamp(64px, 9vw, 112px); font-weight: 900;
             line-height: .85; letter-spacing: -.02em; }
.slab .unit { font-size: .4em; color: var(--on-ink-dim); font-weight: 600; }
.slab dl { margin: 0; display: flex; gap: 32px; flex-wrap: wrap; }
.slab dt, .facts dt { font-family: var(--mono); font-size: 11px; text-transform: uppercase;
                      letter-spacing: .08em; color: var(--on-ink-dim); margin-bottom: 3px; }
.slab dd { margin: 0; font-family: var(--mono); font-size: 20px; font-variant-numeric: tabular-nums; }
.facts { display: flex; gap: 10px 26px; flex-wrap: wrap; margin: 14px 0; padding: 0; }
.facts dt { color: var(--text-dim); }
.facts dd { margin: 0; font-family: var(--mono); font-size: 13.5px; }

.banner { border-radius: var(--r-small); padding: 13px 17px; margin: 14px 0; font-size: 14px;
          border: 1px solid var(--red); background: var(--blood-soft); }
.scoring { border: 1px solid var(--outline); background: var(--canvas-high);
           border-radius: var(--r-small); padding: 12px 16px; font-size: 13.5px;
           color: var(--text-dim); margin: 12px 0; }
.scoring summary { cursor: pointer; color: var(--text); font-weight: 560; }
.scoring ul { margin: 8px 0 2px; padding-left: 18px; }

/* ---- task rows: full goals, no truncation ----------------------------- */
.rows { border: 1px solid var(--outline); border-radius: var(--r-card);
        background: var(--canvas-high); overflow: hidden; margin-top: 20px; }
.row { display: grid; grid-template-columns: 34px 1fr auto 104px; gap: 16px;
       align-items: center; padding: 14px 20px; border-top: 1px solid var(--outline);
       text-decoration: none; }
.row:first-child { border-top: 0; }
a.row:hover { background: var(--chip); color: inherit; }
.row .n { font-family: var(--mono); font-size: 12px; color: var(--text-dim); }
.row .name { font-family: var(--mono); font-size: 14px; font-weight: 520; display: block; }
.row .goal { display: block; font-size: 13.5px; color: var(--text-dim); margin-top: 3px; }
.row .num { font-family: var(--mono); font-size: 12.5px; text-align: right; color: var(--text-dim); }
.row .num small { display: block; font-size: 11px; }
.row .pill { justify-self: end; }
@media (max-width: 700px) {
  .row { grid-template-columns: 1fr auto; }
  .row .n, .row .num { display: none; }
}

.pill { font-family: var(--mono); font-size: 11px; font-weight: 600; letter-spacing: .05em;
        padding: 4px 10px; border-radius: 999px; white-space: nowrap; text-transform: uppercase;
        display: inline-block; }
.pill.pass { background: var(--pass-soft); color: var(--pass); }
.pill.fail { background: var(--blood-soft); color: var(--blood); }
.pill.none { background: var(--chip); color: var(--text-dim); }
.pill.blind { background: transparent; color: var(--blood); border: 1px solid var(--blood); }

button { font: inherit; cursor: pointer; border-radius: 999px; border: 1px solid var(--text);
         background: var(--text); color: var(--canvas); padding: 7px 18px; font-size: 13px;
         font-weight: 560; transition: background .15s, transform .06s; }
button:hover { opacity: .88; }
button:active { transform: translateY(1px); }
button:disabled { opacity: .32; cursor: not-allowed; }
button.ghost { background: transparent; color: var(--text); border-color: var(--outline); }
button.ghost:hover { background: var(--chip); opacity: 1; }

/* ---- task page --------------------------------------------------------- */
.back { font-family: var(--mono); font-size: 12.5px; color: var(--text-dim);
        text-decoration: none; display: inline-block; margin-bottom: 22px; }
.goalbox { font-size: clamp(17px, 1.7vw, 20px); line-height: 1.5; margin: 18px 0 6px;
           padding: 18px 22px; border-radius: var(--r-small); background: var(--canvas-high);
           border: 1px solid var(--outline); border-left: 3px solid var(--red); }
.answer { font-size: 15px; margin: 8px 0; }
.cols { display: grid; grid-template-columns: minmax(0, 380px) minmax(0, 1fr); gap: 32px;
        align-items: start; }
.cols > section:first-child { position: sticky; top: 76px; }
@media (max-width: 900px) { .cols { grid-template-columns: 1fr; } .cols > section:first-child { position: static; } }
video { width: 100%%; border-radius: var(--r-small); background: #000;
        border: 1px solid var(--outline); display: block; margin-bottom: 10px; }

.session .sess { font-family: var(--mono); font-size: 12px; color: var(--text-dim); margin: 0 0 10px; }
.step { border: 1px solid var(--outline); border-radius: var(--r-small); background: var(--canvas-high);
        margin-bottom: 10px; overflow: hidden; }
.step.bad { border-color: var(--red); }
.step > header { display: flex; gap: 10px; align-items: center; padding: 9px 14px;
                 border-bottom: 1px solid var(--outline); font-size: 14px; }
.step > header .n { font-family: var(--mono); font-size: 11.5px; color: var(--text-dim); min-width: 22px; }
.step > header b { font-family: var(--mono); font-weight: 600; }
.step > header .t { font-family: var(--mono); font-size: 11.5px; color: var(--text-dim); margin-left: auto; }
.step .body { display: grid; grid-template-columns: 150px minmax(0, 1fr); gap: 14px; padding: 12px 14px; }
.step .body.noshot { grid-template-columns: minmax(0, 1fr); }
.step.llm > header b { color: var(--text-dim); }
.shot { margin: 0; position: relative; align-self: start; }
.shot img { width: 100%%; display: block; border-radius: 8px; border: 1px solid var(--outline);
            aspect-ratio: 9 / 20; object-fit: contain; background: #000; }  /* sized before it loads */
.mark { position: absolute; width: 18px; height: 18px; margin: -9px 0 0 -9px; border-radius: 50%%;
        border: 2.5px solid var(--red); background: rgba(255,43,28,.25); pointer-events: none;
        box-shadow: 0 0 0 3px rgba(255,255,255,.7); }
.mark.end { border-style: dashed; }
.io h4 { font-family: var(--mono); font-size: 10.5px; text-transform: uppercase; letter-spacing: .08em;
         color: var(--text-dim); margin: 0 0 4px; font-weight: 560; }
.io h4 + pre { margin-bottom: 10px; }
.io pre { white-space: pre-wrap; word-break: break-word; max-height: 260px; overflow: auto;
          background: var(--chip); color: var(--text); border: 0; border-radius: 8px;
          padding: 8px 10px; font-size: 12px; line-height: 1.5; }
.io details summary { cursor: pointer; font-size: 12.5px; color: var(--text-dim); }
@media (max-width: 560px) { .step .body { grid-template-columns: 1fr; } .shot { max-width: 220px; } }

details.log { border: 1px solid var(--outline); border-radius: var(--r-small);
              background: var(--canvas-high); margin-top: 10px; overflow: hidden; }
details.log > summary { cursor: pointer; padding: 12px 16px; font-size: 13.5px; font-weight: 560;
                        list-style: none; }
details.log > summary::-webkit-details-marker { display: none; }
details.log > summary::before { content: "+  "; font-family: var(--mono); color: var(--text-dim); }
details.log[open] > summary::before { content: "\\2212  "; }
details.log .dim { font-weight: 400; }
pre { margin: 0; padding: 14px 16px; background: var(--ink); color: var(--on-ink);
      font-family: var(--mono); font-size: 12px; line-height: 1.6; overflow-x: auto;
      white-space: pre; border-top: 1px solid var(--outline); }
pre .err { color: #FF8A80; }
footer { margin-top: 56px; padding-top: 20px; border-top: 1px solid var(--outline);
         font-size: 13px; color: var(--text-dim); }
""" % (_LIGHT, _DARK, _DARK)
