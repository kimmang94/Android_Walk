package com.defaultcompany.stepcountplugin;

import android.app.AlertDialog;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Binder;
import android.os.Debug;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import java.util.HashMap;
import java.util.Calendar;
import com.google.gson.Gson;
import android.content.SharedPreferences;
import com.google.gson.reflect.TypeToken;
import android.app.PendingIntent;
import android.os.SystemClock;
import android.app.AlarmManager;
import androidx.core.app.NotificationManagerCompat;
import android.os.Handler;
import android.os.Looper;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class StepCounterService extends Service implements SensorEventListener {
    private final IBinder binder = new LocalBinder();
    private SensorManager sensorManager;
    private Sensor stepSensor;
    private int totalSteps;
    private String currentDate;
    private SimpleDateFormat dateFormat;
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "StepCounterServiceChannel";
    private HashMap<String, Integer> stepsPerHour = new HashMap<>();
    private HashMap<String, HashMap<String, Integer>> saveStepsPerHour = new HashMap<>();
    private int lastStepCount = 0;
    private int lastHour = -1;
    private SharedPreferences sharedPreferences;
    private NotificationManager notificationManager;
    private int todaySteps = 0; // 하루 동안의 발걸음수를 저장할 변수
    public static boolean isStart;
    private ExecutorService executorService;
    private Handler uiHandler;
    private ScheduledExecutorService scheduledExecutorService;
    public int lastSavedTotalSteps;
    int stepsSinceAppStarted;
    Calendar calendar;
    int timeStep;
    private int currentHour;
    @Override
    public void onCreate() {
        super.onCreate();
        calendar = Calendar.getInstance();

        dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        currentDate = dateFormat.format(new Date());

        sharedPreferences = getSharedPreferences("StepCounterPrefs", Context.MODE_PRIVATE);
        loadStepsData();
        // stepsPerHour 초기화 로직을 loadStepsData 메서드 안으로 이동
        if (stepsPerHour == null || stepsPerHour.isEmpty()) {
            stepsPerHour = new HashMap<>();
            for (int i = 0; i < 24; i++) {
                stepsPerHour.put(String.format(Locale.getDefault(), "%02d", i), 0);
            }
        }
        // 이전에 저장된 걸음 수를 불러옵니다.
        totalSteps = sharedPreferences.getInt("TotalSteps", 0);

        Log.d("totalSteps 1111", "totalSteps 1111 : " + totalSteps);
        lastStepCount = sharedPreferences.getInt("LastStepCount", 0);
        lastHour = sharedPreferences.getInt("LastHour", -1);
        currentDate = sharedPreferences.getString("CurrentDate", dateFormat.format(new Date()));

        // 오늘 날짜와 마지막 저장된 날짜를 비교하여, 날짜가 변경되었을 경우 todaySteps를 0으로 초기화
        String lastSavedDate = sharedPreferences.getString("LastSavedDate", currentDate);
        if (!currentDate.equals(lastSavedDate)) {
            todaySteps = 0;
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString("LastSavedDate", currentDate);
            editor.apply();
        } else {
            todaySteps = sharedPreferences.getInt("TodaySteps", 0);
        }
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) {
            stopSelf();
            return;
        }

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, getNotification(stepsSinceAppStarted));

        NotificationManagerCompat notificationManagerCompat = NotificationManagerCompat.from(this);

        // ExecutorService와 Handler를 초기화합니다.
        executorService = Executors.newSingleThreadExecutor();
        uiHandler = new Handler(Looper.getMainLooper());

        // ScheduledExecutorService 초기화
        scheduledExecutorService = Executors.newScheduledThreadPool(1);

        // 정기적인 체크 시작
        scheduleRegularCheck();
    }

    private void loadStepsData() {
        totalSteps = sharedPreferences.getInt("TotalSteps", 0);
        stepsSinceAppStarted = sharedPreferences.getInt("stepsSinceAppStarted", 0);
        Log.d("totalSteps 2222", "totalSteps 2222 : " + totalSteps);
        lastStepCount = sharedPreferences.getInt("LastStepCount", 0);
        lastHour = sharedPreferences.getInt("LastHour", -1);
        currentDate = sharedPreferences.getString("CurrentDate", dateFormat.format(new Date()));

        String stepsPerHourJson = sharedPreferences.getString("StepsPerHour", "{}");
        Gson gson = new Gson();
        stepsPerHour = gson.fromJson(stepsPerHourJson, new TypeToken<HashMap<String, Integer>>(){}.getType());

        // 기존 데이터가 없는 경우 시간별로 0으로 초기화
        if (stepsPerHour == null || stepsPerHour.isEmpty()) {
            stepsPerHour = new HashMap<>();
            for (int i = 0; i < 24; i++) {
                stepsPerHour.put(String.format(Locale.getDefault(), "%02d", i), 0);
            }
        }

    }
    // 배터리 최적화의 상태를 알아보는 함수
    public void CheckBattery()
    {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean isIgnoringOptimizations = false;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            isIgnoringOptimizations = powerManager.isIgnoringBatteryOptimizations(getPackageName());
        }
        if (isIgnoringOptimizations) {
            // 앱이 배터리 최적화 대상에서 제외됨 ("제한 없음" 상태)
            isStart = true;
        } else {
            // 앱이 배터리 최적화 대상에 포함됨 ("최적화" 상태)
            isStart = false;
        }
    }

    // 배터리 최적화 퍼미션을 띄우는 함수
    public static boolean isIgnoringBatteryOptimizations(Context context) {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            return powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
        }
        return true; // API 레벨 23 미만에서는 항상 true 반환
    }

    // 배터리최적화 퍼미션
    public void openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent();
            intent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Log.e("StepCounterService", "Battery optimization settings activity not found", e);
            }
        }
    }
    // saveStepsPerHour 에 저장된 HashMap 값을 Json 으로 유니티에서 호출하게끔 하는 함수
    public String getSaveStepsPerHourJson() {
        Gson gson = new Gson();
        return gson.toJson(saveStepsPerHour);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 센서 관리자 인스턴스를 가져옵니다.
        if (sensorManager == null) {
            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        }

        // 걸음수 센서를 가져옵니다.
        if (stepSensor == null) {
            stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        }

        // 센서 리스너를 등록합니다. 기존에 등록된 리스너가 있다면, 먼저 등록을 해제합니다.
        sensorManager.unregisterListener(this);
        sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI);

        // SharedPreferences에서 마지막으로 저장된 걸음수를 불러옵니다.
        if (sharedPreferences == null) {
            sharedPreferences = getSharedPreferences("StepCounterPrefs", MODE_PRIVATE);
        }
        int lastSavedSteps = sharedPreferences.getInt("LastSteps", 0);
        totalSteps = lastSavedSteps;
        // 포그라운드 서비스 알림을 업데이트합니다.
        updateNotification(stepsSinceAppStarted);

        // 재시작할 때 사용할 인텐트를 준비합니다.
        Intent restartServiceIntent = new Intent(getApplicationContext(), this.getClass());
        restartServiceIntent.setPackage(getPackageName());
        PendingIntent restartServicePendingIntent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            restartServicePendingIntent = PendingIntent.getService(this, 1, restartServiceIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else {
            restartServicePendingIntent = PendingIntent.getService(this, 1, restartServiceIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        }

        // 알람 매니저를 사용하여 서비스를 정기적으로 재시작합니다.
        AlarmManager alarmService = (AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
        alarmService.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 10000, restartServicePendingIntent);

        // START_STICKY는 시스템에 서비스가 강제 종료된 경우 재시작하도록 지시합니다.
        return START_STICKY;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
        currentHour = calendar.get(Calendar.HOUR_OF_DAY);
        Log.d("currentHour", "currentHour" + currentHour);
        String hourKey = String.format(Locale.getDefault(), "%02d", calendar.get(Calendar.HOUR_OF_DAY));
        int stepsThisHour = stepsPerHour.getOrDefault(hourKey, 0) + stepsSinceAppStarted;


        if (event.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
            int currentTotalSteps = (int) event.values[0];
            int lastSavedSteps = sharedPreferences.getInt("LastSteps", 0);
            lastSavedTotalSteps = sharedPreferences.getInt("stepsSinceAppStarted", 0);

            if (sharedPreferences.getInt("InitialStepCount", -1) == -1) {
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putInt("InitialStepCount", currentTotalSteps);
                editor.apply();
            }
            int initialStepCount = sharedPreferences.getInt("InitialStepCount", 0);
            // 앱이 시작한 후의 실제 걸음수를 계산합니다.
            stepsSinceAppStarted = currentTotalSteps - initialStepCount;
            Log.d("stepsSinceAppStarted", "stepsSinceAppStarted : " + stepsSinceAppStarted);
            //CheckBattery();
            // 센서 리셋 감지 및 처리
            if (currentTotalSteps < lastSavedSteps) {
                lastSavedSteps = currentTotalSteps;
                lastSavedTotalSteps += lastSavedSteps;
            }

            int newSteps = stepsSinceAppStarted;//currentTotalSteps - lastSavedTotalSteps;
            int test = currentTotalSteps - lastSavedTotalSteps;
            Log.d("currentTotalSteps - lastSavedTotalSteps", "currentTotalSteps - lastSavedTotalSteps : " + test);

            totalSteps = lastSavedTotalSteps + newSteps;
            // 현재 시간과 날짜를 가져옵니다.
            Calendar calendar = Calendar.getInstance();
            String newDate = dateFormat.format(new Date());

            sharedPreferences.getInt("timeStep", stepsSinceAppStarted);
            // 날짜 변경 감지 및 처리
            if (!currentDate.equals(newDate)) {
                currentDate = newDate;
                todaySteps = 0;  // 새 날짜부터 걸음수를 0으로 초기화
                SetSaveStepHashMap();
                logSaveStepsPerHour();

                stepsPerHour.clear(); // 시간별 걸음수 데이터 초기화
                for (int i = 0; i < 24; i++) {
                    stepsPerHour.put(String.format(Locale.getDefault(), "%02d", i), 0);
                }
                lastStepCount = 0;
            } else if (currentTotalSteps > lastSavedSteps) {
                todaySteps += newSteps;
            }
            // 변경된 값 저장
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString("CurrentDate", currentDate);
            editor.putInt("LastSteps", currentTotalSteps);  // 현재 총 걸음수 저장
            editor.putInt("TodaySteps", todaySteps);
            editor.apply();
            // 현재 시간대의 걸음 수를 계산하고 저장합니다.

            int newHour = calendar.get(Calendar.HOUR_OF_DAY); // 24시간 형식의 현재 시간을 가져옵니다.

            if (currentHour != newHour) { // 현재 시간이 저장된 시간과 다르면
                Log.d("TimeCheck", "시간이 변경되었습니다. 현재 시간: " + newHour + "시");
                timeStep = sharedPreferences.getInt("timeStep", 0); // 현재 시간을 업데이트합니다.
            }

            Log.d("Time" , "currentHour : " + currentHour + "newHour : " + newHour);

            GetCurrentStep();
            stepsPerHour.put(hourKey, stepsSinceAppStarted);
            Log.d("tqtq Hash", "tqtq Hash : " + hourKey + "count : " + timeStep);
            saveStepsData();
            updateNotification(stepsSinceAppStarted);
            Log.d("lastSavedSteps 1111", "lastSavedSteps 1111 : " + lastSavedSteps);
            SetSaveStepHashMap();
            logSaveStepsPerHourContents();
        }
    }

    // 어제 날짜를 key 값으로 걸음수를 stepsPerHour 로 넣는 함수
    public void SetSaveStepHashMap()
    {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DATE, -1);
        String yesterdayDate = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(calendar.getTime());
        saveStepsPerHour.put(yesterdayDate,stepsPerHour);
    }
    public int GetCurrentStep()
    {
        return totalSteps;
    }
    // 없으면 에러발생
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not used
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public class LocalBinder extends Binder {
        public StepCounterService getService() {
            return StepCounterService.this;
        }
    }

    // 유니티에서 호출하는 함수
    public void triggerSensorCheck() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL);

        if (stepSensor != null) {
            sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL);
        } else {
        }
    }

    // notificationChannel 을 만드는 함수
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Step Counter Service Channel",
                    NotificationManager.IMPORTANCE_MIN
            );

            serviceChannel.enableVibration(false); // 진동 없음
            serviceChannel.setVibrationPattern(null); // 진동 패턴 없음
            serviceChannel.setShowBadge(false); // 알림 배지 숫자 표시하지 않음

            if (notificationManager != null) {
                notificationManager.createNotificationChannel(serviceChannel);
            } else {
                Log.e("test", "test NotificationManager not initialized.");
            }
        }
    }

    // 알림을 생성하고 업데이트하는 메서드, 알림의 아이콘 등 관리
    private Notification getNotification(int stepCount) {

        // 유니티 액티비티에 대한 인텐트 생성
        Intent intent = new Intent();
        intent.setClassName(getPackageName(), "com.unity3d.player.UnityPlayerActivity");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

// PendingIntent 생성
        PendingIntent pendingIntent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else {
            pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(stepCount + " 걸음")
                .setSmallIcon(R.drawable.realptlogo)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setVibrate(new long[]{0}) // 진동 없음
                .setShowWhen(false) // 알림에 시간 표시하지 않음
                .setNumber(0) // 알림 배지에 숫자를 표시하지 않음
                .setContentIntent(pendingIntent); // 인텐트 설정
        return builder.build();
    }

    // 서버안될때 saveStepsPerHour 의 key와 value 값 저장 log
    public void logSaveStepsPerHourContents() {
        for (Map.Entry<String, HashMap<String, Integer>> entry : saveStepsPerHour.entrySet()) {
            String dateKey = entry.getKey(); // 어제 날짜 (yesterdayDate)
            HashMap<String, Integer> dailySteps = entry.getValue(); // 해당 날짜의 stepsPerHour 맵

            Log.d("StepCounterService", "Date: " + dateKey); // 날짜 로그 출력

            for (Map.Entry<String, Integer> stepsEntry : dailySteps.entrySet()) {
                String hour = stepsEntry.getKey(); // 시간대
                Integer steps = stepsEntry.getValue(); // 그 시간대의 걸음 수

                Log.d("StepCounterService 121212", "Date1212 : " +dateKey + " Hour 1212 : " + hour + ", Steps 1212: " + steps); // 시간대별 걸음 수 로그 출력
            }
        }
    }
    // 유니티에서 호출되는 함수
    public String getStepsPerHourJson() {
        Gson gson = new Gson();
        try {
            // stepsSinceAppStarted
            String json = gson.toJson(stepsPerHour);
            Log.e("JSON000", "test json : " + json);
            return json;
        } catch (Exception e) {
            Log.e("test", "Error while converting stepsPerHour to JSON: " + e.getMessage());
            return "{}";
        }
    }

    // Step 들을 저장하는 함수
    private void saveStepsData() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt("TotalSteps", totalSteps);
        editor.putInt("stepsSinceAppStarted", stepsSinceAppStarted);
        editor.putInt("LastStepCount", lastStepCount);
        editor.putInt("LastHour", lastHour);
        editor.putString("CurrentDate", currentDate);

        Gson gson = new Gson();
        String stepsPerHourJson = gson.toJson(stepsPerHour);
        editor.putString("StepsPerHour", stepsPerHourJson);

        editor.apply();
    }

    @Override
    public void onDestroy() {
        // 서비스 종료 전에 알림 업데이트
        updateNotification(stepsSinceAppStarted);

        Intent restartServiceIntent = new Intent(getApplicationContext(), this.getClass());
        restartServiceIntent.setPackage(getPackageName());

        PendingIntent restartServicePendingIntent = PendingIntent.getService(getApplicationContext(), 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT);
        AlarmManager alarmService = (AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
        alarmService.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 1000,
                restartServicePendingIntent);

        super.onDestroy();

        // 서비스가 종료될 때 ExecutorService를 종료하여 백그라운드 작업을 중단합니다.
        executorService.shutdownNow();
        // Handler에서 모든 콜백과 메시지를 제거하여 메모리 누수를 방지합니다.
        uiHandler.removeCallbacksAndMessages(null);

        saveStepsData();
    }

    // 알림을 업데이트하는 메서드
    public void updateNotification(int stepCount) {

        Notification notification = getNotification(stepCount);
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, notification);
        } else {
            Log.e("test", "test NotificationManager is not initialized when trying to update notification.");
        }
    }

    // 오늘 날짜를 yyyyMMdd 형식의 Key값으로 걸을수 HashMap(saveStepsPerHour) 을 Value로 저장시켜주는 함수
    public void logSaveStepsPerHour() {
        for (Map.Entry<String, HashMap<String, Integer>> outerEntry : saveStepsPerHour.entrySet()) {
            String date = outerEntry.getKey(); // 날짜(외부 HashMap의 키)
            HashMap<String, Integer> hourlySteps = outerEntry.getValue(); // 해당 날짜의 시간대별 걸음수(내부 HashMap)

            for (Map.Entry<String, Integer> innerEntry : hourlySteps.entrySet()) {
                String hour = innerEntry.getKey(); // 시간대
                Integer steps = innerEntry.getValue(); // 해당 시간대의 걸음수
            }
        }
    }

    private void scheduleRegularCheck() {
        // 30분마다 실행
        long delay = 30 * 60 * 1000;
        scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                int totalHourlySteps = 0;
                for (Integer steps : stepsPerHour.values()) {
                    totalHourlySteps += steps;
                }

                if (todaySteps != totalHourlySteps) {
                    todaySteps = totalHourlySteps;

                    // 변경된 todaySteps 저장
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putInt("TodaySteps", todaySteps);
                    editor.apply();
                }
            }
        }, 0, delay, TimeUnit.MILLISECONDS);
    }
}
