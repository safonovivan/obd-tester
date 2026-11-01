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
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    // UI элементы
    private TextView tvSpeed, tvGear;
    private TextView tvCoolant, tvTransTemp, tvOutTemp, tvVoltage;
    private TextView tvDoorDriver, tvDoorPass, tvDoorRL, tvDoorRR, tvDoorTrunk;
    
    // Сервис и данные
    private IBinder bcmService;
    private boolean isServiceConnected = false;
    private final String CLIENT_NAME = "OBDTester-" + java.util.UUID.randomUUID().toString();
    private Handler handler = new Handler(Looper.getMainLooper());

    // Для чтения кэша (АКБ)
    private int[] cachedData = new int[179];

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
        rootLayout.setOrientation(LinearLayout.HORIZONTAL); // Горизонтально (3 колонки)
        rootLayout.setBackgroundColor(Color.BLACK);
        rootLayout.setPadding(20, 20, 20, 20);

        // === ЛЕВАЯ КОЛОНКА (Скорость и Передача) ===
        LinearLayout leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftCol.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
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

        // === ЦЕНТРАЛЬНАЯ КОЛОНКА (Температуры и АКБ) ===
        LinearLayout centerCol = new LinearLayout(this);
        centerCol.setOrientation(LinearLayout.VERTICAL);
        centerCol.setGravity(Gravity.CENTER_VERTICAL);
        centerCol.setPadding(20, 0, 20, 0);
        LinearLayout.LayoutParams centerParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        centerCol.setLayoutParams(centerParams);

        tvCoolant = createDataRow(centerCol, "ОЖ:", "-- °C", Color.WHITE);
        tvTransTemp = createDataRow(centerCol, "АКПП:", "-- °C", Color.WHITE);
        tvOutTemp = createDataRow(centerCol, "За бортом:", "-- °C", Color.LTGRAY);
        tvVoltage = createDataRow(centerCol, "АКБ:", "-- В", Color.YELLOW);

        // === ПРАВАЯ КОЛОНКА (Двери и Багажник) ===
        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        rightCol.setLayoutParams(rightParams);

        tvDoorDriver = createDataRow(rightCol, "Водитель:", "Закрыто", Color.LTGRAY);
        tvDoorPass = createDataRow(rightCol, "Пассажир:", "Закрыто", Color.LTGRAY);
        tvDoorRL = createDataRow(rightCol, "Зад. левая:", "Закрыто", Color.LTGRAY);
        tvDoorRR = createDataRow(rightCol, "Зад. правая:", "Закрыто", Color.LTGRAY);
        tvDoorTrunk = createDataRow(rightCol, "Багажник:", "Закрыто", Color.LTGRAY);

        // Собираем всё вместе
        rootLayout.addView(leftCol);
        rootLayout.addView(centerCol);
        rootLayout.addView(rightCol);
        setContentView(rootLayout);

        // Подключаемся к BCM
        bindToBcmService();

        // Запускаем чтение кэша (АКБ) каждые 2 секунды
        handler.postDelayed(cacheRunnable, 2000);
    }

    // Вспомогательная функция для создания строк "Название: Значение"
    private TextView createDataRow(LinearLayout parent, String label, String defaultValue, int valueColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 15, 0, 15);

        TextView tvLabel = new TextView(this);
        tvLabel.setTextColor(Color.GRAY);
        tvLabel.setTextSize(35);
        tvLabel.setText(label);
        tvLabel.setWidth(250); // Фиксированная ширина для ровности

        TextView tvValue = new TextView(this);
        tvValue.setTextColor(valueColor);
        tvValue.setTextSize(35);
        tvValue.setTypeface(null, Typeface.BOLD);
        tvValue.setText(defaultValue);

        row.addView(tvLabel);
        row.addView(tvValue);
        parent.addView(row);
        
        return tvValue; // Возвращаем само поле со значением, чтобы его обновлять
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
        }
    };

    private final IBinder bcmCallback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == 1) {
                data.enforceInterface("com.adayo.module.bcmproxy.IBcmClientCallBack");
                String signalName = data.readString();
                final int signalValue = data.readInt();

                // Пишем всё подряд в лог
                String time = timeFormat.format(new Date());
                writeToLog(String.format(Locale.US, "[%s] СОБЫТИЕ: %s = %d", time, signalName, signalValue));

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
                tvSpeed.setText(String.valueOf(value));
                break;
                
            case "BCM_VCU_GearSts_R":
                String gearText = "?";
                if (value == 0) gearText = "P";
                else if (value == 5) gearText = "D";
                else if (value == 6) gearText = "N";
                else if (value == 7) gearText = "R";
                tvGear.setText(gearText);
                break;
                
            case "BCM_Coolant_R":
                if (value > 0 && value <= 120) tvCoolant.setText(String.format(Locale.US, "%d °C", value));
                break;
                
            case "BCM_TRANS_OIL_TEMP_R":
                if (value > 0 && value <= 150) tvTransTemp.setText(String.format(Locale.US, "%d °C", value));
                break;
                
            case "BCM_Ext_Temp_R":
                if (value > -40 && value < 50) tvOutTemp.setText(String.format(Locale.US, "%d °C", value));
                break;

            case "BCM_DriverDoorSts":
                updateDoorState(tvDoorDriver, value);
                break;
            case "BCM_PassengerDoorSts":
                updateDoorState(tvDoorPass, value);
                break;
            case "BCM_LRDoorSts":
                updateDoorState(tvDoorRL, value);
                break;
            case "BCM_RRDoorSts":
                updateDoorState(tvDoorRR, value);
                break;
            case "BCM_RearLidSts":
                updateDoorState(tvDoorTrunk, value);
                break;

            // Отлов напряжения через события (если они придут)
            case "MTR_GWE_U_BATT_R":
            case "BCM_InputVoltForBCM_R":
                // Обычно напряжение приходит в милливольтах (например, 12500 = 12.5 В)
                double volts = value / 1000.0;
                if (volts > 5.0 && volts < 20.0) {
                    tvVoltage.setText(String.format(Locale.US, "%.1f В", volts));
                } else if (value > 5 && value < 20) {
                    // Если пришло сразу в вольтах
                    tvVoltage.setText(String.format(Locale.US, "%d В", value));
                }
                break;
        }
    }

    // Вспомогательный метод для дверей
    private void updateDoorState(TextView tv, int value) {
        if (value == 1 || value == 2 || value == 3) { // 1 или другие ненулевые значения = открыто
            tv.setText("ОТКРЫТО");
            tv.setTextColor(Color.RED);
        } else {
            tv.setText("Закрыто");
            tv.setTextColor(Color.LTGRAY);
        }
    }

    // --- ЧТЕНИЕ КЭША ДЛЯ НАПРЯЖЕНИЯ АКБ ---
    private Runnable cacheRunnable = new Runnable() {
        @Override
        public void run() {
            if (isServiceConnected && bcmService != null) {
                readBcmCache();
            }
            handler.postDelayed(this, 2000); // Каждые 2 секунды
        }
    };

    private void readBcmCache() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.adayo.module.bcmproxy.IBcmServiceAIDL");
            boolean transactSuccess = bcmService.transact(4, data, reply, 0);
            
            if (transactSuccess) {
                reply.readException();
                int successFlag = reply.readInt();
                if (successFlag == 1 && reply.dataAvail() == 716) {
                    for (int i = 0; i < 179; i++) {
                        cachedData[i] = reply.readInt();
                    }
                    
                    // Пытаемся вытащить напряжение (индекс 59 из реверса M6Drive)
                    int rawVolts = cachedData[59];
                    if (rawVolts != 0) {
                        // Если напряжение в милливольтах (например, 12500)
                        if (rawVolts > 1000) {
                            final double v = rawVolts / 1000.0;
                            if (v > 5 && v < 20) {
                                runOnUiThread(new Runnable() {
                                    public void run() { tvVoltage.setText(String.format(Locale.US, "%.1f В", v)); }
                                });
                            }
                        } else if (rawVolts > 5 && rawVolts < 20) {
                            // Если сразу в вольтах
                            final int v = rawVolts;
                            runOnUiThread(new Runnable() {
                                public void run() { tvVoltage.setText(String.format(Locale.US, "%d В", v)); }
                            });
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            data.recycle();
            reply.recycle();
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