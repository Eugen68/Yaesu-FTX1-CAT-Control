package com.example.yaesucatcontroladv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Căutare silențioasă (fără Toast-uri) a locatorului unui indicativ: întâi QRZ.com
 * (dă grid doar cu abonament XML), apoi HamQTH (gratuit). Folosită la completarea
 * automată a grid-ului după ADD QSO și de butonul "Fill missing grids" din LOG STATISTICS.
 * Callback-ul vine pe UI thread (așa livrează ambele helpere).
 */
final class GridLookup {

    interface Callback {
        /** grid = locatorul găsit (majuscule), sau null dacă nu s-a găsit / eroare. */
        void onRezultat(String grid);
    }

    private static final Pattern GRID_VALID = Pattern.compile("^[A-R]{2}[0-9]{2}([A-X]{2})?$");

    private GridLookup() {}

    /** True dacă e configurat măcar un serviciu (QRZ sau HamQTH). */
    static boolean areCont(Context context) {
        return !userQrz(context).isEmpty() && !parolaQrz(context).isEmpty()
                || !userHam(context).isEmpty() && !parolaHam(context).isEmpty();
    }

    /**
     * Indicativul de căutat, sau null dacă locatorul din bază n-ar corespunde
     * locului de emisie: /P /M /QRP se scot (stația e tot acolo, aproximativ);
     * orice alt '/' (YO/DL1ABC, DL1ABC/EA8, /MM) -> null, nu căutăm.
     */
    static String indicativDeCautat(String call) {
        if (call == null) return null;
        String c = call.trim().toUpperCase(Locale.US);
        if (c.isEmpty()) return null;
        if (c.indexOf('/') < 0) return c;
        String baza = null;
        for (String p : c.split("/")) {
            if (p.isEmpty() || p.equals("P") || p.equals("M") || p.equals("QRP")) continue;
            if (baza != null) return null; // două părți semnificative -> portabil în altă zonă
            baza = p;
        }
        if (baza == null || baza.length() < 3) return null;
        return baza;
    }

    static void cauta(Context context, String call, Callback cb) {
        String deCautat = indicativDeCautat(call);
        if (deCautat == null) {
            cb.onRezultat(null);
            return;
        }
        String uQrz = userQrz(context), pQrz = parolaQrz(context);
        if (!uQrz.isEmpty() && !pQrz.isEmpty()) {
            QrzLookupHelper.cautaIndicativ(uQrz, pQrz, deCautat, new QrzLookupHelper.QrzCallback() {
                @Override public void onSucces(QrzLookupHelper.QrzRezultat r) {
                    String g = r == null ? null : valideaza(r.grid);
                    if (g != null) cb.onRezultat(g);
                    else cautaHamQth(context, deCautat, cb);
                }
                @Override public void onEroare(String mesaj) {
                    cautaHamQth(context, deCautat, cb);
                }
            });
        } else {
            cautaHamQth(context, deCautat, cb);
        }
    }

    private static void cautaHamQth(Context context, String call, Callback cb) {
        String u = userHam(context), p = parolaHam(context);
        if (u.isEmpty() || p.isEmpty()) {
            cb.onRezultat(null);
            return;
        }
        HamQthLookupHelper.cautaIndicativ(u, p, call, new HamQthLookupHelper.HamQthCallback() {
            @Override public void onSucces(HamQthLookupHelper.HamQthRezultat r) {
                cb.onRezultat(r == null ? null : valideaza(r.grid));
            }
            @Override public void onEroare(String mesaj) {
                cb.onRezultat(null);
            }
        });
    }

    private static String valideaza(String grid) {
        if (grid == null) return null;
        String g = grid.trim().toUpperCase(Locale.US);
        // Unele baze dau locator de 8 caractere (KN34BK12) -> păstrăm primele 6
        if (g.length() > 6) g = g.substring(0, 6);
        return GRID_VALID.matcher(g).matches() ? g : null;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String userQrz(Context c) {
        return prefs(c).getString(EcranSetariProprii.PREF_QRZ_USER, "");
    }

    private static String parolaQrz(Context c) {
        return SecurePrefsHelper.getPassword(c, EcranSetariProprii.PREF_QRZ_PASS, "");
    }

    private static String userHam(Context c) {
        return prefs(c).getString(EcranSetariProprii.PREF_HAMQTH_USER, "");
    }

    private static String parolaHam(Context c) {
        return SecurePrefsHelper.getPassword(c, EcranSetariProprii.PREF_HAMQTH_PASS, "");
    }
}
