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
    private UiObject2 contains(String value) { return waitObj(By.textContains(value), 12000); }

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

    private void openMainXtream() {
        seedXtream();
        launch(MainActivity.class);
    }

    private void openMainM3u() {
        seedM3u();
        launch(MainActivity.class);
    }

    private void clickNav(String id) {
        res(id).click();
        SystemClock.sleep(400);
    }

    private void openCardAndPlay(String title) {
        contains(title).click();
        SystemClock.sleep(300);
        res("heroAction").click();
        waitObj(By.res(PKG, "playerTitle"), 12000);
    }

    @Test public void firstRun_language_and_xtream_profile_connection() {
        launch(SplashActivity.class);
        contains("Choose your language");
        contains("Nederlands").click();

        contains("TV-bron");
        setText("nameField", "QA profile");
        setText("serverField", BASE);
        setText("userField", "qa");
        setText("passField", "qa-pass");
        device.pressBack(); // close IME so status feedback is visible
        res("testButton").click();
        long connectionDeadline=SystemClock.uptimeMillis()+15000;
        boolean connected=false;
        while(SystemClock.uptimeMillis()<connectionDeadline){
            UiObject2 state=device.findObject(By.res(PKG,"profileStatus"));
            String text=state==null?null:state.getText();
            if(text!=null&&text.contains("Verbinding OK")){connected=true;break;}
            if(text!=null&&(text.contains("mislukt")||text.contains("Failed")||text.contains("fout")))
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
        contains("QA NenoTV Live NL");

        // v0.12.6.6 regression: player controls must auto-hide during playback.
        assertTrue("Player controls did not auto-hide", device.wait(Until.gone(By.res(PKG, "playPauseButton")), 7000));

        int cx = device.getDisplayWidth() / 2;
        int cy = device.getDisplayHeight() / 2;
        device.click(cx, cy);
        res("playPauseButton");

        // When paused, controls must stay visible.
        res("playPauseButton").click();
        SystemClock.sleep(4200);
        assertNotNull("Controls disappeared while paused", device.findObject(By.res(PKG, "playPauseButton")));
        res("playPauseButton").click();

        // Player favorite button must toggle visually.
        res("favoriteButton").click();
        assertEquals("♥", res("favoriteButton").getText());
        device.pressBack();
        SystemClock.sleep(500);

        clickNav("navEpg");
        contains("QA NenoTV Live NL");
        contains("QA Now");

        clickNav("navMovies");
        contains("QA NenoTV Test Movie");
        openCardAndPlay("QA NenoTV Test Movie");
        contains("QA NenoTV Test Movie");
        SystemClock.sleep(5500);
        device.pressBack();

        clickNav("navSeries");
        contains("QA NenoTV Test Series").click();
        SystemClock.sleep(300);
        res("heroAction").click();
        contains("QA Pilot");
        contains("QA Pilot").click();
        SystemClock.sleep(250);
        res("heroAction").click();
        contains("QA Pilot");
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

        res("searchToggle").click();
        UiObject2 search = res("searchBox");
        search.setText("QA NenoTV Test Movie");
        SystemClock.sleep(1100);
        assertTrue("Search result disappeared", device.hasObject(By.textContains("QA NenoTV Test Movie")));

        device.setOrientationLeft();
        SystemClock.sleep(900);
        assertTrue("NenoTV lost foreground after rotation", device.hasObject(By.pkg(PKG)));
        device.setOrientationNatural();
        SystemClock.sleep(900);

        device.pressHome();
        SystemClock.sleep(800);
        Intent i = app.getPackageManager().getLaunchIntentForPackage(PKG);
        assertNotNull(i);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        app.startActivity(i);
        assertTrue("NenoTV did not resume", device.wait(Until.hasObject(By.pkg(PKG)), 8000));

        res("menuButton").click();
        contains("Instellingen").click();
        contains("Instellingen");
    }

    @Test public void invalid_xtream_credentials_are_reported_without_crash() {
        SettingsStore.setPrimaryLanguage(app, "nl");
        launch(ProfileActivity.class);

        setText("nameField", "bad");
        setText("serverField", BASE);
        setText("userField", "wrong");
        setText("passField", "wrong");
        res("testButton").click();

        contains("niet geaccepteerd");
        assertTrue(device.hasObject(By.pkg(PKG)));
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
