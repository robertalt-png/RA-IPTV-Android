"""Regression checks for mandatory website pairing (source-level guardrails)."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "android/app/src/main/java/com/robertalt/raiptv/MainActivity.java"
LINK = ROOT / "android/app/src/main/java/com/robertalt/raiptv/storage/AccountLinkStore.java"
ACCOUNT = ROOT / "android/app/src/main/java/com/robertalt/raiptv/AccountActivity.java"
PAIRING = ROOT / "android/app/src/main/java/com/robertalt/raiptv/PairingActivity.java"
PROFILE = ROOT / "android/app/src/main/java/com/robertalt/raiptv/ProfileActivity.java"


class GuestFirstLaunchChecks(unittest.TestCase):
    def test_new_install_requires_website_pairing(self):
        source = MAIN.read_text(encoding="utf-8")
        self.assertIn("if(!account.linked()&&!account.guest()&&!FamilyStore.active(this)){", source)
        self.assertIn("startActivity(firstRunIntent(this));", source)
        self.assertIn('putExtra("mandatory_login",true).putExtra("auto_web",true)', source)
        self.assertNotIn('if(!account.linked()&&!account.guest()&&!FamilyStore.active(this))account.startGuest();', source)

    def test_first_run_pairing_has_no_guest_bypass(self):
        source = PAIRING.read_text(encoding="utf-8")
        self.assertNotIn('guest.setTag("start_without_account")', source)
        self.assertIn('browserOpener.open(this,created.url)', source)
        self.assertIn('getBooleanExtra("auto_web",false)', source)

    def test_guest_access_remains_light_and_account_is_separate(self):
        source = LINK.read_text(encoding="utf-8")
        self.assertIn("public void startGuest()", source)
        self.assertIn("return usable()||guest();", source)
        self.assertIn('return !linked()&&prefs.getBoolean("guest",false)', source)

    def test_guest_reaches_local_playlist_setup(self):
        source = MAIN.read_text(encoding="utf-8")
        profile = PROFILE.read_text(encoding="utf-8")
        self.assertIn("if(!profiles.exists())startActivityForResult(new Intent(this,ProfileActivity.class),10)", source)
        self.assertIn("M3u", profile) if "M3u" in profile else self.assertIn("m3u", profile.lower())

    def test_my_sunnyiptv_entry_and_play_billing_remain(self):
        source = ACCOUNT.read_text(encoding="utf-8")
        self.assertIn('myNenoLabel()', source)
        self.assertIn('v->openWeb(myNenoUrl())', source)
        self.assertIn('play().loadProducts()', source)


if __name__ == "__main__":
    unittest.main()
