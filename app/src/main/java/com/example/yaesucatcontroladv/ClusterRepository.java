package com.example.yaesucatcontroladv;

import java.util.ArrayList;
import java.util.List;

/**
 * Singleton Repository pentru DX Cluster.
 * Acționează ca sursă unică de adevăr pentru spoturi și starea conexiunii,
 * permițând comunicarea între ClusterService (fundal) și EcranCluster (interfață).
 */
public class ClusterRepository {
    private static ClusterRepository instance;

    public interface RepoListener {
        void onNewSpot(Spot spot);
        void onStatusChanged(boolean connected, String message);

        // Default method: cine implementează RepoListener nu e obligat să o
        // suprascrie. EcranCluster o poate folosi ca să golească RecyclerView-ul
        // instant, dacă ascultă în momentul în care se face clearSpots().
        default void onSpotsCleared() {}
    }

    private final List<Spot> allSpots = new ArrayList<>();
    private static final int MAX_SPOTS = 150;

    private boolean isConnected = false;
    private String statusMessage = "Disconnected";

    private RepoListener listener;

    private ClusterRepository() {}

    public static synchronized ClusterRepository getInstance() {
        if (instance == null) {
            instance = new ClusterRepository();
        }
        return instance;
    }

    public void setListener(RepoListener listener) {
        this.listener = listener;
        // La înregistrare, trimitem starea curentă ptr a sincroniza UI-ul imediat
        if (listener != null) {
            listener.onStatusChanged(isConnected, statusMessage);
        }
    }

    /**
     * Înlătură un ascultător specific ptr a preveni memory leaks.
     * Verifică dacă instanța care cere ștergerea este cea activă, ptr a nu
     * șterge din greșeală un listener nou înregistrat în timpul rotației.
     */
    public void removeListener(RepoListener callerListener) {
        if (this.listener == callerListener) {
            this.listener = null;
        }
    }

    public synchronized void addSpot(Spot spot) {
        // Evităm duplicatele exacte (Frecvență + Callsign) sosite în rafale
        if (!allSpots.isEmpty()) {
            Spot last = allSpots.get(0);
            if (last.dxCall.equals(spot.dxCall) && Math.abs(last.frequency - spot.frequency) < 0.0001) {
                return;
            }
        }

        allSpots.add(0, spot);
        if (allSpots.size() > MAX_SPOTS) {
            allSpots.remove(allSpots.size() - 1);
        }

        if (listener != null) {
            listener.onNewSpot(spot);
        }
    }

    public synchronized List<Spot> getAllSpots() {
        return new ArrayList<>(allSpots);
    }

    public synchronized void clearSpots() {
        allSpots.clear();
        if (listener != null) {
            listener.onSpotsCleared();
        }
    }

    public synchronized void updateStatus(boolean connected, String message) {
        this.isConnected = connected;
        this.statusMessage = message;
        if (listener != null) {
            listener.onStatusChanged(connected, message);
        }
    }

    public boolean isConnected() {
        return isConnected;
    }

    public String getStatusMessage() {
        return statusMessage;
    }
}