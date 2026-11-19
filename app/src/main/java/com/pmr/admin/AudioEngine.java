package com.pmr.admin;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioRecord;
import android.media.AudioTrack;

public class AudioEngine {
    private static final int BUF_ELEMENTS = 320;
    private static final int BYTES_PER_ELEM = 2;
    public static final int CMD_G711_16K = 22;
    public static final int CMD_G711_8K = 26;
    public static final int CMD_PCM16_16K = 21;
    public static final int CMD_PCM16_8K = 25;
    public static final int CMD_PCM8_16K = 19;
    public static final int CMD_PCM8_8K = 27;
    private static final int OFFSET_8K = 20;
    private static final int SAMPLE_RATE_16K = 16000;
    private static final int SAMPLE_RATE_8K = 8000;
    private static final int SLOTS_PER_FORMAT = 20;
    private static final float DEFAULT_VOLUME = 1.7f;

    private final Context appCtx;
    private final PmrSocket pmrSocket;
    private final G711Ua g711 = new G711Ua();
    private AudioTrack[] tracks = new AudioTrack[40];
    private AudioRecord recorder = null;
    private Thread recorderThread = null;
    private volatile boolean isRecording = false;
    private volatile boolean isPlaying = false;
    private volatile int rej = 22;

    // V13.6: Мониторинг VOX
    private volatile boolean isVoxMonitoring = false;
    private volatile boolean isVoxActive = false;
    private volatile boolean isPttActive = false;

    private boolean onUsilMic = false;
    private boolean onAgc = false;
    private float micGain = 2.0f;
    private boolean onUsilDin = false;
    private float dinGain = 0.4f;
    private double maxMic = 0.0;

    public AudioEngine(Context ctx, PmrSocket sock) {
        this.appCtx = ctx;
        this.pmrSocket = sock;
        loadGainSettings();
    }

    public void loadGainSettings() {
        SharedPreferences prefs = appCtx.getSharedPreferences("pmr_prefs", Context.MODE_PRIVATE);
        onUsilMic = prefs.getBoolean("onUsilMic", false);
        onAgc = prefs.getBoolean("onAgc", false);
        micGain = prefs.getFloat("MicUsildouble", 2.0f);
        onUsilDin = prefs.getBoolean("onUsilDin", false);
        dinGain = prefs.getFloat("DinUsildouble", 0.4f);
    }

    public void applyGain() {
        loadGainSettings();
        if (isPlaying) {
            float vol = onUsilDin ? dinGain : DEFAULT_VOLUME;
            for (AudioTrack t : tracks) {
                if (t != null) {
                    try { t.setVolume(vol); } catch (Exception ignored) {}
                }
            }
            AppLog.add("AudioEngine: applyGain din=" + vol);
        }
        AppLog.add("AudioEngine: applyGain mic=" + micGain + ", agc=" + onAgc + ", din=" + dinGain);
    }

    private void applyAgc(short[] pcm, int len) {
        int max = 0;
        for (int i = 0; i < len; i++) {
            int val = Math.abs(pcm[i]);
            if (max < val) max = val;
        }
        double r = 0x7FFF;
        if (max == 0) max = 1;
        double delit = r / max;
        if (delit < maxMic) maxMic = delit;
        if (delit > maxMic) {
            if (micGain > 0.0 && maxMic < micGain) maxMic = maxMic + 0.1;
        }
        if (maxMic > micGain) maxMic = micGain;
        for (int i = 0; i < len; i++) {
            double rVal = pcm[i] * maxMic;
            if (rVal > 32767) rVal = 32767;
            if (rVal < -32768) rVal = -32768;
            pcm[i] = (short) rVal;
        }
    }

    public void setRej(int r) { this.rej = r; }
    public int getRej() { return this.rej; }
    public boolean isRecording() { return this.isRecording; }
    public boolean isPlaying() { return this.isPlaying; }
    public boolean isDuplex() { return true; }

    // V13.6: Включение/выключение мониторинга VOX
    public void setVoxMonitoring(boolean enabled) {
        this.isVoxMonitoring = enabled;
        if (enabled && !isRecording) {
            startRecording(); // Запускаем фоновый захват для VOX
        } else if (!enabled && !isPttActive) {
            stopRecording(); // Если VOX выключен и PTT нет — стоп
        }
    }
    public void setVoxActive(boolean active) { this.isVoxActive = active; }
    public void setPttActive(boolean active) { this.isPttActive = active; }

    public void pushToRingBuffer(byte[] packet) {
        if (packet != null && packet.length >= 324 && isPlaying) {
            int client = (packet[2] & 0xFF) | ((packet[3] & 0xFF) << 8);
            if (packet[0] == 22) {
                playG711_16k(client, packet, packet.length);
            }
        }
    }

    public void startPlaying() {
        if (this.isPlaying) return;
        loadGainSettings();
        float vol = onUsilDin ? dinGain : DEFAULT_VOLUME;

        for (int i = 0; i < 20; i++) {
            if (this.tracks[i] == null) {
                this.tracks[i] = new AudioTrack(3, SAMPLE_RATE_16K, 4, 2, AudioTrack.getMinBufferSize(SAMPLE_RATE_16K, 4, 2), 1);
                try { this.tracks[i].setVolume(vol); } catch (Exception ignored) {}
            }
        }
        for (int i2 = 20; i2 < 40; i2++) {
            if (this.tracks[i2] == null) {
                this.tracks[i2] = new AudioTrack(3, SAMPLE_RATE_8K, 4, 2, AudioTrack.getMinBufferSize(SAMPLE_RATE_8K, 4, 2), 1);
                try { this.tracks[i2].setVolume(vol); } catch (Exception ignored) {}
            }
        }
        this.isPlaying = true;
    }

    public void stopPlaying() {
        this.isPlaying = false;
        int i = 0;
        while (true) {
            AudioTrack[] audioTrackArr = this.tracks;
            if (i < audioTrackArr.length) {
                AudioTrack audioTrack = audioTrackArr[i];
                if (audioTrack != null) {
                    try { audioTrack.flush(); this.tracks[i].stop(); this.tracks[i].release(); } catch (Exception ignored) {}
                    this.tracks[i] = null;
                }
                i++;
            } else {
                return;
            }
        }
    }

    public void playall() {}
    public void pauseall() {}

    private boolean isValid16(int client) { return client >= 0 && client < 20; }
    private boolean isValid8(int client) { return client >= 0 && client < 20; }

    public void playG711_16k(int client, byte[] buf, int len) {
        if (this.isPlaying && isValid16(client) && this.tracks[client] != null) {
            byte[] pcm = new byte[640];
            this.g711.decode(buf, 4, BUF_ELEMENTS, pcm);
            try {
                this.tracks[client].write(pcm, 0, 640);
                if (this.tracks[client].getPlayState() != 3) this.tracks[client].play();
            } catch (Exception ignored) {}
        }
    }

    public void playG711_8k(int client, byte[] buf, int len) {
        if (!this.isPlaying || !isValid8(client)) return;
        int slot = client + 20;
        if (this.tracks[slot] == null) return;
        byte[] pcm = new byte[BUF_ELEMENTS];
        this.g711.decode(buf, 4, 160, pcm);
        try {
            this.tracks[slot].write(pcm, 0, BUF_ELEMENTS);
            if (this.tracks[slot].getPlayState() != 3) this.tracks[slot].play();
        } catch (Exception ignored) {}
    }

    public void playPCM16_16k(int client, byte[] buf, int len) {
        AudioTrack audioTrack;
        if (this.isPlaying && isValid16(client) && (audioTrack = this.tracks[client]) != null) {
            try {
                audioTrack.write(buf, 4, 640);
                if (this.tracks[client].getPlayState() != 3) this.tracks[client].play();
            } catch (Exception ignored) {}
        }
    }

    public void playPCM16_8k(int client, byte[] buf, int len) {
        int slot;
        AudioTrack audioTrack;
        if (this.isPlaying && isValid8(client) && (audioTrack = this.tracks[(slot = client + 20)]) != null) {
            try {
                audioTrack.write(buf, 4, BUF_ELEMENTS);
                if (this.tracks[slot].getPlayState() != 3) this.tracks[slot].play();
            } catch (Exception ignored) {}
        }
    }

    public void playPCM8_16k(int client, byte[] buf, int len) {
        if (this.isPlaying && isValid16(client) && this.tracks[client] != null) {
            short[] pcm = new short[BUF_ELEMENTS];
            for (int i = 4; i < 324; i++) pcm[i - 4] = (short) (buf[i] * 256);
            byte[] out = short2byte(pcm);
            try {
                this.tracks[client].write(out, 0, 640);
                if (this.tracks[client].getPlayState() != 3) this.tracks[client].play();
            } catch (Exception ignored) {}
        }
    }

    public void playPCM8_8k(int client, byte[] buf, int len) {
        if (!this.isPlaying || !isValid8(client)) return;
        int slot = client + 20;
        if (this.tracks[slot] == null) return;
        short[] pcm = new short[160];
        for (int i = 4; i < 164; i++) pcm[i - 4] = (short) (buf[i] * 256);
        byte[] out = short2byte(pcm);
        try {
            this.tracks[slot].write(out, 0, BUF_ELEMENTS);
            if (this.tracks[slot].getPlayState() != 3) this.tracks[slot].play();
        } catch (Exception ignored) {}
    }

    public void startRecording() {
        final int sampleRate;
        int bufSize;
        if (this.isRecording) return;

        loadGainSettings();
        if (this.rej == 22 || this.rej == 21 || this.rej == 19) {
            sampleRate = 16000;
            bufSize = 640;
        } else {
            sampleRate = SAMPLE_RATE_8K;
            bufSize = BUF_ELEMENTS;
        }
        try {
            AudioRecord audioRecord = new AudioRecord(1, sampleRate, 16, 2, bufSize * 2);
            this.recorder = audioRecord;
            audioRecord.startRecording();
            this.isRecording = true;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    lambda$startRecording$0(sampleRate);
                }
            }, "pmr-rec");
            this.recorderThread = thread;
            thread.start();
        } catch (Exception e) {
            AppLog.add("AudioEngine: ОШИБКА startRecording — " + e);
        }
    }

    public void stopRecording() {
        this.isRecording = false;
        AudioRecord audioRecord = this.recorder;
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                this.recorder.release();
            } catch (Exception ignored) {}
            this.recorder = null;
        }
        this.recorderThread = null;
    }

    private void lambda$startRecording$0(int sampleRate) {
        int cmd;
        int payloadLen;
        byte[] payload;
        short[] pcm16Short = new short[BUF_ELEMENTS];
        byte[] pcm16 = new byte[640];
        byte[] g711buf = new byte[BUF_ELEMENTS];
        int packetCount = 0;

        while (this.isRecording) {
            try {
                int read = this.recorder.read(pcm16, 0, pcm16.length);
                if (read > 0) {
                    // V13.6: Всегда считаем уровень для VOX
                    if (read >= 2) {
                        int numSamples = read / 2;
                        long sum = 0;
                        for (int i = 0; i < numSamples; i++) {
                            int sample = (short) ((pcm16[i*2] & 0xFF) | (pcm16[i*2+1] << 8));
                            sum += Math.abs(sample);
                        }
                        int avg = (int) (sum / numSamples);
                        int levelPercent = (int) ((avg / 32767.0) * 100);
                        
                        Intent voxIntent = new Intent("PMR_VOX_LEVEL");
                        voxIntent.putExtra("level", levelPercent);
                        appCtx.sendBroadcast(voxIntent);
                    }

                    // Отправка звука только если PTT активен или VOX сработал
                    if (!isPttActive && !isVoxActive) {
                        continue; // Пропускаем отправку
                    }

                    if (onUsilMic) {
                        for (int i = 0; i < read; i += 2) {
                            int sample = (short) ((pcm16[i] & 0xFF) | (pcm16[i+1] << 8));
                            sample = (int) (sample * micGain);
                            if (sample > 32767) sample = 32767;
                            if (sample < -32768) sample = -32768;
                            pcm16[i] = (byte) (sample & 0xFF);
                            pcm16[i+1] = (byte) ((sample >> 8) & 0xFF);
                        }
                    }
                    if (onAgc) {
                        int count = read / 2;
                        for (int i = 0; i < count; i++) {
                            pcm16Short[i] = (short) ((pcm16[i * 2] & 0xFF) | (pcm16[i * 2 + 1] << 8));
                        }
                        applyAgc(pcm16Short, count);
                        for (int i = 0; i < count; i++) {
                            pcm16[i * 2] = (byte) (pcm16Short[i] & 0xFF);
                            pcm16[i * 2 + 1] = (byte) ((pcm16Short[i] >> 8) & 0xFF);
                        }
                    }

                    if (this.rej == 22) {
                        cmd = 22;
                        this.g711.encode(pcm16, 0, read, g711buf);
                        payloadLen = read / 2;
                        payload = new byte[payloadLen];
                        System.arraycopy(g711buf, 0, payload, 0, payloadLen);
                    } else if (this.rej == 21) {
                        cmd = 21;
                        payloadLen = read;
                        payload = new byte[payloadLen];
                        System.arraycopy(pcm16, 0, payload, 0, payloadLen);
                    } else if (this.rej == 25) {
                        cmd = 25;
                        payloadLen = read;
                        payload = new byte[payloadLen];
                        System.arraycopy(pcm16, 0, payload, 0, payloadLen);
                    } else if (this.rej == 19) {
                        cmd = 19;
                        short[] s = byte2short(pcm16);
                        payloadLen = s.length;
                        payload = new byte[payloadLen];
                        for (int i = 0; i < s.length; i++) payload[i] = (byte) (s[i] / 256);
                    } else if (this.rej == 26) {
                        cmd = 26;
                        this.g711.encode(pcm16, 0, read, g711buf);
                        payloadLen = read / 2;
                        payload = new byte[payloadLen];
                        System.arraycopy(g711buf, 0, payload, 0, payloadLen);
                    } else {
                        continue;
                    }
                    
                    PmrSocket pmrSocket = this.pmrSocket;
                    int secret = pmrSocket != null ? pmrSocket.getKanalSecretInstance() : 0;
                    byte[] packet = new byte[payloadLen + 6];
                    packet[0] = (byte) cmd;
                    packet[1] = 0;
                    packet[2] = (byte) (PmrSocket.Priznak_pmr & 255);
                    packet[3] = (byte) ((PmrSocket.Priznak_pmr >> 8) & 255);
                    packet[4] = (byte) (secret & 255);
                    packet[5] = (byte) ((secret >> 8) & 255);
                    System.arraycopy(payload, 0, packet, 6, payloadLen);
                    
                    byte[] mainPacket = new byte[payloadLen + 4];
                    mainPacket[0] = (byte) cmd;
                    mainPacket[1] = 0;
                    mainPacket[2] = (byte) (PmrSocket.Priznak_pmr & 255);
                    mainPacket[3] = (byte) ((PmrSocket.Priznak_pmr >> 8) & 255);
                    System.arraycopy(payload, 0, mainPacket, 4, payloadLen);
                    
                    if (pmrSocket != null) pmrSocket.sendVoice(mainPacket, packet);

                    packetCount++;
                    if (packetCount <= 3 || packetCount % 50 == 0) {
                        AppLog.add("AudioEngine: пакет #" + packetCount + ", cmd=" + cmd + ", payloadLen=" + payloadLen);
                    }
                }
            } catch (Exception e) {
                AppLog.add("AudioEngine: ошибка в цикле записи — " + e);
                return;
            }
        }
    }

    private static byte[] short2byte(short[] sArr) {
        int length = sArr.length;
        byte[] bArr = new byte[length * 2];
        for (int i = 0; i < length; i++) {
            int i2 = i * 2;
            bArr[i2] = (byte) (sArr[i] & 255);
            bArr[i2 + 1] = (byte) (sArr[i] >> 8);
        }
        return bArr;
    }

    private static short[] byte2short(byte[] b) {
        short[] s = new short[b.length / 2];
        for (int i = 0; i < s.length; i++) {
            s[i] = (short) ((b[i * 2] & 255) | (b[(i * 2) + 1] << 8));
        }
        return s;
    }
}