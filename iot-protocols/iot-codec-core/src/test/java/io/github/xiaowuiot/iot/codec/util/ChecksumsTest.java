package io.github.xiaowuiot.iot.codec.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ChecksumsTest {

    @Test
    void bcc() {
        assertEquals(0x00, Checksums.bcc(new byte[]{0x01, 0x02, 0x03}));
        assertEquals(0x7E, Checksums.bcc(new byte[]{0x7E}));
        // 808 心跳：消息头 0002 0000 013800138000 0001
        assertEquals(0xA9, Checksums.bcc(HexUtils.fromHex("0002 0000 013800138000 0001")));
    }

    @Test
    void crc16Modbus() {
        // CRC 算法通用的测试向量
        assertEquals(0x4B37, Checksums.crc16Modbus("123456789".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void crc16Hj212() {
        // HJ212-2017 标准中的示例报文，数据段的 CRC 为 1C80
        String data = "QN=20160801085857223;ST=32;CN=1062;PW=100000;"
                + "MN=010000A8900016F000169DC0;Flag=5;CP=&&RtdInterval=30&&";
        assertEquals(0x1C80, Checksums.crc16Hj212(data.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void crc16CcittFalse() {
        assertEquals(0x29B1, Checksums.crc16CcittFalse("123456789".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void hj212DiffersFromModbus() {
        byte[] data = "123456789".getBytes(StandardCharsets.US_ASCII);
        assertNotEquals(Checksums.crc16Modbus(data), Checksums.crc16Hj212(data));
    }
}
