package com.pmr.admin;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PmrSocket {
    private static final String TAG = "PmrSocket";
    private static final int ADMIN_OBSERVER_ID = 11777;
    private static final int ADMIN_INFO_PERIOD_MS = 10000;
    private static final String ADMIN_NAME = "Admin lin";

    private DatagramSocket socket;
    private InetAddress serverAddr;
    private boolean isRunning = false;
    private Thread receiveThread;
    private Thread pingThread;
    private Thread adminInfoThread;
    private AudioEngine audioEngine;
    private Context appContext;

    private SharedPreferences prefs;
    public static int Priznak_pmr = 26000;
    private int myMailIndex = 51953;
    private int portPrm = 5253;
    private int portPrd = 15000;
    private int kanalPrd = 13;
    private int kanalSecret = 0;
    private int clientNumber = -1;
    private int pingCount = 0;
    private static int lastVoiceNumber = -1;

    // V11.6: Сторожевой таймер звука
    private long lastVoicePacketTime = 0;
    private Thread voiceTimeoutThread;

    public interface OnVoiceListener {
        void onVoiceReceived(int clientNumber);
    }
    private OnVoiceListener voiceListener;

    public static class ChanEntry {
        public int number;
        public int cod;
        public int id;
        public int port;
        public String ip;
        public boolean ban;
        public ChanEntry(int number, int cod, int id, int port, String ip, boolean ban) {
            this.number = number;
            this.cod = cod;
            this.id = id;
            this.port = port;
            this.ip = ip;
            this.ban = ban;
        }
    }

    public interface OnChanListListener {
        void onChanListReceived(List<ChanEntry> entries);
    }
    private static OnChanListListener chanListListener;
    private static List<ChanEntry> lastChanList = new ArrayList<>();

    public static boolean isAdminObserver(int id) {
        return id == ADMIN_OBSERVER_ID;
    }

    public PmrSocket(Context context, OnVoiceListener listener) {
        this.voiceListener = listener;
        this.appContext = context.getApplicationContext();
        this.prefs = context.getSharedPreferences("pmr_prefs", Context.MODE_PRIVATE);
        loadSettings();
        try {
            String ip = prefs.getString("ip_server", "109.172.7.155");
            this.serverAddr = InetAddress.getByName(ip);
            this.socket = createSocket(portPrm);
            this.socket.setSoTimeout(1000);
            AppLog.add("PmrSocket: Сокет открыт, порт=" + portPrm + ", IP=" + serverAddr.getHostAddress());
        } catch (Exception e) {
            Log.e(TAG, "Error init socket: " + e.getMessage());
            AppLog.add("PmrSocket: ОШИБКА сокета — " + e.getMessage());
        }
    }

    private DatagramSocket createSocket(int port) throws Exception {
        DatagramSocket s = new DatagramSocket(null);
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(port));
        return s;
    }

    public void setVoiceListener(OnVoiceListener listener) {
        this.voiceListener = listener;
    }

    public static void setChanListListener(OnChanListListener listener) {
        chanListListener = listener;
        if (listener != null && !lastChanList.isEmpty()) {
            listener.onChanListReceived(lastChanList);
        }
    }

    private void loadSettings() {
        try { myMailIndex = Integer.parseInt(prefs.getString("MyMailIndex", "111111")); } catch (Exception ignored) {}
        try { Priznak_pmr = Integer.parseInt(prefs.getString("Priznak_pmr", "22222")); } catch (Exception ignored) {}
        portPrm = prefs.getInt("PORT_prm", 5253);
        portPrd = prefs.getInt("PORT_prd", 15000);

        kanalSecret = (myMailIndex & 0xFFFFFFF0) >> 4;
        int myPChannel = prefs.getInt("current_channel", 4);
        if (myPChannel == 0) kanalPrd = 0;
        else kanalPrd = ((myMailIndex & 0xF) * 8) + myPChannel;
    }

    public void reloadFromPrefs(Context context) {
        SharedPreferences sp = context.getSharedPreferences("pmr_prefs", Context.MODE_PRIVATE);
        try { myMailIndex = Integer.parseInt(sp.getString("MyMailIndex", "111111")); } catch (Exception ignored) {}
        try { Priznak_pmr = Integer.parseInt(sp.getString("Priznak_pmr", "22222")); } catch (Exception ignored) {}
        portPrd = sp.getInt("PORT_prd", 15000);
        portPrm = sp.getInt("PORT_prm", 5253);
        try { serverAddr = InetAddress.getByName(sp.getString("ip_server", "109.172.7.155")); } catch (Exception ignored) {}
        int myPChannel = sp.getInt("current_channel", 4);
        if (myPChannel == 0) kanalPrd = 0;
        else kanalPrd = ((myMailIndex & 0xF) * 8) + myPChannel;
        kanalSecret = (myMailIndex & 0xFFFFFFF0) >> 4;
    }

    public void recreateSocket(Context context) {
        isRunning = false;
        if (adminInfoThread != null) { adminInfoThread.interrupt(); adminInfoThread = null; }
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (Exception ignored) {}
        if (receiveThread != null) { try { receiveThread.join(500); } catch (Exception ignored) {} }
        try { Thread.sleep(300); } catch (Exception ignored) {}
        reloadFromPrefs(context);
        try {
            this.socket = createSocket(portPrm);
            this.socket.setSoTimeout(1000);
        } catch (Exception e) { return; }
        isRunning = true;
        receiveThread = new Thread(() -> {
            byte[] buffer = new byte[2048];
            while (isRunning) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    byte[] data = new byte[packet.getLength()];
                    System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());
                    parsePacket(data);
                } catch (Exception ignored) {}
            }
        }, "PMR-ReceiveThread");
        receiveThread.start();
        startPingThread();
        startAdminInfoThread();
    }

    public int getClientNumber() { return clientNumber; }
    public int getKanalSecretInstance() { return kanalSecret; }
    public void setAudioEngine(AudioEngine audioEngine) { this.audioEngine = audioEngine; }

    public void start() {
        if (isRunning) return;
        isRunning = true;
        receiveThread = new Thread(() -> {
            byte[] buffer = new byte[2048];
            while (isRunning) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    byte[] data = new byte[packet.getLength()];
                    System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());
                    parsePacket(data);
                } catch (Exception ignored) {}
            }
        }, "PMR-ReceiveThread");
        receiveThread.start();
        startPingThread();
        startAdminInfoThread();
    }

    private void startPingThread() {
        pingThread = new Thread(() -> {
            while (isRunning) {
                try {
                    byte[] ping = new byte[4];
                    ping[0] = 7; ping[1] = 0;
                    ping[2] = (byte) (kanalSecret & 0xFF);
                    ping[3] = (byte) ((kanalSecret >> 8) & 0xFF);
                    socket.send(new DatagramPacket(ping, 4, serverAddr, portPrd + kanalPrd));
                    Thread.sleep(2000);
                } catch (Exception e) {}
            }
        }, "PMR-PingThread");
        pingThread.start();
    }

    private void startAdminInfoThread() {
        if (adminInfoThread != null) { adminInfoThread.interrupt(); adminInfoThread = null; }
        adminInfoThread = new Thread(() -> {
            try { Thread.sleep(1000); } catch (InterruptedException ie) { return; } catch (Exception ignored) {}
            while (isRunning && !Thread.currentThread().isInterrupted()) {
                try {
                    if (prefs.getBoolean("is_admin_mode", false)) {
                        int port = portPrd + kanalPrd;
                        byte[] priznak = new byte[4];
                        priznak[0] = 77; priznak[1] = 0;
                        priznak[2] = (byte) (Priznak_pmr & 0xFF);
                        priznak[3] = (byte) ((Priznak_pmr >> 8) & 0xFF);
                        socket.send(new DatagramPacket(priznak, 4, serverAddr, port));

                        byte[] nameBytes = ADMIN_NAME.getBytes(StandardCharsets.UTF_8);
                        int payloadLen = 4 + 2 + nameBytes.length + 1;
                        byte[] namePacket = new byte[payloadLen];
                        namePacket[0] = 88; namePacket[1] = 0;
                        namePacket[2] = (byte) (Priznak_pmr & 0xFF);
                        namePacket[3] = (byte) ((Priznak_pmr >> 8) & 0xFF);
                        namePacket[4] = (byte) (Priznak_pmr & 0xFF);
                        namePacket[5] = (byte) ((Priznak_pmr >> 8) & 0xFF);
                        System.arraycopy(nameBytes, 0, namePacket, 6, nameBytes.length);
                        namePacket[payloadLen - 1] = 0;
                        socket.send(new DatagramPacket(namePacket, payloadLen, serverAddr, port));
                    }
                    Thread.sleep(ADMIN_INFO_PERIOD_MS);
                } catch (InterruptedException ie) { return; } catch (Exception e) {
                    try { Thread.sleep(ADMIN_INFO_PERIOD_MS); } catch (InterruptedException ie) { return; } catch (Exception ignored) {}
                }
            }
        }, "PMR-AdminInfoThread");
        adminInfoThread.start();
    }

    private void parsePacket(byte[] data) {
        if (data.length < 4) return;
        int command = data[0] & 0xFF;
        int kanal = data[1] & 0xFF;
        int client = (data[2] & 0xFF) | ((data[3] & 0xFF) << 8);

        if (kanal != kanalPrd && kanalPrd != 0) return;

        if (command == 22) {
            lastVoiceNumber = client;
            lastVoicePacketTime = System.currentTimeMillis(); // V11.6: Обновляем время приема звука
        }

        if (command == 22 && data.length == 324) {
            if (audioEngine != null) audioEngine.pushToRingBuffer(data);
            if (voiceListener != null) voiceListener.onVoiceReceived(client);
            if (appContext != null) {
                try {
                    Intent i = new Intent("PMR_VOICE_RECEIVED");
                    i.putExtra("clientId", client);
                    appContext.sendBroadcast(i);
                } catch (Exception ignored) {}
            }
            // V11.6: Запускаем сторожевой таймер (если поток не запущен)
            if (voiceTimeoutThread == null || !voiceTimeoutThread.isAlive()) {
                voiceTimeoutThread = new Thread(() -> {
                    while (isRunning) {
                        try {
                            Thread.sleep(1000);
                            if (System.currentTimeMillis() - lastVoicePacketTime > 3000 && lastVoicePacketTime > 0) {
                                lastVoicePacketTime = 0;
                                if (appContext != null) {
                                    appContext.sendBroadcast(new Intent("PMR_VOICE_END"));
                                }
                                break; // Выходим из потока, он перезапустится при следующем звуке
                            }
                        } catch (InterruptedException ignored) {}
                    }
                });
                voiceTimeoutThread.start();
            }
        } else if (command == 110) {
            if (clientNumber != client) {
                clientNumber = client;
                requestChanList();
            }
            if (appContext != null) {
                try {
                    Intent i = new Intent("PMR_USERS_CHANGED");
                    i.putExtra("count", client);
                    appContext.sendBroadcast(i);
                } catch (Exception ignored) {}
            }
        } else if (command == 234) {
            parseChanList(data);
        } else if (command == 88) {
            parseAdminName(data);
        }
    }

    private void parseAdminName(byte[] data) {
        if (data.length < 7 || appContext == null || !prefs.getBoolean("is_admin_mode", false)) return;
        int peerId = (data[4] & 0xFF) | ((data[5] & 0xFF) << 8);
        if (peerId == Priznak_pmr) return;
        int end = 6;
        while (end < data.length && data[end] != 0) end++;
        if (end <= 6) return;
        String peerName = new String(data, 6, end - 6, StandardCharsets.UTF_8).trim();
        if (peerName.isEmpty()) return;
        Map<Integer, String> map = NameBook.load(appContext);
        if (!peerName.equals(map.get(peerId))) {
            map.put(peerId, peerName);
            NameBook.save(appContext, map);
            requestChanList();
        }
    }

    private void parseChanList(byte[] data) {
        List<ChanEntry> entries = new ArrayList<>();
        int count = (data.length - 4) / 13;
        for (int i = 0; i < count; i++) {
            int offset = 4 + (i * 13);
            if (offset + 12 >= data.length) break;
            int numberRaw = (data[offset + 0] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
            int cod = (data[offset + 2] & 0xFF) | ((data[offset + 3] & 0xFF) << 8);
            int id = (data[offset + 4] & 0xFF) | ((data[offset + 5] & 0xFF) << 8);
            int port = (data[offset + 6] & 0xFF) | ((data[offset + 7] & 0xFF) << 8);
            int ip0 = data[offset + 8] & 0xFF;
            int ip1 = data[offset + 9] & 0xFF;
            int ip2 = data[offset + 10] & 0xFF;
            int ip3 = data[offset + 11] & 0xFF;
            String ip = ip0 + "." + ip1 + "." + ip2 + "." + ip3;
            boolean ban = (data[offset + 12] & 0xFF) != 0;
            entries.add(new ChanEntry(numberRaw, cod, id, port, ip, ban));
        }
        lastChanList = entries;
        if (chanListListener != null) chanListListener.onChanListReceived(entries);
    }

    public static int getClientIdByNumber(int number) {
        for (ChanEntry e : lastChanList) {
            if (e.number == number) return e.id;
        }
        return -1;
    }

    public static int getLastVoiceNumber() { return lastVoiceNumber; }

    public void requestChanList() {
        if (socket == null || socket.isClosed()) return;
        try {
            byte[] cmd = new byte[4];
            cmd[0] = (byte) 234;
            cmd[1] = (byte) (kanalPrd & 0xFF);
            int listPort = portPrd + kanalPrd;
            socket.send(new DatagramPacket(cmd, 4, serverAddr, listPort));
        } catch (IOException e) {}
    }

    public void sendCall() {
        if (socket == null || socket.isClosed() || kanalPrd == 0) return;
        new Thread(() -> {
            try {
                byte[] callPacket = new byte[24];
                callPacket[0] = (byte) 20; callPacket[1] = 0;
                callPacket[2] = (byte) (Priznak_pmr & 0xFF);
                callPacket[3] = (byte) ((Priznak_pmr >> 8) & 0xFF);
                byte[] bellBytes = "BELL".getBytes();
                System.arraycopy(bellBytes, 0, callPacket, 4, bellBytes.length);
                int callPort = portPrd + kanalPrd;
                socket.send(new DatagramPacket(callPacket, 24, serverAddr, callPort));
            } catch (Exception e) {}
        }).start();
    }

    public void sendBan(final int targetNumber, final int targetId) {
        if (socket == null || socket.isClosed()) return;
        new Thread(() -> {
            if (targetId == Priznak_pmr) return;
            byte[] ban = new byte[4];
            ban[0] = (byte) 222; ban[1] = (byte) (kanalPrd & 0xFF);
            ban[2] = (byte) (targetNumber & 0xFF); ban[3] = (byte) ((targetNumber >> 8) & 0xFF);
            try {
                int banPort = portPrd + kanalPrd;
                socket.send(new DatagramPacket(ban, 4, serverAddr, banPort));
                requestChanList();
                Thread.sleep(300);
                requestChanList();
            } catch (Exception e) {}
        }).start();
    }

    public void sendUnban(int targetNumber, int targetId) { sendBan(targetNumber, targetId); }

    public void stop() {
        isRunning = false;
        if (adminInfoThread != null) { adminInfoThread.interrupt(); adminInfoThread = null; }
        if (socket != null && !socket.isClosed()) socket.close();
    }

    public void sendVoice(byte[] mainPacket, byte[] reservePacket) {
        if (socket == null || socket.isClosed()) return;
        try {
            if (mainPacket != null) {
                socket.send(new DatagramPacket(mainPacket, mainPacket.length, serverAddr, portPrd + kanalPrd));
            }
        } catch (IOException e) {}
    }
}