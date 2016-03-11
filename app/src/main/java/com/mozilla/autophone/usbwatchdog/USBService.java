/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.mozilla.autophone.usbwatchdog;

// am startservice -n com.mozilla.autophone.usbwatchdog/.USBService  --ei poll_interval 1800 --esn debug

import android.Manifest;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

// See http://developer.android.com/guide/components/services.html
public class USBService extends Service {
    private boolean mDebug = false;
    private static final int POLL_INTERVAL = 1800; // seconds
    private Looper mServiceLooper;
    private ServiceHandler mServiceHandler;
    private boolean mPermissions;

    private final class ServiceHandler extends Handler {
        private boolean mDebug;
        private long mPollInterval; // milliseconds
        private List<String> mSuArgs;
        private List<String> mRebootArgs;
        private List<String> mGetHeartbeatArgs;
        private boolean mQuoteArgs;
        private boolean mPermissions;
        private String mHeartbeat;

        public ServiceHandler(Looper looper, boolean permissions) {
            super(looper);
            mDebug = false;
            mRebootArgs = new ArrayList<>();
            mRebootArgs.add("reboot");
            mGetHeartbeatArgs = new ArrayList<>();
            mGetHeartbeatArgs.add("getprop");
            mGetHeartbeatArgs.add("usbwatchdog.heartbeat");
            mQuoteArgs = false;
            mSuArgs = new ArrayList<>();
            mPermissions = permissions;
            mHeartbeat = "";
        }

        private List<String> processCommand(List<String> suArgs, List<String> args, boolean quoteArgs) {
            assert (args.size() >= 1);

            List<String> outputList = new ArrayList<String>();
            List<String> cmdArgs = new ArrayList<String>();

            cmdArgs.addAll(suArgs);

            if (!quoteArgs || args.size() == 1) {
                cmdArgs.addAll(args);
            } else {
                StringBuilder sb = new StringBuilder("'");
                for (String arg : args) {
                    sb.append(arg);
                    sb.append(" ");
                }
                sb.deleteCharAt(sb.length() - 1);
                sb.append("'");
                cmdArgs.add(sb.toString());
            }

            if (mDebug) {
                Log.d("USBWatchdog", String.format("processCommand: cmdArgs: %s", cmdArgs));
            }

            Process process = null;

            try {
                process = new ProcessBuilder()
                        .command(cmdArgs)
                        .redirectErrorStream(true)
                        .start();

                InputStream instream = process.getInputStream();
                InputStreamReader instream_reader = new InputStreamReader(instream);
                BufferedReader buffered_reader = new BufferedReader(instream_reader);
                String line;
                while ((line = buffered_reader.readLine()) != null) {
                    if (mDebug) {
                        Log.d("USBWatchdog", line);
                    }
                    outputList.add(line);
                }
            } catch (IOException e) {
                Log.e("USBWatchdog", String.format("%s IOException %s", cmdArgs, e.getMessage()));
            } finally {
                if (process != null) {
                    try {
                        int exitValue = process.exitValue();
                    } catch (IllegalThreadStateException e) {
                        // process still running
                        try {
                            process.destroy();
                        } catch (Exception e1) {
                            Log.e("USBWatchdog", String.format("Ignoring Exception %s caught destroying process", e1));
                        }
                    }
                }
            }
            return outputList;
        }

        private boolean detectSuArgs() {
            boolean detectedSuArgs = false;

            List<String> idCmd = new ArrayList<>();
            idCmd.add("id");

            List<String> args = new ArrayList<>();
            args.add("ls");
            args.add("/data/data");

            String[] suOptions = {"-c", "0"};
            boolean[] needQuote = {true, false};

            checkloop:
            for (String suOption : suOptions) {
                List<String> suArgs = new ArrayList<>();
                suArgs.add("su");
                suArgs.add(suOption);

                for (boolean quote : needQuote) {
                    List<String> outputList = processCommand(suArgs, args, quote);

                    for (int j = 0; j < outputList.size(); j++) {
                        if (outputList.get(j).startsWith("com.android.")) {
                            // Command syntax is alright.
                            // Can we obtain root with this syntax?
                            List<String> idOutputList = processCommand(suArgs, idCmd, quote);

                            for (int k = 0; k < idOutputList.size(); k++) {
                                if (idOutputList.get(k).startsWith("uid=0")) {
                                    detectedSuArgs = true;
                                    mSuArgs.addAll(suArgs);
                                    mQuoteArgs = quote;
                                    break checkloop;
                                }
                            }
                        }
                    }
                }
            }
            return detectedSuArgs;
        }

        @Override
        public void handleMessage(Message msg) {
            if (!mPermissions && !detectSuArgs()) {
                Log.e("USBWatchdog", "Do not have su or sufficient permissions.");
                stopSelf(msg.arg1);
                return;
            }

            String rebootReason = null;
            boolean usbConnected = true;
            List<String> outputList;

            while (usbConnected) {
                String heartbeat = "";
                outputList = processCommand(mSuArgs, mGetHeartbeatArgs, mQuoteArgs);
                for (int i = 0; i < outputList.size(); i++) {
                    heartbeat = outputList.get(i);
                    if (!heartbeat.equals("")){
                        break;
                    }
                }
                if (!heartbeat.equals("")) {
                    Log.i("USBWatchdog", String.format("Heartbeat: %s", heartbeat));
                }
                if (!mHeartbeat.equals(heartbeat)) {
                    mHeartbeat = heartbeat;
                }
                else if (!mHeartbeat.equals("")) {
                    // Consider the usb debugging connection dead
                    // if the heartbeat hasn't been updated since the
                    // last time we checked.
                    rebootReason = "Heartbeat not changed";
                    usbConnected = false;
                }
                if (usbConnected) {
                    try {
                        Thread.sleep(mPollInterval);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        Log.e("USBWatchdog", String.format("InterruptedException %s during sleep", e.getMessage()));
                    }
                }
            }

            // reboot the device now that the usb connection has been lost.
            Date now = new Date();
            String filename = "usbwatchdog.log";
            FileOutputStream outputStream = null;
            byte[] logMessage = String.format("USBWatchdog rebooted %s due to %s\n", now, rebootReason).getBytes();

            try {
                outputStream = openFileOutput(filename, Context.MODE_PRIVATE);
                outputStream.write(logMessage);
            } catch (IOException e) {
                Log.e("USBWatchdog", String.format("Exception writing log: %s", e));
            }
            finally {
                if (outputStream != null) {
                    try {
                        outputStream.close();
                    } catch (IOException e2) {
                        Log.e("USBWatchdog", String.format("Exception closing log: %s", e2));
                    }
                }
            }
            if (!mPermissions) {
                Log.d("USBWatchdog", "su reboot");
                processCommand(mSuArgs, mRebootArgs, mQuoteArgs);
            } else {
                Log.d("USBWatchdog", "PowerManager.reboot");
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                pm.reboot("USB Disconnected - rebooting");

            }

            stopSelf(msg.arg1);
        }
    }

    public USBService() {
    }

    @Override
    public void onCreate() {
        HandlerThread thread = new HandlerThread("ServiceStartArguments", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();

        int permission = ContextCompat.checkSelfPermission(this, Manifest.permission.REBOOT);
        mPermissions = (permission == PackageManager.PERMISSION_GRANTED);

        permission = ContextCompat.checkSelfPermission(this, Manifest.permission.DUMP);
        mPermissions = mPermissions && (permission == PackageManager.PERMISSION_GRANTED);

        if (mPermissions) {
            Log.i("USBWatchdog", "Permissions granted. Using PowerManager.");
        } else {
            Log.i("USBWatchdog", "Permissions not granted. Using su.");
        }

        mServiceLooper = thread.getLooper();
        mServiceHandler = new ServiceHandler(mServiceLooper, mPermissions);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        long poll_interval;
        mDebug = false;
        if (intent == null) {
            Log.e("USBWatchdog", "onStartCommand with null intent");
            poll_interval = (long) POLL_INTERVAL;
        } else {
            poll_interval = (long) intent.getIntExtra("poll_interval", POLL_INTERVAL);
            Bundle b = intent.getExtras();
            if (b.toString().indexOf("debug") != -1) {
                mDebug = true;
            }
            Log.i("USBWatchdog", String.format("mDebug: %s", mDebug));
        }

        // Convert the poll interval from the external seconds
        // to milliseconds.
        mServiceHandler.mDebug = mDebug;
        mServiceHandler.mPollInterval = 1000 * poll_interval;

        Message msg = mServiceHandler.obtainMessage();
        msg.arg1 = startId;
        mServiceHandler.sendMessage(msg);

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
