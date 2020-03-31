//
// Created by 23281 on 2020/3/2.
//
#include <jni.h>
#include <string>
#include <android/log.h>
#include <unistd.h>
#include <asm/fcntl.h>
#include <fcntl.h>
#include <android/native_window_jni.h>
#include <android/log.h>

#include <stdio.h>
#include <stdlib.h>

//RTP分包
#include <stdint.h>
#include <string.h>
#include <stdlib.h>

#include <unistd.h>
#include <errno.h>

#include <arpa/inet.h>

#define NAL_MAX     4000000
#define H264        96
#define G711        8

typedef struct rtp_header {
    /* little-endian */
    /* byte 0 */
    uint8_t csrc_len:       4;  /* bit: 0~3 */
    uint8_t extension:      1;  /* bit: 4 */
    uint8_t padding:        1;  /* bit: 5*/
    uint8_t version:        2;  /* bit: 6~7 */
    /* byte 1 */
    uint8_t payload_type:   7;  /* bit: 0~6 */
    uint8_t marker:         1;  /* bit: 7 */
    /* bytes 2, 3 */
    uint16_t seq_no;
    /* bytes 4-7 */
    uint32_t timestamp;
    /* bytes 8-11 */
    uint32_t ssrc;
} __attribute__ ((packed)) rtp_header_t; /* 12 bytes */

typedef struct nalu_header {
    /* byte 0 */
    uint8_t type:   5;  /* bit: 0~4 */
    uint8_t nri:    2;  /* bit: 5~6 */
    uint8_t f:      1;  /* bit: 7 */
} __attribute__ ((packed)) nalu_header_t; /* 1 bytes */

typedef struct nalu {
    int startcodeprefix_len;
    unsigned len;             /* Length of the NAL unit (Excluding the start code, which does not belong to the NALU) */
    unsigned max_size;        /* Nal Unit Buffer size */
    int forbidden_bit;        /* should be always FALSE */
    int nal_reference_idc;    /* NALU_PRIORITY_xxxx */
    int nal_unit_type;        /* NALU_TYPE_xxxx */
    char *buf;                /* contains the first byte followed by the EBSP */
    unsigned short lost_packets;  /* true, if packet loss is detected */
} nalu_t;


typedef struct fu_indicator {
    /* byte 0 */
    uint8_t type:   5;
    uint8_t nri:    2;
    uint8_t f:      1;
} __attribute__ ((packed)) fu_indicator_t; /* 1 bytes */

typedef struct fu_header {
    /* byte 0 */
    uint8_t type:   5;
    uint8_t r:      1;
    uint8_t e:      1;
    uint8_t s:      1;
} __attribute__ ((packed)) fu_header_t; /* 1 bytes */

typedef struct rtp_package {
    rtp_header_t rtp_package_header;
    uint8_t *rtp_load;
} rtp_t;

#define DEFAULT_DEST_PORT           1234
#define RTP_PAYLOAD_MAX_SIZE        1400
#define SEND_BUF_SIZE               1500
#define NAL_BUF_SIZE                1500 * 50
#define SSRC_NUM                    10

uint16_t DEST_PORT;
//linklist CLIENT_IP_LIST;
uint8_t SENDBUFFER[SEND_BUF_SIZE];
uint8_t nal_buf[NAL_BUF_SIZE];


static int h264nal2rtp_send(int framerate, uint8_t *pstStream, int nalu_len)
{
    uint8_t *nalu_buf;
    nalu_buf = pstStream;
    // int nalu_len;   /* 不包括0x00000001起始码, 但包括nalu头部的长度 */
    static uint32_t ts_current = 0;
    static uint16_t seq_num = 0;
    rtp_header_t *rtp_hdr;
    nalu_header_t *nalu_hdr;
    fu_indicator_t *fu_ind;
    fu_header_t *fu_hdr;
    size_t len_sendbuf;

    int fu_pack_num;        /* nalu 需要分片发送时分割的个数 */
    int last_fu_pack_size;  /* 最后一个分片的大小 */
    int fu_seq;             /* fu-A 序号 */

    ts_current += (90000 / framerate);  /* 90000 / 25 = 3600 */

    /*
     * 加入长度判断，
     * 当 nalu_len == 0 时， 必须跳到下一轮循环
     * nalu_len == 0 时， 若不跳出会发生段错误!
     * fix by hmg
     */
    if (nalu_len < 1) {
        return -1;
    }

    if (nalu_len <= RTP_PAYLOAD_MAX_SIZE) {
        /*
         * single nal unit
         */

        memset(SENDBUFFER, 0, SEND_BUF_SIZE);

        /*
         * 1. 设置 rtp 头
         */
        rtp_hdr = (rtp_header_t *)SENDBUFFER;
        rtp_hdr->csrc_len = 0;
        rtp_hdr->extension = 0;
        rtp_hdr->padding = 0;
        rtp_hdr->version = 2;
        rtp_hdr->payload_type = H264;
        // rtp_hdr->marker = (pstStream->u32PackCount - 1 == i) ? 1 : 0;   /* 该包为一帧的结尾则置为1, 否则为0. rfc 1889 没有规定该位的用途 */
        rtp_hdr->seq_no = htons(++seq_num % UINT16_MAX);
        rtp_hdr->timestamp = htonl(ts_current);
        rtp_hdr->ssrc = htonl(SSRC_NUM);

        /*
         * 2. 设置rtp荷载 single nal unit 头
         */
        nalu_hdr = (nalu_header_t *)&SENDBUFFER[12];
        nalu_hdr->f = (nalu_buf[0] & 0x80) >> 7;        /* bit0 */
        nalu_hdr->nri = (nalu_buf[0] & 0x60) >> 5;      /* bit1~2 */
        nalu_hdr->type = (nalu_buf[0] & 0x1f);
        //debug_print();

        /*
         * 3. 填充nal内容
         */
        //debug_print();
        memcpy(SENDBUFFER + 13, nalu_buf + 1, nalu_len - 1);    /* 不拷贝nalu头 */

        /*
         * 4. 发送打包好的rtp到客户端
         */
        len_sendbuf = 12 + nalu_len;
        //send_data_to_client_list(SENDBUFFER, len_sendbuf, client_ip_list);
    } else {    /* nalu_len > RTP_PAYLOAD_MAX_SIZE */
        /*
         * FU-A分割
         */

        /*
         * 1. 计算分割的个数
         *
         * 除最后一个分片外，
         * 每一个分片消耗 RTP_PAYLOAD_MAX_SIZE BYLE
         */
        fu_pack_num = nalu_len % RTP_PAYLOAD_MAX_SIZE ? (nalu_len / RTP_PAYLOAD_MAX_SIZE + 1) : nalu_len / RTP_PAYLOAD_MAX_SIZE;
        last_fu_pack_size = nalu_len % RTP_PAYLOAD_MAX_SIZE ? nalu_len % RTP_PAYLOAD_MAX_SIZE : RTP_PAYLOAD_MAX_SIZE;
        fu_seq = 0;

        for (fu_seq = 0; fu_seq < fu_pack_num; fu_seq++) {
            memset(SENDBUFFER, 0, SEND_BUF_SIZE);

            /*
             * 根据FU-A的类型设置不同的rtp头和rtp荷载头
             */
            if (fu_seq == 0) {  /* 第一个FU-A */
                /*
                 * 1. 设置 rtp 头
                 */
                rtp_hdr = (rtp_header_t *)SENDBUFFER;
                rtp_hdr->csrc_len = 0;
                rtp_hdr->extension = 0;
                rtp_hdr->padding = 0;
                rtp_hdr->version = 2;
                rtp_hdr->payload_type = H264;
                rtp_hdr->marker = 0;    /* 该包为一帧的结尾则置为1, 否则为0. rfc 1889 没有规定该位的用途 */
                rtp_hdr->seq_no = htons(++seq_num % UINT16_MAX);
                rtp_hdr->timestamp = htonl(ts_current);
                rtp_hdr->ssrc = htonl(SSRC_NUM);

                /*
                 * 2. 设置 rtp 荷载头部
                 */
                fu_ind = (fu_indicator_t *)&SENDBUFFER[12];
                fu_ind->f = (nalu_buf[0] & 0x80) >> 7;
                fu_ind->nri = (nalu_buf[0] & 0x60) >> 5;
                fu_ind->type = 28;

                fu_hdr = (fu_header_t *)&SENDBUFFER[13];
                fu_hdr->s = 1;
                fu_hdr->e = 0;
                fu_hdr->r = 0;
                fu_hdr->type = nalu_buf[0] & 0x1f;

                /*
                 * 3. 填充nalu内容
                 */
                memcpy(SENDBUFFER + 14, nalu_buf + 1, RTP_PAYLOAD_MAX_SIZE - 1);    /* 不拷贝nalu头 */

                /*
                 * 4. 发送打包好的rtp包到客户端
                 */
                len_sendbuf = 12 + 2 + (RTP_PAYLOAD_MAX_SIZE - 1);  /* rtp头 + nalu头 + nalu内容 */
                //send_data_to_client_list(SENDBUFFER, len_sendbuf, client_ip_list);

            } else if (fu_seq < fu_pack_num - 1) { /* 中间的FU-A */
                /*
                 * 1. 设置 rtp 头
                 */
                rtp_hdr = (rtp_header_t *)SENDBUFFER;
                rtp_hdr->csrc_len = 0;
                rtp_hdr->extension = 0;
                rtp_hdr->padding = 0;
                rtp_hdr->version = 2;
                rtp_hdr->payload_type = H264;
                rtp_hdr->marker = 0;    /* 该包为一帧的结尾则置为1, 否则为0. rfc 1889 没有规定该位的用途 */
                rtp_hdr->seq_no = htons(++seq_num % UINT16_MAX);
                rtp_hdr->timestamp = htonl(ts_current);
                rtp_hdr->ssrc = htonl(SSRC_NUM);

                /*
                 * 2. 设置 rtp 荷载头部
                 */
                fu_ind = (fu_indicator_t *)&SENDBUFFER[12];
                fu_ind->f = (nalu_buf[0] & 0x80) >> 7;
                fu_ind->nri = (nalu_buf[0] & 0x60) >> 5;
                fu_ind->type = 28;

                fu_hdr = (fu_header_t *)&SENDBUFFER[13];
                fu_hdr->s = 0;
                fu_hdr->e = 0;
                fu_hdr->r = 0;
                fu_hdr->type = nalu_buf[0] & 0x1f;

                /*
                 * 3. 填充nalu内容
                 */
                memcpy(SENDBUFFER + 14, nalu_buf + RTP_PAYLOAD_MAX_SIZE * fu_seq, RTP_PAYLOAD_MAX_SIZE);    /* 不拷贝nalu头 */

                /*
                 * 4. 发送打包好的rtp包到客户端
                 */
                len_sendbuf = 12 + 2 + RTP_PAYLOAD_MAX_SIZE;
                //send_data_to_client_list(SENDBUFFER, len_sendbuf, client_ip_list);

            } else { /* 最后一个FU-A */
                /*
                 * 1. 设置 rtp 头
                 */
                rtp_hdr = (rtp_header_t *)SENDBUFFER;
                rtp_hdr->csrc_len = 0;
                rtp_hdr->extension = 0;
                rtp_hdr->padding = 0;
                rtp_hdr->version = 2;
                rtp_hdr->payload_type = H264;
                rtp_hdr->marker = 1;    /* 该包为一帧的结尾则置为1, 否则为0. rfc 1889 没有规定该位的用途 */
                rtp_hdr->seq_no = htons(++seq_num % UINT16_MAX);
                rtp_hdr->timestamp = htonl(ts_current);
                rtp_hdr->ssrc = htonl(SSRC_NUM);

                /*
                 * 2. 设置 rtp 荷载头部
                 */
                fu_ind = (fu_indicator_t *)&SENDBUFFER[12];
                fu_ind->f = (nalu_buf[0] & 0x80) >> 7;
                fu_ind->nri = (nalu_buf[0] & 0x60) >> 5;
                fu_ind->type = 28;

                fu_hdr = (fu_header_t *)&SENDBUFFER[13];
                fu_hdr->s = 0;
                fu_hdr->e = 1;
                fu_hdr->r = 0;
                fu_hdr->type = nalu_buf[0] & 0x1f;

                /*
                 * 3. 填充rtp荷载
                 */
                memcpy(SENDBUFFER + 14, nalu_buf + RTP_PAYLOAD_MAX_SIZE * fu_seq, last_fu_pack_size);    /* 不拷贝nalu头 */

                /*
                 * 4. 发送打包好的rtp包到客户端
                 */
                len_sendbuf = 12 + 2 + last_fu_pack_size;
                //send_data_to_client_list(SENDBUFFER, len_sendbuf, client_ip_list);

            } /* else-if (fu_seq == 0) */
        } /* end of for (fu_seq = 0; fu_seq < fu_pack_num; fu_seq++) */

    } /* end of else-if (nalu_len <= RTP_PAYLOAD_MAX_SIZE) */

    return 0;
}
extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_example_bpi_rtp_RtpStream_oneRtp(JNIEnv *env, jobject instance,jbyteArray data, jint size){
    uint8_t *nalu_buf;

    nalu_buf = static_cast<uint8_t *>(env->GetDirectBufferAddress(data));
    //需要去掉起始码的长度
    int nalu_len = size;
    // int nalu_len;   /* 不包括0x00000001起始码, 但包括nalu头部的长度 */

    static uint32_t ts_current = 0;
    static uint16_t seq_num = 0;
    rtp_header_t *rtp_hdr;
    nalu_header_t *nalu_hdr;
    fu_indicator_t *fu_ind;
    fu_header_t *fu_hdr;
    size_t len_sendbuf;

    int fu_pack_num;        /* nalu 需要分片发送时分割的个数 */
    int last_fu_pack_size;  /* 最后一个分片的大小 */
    int fu_seq;             /* fu-A 序号 */

    ts_current += (90000 / 60);  /* 90000 / 25 = 3600 */

    /*
     * 加入长度判断，
     * 当 nalu_len == 0 时， 必须跳到下一轮循环
     * nalu_len == 0 时， 若不跳出会发生段错误!
     * fix by hmg
     */
    //if (nalu_len < 1) {
        //return -1;
    //}
        /*
         * single nal unit
         */
        memset(SENDBUFFER, 0, SEND_BUF_SIZE);

        /*
         * 1. 设置 rtp 头
         */
        rtp_hdr = (rtp_header_t *)SENDBUFFER;
        rtp_hdr->csrc_len = 0;
        rtp_hdr->extension = 0;
        rtp_hdr->padding = 0;
        rtp_hdr->version = 2;
        rtp_hdr->payload_type = H264;
        // rtp_hdr->marker = (pstStream->u32PackCount - 1 == i) ? 1 : 0;   /* 该包为一帧的结尾则置为1, 否则为0. rfc 1889 没有规定该位的用途 */
        rtp_hdr->seq_no = htons(++seq_num % UINT16_MAX);
        rtp_hdr->timestamp = htonl(ts_current);
        rtp_hdr->ssrc = htonl(SSRC_NUM);

        /*
         * 2. 设置rtp荷载 single nal unit 头
         */
        nalu_hdr = (nalu_header_t *)&SENDBUFFER[12];
        nalu_hdr->f = (nalu_buf[0] & 0x80) >> 7;        /* bit0 */
        nalu_hdr->nri = (nalu_buf[0] & 0x60) >> 5;      /* bit1~2 */
        nalu_hdr->type = (nalu_buf[0] & 0x1f);
        //debug_print();

        /*
         * 3. 填充nal内容
         */
        //debug_print();
        memcpy(SENDBUFFER + 13, nalu_buf + 1, nalu_len - 1);    /* 不拷贝nalu头 */

        /*
         * 4. 发送打包好的rtp到客户端
         */
        len_sendbuf = 12 + nalu_len;
        //send_data_to_client_list(SENDBUFFER, len_sendbuf, client_ip_list);
        //jbyteArray ret =

}

//在这里可以处理每一帧的数据
/*
 *
uint8_t *buf;
uint8_t *bbuf_yIn;
uint8_t *bbuf_uIn;
uint8_t *bbuf_vIn;

bool NV21toNV12(const uint8_t *src, uint8_t *dst, int width, int height)
{
    if (!src || !dst) {
        return false;
    }

    unsigned int YSize = width * height;
    unsigned int UVSize = (YSize>>1);

    // NV21: Y..Y + VUV...U
    const uint8_t *pSrcY = src;
    const uint8_t *pSrcUV = src + YSize;

    // NV12: Y..Y + UVU...V
    uint8_t *pDstY = dst;
    uint8_t *pDstUV = dst + YSize;

    // copy Y
    memcpy(pDstY, pSrcY, YSize);

    // copy U and V
    for (int k=0; k < (UVSize>>1); k++) {
        pDstUV[k * 2 + 1] = pSrcUV[k * 2];     // copy V
        pDstUV[k * 2] = pSrcUV[k * 2 + 1];   // copy U
    }

    return true;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_example_bpi_Camera2Preview_yuvToBuffer(JNIEnv *env, jobject instance,
                                                                jobject yPlane, jobject uPlane, jobject vPlane,
                                                                jint yPixelStride, jint yRowStride,
                                                                jint uPixelStride, jint uRowStride,
                                                                jint vPixelStride, jint vRowStride,
                                                                jint imgWidth, jint imgHeight) {

    bbuf_yIn = static_cast<uint8_t *>(env->GetDirectBufferAddress(yPlane));
    bbuf_uIn = static_cast<uint8_t *>(env->GetDirectBufferAddress(uPlane));
    bbuf_vIn = static_cast<uint8_t *>(env->GetDirectBufferAddress(vPlane));

    buf = (uint8_t *) malloc(sizeof(uint8_t) * imgWidth * imgHeight +
                             2 * (imgWidth + 1) / 2 * (imgHeight + 1) / 2);

    __android_log_print(ANDROID_LOG_INFO, "YUVTOBUFFER", "yPixelStride: %d, yRowStride: %d", yPixelStride, yRowStride);
    __android_log_print(ANDROID_LOG_INFO, "YUVTOBUFFER", "uPixelStride: %d, uRowStride: %d", uPixelStride, uRowStride);
    __android_log_print(ANDROID_LOG_INFO, "YUVTOBUFFER", "vPixelStride: %d, vRowStride: %d", vPixelStride, vRowStride);
    __android_log_print(ANDROID_LOG_INFO, "YUVTOBUFFER", "bbuf_yIn: %p, bbuf_uIn: %p, bbuf_vIn: %p", bbuf_yIn, bbuf_uIn, bbuf_vIn);
    __android_log_print(ANDROID_LOG_INFO, "YUVTOBUFFER", "imgWidth: %d, imgHeight: %d", imgWidth, imgHeight);

    bool isNV21;
    if (yPixelStride == 1) {
        // All pixels in a row are contiguous; copy one line at a time.
        for (int y = 0; y < imgHeight; y++)
            memcpy(buf + y * imgWidth, bbuf_yIn + y * yRowStride,
                   static_cast<size_t>(imgWidth));
    } else {
        // Highly improbable, but not disallowed by the API. In this case
        // individual pixels aren't stored consecutively but sparsely with
        // other data inbetween each pixel.
        for (int y = 0; y < imgHeight; y++)
            for (int x = 0; x < imgWidth; x++)
                buf[y * imgWidth + x] = bbuf_yIn[y * yRowStride + x * yPixelStride];
    }

    uint8_t *chromaBuf = &buf[imgWidth * imgHeight];
    int chromaBufStride = 2 * ((imgWidth + 1) / 2);



    if (uPixelStride == 2 && vPixelStride == 2 &&
        uRowStride == vRowStride && bbuf_uIn == bbuf_vIn + 1) {


        isNV21 = true;
        // The actual cb/cr planes happened to be laid out in
        // exact NV21 form in memory; copy them as is
        for (int y = 0; y < (imgHeight + 1) / 2; y++)
            memcpy(chromaBuf + y * chromaBufStride, bbuf_vIn + y * vRowStride,
                   static_cast<size_t>(chromaBufStride));


    } else if (vPixelStride == 2 && uPixelStride == 2 &&
               uRowStride == vRowStride && bbuf_vIn == bbuf_uIn + 1) {


        isNV21 = false;
        // The cb/cr planes happened to be laid out in exact NV12 form
        // in memory; if the destination API can use NV12 in addition to
        // NV21 do something similar as above, but using cbPtr instead of crPtr.
        // If not, remove this clause and use the generic code below.
    }
    else {
        isNV21 = true;
        if (vPixelStride == 1 && uPixelStride == 1) {
            // Continuous cb/cr planes; the input data was I420/YV12 or similar;
            // copy it into NV21 form
            for (int y = 0; y < (imgHeight + 1) / 2; y++) {
                for (int x = 0; x < (imgWidth + 1) / 2; x++) {
                    chromaBuf[y * chromaBufStride + 2 * x + 0] = bbuf_vIn[y * vRowStride + x];
                    chromaBuf[y * chromaBufStride + 2 * x + 1] = bbuf_uIn[y * uRowStride + x];
                }
            }
        } else {
            // Generic data copying into NV21
            for (int y = 0; y < (imgHeight + 1) / 2; y++) {
                for (int x = 0; x < (imgWidth + 1) / 2; x++) {
                    chromaBuf[y * chromaBufStride + 2 * x + 0] = bbuf_vIn[y * vRowStride +
                                                                          x * uPixelStride];
                    chromaBuf[y * chromaBufStride + 2 * x + 1] = bbuf_uIn[y * uRowStride +
                                                                          x * vPixelStride];
                }
            }
        }
    }

    if (isNV21) {
        __android_log_print(ANDROID_LOG_INFO, "YUVTOBUFFER", "isNV21");
    }
    else {
        __android_log_print(ANDROID_LOG_INFO, "YUVTOBUFFER", "isNV12");
    }

//    uint8_t *I420Buff = (uint8_t *) malloc(sizeof(uint8_t) * imgWidth * imgHeight +
//                                           2 * (imgWidth + 1) / 2 * (imgHeight + 1) / 2);
    uint8_t *NV12Buff = (uint8_t *) malloc(sizeof(uint8_t) * imgWidth * imgHeight +
                                           2 * (imgWidth + 1) / 2 * (imgHeight + 1) / 2);
//    SPtoI420(buf,I420Buff,imgWidth,imgHeight,isNV21);
    if (isNV21) {
        NV21toNV12(buf, NV12Buff, imgWidth, imgHeight);
    }
    else {
        NV12Buff = buf;
    }

    jbyteArray ret = env->NewByteArray(imgWidth * imgHeight *
                                       3/2);
    env->SetByteArrayRegion (ret, 0, imgWidth * imgHeight *
                                     3/2, (jbyte*)NV12Buff);
    free(buf);
    free(NV12Buff);
//    free (I420Buff);
    return ret;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_example_bpi_Camera2Preview_toNV12(JNIEnv *env, jobject instance,
        jobject yPlane, jobject uPlane, jobject vPlane,jint imgWidth, jint imgHeight){

    bbuf_yIn = static_cast<uint8_t *>(env->GetDirectBufferAddress(yPlane));
    bbuf_uIn = static_cast<uint8_t *>(env->GetDirectBufferAddress(uPlane));
    bbuf_vIn = static_cast<uint8_t *>(env->GetDirectBufferAddress(vPlane));

    buf = (uint8_t *) calloc(sizeof(uint8_t) * imgWidth * imgHeight*1.5, 1);

    int pix = 1920*1080;
    int white = 1920*1088;
    memcpy(buf,bbuf_yIn, pix);
    memcpy(buf+white,bbuf_uIn,  pix/4);
    memcpy(buf+white+white/4, bbuf_vIn, pix/4);

*/
/*    for(int i =0;i<pix/4;i++)
    {
        buf[pix+ i*2+0]=bbuf_uIn[i];
        buf[pix+ i*2+1]=bbuf_vIn[i];
    }*//*

    jbyteArray ret = env->NewByteArray(imgWidth * imgHeight *
                                       3/2);
    env->SetByteArrayRegion (ret, 0, imgWidth * imgHeight *
                                     3/2, (jbyte*)buf);
    free(buf);
    return ret;
}*/
