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
    public static boolean isSuccess ;
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

    //private KeyFetcher keyFetcher;
    public int lastSavedTotalSteps;
    Calendar calendar;
    private long lastUpdate = 0;
    private int lastCount = 0;
    private final int MIN_STEPS = 5; // 최소 변화량
    private final long MIN_TIME = 10000; // 최소 시간 간격 (10초)

    @Override
    public void onCreate() {

        super.onCreate();
        Log.d("OnCreate", "OnCreate");
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
        startForeground(NOTIFICATION_ID, getNotification(totalSteps));

        NotificationManagerCompat notificationManagerCompat = NotificationManagerCompat.from(this);
        boolean areNotificationsEnabled = notificationManagerCompat.areNotificationsEnabled();

        // ExecutorService와 Handler를 초기화합니다.
        executorService = Executors.newSingleThreadExecutor();
        uiHandler = new Handler(Looper.getMainLooper());

        // 키 가져오기 작업을 정기적으로 실행합니다.
        //scheduleKeyFetching();

        // ScheduledExecutorService 초기화
        scheduledExecutorService = Executors.newScheduledThreadPool(1);

        // 정기적인 체크 시작
        scheduleRegularCheck();
    }
    // HashMap 에 기존 걸음수 데이터 저장

    // 배터리 제한없음 확인 함수
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

    public static boolean isIgnoringBatteryOptimizations(Context context) {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            return powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
        }
        return true; // API 레벨 23 미만에서는 항상 true 반환
    }

    // saveStepsPerHour 에 저장된 HashMap 값을 Json 으로 유니티에서 호출하게끔 하는 함수
    public String getSaveStepsPerHourJson() {
        Gson gson = new Gson();
        return gson.toJson(saveStepsPerHour);
    }
    public void resetStepsPerHour() {
        // 'stepsPerHour' 맵을 초기화하여 새로운 날의 데이터 수집을 준비
        for (int i = 0; i < 24; i++) {
            stepsPerHour.put(String.format(Locale.getDefault(), "%02d", i), 0);
        }
    }
    /*
    private void scheduleKeyFetching() {
        // ExecutorService와 Handler를 초기화합니다.
        if (executorService == null || executorService.isShutdown()) {
            executorService = Executors.newSingleThreadExecutor();
        }
        if (uiHandler == null) {
            uiHandler = new Handler(Looper.getMainLooper());
        }
        /*
        //자정에 랜덤한 초로 서버에 보내는 부분
        // 현재 시간 및 다음 정시까지 남은 시간 계산
        Calendar calendar = Calendar.getInstance();
        long currentTimeMillis = System.currentTimeMillis();
        long delayUntilNextHour = 3600000 - (calendar.get(Calendar.MINUTE) * 60000 + calendar.get(Calendar.SECOND) * 1000 + calendar.get(Calendar.MILLISECOND));
        int randomSeconds = (int) (Math.random() * 60); // 0초에서 60초 사이의 랜덤 숫자
        long totalDelay = delayUntilNextHour + randomSeconds * 1000;


        // 정각마다 랜덤한 초로 서버에 보내는 부분
        // 현재 시간 및 다음 정각까지 남은 시간 계산

        long delayUntilNextHour = 3600000 - (calendar.get(Calendar.MINUTE) * 60000 + calendar.get(Calendar.SECOND) * 1000 + calendar.get(Calendar.MILLISECOND));
        int randomSeconds = (int) (Math.random() * 60); // 0초에서 60초 사이의 랜덤 숫자
        //long totalDelay = delayUntilNextHour + randomSeconds * 10000;
        long totalDelay = 60000;
        //long delay = 10000; // 10초

       uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // KeyFetcher의 getKey 메서드를 실행합니다.T
                new KeyFetcher().getKey(new KeyFetcher.KeyFetchCallback() {

                    @Override
                    public void onSuccess(String key, String message) {
                        // 성공시 처리 로직

                        //Log.d("TAG", "test Key: " + key + ", Message: " + message);
                        // UI 스레드에서 실행할 작업을 Handler를 통해 전달합니다.
                        uiHandler.post(() -> {
                            // UI 스레드에서 키 가져오기 작업의 결과를 처리합니다.
                            Log.d("StepCounterService", "test Key fetch operation succeeded, key: " + key);
                        });
                    }

                    @Override
                    public void onFailure(Exception e) {
                        // 실패시 처리 로직
                        //Log.e("TAG", "test Error fetching key", e);
                        // UI 스레드에서 실행할 작업을 Handler를 통해 전달합니다.
                        uiHandler.post(() -> {
                            // UI 스레드에서 키 가져오기 작업의 오류를 처리합니다.
                            //Log.e("StepCounterService", "test Key fetch operation failed");
                        });
                    }
                }, StepCounterService.this); // 수정된 부분
                Log.d("StepCounterService", "test 11111111 : " + totalDelay);
                // 다음 정각 + 랜덤 초 후에 다시 이 메서드를 실행합니다.
                scheduleKeyFetching();
            }
        }, totalDelay );
    }
       */
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
        updateNotification(todaySteps);

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
        Log.d("onSensorChanged", "onSensorChanged");
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
        String todayDateKey = dateFormat.format(new Date());


        if (event.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
            int currentTotalSteps = (int) event.values[0];
            int lastSavedSteps = sharedPreferences.getInt("LastSteps", 0);
            lastSavedTotalSteps = sharedPreferences.getInt("TotalSteps", 0);
            long currentTime = System.currentTimeMillis();
            int stepsChange = currentTotalSteps - lastCount;
            long timeDifference = currentTime - lastUpdate;

            Log.d("currentTotalSteps", "currentTotalSteps : " + currentTotalSteps);
            Log.d("lastSavedSteps", "lastSavedSteps : " + lastSavedSteps);
            Log.d("lastSavedTotalSteps", "lastSavedTotalSteps : " + lastSavedTotalSteps);
            Log.d("currentTotalSteps", "currentTotalSteps : " + currentTotalSteps);
            Log.d("stepsChange", "stepsChange : " + stepsChange);

            if (stepsChange >= MIN_STEPS && timeDifference >= MIN_TIME) {
                // 유효한 변화: 걸음수 업데이트 및 시간 갱신
                updateSteps(currentTotalSteps);
                lastUpdate = currentTime;
                lastCount = currentTotalSteps;
            }
            //CheckBattery();
            // 센서 리셋 감지 및 처리
            if (currentTotalSteps < lastSavedSteps) {
                lastSavedSteps = currentTotalSteps;
                lastSavedTotalSteps += lastSavedSteps;
            }

            int newSteps = currentTotalSteps - lastSavedSteps;
            Log.d("newSteps", "newSteps : " + newSteps);

            totalSteps = lastSavedTotalSteps + newSteps;
            Log.d("totalSteps", "newSteps : " + newSteps);
            // 현재 시간과 날짜를 가져옵니다.
            Calendar calendar = Calendar.getInstance();
            String newDate = dateFormat.format(new Date());

            // 날짜 변경 감지 및 처리
            if (!currentDate.equals(newDate)) {

                currentDate = newDate;
                todaySteps = 0;  // 새 날짜부터 걸음수를 0으로 초기화
                SetSaveStepHashMap();
                logSaveStepsPerHour();
                logStepsPerHour();
                stepsPerHour.clear(); // 시간별 걸음수 데이터 초기화
                for (int i = 0; i < 24; i++) {
                    stepsPerHour.put(String.format(Locale.getDefault(), "%02d", i), 0);
                }
                lastSavedSteps = 0;
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
            String hourKey = String.format(Locale.getDefault(), "%02d", calendar.get(Calendar.HOUR_OF_DAY));
            int stepsThisHour = stepsPerHour.getOrDefault(hourKey, 0) + newSteps;
            GetCurrentStep();
            stepsPerHour.put(hourKey, stepsThisHour);

            saveStepsData();
            updateNotification(todaySteps);

        }
    }
    private void updateSteps(int newSteps) {
        // 걸음수를 업데이트하는 로직
        // 예: UI 업데이트, SharedPreferences에 저장 등
        totalSteps = newSteps; // 예시 업데이트 방법
        // 필요한 추가 작업 수행...
    }

    public void handleLoginFailure() {

        SetSaveStepHashMap();
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
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not used
    }

    public int getTotalSteps() {
        return todaySteps; // 하루 동안의 발걸음수를 반환
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
    /*
    public void userTest(String userID, String userPW)
    {
        KeyFetcher.name = userID;
        KeyFetcher.pw = userPW;
    }
*/
    public void triggerSensorCheck() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL);

        if (stepSensor != null) {
            sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL);
        } else {
        }
    }

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

    // 알림을 생성하고 업데이트하는 메서드
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

    public HashMap<String, Integer> getStepsPerHour() {
        return stepsPerHour;
    }

    public String getStepsPerHourJson() {
        Gson gson = new Gson();
        try {
            String json = gson.toJson(stepsPerHour);
            Log.e("JSON000", "test json : " + json);
            return json;
        } catch (Exception e) {
            Log.e("test", "Error while converting stepsPerHour to JSON: " + e.getMessage());
            return "{}";
        }
    }
    private void loadStepsData() {
        totalSteps = sharedPreferences.getInt("TotalSteps", 0);
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

    private void saveStepsData() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt("TotalSteps", totalSteps);
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
        updateNotification(todaySteps);

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

    private void sendStepsMessage(int hour, Integer steps) {
        if (steps == null) {
            steps = 0;
        }
        String message = "Hour: " + hour + ", Steps: " + steps;
    }

    private void saveLastSteps(int lastSteps) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt("LastSteps", lastSteps);
        editor.apply();
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
    // HashMap stepsPerHour 의 key value log를 찍는 함수
    public void logStepsPerHour() {
        for (Map.Entry<String, Integer> entry : stepsPerHour.entrySet()) {
            String hour = entry.getKey();
            Integer steps = entry.getValue();
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
