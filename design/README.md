# design

Source for the images the repo ships. Both are plain HTML in the website's style (paper `#E7E9EC`,
ink `#0B0C0F`, red `#FF2B1C`; Doto, Geist and Geist Mono from Google Fonts), rendered to PNG in a
browser.

| Source | Output | Size |
|---|---|---|
| `aura-android-use-website/architecture.html` (also the live `/architecture` page) | `.github/readme/architecture.png` | 1080 × 780 at device scale 2 |
| `design/og-card.html` (the link-preview card) | `aura-android-use-website/og.png` | 1200 × 630 at device scale 1 |

## Render

Serve the repo root over HTTP (fonts and relative images don't load from `file://`):

```
python -m http.server 4792 --bind 127.0.0.1
```

Open the page at exactly the size above (Playwright: `newContext({ viewport, deviceScaleFactor })`),
wait for `document.fonts.ready`, and screenshot the viewport.

## Assets

- `assets/phone-dots.png` is the website's own dot phone: the hero `canvas[data-canvas]` captured with a
  transparent background at device scale 3. Recapture it the same way if the hero phone changes.
- The diagram icons in `aura-android-use-website/icons/` are from [Lucide](https://lucide.dev) (ISC
  licence). Brand marks come from `aura-android-use-website/logos/`.

Change together: after editing either HTML, re-render its PNG in the same commit. After replacing
`og.png`, re-upload it as the repo's Social preview in GitHub Settings; GitHub doesn't read it from
the repo.
