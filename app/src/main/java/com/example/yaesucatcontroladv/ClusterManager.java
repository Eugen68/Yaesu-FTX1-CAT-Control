package com.example.yaesucatcontroladv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manager pentru conexiunea la DX Cluster prin Telnet.
 * Se conectează la serverul configurat, parsează spot-urile în timp real
 * și le distribuie către UI prin callback.
 */
public class ClusterManager {
    private static final String TAG = "ClusterManager";
    private static final int CONNECT_TIMEOUT_MS = 15000;

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
    private ScheduledExecutorService heartbeatScheduler;

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
        // Din UI, adresa vine acum ca un singur câmp "ADRESA:port" (ex: dx.w1nr.net:7300).
        String serverEntry = prefs.getString("cluster_server", "dx.w1nr.net:7300").trim();
        int colonIdx = serverEntry.lastIndexOf(":");
        if (colonIdx > 0 && colonIdx < serverEntry.length() - 1) {
            serverHost = serverEntry.substring(0, colonIdx).trim();
            try {
                serverPort = Integer.parseInt(serverEntry.substring(colonIdx + 1).trim());
            } catch (Exception e) {
                serverPort = 7300;
            }
        } else {
            // Compatibilitate cu formatul vechi (Server și Port salvate separat)
            serverHost = serverEntry;
            String portStr = prefs.getString("cluster_port", "7300");
            try {
                serverPort = Integer.parseInt(portStr);
            } catch (Exception e) {
                serverPort = 7300;
            }
        }
        // Fiecare server din listă are propriul user/pass (setat în Ecran Setări)
        username = EcranSetariProprii.getUserPentruServer(context, serverEntry);
        password = SecurePrefsHelper.getPassword(context, EcranSetariProprii.parolaKeyPentruServer(serverEntry), "");
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
        startHeartbeat();
        connectionThread = new Thread(this::connectionLoop, "ClusterConnection");
        connectionThread.start();
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
        heartbeatScheduler.scheduleWithFixedDelay(() -> {
            if (isConnected()) {
                // Trimitem un Heartbeat (CRLF) pentru a menține socket-ul activ
                sendCommand("\r\n");
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
            heartbeatScheduler = null;
        }
    }

    /**
     * Oprește conexiunea la cluster.
     */
    public void disconnect() {
        isRunning.set(false);
        stopHeartbeat();

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

            // Conectare socket cu deadline STRICT (vezi connectWithTimeout):
            // InetSocketAddress(host, port) face rezoluție DNS SINCRONă, care NU
            // răspunde la socket.close()/interrupt(). Fără această protecție,
            // un server nefuncțional/nerezolvabil poate bloca acest thread la
            // nesfârșit și ține aplicația înghețată la închidere.
            socket = connectWithTimeout(serverHost, serverPort, CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(300000); // 5 minute timeout pentru citire (DX Cluster poate fi inactiv)

            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new PrintWriter(socket.getOutputStream(), true);

            Log.d(TAG, "Socket deschis. Pornire autentificare...");
            notifyStatus(true, "Connected. Authenticating...");

            // Autentificare (dacă e necesară)
            authenticate();

            notifyStatus(true, "Connected to " + serverHost + ":" + serverPort);
            Log.d(TAG, "Autentificare finalizată. Încep citirea spoturilor...");

            // Citire spot-uri în buclă (blocking read)
            while (isRunning.get()) {
                try {
                    String line = reader.readLine();
                    if (line == null) {
                        Log.d(TAG, "Serverul a închis conexiunea (EOF)");
                        break;
                    }

                    if (!isRunning.get()) break;

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

    /**
     * Deschide o conexiune TCP catre host:port cu un deadline STRICT, impus
     * din exterior. Rezolvam hostname-ul (DNS) si facem connect() intr-un
     * executor separat; asteptam rezultatul cu future.get(timeout).
     *
     * De ce e necesar: InetSocketAddress(host, port) face rezolutia DNS
     * SINCRON si NEINTRERUPTIBIL - nu raspunde la Thread.interrupt() si,
     * pe unele device-uri Android, nici socket.close() apelat din alt thread
     * nu garanteaza deblocarea unui connect() aflat in desfasurare catre
     * un server nefunctional/blocat de firewall. Daca nu impunem noi acest
     * deadline, thread-ul de conexiune poate ramane blocat la nesfarsit,
     * tinand Service-ul (si implicit aplicatia) intr-o stare "inghetata"
     * chiar si dupa ce utilizatorul a cerut oprirea/Exit.
     *
     * Nota: daca apelul blocant chiar ramane blocat dincolo de deadline,
     * NU putem forta oprirea lui la nivel de JVM (limitare Java/Android) -
     * dar renuntam logic la el (abandon), astfel incat restul aplicatiei
     * ramane responsiva. Thread-ul abandonat se va inchide singur cand
     * apelul de retea de dedesubt expira la nivel de OS.
     */
    private Socket connectWithTimeout(String host, int port, int timeoutMs) throws Exception {
        ExecutorService connectExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<Socket> future = connectExecutor.submit(() -> {
                Socket s = new Socket();
                java.net.InetSocketAddress addr = new java.net.InetSocketAddress(host, port); // aici poate bloca la DNS
                s.connect(addr, timeoutMs);
                return s;
            });
            return future.get(timeoutMs + 5000L, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            throw new IOException("Timeout la conectare/rezolvare DNS pentru " + host + ":" + port
                    + " (peste " + (timeoutMs + 5000) + " ms)");
        } finally {
            // shutdownNow() nu poate forta oprirea unui apel DNS/connect nativ
            // blocat, dar detașeaza acel thread de noi: nu il mai așteptam
            // si nu-l mai refolosim la reincercarile ulterioare.
            connectExecutor.shutdownNow();
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
        // NU setam listener = null aici: disconnect() posteaza async pe
        // mainHandler notifyStatus(false, "Deconectat"), iar acel callback
        // ruleaza DUPA acest apel (thread-ul principal proceseaza coada in
        // ordine). Daca am nula listener-ul aici, callback-ul deja pus in
        // coada nu ar mai ajunge niciodata la ClusterRepository, iar
        // starea "conectat" ar ramane blocata pentru totdeauna (exact
        // bug-ul cu butonul Disconnect ramas rosu/blocat). Protectia
        // impotriva callback-urilor intarziate de la o incercare abandonata
        // (connectWithTimeout) e deja asigurata de flag-ul isStopping din
        // ClusterService, nu mai e nevoie si aici.
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