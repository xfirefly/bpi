package com.example.bpi;

public class FrameData
{
    byte[] buffer;
    long presentationTimeUs;
    boolean isEOS;
    public FrameData(byte[] buffer,long presentationTimeUs, boolean isEOS){
        this.buffer = new byte[buffer.length];
        System.arraycopy(buffer, 0, this.buffer, 0, buffer.length);
        this.presentationTimeUs = presentationTimeUs;
        this.isEOS = isEOS;
    }

    public byte[] getBuffer() {
        return buffer;
    }

    public void setBuffer(byte[] buffer) {
        this.buffer = buffer;
    }

    public long getPresentationTimeUs() {
        return presentationTimeUs;
    }
}
