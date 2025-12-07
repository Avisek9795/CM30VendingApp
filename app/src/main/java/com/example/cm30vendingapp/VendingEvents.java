package com.example.cm30vendingapp;

public class VendingEvents {
    public static final String ACTION_STATUS = "VENDING_STATUS";
    public static final String KEY_EVENT = "event";

    // New key for passing amount
    public static final String KEY_AMOUNT = "amount";

    public static final int EVENT_ONLINE = 1;
    public static final int EVENT_OFFLINE = 2;
    public static final int EVENT_VEND_STARTED = 3;
    public static final int EVENT_PAYMENT_SUCCESS = 4;
    public static final int EVENT_PAYMENT_FAILED = 5;
}