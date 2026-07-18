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

    // Porturi separate
    private UsbSerialPort catPort;
    private UsbSerialPort dataPort;

    // ✅ FLAG-uri separate pentru fiecare port
    private boolean ascultareCatActiva = false;
    private boolean ascultareDataActiva = false;

    // Interfață pentru CAT
    public interface CatDataListener {
        void onCatCommandReceived(String cmd);
    }
    private CatDataListener catListener;

    // Interfață pentru DATA (opțional, pentru viitor)
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

            // ✅ PORNIM THREAD-URI SEPARATE
            ascultareCatActiva = true;
            ascultareDataActiva = true;

            pornesteThreadAscultareCAT();
            pornesteThreadAscultareDATA();

            interogheazaStareStatie();
        } catch (Exception e) {
            Log.e(TAG, "Eroare deschidere porturi USB", e);
        }
    }

    // ✅ THREAD SEPARAT PENTRU CAT
    private void pornesteThreadAscultareCAT() {
        new Thread(() -> {
            byte[] catBuffer = new byte[1024];  // ✅ Buffer dedicat CAT
            StringBuilder catSb = new StringBuilder();  // ✅ StringBuilder dedicat CAT

            while (ascultareCatActiva) {
                try {
                    if (catPort != null && catPort.isOpen()) {
                        int len = catPort.read(catBuffer, 50);
                        if (len > 0) {
                            String data = new String(catBuffer, 0, len, StandardCharsets.US_ASCII)
                                    .replaceAll("[\\s\\n\\r]", "");
                            catSb.append(data);
                        }
                    }

                    int idx;
                    while ((idx = catSb.indexOf(";")) != -1) {
                        String cmd = catSb.substring(0, idx);
                        catSb.delete(0, idx + 1);
                        if (catListener != null) {
                            catListener.onCatCommandReceived(cmd);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Err in CAT read loop", e);
                }
                try { Thread.sleep(20); } catch (Exception e) {}
            }
        }, "CAT-Reader-Thread").start();
    }

    // ✅ THREAD SEPARAT PENTRU DATA
    private void pornesteThreadAscultareDATA() {
        new Thread(() -> {
            byte[] dataBuffer = new byte[1024];  // ✅ Buffer dedicat DATA

            while (ascultareDataActiva) {
                try {
                    if (dataPort != null && dataPort.isOpen()) {
                        int len = dataPort.read(dataBuffer, 50);
                        if (len > 0 && dataListener != null) {
                            // Pentru moment nu procesăm datele DATA (FSK keying e manual)
                            // Dar avem infrastructura pregătită pentru viitor
                            dataListener.onDataReceived(dataBuffer, len);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Err in DATA read loop", e);
                }
                try { Thread.sleep(20); } catch (Exception e) {}
            }
        }, "DATA-Reader-Thread").start();
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
                trimiteComandaCAT("SS04;");
                Thread.sleep(50);
                trimiteComandaCAT("KS;");
            } catch (Exception e) {
                Log.e(TAG, "Error in initial query", e);
            }
        }).start();
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

    public void opresteToat() {
        // ✅ Oprim ambele thread-uri
        ascultareCatActiva = false;
        ascultareDataActiva = false;

        try {
            if (catPort != null) catPort.close();
            if (dataPort != null) dataPort.close();
        } catch (Exception e) {
            Log.e(TAG, "Err closing ports", e);
        }
    }
}