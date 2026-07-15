package com.example.yaesucatcontroladv;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class RadioManager {
    private static final String TAG = "RadioManager";
    private static final String ACTION_USB_PERMISSION = "com.example.yaesucatcontroladv.USB_PERMISSION";

    private final Context context;
    private UsbSerialPort catPort;
    private UsbSerialPort dataPort;
    private boolean ascultareActiva = false;

    // Interfață pentru a trimite comenzile CAT primite înapoi către ecrane pentru a actualiza butoanele
    public interface CatDataListener {
        void onCatCommandReceived(String cmd);
    }
    private CatDataListener listener;

    public RadioManager(Context context) {
        this.context = context;
    }

    public void setCatDataListener(CatDataListener listener) {
        this.listener = listener;
    }

    public void conecteazaStatie() {
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
                int flags = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M ?
                        PendingIntent.FLAG_IMMUTABLE : 0;
                PendingIntent pi = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), flags);
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
            Toast.makeText(context, "Connection error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void deschidePorturi(UsbManager manager, UsbDevice device) {
        try {
            UsbDeviceConnection connection = manager.openDevice(device);
            if (connection == null) return;

            // 1. Configurare Port principal (CAT - Enhanced)
            catPort.open(connection);
            catPort.setParameters(38400, 8, 1, UsbSerialPort.PARITY_NONE);

            // 2. Configurare Port secundar (DATA / FSK Keying - Standard)
            if (dataPort != null) {
                dataPort.open(connection);
                dataPort.setParameters(4800, 8, 1, UsbSerialPort.PARITY_NONE);

                // REPARAT HARDWARE: Ambele linii pe false la pornire pentru siguranță totală (Stația rămâne în RX)
                dataPort.setRTS(false);
                dataPort.setDTR(false);
            }

            // --- REPARAT: Pauză critică pentru stabilizarea microprocesorului Yaesu ---
            try { Thread.sleep(200); } catch (Exception e) {}

            Toast.makeText(context, "Connected to Yaesu!", Toast.LENGTH_SHORT).show();

            ascultareActiva = true;
            pornesteThreadAscultare();

            // --- FLUX DE INTEROGARE MASIV LA PORNIRE (Sincronizare 100%) ---
            interogheazaStareStatie();

        } catch (Exception e) {
            Log.e(TAG, "Eroare deschidere porturi USB", e);
        }
    }

    public void interogheazaStareStatie() {
        new Thread(() -> {
            try {
                trimiteComandaCAT("AI1;"); // Auto-Information pornit
                Thread.sleep(50);
                trimiteComandaCAT("MD0;"); // Interogare Mod de lucru (LSB/USB/CW) - Corectat MD0;
                Thread.sleep(50);
                trimiteComandaCAT("BI;");  // Interogare CW Break-In
                Thread.sleep(50);
                trimiteComandaCAT("CO02;"); // Interogare APF
                Thread.sleep(50);
                trimiteComandaCAT("SH0;");  // Interogare WIDTH filtru (Corectat SH0; pentru Main)
                Thread.sleep(50);
                trimiteComandaCAT("RL0;");   // Interogare DNR (Yaesu cere RL0; pentru interogare)
                Thread.sleep(50);
                trimiteComandaCAT("PC;");   // Interogare Putere de emisie
                Thread.sleep(50);
                trimiteComandaCAT("SS04;"); // Interogare Spectrum Scope
                Thread.sleep(50);
                trimiteComandaCAT("KS;");   // Interogare Viteza CW
            } catch (Exception e) {
                Log.e(TAG, "Error in initial query", e);
            }
        }).start();
    }

    private void pornesteThreadAscultare() {
        new Thread(() -> {
            byte[] buffer = new byte[1024];
            StringBuilder sb = new StringBuilder();

            while (ascultareActiva) {
                try {
                    if (catPort != null && catPort.isOpen()) {
                        int len = catPort.read(buffer, 50);
                        if (len > 0) {
                            sb.append(new String(buffer, 0, len, StandardCharsets.US_ASCII).replaceAll("[\\s\\n\\r]", ""));
                        }
                    }
                    if (dataPort != null && dataPort.isOpen()) {
                        int lenData = dataPort.read(buffer, 50);
                        if (lenData > 0) {
                            sb.append(new String(buffer, 0, lenData, StandardCharsets.US_ASCII).replaceAll("[\\s\\n\\r]", ""));
                        }
                    }

                    int idx;
                    while ((idx = sb.indexOf(";")) != -1) {
                        String cmd = sb.substring(0, idx);
                        sb.delete(0, idx + 1);
                        if (listener != null) {
                            listener.onCatCommandReceived(cmd); // Trimitem comanda direct spre parsare în UI
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Err in read loop", e);
                }
                try { Thread.sleep(20); } catch (Exception e) {}
            }
        }).start();
    }

    public void trimiteComandaCAT(String cmd) {
        if (catPort == null || !catPort.isOpen()) return;
        if (!cmd.endsWith(";")) cmd += ";";
        try {
            catPort.write(cmd.getBytes(StandardCharsets.US_ASCII), 500);
            // Raportăm în log ce am trimis (dacă avem listener)
            if (listener != null) {
                listener.onCatCommandReceived("TX -> " + cmd);
            }
        } catch (IOException e) {
            Log.e(TAG, "Err writing CAT", e);
        }
    }

    public UsbSerialPort getDataPort() {
        return dataPort;
    }

    public void opresteToat() {
        ascultareActiva = false;
        try {
            if (catPort != null) catPort.close();
            if (dataPort != null) dataPort.close();
        } catch (Exception e) {
            Log.e(TAG, "Err closing ports", e);
        }
    }
}
