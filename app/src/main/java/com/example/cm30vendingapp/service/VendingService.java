package com.example.cm30vendingapp.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.cm30vendingapp.R;
import com.example.cm30vendingapp.VendingEvents;
import com.example.cm30vendingapp.util.LoggerHelper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.hardware.cashless.CashlessManager;
import android.hardware.cashless.ICashlessEventMonitor;
import android.hardware.mdbSlave.MdbSlave;
import android.util.Log;

import pos.paylib.posPayKernel;
import com.ciontek.hardware.aidl.readcard.ReadCardOptV2;
import com.ciontek.hardware.aidl.readcard.CheckCardCallbackV2;
import com.ciontek.hardware.aidl.emv.EMVOptV2;

/**
 * VendingService: listens for vending machine requests, triggers card read + payment,
 * and responds to the VMC via CashlessManager + MDB.
 *
 * Card types handled: MAGSTRIPE | ICC (chip) | NFC (contactless)
 */
public class VendingService extends Service {
    private static final String TAG = "VendingService";
    private static final String CHANNEL_ID = "cm30_vending_channel";

    private CashlessManager cashlessManager;
    private MdbSlave mdbSlave;
    private final ExecutorService vendExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean running = false;

    private posPayKernel payKernel;
    private ReadCardOptV2 readCardOpt;
    private EMVOptV2 emvOpt;

    private static final int CARD_MAGNETIC = 1;
    private static final int CARD_IC = 2;
    private static final int CARD_NFC = 4;
    private static final int CARD_ALL = CARD_MAGNETIC | CARD_IC | CARD_NFC;
    private static final int CHECK_CARD_TIMEOUT_SEC = 60;

    @Override
    public void onCreate() {
        super.onCreate();
        LoggerHelper.log(TAG, "Service onCreate");
        createForegroundNotification();

        initCashlessManager();
        initPayKernel();
        initMdbSlave();

        running = true;
        startMdbReceiveLoop();
    }

    private void createForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "CM30 Vending Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Notification channel for CM30 vending service");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Vending service running")
                .setContentText("Listening for vend requests...")
                .setSmallIcon(R.drawable.ic_vending)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            startForeground(1337, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(1337, notification);
        }

        LoggerHelper.log(TAG, "Foreground notification created");
    }

    private void initCashlessManager() {
        cashlessManager = CashlessManager.getInstance();
        if (cashlessManager == null) {
            LoggerHelper.log(TAG, "CashlessManager instance is null");
            return;
        }

        cashlessManager.setConfiguration(3, 840, 100, 2, 30, 0);
        cashlessManager.setLogLevel(3);

        try {
            cashlessManager.registerMonitor(new ICashlessEventMonitor.Stub() {
                @Override
                public void onInitialComplete(byte[] cashlessInfo, byte[] vmcInfo) {
                    LoggerHelper.log(TAG, "Cashless initial complete");
                    sendStatus(VendingEvents.EVENT_ONLINE);
                }

                @Override
                public void onVendRequest(byte[] data) {
                    LoggerHelper.log(TAG, "Cashless vend request received");
                    vendExecutor.submit(() -> handleVendRequest(data));
                }

                @Override
                public void onVendCancel() {
                    LoggerHelper.log(TAG, "Cashless vend canceled");
                    sendStatus(VendingEvents.EVENT_PAYMENT_FAILED);
                }

                @Override
                public void onVendSuccess(byte[] data) {
                    LoggerHelper.log(TAG, "Cashless vend successful");
                    sendStatus(VendingEvents.EVENT_PAYMENT_SUCCESS);
                }

                @Override
                public void onVendFailure(byte[] data) {
                    LoggerHelper.log(TAG, "Cashless vend failed");
                    sendStatus(VendingEvents.EVENT_PAYMENT_FAILED);
                }

                @Override public void onReset() {}
                @Override public void onSetupMaxMinPrices(byte[] data) {}
                @Override public void onSessionComplete() {}
                @Override public void onCashSale(byte[] data) {}
                @Override public void onNegativeVendRequest(byte[] data) {}
                @Override public void onSelectionDenied(byte[] data) {}
                @Override public void onCouponReply(byte[] data) {}
                @Override public void onReaderDisable() {}
                @Override public void onReaderEnable() {}
                @Override public void onReaderCancel() {}
                @Override public void onReaderDataEntryResponse(byte[] data) {}
                @Override public void onRevalueRequest(byte[] data) {}
                @Override public void onRevalueLimitRequest() {}
                @Override public void onSyncTimeDate(byte[] data) {}
                @Override public void onDiagnostics(byte[] data) {}
            });
            LoggerHelper.log(TAG, "Cashless monitor registered");
        } catch (Exception e) {
            LoggerHelper.log(TAG, "Failed to register Cashless monitor: " + Log.getStackTraceString(e));
        }
    }

    private void initPayKernel() {
        payKernel = posPayKernel.getInstance();
        boolean ok = payKernel.initPaySDK(getApplicationContext(), new posPayKernel.ConnectCallback() {
            @Override
            public void onConnectPaySDK() {
                LoggerHelper.log(TAG, "Pay SDK connected");
                readCardOpt = payKernel.mReadcardOpt;
                emvOpt = payKernel.mEmvOpt;

                LoggerHelper.log(TAG, "readCardOpt ready: " + (readCardOpt != null));
                LoggerHelper.log(TAG, "emvOpt ready: " + (emvOpt != null));
            }

            @Override
            public void onDisconnectPaySDK() {
                LoggerHelper.log(TAG, "Pay SDK disconnected");
                readCardOpt = null;
                emvOpt = null;
            }
        });
        LoggerHelper.log(TAG, "initPaySDK bind result: " + ok);
    }

    private void initMdbSlave() {
        mdbSlave = MdbSlave.getInstance();
        int openResult = mdbSlave.open();
        LoggerHelper.log(TAG, "MDB Slave open result: " + openResult);
    }

    private void startMdbReceiveLoop() {
        vendExecutor.submit(() -> {
            byte[] cmdBuffer = new byte[256];
            while (running) {
                int result = mdbSlave.receiveCommand(cmdBuffer);
                if (result == MdbSlave.SUCCESS) {
                    handleMdbCommand(cmdBuffer);
                } else {
                    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                }
            }
        });
        LoggerHelper.log(TAG, "MDB receive loop started");
    }

    private void handleVendRequest(byte[] vendPayload) {
        LoggerHelper.log(TAG, "handleVendRequest: starting card check");

        if (readCardOpt == null) {
            LoggerHelper.log(TAG, "readCardOpt is null — cannot check card");
            if (cashlessManager != null) cashlessManager.sendVendDenied();
            sendStatus(VendingEvents.EVENT_PAYMENT_FAILED);
            return;
        }

        double amount = parseAmountFromVendPayload(vendPayload);
        LoggerHelper.log(TAG, "Vend amount parsed: " + amount);
        sendVendStarted(amount);

        try {
            readCardOpt.checkCard(CARD_ALL, new CheckCardCallbackV2.Stub() {

                @Override
                public void findMagCard(Bundle info) {
                    LoggerHelper.log(TAG, "findMagCard callback");
                    vendExecutor.submit(() -> {
                        boolean ok = processMagstripePayment(info, amount);
                        if (ok) {
                            if (cashlessManager != null) cashlessManager.sendVendApproved(vendPayload);
                            sendStatus(VendingEvents.EVENT_PAYMENT_SUCCESS);
                        } else {
                            if (cashlessManager != null) cashlessManager.sendVendDenied();
                            sendStatus(VendingEvents.EVENT_PAYMENT_FAILED);
                        }
                    });
                }

                @Override
                public void findICCard(String atr) {
                    LoggerHelper.log(TAG, "findICCard callback, ATR=" + atr);
                    vendExecutor.submit(() -> {
                        boolean ok = startEmvTransaction(vendPayload, false, amount);
                        if (ok) {
                            if (cashlessManager != null) cashlessManager.sendVendApproved(vendPayload);
                            sendStatus(VendingEvents.EVENT_PAYMENT_SUCCESS);
                        } else {
                            if (cashlessManager != null) cashlessManager.sendVendDenied();
                            sendStatus(VendingEvents.EVENT_PAYMENT_FAILED);
                        }
                    });
                }

                @Override
                public void findRFCard(String uuid) {
                    LoggerHelper.log(TAG, "findRFCard callback, UUID=" + uuid);
                    vendExecutor.submit(() -> {
                        boolean ok = startEmvTransaction(vendPayload, true, amount);
                        if (ok) {
                            if (cashlessManager != null) cashlessManager.sendVendApproved(vendPayload);
                            sendStatus(VendingEvents.EVENT_PAYMENT_SUCCESS);
                        } else {
                            if (cashlessManager != null) cashlessManager.sendVendDenied();
                            sendStatus(VendingEvents.EVENT_PAYMENT_FAILED);
                        }
                    });
                }

                @Override
                public void onError(int code, String message) {
                    LoggerHelper.log(TAG, "checkCard onError code=" + code + " msg=" + message);
                    if (cashlessManager != null) cashlessManager.sendVendDenied();
                    sendStatus(VendingEvents.EVENT_PAYMENT_FAILED);
                }

                @Override public void findICCardEx(Bundle info) {}
                @Override public void findRFCardEx(Bundle info) {}
                @Override public void onErrorEx(Bundle info) {}

            }, CHECK_CARD_TIMEOUT_SEC);
        } catch (Exception e) {
            LoggerHelper.log(TAG, "Exception in checkCard: " + Log.getStackTraceString(e));
            if (cashlessManager != null) cashlessManager.sendVendDenied();
            sendStatus(VendingEvents.EVENT_PAYMENT_FAILED);
        }
    }

    private double parseAmountFromVendPayload(byte[] payload) {
        if (payload == null || payload.length < 4) return 0.0;
        int cents = ((payload[0] & 0xFF) << 24) |
                ((payload[1] & 0xFF) << 16) |
                ((payload[2] & 0xFF) << 8) |
                (payload[3] & 0xFF);
        return cents / 100.0;
    }

    private boolean processMagstripePayment(Bundle info, double amount) {
        LoggerHelper.log(TAG, "processMagstripePayment: amount $" + amount);
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        return true;
    }

    private boolean startEmvTransaction(byte[] vendPayload, boolean isContactless, double amount) {
        LoggerHelper.log(TAG, "startEmvTransaction: isContactless=" + isContactless + " amount $" + amount);
        if (emvOpt == null) {
            LoggerHelper.log(TAG, "emvOpt is null - cannot start EMV");
            return false;
        }
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        return true;
    }

    private void handleMdbCommand(byte[] command) {
        mdbSlave.sendAnswer(0);
        LoggerHelper.log(TAG, "handleMdbCommand: acknowledged");
    }

    private void sendVendStarted(double amount) {
        Intent intent = new Intent(VendingEvents.ACTION_STATUS);
        intent.putExtra(VendingEvents.KEY_EVENT, VendingEvents.EVENT_VEND_STARTED);
        intent.putExtra(VendingEvents.KEY_AMOUNT, amount);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        LoggerHelper.log(TAG, "Broadcasted EVENT_VEND_STARTED amount $" + amount);
    }

    private void sendStatus(int event) {
        Intent intent = new Intent(VendingEvents.ACTION_STATUS);
        intent.putExtra(VendingEvents.KEY_EVENT, event);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        LoggerHelper.log(TAG, "Broadcasted event: " + event);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        if (mdbSlave != null) mdbSlave.close();
        vendExecutor.shutdownNow();
        try { payKernel.destroyPaySDK(); } catch (Exception ignored) {}
        LoggerHelper.log(TAG, "VendingService destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
