package com.fongmi.android.tv.ui.activity;

import static org.junit.Assert.*;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.github.catvod.Init;
import com.github.catvod.crawler.Spider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = SettingDriveLoginTest.TestApp.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class SettingDriveLoginTest {

    public static class TestApp extends App {
        @Override public void onCreate() { Init.set(this); }
    }

    public static class TestActivity extends SettingActivity {
        @Override protected void initView(Bundle state) {}
        @Override protected boolean customWall() { return false; }
    }

    private Object previousSites;
    private Object previousHome;
    private ActivityController<TestActivity> controller;
    private TestActivity activity;
    private View button;
    private final CountDownLatch release = new CountDownLatch(1);

    @Before
    public void setUp() {
        previousSites = ReflectionHelpers.getField(VodConfig.get(), "sites");
        previousHome = ReflectionHelpers.getField(VodConfig.get(), "home");
        ReflectionHelpers.setField(VodConfig.get(), "sites", List.of());
        controller = Robolectric.buildActivity(TestActivity.class);
        activity = controller.get();
        activity.setTheme(com.google.android.material.R.style.Theme_MaterialComponents_DayNight_NoActionBar);
        controller.setup().visible();
        button = activity.findViewById(R.id.driveLogin);
        assertNotNull(button);
        assertTrue(button.isFocusable());
    }

    @After
    public void tearDown() {
        release.countDown();
        if (!activity.isDestroyed()) controller.pause().stop().destroy();
        ReflectionHelpers.setField(VodConfig.get(), "sites", previousSites);
        ReflectionHelpers.setField(VodConfig.get(), "home", previousHome);
    }

    @Test
    public void clickOpensNativeConfigurationWithoutChangingHome() throws Exception {
        Site home = new Site();
        home.setKey("webhome_eclipse");
        ReflectionHelpers.setField(VodConfig.get(), "home", home);
        installConfig("{\"class\":[{\"type_id\":\"quark\",\"type_name\":\"Quark\"}]}", false);
        button.performClick();
        awaitCompletion();
        Intent intent = shadow().getNextStartedActivity();
        assertEquals(VodActivity.class.getName(), intent.getComponent().getClassName());
        assertEquals("configuration", intent.getStringExtra("key"));
        assertSame(home, VodConfig.get().getHome());
    }

    @Test
    public void missingConfigShowsErrorAndRestoresButton() throws Exception {
        button.performClick();
        awaitCompletion();
        assertTrue(ShadowToast.getTextOfLatestToast().contains("no drive configuration center"));
        assertNull(shadow().getNextStartedActivity());
    }

    @Test
    public void emptyCategoriesShowErrorInsteadOfOpening() throws Exception {
        installConfig("{\"class\":[]}", false);
        button.performClick();
        awaitCompletion();
        assertTrue(ShadowToast.getTextOfLatestToast().contains("no categories"));
        assertNull(shadow().getNextStartedActivity());
    }

    @Test
    public void leavingSettingsDuringLoadingDoesNotOpenAnotherScreen() throws Exception {
        installConfig("{\"class\":[{\"type_id\":\"quark\",\"type_name\":\"Quark\"}]}", true);
        button.performClick();
        assertFalse(button.isEnabled());
        controller.pause().stop().destroy();
        release.countDown();
        awaitCompletion();
        assertNull(shadow().getNextStartedActivity());
    }

    private void installConfig(String json, boolean wait) {
        Site config = new Site() {
            @Override public Site recent() { return this; }
            @Override public Spider spider() {
                return new Spider() {
                    @Override public String homeContent(boolean filter) throws Exception {
                        if (wait) assertTrue(release.await(3, TimeUnit.SECONDS));
                        return json;
                    }
                };
            }
        };
        config.setApi("csp_Config");
        config.setType(3);
        config.setKey("configuration");
        ReflectionHelpers.setField(VodConfig.get(), "sites", List.of(config));
    }

    private void awaitCompletion() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!button.isEnabled() && System.nanoTime() < deadline) {
            Thread.sleep(10);
            ShadowLooper.idleMainLooper();
        }
        assertTrue("Configuration load did not complete", button.isEnabled());
    }

    private ShadowActivity shadow() { return Shadow.extract(activity); }
}
