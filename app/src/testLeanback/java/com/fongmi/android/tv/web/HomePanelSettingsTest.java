package com.fongmi.android.tv.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.activity.VodActivity;
import com.github.catvod.Init;
import com.github.catvod.crawler.Spider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = HomePanelSettingsTest.TestApp.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class HomePanelSettingsTest {

    public static class TestApp extends App {
        @Override public void onCreate() { Init.set(this); }
    }

    private Object previousSites;
    private Object previousHome;
    private Activity activity;

    @Before
    public void setUp() {
        previousSites = ReflectionHelpers.getField(VodConfig.get(), "sites");
        previousHome = ReflectionHelpers.getField(VodConfig.get(), "home");
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        ReflectionHelpers.setField(VodConfig.get(), "sites", List.of());
    }

    @After
    public void tearDown() {
        ReflectionHelpers.setField(VodConfig.get(), "sites", previousSites);
        ReflectionHelpers.setField(VodConfig.get(), "home", previousHome);
    }

    @Test
    public void missingConfigurationCenterReportsAnError() {
        Exception error = assertThrows(Exception.class, () -> openConfig(new HomeWebBridge(null, activity, null)));
        assertTrue(error instanceof IllegalStateException);
        assertTrue(error.getMessage().contains("configuration center"));
    }

    @Test
    public void emptyConfigurationCategoriesReportAnError() {
        Site config = new Site() {
            @Override public Site recent() { return this; }
            @Override public Spider spider() {
                return new Spider() {
                    @Override public String homeContent(boolean filter) { return "{\"class\":[]}"; }
                };
            }
        };
        config.setApi("csp_Config");
        config.setType(3);
        config.setKey("configuration");
        ReflectionHelpers.setField(VodConfig.get(), "sites", List.of(config));
        Exception error = assertThrows(Exception.class, () -> openConfig(new HomeWebBridge(null, activity, null)));
        assertTrue(error instanceof IllegalStateException);
        assertTrue(error.getMessage().contains("no categories"));
    }

    @Test
    public void nativeConfigurationNavigationPreservesEclipseHome() {
        HomeActivity homeActivity = Robolectric.buildActivity(HomeActivity.class).get();
        Site home = new Site();
        home.setKey("webhome_eclipse");
        ReflectionHelpers.setField(VodConfig.get(), "home", home);
        Result result = Result.fromJson("{\"class\":[{\"type_id\":\"quark\",\"type_name\":\"Quark\"}]}");
        homeActivity.openDriveConfig("configuration", result);
        ShadowActivity shadow = Shadow.extract(homeActivity);
        Intent intent = shadow.getNextStartedActivity();
        assertEquals(VodActivity.class.getName(), intent.getComponent().getClassName());
        assertEquals("configuration", intent.getStringExtra("key"));
        assertSame(result, intent.getParcelableExtra("result"));
        assertSame(home, VodConfig.get().getHome());
    }

    private static String openConfig(HomeWebBridge bridge) throws Exception {
        Method method = HomeWebBridge.class.getDeclaredMethod("openDriveConfig");
        method.setAccessible(true);
        try {
            return (String) method.invoke(bridge);
        } catch (InvocationTargetException error) {
            throw (Exception) error.getCause();
        }
    }
}
