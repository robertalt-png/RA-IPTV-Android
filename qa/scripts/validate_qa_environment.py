#!/usr/bin/env python3
from pathlib import Path
import json
required=[
 'qa/mock_iptv_server.py',
 'qa/prepare_android_tests.py',
 'qa/androidTest/java/com/robertalt/raiptv/NenoTvAutomatedQaTest.java',
 'qa/config/test-matrix.yml',
 'qa/config/release-gates.yml',
 'qa/config/network-profiles.yml',
 'qa/catalog/test-catalog.json',
 'qa/scripts/validate_test_catalog.py',
 'qa/scripts/discover_locales.py',
 'qa/scripts/check_translations.py',
 'qa/scripts/generate_matrix.py',
 'qa/scripts/scan_logcat.py',
 'qa/scripts/check_apk_manifest.sh',
 'qa/scripts/check_pro_aab_contract.py',
 'qa/scripts/run_emulator_qa.sh',
]
missing=[p for p in required if not Path(p).exists()]
if missing:
    raise SystemExit('Missing QA v2 files: '+', '.join(missing))
summary={
 'qa_version':2,
 'required_files':len(required),
 'missing':missing,
 'canonical_project_catalog_count':288,
 'canonical_project_catalog_sha256':'21a6ee5cdd473e0677997303791a1b9e595b16e386acbf9fba74bebf1a2a39ba',
}
print(json.dumps(summary,indent=2))
