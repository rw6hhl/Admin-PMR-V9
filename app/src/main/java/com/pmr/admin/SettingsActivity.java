package com.pmr.admin;

import android.app.AlertDialog;
import android.content.Context;
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

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public class SettingsActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private EditText editMailIndex, editPriznak, editPortPrm, editPortPrd, editIp;
    private EditText editUsilMic, editUsilDin;
    private CheckBox cbUsilMic, cbUsilDin, cbAgc;
    private LinearLayout layoutServerSettings, layoutParamsSettings;
    private Button btnChanList, btnLogs, btnLoadList;
    private CheckBox cbVerboseLog, cbSoundEnabled, cbAskPassword, cbPttLatch, cbShowListNames;
    private CheckBox cbSimplex, cbShowPowerButton, cbShowServerIp;

    private final ActivityResultLauncher<String[]> openListLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                try {
                    InputStream is = getContentResolver().openInputStream(uri);
                    if (is == null) return;
                    int count = NameBook.importFromStream(this, is);
                    is.close();
                    Toast.makeText(this, "Загружено имён: " + count, Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<String> exportPresetsLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"), uri -> {
                if (uri == null) return;
                try {
                    JSONObject json = new JSONObject();
                    for (int i = 1; i <= 5; i++) {
                        if (prefs.contains("preset_ip_" + i)) {
                            JSONObject preset = new JSONObject();
                            preset.put("channel", prefs.getInt("preset_channel_" + i, 0));
                            preset.put("index", prefs.getString("preset_index_" + i, ""));
                            preset.put("priznak", prefs.getString("preset_priznak_" + i, ""));
                            preset.put("ip", prefs.getString("preset_ip_" + i, ""));
                            preset.put("port_prm", prefs.getInt("preset_port_prm_" + i, 0));
                            preset.put("port_prd", prefs.getInt("preset_port_prd_" + i, 0));
                            json.put(String.valueOf(i), preset);
                        }
                    }
                    String data = json.toString(2);
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os != null) {
                            os.write(data.getBytes(StandardCharsets.UTF_8));
                            os.flush();
                        }
                    }
                    Toast.makeText(this, "Ячейки сохранены", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<String[]> importPresetsLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                try {
                    StringBuilder sb = new StringBuilder();
                    try (InputStream is = getContentResolver().openInputStream(uri);
                         BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                    }
                    JSONObject json = new JSONObject(sb.toString());
                    SharedPreferences.Editor editor = prefs.edit();
                    Iterator<String> keys = json.keys();
                    int count = 0;
                    while (keys.hasNext()) {
                        String key = keys.next();
                        JSONObject preset = json.getJSONObject(key);
                        editor.putInt("preset_channel_" + key, preset.optInt("channel"));
                        editor.putString("preset_index_" + key, preset.optString("index"));
                        editor.putString("preset_priznak_" + key, preset.optString("priznak"));
                        editor.putString("preset_ip_" + key, preset.optString("ip"));
                        editor.putInt("preset_port_prm_" + key, preset.optInt("port_prm"));
                        editor.putInt("preset_port_prd_" + key, preset.optInt("port_prd"));
                        count++;
                    }
                    editor.apply();
                    Toast.makeText(this, "Загружено ячеек: " + count, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        AppLog.add("SettingsActivity: onCreate (V12.8)");

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
        cbShowServerIp = findViewById(R.id.cbShowServerIp);

        cbUsilMic = findViewById(R.id.cbUsilMic);
        editUsilMic = findViewById(R.id.editUsilMic);
        cbAgc = findViewById(R.id.cbAgc);
        cbUsilDin = findViewById(R.id.cbUsilDin);
        editUsilDin = findViewById(R.id.editUsilDin);

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
            cbPttLatch.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean("ptt_latch_enabled", checked).apply());
        }
        if (cbShowListNames != null) {
            cbShowListNames.setChecked(prefs.getBoolean("show_list_names", false));
            cbShowListNames.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean("show_list_names", checked).apply());
        }
        if (cbAskPassword != null) {
            cbAskPassword.setChecked(prefs.getBoolean("ask_password", false));
            cbAskPassword.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean("ask_password", checked).apply());
        }
        if (cbSimplex != null) {
            cbSimplex.setChecked(prefs.getBoolean("simplex", false));
            cbSimplex.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean("simplex", checked).apply());
        }
        if (cbShowPowerButton != null) {
            cbShowPowerButton.setChecked(prefs.getBoolean("show_power_button", false));
            cbShowPowerButton.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean("show_power_button", checked).apply());
        }
        if (cbShowServerIp != null) {
            cbShowServerIp.setChecked(prefs.getBoolean("show_server_ip", false));
            cbShowServerIp.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean("show_server_ip", checked).apply());
        }
        if (cbVerboseLog != null) {
            cbVerboseLog.setChecked(prefs.getBoolean("verbose_log", false));
            cbVerboseLog.setOnCheckedChangeListener((b, checked) -> {
                prefs.edit().putBoolean("verbose_log", checked).apply();
                AppLog.setVerbose(checked);
            });
        }
        if (cbSoundEnabled != null) {
            cbSoundEnabled.setChecked(prefs.getBoolean("sound_enabled", false));
            cbSoundEnabled.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean("sound_enabled", checked).apply());
        }

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

        findViewById(R.id.btnToggleServer).setOnClickListener(v -> layoutServerSettings.setVisibility(layoutServerSettings.getVisibility() == View.GONE ? View.VISIBLE : View.GONE));
        findViewById(R.id.btnToggleParams).setOnClickListener(v -> layoutParamsSettings.setVisibility(layoutParamsSettings.getVisibility() == View.GONE ? View.VISIBLE : View.GONE));
        findViewById(R.id.btnCodecDialog).setOnClickListener(v -> showCodecDialog());

        btnChanList.setOnClickListener(v -> startActivity(new Intent(this, ChanListActivity.class)));
        btnLogs.setOnClickListener(v -> startActivity(new Intent(this, LogActivity.class)));
        if (btnLoadList != null) {
            btnLoadList.setOnClickListener(v -> openListLauncher.launch(new String[]{"*/*"}));
        }

        findViewById(R.id.btnSaveSettings).setOnClickListener(v -> saveSettings());

        Button btnShareProgram = findViewById(R.id.btnShareProgram);
        Button btnShowDescription = findViewById(R.id.btnShowDescription);
        if (btnShareProgram != null) {
            btnShareProgram.setOnClickListener(v -> {
                String url = "https://www.cqham.ru/forum/showthread.php/41895-Хочу-поделиться-программой/page9";
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            });
        }
        if (btnShowDescription != null) {
            btnShowDescription.setOnClickListener(v -> {
                StringBuilder sb = new StringBuilder();
                try (InputStream is = getAssets().open("opisanie.txt");
                     BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append("\n");
                } catch (IOException e) {
                    sb.append("Ошибка чтения файла opisanie.txt");
                }
                new AlertDialog.Builder(this)
                        .setTitle("Описание работы программы")
                        .setMessage(sb.toString())
                        .setPositiveButton("Закрыть", null)
                        .show();
            });
        }

        Button btnExport = findViewById(R.id.btnExportPresets);
        if (btnExport != null) {
            btnExport.setOnClickListener(v -> exportPresetsLauncher.launch("presets_backup.txt"));
        }

        Button btnImport = findViewById(R.id.btnImportPresets);
        if (btnImport != null) {
            btnImport.setOnClickListener(v -> importPresetsLauncher.launch(new String[]{"text/plain", "*/*"}));
        }

        // V12.8: Открытие папки apk в браузере
        Button btnCheckUpdate = findViewById(R.id.btnCheckUpdate);
        if (btnCheckUpdate != null) {
            btnCheckUpdate.setOnClickListener(v -> {
                String url = "https://github.com/rw6hhl/Admin-PMR-V9/tree/main/apk";
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            });
        }
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

        if (PmrService.pmrSocket != null) {
            PmrService.pmrSocket.recreateSocket(this);
            if (PmrService.audioEngine != null) PmrService.audioEngine.applyGain();
        }
        finish();
    }
}