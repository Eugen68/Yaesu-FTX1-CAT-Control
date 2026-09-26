package com.example.yaesucatcontroladv;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Baza de date SQLite pentru jurnalul de QSO-uri (tab LOGGER).
 * <p>
 * Numele coloanelor urmează convenția standard ADIF 2.1.4
 * (QSO_DATE, TIME_ON, CALL, BAND, MODE, RST_SENT/RCVD, etc.) - acesta
 * este formatul universal de schimb pentru loguri radioamator, folosit
 * de QRZ, LoTW, Log4OM, N1MM și practic orice alt software serios de
 * logging. Alegerea asta face export-ul ADIF (adăugat ulterior)
 * trivial - coloanele deja se mapează 1-la-1 pe câmpurile ADIF.
 */
public class QsoDbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "qso_log.db";
    private static final int DB_VERSION = 1;

    public static final String TABLE_QSO = "qso_log";

    public static final String COL_ID = "id";
    public static final String COL_CALL = "call";
    public static final String COL_QSO_DATE = "qso_date";   // format ADIF: YYYYMMDD (UTC)
    public static final String COL_TIME_ON = "time_on";     // format ADIF: HHMM sau HHMMSS (UTC)
    public static final String COL_BAND = "band";           // ex: "20m", "40m"
    public static final String COL_FREQ = "freq";           // MHz, ex: 14.074000
    public static final String COL_MODE = "mode";
    public static final String COL_RST_SENT = "rst_sent";
    public static final String COL_RST_RCVD = "rst_rcvd";
    public static final String COL_NAME = "name";
    public static final String COL_QTH = "qth";
    public static final String COL_GRIDSQUARE = "gridsquare";
    public static final String COL_COMMENT = "comment";
    public static final String COL_POWER = "power";
    public static final String COL_MY_CALL = "my_call";
    public static final String COL_MY_GRIDSQUARE = "my_gridsquare";

    public QsoDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_QSO + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_CALL + " TEXT NOT NULL, " +
                COL_QSO_DATE + " TEXT NOT NULL, " +
                COL_TIME_ON + " TEXT NOT NULL, " +
                COL_BAND + " TEXT, " +
                COL_FREQ + " TEXT, " +
                COL_MODE + " TEXT, " +
                COL_RST_SENT + " TEXT, " +
                COL_RST_RCVD + " TEXT, " +
                COL_NAME + " TEXT, " +
                COL_QTH + " TEXT, " +
                COL_GRIDSQUARE + " TEXT, " +
                COL_COMMENT + " TEXT, " +
                COL_POWER + " TEXT, " +
                COL_MY_CALL + " TEXT, " +
                COL_MY_GRIDSQUARE + " TEXT" +
                ");");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Prima versiune - fara migrari necesare inca. Cand adaugam
        // coloane noi in viitor, aici punem ALTER TABLE in functie de
        // oldVersion, ca sa nu pierdem datele existente ale utilizatorului.
    }

    /** Reprezentarea unui rand din tabelul de QSO-uri, folosita de UI (EcranLogger). */
    public static class Qso {
        public long id;
        public String call = "";
        public String qsoDate = "";
        public String timeOn = "";
        public String band = "";
        public String freq = "";
        public String mode = "";
        public String rstSent = "";
        public String rstRcvd = "";
        public String name = "";
        public String qth = "";
        public String gridsquare = "";
        public String comment = "";
        public String power = "";
        public String myCall = "";
        public String myGridsquare = "";
    }

    public long insereazaQso(Qso qso) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_CALL, qso.call);
        cv.put(COL_QSO_DATE, qso.qsoDate);
        cv.put(COL_TIME_ON, qso.timeOn);
        cv.put(COL_BAND, qso.band);
        cv.put(COL_FREQ, qso.freq);
        cv.put(COL_MODE, qso.mode);
        cv.put(COL_RST_SENT, qso.rstSent);
        cv.put(COL_RST_RCVD, qso.rstRcvd);
        cv.put(COL_NAME, qso.name);
        cv.put(COL_QTH, qso.qth);
        cv.put(COL_GRIDSQUARE, qso.gridsquare);
        cv.put(COL_COMMENT, qso.comment);
        cv.put(COL_POWER, qso.power);
        cv.put(COL_MY_CALL, qso.myCall);
        cv.put(COL_MY_GRIDSQUARE, qso.myGridsquare);
        return db.insert(TABLE_QSO, null, cv);
    }

    /** Actualizează un QSO existent (identificat prin qso.id) cu noile valori din formular. */
    public void actualizeazaQso(Qso qso) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_CALL, qso.call);
        cv.put(COL_QSO_DATE, qso.qsoDate);
        cv.put(COL_TIME_ON, qso.timeOn);
        cv.put(COL_BAND, qso.band);
        cv.put(COL_FREQ, qso.freq);
        cv.put(COL_MODE, qso.mode);
        cv.put(COL_RST_SENT, qso.rstSent);
        cv.put(COL_RST_RCVD, qso.rstRcvd);
        cv.put(COL_NAME, qso.name);
        cv.put(COL_QTH, qso.qth);
        cv.put(COL_GRIDSQUARE, qso.gridsquare);
        cv.put(COL_COMMENT, qso.comment);
        cv.put(COL_POWER, qso.power);
        cv.put(COL_MY_CALL, qso.myCall);
        cv.put(COL_MY_GRIDSQUARE, qso.myGridsquare);
        db.update(TABLE_QSO, cv, COL_ID + "=?", new String[]{String.valueOf(qso.id)});
    }

    public void stergeQso(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_QSO, COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** Completează grid-ul unui QSO, doar dacă e încă gol (nu suprascrie ce a pus utilizatorul). */
    public boolean completeazaGridDacaLipseste(long id, String grid) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_GRIDSQUARE, grid);
        return db.update(TABLE_QSO, cv,
                COL_ID + "=? AND (" + COL_GRIDSQUARE + " IS NULL OR TRIM(" + COL_GRIDSQUARE + ")='')",
                new String[]{String.valueOf(id)}) > 0;
    }

    /** Șterge toate QSO-urile din log. Returnează numărul de rânduri șterse. */
    public int stergeTotLogul() {
        SQLiteDatabase db = getWritableDatabase();
        return db.delete(TABLE_QSO, "1", null);
    }

    /** Returneaza toate QSO-urile, cele mai recente primele (dupa id descrescator). */
    public List<Qso> getToateQsourile() {
        List<Qso> lista = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_QSO, null, null, null, null, null, COL_ID + " DESC");

        if (c != null) {
            while (c.moveToNext()) {
                Qso q = new Qso();
                q.id = c.getLong(c.getColumnIndexOrThrow(COL_ID));
                q.call = c.getString(c.getColumnIndexOrThrow(COL_CALL));
                q.qsoDate = c.getString(c.getColumnIndexOrThrow(COL_QSO_DATE));
                q.timeOn = c.getString(c.getColumnIndexOrThrow(COL_TIME_ON));
                q.band = safeGet(c, COL_BAND);
                q.freq = safeGet(c, COL_FREQ);
                q.mode = safeGet(c, COL_MODE);
                q.rstSent = safeGet(c, COL_RST_SENT);
                q.rstRcvd = safeGet(c, COL_RST_RCVD);
                q.name = safeGet(c, COL_NAME);
                q.qth = safeGet(c, COL_QTH);
                q.gridsquare = safeGet(c, COL_GRIDSQUARE);
                q.comment = safeGet(c, COL_COMMENT);
                q.power = safeGet(c, COL_POWER);
                q.myCall = safeGet(c, COL_MY_CALL);
                q.myGridsquare = safeGet(c, COL_MY_GRIDSQUARE);
                lista.add(q);
            }
            c.close();
        }
        return lista;
    }

    private String safeGet(Cursor c, String col) {
        int idx = c.getColumnIndex(col);
        if (idx < 0) return "";
        String v = c.getString(idx);
        return v != null ? v : "";
    }

    // REPARAT (bug #1): folosește constanta TABLE_QSO în loc de string hard-coded "qsos"
    // care cauza SQLiteException "no such table: qsos" la orice import ADIF.
    // Migrați la db.query() cu try-with-resources pentru închidere automată cursor.
    public boolean existaQso(String call, String qsoDate, String timeOn) {
        SQLiteDatabase db = this.getReadableDatabase();
        try (Cursor cursor = db.query(
                TABLE_QSO,
                new String[]{"COUNT(*)"},
                "call = ? AND qso_date = ? AND time_on = ?",
                new String[]{call, qsoDate, timeOn},
                null, null, null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0) > 0;
            }
            return false;
        }
    }
}