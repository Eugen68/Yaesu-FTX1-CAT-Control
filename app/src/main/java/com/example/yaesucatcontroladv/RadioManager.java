package com.example.yaesucatcontroladv;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class RadioManager {
    private static final String TAG = "RadioManager";
    private static final String ACTION_USB_PERMISSION = "com.example.yaesucatcontroladv.USB_PERMISSION";
    private final Context context;
    private final Handler handlerUi = new Handler(Looper.getMainLooper());

    // Porturi separate
    private UsbSerialPort catPort;
    private UsbSerialPort dataPort;

    private boolean intentionalDisconnect = false;

    // OPTIMIZARE: inlocuim thread-urile proprii cu polling (Thread.sleep(20) intre
    // citiri) cu SerialInputOutputManager din biblioteca usb-serial-for-android:
    // - citire event-driven, onNewData() apelat imediat ce sosesc date (nu la
    //   urmatorul "tick" de 20-70ms) -> latenta mai mica, zero risipa CPU/baterie
    //   cand nu vine nimic;
    // - onRunError() e apelat automat la deconectare/eroare de citire, in loc sa
    //   trebuiasca sa prindem noi exceptia dintr-un read() manual;
    // - stop() gestioneaza intern oprirea sigura a thread-ului de citire, eliminand
    //   cursa veche dintre "citire in curs" si "port inchis" (vezi opresteToat()).
    private SerialInputOutputManager catIoManager;
    private SerialInputOutputManager dataIoManager;

    // Buffer de asamblare comenzi CAT. Comenzile pot sosi fragmentate pe mai multe
    // apeluri onNewData (nu vine mereu o comanda "intreaga" dintr-o citire), deci
    // bufferul trebuie sa persiste intre apeluri -> field, nu variabila locala.
    // Ii facem reset explicit la fiecare pornire de conexiune (porneteIoManagerCAT).
    private final StringBuilder catSb = new StringBuilder();

    // Interfata pentru CAT
    public interface CatDataListener {
        void onCatCommandReceived(String cmd);
        void onConnectionStateChanged(boolean connected);
    }
    private CatDataListener catListener;

    // Interfata pentru DATA (optional, pentru viitor)
    public interface DataListener {
        void onDataReceived(byte[] data, int length);
    }
    private DataListener dataListener;

    public RadioManager(Context context) {
        this.context = context;
    }

    public void setCatDataListener(CatDataListener listener) {
        this.catListener = listener;
    }

    public void setDataListener(DataListener listener) {
        this.dataListener = listener;
    }

    public void conecteazaStatie() {
        if (isConectat()) {
            Log.d(TAG, "Stația este deja conectată.");
            return;
        }
        try {
            UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);

            if (drivers.isEmpty()) {
                Toast.makeText(context, "No USB device detected!", Toast.LENGTH_SHORT).show();
                return;
            }

            UsbSerialDriver driver = drivers.get(0);
            UsbDevice device = driver.getDevice();

            if (!manager.hasPermission(device)) {
                // FIX Android 12+: UsbManager needs FLAG_MUTABLE to add EXTRA_PERMISSION_GRANTED
                int flags = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S ?
                        PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_IMMUTABLE;
                
                Intent intent = new Intent(ACTION_USB_PERMISSION);
                intent.setPackage(context.getPackageName()); // FIX: Android 14+ needs explicit intent for MUTABLE flags
                
                PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent, flags);
                manager.requestPermission(device, pi);
                return;
            }

            if (driver.getPorts().isEmpty()) return;

            catPort = driver.getPorts().get(0);
            if (driver.getPorts().size() > 1) {
                dataPort = driver.getPorts().get(1);
            }

            deschidePorturi(manager, device);
        } catch (Exception e) {
            Log.e(TAG, "Exception in conecteazaStatie", e);
            String errorMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
            Toast.makeText(context, "Connection error: [" + errorMsg + "]", Toast.LENGTH_LONG).show();
        }
    }

    private void deschidePorturi(UsbManager manager, UsbDevice device) {
        try {
            intentionalDisconnect = false;
            UsbDeviceConnection connection = manager.openDevice(device);
            if (connection == null) {
                Log.e(TAG, "UsbManager.openDevice() returned NULL - checking permissions...");
                Toast.makeText(context, "Unable to open USB device. Please reconnect.", Toast.LENGTH_LONG).show();
                return;
            }

            // 1. Configurare Port CAT
            catPort.open(connection);
            catPort.setParameters(38400, 8, 1, UsbSerialPort.PARITY_NONE);

            // 2. Configurare Port DATA
            if (dataPort != null) {
                dataPort.open(connection);
                dataPort.setParameters(4800, 8, 1, UsbSerialPort.PARITY_NONE);
                dataPort.setRTS(false);
                dataPort.setDTR(false);
            }

            try { Thread.sleep(200); } catch (Exception e) {}

            Toast.makeText(context, "Connected to Yaesu!", Toast.LENGTH_SHORT).show();

            // CORECTIE (regresie descoperita dupa test pe hardware real): FTX1 expune CAT
            // si DATA ca doua UART-uri ale ACELUIASI chip fizic (Silicon Labs Dual CP210x),
            // multiplexate pe o singura conexiune USB. Pornind DOI SerialInputOutputManager
            // simultan (CAT + DATA), fiecare cu thread-uri proprii de citire+scriere (asa
            // cum functioneaza biblioteca din v3.9+), am creat contentie pe conexiunea USB
            // fizica partajata - portul CAT nu mai primea date de la statie.
            // In plus, dataIoManager nu are NICIUN rol functional acum: setDataListener()
            // nu e apelat nicaieri in aplicatie - portul DATA e folosit doar pentru
            // setDTR()/setRTS() (keying FSK), nu pentru citire. Deci il eliminam complet:
            // portul ramane deschis (setDTR/setRTS tot functioneaza), dar nu mai lansam
            // un thread de citire inutil care concura cu cel de CAT pentru aceeasi conexiune.
            porneteIoManagerCAT();
            if (catListener != null) catListener.onConnectionStateChanged(true);

            interogheazaStareStatie();
        } catch (Exception e) {
            Log.e(TAG, "Eroare deschidere porturi USB", e);
        }
    }

    // OPTIMIZARE: inlocuieste vechiul pornesteThreadAscultareCAT() (thread manual,
    // buffer+StringBuilder locale, bucla while cu Thread.sleep(20)) cu un
    // SerialInputOutputManager event-driven. Parsarea comenzilor terminate cu ';'
    // ramane identica ca logica, doar mecanismul de livrare a datelor s-a schimbat.
    private void porneteIoManagerCAT() {
        catSb.setLength(0);

        SerialInputOutputManager.Listener listener = new SerialInputOutputManager.Listener() {
            @Override
            public void onNewData(byte[] data) {
                String chunk = new String(data, StandardCharsets.US_ASCII)
                        .replaceAll("[\\s\\n\\r]", "");
                catSb.append(chunk);

                int idx;
                while ((idx = catSb.indexOf(";")) != -1) {
                    String cmd = catSb.substring(0, idx);
                    catSb.delete(0, idx + 1);
                    if (catListener != null) {
                        catListener.onCatCommandReceived(cmd);
                    }
                }
            }

            @Override
            public void onRunError(Exception e) {
                // Apelat automat de biblioteca la deconectare/eroare de citire -
                // inainte trebuia sa prindem noi asta manual dintr-un read().
                if (intentionalDisconnect) {
                    Log.d(TAG, "CAT IoManager oprit controlat (disconnect/exit)");
                    return;
                }

                Log.e(TAG, "CAT IoManager oprit (deconectare/eroare)", e);
                if (catListener != null) catListener.onConnectionStateChanged(false);
                handlerUi.post(() -> Toast.makeText(context,
                        "Conexiune CAT pierduta: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        };

        catIoManager = new SerialInputOutputManager(catPort, listener);
        catIoManager.start();
    }

    // NEFOLOSITA MOMENTAN (vezi comentariul din deschidePorturi): pornirea acestui
    // manager pe portul DATA a cauzat contentie pe conexiunea USB fizica partajata cu
    // portul CAT (Dual CP210x), stricand fiabilitatea citirii CAT. O las definita, pentru
    // eventualitatea in care se va dori procesarea reala a datelor DATA in viitor - dar
    // atunci ar trebui gasita o solutie care sa serializeze accesul intre cele doua
    // porturi (ex. un singur thread care le citeste alternativ), nu doi manageri
    // independenti concurenti ca acum.
    private void porneteIoManagerDATA() {
        SerialInputOutputManager.Listener listener = new SerialInputOutputManager.Listener() {
            @Override
            public void onNewData(byte[] data) {
                if (dataListener != null) {
                    dataListener.onDataReceived(data, data.length);
                }
            }

            @Override
            public void onRunError(Exception e) {
                Log.e(TAG, "DATA IoManager oprit (deconectare/eroare)", e);
            }
        };

        dataIoManager = new SerialInputOutputManager(dataPort, listener);
        dataIoManager.start();
    }

    public void interogheazaStareStatie() {
        new Thread(() -> {
            try {
                trimiteComandaCAT("AI1;");
                Thread.sleep(50);
                trimiteComandaCAT("MD0;");
                Thread.sleep(50);
                trimiteComandaCAT("BI;");
                Thread.sleep(50);
                trimiteComandaCAT("CO02;");
                Thread.sleep(50);
                trimiteComandaCAT("SH0;");
                Thread.sleep(50);
                trimiteComandaCAT("RL0;");
                Thread.sleep(50);
                trimiteComandaCAT("PC;");
                Thread.sleep(50);
                trimiteComandaCAT("RM8;"); // Interogăm tensiunea pentru a detecta 6W vs 10W
                Thread.sleep(50);
                trimiteComandaCAT("SS04;");
                Thread.sleep(50);
                trimiteComandaCAT("KS;");
            } catch (Exception e) {
                Log.e(TAG, "Error in initial query", e);
            }
        }).start();

        // Lansăm polling-ul periodic pentru tensiune (RM8) la fiecare 5 secunde
        pornestePollingTensiune();
    }

    private final Runnable runnablePollingRM8 = new Runnable() {
        @Override
        public void run() {
            if (catPort != null && catPort.isOpen()) {
                trimiteComandaCAT("RM8;");
                handlerUi.postDelayed(this, 5000); // Repetăm la 5 secunde
            }
        }
    };

    private void pornestePollingTensiune() {
        handlerUi.removeCallbacks(runnablePollingRM8);
        handlerUi.postDelayed(runnablePollingRM8, 5000);
    }

    public void trimiteComandaCAT(String cmd) {
        if (catPort == null || !catPort.isOpen()) return;
        if (!cmd.endsWith(";")) cmd += ";";
        try {
            catPort.write(cmd.getBytes(StandardCharsets.US_ASCII), 500);
            if (catListener != null) {
                catListener.onCatCommandReceived("TX -> " + cmd);
            }
        } catch (IOException e) {
            Log.e(TAG, "Err writing CAT", e);
        }
    }

    public UsbSerialPort getDataPort() {
        return dataPort;
    }

    public boolean isConectat() {
        return catPort != null && catPort.isOpen();
    }

    // OPTIMIZARE: oprim intai IoManager-ele - stop() e SINCRON cu privire la starea
    // interna (biblioteca gestioneaza tranzitia RUNNING -> STOPPING -> STOPPED si
    // orice citire aflata in desfasurare) - si ABIA DUPA aceea inchidem porturile.
    // Elimina cursa veche (thread de citire activ + port inchis simultan din alt
    // thread), care putea produce IOException/crash la deconectare rapida.
    public void opresteToat() {
        intentionalDisconnect = true;
        if (catListener != null) catListener.onConnectionStateChanged(false);
        if (catIoManager != null) {
            catIoManager.stop();
            catIoManager = null;
        }
        if (dataIoManager != null) {
            dataIoManager.stop();
            dataIoManager = null;
        }

        try {
            if (catPort != null) {
                catPort.close();
            }
        } catch (IOException e) {
            if (!"Already closed".equals(e.getMessage())) {
                Log.e(TAG, "Err closing catPort", e);
            }
        } catch (Exception e) {
            Log.e(TAG, "Err closing catPort (generic)", e);
        } finally {
            catPort = null;
        }

        try {
            if (dataPort != null) {
                dataPort.close();
            }
        } catch (IOException e) {
            if (!"Already closed".equals(e.getMessage())) {
                Log.e(TAG, "Err closing dataPort", e);
            }
        } catch (Exception e) {
            Log.e(TAG, "Err closing dataPort (generic)", e);
        } finally {
            dataPort = null;
        }
    }
}
