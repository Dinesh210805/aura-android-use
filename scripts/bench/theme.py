"""One CSS block, shared by the live control UI and the generated static report.

Colours are AURA's own "Mono" palette (`ui/theme/Mono.kt`): warm off-white
canvas, hairline outlines, near-black ink, and blood red used for attention
only -- a failure, a live recording dot, a missing capability. Red is never
decorative here either.
"""

CSS = """
:root {
  --canvas: #FAFAF8;  --canvas-high: #FFFFFF;  --outline: #E8E8E4;
  --text: #111111;    --text-dim: #6B6B68;
  --ink: #0A0A0A;     --ink-soft: #171717;     --on-ink: #F5F5F2;
  --on-ink-dim: #9C9C98; --outline-ink: #2E2E2B;
  --blood: #A31621;   --blood-soft: #FBEBEC;
  --pass: #1B7F4C;    --pass-soft: #E9F4EE;
  --r-card: 24px; --r-small: 16px; --r-chip: 12px;
  --mono: ui-monospace, "SF Mono", "Cascadia Mono", Menlo, Consolas, monospace;
  --sans: -apple-system, BlinkMacSystemFont, "Segoe UI", Inter, system-ui, sans-serif;
}
* { box-sizing: border-box; }
body {
  margin: 0; background: var(--canvas); color: var(--text);
  font-family: var(--sans); font-size: 15px; line-height: 1.5;
  -webkit-font-smoothing: antialiased;
}
a { color: inherit; }
.wrap { max-width: 1100px; margin: 0 auto; padding: 40px 24px 96px; }

/* ---- masthead ------------------------------------------------------- */
.mast { display: flex; align-items: flex-end; justify-content: space-between;
        gap: 24px; flex-wrap: wrap; margin-bottom: 8px; }
.mast h1 { font-size: clamp(28px, 4vw, 44px); letter-spacing: -0.03em;
           margin: 0; font-weight: 620; }
.mast .sub { color: var(--text-dim); font-size: 14px; margin-top: 6px; }

/* ---- score slab ----------------------------------------------------- */
.slab { background: var(--ink); color: var(--on-ink); border-radius: var(--r-card);
        padding: 28px 32px; display: flex; gap: 40px; flex-wrap: wrap;
        align-items: baseline; margin: 24px 0 8px; }
.slab .big { font-size: 56px; font-weight: 660; letter-spacing: -0.04em;
             line-height: 1; font-variant-numeric: tabular-nums; }
.slab .unit { font-size: 20px; color: var(--on-ink-dim); font-weight: 500; }
.slab dl { margin: 0; display: flex; gap: 32px; flex-wrap: wrap; }
.slab dt { font-size: 11px; text-transform: uppercase; letter-spacing: .09em;
           color: var(--on-ink-dim); margin-bottom: 3px; }
.slab dd { margin: 0; font-size: 18px; font-variant-numeric: tabular-nums; }

/* ---- banner --------------------------------------------------------- */
.banner { border-radius: var(--r-small); padding: 14px 18px; margin: 16px 0;
          font-size: 14px; border: 1px solid var(--blood);
          background: var(--blood-soft); color: #5C0C13; }
.banner b { font-weight: 620; }

/* ---- task rows ------------------------------------------------------ */
.rows { border: 1px solid var(--outline); border-radius: var(--r-card);
        background: var(--canvas-high); overflow: hidden; margin-top: 20px; }
.row { display: grid; grid-template-columns: 26px 1fr auto auto;
       gap: 14px; align-items: center; padding: 13px 20px;
       border-top: 1px solid var(--outline); }
.row:first-child { border-top: 0; }
.row:hover { background: #FCFCFB; }
.row .n { font-family: var(--mono); font-size: 12px; color: var(--text-dim);
          font-variant-numeric: tabular-nums; }
.row .name { font-family: var(--mono); font-size: 13.5px; }
.row .goal { display: block; font-family: var(--sans); font-size: 12.5px;
             color: var(--text-dim); margin-top: 2px;
             overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.row.pending .name { color: var(--text-dim); }

.pill { font-size: 11px; font-weight: 600; letter-spacing: .04em;
        padding: 4px 11px; border-radius: 999px; white-space: nowrap;
        text-transform: uppercase; }
.pill.pass { background: var(--pass-soft); color: var(--pass); }
.pill.fail { background: var(--blood-soft); color: var(--blood); }
.pill.none { background: #F2F2EF; color: var(--text-dim); }
.pill.blind { background: transparent; color: var(--blood);
              border: 1px solid var(--blood); }

button { font: inherit; cursor: pointer; border-radius: 999px;
         border: 1px solid var(--ink); background: var(--ink);
         color: var(--on-ink); padding: 7px 18px; font-size: 13px;
         font-weight: 550; transition: background .15s, transform .06s; }
button:hover { background: var(--ink-soft); }
button:active { transform: translateY(1px); }
button:disabled { opacity: .32; cursor: not-allowed; }
button.ghost { background: transparent; color: var(--text);
               border-color: var(--outline); }
button.ghost:hover { background: #F2F2EF; }

/* ---- detail page ---------------------------------------------------- */
.back { font-family: var(--mono); font-size: 12.5px; color: var(--text-dim);
        text-decoration: none; display: inline-block; margin-bottom: 20px; }
.back:hover { color: var(--text); }
video { width: 100%; border-radius: var(--r-small); background: #000;
        border: 1px solid var(--outline); display: block; }
.vid-note { font-size: 12.5px; color: var(--text-dim); margin: 8px 0 0; }
details.log { border: 1px solid var(--outline); border-radius: var(--r-small);
              background: var(--canvas-high); margin-top: 16px; overflow: hidden; }
details.log > summary { cursor: pointer; padding: 13px 18px; font-size: 13px;
                        font-weight: 550; list-style: none; }
details.log > summary::-webkit-details-marker { display: none; }
details.log > summary::before { content: "+  "; font-family: var(--mono);
                                color: var(--text-dim); }
details.log[open] > summary::before { content: "\2212  "; }
pre { margin: 0; padding: 16px 18px; background: var(--ink); color: var(--on-ink);
      font-family: var(--mono); font-size: 12px; line-height: 1.65;
      overflow-x: auto; white-space: pre; border-top: 1px solid var(--outline); }
pre .err { color: #FF8A93; }

.meta { display: flex; gap: 28px; flex-wrap: wrap; margin: 20px 0;
        padding: 16px 20px; border: 1px solid var(--outline);
        border-radius: var(--r-small); background: var(--canvas-high); }
.meta div { font-size: 13px; }
.meta dt { font-size: 10.5px; text-transform: uppercase; letter-spacing: .09em;
           color: var(--text-dim); }
.meta dd { margin: 3px 0 0; font-variant-numeric: tabular-nums;
           font-family: var(--mono); font-size: 13px; }
.goalbox { font-size: 16px; line-height: 1.55; margin: 0 0 4px;
           padding: 18px 22px; border-radius: var(--r-small);
           background: var(--canvas-high); border: 1px solid var(--outline);
           border-left: 3px solid var(--ink); }
footer { margin-top: 56px; padding-top: 20px; border-top: 1px solid var(--outline);
         font-size: 12.5px; color: var(--text-dim); }
"""
