package com.car.tpms;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements SyuCanbusManager.OnTireDataListener {
    private TextView mTvFLVal, mTvFRVal, mTvRLVal, mTvRRVal;
    private TextView mTvFLStatus, mTvFRStatus, mTvRLStatus, mTvRRStatus;
    private Button mBtnToggleFloat;
    private Button mBtnUnitToggle;
    private TextView mTvBottomInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SyuCanbusManager.getInstance().initContext(this);

        mTvFLVal = findViewById(R.id.tv_fl_val);
        mTvFRVal = findViewById(R.id.tv_fr_val);
        mTvRLVal = findViewById(R.id.tv_rl_val);
        mTvRRVal = findViewById(R.id.tv_rr_val);

        mTvFLStatus = findViewById(R.id.tv_fl_status);
        mTvFRStatus = findViewById(R.id.tv_fr_status);
        mTvRLStatus = findViewById(R.id.tv_rl_status);
        mTvRRStatus = findViewById(R.id.tv_rr_status);

        mBtnToggleFloat = findViewById(R.id.btn_toggle_float);
        mBtnUnitToggle = findViewById(R.id.btn_unit_toggle);
        mTvBottomInfo = findViewById(R.id.tv_bottom_info);

        mBtnUnitToggle.setOnClickListener(v -> {
            SyuCanbusManager.getInstance().switchUnit();
            updateUnitButton();
        });

        mBtnToggleFloat.setOnClickListener(v -> toggleFloatingWindow());

        updateUnitButton();
        updateFloatButton();
    }

    @Override
    protected void onResume() {
        super.onResume();
        SyuCanbusManager.getInstance().addListener(this);
        SyuCanbusManager.getInstance().queryAll();
        updateFloatButton();
    }

    @Override
    protected void onPause() {
        super.onPause();
        SyuCanbusManager.getInstance().removeListener(this);
    }

    private void updateUnitButton() {
        mBtnUnitToggle.setText("單位: " + SyuCanbusManager.getInstance().getUnitName());
    }

    private void updateFloatButton() {
        if (TpmsFloatService.isRunning) {
            mBtnToggleFloat.setText("關閉懸浮");
        } else {
            mBtnToggleFloat.setText("開啟懸浮");
        }
    }

    private void toggleFloatingWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                Toast.makeText(this, "請開啟懸浮視窗權限", Toast.LENGTH_LONG).show();
                return;
            }
        }

        if (TpmsFloatService.isRunning) {
            stopService(new Intent(this, TpmsFloatService.class));
            mBtnToggleFloat.setText("開啟懸浮");
        } else {
            Intent intent = new Intent(this, TpmsFloatService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            mBtnToggleFloat.setText("關閉懸浮");
        }
    }

    @Override
    public void onTireDataChanged(int fl, int fr, int rl, int rr, int unit) {
        SyuCanbusManager mgr = SyuCanbusManager.getInstance();
        mTvFLVal.setText(mgr.formatValue(fl));
        mTvFRVal.setText(mgr.formatValue(fr));
        mTvRLVal.setText(mgr.formatValue(rl));
        mTvRRVal.setText(mgr.formatValue(rr));

        updateStatus(mTvFLStatus, fl);
        updateStatus(mTvFRStatus, fr);
        updateStatus(mTvRLStatus, rl);
        updateStatus(mTvRRStatus, rr);

        mTvBottomInfo.setText("● CANbus 原廠連線正常 (" + mgr.getUnitName() + ")");
    }

    private void updateStatus(TextView tv, int val) {
        if (tv == null) return;
        int kpa = SyuCanbusManager.getInstance().parseKpa(val);
        if (kpa <= 0) {
            tv.setText("● 無訊號");
            tv.setTextColor(0xFF8E9BAE);
        } else {
            tv.setText("● 正常");
            tv.setTextColor(0xFF00E676);
        }
    }
}
