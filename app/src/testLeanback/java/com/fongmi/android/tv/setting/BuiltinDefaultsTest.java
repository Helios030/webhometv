package com.fongmi.android.tv.setting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.room.Room;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.db.AppDatabase;
import com.github.catvod.Init;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.nio.file.Files;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 28, application = BuiltinDefaultsTest.TestApp.class)
public class BuiltinDefaultsTest {

    public static class TestApp extends App {
        @Override
        public void onCreate() {
            Init.set(this);
        }
    }

    private AppDatabase database;
    private File registry;

    @Before
    public void setUp() throws Exception {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase.class)
                .allowMainThreadQueries().build();
        ReflectionHelpers.setStaticField(AppDatabase.class, "instance", database);
        registry = new File(CustomCspSetting.dir(), "registry.json");
        Files.deleteIfExists(registry.toPath());
    }

    @After
    public void tearDown() throws Exception {
        Files.deleteIfExists(registry.toPath());
        database.close();
        ReflectionHelpers.setStaticField(AppDatabase.class, "instance", null);
    }

    @Test
    public void firstLaunchUsesAndPersistsBuiltinSource() {
        Config config = Config.vod();
        assertEquals("https://qist.wyfc.qzz.io/xiaosa/api.json", config.getUrl());
        assertTrue(config.getId() > 0);
        assertEquals(config.getId(), Config.vod().getId());
        assertEquals(1, Config.getAll(0).size());
    }

    @Test
    public void existingSourceIsPreserved() {
        Config config = Config.create(0, "https://example.com/custom.json", "My source");
        assertEquals(config.getId(), Config.vod().getId());
        assertEquals(config.getUrl(), Config.vod().getUrl());
    }

    @Test
    public void builtinCatalogOnlyEnablesEclipse() {
        CustomCspSetting.Registry defaults = CustomCspSetting.load();
        assertTrue(defaults.isEnabled());
        assertEquals(17, defaults.getItems().size());
        assertEquals("webhome_eclipse", defaults.getHomeKey());
        assertEquals(1, defaults.getItems().stream().filter(CustomCspSetting.Item::isEnabled).count());
        assertEquals(1, defaults.sites().size());
        assertEquals("webhome_eclipse", defaults.sites().get(0).getKey());
        assertEquals("https://vvee.ccwu.cc/", defaults.sites().get(0).getHomePage());
        assertTrue(defaults.getItems().stream().allMatch(item -> item.isWebHome() && item.isValid()));
        assertFalse(registry.exists());
    }

    @Test
    public void savedDisabledRegistryGetsSelectablePresetsWithoutEnablingThem() throws Exception {
        Files.writeString(registry.toPath(), "{\"enabled\":false,\"items\":[]}");
        CustomCspSetting.Registry saved = CustomCspSetting.load();
        assertFalse(saved.isEnabled());
        assertEquals(17, saved.getItems().size());
        assertEquals(0, saved.getItems().stream().filter(CustomCspSetting.Item::isEnabled).count());
        assertTrue(saved.getHomeKey().isEmpty());
    }

    @Test
    public void existingItemsAndHomeSurviveMigrationWithoutDuplicateKeys() throws Exception {
        Files.writeString(registry.toPath(), """
                {"enabled":true,"homeKey":"my_home","items":[
                  {"key":"my_home","name":"My homepage","type":3,"api":"csp_Builtin","homePage":"https://example.com/"},
                  {"key":"webhome_eclipse","name":"My Eclipse","enabled":false,"type":3,"api":"csp_Builtin","homePage":"https://example.com/eclipse/"}
                ]}
                """);
        CustomCspSetting.Registry saved = CustomCspSetting.load();
        assertEquals(18, saved.getItems().size());
        assertEquals("my_home", saved.getHomeKey());
        assertEquals(1, saved.getItems().stream().filter(CustomCspSetting.Item::isEnabled).count());
        CustomCspSetting.Item eclipse = saved.getItems().stream().filter(item -> item.getKey().equals("webhome_eclipse")).findFirst().orElseThrow();
        assertEquals("My Eclipse", eclipse.getName());
        assertEquals("https://example.com/eclipse/", eclipse.getHomePage());
        assertFalse(eclipse.isEnabled());
        assertEquals(18, CustomCspSetting.load().getItems().size());
    }

    @Test
    public void savedPresetSelectionAndDeletionSurviveReload() throws Exception {
        Files.writeString(registry.toPath(), "{\"items\":[]}");
        CustomCspSetting.Registry saved = CustomCspSetting.load();
        CustomCspSetting.Item selected = saved.getItems().stream().filter(item -> item.getKey().equals("webhome_nostr_tv")).findFirst().orElseThrow();
        selected.setEnabled(true);
        saved.setHomeKey(selected.getKey());
        saved.getItems().removeIf(item -> item.getKey().equals("webhome_pomo"));
        Files.writeString(registry.toPath(), App.gson().toJson(saved));
        CustomCspSetting.Registry reloaded = CustomCspSetting.load();
        assertEquals(16, reloaded.getItems().size());
        assertEquals("webhome_nostr_tv", reloaded.getHomeKey());
        assertEquals(1, reloaded.sites().size());
        assertEquals("https://nostrtv.aws.dpdns.org/", reloaded.sites().get(0).getHomePage());
    }

    @Test
    public void explicitlySavedEmptyOrMalformedRegistryDoesNotRestoreDefaults() throws Exception {
        Files.writeString(registry.toPath(), "{\"builtinHomesImported\":true,\"items\":[]}");
        assertTrue(CustomCspSetting.load().getItems().isEmpty());
        Files.writeString(registry.toPath(), "invalid JSON");
        assertTrue(CustomCspSetting.load().getItems().isEmpty());
    }
}
