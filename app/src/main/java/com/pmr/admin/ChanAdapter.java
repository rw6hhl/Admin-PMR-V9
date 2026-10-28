package com.pmr.admin;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class ChanAdapter extends RecyclerView.Adapter<ChanAdapter.VH> {
    private static final int MAX_NAME_LEN = 16;
    private final Context ctx;
    private final List<ChanListActivity.User> users;
    private final OnBanClickListener listener;
    private int activeNumber = -1;
    
    private final Handler resetHandler = new Handler(Looper.getMainLooper());
    private final Runnable resetRunnable;

    public interface OnBanClickListener {
        void onBanClick(ChanListActivity.User user, int position);
    }

    public ChanAdapter(Context ctx, List<ChanListActivity.User> users, OnBanClickListener listener) {
        this.ctx = ctx;
        this.users = users;
        this.listener = listener;
        setHasStableIds(true);
        
        this.resetRunnable = () -> {
            if (this.activeNumber != -1) {
                int old = this.activeNumber;
                AppLog.add("ChanAdapter: resetRunnable — сброс activeNumber с " + old + " на -1");
                this.activeNumber = -1;
                for (int i = 0; i < this.users.size(); i++) {
                    if (this.users.get(i).number == old) {
                        notifyItemChanged(i);
                        break;
                    }
                }
            }
        };
    }

    @Override
    public long getItemId(int position) {
        return users.get(position).number;
    }

    public void setActiveNumber(int number) {
        AppLog.add("ChanAdapter: setActiveNumber(" + number + "), текущий activeNumber=" + this.activeNumber);
        if (this.activeNumber != number) {
            this.activeNumber = number;
            AppLog.add("ChanAdapter: activeNumber изменен на " + number + " → notifyDataSetChanged()");
            notifyDataSetChanged();
        }
        resetHandler.removeCallbacks(resetRunnable);
        if (number != -1) {
            resetHandler.postDelayed(resetRunnable, 400);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chan, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ChanListActivity.User user = users.get(position);
        String name = user.call == null ? "" : user.call.trim();
        if (name.length() > MAX_NAME_LEN) {
            name = name.substring(0, MAX_NAME_LEN - 1) + "…";
        }
        
        h.itemText.setText(String.format("%d %s", user.id, name));
        
        if (user.number == activeNumber) {
            AppLog.add("ChanAdapter: onBindViewHolder — " + user.number + " АКТИВЕН (Красный)");
            h.itemText.setTextColor(Color.RED);
            h.itemText.setTypeface(null, Typeface.BOLD);
        } else {
            h.itemText.setTextColor(Color.WHITE);
            h.itemText.setTypeface(null, Typeface.NORMAL);
        }

        h.itemBtnBan.setText("");
        if (user.isBanned) {
            AppLog.add("ChanAdapter: onBindViewHolder — number=" + user.number + " ЗАБАНЕН (Красный квадрат)");
            h.itemBtnBan.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, android.R.color.holo_red_dark)));
        } else {
            h.itemBtnBan.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, android.R.color.holo_green_dark)));
        }
        h.itemBtnBan.setEnabled(true);
        h.itemBtnBan.setOnClickListener(v -> {
            if (listener != null) listener.onBanClick(user, position);
        });
    }

    @Override
    public int getItemCount() {
        return users.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView itemText;
        Button itemBtnBan;
        VH(@NonNull View v) {
            super(v);
            itemText = v.findViewById(R.id.itemText);
            itemBtnBan = v.findViewById(R.id.itemBtnBan);
        }
    }
}