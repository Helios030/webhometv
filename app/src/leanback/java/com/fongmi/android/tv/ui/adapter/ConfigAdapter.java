package com.fongmi.android.tv.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterConfigBinding;
import com.fongmi.android.tv.utils.ConfigLatencyProbe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ConfigAdapter extends RecyclerView.Adapter<ConfigAdapter.ViewHolder> {

    private final OnClickListener listener;
    private List<Config> mItems;
    private boolean readOnly;
    private ConfigLatencyProbe latencyProbe;
    private Map<String, Long> latencies;

    public ConfigAdapter(OnClickListener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public interface OnClickListener {

        void onTextClick(Config item);

        void onDeleteClick(Config item);
    }

    public ConfigAdapter readOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    public ConfigAdapter addAll(int type) {
        return addAll(type, null);
    }

    public ConfigAdapter addAll(int type, Config current) {
        mItems = Config.getAll(type);
        String currentUrl = current == null ? null : current.getUrl();
        if (!readOnly && !TextUtils.isEmpty(currentUrl)) mItems.removeIf(item -> TextUtils.equals(item.getUrl(), currentUrl));
        return this;
    }

    public int remove(Config item) {
        int position = mItems.indexOf(item);
        if (position == -1) return -1;
        item.delete();
        mItems.remove(position);
        notifyItemRemoved(position);
        return getItemCount();
    }

    public void checkLatency() {
        cancelLatencyCheck();
        latencies = Collections.emptyMap();
        latencyProbe = new ConfigLatencyProbe();
        notifyDataSetChanged();
        latencyProbe.start(new ArrayList<>(mItems), results -> {
            latencies = results;
            ConfigLatencyProbe.sort(mItems, results);
            notifyDataSetChanged();
        });
    }

    public void cancelLatencyCheck() {
        if (latencyProbe != null) latencyProbe.cancel();
    }

    @Override
    public long getItemId(int position) {
        return mItems.get(position).getId();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterConfigBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Config item = mItems.get(position);
        holder.binding.text.setText(item.getDesc());
        if (latencies != null) {
            Long cost = latencies.get(item.getUrl());
            String value = cost == null ? holder.itemView.getContext().getString(R.string.config_latency_pending)
                    : cost == ConfigLatencyProbe.LOCAL ? holder.itemView.getContext().getString(R.string.config_latency_local)
                    : cost == ConfigLatencyProbe.UNREACHABLE ? holder.itemView.getContext().getString(R.string.config_latency_unreachable)
                    : holder.itemView.getContext().getString(R.string.config_latency_ms, cost);
            holder.binding.text.setText(item.getDesc() + " · " + value);
        }
        holder.binding.text.setOnClickListener(v -> listener.onTextClick(item));
        holder.binding.delete.setVisibility(readOnly ? View.GONE : View.VISIBLE);
        holder.binding.delete.setOnClickListener(v -> listener.onDeleteClick(item));
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterConfigBinding binding;

        public ViewHolder(@NonNull AdapterConfigBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
