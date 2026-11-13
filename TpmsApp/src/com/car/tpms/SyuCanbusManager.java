package com.car.tpms;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class SyuCanbusManager implements IBinder.DeathRecipient {
    private static final String TAG = "SyuCanbusManager";
    private static final String PREF_NAME = "tpms_settings";
    private static final String KEY_UNIT = "pref_unit";

    private static SyuCanbusManager sInstance;

    public static final int CODE_FL = 133;
    public static final int CODE_FR = 134;
    public static final int CODE_RL = 135;
    public static final int CODE_RR = 136;

    public static final int UNIT_KPA = 0;
    public static final int UNIT_PSI = 1;
    public static final int UNIT_BAR = 2;

    private int mCurrentUnit = UNIT_KPA;

    private int mValFL = -1;
    private int mValFR = -1;
    private int mValRL = -1;
    private int mValRR = -1;

    private Context mContext;
    private IBinder mCanbusModuleBinder;
    private SharedPreferences mPrefs;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<OnTireDataListener> mListeners = new ArrayList<>();

    public interface OnTireDataListener {
        void onTireDataChanged(int fl, int fr, int rl, int rr, int unit);
    }

    public static synchronized SyuCanbusManager getInstance() {
        if (sInstance == null) {
            sInstance = new SyuCanbusManager();
        }
        return sInstance;
    }

    private SyuCanbusManager() {
        startPollingTask();
    }

    public void initContext(Context context) {
        if (context != null) {
            mContext = context.getApplicationContext();
            if (mPrefs == null) {
                mPrefs = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                mCurrentUnit = mPrefs.getInt(KEY_UNIT, UNIT_KPA);
            }
            try {
                android.content.IntentFilter filter = new android.content.IntentFilter("com.car.tpms.ACTION_SIMULATE_TIRE");
                android.content.BroadcastReceiver simReceiver = new android.content.BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ctx, Intent intent) {
                        int fl = intent.getIntExtra("fl", 228);
                        int fr = intent.getIntExtra("fr", 214);
                        int rl = intent.getIntExtra("rl", 221);
                        int rr = intent.getIntExtra("rr", 221);
                        Log.i(TAG, "Simulated tire data received: " + fl + ", " + fr + ", " + rl + ", " + rr);
                        setSimulatedData(fl, fr, rl, rr);
                    }
                };
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    mContext.registerReceiver(simReceiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    mContext.registerReceiver(simReceiver, filter);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to register simReceiver", t);
            }
            bindSyuService();
        }
    }

    private boolean mIsBinding = false;

    private synchronized void bindSyuService() {
        if (mCanbusModuleBinder != null && mCanbusModuleBinder.isBinderAlive()) {
            return;
        }
        if (mIsBinding) {
            return;
        }
        mIsBinding = true;

        if (mContext != null) {
            try {
                Intent intent = new Intent("com.syu.ms.toolkit");
                intent.setComponent(new ComponentName("com.syu.ms", "app.ToolkitService"));
                boolean bound = mContext.bindService(intent, new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        Log.i(TAG, "Connected to app.ToolkitService via bindService: " + service);
                        initFromToolkitBinder(service);
                        mIsBinding = false;
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        Log.w(TAG, "Disconnected from app.ToolkitService");
                        mCanbusModuleBinder = null;
                        mIsBinding = false;
                    }
                }, Context.BIND_AUTO_CREATE);
                Log.i(TAG, "bindService com.syu.ms.toolkit result: " + bound);
                if (!bound) {
                    mIsBinding = false;
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error in bindService", t);
                mIsBinding = false;
            }
        } else {
            mIsBinding = false;
        }
    }

    private synchronized void initFromToolkitBinder(IBinder toolkitBinder) {
        if (toolkitBinder == null) {
            Log.w(TAG, "initFromToolkitBinder: toolkitBinder is null");
            return;
        }
        Log.i(TAG, "initFromToolkitBinder: entered with " + toolkitBinder);
        try {
            toolkitBinder.linkToDeath(this, 0);
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken("com.syu.ipc.IRemoteToolkit");
                data.writeInt(7); // 7 = CANBUS (MODULE_CODE_CANBUS)
                boolean ok = toolkitBinder.transact(1, data, reply, 0);
                Log.i(TAG, "transact(1) result: " + ok);
                reply.readException();
                mCanbusModuleBinder = reply.readStrongBinder();
                Log.i(TAG, "mCanbusModuleBinder: " + mCanbusModuleBinder);
                if (mCanbusModuleBinder != null) {
                    mCanbusModuleBinder.linkToDeath(this, 0);
                    Log.i(TAG, "Successfully acquired CANbus Module Binder: " + mCanbusModuleBinder);
                    registerCanbusCallbacks();
                }
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Throwable t) {
            Log.e(TAG, "initFromToolkitBinder error", t);
        }
    }

    private void registerCanbusCallbacks() {
        if (mCanbusModuleBinder == null) return;
        // Register callback for tire updates
        for (int code : new int[]{CODE_FL, CODE_FR, CODE_RL, CODE_RR}) {
            try {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                data.writeInterfaceToken("com.syu.ipc.IRemoteModule");
                data.writeStrongBinder(mCallbackBinder);
                data.writeInt(code);
                data.writeInt(1); // update = 1 triggers immediate callback
                mCanbusModuleBinder.transact(3, data, reply, 0); // TRANSACTION_register = 3
                data.recycle();
                reply.recycle();
                Log.i(TAG, "Registered callback for CAN code: " + code);
            } catch (Throwable t) {
                Log.e(TAG, "registerCanbusCallback error for " + code, t);
            }
        }
    }

    private final Binder mCallbackBinder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == 1) { // TRANSACTION_update = 1
                data.enforceInterface("com.syu.ipc.IModuleCallback");
                int updateCode = data.readInt();
                int[] ints = data.createIntArray();
                float[] flts = data.createFloatArray();
                String[] strs = data.createStringArray();
                Log.i(TAG, "onTransact update: " + updateCode + " ints: " + java.util.Arrays.toString(ints));
                handleCanbusUpdate(updateCode, ints);
                if (reply != null) {
                    reply.writeNoException();
                }
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    private void handleCanbusUpdate(int updateCode, int[] ints) {
        if (ints == null || ints.length == 0) return;
        int val = ints[0];
        Log.i(TAG, "handleCanbusUpdate: code=" + updateCode + " val=" + val);
        boolean changed = false;
        switch (updateCode) {
            case CODE_FL:
                if (val != mValFL) { mValFL = val; changed = true; }
                break;
            case CODE_FR:
                if (val != mValFR) { mValFR = val; changed = true; }
                break;
            case CODE_RL:
                if (val != mValRL) { mValRL = val; changed = true; }
                break;
            case CODE_RR:
                if (val != mValRR) { mValRR = val; changed = true; }
                break;
        }
        if (changed) {
            notifyListeners();
        }
    }

    @Override
    public void binderDied() {
        Log.w(TAG, "Binder died, re-binding...");
        mCanbusModuleBinder = null;
        mHandler.postDelayed(this::bindSyuService, 2000);
    }

    public synchronized void queryAll() {
        if (mCanbusModuleBinder == null || !mCanbusModuleBinder.isBinderAlive()) {
            bindSyuService();
            return;
        }
        // Re-request canbus callbacks with update=1 to get current values immediately
        registerCanbusCallbacks();
    }

    public void addListener(OnTireDataListener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
            listener.onTireDataChanged(mValFL, mValFR, mValRL, mValRR, mCurrentUnit);
        }
    }

    public void removeListener(OnTireDataListener listener) {
        mListeners.remove(listener);
    }

    public int getCurrentUnit() {
        return mCurrentUnit;
    }

    public void switchUnit() {
        mCurrentUnit = (mCurrentUnit + 1) % 3;
        if (mPrefs != null) {
            mPrefs.edit().putInt(KEY_UNIT, mCurrentUnit).apply();
        }
        notifyListeners();
    }

    public int parseKpa(int rawVal) {
        if (rawVal <= 0 || rawVal == 254 || rawVal == 255) {
            return -1;
        }
        int kpa = (rawVal / 256) + (rawVal % 256);
        if (kpa <= 0 || kpa == 254 || kpa == 255 || kpa > 600) {
            return -1;
        }
        return kpa;
    }

    public String formatValue(int rawVal) {
        int kpa = parseKpa(rawVal);
        if (kpa <= 0) {
            return "---";
        }
        if (mCurrentUnit == UNIT_KPA) {
            return kpa + " kPa";
        } else if (mCurrentUnit == UNIT_PSI) {
            int psi = (kpa * 1000) / 6895;
            return psi + " Psi";
        } else {
            int intPart = kpa / 100;
            int decPart = kpa % 100;
            return intPart + "." + String.format(java.util.Locale.US, "%02d", decPart) + " bar";
        }
    }

    public void setSimulatedData(int fl, int fr, int rl, int rr) {
        mValFL = fl;
        mValFR = fr;
        mValRL = rl;
        mValRR = rr;
        notifyListeners();
    }

    public String getUnitName() {
        if (mCurrentUnit == UNIT_KPA) return "kPa";
        if (mCurrentUnit == UNIT_PSI) return "Psi";
        return "bar";
    }

    private void notifyListeners() {
        mHandler.post(() -> {
            for (OnTireDataListener l : mListeners) {
                l.onTireDataChanged(mValFL, mValFR, mValRL, mValRR, mCurrentUnit);
            }
        });
    }

    private void startPollingTask() {
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mCanbusModuleBinder == null || !mCanbusModuleBinder.isBinderAlive() 
                        || mValFL <= 0 || mValFR <= 0 || mValRL <= 0 || mValRR <= 0) {
                    queryAll();
                }
                mHandler.postDelayed(this, 10000);
            }
        }, 5000);
    }
}
