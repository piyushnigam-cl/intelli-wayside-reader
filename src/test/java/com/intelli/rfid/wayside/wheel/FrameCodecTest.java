package com.intelli.rfid.wayside.wheel;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FrameCodecTest {

    /** The standard check value. The firmware's CRC must produce the same for the same input. */
    @Test
    void crcMatchesTheCcittFalseCheckValue() {
        byte[] data = "123456789".getBytes(StandardCharsets.US_ASCII);
        assertThat(Crc16.ccittFalse(data, 0, data.length)).isEqualTo(0x29B1);
    }

    @Test
    void cobsRoundTripsZerosAndLongRuns() {
        Random random = new Random(7);
        for (int length : new int[] {0, 1, 253, 254, 255, 256, 600}) {
            for (int trial = 0; trial < 20; trial++) {
                byte[] data = new byte[length];
                random.nextBytes(data);
                if (trial % 3 == 0) {
                    java.util.Arrays.fill(data, (byte) 0);
                } else if (trial % 3 == 1) {
                    java.util.Arrays.fill(data, (byte) 7);
                }
                byte[] encoded = Cobs.encode(data);
                for (byte b : encoded) {
                    assertThat(b).isNotZero();
                }
                assertThat(Cobs.decode(encoded, encoded.length)).isEqualTo(data);
            }
        }
    }

    @Test
    void framesSurviveArbitraryChunking() {
        List<byte[]> wire = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            wire.add(FrameCodec.encode(new Frame(1, WheelMessage.SYSTEM_EDGE, i,
                    WheelMessages.edge(new WheelMessage.SystemEdge(0xFFFFFF00L + i, i % 4, true, 0)))));
        }
        byte[] all = concat(wire);
        FrameCodec codec = new FrameCodec();
        List<Frame> frames = new ArrayList<>();
        for (int i = 0; i < all.length; i += 3) {
            frames.addAll(codec.feed(all, i, Math.min(3, all.length - i)));
        }
        assertThat(frames).hasSize(5);
        assertThat(frames).extracting(Frame::seq).containsExactly(0, 1, 2, 3, 4);
        WheelMessage.SystemEdge edge = (WheelMessage.SystemEdge) WheelMessages.decode(frames.get(4));
        assertThat(edge.tick()).isEqualTo(0xFFFFFF04L);
        assertThat(edge.channel()).isEqualTo(0);
    }

    /** A torn or corrupted frame costs that frame and nothing after it. */
    @Test
    void aBadFrameIsCountedAndTheStreamResynchronises() {
        byte[] good = FrameCodec.encode(new Frame(1, WheelMessage.ACK, 9,
                WheelMessages.ack(new WheelMessage.Ack(3, 0))));
        byte[] corrupt = good.clone();
        corrupt[3] ^= 0x10;
        if (corrupt[3] == 0) {
            corrupt[3] = 0x55;
        }
        byte[] noise = {0x11, 0x22, 0x33, 0x00};
        FrameCodec codec = new FrameCodec();
        byte[] all = concat(List.of(noise, corrupt, good));
        List<Frame> frames = codec.feed(all, 0, all.length);
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).seq()).isEqualTo(9);
        assertThat(codec.crcErrors() + codec.malformed()).isEqualTo(2);
    }

    @Test
    void everyMessageDecodesToWhatWasEncoded() {
        WheelMessage.SystemPulse pulse = new WheelMessage.SystemPulse(3, 4000000000L, 4000001800L,
                9820, 123456);
        Frame frame = new Frame(1, WheelMessage.SYSTEM_PULSE, 1, WheelMessages.pulse(pulse));
        assertThat(WheelMessages.decode(frame)).isEqualTo(pulse);

        WheelMessage.Hello hello = new WheelMessage.Hello(1, 20261001L, 4, 5000, 1000000, 0x40);
        assertThat(WheelMessages.decode(new Frame(1, WheelMessage.HELLO, 0, WheelMessages.hello(hello))))
                .isEqualTo(hello);

        WheelMessage.ChannelFault fault = new WheelMessage.ChannelFault(2,
                WheelMessage.FaultKind.OPEN, 12);
        assertThat(WheelMessages.decode(new Frame(1, WheelMessage.CHANNEL_FAULT, 0,
                WheelMessages.fault(fault)))).isEqualTo(fault);
    }

    private static byte[] concat(List<byte[]> parts) {
        int length = parts.stream().mapToInt(p -> p.length).sum();
        byte[] all = new byte[length];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, all, at, part.length);
            at += part.length;
        }
        return all;
    }
}
