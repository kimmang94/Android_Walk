package com.defaultcompany.stepcountplugin;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

public class BootCompletedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d("test", "Test Boot completed received, starting service...");

            // 부팅 완료 이벤트가 수신되면 StepCounterService를 시작합니다.
            Intent serviceIntent = new Intent(context, StepCounterService.class);


            // Android 8.0 (API level 26) 이상에서는 startForegroundService를 사용하여 포그라운드 서비스를 시작합니다.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                // Android 8.0 미만에서는 startService를 사용합니다.
                context.startService(serviceIntent);
            }

        }
    }
}
