package io.nervous.sourcesync;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.widget.TextView;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private TextView status;
    private volatile String progress;   // 实时进度（来自 WorkInfo.progress）
    private final Handler poll = new Handler();

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            try {
                WorkManager wm = WorkManager.getInstance(MainActivity.this);
                String found = null;
                for (String name : new String[]{"manual", "daily"}) {
                    List<WorkInfo> list = wm.getWorkInfosForUniqueWork(name).get();
                    if (list != null && !list.isEmpty()) {
                        WorkInfo wi = list.get(0);
                        if (wi.getState() == WorkInfo.State.RUNNING) {
                            String m = wi.getProgress().getString("msg");
                            found = (m == null ? "正在同步…" : m);
                            break;
                        }
                    }
                }
                progress = found;   // null = 没有任务在跑，显示历史结果
            } catch (Exception ignored) {}
            refresh();
            poll.postDelayed(this, 1000);
        }
    };

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
        android.content.SharedPreferences sp = getSharedPreferences("sync", MODE_PRIVATE);
        String last = sp.getString("last", "尚未同步，点上方按钮试试");
        StringBuilder hist = new StringBuilder();
        String old = sp.getString("hist", null);
        if (old != null) {
            for (String s : old.split("\n")) {
                if (s.isEmpty()) continue;
                // 兼容旧版长文案：单行超40字截断，避免界面堆叠
                if (s.length() > 40) s = s.substring(0, 40) + "…";
                hist.append('\n').append(s);
            }
        }
        String progLine = (progress == null) ? "" : "\n▶ " + progress + "\n";
        status.setText(
                "书源自动同步 v3.13" + progLine +
                "\n上次：" + last + "\n" +
                (hist.length() == 0 ? "" : "\n最近记录：" + hist + "\n") +
                "\n每周一凌晨5点自动同步（错过自动补跑）· 镜像自动切换 · 全程验签");
    }

    @Override
    protected void onResume() {
        super.onResume();
        poll.postDelayed(pollTask, 200);
    }

    @Override
    protected void onPause() {
        super.onPause();
        poll.removeCallbacks(pollTask);
    }
}
