package io.github.xiaowuiot.iot.simulator.web;

import io.github.xiaowuiot.iot.hj212.Hj212Crypto;
import io.github.xiaowuiot.iot.hj212.Hj212Format;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameInspectorTest {

    static final String A1 = "##0087QN=20240601085857223;ST=32;CN=1011;PW=123456;MN=010000A8900016F000169DC0;Flag=9;CP=&&&&2200";

    @Test
    void appendixA1() {
        Map<String, Object> j = FrameInspector.inspect(FrameInspector.toFrame(A1), null).json;
        assertEquals(true, j.get("crcOk"));
        assertEquals(true, j.get("lengthOk"));
        assertEquals("2025", packet(j).get("version"));
        assertEquals("提取现场机时间", packet(j).get("cnName"));
    }

    @Test
    void acceptsDataSegmentAndHex() {
        String data = A1.substring(6, A1.length() - 4);
        assertEquals(A1 + "\r\n", new String(FrameInspector.toFrame(data), StandardCharsets.UTF_8));
        String hex = FrameInspector.hex(FrameInspector.toFrame(A1));
        assertEquals(true, FrameInspector.inspect(FrameInspector.toFrame(hex), null).json.get("crcOk"));
    }

    @Test
    void encryptedFrameNeedsKey() {
        String data = "QN=20240520210700000;ST=31;CN=2051;PW=123456;MN=010000A8900016F000169DC0;Flag=9;"
                + "CP=&&DataTime=20240520210600;a01011-Min=1.35,a01011-Avg=1.35,a01011-Max=1.35,a01011-Flag=N&&";
        byte[] key = Hj212Crypto.key("0000000000000000");
        byte[] frame = Hj212Format.frameBytes(data, key);
        Map<String, Object> no = FrameInspector.inspect(frame, Collections.emptyList()).json;
        assertEquals(false, no.get("crcOk"));
        Map<String, Object> yes = FrameInspector.inspect(frame, List.of(key)).json;
        assertEquals(true, yes.get("crcOk"));
        assertEquals(true, yes.get("encrypted"));
        assertTrue(((String) yes.get("plain")).contains("DataTime=20240520210600"));
        assertFalse(((String) yes.get("text")).contains("DataTime"));
    }

    @Test
    void chineseLogStaysReadable() {
        String data = "QN=1;ST=32;CN=3020;PW=123456;MN=M;Flag=9;CP=&&PolId=w01018;i11001-Info=//清洗管路;完成//&&";
        Map<String, Object> j = FrameInspector.inspect(Hj212Format.frameBytes(data, null), null).json;
        assertTrue(((String) j.get("text")).contains("清洗管路"));
        assertEquals(true, j.get("crcOk"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> packet(Map<String, Object> j) {
        return (Map<String, Object>) j.get("packet");
    }
}
