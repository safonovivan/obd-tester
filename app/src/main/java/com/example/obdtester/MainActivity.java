package com.example.obdtester;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private TextView tvSpeed, tvCoolant, tvGear, tvSteering, tvDoor, tvTransTemp;
    private IBinder bcmService;
    private boolean isServiceConnected = false;
    private final String CLIENT_NAME = "OBDTester-" + java.util.UUID.randomUUID().toString();

    // --- Переменные для логирования ---
    private File logFile;
    private File logDir;
    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final long MAX_LOG_SIZE = 5 * 1024 * 1024; // 5MB

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // --- Настройка папки для логов ---
        logDir = getExternalFilesDir(null);

        // --- Настройка UI (Дашборд) ---
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setBackgroundColor(Color.BLACK);
        mainLayout.setPadding(40, 20, 40, 20);
        mainLayout.setGravity(Gravity.CENTER);

        tvSpeed = new TextView(this);
        tvSpeed.setTextColor(Color.WHITE);
        tvSpeed.setTextSize(80);
        tvSpeed.setGravity(Gravity.CENTER);
        tvSpeed.setText("0 км/ч");

        LinearLayout middleLayout = new LinearLayout(this);
        middleLayout.setOrientation(LinearLayout.HORIZONTAL);
        middleLayout.setGravity(Gravity.CENTER);
        middleLayout.setPadding(0, 30, 0, 30);

        tvGear = new TextView(this);
        tvGear.setTextColor(Color.GREEN);
        tvGear.setTextSize(50);
        tvGear.setPadding(0, 0, 50, 0);
        tvGear.setText("P");

        tvCoolant = new TextView(this);
        tvCoolant.setTextColor(Color.WHITE);
        tvCoolant.setTextSize(50);
        tvCoolant.setText("ОЖ: -- °C");

        middleLayout.addView(tvGear);
        middleLayout.addView(tvCoolant);

        LinearLayout bottomLayout = new LinearLayout(this);
        bottomLayout.setOrientation(LinearLayout.HORIZONTAL);
        bottomLayout.setGravity(Gravity.CENTER);

        tvDoor = new TextView(this);
        tvDoor.setTextColor(Color.LTGRAY);
        tvDoor.setTextSize(30);
        tvDoor.setPadding(0, 0, 50, 0);
        tvDoor.setText("Двери: Закрыты");

        tvTransTemp = new TextView(this);
        tvTransTemp.setTextColor(Color.LTGRAY);
        tvTransTemp.setTextSize(30);
        tvTransTemp.setText("АКПП: -- °C");

        bottomLayout.addView(tvDoor);
        bottomLayout.addView(tvTransTemp);

        mainLayout.addView(tvSpeed);
        mainLayout.addView(middleLayout);
        mainLayout.addView(bottomLayout);

        setContentView(mainLayout);

        // Подключаемся к службе BCM
        bindToBcmService();
    }

    private void bindToBcmService() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                "com.adayo.midware.bcmservice",
                "com.adayo.midware.bcmservice.Controller.BcmService"
        ));

        try {
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            tvSpeed.setText("Ошибка связи");
            writeToLog("ОШИБКА bindService: " + e.toString());
        }
    }

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            bcmService = service;
            try {
                if (!"com.adayo.module.bcmproxy.IBcmServiceAIDL".equals(service.getInterfaceDescriptor())) return;
            } catch (RemoteException e) {
                return;
            }

            if (subscribeToBcm()) {
                isServiceConnected = true;
                writeToLog("=== ПОДПИСКА НА BCM ОФОРМЛЕНА ===");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bcmService = null;
            isServiceConnected = false;
            writeToLog("=== BCM СЛУЖБА ОТКЛЮЧЕНА ===");
        }
    };

    // ПРИЕМНИК ДАННЫХ
    private final IBinder bcmCallback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == 1) {
                data.enforceInterface("com.adayo.module.bcmproxy.IBcmClientCallBack");
                String signalName = data.readString();
                final int signalValue = data.readInt();

                // 1. ПИШЕМ В ЛОГ
                String time = timeFormat.format(new Date());
                writeToLog(String.format(Locale.US, "[%s] СОБЫТИЕ: %s = %d", time, signalName, signalValue));

                // 2. ОБНОВЛЯЕМ ЭКРАН
                runOnUiThread(new Runnable() {
                    public void run() {
                        updateUI(signalName, signalValue);
                    }
                });

                if (reply != null) reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    private void updateUI(String signalName, int value) {
        if (signalName == null) return;

        switch (signalName) {
            case "VCU_VehSpd":
                tvSpeed.setText(String.format(Locale.US, "%d км/ч", value));
                break;
            case "BCM_Coolant_R":
                tvCoolant.setText(String.format(Locale.US, "ОЖ: %d °C", value));
                break;
            case "BCM_TRANS_OIL_TEMP_R":
                tvTransTemp.setText(String.format(Locale.US, "АКПП: %d °C", value));
                break;
            case "BCM_VCU_GearSts_R":
                String gearText = "?";
                if (value == 0) gearText = "P";
                else if (value == 5) gearText = "D";
                else if (value == 6) gearText = "N";
                else if (value == 7) gearText = "R";
                tvGear.setText(gearText);
                break;
            case "BCM_DriverDoorSts":
                tvDoor.setText(value == 1 ? "Двери: ОТКРЫТЫ" : "Двери: Закрыты");
                if (value == 1) tvDoor.setTextColor(Color.RED);
                else tvDoor.setTextColor(Color.LTGRAY);
                break;
        }
    }

    private boolean subscribeToBcm() {
        if (bcmService == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.adayo.module.bcmproxy.IBcmServiceAIDL");
            data.writeString(CLIENT_NAME);
            data.writeStrongBinder(bcmCallback);

            boolean transactSuccess = bcmService.transact(3, data, reply, 0);
            if (transactSuccess) {
                reply.readException();
                return reply.readInt() != 0;
            }
        } catch (Exception e) {
            writeToLog("Ошибка подписки: " + e.toString());
        } finally {
            data.recycle();
            reply.recycle();
        }
        return false;
    }

    // --- ЛОГИКА ЛОГОВ (Наш "Черный ящик") ---
    private File getNextLogFile() {
        if (logDir == null) return null;
        int maxIndex = 0;
        Pattern pattern = Pattern.compile("carlog(\\d+)\\.txt");
        File[] files = logDir.listFiles();
        if (files != null) {
            for (File f : files) {
                Matcher matcher = pattern.matcher(f.getName());
                if (matcher.matches()) {
                    int index = Integer.parseInt(matcher.group(1));
                    if (index > maxIndex) maxIndex = index;
                }
            }
        }
        if (maxIndex == 0) return new File(logDir, "carlog1.txt");
        File lastFile = new File(logDir, "carlog" + maxIndex + ".txt");
        if (lastFile.length() < MAX_LOG_SIZE) {
            return lastFile;
        } else {
            return new File(logDir, "carlog" + (maxIndex + 1) + ".txt");
        }
    }

    private void writeToLog(String message) {
        if (logDir == null) return;
        if (logFile == null || logFile.length() >= MAX_LOG_SIZE) {
            logFile = getNextLogFile();
        }
        if (logFile == null) return;
        try {
            FileWriter writer = new FileWriter(logFile, true);
            writer.append(message).append("\n");
            writer.close();
        } catch (IOException ignored) {}
    }
}