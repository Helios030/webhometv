package com.fongmi.android.tv.ui.adapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.utils.ConfigLatencyProbe;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 28, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ConfigLatencyAdapterTest {

    @Test
    public void sortedResultsKeepFocusedSourceAndClicksBoundToItsIdentity() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        activity.setTheme(com.google.android.material.R.style.Theme_MaterialComponents_DayNight_NoActionBar);
        List<Config> sources = new ArrayList<>(List.of(config(1, "Slow"), config(2, "Fast"), config(3, "Offline")));
        List<Integer> selected = new ArrayList<>();
        ConfigAdapter adapter = new ConfigAdapter(new ConfigAdapter.OnClickListener() {
            @Override public void onTextClick(Config config) { selected.add(config.getId()); }
            @Override public void onDeleteClick(Config config) {}
        });
        ReflectionHelpers.setField(adapter, "mItems", sources);
        RecyclerView recycler = new RecyclerView(activity);
        recycler.setLayoutManager(new LinearLayoutManager(activity));
        recycler.setItemAnimator(null);
        recycler.setAdapter(adapter);
        activity.setContentView(recycler);
        layout(recycler);
        TextView slow = recycler.findViewHolderForItemId(1).itemView.findViewById(R.id.text);
        assertTrue(slow.requestFocus());
        assertSame(slow, recycler.findFocus());
        Map<String, Long> costs = Map.of("https://1.example/", 300L, "https://2.example/", 10L,
                "https://3.example/", ConfigLatencyProbe.UNREACHABLE);
        ReflectionHelpers.setField(adapter, "latencies", costs);
        ConfigLatencyProbe.sort(sources, costs);
        adapter.notifyDataSetChanged();
        layout(recycler);
        assertEquals(2, adapter.getItemId(0));
        assertEquals(1, recycler.findContainingViewHolder(recycler.findFocus()).getItemId());
        TextView focused = recycler.findViewHolderForItemId(1).itemView.findViewById(R.id.text);
        assertTrue(focused.getText().toString().contains("300 ms"));
        focused.performClick();
        assertEquals(List.of(1), selected);
        TextView failed = recycler.findViewHolderForItemId(3).itemView.findViewById(R.id.text);
        assertTrue(failed.getText().toString().contains("Unreachable"));
    }

    @Test
    public void completedSnapshotDoesNotReinsertADeletedSource() {
        List<Config> sources = new ArrayList<>(List.of(config(1, "One"), config(2, "Two")));
        Map<String, Long> result = Map.of(sources.get(0).getUrl(), 20L, sources.get(1).getUrl(), 10L);
        sources.remove(0);
        ConfigLatencyProbe.sort(sources, result);
        assertEquals(1, sources.size());
        assertEquals(2, sources.get(0).getId());
    }

    private static void layout(RecyclerView recycler) {
        ShadowLooper.idleMainLooper(32, TimeUnit.MILLISECONDS);
        recycler.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        recycler.layout(0, 0, 800, 600);
        ShadowLooper.idleMainLooper(32, TimeUnit.MILLISECONDS);
    }

    private static Config config(int id, String name) {
        Config config = new Config();
        config.setId(id);
        config.setUrl("https://" + id + ".example/");
        config.setName(name);
        return config;
    }
}
