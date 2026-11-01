package com.example.obdtester;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private TextView tvSpeed, tvGear;
    private TextView tvCoolant, tvTransTemp, tvOutTemp, tvVoltage;
    private TextView tvDoorDriver, tvDoorPass, tvDoorRL, tvDoorRR, tvDoorTrunk;
    
    private IBinder bcmService;
    private boolean isServiceConnected = false;
    private final String CLIENT_NAME = "OBDTester-" + java.util.UUID.randomUUID().toString();
    private Handler handler = new Handler(Looper.getMainLooper());

    // --- Переменные для логирования ---
    private File logFile;
    private File logDir;
    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final long MAX_LOG_SIZE = 5 * 1024 * 1024; // 5MB

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logDir = getExternalFilesDir(null);

        // --- ГЛАВНЫЙ КАРКАС ---
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.HORIZONTAL);
        rootLayout.setBackgroundColor(Color.BLACK);
        rootLayout.setPadding(20, 20, 20, 20);

        // === ЛЕВАЯ КОЛОНКА (Скорость и Передача) ===
        LinearLayout leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftCol.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.8f);
        leftCol.setLayoutParams(leftParams);

        tvSpeed = new TextView(this);
        tvSpeed.setTextColor(Color.WHITE);
        tvSpeed.setTextSize(100);
        tvSpeed.setTypeface(null, Typeface.BOLD);
        tvSpeed.setGravity(Gravity.CENTER);
        tvSpeed.setText("0");

        TextView tvSpeedLabel = new TextView(this);
        tvSpeedLabel.setTextColor(Color.GRAY);
        tvSpeedLabel.setTextSize(30);
        tvSpeedLabel.setGravity(Gravity.CENTER);
        tvSpeedLabel.setText("км/ч");

        tvGear = new TextView(this);
        tvGear.setTextColor(Color.GREEN);
        tvGear.setTextSize(80);
        tvGear.setTypeface(null, Typeface.BOLD);
        tvGear.setGravity(Gravity.CENTER);
        tvGear.setPadding(0, 30, 0, 0);
        tvGear.setText("P");

        leftCol.addView(tvSpeed);
        leftCol.addView(tvSpeedLabel);
        leftCol.addView(tvGear);

        // === ЦЕНТРАЛЬНАЯ КОЛОНКА (Температуры, АКБ) ===
        LinearLayout centerCol = new LinearLayout(this);
        centerCol.setOrientation(LinearLayout.VERTICAL);
        centerCol.setGravity(Gravity.CENTER_VERTICAL);
        centerCol.setPadding(10, 0, 10, 0);
        LinearLayout.LayoutParams centerParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.1f);
        centerCol.setLayoutParams(centerParams);

        tvCoolant = createDataRow(centerCol, "ОЖ:", "-- °C", Color.WHITE, 28);
        tvTransTemp = createDataRow(centerCol, "АКПП:", "-- °C", Color.WHITE, 28);
        tvOutTemp = createDataRow(centerCol, "За бортом:", "-- °C", Color.LTGRAY, 28);
        tvVoltage = createDataRow(centerCol, "АКБ:", "-- В", Color.YELLOW, 28);

        // === ПРАВАЯ КОЛОНКА (Двери и Багажник) ===
        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.1f);
        rightCol.setLayoutParams(rightParams);

        tvDoorDriver = createDataRow(rightCol, "Водитель:", "Закрыто", Color.LTGRAY, 26);
        tvDoorPass = createDataRow(rightCol, "Пассажир:", "Закрыто", Color.LTGRAY, 26);
        tvDoorRL = createDataRow(rightCol, "Зад. левая:", "Закрыто", Color.LTGRAY, 26);
        tvDoorRR = createDataRow(rightCol, "Зад. правая:", "Закрыто", Color.LTGRAY, 26);
        tvDoorTrunk = createDataRow(rightCol, "Багажник:", "Закрыто", Color.LTGRAY, 26);

        rootLayout.addView(leftCol);
        rootLayout.addView(centerCol);
        rootLayout.addView(rightCol);
        setContentView(rootLayout);

        // Подключаемся к BCM
        bindToBcmService();

        // Запускаем чтение АКБ через Root
        handler.postDelayed(rootBatteryRunnable, 2000);
    }

    // Вспомогательная функция для строк
    private TextView createDataRow(LinearLayout parent, String label, String defaultValue, int valueColor, int textSize) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 10, 0, 10);

        TextView tvLabel = new TextView(this);
        tvLabel.setTextColor(Color.GRAY);
        tvLabel.setTextSize(textSize);
        tvLabel.setText(label);
        tvLabel.setWidth(220); 

        TextView tvValue = new TextView(this);
        tvValue.setTextColor(valueColor);
        tvValue.setTextSize(textSize);
        tvValue.setTypeface(null, Typeface.BOLD);
        tvValue.setText(defaultValue);

        row.addView(tvLabel);
        row.addView(tvValue);
        parent.addView(row);
        return tvValue;
    }

    // --- ЧТЕНИЕ НАПРЯЖЕНИЯ ЧЕРЕЗ ROOT (SU) ---
    private Runnable rootBatteryRunnable = new Runnable() {
        @Override
        public void run() {
            readBatteryVoltageViaRoot();
            handler.postDelayed(this, 5000); // Раз в 5 секунд
        }
    };

    private void readBatteryVoltageViaRoot() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Выполняем команду под root
                    Process process = Runtime.getRuntime().exec("su -c cat /sys/class/power_supply/battery/voltage_now");
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    String line = reader.readLine();
                    reader.close();
                    process.waitFor();

                    if (line != null) {
                        final long microVolts = Long.parseLong(line.trim());
                        final double volts = microVolts / 1000000.0;
                        
                        if (volts > 5.0 && volts < 30.0) {
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    tvVoltage.setText(String.format(Locale.US, "%.1f В", volts));
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                    // Если не сработало, пробуем USB
                    try {
                        Process p2 = Runtime.getRuntime().exec("su -c cat /sys/class/power_supply/usb/voltage_now");
                        BufferedReader br = new BufferedReader(new InputStreamReader(p2.getInputStream()));
                        String l = br.readLine();
                        br.close();
                        if (l != null) {
                            double v = Long.parseLong(l.trim()) / 1000000.0;
                            if (v > 5.0 && v < 30.0) {
                                final double finalV = v;
                                runOnUiThread(new Runnable() { public void run() { tvVoltage.setText(String.format(Locale.US, "%.1f В", finalV)); } });
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }).start();
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
            writeToLog("ОШИБКА bindService: " + e.toString());
        }
    }

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            bcmService = service;
            try {
                if (!"com.adayo.module.bcmproxy.IBcmServiceAIDL".equals(service.getInterfaceDescriptor())) return;
            } catch (RemoteException e) { return; }

            if (subscribeToBcm()) {
                isServiceConnected = true;
                writeToLog("=== ПОДПИСКА НА BCM ОФОРМЛЕНА ===");
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            bcmService = null;
            isServiceConnected = false;
        }
    };

    private final IBinder bcmCallback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == 1) {
                data.enforceInterface("com.adayo.module.bcmproxy.IBcmClientCallBack");
                String signalName = data.readString();
                final int signalValue = data.readInt();

                String time = timeFormat.format(new Date());
                writeToLog(String.format(Locale.US, "[%s] СОБЫТИЕ: %s = %d", time, signalName, signalValue));

                runOnUiThread(new Runnable() {
                    public void run() { updateUI(signalName, signalValue); }
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
            case "VCU_VehSpd": tvSpeed.setText(String.valueOf(value)); break;
            case "BCM_VCU_GearSts_R":
                String g = "?";
                if (value == 0) g = "P"; else if (value == 5) g = "D"; else if (value == 6) g = "N"; else if (value == 7) g = "R";
                tvGear.setText(g); break;
            case "BCM_Coolant_R":
                if (value > 0 && value <= 120) tvCoolant.setText(String.format(Locale.US, "%d °C", value)); break;
            case "BCM_TRANS_OIL_TEMP_R":
                if (value > 0 && value <= 150) tvTransTemp.setText(String.format(Locale.US, "%d °C", value)); break;
            case "BCM_Ext_Temp_R":
                if (value > -40 && value < 50) tvOutTemp.setText(String.format(Locale.US, "%d °C", value)); break;
            case "BCM_DriverDoorSts": updateDoorState(tvDoorDriver, value); break;
            case "BCM_PassengerDoorSts": updateDoorState(tvDoorPass, value); break;
            case "BCM_LRDoorSts": updateDoorState(tvDoorRL, value); break;
            case "BCM_RRDoorSts": updateDoorState(tvDoorRR, value); break;
            case "BCM_RearLidSts": updateDoorState(tvDoorTrunk, value); break;
        }
    }

    private void updateDoorState(TextView tv, int value) {
        if (value != 0) {
            tv.setText("ОТКРЫТО");
            tv.setTextColor(Color.RED);
        } else {
            tv.setText("Закрыто");
            tv.setTextColor(Color.LTGRAY);
        }
    }

    private boolean subscribeToBcm() {
        if (bcmService == null) return false;
        Parcel data = Parcel.obtain(); Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.adayo.module.bcmproxy.IBcmServiceAIDL");
            data.writeString(CLIENT_NAME);
            data.writeStrongBinder(bcmCallback);
            boolean success = bcmService.transact(3, data, reply, 0);
            if (success) { reply.readException(); return reply.readInt() != 0; }
        } catch (Exception e) { writeToLog("Ошибка подписки: " + e.toString()); } 
        finally { data.recycle(); reply.recycle(); }
        return false;
    }

    // --- ЛОГИКА ЛОГОВ ---
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
        if (lastFile.length() < MAX_LOG_SIZE) return lastFile;
        else return new File(logDir, "carlog" + (maxIndex + 1) + ".txt");
    }

    private void writeToLog(String message) {
        if (logDir == null) return;
        if (logFile == null || logFile.length() >= MAX_LOG_SIZE) logFile = getNextLogFile();
        if (logFile == null) return;
        try {
            FileWriter writer = new FileWriter(logFile, true);
            writer.append(message).append("\n");
            writer.close();
        } catch (IOException ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}