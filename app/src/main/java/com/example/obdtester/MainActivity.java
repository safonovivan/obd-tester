package com.example.obdtester;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private TextView textView;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Object carInstance;
    private Object propertyManager;
    private boolean isCarReady = false;
    private boolean isRunning = false;
    private File logFile;
    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    // Лимит одного файла: 5 МБ
    private static final long MAX_LOG_SIZE = 5 * 1024 * 1024;
    private File logDir;

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
            public void onClick(View v) {
                startPolling();
            }
        });

        Button btnStop = new Button(this);
        btnStop.setText("СТОП");
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopPolling();
            }
        });

        buttonLayout.addView(btnStart);
        buttonLayout.addView(btnStop);

        textView = new TextView(this);
        textView.setTextColor(Color.WHITE);
        textView.setTextSize(35);
        textView.setPadding(0, 30, 0, 0);
        textView.setText("Ожидание...\nНажмите СТАРТ");

        layout.addView(buttonLayout);
        layout.addView(textView);
        setContentView(layout);

        // --- Настройка папки для логов ---
        logDir = getExternalFilesDir(null);

        // --- Инициализация Car API в фоне ---
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Class<?> carClass = Class.forName("android.car.Car");
                    Method createCarMethod = carClass.getMethod("createCar", Context.class);
                    carInstance = createCarMethod.invoke(null, MainActivity.this);

                    if (carInstance != null) {
                        Field propServiceField = carClass.getField("PROPERTY_SERVICE");
                        String propServiceName = (String) propServiceField.get(null);
                        Method getCarManagerMethod = carClass.getMethod("getCarManager", String.class);
                        propertyManager = getCarManagerMethod.invoke(carInstance, propServiceName);
                        isCarReady = true;
                        writeToLog("Car API успешно инициализирован.");
                    }
                } catch (Exception e) {
                    writeToLog("КРИТИЧЕСКАЯ ОШИБКА INIT: " + e.toString());
                    final String error = "Ошибка Reflection:\n" + e.toString();
                    runOnUiThread(new Runnable() {
                        public void run() { textView.setText(error); }
                    });
                }
            }
        }).start();
    }

    private void startPolling() {
        if (!isCarReady) {
            textView.setText("Ошибка: Car API еще не готов или недоступен.");
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
            handler.postDelayed(this, 1000); // 1 раз в секунду
        }
    };

    private void readData() {
        StringBuilder sb = new StringBuilder();
        String time = timeFormat.format(new Date());
        
        String speed = getPropValue("PERF_VEHICLE_SPEED", "км/ч");
        String rpm = getPropValue("ENGINE_RPM", "об/мин");
        String temp = getPropValue("ENGINE_COOLANT_TEMP", "°C");
        String fuel = getPropValue("FUEL_LEVEL", "мл");

        sb.append("Скорость: ").append(speed).append("\n");
        sb.append("Обороты: ").append(rpm).append("\n");
        sb.append("Темп. ОЖ: ").append(temp).append("\n");
        sb.append("Топливо: ").append(fuel).append("\n");

        textView.setText(sb.toString());

        String logLine = String.format(Locale.US, "[%s] Speed: %s | RPM: %s | Temp: %s | Fuel: %s", 
            time, speed, rpm, temp, fuel);
        writeToLog(logLine);
    }

    private String getPropValue(String propName, String unit) {
        try {
            Class<?> propIdsClass = Class.forName("android.car.VehiclePropertyIds");
            Field propField = propIdsClass.getField(propName);
            int propId = propField.getInt(null);

            Method getPropertyMethod = propertyManager.getClass().getMethod("getProperty", int.class, int.class);
            Object carPropertyValue = getPropertyMethod.invoke(propertyManager, propId, 0);

            if (carPropertyValue == null) return "null";

            Method getStatusMethod = carPropertyValue.getClass().getMethod("getStatus");
            int status = (int) getStatusMethod.invoke(carPropertyValue);

            if (status != 0) return "Недоступно(St:" + status + ")";

            Method getValueMethod = carPropertyValue.getClass().getMethod("getValue");
            Object value = getValueMethod.invoke(carPropertyValue);

            if (value instanceof Float) return String.format(Locale.US, "%.1f %s", (Float) value, unit);
            if (value instanceof Integer) return value + " " + unit;
            if (value instanceof Double) return String.format(Locale.US, "%.1f %s", (Double) value, unit);
            return value.toString() + " " + unit;

        } catch (Exception e) {
            return "Ошибка: " + (e.getCause() != null ? e.getCause().getClass().getSimpleName() : e.getClass().getSimpleName());
        }
    }

    // Ищем нужный файл, если текущего нет или он переполнен
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
        
        // Если файлов еще нет, начинаем с первого
        if (maxIndex == 0) return new File(logDir, "carlog1.txt");
        
        // Проверяем последний существующий файл
        File lastFile = new File(logDir, "carlog" + maxIndex + ".txt");
        if (lastFile.length() < MAX_LOG_SIZE) {
            return lastFile; // Ему еще есть место, пишем в него
        } else {
            return new File(logDir, "carlog" + (maxIndex + 1) + ".txt"); // Создаем следующий
        }
    }

    private void writeToLog(String message) {
        if (logDir == null) return;

        // Если текущего файла нет (первый запуск) ИЛИ он превысил 5 МБ, запрашиваем новый
        if (logFile == null || logFile.length() >= MAX_LOG_SIZE) {
            logFile = getNextLogFile();
        }
        
        if (logFile == null) return;

        try {
            FileWriter writer = new FileWriter(logFile, true);
            writer.append(message).append("\n");
            writer.close();
        } catch (IOException e) {
            // Игнорируем
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPolling();
        if (carInstance != null) {
            try {
                Method disconnect = carInstance.getClass().getMethod("disconnect");
                disconnect.invoke(carInstance);
            } catch (Exception ignored) {}
        }
    }
}