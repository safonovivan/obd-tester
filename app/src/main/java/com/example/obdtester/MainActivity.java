package com.example.obdtester;

import android.app.Activity;
import android.car.Car;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView textView;
    private Handler handler = new Handler(Looper.getMainLooper());
    private CarPropertyManager propertyManager;
    private Car car;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Создаем экран программно
        textView = new TextView(this);
        textView.setTextColor(Color.WHITE);
        textView.setTextSize(40);
        textView.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        textView.setPadding(50, 50, 50, 50);
        setContentView(textView);

        // Подключаемся к сервису автомобиля
        try {
            car = Car.createCar(this);
            propertyManager = (CarPropertyManager) car.getCarManager(Car.PROPERTY_SERVICE);
        } catch (Exception e) {
            textView.setText("Ошибка подключения к Car API:\n" + e.getMessage());
            return;
        }

        // Запускаем цикл опроса каждую секунду
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                readData();
                handler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private void readData() {
        if (propertyManager == null) return;

        StringBuilder sb = new StringBuilder();
        
        // 1. СКОРОСТЬ
        sb.append("Скорость:\n");
        sb.append(getPropValue(android.car.VehiclePropertyIds.PERF_VEHICLE_SPEED, "км/ч")).append("\n\n");

        // 2. ОБОРОТЫ
        sb.append("Обороты:\n");
        sb.append(getPropValue(android.car.VehiclePropertyIds.ENGINE_RPM, "об/мин")).append("\n\n");

        // 3. ТЕМПЕРАТУРА ОЖ
        sb.append("Температура ОЖ:\n");
        sb.append(getPropValue(android.car.VehiclePropertyIds.ENGINE_COOLANT_TEMP, "°C")).append("\n\n");

        // 4. ТОПЛИВО
        sb.append("Топливо:\n");
        sb.append(getPropValue(android.car.VehiclePropertyIds.FUEL_LEVEL, "мл")).append("\n");

        textView.setText(sb.toString());
    }

    private String getPropValue(int propId, String unit) {
        try {
            CarPropertyValue<?> value = propertyManager.getProperty(propId, 0);
            if (value != null && value.getStatus() == CarPropertyValue.STATUS_AVAILABLE) {
                Object val = value.getValue();
                if (val instanceof Float) {
                    return String.format("%.1f %s", (Float) val, unit);
                } else if (val instanceof Integer) {
                    return val + " " + unit;
                } else if (val instanceof Double) {
                    return String.format("%.1f %s", (Double) val, unit);
                }
                return val.toString() + " " + unit;
            } else {
                return "Нет данных (Ошибка: " + (value != null ? value.getStatus() : "null") + ")";
            }
        } catch (Exception e) {
            return "Ошибка доступа (Возможно, нет прав)";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (car != null) {
            car.disconnect();
        }
        handler.removeCallbacksAndMessages(null);
    }
}