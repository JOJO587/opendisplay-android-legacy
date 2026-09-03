package org.opendisplay.legacy;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

/**
 * mDNS 广播：让 Mac 上的 OpenDisplay 能在下拉列表里发现这台设备。
 *
 * 规范 2.1 / 6.1：
 *  - 服务类型固定是 _opensidecar._tcp（历史遗留，不能改）
 *  - TXT 里带 id（稳定 UUID）、pv（协议版本）、sig（固定 OpenDisplay，
 *    让 Mac 把这条 Bonjour 命中当作已签名验证，参考 gprot42/josepacelli）
 *  - 名字只是给人看的，不能当设备身份用
 *
 * NsdManager 从 API 16 就有，API 23 完全可用。
 */
public class NsdAdvertiser {

    private static final String TAG = "ODNsd";

    private final NsdManager nsd;
    private final String serviceName;
    private final String id;
    private NsdManager.RegistrationListener listener;
    private boolean registered = false;
    /**
     * 注册请求已发出、回调还没回来。registered 是异步回调才置位的，
     * 加上这个标记避免 start() 被连续调用时重复 registerService
     * （重复注册会以 NAME_CONFLICT 失败，虽不崩但会污染日志）。
     * 由 NsdManager 回调线程写、调用方线程读，须 volatile。
     */
    private volatile boolean registerPending = false;

    public NsdAdvertiser(Context ctx, String serviceName, String id) {
        this.nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        this.serviceName = serviceName;
        this.id = id;
    }

    public void start() {
        if (nsd == null || registered || registerPending) return;
        registerPending = true;

        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(serviceName);
        info.setServiceType(Protocol.SERVICE_TYPE);
        info.setPort(Protocol.PORT);
        // TXT：id 必须与 hello 里的 id 一致；pv 让发送端提前判断兼容性；
        // sig=OpenDisplay 让 Mac 把这条命中当作已签名验证（参考实现一致做法）
        info.setAttribute("id", id);
        info.setAttribute("pv", String.valueOf(Protocol.PV));
        info.setAttribute("sig", "OpenDisplay");

        listener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo si) {
                Log.i(TAG, "registered: " + si.getServiceName());
                registered = true;
                registerPending = false;
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo si, int errorCode) {
                Log.w(TAG, "register failed: " + errorCode);
                registered = false;
                registerPending = false; // 允许后续重试
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo si) {
                registered = false;
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo si, int errorCode) {
                Log.w(TAG, "unregister failed: " + errorCode);
            }
        };

        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (Exception e) {
            Log.e(TAG, "registerService threw", e);
            // 同步抛出时不会有任何回调，必须复位，否则 registerPending 卡死、
            // 之后所有 start() 都被挡住（广播从此再也起不来）
            registerPending = false;
        }
    }

    public void stop() {
        if (nsd == null || !registered) return;
        try {
            nsd.unregisterService(listener);
        } catch (Exception e) {
            Log.w(TAG, "unregister threw", e);
        }
        registered = false;
    }
}
