package com.example.cm30vendingapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.airbnb.lottie.LottieAnimationView;
import com.example.cm30vendingapp.service.VendingService;

public class MainActivity extends AppCompatActivity {

    private View dotStatus;
    private TextView tvStatus, tvCenterStatus, tvAmount;
    private CardView cardLottieWrapper;
    private LottieAnimationView lottieCardWait, lottieChip, lottieSpinner, lottieThreeDots;

    private Boolean lastOnlineStatus = null;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int event = intent.getIntExtra(VendingEvents.KEY_EVENT, -1);
            switch (event) {
                case VendingEvents.EVENT_ONLINE:
                    updateOnlineUI(true);
                    break;
                case VendingEvents.EVENT_OFFLINE:
                    updateOnlineUI(false);
                    break;
                case VendingEvents.EVENT_VEND_STARTED:
                    double amount = intent.getDoubleExtra(VendingEvents.KEY_AMOUNT, 0.0);
                    showCardReadStep(amount);
                    break;
                case VendingEvents.EVENT_PAYMENT_SUCCESS:
                    showPaymentSuccess();
                    break;
                case VendingEvents.EVENT_PAYMENT_FAILED:
                    showPaymentFailed();
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dotStatus = findViewById(R.id.dotStatus);
        tvStatus = findViewById(R.id.tvStatus);
        tvCenterStatus = findViewById(R.id.tvCenterStatus);
        tvAmount = findViewById(R.id.tvAmount);
        cardLottieWrapper = findViewById(R.id.cardLottieWrapper);

        lottieCardWait = findViewById(R.id.lottieCardWait);
        lottieChip = findViewById(R.id.lottieChip);
        lottieSpinner = findViewById(R.id.lottieSpinner);
        lottieThreeDots = findViewById(R.id.lottieThreeDots);

        // Register receiver
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(statusReceiver, new IntentFilter(VendingEvents.ACTION_STATUS));

        // Start the vending service
        startForegroundService(new Intent(this, VendingService.class));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver);
    }

    // ------------------ UI States ------------------

    private void updateOnlineUI(boolean online) {
        if (lastOnlineStatus != null && lastOnlineStatus == online) return;
        lastOnlineStatus = online;

        dotStatus.setBackground(ContextCompat.getDrawable(this,
                online ? R.drawable.circle_dot_green : R.drawable.circle_dot_red));
        tvStatus.setText(online ? "Connected" : "Disconnected");

        if (online) {
            cardLottieWrapper.setVisibility(View.VISIBLE);
            tvCenterStatus.setVisibility(View.VISIBLE);
            tvCenterStatus.setText("Waiting for payment");
            tvAmount.setVisibility(View.GONE);

            Animation blink = new AlphaAnimation(0.0f, 1.0f);
            blink.setDuration(500);
            blink.setRepeatMode(Animation.REVERSE);
            blink.setRepeatCount(Animation.INFINITE);
            tvCenterStatus.startAnimation(blink);
        } else {
            cardLottieWrapper.setVisibility(View.GONE);
            tvCenterStatus.clearAnimation();
            tvCenterStatus.setVisibility(View.GONE);
            tvAmount.setVisibility(View.GONE);
        }
    }

    private void showCardReadStep(double amount) {
        tvCenterStatus.clearAnimation();
        tvCenterStatus.setText("Tap / Insert / Swipe your card");
        tvAmount.setText("Amount: $" + String.format("%.2f", amount));
        tvAmount.setVisibility(View.VISIBLE);

        cardLottieWrapper.setVisibility(View.VISIBLE);
        lottieCardWait.setAnimation("payment_wait.json");
        lottieCardWait.playAnimation();

        lottieChip.setVisibility(View.VISIBLE);
        lottieSpinner.setVisibility(View.VISIBLE);
        lottieThreeDots.setVisibility(View.GONE);
    }

    private void showPaymentSuccess() {
        tvCenterStatus.clearAnimation();
        tvCenterStatus.setText("Payment Successful \u2714");
        tvAmount.setVisibility(View.GONE);

        lottieCardWait.setAnimation("success_animation.json");
        lottieCardWait.playAnimation();

        lottieChip.setVisibility(View.GONE);
        lottieSpinner.setVisibility(View.GONE);
        lottieThreeDots.setVisibility(View.VISIBLE);

        // Return to waiting after a short delay
        tvCenterStatus.postDelayed(this::returnToWaitingState, 3000);
    }

    private void showPaymentFailed() {
        tvCenterStatus.clearAnimation();
        tvCenterStatus.setText("Payment Failed \u2716");
        tvAmount.setVisibility(View.GONE);

        lottieCardWait.setAnimation("failed_animation.json");
        lottieCardWait.playAnimation();

        lottieChip.setVisibility(View.GONE);
        lottieSpinner.setVisibility(View.GONE);
        lottieThreeDots.setVisibility(View.VISIBLE);

        // Return to waiting after a short delay
        tvCenterStatus.postDelayed(this::returnToWaitingState, 3000);
    }

    private void returnToWaitingState() {
        tvCenterStatus.setText("Waiting for payment");
        tvAmount.setVisibility(View.GONE);
        lottieCardWait.setAnimation("payment_wait.json");
        lottieCardWait.playAnimation();

        lottieChip.setVisibility(View.VISIBLE);
        lottieSpinner.setVisibility(View.VISIBLE);
        lottieThreeDots.setVisibility(View.GONE);

        Animation blink = new AlphaAnimation(0.0f, 1.0f);
        blink.setDuration(500);
        blink.setRepeatMode(Animation.REVERSE);
        blink.setRepeatCount(Animation.INFINITE);
        tvCenterStatus.startAnimation(blink);
    }
}
