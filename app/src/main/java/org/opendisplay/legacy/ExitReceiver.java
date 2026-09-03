package org.opendisplay.legacy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 通知栏「退出」动作的接收者（在 AndroidManifest 中静态注册，进程级可达）。
 *
 * 收到 ACTION_EXIT 后：
 *  1) 停止前台接收服务 —— 通知随之消失，用户感知即"整体退出"；
 *  2) 通过 finish extra 唤起 MainActivity 并关闭它（清空任务栈）。
 */
public class ExitReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ReceiverService.ACTION_EXIT.equals(intent.getAction())) {
            return;
        }
        context.stopService(new Intent(context, ReceiverService.class)
                .setAction(ReceiverService.ACTION_STOP));

        Intent it = new Intent(context, MainActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        it.putExtra("finish", true);
        context.startActivity(it);
    }
}
