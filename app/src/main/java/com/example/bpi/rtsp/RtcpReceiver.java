package com.example.bpi.rtsp;

//------------------------
//Listener for RTCP packets sent from client
//------------------------
class RtcpReceiver {
    //private Timer rtcpTimer;
    private byte[] rtcpBuf;
    int interval;

    /*public RtcpReceiver(int interval) {
        //set timer with interval for receiving packets
        this.interval = interval;
        rtcpTimer = new Timer(interval, this);
        rtcpTimer.setInitialDelay(0);
        rtcpTimer.setCoalesce(true);

        //allocate buffer for receiving RTCP packets
        rtcpBuf = new byte[512];
    }

    public void actionPerformed(ActionEvent e) {
        //Construct a DatagramPacket to receive data from the UDP socket
        DatagramPacket dp = new DatagramPacket(rtcpBuf, rtcpBuf.length);
        float fractionLost;

        try {
            RTCPsocket.receive(dp);   // Blocking
            RTCPpacket rtcpPkt = new RTCPpacket(dp.getData(), dp.getLength());
            System.out.println("[RTCP] " + rtcpPkt);

            //set congestion level between 0 to 4
            fractionLost = rtcpPkt.fractionLost;
            if (fractionLost >= 0 && fractionLost <= 0.01) {
                congestionLevel = 0;    //less than 0.01 assume negligible
            }
            else if (fractionLost > 0.01 && fractionLost <= 0.25) {
                congestionLevel = 1;
            }
            else if (fractionLost > 0.25 && fractionLost <= 0.5) {
                congestionLevel = 2;
            }
            else if (fractionLost > 0.5 && fractionLost <= 0.75) {
                congestionLevel = 3;
            }
            else {
                congestionLevel = 4;
            }
        }
        catch (InterruptedIOException iioe) {
            System.out.println("Nothing to read");
        }
        catch (IOException ioe) {
            System.out.println("Exception caught: "+ioe);
        }
    }

    public void startRcv() {
        rtcpTimer.start();
    }

    public void stopRcv() {
        rtcpTimer.stop();
    }*/
}
