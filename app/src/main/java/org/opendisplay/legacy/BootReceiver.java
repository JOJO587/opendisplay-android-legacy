package org.opendisplay.legacy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启 + EMUI 异常重启场景兜底。
 *
 * 触发场景（覆盖荣耀平板2 / EMUI 4.0-4.1 上能遇到的所有）：
 *   - 正常开机：BOOT_COMPLETED
 *   - 华为"快速启动"唤醒：QUICKBOOT_POWERON
 *   - OTA / 卡刷后首次启动（部分 EMUI 版本）
 *   - 应用被"自启动管理"恢复后回到前台（华为管家偶尔会重启）
 *
 * 设计取舍：
 *   - 不在 LOCKED_BOOT_COMPLETED 启动（直接启动阶段，NsdManager 还不可用）
 *   - 始终以 ADB 模式启动（默认 wifi_discovery=false），保证最稳定的链路
 *   - 不在主线程做事，startService 由系统调度
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "ODBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        // 过滤无关广播，只在真正启动事件触发
        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
            case "android.intent.action.QUICKBOOT_POWERON":
            case "com.htc.intent.action.QUICKBOOT_POWERON":
            case "android.intent.action.LOCKED_BOOT_COMPLETED":
            case "android.intent.action.MY_PACKAGE_REPLACED": // 自身更新后
                Log.i(TAG, "boot/restart signal: " + action);
                Intent svc = new Intent(context, ReceiverService.class)
                        .setAction(ReceiverService.ACTION_START)
                        // ADB 模式优先：设备端不广播 mDNS，避免和 Mac 本地代理冲突
                        .putExtra(ReceiverService.EXTRA_WIFI_DISCOVERY, false);
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(svc);
                    } else {
                        context.startService(svc);
                    }
                    Log.i(TAG, "ReceiverService start requested");
                } catch (Exception e) {
                    Log.e(TAG, "startService failed", e);
                }
                break;
            default:
                // 其它广播不处理
                break;
        }
    }
}
