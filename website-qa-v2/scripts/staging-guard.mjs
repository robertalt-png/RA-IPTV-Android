const raw = process.env.STAGING_BASE_URL || '';
if (process.env.STAGING_QA_ENABLED !== 'true') {
  console.error('BLOCKED: STAGING_QA_ENABLED=true is required.');
  process.exit(2);
}
let url;
try { url = new URL(raw); } catch { console.error('BLOCKED: invalid STAGING_BASE_URL'); process.exit(2); }
const host = url.hostname.toLowerCase();
if (host === 'nenotv.com' || host === 'www.nenotv.com') {
  console.error('BLOCKED: destructive staging QA can never target production nenotv.com.');
  process.exit(2);
}
if (url.protocol !== 'https:') {
  console.error('BLOCKED: staging commerce requires HTTPS.');
  process.exit(2);
}
if (!/staging|test|qa|acceptance/.test(host)) {
  console.error('BLOCKED: staging hostname must clearly identify itself as staging/test/qa/acceptance.');
  process.exit(2);
}
console.log(`Staging guard OK: ${url.origin}`);
