package app.emberglass;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Keeps the process, the CPU and Wi-Fi alive while monitoring, so logging and the web server
 * keep running with the screen off. The notification shows the live reading and a stop action.
 */
public class MonitorService extends Service {
    private static final String CHANNEL = "monitor", STOP = "app.emberglass.STOP_MONITOR";
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        Engine e = Engine.get(this);
        if (intent != null && STOP.equals(intent.getAction())) {   // Stop in the notification ends both jobs
            e.applySetting("backgroundStream", "0", true);
            e.stopMonitoring();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!e.monitor.active() && !e.backgroundStream) { stopSelf(); return START_NOT_STICKY; }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Monitoring", NotificationManager.IMPORTANCE_LOW));
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, build(e), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(1, build(e));
        if (wake == null) {
            wake = ((PowerManager) getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "emberglass:monitor");
            wake.acquire();
            wifi = ((WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE))
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "emberglass:monitor");
            wifi.acquire();
            h.post(update);
        }
        return START_STICKY;
    }

    private final Runnable update = new Runnable() {
        @Override public void run() {
            Engine e = Engine.get(MonitorService.this);
            getSystemService(NotificationManager.class).notify(1, build(e));
            h.postDelayed(this, 10_000);
        }
    };

    private Notification build(Engine e) {
        Thermal th = e.th;
        String text = th.warming || th.frames == 0 ? e.status
                : (th.alarm ? "ALARM. " : "") + "Max " + th.fmt(th.max) + ", min " + th.fmt(th.min)
                + (e.monitor.active() ? ", " + e.monitor.rows() + " samples" : "");
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        int imm = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), imm);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, MonitorService.class).setAction(STOP), imm);
        return b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(e.monitor.active() ? "Emberglass monitoring" : "Emberglass streaming")
                .setContentText(text + "  http://" + Engine.ip() + ":" + Engine.PORT)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }

    @Override public void onDestroy() {
        h.removeCallbacks(update);
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        super.onDestroy();
    }
}
