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
    private TextView tvSteering, tvStartStop;
    
    private IBinder bcmService;
    private boolean isServiceConnected = false;
    private final String CLIENT_NAME = "OBDTester-" + java.util.UUID.randomUUID().toString();
    private Handler handler = new Handler(Looper.getMainLooper());

    // Для отслеживания направления руля
    private int currentSteeringSign = 0; 
    
    private File logFile;
    private File logDir;
    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logDir = getExternalFilesDir(null);

        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.HORIZONTAL);
        rootLayout.setBackgroundColor(Color.BLACK);
        rootLayout.setPadding(20, 10, 20, 10);

        // === ЛЕВАЯ КОЛОНКА (Скорость) ===
        LinearLayout leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftCol.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.7f);
        leftCol.setLayoutParams(leftParams);

        tvSpeed = new TextView(this);
        tvSpeed.setTextColor(Color.WHITE);
        tvSpeed.setTextSize(80);
        tvSpeed.setTypeface(null, Typeface.BOLD);
        tvSpeed.setGravity(Gravity.CENTER);
        tvSpeed.setText("0");

        TextView tvSpeedLabel = new TextView(this);
        tvSpeedLabel.setTextColor(Color.GRAY);
        tvSpeedLabel.setTextSize(25);
        tvSpeedLabel.setGravity(Gravity.CENTER);
        tvSpeedLabel.setText("км/ч");

        tvGear = new TextView(this);
        tvGear.setTextColor(Color.GREEN);
        tvGear.setTextSize(60);
        tvGear.setTypeface(null, Typeface.BOLD);
        tvGear.setGravity(Gravity.CENTER);
        tvGear.setPadding(0, 10, 0, 10);
        tvGear.setText("P");

        tvPower = new TextView(this);
        tvPower.setTextColor(Color.LTGRAY);
        tvPower.setTextSize(22);
        tvPower.setTypeface(null, Typeface.BOLD);
        tvPower.setGravity(Gravity.CENTER);
        tvPower.setText("PWR: --");

        tvVoltage = new TextView(this);
        tvVoltage.setTextColor(Color.YELLOW);
        tvVoltage.setTextSize(24);
        tvVoltage.setTypeface(null, Typeface.BOLD);
        tvVoltage.setGravity(Gravity.CENTER);
        tvVoltage.setPadding(0, 5, 0, 0);
        tvVoltage.setText("АКБ: -- В");

        leftCol.addView(tvSpeed);
        leftCol.addView(tvSpeedLabel);
        leftCol.addView(tvGear);
        leftCol.addView(tvPower);
        leftCol.addView(tvVoltage);

        // === ЦЕНТРАЛЬНАЯ КОЛОНКА (Температуры) ===
        LinearLayout centerCol = new LinearLayout(this);
        centerCol.setOrientation(LinearLayout.VERTICAL);
        centerCol.setGravity(Gravity.CENTER_VERTICAL);
        centerCol.setPadding(10, 0, 10, 0);
        LinearLayout.LayoutParams centerParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.8f);
        centerCol.setLayoutParams(centerParams);

        tvCoolant = createDataRow(centerCol, "ОЖ:", "-- °C", Color.WHITE, 30, 15);
        tvTransTemp = createDataRow(centerCol, "АКПП:", "-- °C", Color.WHITE, 30, 15);
        tvOutTemp = createDataRow(centerCol, "За бортом:", "-- °C", Color.LTGRAY, 30, 15);

        // === 3-Я КОЛОНКА (Двери) ===
        LinearLayout doorCol = new LinearLayout(this);
        doorCol.setOrientation(LinearLayout.VERTICAL);
        doorCol.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams doorParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.8f);
        doorCol.setLayoutParams(doorParams);

        tvDoorDriver = createDataRow(doorCol, "Водитель:", "Закрыто", Color.LTGRAY, 26, 12);
        tvDoorPass = createDataRow(doorCol, "Пассажир:", "Закрыто", Color.LTGRAY, 26, 12);
        tvDoorRL = createDataRow(doorCol, "Зад. левая:", "Закрыто", Color.LTGRAY, 26, 12);
        tvDoorRR = createDataRow(doorCol, "Зад. правая:", "Закрыто", Color.LTGRAY, 26, 12);
        tvDoorTrunk = createDataRow(doorCol, "Багажник:", "Закрыто", Color.LTGRAY, 26, 12);

        // === 4-Я КОЛОНКА (Руль, Старт-Стоп) ===
        LinearLayout newCol = new LinearLayout(this);
        newCol.setOrientation(LinearLayout.VERTICAL);
        newCol.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams newParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.8f);
        newCol.setLayoutParams(newParams);

        tvSteering = createDataRow(newCol, "Руль:", "-- °", Color.CYAN, 28, 15);
        tvStartStop = createDataRow(newCol, "Ст-Стоп:", "--", Color.LTGRAY, 28, 15);

        rootLayout.addView(leftCol);
        rootLayout.addView(centerCol);
        rootLayout.addView(doorCol);
        rootLayout.addView(newCol);
        setContentView(rootLayout);

        bindToBcmService();
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
        tvLabel.setWidth(200); 

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

    private int getDeviceValue(String key) {
        if (bcmService == null) return Integer.MIN_VALUE;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.adayo.module.bcmproxy.IBcmServiceAIDL");
            data.writeString("BCM");
            data.writeString(key);
            if (bcmService.transact(7, data, reply, 0)) {
                reply.readException();
                return reply.readInt();
            }
        } catch (Exception ignored) {
        } finally {
            data.recycle();
            reply.recycle();
        }
        return Integer.MIN_VALUE;
    }

    private Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (isServiceConnected && bcmService != null) {
                final int coolant = getDeviceValue("BCM_Coolant_R");
                final int transTemp = getDeviceValue("BCM_TRANS_OIL_TEMP_R");
                final int outTemp = getDeviceValue("BCM_Ext_Temp_R");
                final int volt = getDeviceValue("BCM_BattVolt_R");
                final int powerStatus = getDeviceValue("BCM_SystemPower_R");
                final int startStop = getDeviceValue("BCM_Smart_Stop");

                runOnUiThread(new Runnable() {
                    public void run() {
                        if (powerStatus != Integer.MIN_VALUE) {
                            String pwr = "?";
                            if (powerStatus == 0) pwr = "OFF";
                            else if (powerStatus == 1) pwr = "ACC";
                            else if (powerStatus == 2) pwr = "IGN";
                            else if (powerStatus == 3) pwr = "RUN";
                            tvPower.setText("PWR: " + pwr);
                        }

                        if (coolant > 0 && coolant < 120) tvCoolant.setText(String.format(Locale.US, "%d °C", coolant));
                        if (transTemp > -40 && transTemp < 150) tvTransTemp.setText(String.format(Locale.US, "%d °C", transTemp));
                        if (outTemp > -40 && outTemp < 50) tvOutTemp.setText(String.format(Locale.US, "%d °C", outTemp));
                        if (volt > 0) tvVoltage.setText(String.format(Locale.US, "АКБ: %.1f В", volt / 100.0f));
                        if (startStop != Integer.MIN_VALUE) tvStartStop.setText(startStop == 1 ? "ВКЛ" : "ВЫКЛ");
                    }
                });
            }
            handler.postDelayed(this, 2000);
        }
    };

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
            
            // === РУЛЬ (МГНОВЕННО ИЗ СОБЫТИЙ) ===
            case "BCM_SteeringWheelAngle_R":
                if (value >= 0 && value < 9000) {
                    float angle = value / 10.0f;
                    // ИНВЕРТИРОВАЛИ ЛОГИКУ: Теперь 1 - это вправо (►), 0 - влево (◄)
                    String direction = (currentSteeringSign == 1) ? "►" : "◄";
                    tvSteering.setText(String.format(Locale.US, "%.1f ° %s", angle, direction));
                }
                break;
            case "BCM_SteeringWheelAngleSign_R":
                currentSteeringSign = value; 
                break;
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