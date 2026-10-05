import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'android/app/src/main/java/com/robertalt/raiptv'
APPROVED = {'film-46670912', 'film-29654117', 'film-52325031'}


class FamilyDemoTest(unittest.TestCase):
    def setUp(self):
        self.catalogue = json.loads((ROOT / 'demo/catalogue.json').read_text(encoding='utf-8'))
        self.source = (JAVA / 'DemoSource.java').read_text(encoding='utf-8')
        self.playlist = (ROOT / 'demo/nenotv-demo.m3u').read_text(encoding='utf-8')

    def test_only_selected_animations(self):
        self.assertEqual(APPROVED, {e['id'] for e in self.catalogue})
        self.assertEqual(3, len(self.catalogue))
        self.assertTrue(all(e['type'] == 'vod' for e in self.catalogue))
        self.assertIn('LIVE_COUNT=0;', self.source)
        self.assertIn('FILM_COUNT=3;', self.source)

    def test_credits_and_streams_retained(self):
        for entry in self.catalogue:
            for field in ('url', 'license_url', 'source'):
                self.assertTrue(entry[field].startswith('https://'))
            self.assertTrue(entry['credit'])
            self.assertTrue(entry['license'].startswith('CC BY'))
            self.assertIn(entry['credit'], self.source)
            self.assertIn(entry['license_url'], self.source)

    def test_playlist_matches_catalogue(self):
        lines = self.playlist.splitlines()
        self.assertEqual('#EXTM3U', lines[0])
        self.assertEqual(7, len(lines))
        for i, entry in enumerate(self.catalogue):
            self.assertIn('tvg-id="' + entry['id'] + '"', lines[1 + i * 2])
            self.assertTrue(lines[1 + i * 2].endswith(',' + entry['name']))
            self.assertEqual(entry['url'], lines[2 + i * 2])

    def test_packaged_and_server_mirrors_match(self):
        literal = re.search(r'public static final String PLAYLIST=(".*");', self.source).group(1)
        self.assertEqual(self.playlist, json.loads(literal))
        self.assertEqual(self.playlist, (ROOT / 'server/nenotv-demo.m3u').read_text(encoding='utf-8'))
        server = json.loads((ROOT / 'server/nenotv-demo-catalog.json').read_text(encoding='utf-8'))
        self.assertEqual(APPROVED, {e['tvgId'] for e in server})
        self.assertEqual([e['url'] for e in self.catalogue], [e['url'] for e in server])

    def test_no_removed_decoration(self):
        ids = set(re.findall(r'if\("([^"]+)"\.equals\(e.tvgId\)\)', self.source))
        self.assertEqual(APPROVED, ids)

    def test_legacy_demo_uses_packaged_selection(self):
        http = (JAVA / 'net/HttpText.java').read_text(encoding='utf-8')
        self.assertIn('SiteEndpoints.isDemoUrl(url))return com.nenotv.player.DemoSource.PLAYLIST', http)
        endpoints = (JAVA / 'SiteEndpoints.java').read_text(encoding='utf-8')
        self.assertIn('DEMO_URL.equals(url)', endpoints)
        self.assertNotIn('url.contains(', endpoints)
        client = (JAVA / 'entitlement/CatalogPackageClient.java').read_text(encoding='utf-8')
        self.assertIn('if(com.nenotv.player.DemoPolicy.isDemo(profile))return false;', client)

    def test_demo_cache_rebuilt_without_live_channels(self):
        main = (JAVA / 'MainActivity.java').read_text(encoding='utf-8')
        helper = (JAVA / 'ProfileCacheKey.java').read_text(encoding='utf-8')
        self.assertIn('family-demo-v1', helper)
        self.assertIn('ProfileCacheKey.of(profile)', main)
        self.assertIn('for(String type:new String[]{"live","vod","series"})', main)
        self.assertIn('searchIndex.replaceSection(key,type,indexProvider.items(type,"all"));', main)

    def test_test_runner_matches_build_version(self):
        gradle = (ROOT / 'android/app/build.gradle').read_text(encoding='utf-8')
        version = re.search(r"versionName '([^']+)'", gradle).group(1)
        code = re.search(r'versionCode (\d+)', gradle).group(1)
        workflow = (ROOT / '.github/workflows/build-nenotv.yml').read_text(encoding='utf-8')
        runner = (ROOT / 'tools/qa-one-app.sh').read_text(encoding='utf-8')
        for edition in ('Light', 'Pro'):
            name = f'SunnyIPTV-{edition}-v{version}-vc{code}-TEST-SIGNED.apk'
            self.assertIn(name, workflow)
            self.assertIn(name, runner)


if __name__ == '__main__':
    unittest.main()
