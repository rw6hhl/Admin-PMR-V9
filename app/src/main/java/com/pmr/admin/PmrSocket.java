package com.pmr.admin;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
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
    private int portPrd = 16000;
    private int kanalPrd = 13;
    private int kanalSecret = 0;
    private int clientNumber = -1;
    private int pingCount = 0;
    private static int lastVoiceNumber = -1;

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
            this.serverAddr = InetAddress.getByName("185.221.154.39");
            this.socket = new DatagramSocket(portPrm);
            this.socket.setSoTimeout(1000);
            AppLog.add("PmrSocket: Сокет открыт, порт=" + portPrm + ", IP=" + serverAddr.getHostAddress());
        } catch (Exception e) {
            Log.e(TAG, "Error init socket: " + e.getMessage());
            AppLog.add("PmrSocket: ОШИБКА сокета — " + e.getMessage());
        }
    }

    public void setVoiceListener(OnVoiceListener listener) {
        this.voiceListener = listener;
        AppLog.add("PmrSocket: setVoiceListener " + (listener == null ? "null" : listener.getClass().getSimpleName()));
    }

    public static void setChanListListener(OnChanListListener listener) {
        chanListListener = listener;
        AppLog.add("PmrSocket: setChanListListener " + (listener == null ? "null" : listener.getClass().getSimpleName())
                + ", lastChanList.size=" + lastChanList.size());
        if (listener != null && !lastChanList.isEmpty()) {
            AppLog.add("PmrSocket: отдаю lastChanList (size=" + lastChanList.size() + ") в Activity сразу");
            listener.onChanListReceived(lastChanList);
        }
    }

    private void loadSettings() {
        try {
            myMailIndex = Integer.parseInt(prefs.getString("MyMailIndex", "51953"));
        } catch (Exception ignored) {}
        try {
            Priznak_pmr = Integer.parseInt(prefs.getString("Priznak_pmr", "26000"));
        } catch (Exception ignored) {}
        portPrm = prefs.getInt("PORT_prm", 5253);
        portPrd = prefs.getInt("PORT_prd", 16000);

        kanalSecret = (myMailIndex & 0xFFFFFFF0) >> 4;

        int myPChannel = prefs.getInt("current_channel", 4);
        if (myPChannel == 0) kanalPrd = 0;
        else kanalPrd = ((myMailIndex & 0xF) * 8) + myPChannel;

        AppLog.add("PmrSocket: loadSettings — ID=" + Priznak_pmr + ", Mail=" + myMailIndex
                + ", Ch=" + myPChannel + ", kanal=" + kanalPrd
                + ", secret=" + kanalSecret + ", PORT_prm=" + portPrm + ", PORT_prd=" + portPrd);
    }

    public void reloadFromPrefs(Context context) {
        SharedPreferences sp = context.getSharedPreferences("pmr_prefs", Context.MODE_PRIVATE);
        try {
            myMailIndex = Integer.parseInt(sp.getString("MyMailIndex", "51953"));
        } catch (Exception ignored) {}
        try {
            Priznak_pmr = Integer.parseInt(sp.getString("Priznak_pmr", "26000"));
        } catch (Exception ignored) {}
        portPrd = sp.getInt("PORT_prd", 16000);
        portPrm = sp.getInt("PORT_prm", 5253);
        try {
            String ip = sp.getString("ip_server", "185.221.154.39");
            serverAddr = InetAddress.getByName(ip);
        } catch (Exception ignored) {}
        int myPChannel = sp.getInt("current_channel", 4);
        if (myPChannel == 0) kanalPrd = 0;
        else kanalPrd = ((myMailIndex & 0xF) * 8) + myPChannel;
        kanalSecret = (myMailIndex & 0xFFFFFFF0) >> 4;
        AppLog.add("PmrSocket: reloadFromPrefs — mail=" + myMailIndex + ", ch=" + myPChannel
                + ", priznak=" + Priznak_pmr + ", kanal=" + kanalPrd
                + ", secret=" + kanalSecret + ", PORT_prm=" + portPrm + ", PORT_prd=" + portPrd);
    }

    public void recreateSocket(Context context) {
        AppLog.add("PmrSocket: recreateSocket — старт");
        isRunning = false;
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (Exception ignored) {}
        if (receiveThread != null) {
            try { receiveThread.join(500); } catch (Exception ignored) {}
        }
        try { Thread.sleep(100); } catch (Exception ignored) {}
        reloadFromPrefs(context);
        try {
            this.socket = new DatagramSocket(portPrm);
            this.socket.setSoTimeout(1000);
            AppLog.add("PmrSocket: Сокет пересоздан, порт=" + portPrm + ", kanal=" + kanalPrd);
        } catch (Exception e) {
            Log.e(TAG, "Error recreate socket: " + e.getMessage());
            AppLog.add("PmrSocket: ОШИБКА пересоздания сокета — " + e.getMessage());
            return;
        }
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

    public int getClientNumber() {
        return clientNumber;
    }

    public int getKanalSecretInstance() {
        return kanalSecret;
    }

    public void setAudioEngine(AudioEngine audioEngine) {
        this.audioEngine = audioEngine;
    }

    public void start() {
        if (isRunning) return;
        isRunning = true;
        AppLog.add("PmrSocket: start() — запуск потоков");

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
                    ping[0] = 7;
                    ping[1] = 0;
                    ping[2] = (byte) (kanalSecret & 0xFF);
                    ping[3] = (byte) ((kanalSecret >> 8) & 0xFF);
                    socket.send(new DatagramPacket(ping, 4, serverAddr, portPrd + kanalPrd));

                    pingCount++;
                    if (pingCount <= 5 || pingCount % 10 == 0) {
                        AppLog.addVerbose("PmrSocket: ping #" + pingCount + " → порт " + (portPrd + kanalPrd)
                                + ", secret=" + kanalSecret);
                    }

                    Thread.sleep(1000);
                } catch (Exception e) {
                    Log.e(TAG, "Ping error: " + e.getMessage());
                }
            }
        }, "PMR-PingThread");
        pingThread.start();
    }

    /**
     * V10.0: раз в 10 секунд, только в режиме Админа.
     * cmd=77 — признак (4 байта).
     * cmd=88 — имя (2 байта ID + "Admin lin" + \0).
     */
    private void startAdminInfoThread() {
        adminInfoThread = new Thread(() -> {
            // Небольшая задержка, чтобы не дублировать с первым ping в момент старта
            try { Thread.sleep(1000); } catch (Exception ignored) {}
            while (isRunning) {
                try {
                    boolean isAdmin = prefs.getBoolean("is_admin_mode", false);
                    if (isAdmin) {
                        // cmd=77 — признак
                        byte[] priznak = new byte[4];
                        priznak[0] = 77;
                        priznak[1] = 0;
                        priznak[2] = (byte) (Priznak_pmr & 0xFF);
                        priznak[3] = (byte) ((Priznak_pmr >> 8) & 0xFF);
                        socket.send(new DatagramPacket(priznak, 4, serverAddr, portPrd + kanalPrd));
                        AppLog.addVerbose("PmrSocket: → cmd=77 (признак) ID=" + Priznak_pmr
                                + ", порт=" + (portPrd + kanalPrd));

                        // cmd=88 — имя
                        byte[] nameBytes = ADMIN_NAME.getBytes(StandardCharsets.UTF_8);
                        int payloadLen = 4 + 2 + nameBytes.length + 1;
                        byte[] namePacket = new byte[payloadLen];
                        namePacket[0] = 88;
                        namePacket[1] = 0;
                        namePacket[2] = (byte) (Priznak_pmr & 0xFF);
                        namePacket[3] = (byte) ((Priznak_pmr >> 8) & 0xFF);
                        namePacket[4] = (byte) (Priznak_pmr & 0xFF);
                        namePacket[5] = (byte) ((Priznak_pmr >> 8) & 0xFF);
                        System.arraycopy(nameBytes, 0, namePacket, 6, nameBytes.length);
                        namePacket[payloadLen - 1] = 0;
                        socket.send(new DatagramPacket(namePacket, payloadLen, serverAddr, portPrd + kanalPrd));
                        AppLog.addVerbose("PmrSocket: → cmd=88 (имя=\"" + ADMIN_NAME + "\") ID=" + Priznak_pmr
                                + ", порт=" + (portPrd + kanalPrd) + ", len=" + payloadLen);
                    } else {
                        AppLog.addVerbose("PmrSocket: admin info — is_admin_mode=false, пропуск cmd=77/88");
                    }
                    Thread.sleep(ADMIN_INFO_PERIOD_MS);
                } catch (Exception e) {
                    Log.e(TAG, "AdminInfo error: " + e.getMessage());
                    try { Thread.sleep(ADMIN_INFO_PERIOD_MS); } catch (Exception ignored) {}
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
            AppLog.add("PmrSocket: ← cmd=22 (голос), kanal=" + kanal + ", number=" + client + ", size=" + data.length);
        } else if (command == 7) {
            AppLog.addVerbose("PmrSocket: ← cmd=7, kanal=" + kanal + ", client=" + client + ", size=" + data.length);
        } else if (command == 110) {
            AppLog.addVerbose("PmrSocket: ← cmd=110, kanal=" + kanal + ", client=" + client + ", size=" + data.length);
        } else {
            AppLog.add("PmrSocket: ← cmd=" + command + ", kanal=" + kanal + ", client=" + client + ", size=" + data.length);
        }

        if (command == 22 && data.length == 324) {
            if (audioEngine != null) audioEngine.pushToRingBuffer(data);
            if (voiceListener != null) {
                voiceListener.onVoiceReceived(client);
            }
            if (appContext != null) {
                try {
                    Intent i = new Intent("PMR_VOICE_RECEIVED");
                    i.putExtra("clientId", client);
                    appContext.sendBroadcast(i);
                } catch (Exception ignored) {}
            }
        } else if (command == 110) {
            if (clientNumber != client) {
                AppLog.add("PmrSocket: cmd=110 — количество изменилось " + clientNumber + " → " + client + " → requestChanList");
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

    /**
     * V10.0: приём имени от другого админа (cmd=88). Только в режиме Админа.
     * Формат payload: [id_lo, id_hi, name_bytes..., \0]
     */
    private void parseAdminName(byte[] data) {
        if (data.length < 7) return;
        if (appContext == null) return;
        if (!prefs.getBoolean("is_admin_mode", false)) {
            AppLog.addVerbose("PmrSocket: cmd=88 получен, но is_admin_mode=false — игнор");
            return;
        }
        int idLo = data[4] & 0xFF;
        int idHi = data[5] & 0xFF;
        int peerId = idLo | (idHi << 8);
        if (peerId == Priznak_pmr) {
            AppLog.addVerbose("PmrSocket: cmd=88 — свой ID, игнор");
            return;
        }
        int end = 6;
        while (end < data.length && data[end] != 0) end++;
        if (end <= 6) {
            AppLog.add("PmrSocket: cmd=88 — пустое имя для ID=" + peerId + ", игнор");
            return;
        }
        String peerName = new String(data, 6, end - 6, StandardCharsets.UTF_8).trim();
        if (peerName.isEmpty()) return;

        Map<Integer, String> map = NameBook.load(appContext);
        String existing = map.get(peerId);
        if (existing == null) {
            map.put(peerId, peerName);
            NameBook.save(appContext, map);
            AppLog.add("PmrSocket: cmd=88 — добавлено имя ID=" + peerId + " → \"" + peerName + "\"");
            requestChanList();
        } else if (!existing.equals(peerName)) {
            map.put(peerId, peerName);
            NameBook.save(appContext, map);
            AppLog.add("PmrSocket: cmd=88 — обновлено имя ID=" + peerId
                    + " (было \"" + existing + "\" → стало \"" + peerName + "\")");
            requestChanList();
        } else {
            AppLog.addVerbose("PmrSocket: cmd=88 — имя ID=" + peerId + " без изменений");
        }
    }

    private void parseChanList(byte[] data) {
        List<ChanEntry> entries = new ArrayList<>();
        int payload = data.length - 4;
        int count = payload / 13;
        AppLog.add("PmrSocket: parseChanList — size=" + data.length + ", записей=" + count);

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
            AppLog.add("PmrSocket:   запись #" + i + " — number=" + numberRaw + ", cod=" + cod
                    + ", id=" + id + (id == ADMIN_OBSERVER_ID ? " (админ/наблюдатель)" : "")
                    + ", port=" + port + ", ip=" + ip + ", ban=" + ban);
        }

        lastChanList = entries;

        if (chanListListener != null) {
            chanListListener.onChanListReceived(entries);
        } else {
            AppLog.add("PmrSocket: chanListListener == null, список сохранён в lastChanList (size=" + entries.size() + ")");
        }
    }

    public static int getClientIdByNumber(int number) {
        for (ChanEntry e : lastChanList) {
            if (e.number == number) {
                AppLog.add("PmrSocket: getClientIdByNumber(number=" + number + ") → id=" + e.id);
                return e.id;
            }
        }
        AppLog.add("PmrSocket: getClientIdByNumber(number=" + number + ") → не найдено (-1)");
        return -1;
    }

    public static int getLastVoiceNumber() {
        return lastVoiceNumber;
    }

    /**
     * V9.81: cmd=234 запрашивается ТОЛЬКО на канале 5.
     */
    public void requestChanList() {
        if (socket == null || socket.isClosed()) {
            AppLog.add("PmrSocket: requestChanList — сокет null/closed, пропуск");
            return;
        }
        if (prefs.getInt("current_channel", 4) != 5) {
            AppLog.add("PmrSocket: requestChanList — канал не 5, пропуск (cmd=234 только для канала 5)");
            return;
        }
        try {
            byte[] cmd = new byte[4];
            cmd[0] = (byte) 234;
            cmd[1] = 13;
            cmd[2] = 0;
            cmd[3] = 0;
            int listPort = portPrd + 13;
            socket.send(new DatagramPacket(cmd, 4, serverAddr, listPort));
            AppLog.add("PmrSocket: → cmd=234 отправлен, порт=" + listPort + ", kanal=13 (канал 5)");
        } catch (IOException e) {
            AppLog.add("PmrSocket: ОШИБКА cmd=234 — " + e.getMessage());
        }
    }

    public void sendCall() {
        if (socket == null || socket.isClosed()) {
            AppLog.add("PmrSocket: sendCall — сокет null/closed, пропуск");
            return;
        }
        if (kanalPrd == 0) {
            AppLog.add("PmrSocket: sendCall — репитерный канал (0), вызов не отправляется");
            return;
        }

        new Thread(() -> {
            try {
                byte[] callPacket = new byte[24];
                callPacket[0] = (byte) 20;
                callPacket[1] = 0;
                callPacket[2] = (byte) (Priznak_pmr & 0xFF);
                callPacket[3] = (byte) ((Priznak_pmr >> 8) & 0xFF);

                byte[] bellBytes = "BELL".getBytes();
                System.arraycopy(bellBytes, 0, callPacket, 4, bellBytes.length);

                int callPort = portPrd + kanalPrd;
                socket.send(new DatagramPacket(callPacket, 24, serverAddr, callPort));
                AppLog.add("PmrSocket: → cmd=20 (CALL) отправлен, порт=" + callPort + ", kanal=" + kanalPrd);
            } catch (Exception e) {
                AppLog.add("PmrSocket: ОШИБКА cmd=20 (CALL) — " + e.getMessage());
            }
        }).start();
    }

    /**
     * V9.81: cmd=222 (БАН/РАЗБАН) — только на канале 5.
     */
    public void sendBan(final int targetNumber, final int targetId) {
        if (socket == null || socket.isClosed()) {
            AppLog.add("PmrSocket: sendBan — сокет null/closed, пропуск (number=" + targetNumber + ")");
            return;
        }
        if (prefs.getInt("current_channel", 4) != 5) {
            AppLog.add("PmrSocket: sendBan — канал не 5, пропуск (cmd=222 только для канала 5)");
            return;
        }

        new Thread(() -> {
            if (targetId == Priznak_pmr) {
                AppLog.add("PmrSocket: БАН запрещён — попытка забанить самого себя (ID=" + targetId + ")");
                return;
            }

            byte[] ban = new byte[4];
            ban[0] = (byte) 222;
            ban[1] = 13;
            int rawNumber = targetNumber;
            ban[2] = (byte) (rawNumber & 0xFF);
            ban[3] = (byte) ((rawNumber >> 8) & 0xFF);

            try {
                int banPort = portPrd + 13;
                socket.send(new DatagramPacket(ban, 4, serverAddr, banPort));
                AppLog.add("PmrSocket: → cmd=222 (БАН/РАЗБАН) number=" + targetNumber
                        + ", id=" + targetId + ", kanal=13, порт=" + banPort + " (канал 5)");

                requestChanList();
                Thread.sleep(300);
                requestChanList();

            } catch (Exception e) {
                AppLog.add("PmrSocket: ОШИБКА cmd=222 — " + e.getMessage());
            }
        }).start();
    }

    public void sendUnban(int targetNumber, int targetId) {
        sendBan(targetNumber, targetId);
        AppLog.add("PmrSocket: → РАЗБАН number=" + targetNumber + ", id=" + targetId + " (повтор 222)");
    }

    public void stop() {
        isRunning = false;
        if (socket != null && !socket.isClosed()) socket.close();
        AppLog.add("PmrSocket: stop() — сокет закрыт");
    }

    public void sendVoice(byte[] mainPacket, byte[] reservePacket) {
        if (socket == null || socket.isClosed()) return;
        try {
            if (mainPacket != null) {
                socket.send(new DatagramPacket(mainPacket, mainPacket.length, serverAddr, portPrd + kanalPrd));
            }
        } catch (IOException e) {
            AppLog.add("PmrSocket: ОШИБКА sendVoice — " + e.getMessage());
        }
    }
}