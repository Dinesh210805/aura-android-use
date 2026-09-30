# AURA website

Static site, no build step. Hosted on Vercel.

- `index.html` — home page
- `download.html` — install guide
- `support.js` — page runtime (renders the `<x-dc>` markup; don't edit by hand)
- `aura-film.js` — the scroll animation on the home page
- `media/`, `logos/` — videos, posters, icons

## Preview locally

```bash
npx serve aura-android-use-website
```

Open http://localhost:3000. The `/_vercel/insights/script.js` 404 in the console is expected
locally; that script only exists on Vercel.

## Vercel setup (one time)

1. Import the repo at vercel.com/new.
2. **Root Directory:** `aura-android-use-website`. Framework preset: **Other**. Leave build and output commands empty.
3. Deploy.
4. Project → **Analytics** → **Enable**, then redeploy once so the analytics route goes live.

`vercel.json` turns on clean URLs (`/download`), caches media for a week, and skips a rebuild when
nothing in `aura-android-use-website/` changed since the last successful deploy.
