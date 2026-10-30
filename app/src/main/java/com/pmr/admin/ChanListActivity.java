package com.pmr.admin;

import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class ChanListActivity extends AppCompatActivity implements PmrSocket.OnChanListListener, PmrSocket.OnVoiceListener {

    public static class User {
        public int number;
        public int id;
        public String call;
        public String ip;
        public boolean isBanned;
        public User(int number, int id, String call, String ip, boolean isBanned) {
            this.number = number;
            this.id = id;
            this.call = call;
            this.ip = ip;
            this.isBanned = isBanned;
        }
    }

    private List<User> users = new ArrayList<>();
    private ChanAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLog.add("ChanListActivity: onCreate — старт");
        setContentView(R.layout.activity_chan_list);
        AppLog.add("ChanListActivity: setContentView OK");

        RecyclerView recyclerView = findViewById(R.id.recyclerChanList);
        if (recyclerView == null) {
            AppLog.add("ChanListActivity: ОШИБКА — recyclerChanList не найден");
            finish();
            return;
        }
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new ChanAdapter(this, users, (user, position) -> {
            AppLog.add("ChanListActivity: кнопка БАН/РАЗБАН — number=" + user.number
                    + ", id=" + user.id + ", isBanned=" + user.isBanned);
            if (PmrService.getPmrSocket() != null) {
                if (!user.isBanned) {
                    PmrService.getPmrSocket().sendBan(user.number, user.id);
                } else {
                    PmrService.getPmrSocket().sendUnban(user.number, user.id);
                }
                Toast.makeText(this, "Команда отправлена. Ожидание ответа сервера...", Toast.LENGTH_SHORT).show();
            } else {
                AppLog.add("ChanListActivity: pmrSocket == null — сервис не запущен");
                Toast.makeText(this, "Сервис не запущен", Toast.LENGTH_SHORT).show();
            }
        });
        recyclerView.setAdapter(adapter);
        AppLog.add("ChanListActivity: adapter установлен");

        Button btnRefreshList = findViewById(R.id.btnRefreshList);
        if (btnRefreshList == null) {
            AppLog.add("ChanListActivity: ОШИБКА — btnRefreshList не найден в activity_chan_list.xml");
        } else {
            btnRefreshList.setOnClickListener(v -> {
                AppLog.add("ChanListActivity: btnRefreshList → requestChanList()");
                if (PmrService.getPmrSocket() != null) {
                    new Thread(() -> {
                        try {
                            PmrService.getPmrSocket().requestChanList();
                        } catch (Exception e) {
                            AppLog.add("ChanListActivity: ошибка requestChanList (кнопка) — " + e.getMessage());
                        }
                    }).start();
                    Toast.makeText(this, "Запрос списка отправлен...", Toast.LENGTH_SHORT).show();
                } else {
                    AppLog.add("ChanListActivity: pmrSocket == null — сервис не запущен (кнопка)");
                    Toast.makeText(this, "Сервис не запущен", Toast.LENGTH_SHORT).show();
                }
            });
            AppLog.add("ChanListActivity: btnRefreshList подключён");
        }

        PmrService.setChanListListener(this);
        PmrService.setVoiceListener(this);
        AppLog.add("ChanListActivity: setChanListListener + setVoiceListener");

        if (PmrService.getPmrSocket() != null) {
            new Thread(() -> {
                try {
                    PmrService.getPmrSocket().requestChanList();
                } catch (Exception e) {
                    AppLog.add("ChanListActivity: ошибка requestChanList в фоне — " + e.getMessage());
                }
            }).start();
        } else {
            Toast.makeText(this, "Сервис еще не запущен", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onChanListReceived(List<PmrSocket.ChanEntry> entries) {
        AppLog.add("ChanListActivity: onChanListReceived — записей=" + entries.size());
        runOnUiThread(() -> {
            if (adapter == null) return;
            users.clear();
            int skipped = 0;
            for (PmrSocket.ChanEntry e : entries) {
                if (PmrSocket.isAdminObserver(e.id)) {
                    skipped++;
                    continue;
                }
                String name = NameBook.getName(this, e.id);
                users.add(new User(e.number, e.id, name, e.ip, e.ban));
            }
            adapter.notifyDataSetChanged();
            AppLog.add("ChanListActivity: Список обновлён, абонентов: " + users.size()
                    + " (скрыто админ/наблюдатель: " + skipped + ")");

            int lastNumber = PmrSocket.getLastVoiceNumber();
            if (lastNumber != -1) {
                AppLog.add("ChanListActivity: после заполнения списка подсвечиваем последнего говорившего: number=" + lastNumber);
                adapter.setActiveNumber(lastNumber);
            }
        });
    }

    @Override
    public void onVoiceReceived(int clientNumber) {
        runOnUiThread(() -> {
            if (adapter != null) {
                adapter.setActiveNumber(clientNumber);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AppLog.add("ChanListActivity: onDestroy — setChanListListener(null)");
        PmrService.setChanListListener(null);
        PmrService.setVoiceListener(null);
    }
}