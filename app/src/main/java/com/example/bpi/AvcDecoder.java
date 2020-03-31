package com.example.bpi;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import android.os.Handler;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;

public class AvcDecoder {
    private final static String TAG = "DeEncoder";
    private final static int CONFIGURE_FLAG_DECODE = 0;     //编码器类型

    private MediaCodec mMediaCodec;     //解码器
    private MediaFormat mMediaFormat;   //解码器配置
    private Surface mSurface;           //解码输出
    private int         mViewWidth;
    private int         mViewHeight;

    private Handler mVideoDecoderHandler;
    private HandlerThread mVideoDecoderHandlerThread = new HandlerThread("VideoDecoder");

    private MediaCodec.Callback mCallback = new MediaCodec.Callback() {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec, int id){
            ByteBuffer inputBuffer = mediaCodec.getInputBuffer(id);
            inputBuffer.clear();
            //mediaCodec.queueInputBuffer(id,0, length,0,0);
        }
        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int id, @NonNull MediaCodec.BufferInfo bufferInfo) {
            ByteBuffer outputBuffer = mMediaCodec.getOutputBuffer(id);
            MediaFormat outputFormat = mMediaCodec.getOutputFormat(id);

            if(mMediaFormat == outputFormat && outputBuffer != null && bufferInfo.size > 0){
                byte [] buffer = new byte[outputBuffer.remaining()];
                outputBuffer.get(buffer);
            }
            mMediaCodec.releaseOutputBuffer(id, true);
        }
        @Override
        public void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {
            Log.d(TAG, "------> onError");
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec mediaCodec, @NonNull MediaFormat mediaFormat) {
            Log.d(TAG, "------> onOutputFormatChanged");
        }
    };

    public AvcDecoder(String mimeType, Surface surface, int viewwidth, int viewheight){
        try{
            mMediaCodec = MediaCodec.createDecoderByType(mimeType);
        }catch(IOException e){
            Log.e(TAG, Log.getStackTraceString(e));
            mMediaCodec = null;
            return;
        }

        if(surface == null){
            return;
        }

        this.mViewHeight = viewheight;
        this.mViewWidth = viewwidth;
        this.mSurface = surface;

        mVideoDecoderHandlerThread.start();
        mVideoDecoderHandler = new Handler(mVideoDecoderHandlerThread.getLooper());

        mMediaFormat = MediaFormat.createVideoFormat(mimeType, mViewWidth, mViewHeight);   //surface的大小。如果数据流的大小是1080，surface分辨率比这个小，解码器会自动缩小。
        mMediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        mMediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 6250000/2);
        mMediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
        mMediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10);
    }

    public void startDecoder(){
        if(mMediaCodec != null && mSurface != null){
            mMediaCodec.setCallback(mCallback, mVideoDecoderHandler);
            mMediaCodec.configure(mMediaFormat, mSurface,null,CONFIGURE_FLAG_DECODE);
            mMediaCodec.start();
        }else{
            throw new IllegalArgumentException("startDecoder failed, please check the MediaCodec is init correct");
        }
    }

    public void stopDecoder(){
        if(mMediaCodec != null){
            mMediaCodec.stop();
        }
    }

    /**
     * release all resource that used in Encoder
     */
    public void release(){
        if(mMediaCodec != null){
            mMediaCodec.release();
            mMediaCodec = null;
        }
    }
}
