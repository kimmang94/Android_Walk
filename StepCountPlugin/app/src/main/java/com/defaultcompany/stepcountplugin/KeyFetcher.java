/*package com.defaultcompany.stepcountplugin;

import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Calendar;
import java.util.Locale;
import java.text.SimpleDateFormat;

public class KeyFetcher {
    private static final String TAG = "KeyFetcher";
    private static final String getUserSessionURL = "https://test.realpt.co.kr/api/Enter/GetUserSession";
    private static final String loginURL = "https://test.realpt.co.kr/api/Enter/Login";
    private static final String RecordDailyStepsURL = "https://test.realpt.co.kr/api/mobile/MobileWalking/RecordDailySteps";

    private static final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private static final Handler handler = new Handler(Looper.getMainLooper());
    public static String name;
    public static String pw;

    public interface KeyFetchCallback {
        void onSuccess(String key, String message);
        void onFailure(Exception e);
    }


    public interface LoginFetchCallback {
        void onSuccess(JSONObject response);
        void onFailure(Exception e);
    }

    public void getKey(KeyFetchCallback callback, StepCounterService stepCounterService) {
        executorService.execute(() -> {
            String data = "{}";
            try {
                URL url = new URL(getUserSessionURL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("Content-Type", "text/json");
                connection.setDoOutput(true);

                OutputStream os = connection.getOutputStream();
                byte[] input = data.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
                os.close();

                int responseCode = connection.getResponseCode();
                Log.i(TAG, "test Response Code: " + responseCode);

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                    br.close();

                    JSONObject jsonObject = new JSONObject(response.toString());
                    String key = jsonObject.getString("key");
                    String message = jsonObject.getString("message");
                    boolean isSuccess = jsonObject.getBoolean("isSuccess");

                    if (isSuccess) {
                        handler.post(() -> {
                            callback.onSuccess(key, message);
                            // Login 메서드 호출
                            login(key, "test", name, new LoginFetchCallback() {

                                @Override
                                public void onSuccess(JSONObject response) {
                                    // 성공 처리 로직
                                    Log.d("test", "Success KeyFetcherID : " + name);
                                    Log.d("test", "Success KeyFetcherPW : " + pw);
                                    Log.d("20240318", "20240318");
                                    StepCounterService.isSuccess = true;
                                }

                                @Override
                                public void onFailure(Exception e) {
                                    // 실패 처리 로직
                                    Log.d("test", "Failed KeyFetcherID : " + name);
                                    Log.d("test", "Failed KeyFetcherPW : " + pw);
                                    StepCounterService.isSuccess = false;
                                }
                            }, stepCounterService); // StepCounterService 객체 전달
                        });
                    } else {
                        handler.post(() -> callback.onFailure(new Exception("Key fetch failed")));
                    }
                } else {
                    handler.post(() -> callback.onFailure(new Exception("HTTP error code: " + responseCode)));
                }

                connection.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "test Exception in getKey", e);
                handler.post(() -> callback.onFailure(e));
            }
        });
    }

    public void login(String key, String serial, String phone, LoginFetchCallback callback, StepCounterService stepCounterService) {
        executorService.execute(() -> {
            String data = "{\"key\":\"" + key + "\","
                    + "\"serial\":\"" + serial + "\","
                    + "\"phone\":\"" + phone + "\"}";
            try {
                URL url = new URL(loginURL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("Content-Type", "text/json");
                connection.setDoOutput(true);

                OutputStream os = connection.getOutputStream();
                byte[] input = data.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
                os.close();

                int responseCode = connection.getResponseCode();
                Log.i(TAG, "test Login Response Code: " + responseCode);

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                    br.close();

                    JSONObject jsonObject = new JSONObject(response.toString());
                    handler.post(() -> {
                        if (callback != null) {
                            callback.onSuccess(jsonObject);
                        }
                    });

                    // 성공 후에 recordDailySteps 메소드 호출
                    //recordDailySteps(key, stepCounterService);

                } else {
                    handler.post(() -> {
                        if (callback != null) {
                            callback.onFailure(new Exception("test Login HTTP error code: " + responseCode));
                        }
                    });
                }
                connection.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Exception in login", e);
                handler.post(() -> {
                    if (callback != null) {
                        callback.onFailure(e);
                    }
                });
            }
        });
    }
/*
    private void recordDailySteps(String key, StepCounterService stepCounterService) {
        executorService.execute(() -> {
            try {
                URL url = new URL(RecordDailyStepsURL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                Log.d("record", "record 1");
                // 날짜 설정
                Calendar calendar = Calendar.getInstance();
                calendar.add(Calendar.DAY_OF_MONTH, -1); // 하루 전 날짜
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()); // 포맷 변경
                String recordDate = dateFormat.format(calendar.getTime());

                // 데이터 생성
                JSONObject data = new JSONObject();
                data.put("key", key);
                data.put("userId", 0); // 여기서 적절한 사용자 ID로 변경
                data.put("recordDate", recordDate);
                data.put("dailySteps", new JSONObject(stepCounterService.getStepsPerHourJson())); // JSON 문자열을 JSONObject로 변환
                Log.d("record", "record 2");
                Log.i(TAG, "test data: " + data.toString());

                // 데이터 전송
                OutputStream os = connection.getOutputStream();
                byte[] input = data.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
                os.close();
                Log.d("record", "record 3");
                // 응답 처리
                int responseCode = connection.getResponseCode();
                Log.i(TAG, "test RecordDailySteps Response Code: " + responseCode);

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                    br.close();

                    // 성공 처리 로직 (필요에 따라 작성)
                    Log.i(TAG, "RecordDailySteps success: " + response.toString());
                } else {
                    // 실패 처리 로직
                    Log.e(TAG, "RecordDailySteps failed with HTTP error code: " + responseCode);
                    stepCounterService.handleLoginFailure();
                }
                connection.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Exception in recordDailySteps", e);
            }
        });
    }


}
*/