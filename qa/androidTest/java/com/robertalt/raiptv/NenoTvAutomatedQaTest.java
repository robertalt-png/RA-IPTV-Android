package com.robertalt.raiptv;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.robertalt.raiptv.model.Profile;
import com.robertalt.raiptv.storage.SecureProfileStore;
import com.robertalt.raiptv.storage.SettingsStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class NenoTvAutomatedQaTest {
    private static final String PKG = "com.robertalt.raiptv";
    private static final String BASE = "http://10.0.2.2:8787";

    private UiDevice device;
    private Context app;

    @Before public void before() throws Exception {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        app = ApplicationProvider.getApplicationContext();
        resetAppState();
        device.setOrientationNatural();
    }

    @After public void after() throws Exception {
        try { device.unfreezeRotation(); } catch (Throwable ignored) {}
        device.pressHome();
    }

    private void resetAppState() {
        app.getSharedPreferences("nivaro_settings", Context.MODE_PRIVATE).edit().clear().commit();
        // Core navigation tests must not be diverted into Picture-in-Picture.
        // PiP has its own dedicated coverage; normal Back should return to MainActivity.
        SettingsStore.prefs(app).edit().putBoolean("pip", false).commit();
        app.getSharedPreferences("profile", Context.MODE_PRIVATE).edit().clear().commit();
        app.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit();
        app.getSharedPreferences("nenotv_entitlement", Context.MODE_PRIVATE).edit().clear().commit();
        for (String db : app.databaseList()) app.deleteDatabase(db);
    }

    private void launch(Class<?> cls) {
        Intent i = new Intent(app, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        app.startActivity(i);
        assertTrue("NenoTV package did not become visible", device.wait(Until.hasObject(By.pkg(PKG)), 12000));
    }

    private UiObject2 waitObj(BySelector selector, long timeoutMs) {
        assertTrue("UI object not found: " + selector, device.wait(Until.hasObject(selector), timeoutMs));
        return device.findObject(selector);
    }

    private UiObject2 res(String id) { return waitObj(By.res(PKG, id), 12000); }
    private UiObject2 contains(String value) { return waitObj(By.textContains(value), 25000); }

    private UiObject2 findFresh(BySelector selector, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            UiObject2 object = device.findObject(selector);
            if (object != null) {
                try {
                    object.getVisibleBounds();
                    return object;
                } catch (Throwable ignored) {
                    SystemClock.sleep(250);
                }
            } else {
                SystemClock.sleep(250);
            }
        }
        return null;
    }

    private boolean waitMainChrome(long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            if (device.hasObject(By.res(PKG, "navHome")) || device.hasObject(By.res(PKG, "menuButton"))) {
                return true;
            }
            SystemClock.sleep(250);
        }
        return false;
    }

    private void setText(String id, String value) {
        UiObject2 o = res(id);
        o.click();
        o.setText(value);
    }

    private void seedXtream() {
        SettingsStore.setPrimaryLanguage(app, "nl");
        Profile p = new Profile();
        p.type = Profile.Type.XTREAM;
        p.name = "NenoTV QA Xtream";
        p.server = BASE;
        p.username = "qa";
        p.password = "qa-pass";
        p.m3uUrl = "";
        p.epgUrl = "";
        p.bridgeUrl = "";
        p.bridgeToken = "";
        new SecureProfileStore(app).save(p);
    }

    private void seedM3u() {
        SettingsStore.setPrimaryLanguage(app, "nl");
        Profile p = new Profile();
        p.type = Profile.Type.M3U;
        p.name = "NenoTV QA M3U";
        p.server = "";
        p.username = "";
        p.password = "";
        p.m3uUrl = BASE + "/playlist.m3u";
        p.epgUrl = BASE + "/epg.xml";
        p.bridgeUrl = "";
        p.bridgeToken = "";
        new SecureProfileStore(app).save(p);
    }

    private void launchMain() {
        Intent i = new Intent(app, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        app.startActivity(i);
        if (!waitMainChrome(30000)) {
            device.pressHome();
            SystemClock.sleep(750);
            app.startActivity(i);
        }
        assertTrue("MainActivity navigation did not become visible", waitMainChrome(30000));
        device.waitForIdle(1500);
    }

    private void openMainXtream() {
        seedXtream();
        launchMain();
    }

    private void openMainM3u() {
        seedM3u();
        launchMain();
    }

    private void clickNav(String id) {
        tap(By.res(PKG, id), id);
        SystemClock.sleep(400);
    }

    private void tap(UiObject2 object) {
        Rect bounds = object.getVisibleBounds();
        device.click(bounds.centerX(), bounds.centerY());
    }

    private void tap(BySelector selector, String description) {
        UiObject2 object = findFresh(selector, 25000);
        assertNotNull("UI object not found: " + description, object);
        tap(object);
    }

    private String visibleText(BySelector selector) {
        UiObject2 object = findFresh(selector, 500);
        if (object == null) return "";
        String text = object.getText();
        if (text != null && !text.isEmpty()) return text;
        String description = object.getContentDescription();
        return description == null ? "" : description;
    }

    private void revealProfileStatus() {
        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        for (int attempt = 0; attempt < 3; attempt++) {
            UiObject2 status = device.findObject(By.res(PKG, "profileStatus"));
            if (status != null) {
                Rect bounds = status.getVisibleBounds();
                if (bounds.height() > 0 && bounds.top >= 0 && bounds.bottom <= height) return;
            }
            device.swipe(width / 2, height * 3 / 4, width / 2, height / 3, 24);
            device.waitForIdle(750);
        }
        assertNotNull("Profile status did not become visible",
                findFresh(By.res(PKG, "profileStatus"), 3000));
    }

    private void assertPlayerTitle(String expected) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        String actual = "";
        while (SystemClock.uptimeMillis() < deadline) {
            actual = visibleText(By.res(PKG, "playerTitle"));
            if (actual.contains(expected)) return;
            SystemClock.sleep(100);
        }
        fail("Player title did not contain " + expected + ": " + actual);
    }

    private boolean hasText(String value) {
        return device.hasObject(By.textContains(value)) || device.hasObject(By.descContains(value));
    }

    private boolean hasAnyText(String... values) {
        for (String value : values) {
            if (hasText(value)) return true;
        }
        return false;
    }

    private String firstVisibleText(String... values) {
        for (String value : values) {
            if (hasText(value)) return value;
        }
        return "";
    }

    private boolean isSuccessStatus(String text) {
        String lower = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("verbinding ok") || lower.contains("verbinding gelukt") ||
                lower.contains("geslaagd") || lower.contains("success") || lower.contains("succes");
    }

    private boolean isFailureStatus(String text) {
        String lower = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("mislukt") || lower.contains("failed") || lower.contains("fout") ||
                lower.contains("login") || lower.contains("geaccepteerd") || lower.contains("ongeldig") ||
                lower.contains("invalid") || lower.contains("geweigerd") || lower.contains("denied");
    }

    private boolean isPendingStatus(String text) {
        String lower = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("testen") || lower.contains("testing") ||
                lower.contains("verbinden") || lower.contains("connecting");
    }

    private void waitMainInteractive() {
        if (!waitMainChrome(30000)) {
            device.pressBack();
            SystemClock.sleep(750);
        }
        assertTrue("MainActivity navigation did not return", waitMainChrome(30000));
        device.waitForIdle(1500);
        SystemClock.sleep(350);
    }

    private void backToMainFromPlayer() {
        for (int i = 0; i < 4 && !waitMainChrome(1000); i++) {
            device.pressBack();
            device.waitForIdle(1200);
            SystemClock.sleep(500);
        }
        assertTrue("Player did not return to MainActivity navigation", waitMainChrome(30000));
        device.waitForIdle(1500);
        SystemClock.sleep(350);
    }

    private void openCardAndPlay(String title) {
        boolean opened = false;
        for (int attempt = 0; attempt < 3 && !opened; attempt++) {
            if (findFresh(By.res(PKG, "heroAction"), 1500) == null) {
                tap(By.textContains(title), title);
                device.waitForIdle(2000);
                SystemClock.sleep(1000);
            }
            tap(By.res(PKG, "heroAction"), "heroAction");
            opened = device.wait(Until.hasObject(By.res(PKG, "playerTitle")), 45000);
            if (!opened && findFresh(By.res(PKG, "heroAction"), 1500) == null) {
                device.pressBack();
                device.waitForIdle(1500);
                SystemClock.sleep(500);
            }
        }
        assertTrue("Player did not open for " + title, opened);
        assertPlayerTitle(title);
    }

    private UiObject2 showPlayerControls() {
        UiObject2 controls = findFresh(By.res(PKG, "playPauseButton"), 1000);
        for (int i = 0; i < 4 && controls == null; i++) {
            device.click(device.getDisplayWidth() / 2, device.getDisplayHeight() / 2);
            device.waitForIdle(500);
            controls = findFresh(By.res(PKG, "playPauseButton"), 2500);
        }
        assertNotNull("Player controls did not become visible", controls);
        return controls;
    }

    @Test public void firstRun_language_and_xtream_profile_connection() {
        launch(SplashActivity.class);
        contains("Choose your language");
        tap(By.textContains("Nederlands"), "Nederlands");

        contains("TV-bron");
        setText("nameField", "QA profile");
        setText("serverField", BASE);
        setText("userField", "qa");
        setText("passField", "qa-pass");
        device.pressBack(); // close IME so status feedback is visible
        tap(By.res(PKG, "testButton"), "testButton");
        revealProfileStatus();
        long connectionDeadline=SystemClock.uptimeMillis()+35000;
        boolean connected=false;
        while(SystemClock.uptimeMillis()<connectionDeadline){
            String text=visibleText(By.res(PKG,"profileStatus"));
            if(isSuccessStatus(text) || hasAnyText("Verbinding OK", "Verbinding gelukt", "geslaagd",
                    "Geslaagd", "success", "Success", "succes", "Succes")){connected=true;break;}
            if(isFailureStatus(text))
                fail("Connection test reported failure: "+text);
            SystemClock.sleep(200);
        }
        assertTrue("Xtream test endpoint succeeded but success status was not shown",connected);

        res("saveButton").click();
        contains("NenoTV");
        clickNav("navLive");
        contains("QA NenoTV Live NL");
    }

    @Test public void xtream_live_epg_movies_series_and_player_controls() {
        openMainXtream();

        clickNav("navLive");
        contains("QA NenoTV Live NL");
        openCardAndPlay("QA NenoTV Live NL");

        // v0.12.6.6 regression: player controls must auto-hide during playback.
        assertTrue("Player controls did not auto-hide", device.wait(Until.gone(By.res(PKG, "playPauseButton")), 7000));

        int cx = device.getDisplayWidth() / 2;
        int cy = device.getDisplayHeight() / 2;
        device.click(cx, cy);
        showPlayerControls();

        // When paused, controls must stay visible.
        tap(By.res(PKG, "playPauseButton"), "playPauseButton");
        SystemClock.sleep(4200);
        assertNotNull("Controls disappeared while paused", device.findObject(By.res(PKG, "playPauseButton")));
        showPlayerControls();
        tap(By.res(PKG, "playPauseButton"), "playPauseButton");

        // Player favorite button must toggle visually.
        tap(By.res(PKG, "favoriteButton"), "favoriteButton");
        assertEquals("♥", visibleText(By.res(PKG, "favoriteButton")));
        backToMainFromPlayer();

        clickNav("navEpg");
        contains("QA NenoTV Live NL");
        contains("QA Now");

        clickNav("navMovies");
        contains("QA NenoTV Test Movie");
        openCardAndPlay("QA NenoTV Test Movie");
        SystemClock.sleep(5500);
        backToMainFromPlayer();

        clickNav("navSeries");
        tap(By.textContains("QA NenoTV Test Series"), "QA NenoTV Test Series");
        SystemClock.sleep(300);
        tap(By.res(PKG, "heroAction"), "heroAction");
        contains("QA Pilot");
        tap(By.textContains("QA Pilot"), "QA Pilot");
        SystemClock.sleep(250);
        tap(By.res(PKG, "heroAction"), "heroAction");
        assertTrue("Series episode player did not open",
                device.wait(Until.hasObject(By.res(PKG, "playerTitle")), 30000));
        assertPlayerTitle("QA Pilot");
    }

    @Test public void m3u_live_and_xmltv_flow() {
        openMainM3u();

        clickNav("navLive");
        contains("QA NenoTV Live NL");

        clickNav("navEpg");
        contains("QA NenoTV Live NL");
        contains("QA Now");

        // M3U profile is intentionally live-only in the current Free app.
        clickNav("navMovies");
        SystemClock.sleep(1200);
        assertFalse("Xtream VOD leaked into M3U profile", device.hasObject(By.textContains("QA NenoTV Test Movie")));
    }

    @Test public void search_rotation_background_resume_and_settings() throws Exception {
        openMainXtream();

        clickNav("navMovies");
        contains("QA NenoTV Test Movie");

        tap(By.res(PKG, "searchToggle"), "searchToggle");
        UiObject2 search = findFresh(By.res(PKG, "searchBox"), 15000);
        assertNotNull("Search box did not become visible", search);
        search.setText("QA NenoTV Test Movie");
        SystemClock.sleep(1100);
        assertTrue("Search result disappeared", device.hasObject(By.textContains("QA NenoTV Test Movie")));
        device.pressBack(); // close IME before rotating; the search result must remain visible
        SystemClock.sleep(500);
        assertTrue("Search result disappeared after keyboard close", device.hasObject(By.textContains("QA NenoTV Test Movie")));

        device.setOrientationLeft();
        assertTrue("NenoTV lost foreground after rotation",
                device.wait(Until.hasObject(By.pkg(PKG)), 25000));
        device.setOrientationNatural();
        waitMainInteractive();

        device.pressHome();
        SystemClock.sleep(800);
        Intent i = app.getPackageManager().getLaunchIntentForPackage(PKG);
        assertNotNull(i);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        app.startActivity(i);
        assertTrue("NenoTV did not resume", device.wait(Until.hasObject(By.res(PKG, "navHome")), 12000));

        tap(By.res(PKG, "menuButton"), "menuButton");
        tap(By.textContains("Instellingen"), "Instellingen");
        contains("Instellingen");
    }

    @Test public void invalid_xtream_credentials_are_reported_without_crash() {
        SettingsStore.setPrimaryLanguage(app, "nl");
        launch(ProfileActivity.class);

        setText("nameField", "bad");
        setText("serverField", BASE);
        setText("userField", "wrong");
        setText("passField", "wrong");
        device.pressBack(); // close IME so the status view is actually visible to accessibility
        SystemClock.sleep(250);
        tap(By.res(PKG, "testButton"), "testButton");
        revealProfileStatus();

        long deadline=SystemClock.uptimeMillis()+35000;
        String message=null;
        while(SystemClock.uptimeMillis()<deadline){
            String statusText=visibleText(By.res(PKG,"profileStatus"));
            String visibleFailure=firstVisibleText("mislukt", "Mislukt", "failed", "Failed", "fout",
                    "Fout", "ongeldig", "Ongeldig", "invalid", "Invalid", "geweigerd", "Geweigerd",
                    "denied", "Denied", "login", "Login");
            message = !statusText.isEmpty() ? statusText : visibleFailure;
            if(!message.isEmpty()&&!isPendingStatus(message))break;
            SystemClock.sleep(200);
        }
        assertNotNull("No credential error status shown", message);
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        assertFalse("Credential test never left pending state: " + message,
                isPendingStatus(message));
        assertTrue("Invalid credentials were not reported: " + message, isFailureStatus(message));
        assertTrue("NenoTV disappeared after invalid login", device.wait(Until.hasObject(By.pkg(PKG)), 5000));
    }

    @Test public void layout_sanity_keeps_primary_controls_on_screen() {
        openMainXtream();

        String[] ids = {"menuButton", "navHome", "navLive", "navEpg", "navMovies", "navSeries"};
        Rect display = new Rect(0, 0, device.getDisplayWidth(), device.getDisplayHeight());

        for (String id : ids) {
            UiObject2 o = res(id);
            Rect b = o.getVisibleBounds();
            assertTrue(id + " has empty bounds", b.width() > 0 && b.height() > 0);
            assertTrue(id + " is outside display: " + b, Rect.intersects(display, b));
        }
    }
}

