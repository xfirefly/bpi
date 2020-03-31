package com.example.bpi;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

import android.os.Handler;

import com.example.bpi.rtp.RtpSenderWrapper;

public class AvcEncoder{
    String MIME_TYPE = "video/avc";
    int width;
    int height;
    int bitrate;
    int bitrateMode;
    int frameRate;
    int iframeInterval = 1;

    MediaCodec mEncoder;
    MediaFormat mEncoderOutputVideoFormat;
    Surface inputSurface;

    private Handler handler;
    private HandlerThread handlerThread;

    //UDP send
    private RtpSenderWrapper rtpSenderWrapper;


    ///public Queue<ByteBuffer> mOutbuffer = new LinkedList<ByteBuffer>();
    private final static int CACHE_BUFFER_SIZE = 8;
    private final static ArrayBlockingQueue<byte []> mOutputDatasQueue = new ArrayBlockingQueue<byte[]>(CACHE_BUFFER_SIZE);

    public AvcEncoder(int width, int height, int bitrate, int bitrateMode, int frameRate){
        initHandlerThread();

        this.width = width;
        this.height = height;
        this.bitrate = bitrate;
        this.bitrateMode = bitrateMode;
        this.frameRate = frameRate;

        MediaFormat format = createMediaFormat();
        MediaCodecInfo info = selectCodec(MIME_TYPE);       //

        try {
            mEncoder = MediaCodec.createByCodecName(info.getName());
        } catch (IOException e) {
            e.printStackTrace();
            mEncoder = null;
            return;
        }
        //异步
        mEncoder.setCallback(new MediaCodec.Callback() {   //将回调函放在线程1执行
            //计算帧率
            long timeDiff = 0;
            long startTime = 0;
            int framesRecevie = 0;
            @Override       //用camera2的内存块直接输入，不会回调这个函数。
            public void onInputBufferAvailable(MediaCodec codec, int index) {
                //"Empty" input buffers becomes available here
                //User should fill them with desired media data.
/*                ByteBuffer inputBuffer = codec.getInputBuffer(index);

                FrameData data = mQueue.poll();

                try {
                    if (data != null) {
                        if (inputBuffer != null) {
                            inputBuffer.clear();
                            inputBuffer.put(data.getBuffer());

                            codec.queueInputBuffer(index,
                                    0,
                                    data.getBuffer().length,
                                    data.getPresentationTimeUs(),
                                    0);
                        }
                    } else {
                        //EOS
                        codec.queueInputBuffer(index,
                                0,0, 0, 0);
                    }
                } catch (BufferOverflowException e) {
                    e.printStackTrace();
                    inputBuffer.clear();
                }*/
            }

            @Override
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                //Encoded data are placed in output buffers
                //User will consume these buffers here.
                ByteBuffer outputBuffer = mEncoder.getOutputBuffer(index);
                //MediaFormat outputFormat = mEncoder.getOutputFormat(index);
                if(outputBuffer != null && info.size > 0){     //有效压缩数据
                    byte [] buffer = new byte[outputBuffer.remaining()];
                    outputBuffer.get(buffer);

                    //将编码后的数据送入rtp组装  -> 使用RTSP传输
                    if(rtpSenderWrapper != null)
                        rtpSenderWrapper.sendAvcPacket(buffer, 0, info.size, info.presentationTimeUs*1000);

/*                    boolean result = (mOutputDatasQueue.offer(buffer));
                    if(!result)
                        Log.d("encoder", "Offer to queue failed, queue in full state");
                    }*/
                }
                mEncoder.releaseOutputBuffer(index, false);

                timeDiff = System.currentTimeMillis() - startTime;
                if(timeDiff < 1000){
                    framesRecevie++;
                }else{
                    timeDiff = 0;
                    startTime = System.currentTimeMillis();
                    Log.d("encode","FPS " + framesRecevie);
                    framesRecevie = 0;
                    // MediaFormat newFormat = codec.getOutputFormat();
                    //int videoWidth = newFormat.getInteger("width");
                    //int videoHeight = newFormat.getInteger("height");
                    //Log.d("encode","width " + videoWidth);
                    //Log.d("encode", "height " + videoHeight);
                }
                //mOutbuffer.add(mEncoder.getOutputBuffer(index));
                //muxVideo(index, info);    如果要写MP4

            }

            @Override
            public void onError(MediaCodec codec, MediaCodec.CodecException e) {

            }

            @Override
            public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                mEncoderOutputVideoFormat = mEncoder.getOutputFormat();
            }
        });

        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = mEncoder.createPersistentInputSurface();
        mEncoder.setInputSurface(inputSurface);
        mEncoder.start();
    }

    public void setRtpSenderWrapper(RtpSenderWrapper rtpSenderWrapper){
        this.rtpSenderWrapper = rtpSenderWrapper;
    }


    public void stopEncoder(){
        if(mEncoder != null){
            mEncoder.stop();
            mEncoder.setCallback(null);
        }
        stopHandlerThread();
    }

    public void release(){
        if(mEncoder != null){
            //mOutputDatasQueue.clear();
            mEncoder.release();
        }
    }
    public Surface getInputSurface(){
        return inputSurface;
    }
    private  MediaFormat createMediaFormat(){
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);  //VIDEO_AVC

        int colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iframeInterval);
        return format;
    }

    private MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (!codecInfo.isEncoder()) {
                continue;
            }
            String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(mimeType)) {
                    return codecInfo;
                }
            }
        }
        return null;
    }

    private void initHandlerThread() {
        handlerThread = new HandlerThread("EncodeThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    private void stopHandlerThread() {
        handlerThread.quitSafely();
        try {
            handlerThread.join();
            handlerThread = null;
            handler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
