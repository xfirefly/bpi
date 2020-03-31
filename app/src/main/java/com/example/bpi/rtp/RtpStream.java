package com.example.bpi.rtp;

import java.io.IOException;
import java.nio.ByteBuffer;

public class RtpStream {

    private static final String TAG = "RtpStream";
    private int payloadType;
    private int sampleRate;
    private RtpSocketInter socket;
    private short sequenceNumber = 0;
    private long timeold;

    private int timestamp_incrses= 90000/60;
    private int ts_current=0;

    private int RTP_PAYLOAD_MAX_SIZE = 1400;
    private int SEND_BUF_SIZE = 1500;

    public RtpStream(int pt, int sampleRate, RtpSocketInter socket){
        this.payloadType = 96;
        this.sampleRate = sampleRate;
        this.socket = socket;
    }

    public void addPacket(byte[] data, int offset, int size, long timeUs) throws IOException{
        addPacket(null, data, offset, size, timeUs);
    }

    public void addPacket(byte[] prefixData, byte[] data, int offset, int size, long timeUs) throws IOException{


		/*
		RTP packet header
		Bit offset[b]	0-1	2	3	4-7	8	9-15	16-31
		0			Version	P	X	CC	M	PT	Sequence Number  31
		32			Timestamp									 63
		64			SSRC identifier								 95
		*/
 /*       ByteBuffer buffer = ByteBuffer.allocate(500000);
        buffer.put((byte)(2 << 6));
        buffer.put((byte)(payloadType));
        buffer.putShort(sequenceNumber++);
        ts_current = ts_current + timestamp_incrses;
        buffer.putInt(ts_current);
        buffer.putInt(12345678);            //同步信号源标识符，随机产生，参加同一视频会议的两个同步信源不能有相同的
        buffer.putInt(size);

        if(prefixData != null)
            buffer.put(prefixData);

        buffer.put(data, offset, size);

        //sendpacket调用一次发送一个
        sendPacket(buffer, buffer.position());*/
        ts_current = ts_current + timestamp_incrses;

        offset = 4;//h264起始码的大小
        int nalu_len = size - offset;
        ByteBuffer sendBuffer = ByteBuffer.allocate(SEND_BUF_SIZE);

       if(nalu_len < RTP_PAYLOAD_MAX_SIZE){
           //ByteBuffer sendBuffer = ByteBuffer.allocate(500000);
            //发一次
            sendBuffer.put((byte)(2 << 6));         //0x80
            sendBuffer.put((byte)(payloadType));    //0x60

           sendBuffer.putShort(sequenceNumber++);
            //ts_current = ts_current + timestamp_incrses;
           sendBuffer.putInt(ts_current);
           sendBuffer.putInt(857);     //会话标识
            //至此RTP头封装完成

            //设置rtp负载的nal uint头
            //单个nalu发送时，H264的头和Rtp的头是一样的
            //写数据 不包括H264的起始码,默认了起始码是4个字节
         sendBuffer.put(data,offset, nalu_len);
         sendPacket(sendBuffer, sendBuffer.position());

       }
        else{
            //发多次
           int fu_pack_num = (nalu_len % RTP_PAYLOAD_MAX_SIZE != 0 ) ? (nalu_len / RTP_PAYLOAD_MAX_SIZE + 1) : nalu_len / RTP_PAYLOAD_MAX_SIZE;
           int last_fu_pack_size = (nalu_len % RTP_PAYLOAD_MAX_SIZE != 0) ? nalu_len % RTP_PAYLOAD_MAX_SIZE : RTP_PAYLOAD_MAX_SIZE;
           int fu_seq;
           for(fu_seq = 0;fu_seq<fu_pack_num;fu_seq++){
               if(fu_seq == 0){
                   //RTP头还是一样
                   sendBuffer.put((byte)(2 << 6));
                   sendBuffer.put((byte)(payloadType));
                   sendBuffer.putShort((sequenceNumber++));
                   sendBuffer.putInt(ts_current);
                   sendBuffer.putInt(857);

                   //FU_INDICATOR(1字节)
                   byte fu_ind = (byte)(data[offset] & (byte)224);//0x11100000;  //取出高三位
                   fu_ind = (byte)(fu_ind | (byte)28);  //28 FU-A  0x00011100
                   sendBuffer.put(fu_ind);

                   //FU_HEADER（1字节）
                   byte fu_hdr = (byte)(data[offset] & (byte)31);   //取出低五位  0x00011111
                   fu_hdr = (byte)(fu_hdr | (byte)128);    //赋值高三位
                   sendBuffer.put(fu_hdr);

                   //拷贝数据，不包括起始码和nalu头
                   sendBuffer.put(data,offset+1, RTP_PAYLOAD_MAX_SIZE -1);
                   sendPacket(sendBuffer, sendBuffer.position());

               }else if(fu_seq < fu_pack_num -1){
                   sendBuffer.clear();
                   //RTP头还是一样
                   sendBuffer.put((byte)(2 << 6));
                   sendBuffer.put((byte)(payloadType));
                   sendBuffer.putShort(sequenceNumber++);
                   sendBuffer.putInt(ts_current);
                   sendBuffer.putInt(857);

                   //FU_INDICATOR(1字节) 与第一包一致
                   byte fu_ind = (byte)(data[offset] & (byte)224);  //取出高三位  0x11100000
                   fu_ind = (byte)(fu_ind | (byte)28);  //28 FU-A  0x00011100
                   sendBuffer.put(fu_ind);

                   //FU_HEADER（1字节）
                   byte fu_hdr = (byte)(data[offset] &( byte)31);   //取出低五位  0x00011111
                   fu_hdr = (byte)(fu_hdr | (byte)0 );    //赋值高三位   不一致  0x00000000
                   sendBuffer.put(fu_hdr);

                   sendBuffer.put(data, offset+RTP_PAYLOAD_MAX_SIZE*fu_seq, RTP_PAYLOAD_MAX_SIZE);
                   sendPacket(sendBuffer, sendBuffer.position());
               }else{
                   sendBuffer.clear();
                   //第二个字节的第七位置1
                   sendBuffer.put((byte)(2 << 6));
                   sendBuffer.put((byte)(payloadType|128));  //0x10000000
                   sendBuffer.putShort(sequenceNumber++);
                   sendBuffer.putInt(ts_current);
                   sendBuffer.putInt(857);

                   //FU_INDICATOR(1字节) 与第一包一致
                   byte fu_ind = (byte)(data[offset] & (byte)224);  //取出高三位  0x11100000
                   fu_ind = (byte)(fu_ind | (byte)28 );  //28 FU-A  0x00011100
                   sendBuffer.put(fu_ind);

                   //FU_HEADER（1字节）
                   byte fu_hdr = (byte)(data[offset] & (byte)31);   //取出低五位 0x00011111
                   fu_hdr = (byte)(fu_hdr | (byte)64 );    //赋值高三位   不一致  0x01000000
                   sendBuffer.put(fu_hdr);

                   sendBuffer.put(data, offset+RTP_PAYLOAD_MAX_SIZE*fu_seq, last_fu_pack_size);
                   sendPacket(sendBuffer, sendBuffer.position());
               }
           }
        }


    }

    protected void sendPacket(ByteBuffer buffer, int size) throws IOException{
        socket.sendPacket(buffer.array(), 0, size);
        buffer.clear();
    }

    public native byte[] oneRtp(byte[] data, int size);
    public native byte[] multRtp(byte[] data, int size);


}
