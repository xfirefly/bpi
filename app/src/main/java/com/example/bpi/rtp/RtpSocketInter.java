package com.example.bpi.rtp;

import java.io.IOException;

public interface RtpSocketInter {
    public void sendPacket(byte[] data, int offset, int size) throws IOException;
    public void close();
}

