package io.github.xiaowuiot.iot.gateway.hj212;

import io.github.xiaowuiot.iot.gateway.message.DeviceMessage;
import io.github.xiaowuiot.iot.hj212.Hj212Format;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Hj212V2025ConvertTest {

    @Test
    void secretKeysByMn() {
        Map<String, List<byte[]>> keys = Hj212Config.secretKeys(
                Map.of("010000A8900016F000169DC0", "0000000000000000, 1111111111111111"));
        // MN 不区分大小写，过渡期两把密钥
        assertEquals(2, keys.get("010000a8900016f000169dc0").size());
        assertThrows(IllegalArgumentException.class, () -> Hj212Config.secretKeys(Map.of("M", "123")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void refillAndFlagNameByVersion() {
        DeviceMessage m = Hj212MessageConverter.convert(Hj212Format.parse(
                "QN=20240601085857223;ST=31;CN=2061;PW=123456;MN=010000A8900016F000169DC0;Flag=9;RF=1;"
                        + "CP=&&DataTime=20240601080000;a01011-Avg=1.35,a01011-Flag=B&&"));
        assertEquals("2025", m.data().get("protocolVersion"));
        assertEquals(true, m.data().get("refill"));
        Map<String, Map<String, Object>> factors = (Map<String, Map<String, Object>>) m.data().get("factors");
        assertEquals("闷炉", factors.get("a01011").get("flagName"));
    }
}
