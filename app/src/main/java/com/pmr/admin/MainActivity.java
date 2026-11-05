package com.pmr.admin;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_MIC = 101;
    private static final int PTT_LATCH_BLINK_PERIOD_MS = 500;
    private static final int PTT_TAP_MAX_MS = 150;
    private static final int PTT_DOUBLE_TAP_TIMEOUT_MS = 300;
    private static final String ADMIN_PASSWORD = "r0st0v";

    private static final int PRESET_HIGHLIGHT_COLOR = 0xFFFF9800;
    private static final float PRESET_CORNER_RADIUS_DP = 8f;
    private static final float PRESET_STROKE_WIDTH_DP = 2.5f;

    private SharedPreferences prefs;
    private TextView tvChannel, tvIndex, tvSpeakingId, tvVersion, tvActiveUsers;
    private TextView tvServerIp;
    private ImageView imgRed, imgGreen, btnPower;
    private Button btnMenu, btnUp, btnDown, btnSave1, btnSave2, btnSave3, btnSave4, btnSave5, btnCallOrList, btnPTT, btnSecretMode;
    private boolean isAdminMode = false;
    private PowerManager.WakeLock wakeLock;
    private int currentChannel = 4;
    private ToneGenerator toneGen;
    private boolean soundEnabled = true;

    private GestureDetector gestureDetector;

    private boolean pttLatchEnabled = true;
    private boolean pttLatched = false;
    private long pttDownTime = 0L;
    private boolean pttHoldActive = false;
    private boolean pttTapPending = false;
    private boolean blinkState = false;

    private int lastUserCount = -1;
    private boolean showListNames = false;

    private int activePreset = 0;
    private Button[] presetButtons;

    // V11.7: Переменные сканирования
    private boolean isScanning = false;
    private int scanMode = 0; // 0 = ячейки, 1 = каналы внутри ячейки
    private int scanIndex = 1;
    private int scanTargetPreset = 1;
    private final Handler scanHandler = new Handler(Looper.getMainLooper());
    private Runnable scanRunnable;
    private Runnable resumeScanRunnable;

    private final Handler speakingHandler = new Handler(Looper.getMainLooper());
    private final Runnable resetSpeakingId = () -> {
        tvSpeakingId.setText("");
        imgGreen.setVisibility(View.INVISIBLE);
    };

    private final Handler blinkHandler = new Handler(Looper.getMainLooper());
    private final Runnable blinkRunnable = new Runnable() {
        @Override
        public void run() {
            if (!pttLatched) return;
            blinkState = !blinkState;
            imgRed.setVisibility(blinkState ? View.VISIBLE : View.INVISIBLE);
            blinkHandler.postDelayed(this, PTT_LATCH_BLINK_PERIOD_MS);
        }
    };

    private final Handler tapHandler = new Handler(Looper.getMainLooper());
    private final Runnable singleTapRunnable = () -> {
        if (!pttTapPending) return;
        pttTapPending = false;
        if (pttLatched) stopPttLatch();
    };

    private BroadcastReceiver voiceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int clientNumber = intent.getIntExtra("clientId", -1);
            if (clientNumber != -1) {
                if (isScanning) pauseScanningForVoice();

                if (!showListNames) {
                    String text = lastUserCount >= 0 ? String.valueOf(lastUserCount) : "";
                    tvSpeakingId.setText(text);
                    tvSpeakingId.setTextColor(Color.RED);
                    tvSpeakingId.setTypeface(null, Typeface.BOLD);
                    imgGreen.setVisibility(View.VISIBLE);
                    speakingHandler.removeCallbacks(resetSpeakingId);
                    speakingHandler.postDelayed(resetSpeakingId, 400);
                    return;
                }

                int id;
                if (currentChannel == 4) {
                    id = PmrSocket.Priznak_pmr;
                } else {
                    id = PmrSocket.getClientIdByNumber(clientNumber);
                }

                if (id == -1) return;
                if (PmrSocket.isAdminObserver(id)) return;
                String name = NameBook.getName(MainActivity.this, id);
                String text = String.format("%d %s", id, name);

                tvSpeakingId.setText(text);
                tvSpeakingId.setTextColor(Color.RED);
                tvSpeakingId.setTypeface(null, Typeface.BOLD);
                imgGreen.setVisibility(View.VISIBLE);

                speakingHandler.removeCallbacks(resetSpeakingId);
                speakingHandler.postDelayed(resetSpeakingId, 400);
            }
        }
    };

    private BroadcastReceiver voiceEndReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isScanning && resumeScanRunnable != null) {
                scanHandler.removeCallbacks(resumeScanRunnable);
                scanHandler.postDelayed(resumeScanRunnable, 3000);
            }
        }
    };

    private BroadcastReceiver usersReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int count = intent.getIntExtra("count", -1);
            if (count != -1) {
                lastUserCount = count;
                if (tvActiveUsers != null) tvActiveUsers.setText(String.valueOf(count));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        AppLog.init(getApplicationContext(), BuildConfig.VERSION_NAME);

        prefs = getSharedPreferences("pmr_prefs", MODE_PRIVATE);
        soundEnabled = prefs.getBoolean("sound_enabled", true);
        try {
            toneGen = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
        } catch (Exception ignored) {}

        String savedVersion = prefs.getString("app_version", "0");
        if (!savedVersion.equals("10.5")) {
            prefs.edit()
                .putString("app_version", "10.5")
                .putString("MyMailIndex", "111111")
                .putString("Priznak_pmr", "22222")
                .putInt("PORT_prm", 5253)
                .putInt("PORT_prd", 15000)
                .putString("ip_server", "109.172.7.155")
                .putInt("current_channel", 4)
                .putBoolean("simplex", true)
                .putBoolean("is_admin_mode", false)
                .putBoolean("ptt_latch_enabled", true)
                .putBoolean("sound_enabled", false)
                .putFloat("MicUsildouble", 5.0f)
                .putBoolean("onUsilMic", true)
                .putFloat("DinUsildouble", 5.0f)
                .putBoolean("onUsilDin", true)
                .putBoolean("onAgc", true)
                .putBoolean("show_list_names", false)
                .putBoolean("ask_password", true)
                .putBoolean("show_power_button", false)
                .apply();
        }

        pttLatchEnabled = prefs.getBoolean("ptt_latch_enabled", true);
        showListNames = prefs.getBoolean("show_list_names", false);

        requestMicPermission();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "PMR::WakeLock");
            wakeLock.acquire();
        }

        currentChannel = prefs.getInt("current_channel", 4);
        isAdminMode = prefs.getBoolean("is_admin_mode", false);

        initViews();
        resetPresetHighlight();
        setupListeners();
        updateUI();
        updateServerIpVisibility();
        updatePowerButton();

        if (!PmrService.isRunning) {
            try {
                startService(new Intent(this, PmrService.class));
            } catch (Exception ignored) {}
        }
    }

    private void beep() {
        if (!soundEnabled || toneGen == null) return;
        try {
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 100);
        } catch (Exception ignored) {}
    }

    private void requestMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void initViews() {
        tvChannel = findViewById(R.id.textView);
        tvIndex = findViewById(R.id.textView4);
        tvSpeakingId = findViewById(R.id.tvSpeakingId);
        tvVersion = findViewById(R.id.tvVersion);
        tvActiveUsers = findViewById(R.id.textView6);
        tvServerIp = findViewById(R.id.tvServerIp);

        imgRed = findViewById(R.id.imageView);
        imgGreen = findViewById(R.id.imageView2);
        btnPower = findViewById(R.id.btnPower);

        btnMenu = findViewById(R.id.button4);
        btnUp = findViewById(R.id.button1);
        btnDown = findViewById(R.id.button2);
        btnCallOrList = findViewById(R.id.button);
        btnSave1 = findViewById(R.id.buttonsave1);
        btnSave2 = findViewById(R.id.buttonsave2);
        btnSave3 = findViewById(R.id.buttonsave3);
        btnSave4 = findViewById(R.id.buttonsave4);
        btnSave5 = findViewById(R.id.buttonsave5);
        btnPTT = findViewById(R.id.btnPTT);
        btnSecretMode = findViewById(R.id.btnSecretMode);

        presetButtons = new Button[]{btnSave1, btnSave2, btnSave3, btnSave4, btnSave5};
    }

    private void setupListeners() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) { return true; }
            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                beep();
                if (isAdminMode) {
                    toggleAdminMode();
                } else {
                    if (prefs.getBoolean("ask_password", true)) {
                        showPasswordDialog();
                    } else {
                        toggleAdminMode();
                    }
                }
                return true;
            }
        });

        btnSecretMode.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true;
        });

        btnPower.setOnClickListener(v -> {
            beep();
            if (PmrService.isRunning) {
                if (pttLatched) stopPttLatch();
                stopService(new Intent(this, PmrService.class));
            } else {
                startService(new Intent(this, PmrService.class));
            }
            new Handler(Looper.getMainLooper()).postDelayed(this::updatePowerButton, 300);
        });

        btnMenu.setOnClickListener(v -> {
            beep();
            startActivity(new Intent(this, SettingsActivity.class));
        });

        btnCallOrList.setOnClickListener(v -> {
            beep();
            if (isAdminMode) {
                startActivity(new Intent(this, ChanListActivity.class));
            } else {
                if (!isScanning) {
                    startScanning();
                } else {
                    stopScanning();
                }
            }
        });

        // V11.7: Кнопки UP/DOWN переключают режим сканирования
        btnUp.setOnClickListener(v -> {
            beep();
            if (isScanning) {
                switchToChannelScan(scanTargetPreset);
            } else if (currentChannel < 8) {
                currentChannel++;
                lastUserCount = -1;
                if (tvActiveUsers != null) tvActiveUsers.setText("");
                updateUI();
                prefs.edit().putInt("current_channel", currentChannel).apply();
                if (PmrService.pmrSocket != null) PmrService.pmrSocket.recreateSocket(this);
            }
        });

        btnDown.setOnClickListener(v -> {
            beep();
            if (isScanning) {
                switchToChannelScan(scanTargetPreset);
            } else if (currentChannel > 0) {
                currentChannel--;
                lastUserCount = -1;
                if (tvActiveUsers != null) tvActiveUsers.setText("");
                updateUI();
                prefs.edit().putInt("current_channel", currentChannel).apply();
                if (PmrService.pmrSocket != null) PmrService.pmrSocket.recreateSocket(this);
            }
        });

        btnSave1.setOnClickListener(v -> { beep(); handlePresetClick(1); });
        btnSave2.setOnClickListener(v -> { beep(); handlePresetClick(2); });
        btnSave3.setOnClickListener(v -> { beep(); handlePresetClick(3); });
        btnSave4.setOnClickListener(v -> { beep(); handlePresetClick(4); });
        btnSave5.setOnClickListener(v -> { beep(); handlePresetClick(5); });

        btnSave1.setOnLongClickListener(v -> { beep(); savePreset(1); return true; });
        btnSave2.setOnLongClickListener(v -> { beep(); savePreset(2); return true; });
        btnSave3.setOnLongClickListener(v -> { beep(); savePreset(3); return true; });
        btnSave4.setOnLongClickListener(v -> { beep(); savePreset(4); return true; });
        btnSave5.setOnLongClickListener(v -> { beep(); savePreset(5); return true; });

        btnPTT.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pttDownTime = System.currentTimeMillis();
                    pttHoldActive = false;
                    if (isScanning) stopScanning(); // V11.7: Выключаем сканирование при PTT
                    if (!PmrService.isRunning) {
                        Toast.makeText(this, "Включите программу", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (!PmrService.isRunning) return true;
                    if (!pttHoldActive && !pttLatched) {
                        long dt = System.currentTimeMillis() - pttDownTime;
                        if (dt >= PTT_TAP_MAX_MS) {
                            cancelTapWaiting();
                            pttHoldActive = true;
                            beep();
                            imgRed.setVisibility(View.VISIBLE);
                            sendBroadcast(new Intent("PMR_START_RECORDING"));
                        }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    long duration = System.currentTimeMillis() - pttDownTime;
                    boolean isCancel = event.getActionMasked() == MotionEvent.ACTION_CANCEL;

                    if (!PmrService.isRunning) {
                        pttHoldActive = false;
                        pttTapPending = false;
                        tapHandler.removeCallbacks(singleTapRunnable);
                        return true;
                    }

                    if (pttHoldActive) {
                        pttHoldActive = false;
                        imgRed.setVisibility(View.INVISIBLE);
                        sendBroadcast(new Intent("PMR_STOP_RECORDING"));
                        return true;
                    }

                    if (isCancel) return true;

                    if (duration >= PTT_TAP_MAX_MS) {
                        if (!pttLatched) {
                            beep();
                            imgRed.setVisibility(View.VISIBLE);
                            sendBroadcast(new Intent("PMR_START_RECORDING"));
                            imgRed.setVisibility(View.INVISIBLE);
                            sendBroadcast(new Intent("PMR_STOP_RECORDING"));
                        }
                        return true;
                    }

                    if (pttTapPending) {
                        cancelTapWaiting();
                        if (pttLatchEnabled) {
                            if (pttLatched) stopPttLatch();
                            else startPttLatch();
                        }
                    } else {
                        pttTapPending = true;
                        tapHandler.postDelayed(singleTapRunnable, PTT_DOUBLE_TAP_TIMEOUT_MS);
                    }
                    return true;
            }
            return false;
        });
    }

    private void handlePresetClick(int presetNum) {
        if (isScanning && scanMode == 0) {
            scanTargetPreset = presetNum;
            loadPreset(presetNum);
            Toast.makeText(this, "Выбрана ячейка " + presetNum + ". Нажмите UP/DOWN для сканирования каналов", Toast.LENGTH_LONG).show();
        } else {
            loadPreset(presetNum);
        }
    }

    private void startScanning() {
        isScanning = true;
        scanMode = 0;
        scanIndex = 1;
        btnCallOrList.setText("SCAN");
        applyScanHighlight(true);
        
        scanRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isScanning || scanMode != 0) return;
                if (prefs.contains("preset_ip_" + scanIndex)) {
                    loadPreset(scanIndex);
                }
                scanIndex++;
                if (scanIndex > 5) scanIndex = 1;
                scanHandler.postDelayed(this, 2000);
            }
        };
        scanHandler.post(scanRunnable);
        Toast.makeText(this, "Сканирование ВКЛ. UP/DOWN - сканировать каналы", Toast.LENGTH_LONG).show();
    }

    private void switchToChannelScan(int presetNum) {
        scanMode = 1;
        scanHandler.removeCallbacks(scanRunnable);
        
        // Принудительно загружаем выбранную ячейку, чтобы взять оттуда настройки
        loadPreset(presetNum);
        scanIndex = currentChannel;
        
        scanRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isScanning || scanMode != 1) return;
                
                scanIndex++;
                if (scanIndex > 8) scanIndex = 1;
                currentChannel = scanIndex;
                
                lastUserCount = -1;
                if (tvActiveUsers != null) tvActiveUsers.setText("");
                updateUI();
                prefs.edit().putInt("current_channel", currentChannel).apply();
                
                if (PmrService.pmrSocket != null) {
                    PmrService.pmrSocket.recreateSocket(MainActivity.this);
                }
                
                scanHandler.postDelayed(this, 2000);
            }
        };
        scanHandler.post(scanRunnable);
        Toast.makeText(this, "Сканирование каналов ячейки " + presetNum, Toast.LENGTH_SHORT).show();
    }

    private void stopScanning() {
        isScanning = false;
        scanMode = 0;
        btnCallOrList.setText("SCAN");
        applyScanHighlight(false);
        scanHandler.removeCallbacks(scanRunnable);
        if (resumeScanRunnable != null) scanHandler.removeCallbacks(resumeScanRunnable);
        resetPresetHighlight();
    }

    private void pauseScanningForVoice() {
        scanHandler.removeCallbacks(scanRunnable);
        resumeScanRunnable = new Runnable() {
            @Override
            public void run() {
                if (isScanning) {
                    scanHandler.post(scanRunnable);
                }
            }
        };
    }

    private void applyScanHighlight(boolean active) {
        if (btnCallOrList == null) return;
        if (active) {
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.RECTANGLE);
            gd.setColor(Color.TRANSPARENT);
            float r = PRESET_CORNER_RADIUS_DP * getResources().getDisplayMetrics().density;
            gd.setCornerRadius(r);
            float sw = PRESET_STROKE_WIDTH_DP * getResources().getDisplayMetrics().density;
            gd.setStroke((int) sw, getResources().getColor(R.color.scan_color));
            btnCallOrList.setBackground(gd);
        } else {
            btnCallOrList.setBackgroundResource(R.drawable.roundshapebtn);
        }
    }

    private void cancelTapWaiting() {
        pttTapPending = false;
        tapHandler.removeCallbacks(singleTapRunnable);
    }

    private void startPttLatch() {
        if (pttLatched || !PmrService.isRunning) return;
        pttLatched = true;
        blinkState = false;
        imgRed.setVisibility(View.VISIBLE);
        sendBroadcast(new Intent("PMR_START_RECORDING"));
        blinkHandler.postDelayed(blinkRunnable, PTT_LATCH_BLINK_PERIOD_MS);
        Toast.makeText(this, "Постоянная передача ВКЛ", Toast.LENGTH_SHORT).show();
    }

    private void stopPttLatch() {
        if (!pttLatched) return;
        pttLatched = false;
        blinkHandler.removeCallbacks(blinkRunnable);
        imgRed.setVisibility(View.INVISIBLE);
        sendBroadcast(new Intent("PMR_STOP_RECORDING"));
        Toast.makeText(this, "Передача ВЫКЛ", Toast.LENGTH_SHORT).show();
    }

    private void showPasswordDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Пароль");

        new AlertDialog.Builder(this)
                .setTitle("Вход в режим Админа")
                .setView(input)
                .setPositiveButton("Войти", (dialog, which) -> {
                    if (ADMIN_PASSWORD.equals(input.getText().toString())) {
                        toggleAdminMode();
                    } else {
                        Toast.makeText(this, "Неверный пароль", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void toggleAdminMode() {
        isAdminMode = !isAdminMode;
        SharedPreferences.Editor ed = prefs.edit();
        ed.putBoolean("is_admin_mode", isAdminMode);
        if (isAdminMode) ed.putBoolean("ask_password", true);
        ed.apply();
        updateUI();
    }

    private void updatePowerButton() {
        if (btnPower == null) return;
        boolean show = prefs.getBoolean("show_power_button", false);
        btnPower.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            btnPower.setImageResource(PmrService.isRunning ? R.drawable.power_on : R.drawable.power_off);
        }
    }

    private void updateServerIpVisibility() {
        if (tvServerIp == null) return;
        boolean show = prefs.getBoolean("show_server_ip", false);
        if (show) {
            tvServerIp.setText(prefs.getString("ip_server", "109.172.7.155"));
            tvServerIp.setVisibility(View.VISIBLE);
        } else {
            tvServerIp.setVisibility(View.GONE);
        }
    }

    private void savePreset(int presetNum) {
        String ip = prefs.getString("ip_server", "109.172.7.155");
        int prm = prefs.getInt("PORT_prm", 5253);
        int prd = prefs.getInt("PORT_prd", 15000);
        String index = prefs.getString("MyMailIndex", "111111");
        String priznak = prefs.getString("Priznak_pmr", "22222");

        prefs.edit()
            .putInt("preset_channel_" + presetNum, currentChannel)
            .putString("preset_index_" + presetNum, index)
            .putString("preset_priznak_" + presetNum, priznak)
            .putString("preset_ip_" + presetNum, ip)
            .putInt("preset_port_prm_" + presetNum, prm)
            .putInt("preset_port_prd_" + presetNum, prd)
            .apply();

        Toast.makeText(this, "Ячейка " + presetNum + ": " + ip + ":" + prm + " / " + index, Toast.LENGTH_LONG).show();
    }

    private void loadPreset(int presetNum) {
        if (!prefs.contains("preset_ip_" + presetNum)) return;

        int channel = prefs.getInt("preset_channel_" + presetNum, currentChannel);
        String index = prefs.getString("preset_index_" + presetNum, prefs.getString("MyMailIndex", "111111"));
        String priznak = prefs.getString("preset_priznak_" + presetNum, prefs.getString("Priznak_pmr", "22222"));
        String ip = prefs.getString("preset_ip_" + presetNum, prefs.getString("ip_server", "109.172.7.155"));
        int prm = prefs.getInt("preset_port_prm_" + presetNum, prefs.getInt("PORT_prm", 5253));
        int prd = prefs.getInt("preset_port_prd_" + presetNum, prefs.getInt("PORT_prd", 15000));

        currentChannel = channel;
        lastUserCount = -1;
        if (tvActiveUsers != null) tvActiveUsers.setText("");

        prefs.edit()
            .putInt("current_channel", channel)
            .putString("MyMailIndex", index)
            .putString("Priznak_pmr", priznak)
            .putString("ip_server", ip)
            .putInt("PORT_prm", prm)
            .putInt("PORT_prd", prd)
            .apply();

        if (PmrService.pmrSocket != null) {
            PmrService.pmrSocket.recreateSocket(this);
        }

        updateUI();
        updateServerIpVisibility();
        highlightPreset(presetNum);
    }

    private void highlightPreset(int presetNum) {
        if (presetButtons == null) return;
        if (presetNum < 1 || presetNum > presetButtons.length) return;
        if (activePreset >= 1 && activePreset <= presetButtons.length) {
            applyPresetHighlight(presetButtons[activePreset - 1], false);
        }
        applyPresetHighlight(presetButtons[presetNum - 1], true);
        activePreset = presetNum;
    }

    private void resetPresetHighlight() {
        if (presetButtons == null) return;
        for (int i = 0; i < presetButtons.length; i++) {
            applyPresetHighlight(presetButtons[i], false);
        }
        activePreset = 0;
    }

    private void applyPresetHighlight(Button btn, boolean highlighted) {
        if (btn == null) return;
        if (highlighted) {
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.RECTANGLE);
            gd.setColor(Color.TRANSPARENT);
            float r = PRESET_CORNER_RADIUS_DP * getResources().getDisplayMetrics().density;
            gd.setCornerRadius(r);
            float sw = PRESET_STROKE_WIDTH_DP * getResources().getDisplayMetrics().density;
            gd.setStroke((int) sw, PRESET_HIGHLIGHT_COLOR);
            btn.setBackground(gd);
            btn.setTypeface(null, Typeface.BOLD);
        } else {
            btn.setBackgroundResource(R.drawable.roundshapebtn);
            btn.setTypeface(null, Typeface.NORMAL);
        }
    }

    private void updateUI() {
        tvChannel.setText(currentChannel == 0 ? "R" : String.valueOf(currentChannel));
        tvIndex.setText(prefs.getString("MyMailIndex", "111111"));
        tvVersion.setText("v" + BuildConfig.VERSION_NAME);

        if (isAdminMode) {
            tvChannel.setTextColor(getResources().getColor(R.color.admin_channel));
            btnCallOrList.setText("LIST");
        } else {
            tvChannel.setTextColor(getResources().getColor(android.R.color.black));
            btnCallOrList.setText("SCAN");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        soundEnabled = prefs.getBoolean("sound_enabled", false);
        isAdminMode = prefs.getBoolean("is_admin_mode", false);
        currentChannel = prefs.getInt("current_channel", 4);
        pttLatchEnabled = prefs.getBoolean("ptt_latch_enabled", true);
        showListNames = prefs.getBoolean("show_list_names", false);
        
        updateUI();
        updateServerIpVisibility();
        updatePowerButton();
        
        registerReceiver(voiceReceiver, new IntentFilter("PMR_VOICE_RECEIVED"));
        registerReceiver(voiceEndReceiver, new IntentFilter("PMR_VOICE_END"));
        registerReceiver(usersReceiver, new IntentFilter("PMR_USERS_CHANGED"));

        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(voiceReceiver);
        try { unregisterReceiver(voiceEndReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(usersReceiver); } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        speakingHandler.removeCallbacks(resetSpeakingId);
        blinkHandler.removeCallbacks(blinkRunnable);
        tapHandler.removeCallbacks(singleTapRunnable);
        scanHandler.removeCallbacks(scanRunnable);
        if (resumeScanRunnable != null) scanHandler.removeCallbacks(resumeScanRunnable);
        
        if (pttLatched) {
            try { sendBroadcast(new Intent("PMR_STOP_RECORDING")); } catch (Exception ignored) {}
        }
        if (pttHoldActive) {
            try { sendBroadcast(new Intent("PMR_STOP_RECORDING")); } catch (Exception ignored) {}
        }
        if (toneGen != null) toneGen.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }
}