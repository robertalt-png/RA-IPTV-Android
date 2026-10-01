import http from 'k6/http';
import { check, sleep } from 'k6';
export const options = {
  scenarios: {
    smoke: { executor: 'constant-vus', vus: 2, duration: '30s' },
    normal: { executor: 'ramping-vus', startVUs: 0, stages: [
      { duration: '30s', target: 10 }, { duration: '60s', target: 10 }, { duration: '30s', target: 0 }
    ] }
  },
  thresholds: { http_req_failed: ['rate<0.01'], http_req_duration: ['p(95)<2000'] }
};
const base = __ENV.STAGING_BASE_URL;
export default function() {
  for (const path of ['/', '/pricing/', '/wp-json/nenotv/v1/health']) {
    const r = http.get(base + path, { redirects: 3 });
    check(r, { 'status < 500': x => x.status < 500 });
  }
  sleep(1);
}
