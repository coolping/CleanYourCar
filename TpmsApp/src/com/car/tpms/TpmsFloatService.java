package com.car.tpms;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public class TpmsFloatService extends Service implements SyuCanbusManager.OnTireDataListener {
    private static final String CHANNEL_ID = "tpms_float_service";
    private static final String PREF_NAME = "tpms_settings";
    private static final String KEY_FLOAT_X = "float_x";
    private static final String KEY_FLOAT_Y = "float_y";

    private WindowManager mWindowManager;
    private View mFloatView;
    private WindowManager.LayoutParams mParams;
    private SharedPreferences mPrefs;

    private TextView mTvFL, mTvFR, mTvRL, mTvRR;
    private TextView mTvFloatTitle;
    public static boolean isRunning = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        mPrefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        SyuCanbusManager.getInstance().initContext(this);

        startForegroundNotification();
        initFloatingView();
        SyuCanbusManager.getInstance().addListener(this);
    }

    private void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "TPMS Floating Service", NotificationManager.IMPORTANCE_LOW);
            if (nm != null) nm.createNotificationChannel(channel);
            Notification notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("車載胎壓懸浮視窗")
                    .setContentText("胎壓即時監控中")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build();
            startForeground(1001, notification);
        }
    }

    private void initFloatingView() {
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mFloatView = LayoutInflater.from(this).inflate(R.layout.layout_floating_tpms, null);

        mTvFL = mFloatView.findViewById(R.id.tv_float_fl);
        mTvFR = mFloatView.findViewById(R.id.tv_float_fr);
        mTvRL = mFloatView.findViewById(R.id.tv_float_rl);
        mTvRR = mFloatView.findViewById(R.id.tv_float_rr);
        mTvFloatTitle = mFloatView.findViewById(R.id.tv_float_title);

        View btnClose = mFloatView.findViewById(R.id.btn_float_close);
        btnClose.setOnClickListener(v -> stopSelf());

        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        mParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        mParams.gravity = Gravity.TOP | Gravity.START;
        mParams.x = mPrefs.getInt(KEY_FLOAT_X, 80);
        mParams.y = mPrefs.getInt(KEY_FLOAT_Y, 80);

        mFloatView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean isClick = true;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = mParams.x;
                        initialY = mParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isClick = true;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isClick = false;
                        }
                        mParams.x = initialX + dx;
                        mParams.y = initialY + dy;
                        mWindowManager.updateViewLayout(mFloatView, mParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (isClick) {
                            Intent intent = new Intent(TpmsFloatService.this, MainActivity.class);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                        } else {
                            mPrefs.edit()
                                    .putInt(KEY_FLOAT_X, mParams.x)
                                    .putInt(KEY_FLOAT_Y, mParams.y)
                                    .apply();
                        }
                        return true;
                }
                return false;
            }
        });

        mWindowManager.addView(mFloatView, mParams);
    }

    @Override
    public void onTireDataChanged(int fl, int fr, int rl, int rr, int unit) {
        SyuCanbusManager mgr = SyuCanbusManager.getInstance();
        if (mTvFloatTitle != null) {
            mTvFloatTitle.setText("🚗 胎壓 (" + mgr.getUnitName() + ")");
        }
        if (mTvFL != null) {
            mTvFL.setText("前左: " + mgr.formatValue(fl));
            mTvFR.setText("前右: " + mgr.formatValue(fr));
            mTvRL.setText("後左: " + mgr.formatValue(rl));
            mTvRR.setText("後右: " + mgr.formatValue(rr));
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        SyuCanbusManager.getInstance().removeListener(this);
        if (mFloatView != null && mWindowManager != null) {
            mWindowManager.removeView(mFloatView);
        }
    }
}
