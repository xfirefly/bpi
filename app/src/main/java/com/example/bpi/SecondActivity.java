package com.example.bpi;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.example.bpi.audio.AudioRecordUtil;
import com.example.bpi.audio.PCMEncoderAAC;
import com.example.bpi.rtp.RtpSenderWrapper;
import com.example.bpi.rtsp.TestSever;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;

public class SecondActivity extends AppCompatActivity {

    private AutoFitTextureView previewView;
    private AutoFitTextureView decodeView;

    private AvcEncoder mAvcEncoder;
    private RtpSenderWrapper mRtpSenderWrapper;
    private CameraUtil mCameraUtil;
    private int BITRATE_MODE_CBR = 2;
    private int BITRATE_MODE_VBR = 1;

    DatagramSocket socket;
    private MediaCodec decode;
    private MediaFormat format;
    DatagramPacket datagramPacket = null;
    private static  final String MIME_TYPE = "video/avc";
    private Handler mVideoDecoderHandler;
    private HandlerThread mVideoDecoderHandlerThread = new HandlerThread("VideoDecoder");

    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(
                        final SurfaceTexture texture, final int width, final int height) {
                    decode.configure(format,new Surface(texture),null,0);
                    decode.start();
                }

                @Override
                public void onSurfaceTextureSizeChanged(
                        final SurfaceTexture texture, final int width, final int height) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(final SurfaceTexture texture) {
                    if (socket != null){
                        socket.close();
                        socket = null;
                    }
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(final SurfaceTexture texture) {
                }
            };

    private MediaCodec.Callback mCallback = new MediaCodec.Callback() {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec, int id){
            //mOutputDatasQueue.poll();
            ByteBuffer inputBuffer = mediaCodec.getInputBuffer(id);
            inputBuffer.clear();

            byte [] dataSources = null;
            dataSources = mInputDatasQueue.poll();
            int length = 0;
            if(dataSources != null) {
                inputBuffer.put(dataSources);
                length = dataSources.length;
            }
            mediaCodec.queueInputBuffer(id,0, length,0,0);
        }
        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int id, @NonNull MediaCodec.BufferInfo bufferInfo) {
            ByteBuffer outputBuffer = decode.getOutputBuffer(id);
            MediaFormat outputFormat = decode.getOutputFormat(id);

            if(format == outputFormat && outputBuffer != null && bufferInfo.size > 0){
                byte [] buffer = new byte[outputBuffer.remaining()];
                outputBuffer.get(buffer);
            }
            decode.releaseOutputBuffer(id, true);
        }
        @Override
        public void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {
            Log.d("decoder", "------> onError");
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec mediaCodec, @NonNull MediaFormat mediaFormat) {
            Log.d("decoder", "------> onOutputFormatChanged");
        }
    };

    byte[] rtpData =  new byte[80000];
    byte[] h264Data = new byte[80000];
    private final static ArrayBlockingQueue<byte []> mInputDatasQueue = new ArrayBlockingQueue<byte []>(8);
    Queue<FrameData> mQueue = new LinkedList<FrameData>();
    private  class  PreviewThread extends  Thread {
        PreviewThread(){
            start();
        }
        @Override
        public void run() {
            byte[] data = new byte[80000];
            int h264Length = 0;
            while (true) {
                if (socket != null) {
                    try {
                        datagramPacket = new DatagramPacket(data, data.length);
                        socket.receive(datagramPacket);//接收数据
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                rtpData =  datagramPacket.getData();
                if(rtpData != null){
                    if(rtpData[0] == -128 && rtpData[1] == 96)
                    {
                        int l1 = (rtpData[12]<<24)& 0xff000000;
                        int l2 = (rtpData[13]<<16)& 0x00ff0000;
                        int l3 = (rtpData[14]<<8) & 0x0000ff00;
                        int l4 = rtpData[15]&0x000000FF;
                        h264Length = l1+l2+l3+l4;
                        System.arraycopy(rtpData,16, h264Data,0,h264Length);
                        boolean result = mInputDatasQueue.offer(h264Data);
                        if(!result){
                            Log.d("encoder", "Offer to queue failed, queue in full state");
                        }
                    }
                }
            }
        }
    }

    //audio
    //设置音频采样率，44100是目前的标准，但是某些设备仍然支持22050，16000，11025
    private final int sampleRateInHz = 44100;
    //设置音频的录制的声道CHANNEL_IN_STEREO为双声道，CHANNEL_CONFIGURATION_MONO为单声道
    private final int channelConfig = AudioFormat.CHANNEL_IN_STEREO;
    //音频数据格式:PCM 16位每个样本。保证设备支持。PCM 8位每个样本。不一定能得到设备支持。
    private final int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private AudioRecord audioRecord;
    private byte[] buffer;
    private boolean recorderState = true;

    private PCMEncoderAAC pcmEncoderAAC;
    private AudioRecordUtil mTest;

    private VideoView videoView ;

    private MediaPlayer mediaPlayer;

    //将网络操作放到子线程里
    private TestSever server;
    Runnable network = new Runnable() {
        @Override
        public void run() {
            server = new TestSever(5005);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_second);   //加载活动对应的XML文件名

        new Thread(network).start();
/*        findViewById(R.id.btn_stop_pcm).setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View v) {
                mTest.stop();
            }
        });*/


/*       //单纯使用RTP发送H264文件
        previewView = findViewById(R.id.testview);

        //permission();

        mRtpSenderWrapper = new RtpSenderWrapper("172.17.11.82", 5004, false);

        mAvcEncoder = new AvcEncoder(1920, 1080, 1920*1080*3, 2, 60);
        mCameraUtil = new CameraUtil(this, mAvcEncoder.inputSurface, previewView);

        mAvcEncoder.setRtpSenderWrapper(mRtpSenderWrapper);*/


/*        测试直接拿到音频数据
            final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if(AudioManager.ACTION_HDMI_AUDIO_PLUG.equals(action)){

                }
            }
        };

        //private IRtkHDMIRxService mRtkHDMIRxService = Stub.asInterface(ServiceManager.getService("RtkHDMIRxService"));
        AudioManager audiomanage = (AudioManager)getSystemService(Context.AUDIO_SERVICE);

        TvInputManager manage = (TvInputManager)getSystemService(Context.TV_INPUT_SERVICE);
        List<TvInputInfo> tv = manage.getTvInputList();
        //public MediaPlayer mMediaPlayer	= null;
        //videoView.addSubtitleSource();
        //videoView.setAudioAttributes();

        //获得音量
        int mode = audiomanage.getMode();//MODE_NORMAL Normal audio mode: not ringing and no call established.
        audiomanage.setMode(AudioManager.MODE_RINGTONE);


        int ringermode = audiomanage.getRingerMode();//RINGER_MODE_NORMAL
        boolean mic = audiomanage.isMicrophoneMute();
        audiomanage.setMicrophoneMute(true);
        mic = audiomanage.isMicrophoneMute();
        boolean music = audiomanage.isMusicActive();
        boolean head = audiomanage.isWiredHeadsetOn();

        int max =  audiomanage.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        int current = audiomanage.getStreamVolume(AudioManager.STREAM_ALARM );
        current = audiomanage.getStreamVolume(AudioManager.STREAM_NOTIFICATION);


        AudioDeviceInfo[] audioInput = audiomanage.getDevices(AudioManager.GET_DEVICES_INPUTS);
        AudioDeviceInfo[] audioOutput = audiomanage.getDevices(AudioManager.GET_DEVICES_OUTPUTS);

        String address = audioInput[0].getAddress();
        int type = audioInput[0].getType();

        address = audioInput[1].getAddress();
        type = audioInput[1].getType();
        boolean source = audioInput[1].isSource();

        //audio
        int recordMinBufferSize = 2*AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
        //指定 AudioRecord 缓冲区大小
        buffer = new byte[Math.min(4096, recordMinBufferSize)];

        ByteBuffer b = ByteBuffer.allocateDirect(recordMinBufferSize);

        mode = audiomanage.getMode();

        //TvManager.getInstance().getAudioManager().setInputSource(isHdmi ? EnumInputSource.E_INPUT_SOURCE_HDMI : EnumInputSource.E_INPUT_SOURCE_STORAGE);

        audioRecord = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, sampleRateInHz, channelConfig,
                audioFormat, recordMinBufferSize);

        mTest = new AudioRecordUtil(audioRecord, buffer);
        //int ret = audioRecord.getBufferSizeInFrames();
        mTest.start();

        android.media.MediaDataSource m = null;

        mediaPlayer = new MediaPlayer();
        //mediaPlayer.setDataSource();
        //mediaPlayer.setAudioAttributes();*/

    }
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
                    Manifest.permission.CAMERA,
                    Manifest.permission.INTERNET};
            ActivityCompat.requestPermissions(SecondActivity.this, mPermissionList, 123);
            // ActivityCompat.requestPermissions(Camera2Preview.this, mPermissionList,  android.permission.WRITE_EXTERNAL_STORAGE);
        }
    }
}
