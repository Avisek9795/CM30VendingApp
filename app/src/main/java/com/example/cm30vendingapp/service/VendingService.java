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
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.cm30vendingapp.R;
import com.example.cm30vendingapp.VendingEvents;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.hardware.cashless.CashlessManager;
import android.hardware.cashless.ICashlessEventMonitor;
import android.hardware.mdbSlave.MdbSlave;

// PayLib / POS pay kernel (from Paylib-debug.aar)
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

    // Pay kernel + readcard/emv handles
    private posPayKernel payKernel;
    private ReadCardOptV2 readCardOpt;
    private EMVOptV2 emvOpt;

    // Card type constants (as per Pay SDK doc)
    private static final int CARD_MAGNETIC = 1;
    private static final int CARD_IC = 2;
    private static final int CARD_NFC = 4;
    private static final int CARD_ALL = CARD_MAGNETIC | CARD_IC | CARD_NFC;

    // Timeout for checkCard (seconds)
    private static final int CHECK_CARD_TIMEOUT_SEC = 60;

    @Override
    public void onCreate() {
        super.onCreate();
        createForegroundNotification();

        initCashlessManager();
        initPayKernel();       // init PayLib and obtain read/emv objects
        initMdbSlave();

        running = true;
        startMdbReceiveLoop();
    }

    private void createForegroundNotification() {
        // Notification channel
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
                .setSmallIcon(R.drawable.ic_vending) // make sure drawable exists
                .setOngoing(true)
                .build();

        // Android 13+ / 14 requires type when starting foreground in some cases; use connectedDevice type
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            startForeground(1337, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(1337, notification);
        }
    }

    private void initCashlessManager() {
        cashlessManager = CashlessManager.getInstance();
        if (cashlessManager == null) return;

        cashlessManager.setConfiguration(3, 840, 100, 2, 30, 0);
        cashlessManager.setLogLevel(3);

        try {
            cashlessManager.registerMonitor(new ICashlessEventMonitor.Stub() {
                @Override public void onInitialComplete(byte[] cashlessInfo, byte[] vmcInfo) {
                    Log.d(TAG, "Cashless initial complete");
                    sendStatus(VendingEvents.EVENT_ONLINE);
                }

                @Override public void onVendRequest(byte[] data) {
                    Log.d(TAG, "Cashless vend request received");
                    // run vend flow on executor
                    vendExecutor.submit(() -> handleVendRequest(data));
                }

                @Override public void onVendCancel() {
                    Log.d(TAG, "Cashless vend canceled");
                    sendStatus(VendingEvents.EVENT_PAYMENT_FAILED);
                }

                @Override public void onVendSuccess(byte[] data) {
                    Log.d(TAG, "Cashless vend successful");
                    sendStatus(VendingEvents.EVENT_PAYMENT_SUCCESS);
                }

                @Override public void onVendFailure(byte[] data) {
                    Log.d(TAG, "Cashless vend failed");
                    sendStatus(VendingEvents.EVENT_PAYMENT_FAILED);
                }

                // other overriden methods (no-op)
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
        } catch (Exception e) {
            Log.e(TAG, "Failed to register Cashless monitor", e);
        }
    }

    private void initPayKernel() {
        payKernel = posPayKernel.getInstance();
        // initPaySDK returns boolean (bind result). We provide a ConnectCallback to get notified when ready
        boolean ok = payKernel.initPaySDK(getApplicationContext(), new posPayKernel.ConnectCallback() {
            @Override
            public void onConnectPaySDK() {
                Log.d(TAG, "Pay SDK connected");
                // Fetch references to ReadCardOptV2 and EMVOptV2 from payKernel
                readCardOpt = payKernel.mReadcardOpt;
                emvOpt = payKernel.mEmvOpt;

                if (readCardOpt == null) {
                    Log.w(TAG, "readCardOpt is null after connect");
                } else {
                    Log.d(TAG, "readCardOpt ready");
                }

                if (emvOpt == null) {
                    Log.w(TAG, "emvOpt is null after connect");
                } else {
                    Log.d(TAG, "emvOpt ready");
                }
            }

            @Override
            public void onDisconnectPaySDK() {
                Log.d(TAG, "Pay SDK disconnected");
                readCardOpt = null;
                emvOpt = null;
            }
        });

        Log.d(TAG, "initPaySDK bind result: " + ok);
    }

    private void initMdbSlave() {
        mdbSlave = MdbSlave.getInstance();
        int openResult = mdbSlave.open();
        Log.d(TAG, "MDB Slave open result: " + openResult);
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
    }

    /**
     * Called when a vend request arrives from the VMC (cashless onVendRequest).
     * We must:
     *  1) Start card detection (checkCard for MAG/IC/NFC)
     *  2) On card detected, perform appropriate payment flow (magstripe, EMV contact, contactless)
     */
    private void handleVendRequest(byte[] vendPayload) {
        Log.d(TAG, "handleVendRequest: starting card check (all types)");

        if (readCardOpt == null) {
            Log.w(TAG, "readCardOpt is null — cannot check card");
            if (cashlessManager != null) cashlessManager.sendVendDenied();
            sendStatus(VendingEvents.EVENT_PAYMENT_FAILED);
            return;
        }

        // 1️⃣ Parse the amount from vendPayload (example: first 4 bytes = cents)
        double amount = parseAmountFromVendPayload(vendPayload);
        Log.d(TAG, "Vend amount parsed: " + amount);

        // 2️⃣ Broadcast UI to show "waiting for card" with amount
        sendVendStarted(amount);

        try {
            // 3️⃣ Check for all card types
            readCardOpt.checkCard(CARD_ALL, new CheckCardCallbackV2.Stub() {

                @Override
                public void findMagCard(Bundle info) {
                    Log.d(TAG, "findMagCard callback");
                    String track2 = info.getString("TRACK2");
                    String pan = info.getString("pan");
                    String exp = info.getString("expire");

                    vendExecutor.submit(() -> {
                        boolean ok = processMagstripePayment(pan, exp, track2, info, amount);
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
                    Log.d(TAG, "findICCard callback, ATR=" + atr);
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
                    Log.d(TAG, "findRFCard callback, UUID=" + uuid);
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
                    Log.e(TAG, "checkCard onError code=" + code + " msg=" + message);
                    if (cashlessManager != null) cashlessManager.sendVendDenied();
                    sendStatus(VendingEvents.EVENT_PAYMENT_FAILED);
                }

                @Override public void findICCardEx(Bundle info) {}
                @Override public void findRFCardEx(Bundle info) {}
                @Override public void onErrorEx(Bundle info) {}

            }, CHECK_CARD_TIMEOUT_SEC);

        } catch (Exception e) {
            Log.e(TAG, "Exception while calling checkCard", e);
            if (cashlessManager != null) cashlessManager.sendVendDenied();
            sendStatus(VendingEvents.EVENT_PAYMENT_FAILED);
        }
    }

    /** Broadcast vend started + amount to UI */
    private void sendVendStarted(double amount) {
        Intent intent = new Intent(VendingEvents.ACTION_STATUS);
        intent.putExtra(VendingEvents.KEY_EVENT, VendingEvents.EVENT_VEND_STARTED);
        intent.putExtra(VendingEvents.KEY_AMOUNT, amount); // pass actual amount
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    /** Parse amount from vendPayload (example: first 4 bytes = cents) */
    private double parseAmountFromVendPayload(byte[] payload) {
        if (payload == null || payload.length < 4) return 0.0;
        int cents = ((payload[0] & 0xFF) << 24) |
                ((payload[1] & 0xFF) << 16) |
                ((payload[2] & 0xFF) << 8) |
                (payload[3] & 0xFF);
        return cents / 100.0;
    }

    /**
     * Process magstripe payment.
     * NOTE: This is where you'd integrate with your acquirer/host to perform the actual transaction.
     * For now the method is a placeholder that returns simulated success.
     */

    /** Simulate magstripe payment with amount */
    private boolean processMagstripePayment(String pan, String exp, String track2, Bundle fullInfo, double amount) {
        Log.d(TAG, "processMagstripePayment() - Amount: $" + amount);
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        return true;
    }

    /** Simulate EMV transaction with amount */
    private boolean startEmvTransaction(byte[] vendPayload, boolean isContactless, double amount) {
        Log.d(TAG, "startEmvTransaction() isContactless=" + isContactless + " Amount: $" + amount);
        if (emvOpt == null) {
            Log.w(TAG, "emvOpt is null - cannot start EMV");
            return false;
        }
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        return true;
    }

    private void handleMdbCommand(byte[] command) {
        // Example: simply acknowledge MDB command as success
        mdbSlave.sendAnswer(0);
    }

    private void sendStatus(int event) {
        Intent intent = new Intent(VendingEvents.ACTION_STATUS);
        intent.putExtra(VendingEvents.KEY_EVENT, event);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        if (mdbSlave != null) mdbSlave.close();
        vendExecutor.shutdownNow();

        // destroy Pay SDK gracefully
        try { payKernel.destroyPaySDK(); } catch (Exception ignored) {}

        Log.d(TAG, "VendingService destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
