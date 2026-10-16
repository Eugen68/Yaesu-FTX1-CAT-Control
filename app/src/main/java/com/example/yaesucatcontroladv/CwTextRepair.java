package com.example.yaesucatcontroladv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Repararea textului decodat de modelul CW (CTC), la nivel de text - fara dependente Android,
 * ca sa poata fi testata pe JVM. Modelul prezice el insusi caracterul "spatiu" si greseste in
 * ambele sensuri cand manipularea e neregulata:
 * - "uita" spatiul intre cuvinte: CQDEF6 (trebuia CQ DE F6...)
 * - pune spatii in plus intre literele unui indicativ: F6 BI I (trebuia F6BII)
 * Plus caractere parazite izolate, care strica una din repetarile indicativului: PF6BII.
 *
 * Pasi:
 * 1. Segmentare Viterbi peste TOT textul (nu doar in interiorul unui cuvant): un bloc poate
 *    traversa spatiile modelului doar daca rezultatul e un indicativ valid si nicio bucata a
 *    lui nu e deja un cuvant CW cunoscut sau un indicativ de sine statator.
 * 2. Indicativul "dominant" al unei transmisii (cel fata de care cele mai multe variante din
 *    jur sunt la o singura editare) e punctat la a doua segmentare ca un indicativ confirmat.
 *    Asa se rezolva ambiguitatile egale: "F6 BI I F6 BII" poate fi F6BII F6BII sau
 *    F6BI IF6BII cu acelasi scor - repetarea decide.
 * 3. Vot intre repetari: un indicativ aflat la o singura editare de un alt indicativ repetat
 *    de cel putin doua ori in apropiere (ex. CQ ... F6BII F6BII PF6BII) e inlocuit cu acela.
 *    Nu se foloseste MASTER.SCP pentru corectie (vezi CallsignDatabase): referinta e doar
 *    textul aceleiasi transmisii, iar un indicativ confirmat in MASTER.SCP nu e niciodata
 *    inlocuit.
 */
final class CwTextRepair {

    private CwTextRepair() {}

    static boolean esteIndicativValid(String text) {
        if (text == null) return false;
        text = text.trim().toUpperCase();
        if (text.length() < 3 || text.length() > 10) return false;
        // Fara alternativa "litera+cifra" ca prefix - combinata cu cifra obligatorie de mai
        // jos, ar cere 2 cifre lipite (ex. "I00P"), ceea ce nu corespunde niciunui format
        // real de indicativ (fie prefixul e din litere, fie incepe cu cifra fixa de tara).
        return text.matches("^(?:[A-Z]{1,2}|[0-9][A-Z])[0-9][A-Z]{1,4}$");
    }

    // Prosemne/abrevieri CW fixe, cunoscute dinainte - baza vocabularului de segmentare.
    private static final Set<String> ANCORE_CUNOSCUTE = new HashSet<>(Arrays.asList(
            "CQ", "DE", "BK", "AR", "KN", "K", "QTH", "QRZ", "POTA", "QRT", "QRX", "CUAGN", "CALL", "DX", "73", "88"));

    // Cuvinte CW uzuale care NU se lipesc niciodata intr-un indicativ (pasul 1). Nu sunt
    // ancore de taiere: o lista lunga de ancore ar rupe gresit blocuri lipite (vezi bug-ul
    // "DX" din OV1CDX), dar ca blocaj la lipire sunt sigure. Fara ele, "R 5NN" sau "TU 5NN"
    // ar deveni indicativele R5NN / TU5NN.
    private static final Set<String> CUVINTE_NELIPIBILE = new HashSet<>(Arrays.asList(
            "R", "RR", "TU", "UR", "5NN", "599", "559", "579", "589", "RST", "ES", "GL", "GM", "GA", "GE",
            "GN", "TNX", "TKS", "FB", "OM", "YL", "OP", "NAME", "RIG", "ANT", "WX", "HR", "PSE", "AGN",
            "QSL", "QSO", "QSB", "QRM", "QRN", "QSY", "QRL", "QRP", "QRO", "FER", "HW", "CPY", "BT", "SK",
            "TEST", "PWR", "NR", "CFM", "SRI", "UP", "DN", "CUL", "BCNU", "DR", "VY", "MNI", "SOTA", "WWFF",
            "IOTA"));

    // Scoruri PE CARACTER (nu fixe pe token!) - un token e in cele din urma o SUMA peste
    // caractere, deci daca am pune scoruri fixe, o ancora scurta (ex. "DX", 2 caractere) ar
    // avea o "densitate" de scor pe caracter mult mai mare decat un indicativ valid de 6
    // caractere - asta a produs bug-ul in care DP taia "OV1CDXOV1CDX" in "OV1C | DX | OV1CDX".
    // Ordinea ramane relevanta doar relativ: confirmat in MASTER.SCP > forma valida > ancora.
    private static final int SCOR_PE_CARACTER_ANCORA = 10;
    private static final int SCOR_PE_CARACTER_INDICATIV_CONFIRMAT = 15;
    private static final int SCOR_PE_CARACTER_INDICATIV_FORMAT = 12;
    // Penalizare FIXA pe bloc - daca nimic nu se recunoaste, DP prefera UN singur bloc
    // necunoscut mare in loc sa il taie in bucati mici.
    private static final int PENALIZARE_NECUNOSCUT = -1000;
    // Cost pe spatiu al modelului eliminat la lipirea unui indicativ - lipirea trebuie sa
    // castige clar, nu doar la egalitate.
    private static final int COST_SPATIU_ELIMINAT = 15;
    private static final int MAX_LUNGIME_INDICATIV = 10;

    // Votul: cate cuvinte in stanga/dreapta se uita dupa repetari ale aceluiasi indicativ.
    private static final int FEREASTRA_VOT_CUVINTE = 8;

    /** Repara textul folosind MASTER.SCP ca semnal de incredere pentru indicative. */
    static String repara(String text) {
        return repara(text, call -> CallsignDatabase.getInstance().isKnown(call));
    }

    static String repara(String text, Predicate<String> indicativCunoscut) {
        if (text == null || text.trim().isEmpty()) return text;
        String curat = text.trim().toUpperCase();
        List<String> cuvinte = segmenteaza(curat, indicativCunoscut);
        Set<String> dominante = indicativeDominante(cuvinte);
        if (!dominante.isEmpty()) {
            cuvinte = segmenteaza(curat, call -> indicativCunoscut.test(call) || dominante.contains(call));
        }
        voteazaIndicative(cuvinte, indicativCunoscut);
        return String.join(" ", cuvinte);
    }

    private static int scorFragment(String fragment, Predicate<String> indicativCunoscut) {
        if (ANCORE_CUNOSCUTE.contains(fragment)) return fragment.length() * SCOR_PE_CARACTER_ANCORA;
        if (esteIndicativValid(fragment)) {
            int rata = indicativCunoscut.test(fragment)
                    ? SCOR_PE_CARACTER_INDICATIV_CONFIRMAT : SCOR_PE_CARACTER_INDICATIV_FORMAT;
            return fragment.length() * rata;
        }
        return PENALIZARE_NECUNOSCUT;
    }

    /**
     * Segmentare Viterbi peste toate caracterele textului (fara spatii), cu doua feluri de
     * blocuri: in interiorul unui cuvant al modelului (ca inainte: ancora, indicativ sau
     * necunoscut) si peste spatiile modelului (doar indicativ valid, din bucati nerecunoscute).
     * Un bloc necunoscut nu traverseaza niciodata un spatiu, deci cuvintele normale raman
     * despartite exact ca le-a dat modelul.
     */
    private static List<String> segmenteaza(String text, Predicate<String> indicativCunoscut) {
        String[] cuvinte = text.split(" +");
        StringBuilder sb = new StringBuilder();
        List<Integer> idCuvant = new ArrayList<>();
        List<Integer> startCuvant = new ArrayList<>();
        for (int w = 0; w < cuvinte.length; w++) {
            for (int k = 0; k < cuvinte[w].length(); k++) {
                idCuvant.add(w);
                startCuvant.add(sb.length() - k);
                sb.append(cuvinte[w].charAt(k));
            }
        }
        String s = sb.toString();
        int n = s.length();
        // Un cuvant al modelului scurt sau deja recunoscut nu se taie IN INTERIOR (se poate
        // lipi in continuare de vecini, daca nu e recunoscut) - garda din vechea reparare pe
        // cuvant. Fara ea, orice cuvant necunoscut care contine o ancora era taiat in jurul
        // ei: "SK" -> "S K" ("S" -1000 + "K" +10 bate "SK" -1000).
        boolean[] pastreazaIntreg = new boolean[cuvinte.length];
        for (int w = 0; w < cuvinte.length; w++) {
            pastreazaIntreg[w] = cuvinte[w].length() <= 2 || esteRecunoscut(cuvinte[w]);
        }
        int[] dp = new int[n + 1];
        int[] taiere = new int[n + 1];
        Arrays.fill(dp, Integer.MIN_VALUE);
        dp[0] = 0;

        for (int i = 1; i <= n; i++) {
            int inceputCuvant = startCuvant.get(i - 1);
            int cuvantCurent = idCuvant.get(i - 1);
            boolean laSfarsitCuvant = i == inceputCuvant + cuvinte[cuvantCurent].length();
            // Blocuri in interiorul cuvantului curent.
            for (int start = inceputCuvant; start < i; start++) {
                if (dp[start] == Integer.MIN_VALUE) continue;
                if (pastreazaIntreg[cuvantCurent] && (start != inceputCuvant || !laSfarsitCuvant)) continue;
                int scor = dp[start] + scorFragment(s.substring(start, i), indicativCunoscut);
                if (scor > dp[i]) {
                    dp[i] = scor;
                    taiere[i] = start;
                }
            }
            // Blocuri care traverseaza spatii (lipire de indicativ rupt).
            for (int start = Math.max(0, i - MAX_LUNGIME_INDICATIV); start < inceputCuvant; start++) {
                if (dp[start] == Integer.MIN_VALUE) continue;
                String bloc = s.substring(start, i);
                if (!esteIndicativValid(bloc)) continue;
                List<String> bucati = bucatiDupaCuvant(s, idCuvant, start, i);
                if (!bucatiLipibile(bucati)
                        || !cuvinteLipibile(cuvinte, idCuvant.get(start), idCuvant.get(i - 1))) continue;
                int scor = dp[start] + scorFragment(bloc, indicativCunoscut)
                        - COST_SPATIU_ELIMINAT * (bucati.size() - 1);
                if (scor > dp[i]) {
                    dp[i] = scor;
                    taiere[i] = start;
                }
            }
        }

        LinkedList<String> rezultat = new LinkedList<>();
        int pos = n;
        while (pos > 0) {
            int start = taiere[pos];
            rezultat.addFirst(s.substring(start, pos));
            pos = start;
        }
        return new ArrayList<>(rezultat);
    }

    private static List<String> bucatiDupaCuvant(String s, List<Integer> idCuvant, int start, int end) {
        List<String> bucati = new ArrayList<>();
        int inceput = start;
        for (int k = start + 1; k <= end; k++) {
            if (k == end || !idCuvant.get(k).equals(idCuvant.get(k - 1))) {
                bucati.add(s.substring(inceput, k));
                inceput = k;
            }
        }
        return bucati;
    }

    /** O bucata deja recunoscuta (cuvant CW, indicativ intreg) nu se lipeste de vecini. */
    private static boolean bucatiLipibile(List<String> bucati) {
        for (String bucata : bucati) {
            if (esteRecunoscut(bucata)) return false;
        }
        return true;
    }

    /**
     * Nici cuvintele intregi ale modelului atinse de lipire nu pot fi recunoscute - altfel
     * "TU 5NN" s-ar putea rupe in T + U5N + N, cu bucati care luate separat nu par cunoscute.
     */
    private static boolean cuvinteLipibile(String[] cuvinte, int primul, int ultimul) {
        for (int w = primul; w <= ultimul; w++) {
            if (esteRecunoscut(cuvinte[w])) return false;
        }
        return true;
    }

    private static boolean esteRecunoscut(String cuvant) {
        return ANCORE_CUNOSCUTE.contains(cuvant) || CUVINTE_NELIPIBILE.contains(cuvant)
                || esteIndicativValid(cuvant);
    }

    /**
     * Indicativele care, in fereastra lor, au strict mai multe variante la cel mult o editare
     * (inclusiv ele insele) decat oricare vecin al lor la o editare - centrul unui grup de
     * repetari stricate diferit. Doua indicative diferite cate o data nu dau niciun dominant.
     */
    private static Set<String> indicativeDominante(List<String> cuvinte) {
        Set<String> dominante = new HashSet<>();
        for (int i = 0; i < cuvinte.size(); i++) {
            String x = cuvinte.get(i);
            if (!esteIndicativValid(x)) continue;
            List<String> fereastra = indicativeInFereastra(cuvinte, i);
            int suportX = suport(x, fereastra);
            if (suportX < 2) continue;
            boolean maxim = true;
            for (String y : fereastra) {
                if (!y.equals(x) && distantaEditare(x, y) == 1 && suport(y, fereastra) >= suportX) {
                    maxim = false;
                    break;
                }
            }
            if (maxim) dominante.add(x);
        }
        return dominante;
    }

    private static List<String> indicativeInFereastra(List<String> cuvinte, int i) {
        List<String> rezultat = new ArrayList<>();
        int de = Math.max(0, i - FEREASTRA_VOT_CUVINTE);
        int pana = Math.min(cuvinte.size() - 1, i + FEREASTRA_VOT_CUVINTE);
        for (int j = de; j <= pana; j++) {
            if (esteIndicativValid(cuvinte.get(j))) rezultat.add(cuvinte.get(j));
        }
        return rezultat;
    }

    private static int suport(String x, List<String> fereastra) {
        int n = 0;
        for (String y : fereastra) {
            if (distantaEditare(x, y) <= 1) n++;
        }
        return n;
    }

    /**
     * Inlocuieste un indicativ aflat la o singura editare (litera in plus, in minus sau
     * schimbata) de un indicativ repetat de cel putin doua ori in fereastra din jur, si mai
     * des decat el. Un indicativ confirmat in MASTER.SCP nu se inlocuieste.
     */
    private static void voteazaIndicative(List<String> cuvinte, Predicate<String> indicativCunoscut) {
        List<String> original = new ArrayList<>(cuvinte);
        for (int i = 0; i < original.size(); i++) {
            String y = original.get(i);
            if (!esteIndicativValid(y) || indicativCunoscut.test(y)) continue;

            Map<String, Integer> numar = new HashMap<>();
            for (String c : indicativeInFereastra(original, i)) numar.merge(c, 1, Integer::sum);

            String ales = null;
            int maxim = Math.max(1, numar.getOrDefault(y, 0));
            boolean egalitate = false;
            for (Map.Entry<String, Integer> e : numar.entrySet()) {
                String x = e.getKey();
                int cnt = e.getValue();
                if (x.equals(y) || cnt < 2 || distantaEditare(x, y) != 1) continue;
                if (cnt > maxim) {
                    ales = x;
                    maxim = cnt;
                    egalitate = false;
                } else if (cnt == maxim && ales != null) {
                    egalitate = true;
                }
            }
            if (ales != null && !egalitate) cuvinte.set(i, ales);
        }
    }

    static int distantaEditare(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int sub = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(sub, Math.min(prev[j] + 1, cur[j - 1] + 1));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
