// node tools/tests/source-parse-test.js — checks server/sunnyiptv-source-parse.js against the shared cases.
const parse = require('../../server/sunnyiptv-source-parse.js').parse;
const cases = require('./source-parse-cases.json');
let failed = 0;
for (const c of cases) {
  const got = parse(c.text, !!c.ocr);
  for (const [k, v] of Object.entries(c.expect)) {
    if ((got[k] ?? '') !== v) { failed++; console.log(`FAIL ${c.name}: ${k}=${JSON.stringify(got[k])} expected ${JSON.stringify(v)}`); }
  }
}
console.log(failed ? `${failed} failures` : `Source parse (JS): ${cases.length} cases passed`);
process.exit(failed ? 1 : 0);
