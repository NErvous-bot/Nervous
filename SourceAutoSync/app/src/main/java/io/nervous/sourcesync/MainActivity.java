package io.nervous.sourcesync;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);

        // manual sync button (ignores schedule, syncs immediately)
        findViewById(R.id.syncNow).setOnClickListener(v -> {
            WorkManager.getInstance(this).enqueueUniqueWork("manual",
                    ExistingWorkPolicy.REPLACE,
                    new OneTimeWorkRequest.Builder(SyncWorker.class)
                            .addTag("manual")
                            .setInputData(new androidx.work.Data.Builder()
                                    .putString("mode", "manual").build())
                            .build());
            refresh();
        });

        // runs every 24h but only syncs inside Monday 5-11am window
        // (or if last successful sync was >13 days ago); MD5-skip keeps it cheap
        androidx.work.Constraints cons = new androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("daily",
                ExistingPeriodicWorkPolicy.KEEP,
                new PeriodicWorkRequest.Builder(SyncWorker.class, 24, TimeUnit.HOURS)
                        .setInitialDelay(delayUntilNext5am(), TimeUnit.MILLISECONDS)
                        .setConstraints(cons)
                        .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL,
                                30, TimeUnit.MINUTES)
                        .build());

        refresh();
    }

    /** milliseconds until next 5:00 am */
    private static long delayUntilNext5am() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 5);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }
        return cal.getTimeInMillis() - System.currentTimeMillis();
    }

    private void refresh() {
        String last = getSharedPreferences("sync", MODE_PRIVATE)
                .getString("last", "从未同步（安装后打开一次即注册每日任务）");
        status.setText(
                "书源自动同步 v3.4\n\n" +
                "写入目标：阅读App（com.legado.app.release）\n" +
                "更新频率：每周一凌晨5点自动同步，超13天未同步自动补跑\n" +
                "同步内容：书源 + 全局净化规则\n" +
                "镜像线路：云端自动获取，线路失效自动切换\n\n" +
                "上次同步结果：\n" + last + "\n\n" +
                "提示：请在系统设置中允许本App「后台运行/自启动」，否则定时任务可能被省电机制拦截。\n" +
                "若错过周一窗口（关机/断网），随时手动点「立即同步」补一次。");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }
}
