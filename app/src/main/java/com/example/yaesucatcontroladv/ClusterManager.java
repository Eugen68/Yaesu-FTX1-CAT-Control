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
import java.util.Locale;
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
    // Dacă serverul nu trimite NICIO linie (nici măcar bannerul/promptul de login)
    // timp de atâta, considerăm conexiunea blocată (ex: login rămas agățat la
    // "login:" pentru că username-ul era gol) și forțăm reconectarea.
    private static final long STALE_DATA_TIMEOUT_MS = 30_000;
    // Watchdog-ul e verificat la fiecare tick al acestui interval - trebuie sa
    // fie mai mic decat STALE_DATA_TIMEOUT_MS ca pragul de mai sus sa fie
    // respectat cat mai aproape de valoarea reala (nu doar verificat o data pe minut).
    private static final long HEARTBEAT_INTERVAL_SEC = 15;

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

    private volatile long lastDataAtMillis = 0L;
    private volatile boolean loginPhaseDone = false;

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
                // Conexiune "agățată": serverul nu a trimis absolut nimic de multă
                // vreme (nici bannerul de login, nici spoturi, nici alt trafic al
                // clusterului) - simptom tipic pentru un login rămas blocat la
                // prompt (ex: username gol). Forțăm reconectarea în loc să rămânem
                // la infinit cu starea "Connected" dar fără spoturi.
                long silenceMs = System.currentTimeMillis() - lastDataAtMillis;
                if (silenceMs > STALE_DATA_TIMEOUT_MS) {
                    Log.w(TAG, "Nicio linie primită de " + (silenceMs / 1000) + "s - reconectare fortata");
                    notifyError("No response from cluster for " + (silenceMs / 1000) + "s - reconnecting");
                    disconnect();
                    return;
                }
                // Trimitem un Heartbeat (CRLF) pentru a menține socket-ul activ
                sendCommand("\r\n");
            }
        }, HEARTBEAT_INTERVAL_SEC, HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS);
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

        notifyStatus(false, "Disconnected");
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

            // Bază de pornire pentru watchdog-ul de "conexiune blocată" (vezi
            // startHeartbeat()) - altfel, la valoarea implicită 0, primul tick
            // de heartbeat ar crede că n-a mai văzut date de zeci de ani.
            lastDataAtMillis = System.currentTimeMillis();
            loginPhaseDone = false;
            long connectStartMillis = System.currentTimeMillis();

            Log.d(TAG, "Socket deschis. Pornire autentificare...");
            notifyStatus(true, "Connected. Authenticating...");

            // --- FAZA DE AUTENTIFICARE ROBUSTĂ ---
            // Citim caracter cu caracter până detectăm "login:" sau "callsign:",
            // pentru că serverele Telnet nu trimit \n după prompt.
            socket.setSoTimeout(1000); // Timeout scurt pentru faza de auth
            StringBuilder sb = new StringBuilder();
            boolean authenticated = false;
            long authDeadline = System.currentTimeMillis() + 15000;

            while (isRunning.get() && !authenticated && System.currentTimeMillis() < authDeadline) {
                try {
                    int c = reader.read();
                    if (c == -1) break;
                    sb.append((char) c);
                    String current = sb.toString().toLowerCase(Locale.US);

                    if (current.contains("login:") || current.contains("callsign:") || current.contains("input your call")) {
                        Log.d(TAG, "Detectat prompt login, trimit username: " + username);
                        writer.print(username + "\r\n");
                        writer.flush();
                        sb.setLength(0);
                        authenticated = true; 
                    }
                } catch (java.net.SocketTimeoutException e) {
                    // Ignorăm, verificăm loop-ul
                }
            }

            // Dacă serverul cere și parolă, verificăm scurt dacă apare "password:".
            if (isRunning.get() && !password.isEmpty()) {
                long passDeadline = System.currentTimeMillis() + 3000;
                while (isRunning.get() && System.currentTimeMillis() < passDeadline) {
                    try {
                        int c = reader.read();
                        if (c == -1) break;
                        sb.append((char) c);
                        if (sb.toString().toLowerCase(Locale.US).contains("password:")) {
                            Log.d(TAG, "Detectat prompt password, trimit parola");
                            writer.print(password + "\r\n");
                            writer.flush();
                            break;
                        }
                    } catch (java.net.SocketTimeoutException e) {
                        // Ignorăm
                    }
                }
            }

            socket.setSoTimeout(300000); // Restaurăm timeout-ul lung (5 min)

            loginPhaseDone = true;
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

                    // Orice linie primită (banner, prompt, spot, mesaj) dovedește că
                    // legătura e vie - resetăm watchdog-ul de conexiune blocată.
                    lastDataAtMillis = System.currentTimeMillis();

                    if (!isRunning.get()) break;

                    // Detectare login refuzat (ex: username gol/invalid trimis la
                    // autentificare -> DXSpider răspunde "Sorry ... invalid callsign"
                    // și rămâne la promptul de login la nesfârșit). Verificăm doar
                    // în fereastra de dinaintea confirmării de login, ca să nu
                    // declanșăm fals pe un comentariu de spot care conține din
                    // întâmplare cuvântul "sorry".
                    if (!loginPhaseDone) {
                        String lower = line.toLowerCase(Locale.US);
                        if (lower.contains("invalid callsign") || lower.contains("connection refused")) {
                            notifyError("Login refused by cluster: " + line.trim() + " - check MY CALL in settings");
                            break;
                        }
                        // După primele 15s renunțăm la verificare - o linie de spot
                        // normală nu are cum să conțină din întâmplare aceste fraze,
                        // dar nu vrem sa depindem la nesfarsit de un mesaj exact.
                        if (System.currentTimeMillis() - connectStartMillis > 15_000) {
                            loginPhaseDone = true;
                        }
                    }

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
            throw new IOException("Timeout connecting / resolving DNS for " + host + ":" + port
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
     * Parsează o linie de la cluster ca Spot.
     * Format tipic: "DX de YO9VA: 14250.0 W1AW CQ NA 1234Z"
     */
    private Spot parseSpotLine(String line) {
        if (line == null || line.isEmpty()) return null;

        // Relaxăm condiția: căutăm structura minimă a unui spot (frecvență numerică la început)
        // Un spot DXSpider tipic: "DX de YO9VA:  14025.0  W1AW       CQ TEST      1234Z"
        // Dar poate fi și: "DX de YO9VA-1: 14025.0 ..."
        if (!line.contains(":")) return null;

        try {
            Spot spot = new Spot();

            // Extragem spotter-ul (înainte de ":")
            int colonIdx = line.indexOf(":");
            String beforeColon = line.substring(0, colonIdx).trim();
            
            // Formatul clasic "DX de ..."
            if (beforeColon.startsWith("DX de ")) {
                spot.spotter = beforeColon.substring(6).trim();
            } else {
                // Dacă nu începe cu "DX de", dar conține ":" și are frecvență după, 
                // încercăm să-l procesăm oricum (unele servere trimit formate minimaliste)
                spot.spotter = beforeColon; 
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