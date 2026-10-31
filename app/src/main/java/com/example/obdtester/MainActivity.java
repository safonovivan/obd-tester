package com.example.obdtester;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class MainActivity extends Activity {
    private TextView textView;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Object carInstance;
    private Object propertyManager;
    private boolean isCarReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        textView = new TextView(this);
        textView.setTextColor(Color.WHITE);
        textView.setTextSize(40);
        textView.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        textView.setPadding(50, 50, 50, 50);
        setContentView(textView);

        textView.setText("Подключение к Car API...\n");

        // Инициализируем подключение в фоновом потоке, чтобы не подвесить экран
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Ищем класс android.car.Car
                    Class<?> carClass = Class.forName("android.car.Car");
                    Method createCarMethod = carClass.getMethod("createCar", Context.class);
                    carInstance = createCarMethod.invoke(null, MainActivity.this);

                    if (carInstance != null) {
                        // Получаем PROPERTY_SERVICE
                        Field propServiceField = carClass.getField("PROPERTY_SERVICE");
                        String propServiceName = (String) propServiceField.get(null);

                        // Получаем менеджер свойств
                        Method getCarManagerMethod = carClass.getMethod("getCarManager", String.class);
                        propertyManager = getCarManagerMethod.invoke(carInstance, propServiceName);
                        isCarReady = true;
                    }
                } catch (Exception e) {
                    final String error = "Ошибка Reflection:\n" + e.toString();
                    runOnUiThread(new Runnable() {
                        public void run() { textView.setText(error); }
                    });
                }
            }
        }).start();

        // Запускаем цикл опроса
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isCarReady) {
                    readData();
                }
                handler.postDelayed(this, 1000);
            }
        }, 2000); // Даем 2 секунды на инициализацию
    }

    private void readData() {
        StringBuilder sb = new StringBuilder();
        sb.append("Скорость:\n").append(getPropValue("PERF_VEHICLE_SPEED", "км/ч")).append("\n\n");
        sb.append("Обороты:\n").append(getPropValue("ENGINE_RPM", "об/мин")).append("\n\n");
        sb.append("Температура ОЖ:\n").append(getPropValue("ENGINE_COOLANT_TEMP", "°C")).append("\n\n");
        sb.append("Топливо:\n").append(getPropValue("FUEL_LEVEL", "мл")).append("\n");
        textView.setText(sb.toString());
    }

    private String getPropValue(String propName, String unit) {
        try {
            // Получаем ID свойства (например, ENGINE_RPM)
            Class<?> propIdsClass = Class.forName("android.car.VehiclePropertyIds");
            Field propField = propIdsClass.getField(propName);
            int propId = propField.getInt(null);

            // Вызываем getProperty(id, area)
            Method getPropertyMethod = propertyManager.getClass().getMethod("getProperty", int.class, int.class);
            Object carPropertyValue = getPropertyMethod.invoke(propertyManager, propId, 0);

            if (carPropertyValue == null) {
                return "Нет данных (null)";
            }

            // Получаем статус
            Method getStatusMethod = carPropertyValue.getClass().getMethod("getStatus");
            int status = (int) getStatusMethod.invoke(carPropertyValue);

            if (status != 0) { // 0 = STATUS_AVAILABLE
                return "Недоступно (Статус: " + status + ")";
            }

            // Получаем само значение
            Method getValueMethod = carPropertyValue.getClass().getMethod("getValue");
            Object value = getValueMethod.invoke(carPropertyValue);

            if (value instanceof Float) {
                return String.format("%.1f %s", (Float) value, unit);
            } else if (value instanceof Integer) {
                return value + " " + unit;
            } else if (value instanceof Double) {
                return String.format("%.1f %s", (Double) value, unit);
            }
            return value.toString() + " " + unit;

        } catch (Exception e) {
            return "Ошибка: " + e.getCause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (carInstance != null) {
            try {
                Method disconnect = carInstance.getClass().getMethod("disconnect");
                disconnect.invoke(carInstance);
            } catch (Exception ignored) {}
        }
    }
}