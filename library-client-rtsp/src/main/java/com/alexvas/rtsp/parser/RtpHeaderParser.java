package com.alexvas.rtsp.parser;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alexvas.utils.NetUtils;

import java.io.IOException;
import java.io.InputStream;

public class RtpHeaderParser {

    private static final String TAG = RtpHeaderParser.class.getSimpleName();
    private static final boolean DEBUG = false;

    private final static int RTP_HEADER_SIZE = 12;

    public static class RtpHeader {
        public int version;
        public int padding;
        public int extension;
        public int cc;
        public int marker;
        public int payloadType;
        public int sequenceNumber;
        public long timeStamp;
        public long ssrc;
        public int payloadSize;
        public int channel; // interleaved channel (TCP)

        public long getTimestampUs(long clockRate) {
            if (clockRate <= 0) return 0L;
            return (timeStamp * 1000000L) / clockRate;
        }

        // If RTP header found, return 4 bytes of the header
        private static boolean searchForNextRtpHeader(@NonNull InputStream inputStream, @NonNull byte[] header /*out*/) throws IOException {
            if (header.length < 4)
                throw new IOException("Invalid allocated buffer size");

            int bytesRemaining = 100000; // 100 KB max to check
            boolean foundFirstByte = false;
            boolean foundSecondByte = false;
            byte[] oneByte = new byte[1];
            // Search for interleaved frame header {0x24, channel}
            do {
                if (bytesRemaining-- < 0)
                    return false;
                // Read 1 byte
                NetUtils.readData(inputStream, oneByte, 0, 1);
                if (foundFirstByte) {
                    // Found 0x24. Checking channel 0..3
                    int channel = oneByte[0] & 0xFF;
                    if (channel <= 3)
                        foundSecondByte = true;
                    else
                        foundFirstByte = false;
                }
                if (!foundFirstByte && oneByte[0] == 0x24) {
                    // Found 0x24
                    foundFirstByte = true;
                }
            } while (!foundSecondByte);
            header[0] = 0x24;
            header[1] = oneByte[0];
            // Read 2 bytes more (packet size)
            NetUtils.readData(inputStream, header, 2, 2);
            return true;
        }

        @Nullable
        private static RtpHeader parseData(@NonNull byte[] header, int packetSize) {
            RtpHeader rtpHeader = new RtpHeader();
            rtpHeader.version = (header[0] & 0xFF) >> 6;
            if (rtpHeader.version != 2) {
                if (DEBUG)
                    Log.e(TAG,"Not a RTP packet (" + rtpHeader.version + ")");
                return null;
            }

            // 80 60 40 91 fd ab d4 2a
            // 80 c8 00 06
            rtpHeader.padding = (header[0] & 0x20) >> 5; // 0b00100100
            rtpHeader.extension = (header[0] & 0x10) >> 4;
            rtpHeader.marker = (header[1] & 0x80) >> 7;
            rtpHeader.payloadType = header[1] & 0x7F;
            rtpHeader.sequenceNumber = (header[3] & 0xFF) + ((header[2] & 0xFF) << 8);
            rtpHeader.timeStamp = ((header[4] & 0xFFL) << 24) | ((header[5] & 0xFFL) << 16) | ((header[6] & 0xFFL) << 8) | (header[7] & 0xFFL);
            rtpHeader.ssrc = ((header[8] & 0xFFL) << 24) | ((header[9] & 0xFFL) << 16) | ((header[10] & 0xFFL) << 8) | (header[11] & 0xFFL);
            rtpHeader.payloadSize = packetSize - RTP_HEADER_SIZE;
            return rtpHeader;
        }

        private static int getPacketSize(@NonNull byte[] header) {
            int packetSize = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
            if (DEBUG)
                Log.d(TAG, "Packet size: " + packetSize);
            return packetSize;
        }

        public void dumpHeader() {
            Log.d("RTP","\t\tRTP header version: " + version
                    + ", padding: " + padding
                    + ", ext: " + extension
                    + ", cc: " + cc
                    + ", marker: " + marker
                    + ", payload type: " + payloadType
                    + ", seq num: " + sequenceNumber
                    + ", ts: " + timeStamp
                    + ", ssrc: " + ssrc
                    + ", payload size: " + payloadSize);
        }
    }

    @Nullable
    public static RtpHeader readHeader(@NonNull InputStream inputStream) throws IOException {
        byte[] header = new byte[RTP_HEADER_SIZE];
        while (true) {
            // Skip 4 bytes (TCP only). Interleaved header: 0x24 [channel] [packet size (2 bytes)]
            if (NetUtils.readData(inputStream, header, 0, 4) < 4) return null;
            
            if (header[0] != 0x24) {
                // Not a valid interleaved header. Try searching for the next one.
                if (!RtpHeader.searchForNextRtpHeader(inputStream, header)) return null;
            }

            int channel = header[1] & 0xFF;
            int packetSize = RtpHeader.getPacketSize(header);

            if ((channel & 0x01) != 0) {
                // Skip interleaved RTCP packets (odd channels)
                int remaining = packetSize;
                byte[] skipBuffer = new byte[Math.min(packetSize, 4096)];
                while (remaining > 0) {
                    int toRead = Math.min(remaining, skipBuffer.length);
                    NetUtils.readData(inputStream, skipBuffer, 0, toRead);
                    remaining -= toRead;
                }
                // Continue loop to find next RTP header
                continue;
            }

            if (DEBUG)
                Log.d(TAG, "Packet size: " + packetSize);

            if (NetUtils.readData(inputStream, header, 0, header.length) == header.length) {
                RtpHeader rtpHeader = RtpHeader.parseData(header, packetSize);
                if (rtpHeader != null) {
                    rtpHeader.channel = channel;
                    return rtpHeader;
                }
                // Not a valid RTP header. The loop will continue.
                // We should probably read 1 byte to advance if search didn't happen.
            } else {
                return null;
            }
        }
    }
}
