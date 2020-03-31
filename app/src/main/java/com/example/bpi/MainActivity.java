package com.example.bpi;


import android.Manifest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.example.bpi.audio.AudioRecordUtil;
import com.example.bpi.rtsp.TestSever;

import java.net.ServerSocket;

public class MainActivity extends AppCompatActivity {

    Message mMessage = new Message();
    Handler.Callback callback = new Handler.Callback() {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            AudioRecordUtil.getInstance().start();
            return true;
        }
    };
    private TestSever server;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        findViewById(R.id.btn_start_pcm).setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View v) {
                new Handler(callback).sendMessage(mMessage);
                //AudioRecordUtil.getInstance().start();
            }
        });

        findViewById(R.id.btn_stop_pcm).setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View v) {
                AudioRecordUtil.getInstance().stop();
            }
        });


        permission();
        server = new TestSever(5005);

    }
    //获取权限
    private void permission() {

        if (Build.VERSION.SDK_INT >= 23) {
            String[] mPermissionList = new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_LOGS,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.SET_DEBUG_APP,
                    Manifest.permission.SYSTEM_ALERT_WINDOW,
                    Manifest.permission.GET_ACCOUNTS,
                    Manifest.permission.WRITE_APN_SETTINGS,
                    Manifest.permission.CAMERA};
            ActivityCompat.requestPermissions(MainActivity.this, mPermissionList, 123);
            // ActivityCompat.requestPermissions(Camera2Preview.this, mPermissionList,  android.permission.WRITE_EXTERNAL_STORAGE);
        }
    }
}