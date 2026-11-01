package com.example.obdtester;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.View;
import android.widget.Button;
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
    private TextView textView;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;
    private File logFile;
    private File logDir;
    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final long MAX_LOG_SIZE = 5 * 1024 * 1024; // 5MB

    private IBinder bcmService;
    private boolean isServiceConnected = false;
    private int[] cachedData = new int[179];
    
    // Строка-идентификатор нашего клиента (как в M6Drive)
    private final String CLIENT_NAME = "OBDTester-" + java.util.UUID.randomUUID().toString();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // --- Настройка UI ---
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.BLACK);
        layout.setPadding(30, 30, 30, 30);

        LinearLayout buttonLayout = new LinearLayout(this);
        buttonLayout.setOrientation(LinearLayout.HORIZONTAL);

        Button btnStart = new Button(this);
        btnStart.setText("СТАРТ");
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { startPolling(); }
        });

        Button btnStop = new Button(this);
        btnStop.setText("СТОП");
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { stopPolling(); }
        });

        buttonLayout.addView(btnStart);
        buttonLayout.addView(btnStop);

        textView = new TextView(this);
        textView.setTextColor(Color.WHITE);
        textView.setTextSize(20);
        textView.setPadding(0, 30, 0, 0);
        textView.setText("Ожидание...\nПопытка подключения к BCM Service...");

        layout.addView(buttonLayout);
        layout.addView(textView);
        setContentView(layout);

        // --- Настройка файла лога ---
        logDir = getExternalFilesDir(null);

        // --- Инициализация AIDL подключения ---
        bindToBcmService();
    }

    private void bindToBcmService() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                "com.adayo.midware.bcmservice",
                "com.adayo.midware.bcmservice.Controller.BcmService"
        ));

        try {
            boolean bound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
            if (!bound) {
                writeToLog("ОШИБКА: Система отказала в bindService");
                textView.setText("Ошибка: bindService не удался");
            }
        } catch (Exception e) {
            writeToLog("ИСКЛЮЧЕНИЕ при bindService: " + e.toString());
            textView.setText("Ошибка подключения:\n" + e.getMessage());
        }
    }

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            bcmService = service;
            
            // Проверяем интерфейс (защита от подмены)
            try {
                if (!"com.adayo.module.bcmproxy.IBcmServiceAIDL".equals(service.getInterfaceDescriptor())) {
                    writeToLog("ОШИБКА: Неверный интерфейс сервиса: " + service.getInterfaceDescriptor());
                    return;
                }
            } catch (RemoteException e) {
                writeToLog("ОШИБКА RemoteException при получении дескриптора");
                return;
            }

            // 1. СНАЧАЛА ПОДПИСЫВАЕМСЯ (transact 3)
            boolean subSuccess = subscribeToBcm();
            
            if (subSuccess) {
                isServiceConnected = true;
                writeToLog("УСПЕХ: Подписка на BCM оформлена");
                runOnUiThread(new Runnable() {
                    public void run() { textView.setText("Служба BCM подключена!\nНажмите СТАРТ"); }
                });
            } else {
                writeToLog("ОШИБКА: Не удалось подписаться на BCM");
                runOnUiThread(new Runnable() {
                    public void run() { textView.setText("Ошибка подписки на BCM"); }
                });
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bcmService = null;
            isServiceConnected = false;
            writeToLog("ВНИМАНИЕ: Служба BCM отключена");
        }
    };

    // ЭТОТ БЛОК - НАШ ПРИЕМНИК (CALLBACK)
    // Сюда система будет присылать данные, когда ты откроешь дверь или нажмешь газ
    private final IBinder bcmCallback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == 1) { // 1 - код события
                data.enforceInterface("com.adayo.module.bcmproxy.IBcmClientCallBack");
                
                // Читаем название сигнала (например, "VCU_VehSpd")
                String signalName = data.readString();
                // Читаем само значение
                int signalValue = data.readInt();
                
                // Пишем в лог!
                String time = timeFormat.format(new Date());
                writeToLog(String.format(Locale.US, "[%s] СОБЫТИЕ: %s = %d", time, signalName, signalValue));
                
                // Если это скорость - выводим на экран
                if ("VCU_VehSpd".equals(signalName)) {
                    final String text = "Скорость: " + signalValue + " км/ч";
                    runOnUiThread(new Runnable() {
                        public void run() { textView.setText(text); }
                    });
                }

                if (reply != null) reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    private boolean subscribeToBcm() {
        if (bcmService == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.adayo.module.bcmproxy.IBcmServiceAIDL");
            data.writeString(CLIENT_NAME); // Имя клиента
            data.writeStrongBinder(bcmCallback); // Наш приемник

            // transact(3) - это код подписки
            boolean transactSuccess = bcmService.transact(3, data, reply, 0);
            
            if (transactSuccess) {
                reply.readException();
                return reply.readInt() != 0; // 1 = успех
            }
        } catch (Exception e) {
            writeToLog("Ошибка при подписке: " + e.toString());
        } finally {
            data.recycle();
            reply.recycle();
        }
        return false;
    }

    private void startPolling() {
        if (!isServiceConnected) {
            textView.setText("Ошибка: Служба BCM еще не подключена.");
            return;
        }
        if (isRunning) return;
        isRunning = true;
        writeToLog("=== СТАРТ ОПРОСА ===");
        handler.post(pollRunnable);
    }

    private void stopPolling() {
        isRunning = false;
        handler.removeCallbacks(pollRunnable);
        writeToLog("=== СТОП ОПРОСА ===");
        textView.setText("Опрос остановлен.");
    }

    private Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            readData();
            handler.postDelayed(this, 2000); // Раз в 2 секунды
        }
    };

    private void readData() {
        if (bcmService == null) return;

        boolean success = readBcmCache();

        if (success) {
            // Если мы хотим просто проверить, что кэш живой
            writeToLog(String.format(Locale.US, "[%s] КЭШ: Val59: %d | Val102: %d", timeFormat.format(new Date()), cachedData[59], cachedData[102]));
        }
    }

    private boolean readBcmCache() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.adayo.module.bcmproxy.IBcmServiceAIDL");
            
            // transact(4) - чтение кэша
            boolean transactSuccess = bcmService.transact(4, data, reply, 0);
            
            if (transactSuccess) {
                reply.readException();
                int successFlag = reply.readInt();
                if (successFlag == 1 && reply.dataAvail() == 716) {
                    for (int i = 0; i < 179; i++) {
                        cachedData[i] = reply.readInt();
                    }
                    return true;
                }
            }
        } catch (Exception e) {
            // Игнорируем ошибки кэша
        } finally {
            data.recycle();
            reply.recycle();
        }
        return false;
    }

    // --- Логика логов ---
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPolling();
        if (isServiceConnected) {
            unbindService(serviceConnection);
        }
    }
}