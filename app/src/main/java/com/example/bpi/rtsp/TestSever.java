package com.example.bpi.rtsp;

import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.StringTokenizer;
import java.util.Timer;
import java.util.UUID;

public class TestSever {
    //RTP variables:
    //----------------
    DatagramSocket RTPsocket; //socket to be used to send and receive UDP packets
    DatagramPacket senddp; //UDP packet containing the video frames

    InetAddress ClientIPAddr;   //Client IP address
    public int RTP_dest_port = 0;      //destination port for RTP packets  (given by the RTSP Client)
    public int RTSP_dest_port = 0;

    //Video variables:
    //----------------
    int imagenb = 0; //image nb of the image currently transmitted
    //VideoStream video; //VideoStream object used to access video frames
    private int MJPEG_TYPE = 26; //RTP payload type for MJPEG video
    private int FRAME_PERIOD = 100; //Frame period of the video to stream, in ms
    private int VIDEO_LENGTH = 500; //length of the video in frames

    //Timer timer;    //timer used to send the images at the video frame rate
    private byte[] buf;     //buffer used to store the images to send to the client
    private int sendDelay;  //the delay to send images over the wire. Ideally should be
    //equal to the frame rate of the video file, but may be
    //adjusted when congestion is detected.

    //RTSP variables
    //----------------
    //rtsp states
    private final int INIT = 0;
    private final int READY = 1;
    private final int PLAYING = 2;
    //rtsp message types
    private final int SETUP = 3;
    private final int PLAY = 4;
    private final int PAUSE = 5;
    private final int TEARDOWN = 6;
    private final int DESCRIBE = 7;

    private int state; //RTSP Server state == INIT or READY or PLAY
    private Socket RTSPsocket; //socket used to send/receive RTSP messages
    //input and output stream filters
    private BufferedReader RTSPBufferedReader;
    private BufferedWriter RTSPBufferedWriter;
    private String VideoFileName; //video file requested from the client
    private String RTSPid = UUID.randomUUID().toString(); //ID of the RTSP session
    private int RTSPSeqNb = 0; //Sequence number of RTSP messages within the session

    //RTCP variables
    //----------------
    private int RTCP_RCV_PORT = 19001; //port where the client will receive the RTP packets
    private int RTCP_PERIOD = 400;     //How often to check for control events
    private DatagramSocket RTCPsocket;
    private RtcpReceiver rtcpReceiver;
    private int congestionLevel;

    private final String CRLF = "\r\n";

    public TestSever(int port){
        RTSP_dest_port = port;

        //Initiate TCP connection with the client for the RTSP session
        try{
            ServerSocket listenSocket = new ServerSocket(port);
            RTSPsocket = listenSocket.accept();
            listenSocket.close();
        }catch(IOException e){
            e.printStackTrace();
        }

        //Get Client IP address
        ClientIPAddr = RTSPsocket.getInetAddress();

        //Initiate RTSPstate
        state = INIT;

        //Set input and output stream filters:
        try{
            RTSPBufferedReader = new BufferedReader(new InputStreamReader(RTSPsocket.getInputStream()) );
            RTSPBufferedWriter = new BufferedWriter(new OutputStreamWriter(RTSPsocket.getOutputStream()) );
        }catch(IOException e){
            e.printStackTrace();
        }

        //Wait for the SETUP message from the client
        int request_type;
        boolean done = false;
        while(!done) {
            request_type = parseRequest(); //blocking

            if (request_type == SETUP) {
                done = true;

                //update RTSP state
                state = READY;
                Log.d("RTSP", "New RTSP state: READY");

                //Send response
                sendResponse();

                //init the VideoStream object:
                //video = new VideoStream(VideoFileName);

                //init RTP and RTCP sockets
                try{
                    RTPsocket = new DatagramSocket();
                    RTCPsocket = new DatagramSocket(RTCP_RCV_PORT);
                }catch(SocketException e){
                    e.printStackTrace();
                }
            }
        }
        //loop to handle RTSP requests
        while(true) {
            //parse the request
            request_type = parseRequest(); //blocking

            if ((request_type == PLAY) && (state == READY)) {
                //send back response
                sendResponse();
                //start timer
                //timer.start();
                //rtcpReceiver.startRcv();
                //update state
                state = PLAYING;
                System.out.println("New RTSP state: PLAYING");
            }
            else if ((request_type == PAUSE) && (state == PLAYING)) {
                //send back response
                sendResponse();
                //stop timer
                //timer.stop();
                //rtcpReceiver.stopRcv();
                //update state
                state = READY;
                System.out.println("New RTSP state: READY");
            }
            else if (request_type == TEARDOWN) {
                //send back response
                sendResponse();
                //stop timer
                //timer.stop();
                //rtcpReceiver.stopRcv();
                //close sockets
                try{
                    RTSPsocket.close();
                    RTPsocket.close();
                }catch(IOException e){
                    e.printStackTrace();
                }

                System.exit(0);
            }
            else if (request_type == DESCRIBE) {
                System.out.println("Received DESCRIBE request");
                sendDescribe();
            }
        }

    }
    //------------------------------------
    //Parse RTSP Request
    //------------------------------------
    private int parseRequest() {
        int request_type = -1;
        try {
            //parse request line and extract the request_type:
            String RequestLine = RTSPBufferedReader.readLine();
            System.out.println("RTSP Server - Received from Client:");
            System.out.println(RequestLine);

            StringTokenizer tokens = new StringTokenizer(RequestLine);
            String request_type_string = tokens.nextToken();

            //convert to request_type structure:
            if ((new String(request_type_string)).compareTo("SETUP") == 0)
                request_type = SETUP;
            else if ((new String(request_type_string)).compareTo("PLAY") == 0)
                request_type = PLAY;
            else if ((new String(request_type_string)).compareTo("PAUSE") == 0)
                request_type = PAUSE;
            else if ((new String(request_type_string)).compareTo("TEARDOWN") == 0)
                request_type = TEARDOWN;
            else if ((new String(request_type_string)).compareTo("DESCRIBE") == 0)
                request_type = DESCRIBE;

            if (request_type == SETUP) {
                //extract VideoFileName from RequestLine
                VideoFileName = tokens.nextToken();
            }

            //parse the SeqNumLine and extract CSeq field
            String SeqNumLine = RTSPBufferedReader.readLine();
            System.out.println(SeqNumLine);
            tokens = new StringTokenizer(SeqNumLine);
            tokens.nextToken();
            RTSPSeqNb = Integer.parseInt(tokens.nextToken());

            //get LastLine
            String LastLine = RTSPBufferedReader.readLine();
            System.out.println(LastLine);

            tokens = new StringTokenizer(LastLine);
            if (request_type == SETUP) {
                //extract RTP_dest_port from LastLine
                for (int i=0; i<3; i++)
                    tokens.nextToken(); //skip unused stuff
                RTP_dest_port = Integer.parseInt(tokens.nextToken());
            }
            else if (request_type == DESCRIBE) {
                tokens.nextToken();
                String describeDataType = tokens.nextToken();
            }
            else {
                //otherwise LastLine will be the SessionId line
                tokens.nextToken(); //skip Session:
                RTSPid = tokens.nextToken();
            }
        } catch(Exception ex) {
            System.out.println("Exception caught: "+ex);
            System.exit(0);
        }

        return(request_type);
    }

    // Creates a DESCRIBE response string in SDP format for current media
    private String describe() {
        StringWriter writer1 = new StringWriter();
        StringWriter writer2 = new StringWriter();

        // Write the body first so we can get the size later
        writer2.write("v=0" + CRLF);
        writer2.write("m=video " + RTSP_dest_port + " RTP/AVP " + MJPEG_TYPE + CRLF);
        writer2.write("a=control:streamid=" + RTSPid + CRLF);
        writer2.write("a=mimetype:string;\"video/MJPEG\"" + CRLF);
        String body = writer2.toString();

        writer1.write("Content-Base: " + VideoFileName + CRLF);
        writer1.write("Content-Type: " + "application/sdp" + CRLF);
        writer1.write("Content-Length: " + body.length() + CRLF);
        writer1.write(body);

        return writer1.toString();
    }

    //------------------------------------
    //Send RTSP Response
    //------------------------------------
    private void sendResponse() {
        try {
            RTSPBufferedWriter.write("RTSP/1.0 200 OK"+CRLF);
            RTSPBufferedWriter.write("CSeq: "+RTSPSeqNb+CRLF);
            RTSPBufferedWriter.write("Session: "+RTSPid+CRLF);
            RTSPBufferedWriter.flush();
            System.out.println("RTSP Server - Sent response to Client.");
        } catch(Exception ex) {
            System.out.println("Exception caught: "+ex);
            System.exit(0);
        }
    }

    private void sendDescribe() {
        String des = describe();
        try {
            RTSPBufferedWriter.write("RTSP/1.0 200 OK"+CRLF);
            RTSPBufferedWriter.write("CSeq: "+RTSPSeqNb+CRLF);
            RTSPBufferedWriter.write(des);
            RTSPBufferedWriter.flush();
            System.out.println("RTSP Server - Sent response to Client.");
        } catch(Exception ex) {
            System.out.println("Exception caught: "+ex);
            System.exit(0);
        }
    }
}
