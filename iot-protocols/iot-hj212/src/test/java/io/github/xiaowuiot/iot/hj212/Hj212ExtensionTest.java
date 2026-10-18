package io.github.xiaowuiot.iot.hj212;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Hj212ExtensionTest {

    /**
     * 2005 版：Flag=1（只有"需要应答"，没有版本号），MN 14 位，因子编码 011 / 060 / 001
     */
    static final String DATA_2005 = "QN=20261018080000000;ST=32;CN=2011;PW=123456;MN=88888880000002;Flag=1;"
            + "CP=&&DataTime=20261018080000;011-Rtd=48.6,011-Flag=N;060-Rtd=1.32,060-Flag=N;001-Rtd=7.21,001-Flag=N&&";

    @AfterEach
    void tearDown() {
        Hj212Factors.unregister("w99001");
    }

    @Test
    void detectAndNormalize2005() {
        Hj212Packet p = Hj212Format.parse(DATA_2005);
        assertEquals(Hj212Versions.V2005, Hj212Versions.detect(p));
        Map<String, String> original = new HashMap<>();
        Hj212Packet n = Hj212Versions.normalize(p, original);
        assertEquals("48.6", n.getFactors().get("w01018").get("Rtd"));
        assertEquals("1.32", n.getFactors().get("w21003").get("Rtd"));
        assertEquals("011", original.get("w01018"));
        // 原包不变
        assertTrue(p.getFactors().containsKey("011"));
        // 应答用对方的版本：2005 版回 Flag=0
        assertEquals(0, p.dataAck().getFlag());
        assertTrue(Hj212Format.format(p.dataAck()).contains("Flag=0;"));
    }

    @Test
    void v2017Untouched() {
        Hj212Packet p = Hj212Format.parse(Hj212Test.REALTIME_DATA);
        assertEquals(Hj212Versions.V2017, Hj212Versions.detect(p));
        assertSame(p, Hj212Versions.normalize(p, null));
        assertEquals(4, p.dataAck().getFlag());
    }

    @Test
    void customFactorCannotOverrideStandard() {
        Hj212Factors.register("w99001", "雨水排口液位", "m", 2);
        assertEquals("雨水排口液位", Hj212Factors.get("w99001").name);
        assertTrue(Hj212Factors.get("w99001").custom);
        assertThrows(IllegalArgumentException.class, () -> Hj212Factors.register("w01018", "我的 COD", "mg/L", 1));
        assertFalse(Hj212Factors.get("w01018").custom);
    }

    /**
     * 不认识的因子、不认识的参数：解析不报错，原样保留
     */
    @Test
    void unknownCodesKept() {
        String data = "QN=20261018080000000;ST=32;CN=2011;PW=123456;MN=010000A8900016F000169DC0;Flag=5;"
                + "CP=&&DataTime=20261018080000;DevVer=V2.1;w01018-Rtd=48.6,w01018-Flag=N;w88123-Rtd=3.3,w88123-Flag=N&&";
        Hj212Packet p = Hj212Format.parse(data);
        assertEquals("V2.1", p.getParam("DevVer"));
        assertEquals("3.3", p.getFactors().get("w88123").get("Rtd"));
        assertNull(Hj212Factors.get("w88123"));
        assertTrue(Hj212Factors.isFactorCode("w88123"));
        assertFalse(Hj212Factors.isFactorCode("i13001"));
        assertFalse(Hj212Factors.isFactorCode("011"));
    }
}
