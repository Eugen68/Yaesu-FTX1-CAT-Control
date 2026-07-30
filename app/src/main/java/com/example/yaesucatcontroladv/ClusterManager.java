package com.example.yaesucatcontroladv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manager pentru conexiunea la DX Cluster prin Telnet.
 * Se conectează la serverul configurat, parsează spot-urile în timp real
 * și le distribuie către UI prin callback.
 */
public class ClusterManager {
    private static final String TAG = "ClusterManager";

    public interface ClusterListener {
        void onSpotReceived(Spot spot);
        void onConnectionStatusChanged(boolean connected, String message);
        void onError(String error);
    }

    private final Context context;
    private ClusterListener listener;

    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private Thread connectionThread;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService commandExecutor;

    private String serverHost;
    private int serverPort;
    private String username;
    private String password;

    public ClusterManager(Context context) {
        this.context = context;
        this.commandExecutor = Executors.newSingleThreadExecutor();
        loadSettings();
    }

    public void setListener(ClusterListener listener) {
        this.listener = listener;
    }

    /**
     * Încarcă setările din SharedPreferences.
     */
    private void loadSettings() {
        SharedPreferences prefs = context.getSharedPreferences(
                EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE);

        // Server implicit stabil: dx.w1nr.net port 7300
        serverHost = prefs.getString("cluster_server", "dx.w1nr.net");
        String portStr = prefs.getString("cluster_port", "7300");
        try {
            serverPort = Integer.parseInt(portStr);
        } catch (Exception e) {
            serverPort = 7300;
        }
        username = prefs.getString(EcranSetariProprii.PREF_CLUSTER_USER, "");
        password = SecurePrefsHelper.getPassword(context, EcranSetariProprii.PREF_CLUSTER_PASS, "");
        if (username.isEmpty()) {
            username = prefs.getString(EcranSetariProprii.PREF_MY_CALL, "");
        }
    }

    /**
     * Pornește conexiunea la cluster într-un thread separat.
     */
    public void connect() {
        if (isRunning.get()) {
            Log.w(TAG, "Conexiune deja activă");
            return;
        }

        loadSettings(); // Reîncarcă setările (pot fi schimbate)

        isRunning.set(true);
        connectionThread = new Thread(this::connectionLoop, "ClusterConnection");
        connectionThread.start();
    }

    /**
     * Oprește conexiunea la cluster.
     */
    public void disconnect() {
        isRunning.set(false);

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Eroare la închiderea socket-ului", e);
        }

        if (connectionThread != null && connectionThread.isAlive()) {
            connectionThread.interrupt();
        }

        notifyStatus(false, "Deconectat");
    }

    /**
     * Bucla principală de conexiune (rulează în thread separat).
     */
    private void connectionLoop() {
        try {
            Log.d(TAG, "Connecting to: " + serverHost + ":" + serverPort);
            notifyStatus(false, "Connecting to " + serverHost + ":" + serverPort + "...");

            // Conectare socket (timeout mărit pentru routere/GSM)
            socket = new Socket();
            socket.connect(new java.net.InetSocketAddress(serverHost, serverPort), 20000); 
            socket.setSoTimeout(300000); // 5 minute timeout pentru citire (DX Cluster poate fi inactiv)

            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new PrintWriter(socket.getOutputStream(), true);

            Log.d(TAG, "Socket deschis. Pornire autentificare...");
            notifyStatus(true, "Connected. Authenticating...");

            // Autentificare (dacă e necesară)
            authenticate();

            notifyStatus(true, "Connected & Authenticated");
            Log.d(TAG, "Autentificare finalizată. Încep citirea spoturilor...");

            // Citire spot-uri în buclă
            while (isRunning.get()) {
                try {
                    String line = reader.readLine();
                    if (line == null) {
                        Log.d(TAG, "Serverul a închis conexiunea (EOF)");
                        break;
                    }

                    if (!isRunning.get()) break;

                    // Logăm liniile brute pentru debug în Logcat
                    Log.v(TAG, "RX: " + line);

                    // Parsează linia ca spot
                    Spot spot = parseSpotLine(line);
                    if (spot != null) {
                        mainHandler.post(() -> {
                            if (listener != null) {
                                listener.onSpotReceived(spot);
                            }
                        });
                    }
                } catch (java.net.SocketTimeoutException e) {
                    // Timeout-ul a expirat dar nu e eroare fatală. 
                    // Continuăm să ascultăm.
                    Log.d(TAG, "Read timeout (5 min). Still listening...");
                }
            }

        } catch (Exception e) {
            // Dacă am oprit intenționat conexiunea, ignorăm eroarea "Socket closed"
            if (!isRunning.get()) {
                Log.d(TAG, "Conexiune închisă intenționat. Ignorăm eroarea: " + e.getMessage());
                return;
            }

            String resolvedIp = "unknown";
            try { resolvedIp = java.net.InetAddress.getByName(serverHost).getHostAddress(); } catch (Exception ignored) {}
            
            String errType = e.getClass().getSimpleName();
            String detail = e.getMessage() != null ? e.getMessage() : e.toString();
            String err = "(" + resolvedIp + "):" + serverPort + "\n[" + errType + "]\n" + detail;
            Log.e(TAG, "Eroare conexiune: " + err, e);
            notifyError("Connection Error: " + err);
        } finally {
            cleanup();
            notifyStatus(false, "Disconnected");
            isRunning.set(false);
        }
    }

    private void cleanup() {
        try {
            if (reader != null) reader.close();
            if (writer != null) writer.close();
            if (socket != null) socket.close();
        } catch (Exception e) {
            Log.e(TAG, "Eroare la cleanup", e);
        }
    }

    /**
     * Autentificare la server (dacă e necesară).
     */
    private void authenticate() throws Exception {
        // Așteptăm puțin mesajul de welcome ("Login:")
        Thread.sleep(1500);

        // Trimitem username (obligatoriu pentru majoritatea clusterelor)
        if (!username.isEmpty()) {
            Log.d(TAG, "Trimitere username: " + username);
            writer.print(username + "\r\n"); // Folosim CRLF explicit
            writer.flush();
        }

        // Unele servere cer și password
        if (!password.isEmpty()) {
            Thread.sleep(1000);
            Log.d(TAG, "Trimitere password...");
            writer.print(password + "\r\n");
            writer.flush();
        }
    }

    /**
     * Parsează o linie de la cluster ca Spot.
     * Format tipic: "DX de YO9VA: 14250.0 W1AW CQ NA 1234Z"
     */
    private Spot parseSpotLine(String line) {
        if (line == null || line.isEmpty()) return null;

        // Ignorăm liniile care nu sunt spot-uri
        if (!line.contains(":") || !line.contains("DX")) return null;

        try {
            Spot spot = new Spot();

            // Extragem spotter-ul (înainte de ":")
            int colonIdx = line.indexOf(":");
            String beforeColon = line.substring(0, colonIdx).trim();
            if (beforeColon.startsWith("DX de ")) {
                spot.spotter = beforeColon.substring(6).trim();
            }

            // Extragem restul (după ":")
            String afterColon = line.substring(colonIdx + 1).trim();
            String[] parts = afterColon.split("\\s+");

            if (parts.length < 3) return null;

            // Prima parte = frecvența
            try {
                // DX Cluster trimite frecvența în KHz (ex: 14205.7), convertim în MHz
                spot.frequency = Double.parseDouble(parts[0]) / 1000.0;
            } catch (NumberFormatException e) {
                return null;
            }

            // A doua parte = indicativul DX
            spot.dxCall = parts[1];

            // Restul = comentariu
            StringBuilder comment = new StringBuilder();
            for (int i = 2; i < parts.length; i++) {
                comment.append(parts[i]).append(" ");
            }
            spot.comment = comment.toString().trim();

            // Deducem banda și modul
            spot.band = Spot.deduceBanda(spot.frequency);
            spot.mode = Spot.deduceMod(spot.comment, spot.frequency);

            return spot;

        } catch (Exception e) {
            Log.e(TAG, "Eroare la parsarea liniei: " + line, e);
            return null;
        }
    }

    /**
     * Trimite o comandă către server (ex: filtru).
     */
    public void sendCommand(String command) {
        if (command == null || command.isEmpty()) return;

        commandExecutor.execute(() -> {
            if (writer != null && isRunning.get()) {
                writer.println(command);
                writer.flush();
                Log.d(TAG, "Comandă trimisă: " + command);
            }
        });
    }

    /**
     * Închide executorul și resursele la distrugerea managerului.
     */
    public void shutdown() {
        disconnect();
        commandExecutor.shutdownNow();
    }

    /**
     * Notifică UI despre schimbarea statusului conexiunii.
     */
    private void notifyStatus(boolean connected, String message) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onConnectionStatusChanged(connected, message);
            }
        });
    }

    /**
     * Notifică UI despre o eroare.
     */
    private void notifyError(String error) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onError(error);
            }
        });
    }

    public boolean isConnected() {
        return isRunning.get() && socket != null && !socket.isClosed();
    }
}