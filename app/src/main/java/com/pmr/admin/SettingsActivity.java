package com.pmr.admin;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import java.io.InputStream;

public class SettingsActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private EditText editMailIndex, editPriznak, editPortPrm, editPortPrd, editIp;
    private EditText editUsilMic, editUsilDin;
    private CheckBox cbUsilMic, cbUsilDin, cbAgc;
    private LinearLayout layoutServerSettings, layoutParamsSettings;
    private Button btnChanList, btnLogs, btnLoadList;
    private CheckBox cbVerboseLog, cbSoundEnabled, cbAskPassword, cbPttLatch, cbShowListNames;
    private CheckBox cbSimplex, cbShowPowerButton;

    private final ActivityResultLauncher<String[]> openListLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) {
                    AppLog.add("SettingsActivity: list.txt — файл не выбран");
                    Toast.makeText(this, "Файл не выбран", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    InputStream is = getContentResolver().openInputStream(uri);
                    if (is == null) {
                        AppLog.add("SettingsActivity: list.txt — openInputStream вернул null");
                        Toast.makeText(this, "Не удалось открыть файл", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int count = NameBook.importFromStream(this, is);
                    is.close();
                    AppLog.add("SettingsActivity: list.txt — uri=" + uri + ", загружено имён=" + count);
                    if (count == 0) {
                        Toast.makeText(this, "Файл пуст или неверный формат", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Загружено имён: " + count, Toast.LENGTH_LONG).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    AppLog.add("NameBook: ошибка загрузки list.txt — " + e.getMessage());
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        AppLog.add("SettingsActivity: onCreate (V10.5)");

        prefs = getSharedPreferences("pmr_prefs", MODE_PRIVATE);

        editMailIndex = findViewById(R.id.editMailIndex);
        editPriznak = findViewById(R.id.editPriznak);
        editPortPrm = findViewById(R.id.editPortPrm);
        editPortPrd = findViewById(R.id.editPortPrd);
        editIp = findViewById(R.id.editIp);
        layoutServerSettings = findViewById(R.id.layoutServerSettings);
        layoutParamsSettings = findViewById(R.id.layoutParamsSettings);
        btnChanList = findViewById(R.id.btnChanList);
        btnLogs = findViewById(R.id.btnLogs);
        btnLoadList = findViewById(R.id.btnLoadList);
        cbVerboseLog = findViewById(R.id.cbVerboseLog);
        cbSoundEnabled = findViewById(R.id.cbSoundEnabled);
        cbAskPassword = findViewById(R.id.cbAskPassword);
        cbPttLatch = findViewById(R.id.cbPttLatch);
        cbShowListNames = findViewById(R.id.cbShowListNames);
        cbSimplex = findViewById(R.id.cbSimplex);
        cbShowPowerButton = findViewById(R.id.cbShowPowerButton);

        cbUsilMic = findViewById(R.id.cbUsilMic);
        editUsilMic = findViewById(R.id.editUsilMic);
        cbAgc = findViewById(R.id.cbAgc);
        cbUsilDin = findViewById(R.id.cbUsilDin);
        editUsilDin = findViewById(R.id.editUsilDin);

        // V10.5: дефолты обновлены (PORT_prd=15000, ip=109.172.7.155)
        editMailIndex.setText(prefs.getString("MyMailIndex", "111111"));
        editPriznak.setText(prefs.getString("Priznak_pmr", "22222"));
        editPortPrm.setText(String.valueOf(prefs.getInt("PORT_prm", 5253)));
        editPortPrd.setText(String.valueOf(prefs.getInt("PORT_prd", 15000)));
        editIp.setText(prefs.getString("ip_server", "109.172.7.155"));

        cbUsilMic.setChecked(prefs.getBoolean("onUsilMic", false));
        editUsilMic.setText(String.valueOf(prefs.getFloat("MicUsildouble", 5.0f)));

        cbAgc.setChecked(prefs.getBoolean("onAgc", false));

        cbUsilDin.setChecked(prefs.getBoolean("onUsilDin", false));
        editUsilDin.setText(String.valueOf(prefs.getFloat("DinUsildouble", 5.0f)));

        if (cbPttLatch != null) {
            cbPttLatch.setChecked(prefs.getBoolean("ptt_latch_enabled", false));
            cbPttLatch.setOnCheckedChangeListener((b, checked) -> {
                prefs.edit().putBoolean("ptt_latch_enabled", checked).apply();
                AppLog.add("SettingsActivity: ptt_latch_enabled=" + checked);
            });
        }

        if (cbShowListNames != null) {
            cbShowListNames.setChecked(prefs.getBoolean("show_list_names", false));
            cbShowListNames.setOnCheckedChangeListener((b, checked) -> {
                prefs.edit().putBoolean("show_list_names", checked).apply();
                AppLog.add("SettingsActivity: show_list_names=" + checked);
            });
        }

        if (cbAskPassword != null) {
            cbAskPassword.setChecked(prefs.getBoolean("ask_password", false));
            cbAskPassword.setOnCheckedChangeListener((b, checked) -> {
                prefs.edit().putBoolean("ask_password", checked).apply();
                AppLog.add("SettingsActivity: ask_password=" + checked);
            });
        }

        if (cbSimplex != null) {
            cbSimplex.setChecked(prefs.getBoolean("simplex", false));
            cbSimplex.setOnCheckedChangeListener((b, checked) -> {
                prefs.edit().putBoolean("simplex", checked).apply();
                AppLog.add("SettingsActivity: simplex=" + checked);
            });
        }

        if (cbShowPowerButton != null) {
            cbShowPowerButton.setChecked(prefs.getBoolean("show_power_button", false));
            cbShowPowerButton.setOnCheckedChangeListener((b, checked) -> {
                prefs.edit().putBoolean("show_power_button", checked).apply();
                AppLog.add("SettingsActivity: show_power_button=" + checked);
            });
        }

        if (cbVerboseLog != null) {
            cbVerboseLog.setChecked(prefs.getBoolean("verbose_log", false));
            cbVerboseLog.setOnCheckedChangeListener((b, checked) -> {
                prefs.edit().putBoolean("verbose_log", checked).apply();
                AppLog.setVerbose(checked);
                AppLog.add("SettingsActivity: verbose_log=" + checked);
            });
        }

        if (cbSoundEnabled != null) {
            cbSoundEnabled.setChecked(prefs.getBoolean("sound_enabled", false));
            cbSoundEnabled.setOnCheckedChangeListener((b, checked) -> {
                prefs.edit().putBoolean("sound_enabled", checked).apply();
                AppLog.add("SettingsActivity: sound_enabled=" + checked);
            });
        }

        // V10.5: видимость — cbAskPassword и cbVerboseLog только в админке
        boolean isAdmin = prefs.getBoolean("is_admin_mode", false);
        if (isAdmin) {
            btnChanList.setVisibility(View.VISIBLE);
            btnLogs.setVisibility(View.VISIBLE);
            cbVerboseLog.setVisibility(View.VISIBLE);
            if (cbAskPassword != null) cbAskPassword.setVisibility(View.VISIBLE);
        } else {
            btnChanList.setVisibility(View.GONE);
            btnLogs.setVisibility(View.GONE);
            cbVerboseLog.setVisibility(View.GONE);
            if (cbAskPassword != null) cbAskPassword.setVisibility(View.GONE);
        }

        findViewById(R.id.btnToggleServer).setOnClickListener(v -> {
            layoutServerSettings.setVisibility(layoutServerSettings.getVisibility() == View.GONE ? View.VISIBLE : View.GONE);
        });

        findViewById(R.id.btnToggleParams).setOnClickListener(v -> {
            layoutParamsSettings.setVisibility(layoutParamsSettings.getVisibility() == View.GONE ? View.VISIBLE : View.GONE);
        });

        findViewById(R.id.btnCodecDialog).setOnClickListener(v -> showCodecDialog());

        btnChanList.setOnClickListener(v -> {
            try {
                AppLog.add("SettingsActivity: нажата «Список» → ChanListActivity");
                startActivity(new Intent(this, ChanListActivity.class));
            } catch (Exception e) {
                AppLog.add("SettingsActivity: ОШИБКА startActivity(ChanListActivity) — " + e.getMessage());
            }
        });
        btnLogs.setOnClickListener(v -> startActivity(new Intent(this, LogActivity.class)));

        if (btnLoadList != null) {
            btnLoadList.setOnClickListener(v -> {
                try {
                    openListLauncher.launch(new String[]{"*/*"});
                } catch (Exception e) {
                    Toast.makeText(this, "Нет файлового менеджера", Toast.LENGTH_LONG).show();
                }
            });
        }

        findViewById(R.id.btnSaveSettings).setOnClickListener(v -> saveSettings());
    }

    private void showCodecDialog() {
        String[] codecs = {"GSM 32 kbps", "PCM 8 bit 8000 Hz", "G711 8000 Hz", "G711 16000 Hz", "PCM 8 bit 16000 Hz", "PCM 16 bit 8000 Hz", "PCM 16 bit 16000 Hz"};
        int currentCodec = prefs.getInt("codec", 3);
        new AlertDialog.Builder(this)
                .setTitle("Режим передачи")
                .setSingleChoiceItems(codecs, currentCodec, (dialog, which) -> {
                    prefs.edit().putInt("codec", which).apply();
                    dialog.dismiss();
                })
                .show();
    }

    private void saveSettings() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("MyMailIndex", editMailIndex.getText().toString());
        editor.putString("Priznak_pmr", editPriznak.getText().toString());
        editor.putInt("PORT_prm", Integer.parseInt(editPortPrm.getText().toString()));
        editor.putInt("PORT_prd", Integer.parseInt(editPortPrd.getText().toString()));
        editor.putString("ip_server", editIp.getText().toString());

        try {
            float micVal = Float.parseFloat(editUsilMic.getText().toString());
            editor.putFloat("MicUsildouble", micVal);
        } catch (Exception ignored) {}
        editor.putBoolean("onUsilMic", cbUsilMic.isChecked());
        editor.putBoolean("onAgc", cbAgc.isChecked());

        try {
            float dinVal = Float.parseFloat(editUsilDin.getText().toString());
            editor.putFloat("DinUsildouble", dinVal);
        } catch (Exception ignored) {}
        editor.putBoolean("onUsilDin", cbUsilDin.isChecked());

        editor.apply();

        AppLog.add("SettingsActivity: saveSettings — mail=" + editMailIndex.getText()
                + ", mic=" + cbUsilMic.isChecked() + "/" + editUsilMic.getText()
                + ", agc=" + cbAgc.isChecked()
                + ", din=" + cbUsilDin.isChecked() + "/" + editUsilDin.getText()
                + ", ptt_latch=" + (cbPttLatch != null ? cbPttLatch.isChecked() : "n/a")
                + ", show_list=" + (cbShowListNames != null ? cbShowListNames.isChecked() : "n/a")
                + ", simplex=" + (cbSimplex != null ? cbSimplex.isChecked() : "n/a")
                + ", show_power=" + (cbShowPowerButton != null ? cbShowPowerButton.isChecked() : "n/a"));

        if (PmrService.pmrSocket != null) {
            PmrService.pmrSocket.recreateSocket(this);
            if (PmrService.audioEngine != null) {
                PmrService.audioEngine.applyGain();
            }
            AppLog.add("SettingsActivity: настройки применены в работе (recreateSocket + applyGain)");
        }

        finish();
    }
}