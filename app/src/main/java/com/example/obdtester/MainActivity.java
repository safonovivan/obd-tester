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

public class MainActivity extends Activity {
    private TextView tvSpeed, tvGear, tvPower, tvVoltage;
    private TextView tvCoolant, tvTransTemp, tvOutTemp;
    private TextView tvDoorDriver, tvDoorPass, tvDoorRL, tvDoorRR, tvDoorTrunk;
    
    private IBinder bcmService;
    private boolean isServiceConnected = false;
    private final String CLIENT_NAME = "OBDTester-" + java.util.UUID.randomUUID().toString();
    private Handler handler = new Handler(Looper.getMainLooper());

    private int[] cachedData = new int[179];
    
    private File logFile;
    private File logDir;
    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final long MAX_LOG_SIZE = 5 * 1024 * 1024; // 5MB

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logDir = getExternalFilesDir(null);

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
        tvSpeed.setTextSize(90);
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
        tvGear.setTextSize(70);
        tvGear.setTypeface(null, Typeface.BOLD);
        tvGear.setGravity(Gravity.CENTER);
        tvGear.setPadding(0, 10, 0, 10);
        tvGear.setText("P");

        tvPower = new TextView(this);
        tvPower.setTextColor(Color.LTGRAY);
        tvPower.setTextSize(25);
        tvPower.setTypeface(null, Typeface.BOLD);
        tvPower.setGravity(Gravity.CENTER);
        tvPower.setText("PWR: --");

        tvVoltage = new TextView(this);
        tvVoltage.setTextColor(Color.YELLOW);
        tvVoltage.setTextSize(28);
        tvVoltage.setTypeface(null, Typeface.BOLD);
        tvVoltage.setGravity(Gravity.CENTER);
        tvVoltage.setPadding(0, 10, 0, 0);
        tvVoltage.setText("АКБ: -- В");

        leftCol.addView(tvSpeed);
        leftCol.addView(tvSpeedLabel);
        leftCol.addView(tvGear);
        leftCol.addView(tvPower);
        leftCol.addView(tvVoltage);

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
        
        // === ПРАВАЯ КОЛОНКА ===
        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.1f);
        rightCol.setLayoutParams(rightParams);

        tvDoorDriver = createDataRow(rightCol, "Водитель:", "Закрыто", Color.LTGRAY, 28, 15);
        tvDoorPass = createDataRow(rightCol, "Пассажир:", "Закрыто", Color.LTGRAY, 28, 15);
        tvDoorRL = createDataRow(rightCol, "Зад. левая:", "Закрыто", Color.LTGRAY, 28, 15);
        tvDoorRR = createDataRow(rightCol, "Зад. правая:", "Закрыто", Color.LTGRAY, 28, 15);
        tvDoorTrunk = createDataRow(rightCol, "Багажник:", "Закрыто", Color.LTGRAY, 28, 15);

        rootLayout.addView(leftCol);
        rootLayout.addView(centerCol);
        rootLayout.addView(rightCol);
        setContentView(rootLayout);

        bindToBcmService();
        
        // Запускаем опрос раз в 2 секунды
        handler.postDelayed(pollRunnable, 2000);
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

    private Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (isServiceConnected && bcmService != null) {
                readBcmInfo();
                readCoolantDirectly();
            }
            handler.postDelayed(this, 2000);
        }
    };

    private void readBcmInfo() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.adayo.module.bcmproxy.IBcmServiceAIDL");
            // transact 4 - getBcmInfo()
            if (bcmService.transact(4, data, reply, 0)) {
                reply.readException();
                if (reply.readInt() != 0) {
                    for (int i = 0; i < 179; i++) {
                        cachedData[i] = reply.readInt();
                    }
                    
                    final int rawVolt = cachedData[59];
                    final int rawTransTemp = cachedData[102];
                    final String time = timeFormat.format(new Date());

                    // ЛОГ: Что нам вернул кэш
                    writeToLog(String.format(Locale.US, "[%s] [КЭШ BcmInfo] АКБ(raw)=%d | АКПП=%d", time, rawVolt, rawTransTemp));

                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (rawVolt > 50 && rawVolt < 300) {
                                tvVoltage.setText(String.format(Locale.US, "АКБ: %.1f В", rawVolt / 10.0));
                            }
                            if (rawTransTemp > -40 && rawTransTemp < 150) {
                                tvTransTemp.setText(String.format(Locale.US, "%d °C", rawTransTemp));
                            }
                        }
                    });
                }
            }
        } catch (Exception ignored) {
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void readCoolantDirectly() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.adayo.module.bcmproxy.IBcmServiceAIDL");
            data.writeString("BCM"); // type
            data.writeString("BCM_Coolant_R"); // key
            
            // transact 7 - getDeviceInfo()
            if (bcmService.transact(7, data, reply, 0)) {
                reply.readException();
                final int coolantVal = reply.readInt();
                final String time = timeFormat.format(new Date());

                // ЛОГ: Что нам вернул прямой запрос
                writeToLog(String.format(Locale.US, "[%s] [ЗАПРОС DeviceInfo] ОЖ=%d", time, coolantVal));

                if (coolantVal > -40 && coolantVal < 150) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            tvCoolant.setText(String.format(Locale.US, "%d °C", coolantVal));
                        }
                    });
                }
            }
        } catch (Exception ignored) {
        } finally {
            data.recycle();
            reply.recycle();
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
                writeToLog("=== ПОДПИСКА НА СОБЫТИЯ ОФОРМЛЕНА ===");
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

                // ЛОГ: События от машины
                String time = timeFormat.format(new Date());
                writeToLog(String.format(Locale.US, "[%s] [СОБЫТИЕ] %s = %d", time, signalName, signalValue));

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
                if (value == 0) g = "P"; 
                else if (value == 5) g = "D"; 
                else if (value == 6) g = "N"; 
                else if (value == 7) g = "R";
                else if (value == 8) g = "M";
                tvGear.setText(g); break;
            case "BCM_Coolant_R":
                if (value > 0 && value <= 120) tvCoolant.setText(String.format(Locale.US, "%d °C", value)); break;
            case "BCM_Ext_Temp_R":
                if (value > -40 && value < 50) tvOutTemp.setText(String.format(Locale.US, "%d °C", value)); break;
            case "BCM_SystemPower_R":
                String pwr = "?";
                if (value == 0) pwr = "OFF";
                else if (value == 1) pwr = "ACC";
                else if (value == 2) pwr = "IGN";
                else if (value == 3) pwr = "RUN";
                tvPower.setText("PWR: " + pwr); break;
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

    private void writeToLog(String message) {
        if (logDir == null) return;
        try {
            if (logFile == null) logFile = new File(logDir, "carlog1.txt");
            FileWriter writer = new FileWriter(logFile, true);
            writer.append(message).append("\n");
            writer.close();
        } catch (IOException ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (bcmService != null) {
            try { unbindService(serviceConnection); } catch (Exception ignored) {}
        }
    }
}
