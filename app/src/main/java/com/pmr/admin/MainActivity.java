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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
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
    private static final int REQ_NOTIF = 102;
    private static final int REQ_INSTALL = 103;
    private static final int PTT_LATCH_BLINK_PERIOD_MS = 500;
    private static final int PTT_TAP_MAX_MS = 150;
    private static final int PTT_DOUBLE_TAP_TIMEOUT_MS = 300;
    private static final String ADMIN_PASSWORD = "r0st0v";

    private static final int PRESET_HIGHLIGHT_COLOR = 0xFFFF9800;
    private static final float PRESET_CORNER_RADIUS_DP = 8f;
    private static final float PRESET_STROKE_WIDTH_DP = 2.5f;
    private static final int SCAN_DELAY_MS = 500;

    private SharedPreferences prefs;
    private TextView tvFreqTop, tvFreqBottom, tvVersion, tvSpeakingId, tvActiveUsers;
    private TextView tvServerIp;
    private ImageView imgRed, imgGreen, btnPower;
    private Button btnMenu, btnUp, btnDown, btnSave1, btnSave2, btnSave3, btnSave4, btnSave5, btnCallOrList, btnPTT, btnSecretMode;
    private Button btnVM, btnAB;
    private Button btn1, btn2, btn3, btn4, btn5, btn6, btn7, btn8, btn9, btn0;
    private View btnPttLeft, btnPttRight;

    private boolean isAdminMode = false;
    private PowerManager.WakeLock wakeLock;
    private int currentChannel = 4; // Используется для старой логики
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

    // V13.0: Переменные для частот
    private int freqTop = 145500;
    private int freqBottom = 430000;
    private boolean isTopActive = true;
    private String inputBuffer = "";
    private String currentBand = "V"; // V = 144-146, M = 430-440

    private boolean isScanning = false;
    private int scanMode = 0;
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
                .putString("MyMailIndex", "145500")
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

        // V13.0: Загружаем частоту из MyMailIndex
        try {
            freqTop = Integer.parseInt(prefs.getString("MyMailIndex", "145500"));
        } catch (Exception ignored) {}
        
        if (freqTop >= 430000) {
            currentBand = "M";
            freqBottom = 145500;
        } else {
            currentBand = "V";
            freqBottom = 430000;
        }

        checkAndRequestPermissions();

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
        updateFreqUI();
        updateServerIpVisibility();
        updatePowerButton();

        if (!PmrService.isRunning) {
            try {
                startService(new Intent(this, PmrService.class));
            } catch (Exception e) {
                Toast.makeText(this, "Ошибка запуска службы: " + e.getMessage(), Toast.LENGTH_LONG).show();
                AppLog.add("MainActivity: ОШИБКА startService — " + e.getMessage());
            }
        }
    }

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQ_INSTALL);
            }
        }
    }

    private void beep() {
        if (!soundEnabled || toneGen == null) return;
        try {
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 100);
        } catch (Exception ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void initViews() {
        tvFreqTop = findViewById(R.id.tvFreqTop);
        tvFreqBottom = findViewById(R.id.tvFreqBottom);
        tvVersion = findViewById(R.id.tvVersion);
        tvSpeakingId = findViewById(R.id.tvSpeakingId);
        tvActiveUsers = findViewById(R.id.tvActiveUsers);
        tvServerIp = findViewById(R.id.tvServerIp);

        imgRed = findViewById(R.id.imgRed);
        imgGreen = findViewById(R.id.imgGreen);
        btnPower = findViewById(R.id.btnPower);

        btnMenu = findViewById(R.id.btnMenu);
        btnUp = findViewById(R.id.btnUp);
        btnDown = findViewById(R.id.btnDown);
        btnVM = findViewById(R.id.btnVM);
        btnAB = findViewById(R.id.btnAB);
        
        btn1 = findViewById(R.id.btn1);
        btn2 = findViewById(R.id.btn2);
        btn3 = findViewById(R.id.btn3);
        btn4 = findViewById(R.id.btn4);
        btn5 = findViewById(R.id.btn5);
        btn6 = findViewById(R.id.btn6);
        btn7 = findViewById(R.id.btn7);
        btn8 = findViewById(R.id.btn8);
        btn9 = findViewById(R.id.btn9);
        btn0 = findViewById(R.id.btn0);

        btnSecretMode = findViewById(R.id.btnSecretMode);
        btnPttLeft = findViewById(R.id.btnPttLeft);
        btnPttRight = findViewById(R.id.btnPttRight);

        // Если в layout есть старые кнопки, они не будут найдены, но это не вызовет ошибку, если мы не используем их напрямую.
        // В V13.0 мы заменили layout на UV-5R.
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

        btnMenu.setOnClickListener(v -> {
            beep();
            startActivity(new Intent(this, SettingsActivity.class));
        });

        btnVM.setOnClickListener(v -> {
            beep();
            if (currentBand.equals("V")) {
                currentBand = "M";
                setActiveFreq(430000);
            } else {
                currentBand = "V";
                setActiveFreq(145500);
            }
            updateFreqUI();
        });

        btnAB.setOnClickListener(v -> {
            beep();
            isTopActive = !isTopActive;
            updateFreqUI();
        });

        btnUp.setOnClickListener(v -> {
            beep();
            setActiveFreq(getActiveFreq() + 25);
            updateFreqUI();
        });

        btnDown.setOnClickListener(v -> {
            beep();
            setActiveFreq(getActiveFreq() - 25);
            updateFreqUI();
        });

        // V13.0: Ввод частоты с клавиатуры
        View.OnClickListener numListener = v -> {
            beep();
            Button b = (Button) v;
            inputBuffer += b.getText().toString();
            
            // Если введено 5 цифр (например, 14550) или 6 цифр (145500), применяем
            if (inputBuffer.length() >= 5) {
                try {
                    int newFreq = Integer.parseInt(inputBuffer);
                    if (newFreq >= 100000 && newFreq <= 999999) {
                        setActiveFreq(newFreq);
                        updateFreqUI();
                        inputBuffer = "";
                    } else {
                        inputBuffer = "";
                    }
                } catch (Exception e) {
                    inputBuffer = "";
                }
            }
        };

        btn1.setOnClickListener(numListener);
        btn2.setOnClickListener(numListener);
        btn3.setOnClickListener(numListener);
        btn4.setOnClickListener(numListener);
        btn5.setOnClickListener(numListener);
        btn6.setOnClickListener(numListener);
        btn7.setOnClickListener(numListener);
        btn8.setOnClickListener(numListener);
        btn9.setOnClickListener(numListener);
        btn0.setOnClickListener(numListener);

        // V13.0: PTT (3 зоны)
        View.OnTouchListener pttTouchListener = this::handlePttTouch;
        btnPTT.setOnTouchListener(pttTouchListener);
        if (btnPttLeft != null) btnPttLeft.setOnTouchListener(pttTouchListener);
        if (btnPttRight != null) btnPttRight.setOnTouchListener(pttTouchListener);
    }

    // V13.0: Получить частоту активной строки
    private int getActiveFreq() {
        return isTopActive ? freqTop : freqBottom;
    }

    // V13.0: Установить частоту активной строки
    private void setActiveFreq(int freq) {
        if (freq < 100000) freq = 100000;
        if (freq > 999999) freq = 999999;

        if (isTopActive) {
            freqTop = freq;
            // Автоматически определяем диапазон
            if (freq >= 430000) currentBand = "M";
            else currentBand = "V";
        } else {
            freqBottom = freq;
        }

        // Сохраняем в MyMailIndex
        prefs.edit().putString("MyMailIndex", String.valueOf(freqTop)).apply();

        // Перезапускаем сокет с новой частотой
        if (PmrService.pmrSocket != null) {
            PmrService.pmrSocket.recreateSocket(this);
        }
    }

    // V13.0: Обновление вида частот
    private void updateFreqUI() {
        String topStr = String.format("%d.%03d", freqTop / 1000, freqTop % 1000);
        String botStr = String.format("%d.%03d", freqBottom / 1000, freqBottom % 1000);

        if (!inputBuffer.isEmpty()) {
            if (isTopActive) topStr = inputBuffer + "_";
            else botStr = inputBuffer + "_";
        }

        tvFreqTop.setText(topStr);
        tvFreqBottom.setText(botStr);

        if (isTopActive) {
            tvFreqTop.setTextColor(Color.RED);
            tvFreqBottom.setTextColor(Color.WHITE);
        } else {
            tvFreqTop.setTextColor(Color.WHITE);
            tvFreqBottom.setTextColor(Color.RED);
        }
    }

    private boolean handlePttTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pttDownTime = System.currentTimeMillis();
                pttHoldActive = false;
                if (isScanning) stopScanning();
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
        updatePowerButton();
    }

    private void updatePowerButton() {
        // В V13.0 кнопка питания на картинке UV-5R отсутствует, логика скрыта
    }

    private void updateServerIpVisibility() {
        // В V13.0 IP скрыт, чтобы не портить вид рации
    }

    private void highlightPreset(int presetNum) {
        // Заглушка для V13.0
    }

    private void resetPresetHighlight() {
        // Заглушка для V13.0
    }

    private void applyPresetHighlight(Button btn, boolean highlighted) {
        // Заглушка для V13.0
    }

    private void startScanning() {
        // Заглушка для V13.0
    }

    private void switchToChannelScan(int presetNum) {
        // Заглушка для V13.0
    }

    private void stopScanning() {
        isScanning = false;
    }

    private void pauseScanningForVoice() {
        // Заглушка для V13.0
    }

    private void applyScanHighlight(boolean active) {
        // Заглушка для V13.0
    }

    private void handlePresetClick(int presetNum) {
        // Заглушка для V13.0
    }

    private void savePreset(int presetNum) {
        // Заглушка для V13.0
    }

    private void loadPreset(int presetNum) {
        // Заглушка для V13.0
    }

    private void updateUI() {
        // Заглушка для V13.0
    }

    @Override
    protected void onResume() {
        super.onResume();
        soundEnabled = prefs.getBoolean("sound_enabled", false);
        isAdminMode = prefs.getBoolean("is_admin_mode", false);
        pttLatchEnabled = prefs.getBoolean("ptt_latch_enabled", true);
        showListNames = prefs.getBoolean("show_list_names", false);

        updateFreqUI();
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