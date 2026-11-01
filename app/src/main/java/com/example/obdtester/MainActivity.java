package com.example.obdtester;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
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
    private TextView tvSpeed, tvGear, tvPower;
    private TextView tvCoolant, tvTransTemp, tvOutTemp;
    private TextView tvDoorDriver, tvDoorPass, tvDoorRL, tvDoorRR, tvDoorTrunk;
    
    private IBinder bcmService;
    private boolean isServiceConnected = false;
    private final String CLIENT_NAME = "OBDTester-" + java.util.UUID.randomUUID().toString();
    private Handler handler = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private int[] cachedData = new int[179];

    private File logFile;
    private File logDir;
    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final long MAX_LOG_SIZE = 5 * 1024 * 1024; // 5MB

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logDir = getExternalFilesDir(null);
        prefs = getSharedPreferences("OBD_State", MODE_PRIVATE);

        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.HORIZONTAL);
        rootLayout.setBackgroundColor(Color.BLACK);
        rootLayout.setPadding(20, 20, 20, 20);

        // === ЛЕВАЯ КОЛОНКА ===
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

        tvPower = new TextView(this);
        tvPower.setTextColor(Color.LTGRAY);
        tvPower.setTextSize(30);
        tvPower.setTypeface(null, Typeface.BOLD);
        tvPower.setGravity(Gravity.CENTER);
        tvPower.setPadding(0, 20, 0, 0);
        tvPower.setText("PWR: " + prefs.getString("pwr", "OFF"));

        leftCol.addView(tvSpeed);
        leftCol.addView(tvSpeedLabel);
        leftCol.addView(tvGear);
        leftCol.addView(tvPower);

        // === ЦЕНТРАЛЬНАЯ КОЛОНКА ===
        LinearLayout centerCol = new LinearLayout(this);
        centerCol.setOrientation(LinearLayout.VERTICAL);
        centerCol.setGravity(Gravity.CENTER_VERTICAL);
        centerCol.setPadding(10, 0, 10, 0);
        LinearLayout.LayoutParams centerParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.1f);
        centerCol.setLayoutParams(centerParams);

        tvCoolant = createDataRow(centerCol, "ОЖ:", "-- °C", Color.WHITE, 32, 20);
        tvTransTemp = createDataRow(centerCol, "АКПП:", "-- °C", Color.WHITE, 32, 20);
        tvOutTemp = createDataRow(centerCol, "За бортом:", "-- °C", Color.LTGRAY, 32, 20);
        
        restoreLastValues();
        
        // === ПРАВАЯ КОЛОНКА ===
        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.1f);
        rightCol.setLayoutParams(rightParams);

        tvDoorDriver = createDataRow(rightCol, "Водитель:", "Закрыто", Color.LTGRAY, 30, 15);
        tvDoorPass = createDataRow(rightCol, "Пассажир:", "Закрыто", Color.LTGRAY, 30, 15);
        tvDoorRL = createDataRow(rightCol, "Зад. левая:", "Закрыто", Color.LTGRAY, 30, 15);
        tvDoorRR = createDataRow(rightCol, "Зад. правая:", "Закрыто", Color.LTGRAY, 30, 15);
        tvDoorTrunk = createDataRow(rightCol, "Багажник:", "Закрыто", Color.LTGRAY, 30, 15);

        rootLayout.addView(leftCol);
        rootLayout.addView(centerCol);
        rootLayout.addView(rightCol);
        setContentView(rootLayout);

        bindToBcmService();

        // Запускаем чтение кэша через Root каждые 2 секунды
        handler.postDelayed(rootCacheRunnable, 2000);
    }

    private void restoreLastValues() {
        int coolant = prefs.getInt("coolant", -100);
        if (coolant != -100) tvCoolant.setText(String.format(Locale.US, "%d °C", coolant));

        int transTemp = prefs.getInt("trans_temp", -100);
        if (transTemp != -100) tvTransTemp.setText(String.format(Locale.US, "%d °C", transTemp));

        int outTemp = prefs.getInt("out_temp", -100);
        if (outTemp != -100) tvOutTemp.setText(String.format(Locale.US, "%d °C", outTemp));
    }

    private TextView createDataRow(LinearLayout parent, String label, String defaultValue, int valueColor, int textSize, int padding) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, padding, 0, padding);

        TextView tvLabel = new TextView(this);
        tvLabel.setTextColor(Color.GRAY);
        tvLabel.setTextSize(textSize);
        tvLabel.setText(label);
        tvLabel.setWidth(240); 

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

    // --- ЧТЕНИЕ КЭША ЧЕРЕЗ ROOT ---
    private Runnable rootCacheRunnable = new Runnable() {
        @Override
        public void run() {
            readCacheViaRoot();
            handler.postDelayed(this, 2000); // Раз в 2 секунды
        }
    };

    private void readCacheViaRoot() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Вызов Binder-сервиса через консольную команду под Root
                    String cmd = "su -c service call com.adayo.midware.bcmservice 4 s16 com.adayo.module.bcmproxy.IBcmServiceAIDL";
                    Process process = Runtime.getRuntime().exec(cmd);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    String line;
                    StringBuilder sb = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    process.waitFor();

                    String output = sb.toString();
                    // В логе увидим, что вернула команда
                    writeToLog("ROOT-КЭШ: " + output);

                    // Ищем hex-массив в ответе (обычно он начинается с Result: Parcel(...))
                    if (output.contains("Result: Parcel")) {
                        parseRootCacheOutput(output);
                    }
                } catch (Exception e) {
                    writeToLog("Ошибка Root: " + e.toString());
                }
            }
        }).start();
    }

    // Парсим ответ от service call
    private void parseRootCacheOutput(String output) {
        try {
            // Ищем первую скобку и последнюю
            int start = output.indexOf('(');
            int end = output.lastIndexOf(')');
            if (start == -1 || end == -1) return;

            String hexData = output.substring(start + 1, end).replace(" ", "");
            
            // В Android Parcel данные разбиты на слова по 4 байта (8 hex символов)
            // Первые 8 символов (или около того) - это служебная информация (длина и т.д.)
            // Нам нужно пропустить заголовок и достать 716 байт данных (179 * 4)
            
            // Проверяем, что данных достаточно
            if (hexData.length() < 716 * 2) return; 

            // Обычно ответ от service call выглядит так: 00000000 00000000 00000000 00000001 ... (дальше сами данные)
            // Найдем место, где начинаются наши данные. 
            // В идеале нужно прочитать длину и пропустить заголовок, но для простоты:
            // Просто берем последние 1432 символа (716 * 2) - это и будут наши 179 int-ов.
            
            String dataHex = hexData.substring(hexData.length() - (716 * 2));
            
            for (int i = 0; i < 179; i++) {
                String hexInt = dataHex.substring(i * 8, (i + 1) * 8);
                // Конвертируем hex в int (Little Endian)
                int value = (int) Long.parseLong(hexInt.substring(6, 8) + hexInt.substring(4, 6) + hexInt.substring(2, 4) + hexInt.substring(0, 2), 16);
                cachedData[i] = value;
            }

            final int transTemp = cachedData[102];
            if (transTemp > 0 && transTemp < 150) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        tvTransTemp.setText(String.format(Locale.US, "%d °C", transTemp));
                        prefs.edit().putInt("trans_temp", transTemp).apply();
                    }
                });
            }
            
            // В лог для отладки
            writeToLog("ROOT-КЭШ: АКПП=" + cachedData[102] + " | ОЖ=" + cachedData[59]); // 59 может быть ОЖ или напряжение

        } catch (Exception e) {
            writeToLog("Ошибка парсинга: " + e.toString());
        }
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
            case "VCU_VehSpd": 
                tvSpeed.setText(String.valueOf(value)); 
                break;
            case "BCM_VCU_GearSts_R":
                String g = "?";
                if (value == 0) g = "P"; 
                else if (value == 5) g = "D"; 
                else if (value == 6) g = "N"; 
                else if (value == 7) g = "R";
                else if (value == 8) g = "M";
                tvGear.setText(g); 
                break;
            case "BCM_Coolant_R":
                if (value > 0 && value <= 120) {
                    tvCoolant.setText(String.format(Locale.US, "%d °C", value));
                    prefs.edit().putInt("coolant", value).apply();
                }
                break;
            case "BCM_TRANS_OIL_TEMP_R":
                if (value > 0 && value <= 150) {
                    tvTransTemp.setText(String.format(Locale.US, "%d °C", value));
                    prefs.edit().putInt("trans_temp", value).apply();
                }
                break;
            case "BCM_Ext_Temp_R":
                if (value > -40 && value < 50) {
                    tvOutTemp.setText(String.format(Locale.US, "%d °C", value));
                    prefs.edit().putInt("out_temp", value).apply();
                }
                break;
            case "BCM_SystemPower_R":
                String pwr = "?";
                if (value == 0) pwr = "OFF";
                else if (value == 1) pwr = "ACC";
                else if (value == 2) pwr = "IGN";
                else if (value == 3) pwr = "RUN";
                tvPower.setText("PWR: " + pwr);
                prefs.edit().putString("pwr", pwr).apply();
                break;

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