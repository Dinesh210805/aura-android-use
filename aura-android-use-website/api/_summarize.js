const TZ_COUNTRY = require('./_tz-country.json');

/* Android's Locale.toString(): "en_IN", "ta_IN", "en_US_#Latn", "hi". */
function regionOf(locale) {
  const m = /^[a-zA-Z]{2,3}[_-]([A-Za-z]{2}|\d{3})(?:$|[_-])/.exec(locale || '');
  return m ? m[1].toUpperCase() : null;
}

/**
 * Turns `devices/{uid}` rows into the public totals the download page shows.
 * - Contract: release builds only; one handset per `deviceHash`, keeping its newest row, and a row
 *   with no hash stands alone; country from `timeZone`, else the `locale` region. A handset with
 *   no country counts towards `installs` but not `countries`.
 * - Change together: `toHandsets`, `countryOf` and `exportPublicStats` in the owner dashboard's
 *   app.js, which must count the same way, and `buildStats` in download.html, which reads this shape.
 */
function summarize(rows, asOf) {
  const handsets = new Map();
  for (const r of rows) {
    if (r.buildType === 'debug') continue;
    const key = r.deviceHash ? `h:${r.deviceHash}:${r.buildType}` : `u:${r.id}`;
    const held = handsets.get(key);
    if (!held || r.lastSeen > held.lastSeen) handsets.set(key, r);
  }
  const counts = {};
  for (const u of handsets.values()) {
    const code = TZ_COUNTRY[u.timeZone] || regionOf(u.locale);
    if (code) counts[code] = (counts[code] || 0) + 1;
  }
  const countries = Object.fromEntries(Object.entries(counts).sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])));
  return { asOf, installs: handsets.size, countryCount: Object.keys(countries).length, countries };
}

module.exports = { summarize };
