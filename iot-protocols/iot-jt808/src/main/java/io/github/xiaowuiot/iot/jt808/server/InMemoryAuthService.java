package io.github.xiaowuiot.iot.jt808.server;

import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.github.xiaowuiot.iot.jt808.body.Auth;
import io.github.xiaowuiot.iot.jt808.body.Register;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 演示用：任何终端都允许注册，鉴权码存在内存里。
 * <p>
 * <b>生产环境不能这么用</b>：平台一重启鉴权码就丢了，所有终端鉴权失败。
 * 终端收到鉴权失败后会重新注册，但很多终端的实现是"鉴权失败就一直重试鉴权"，结果就是全部设备掉线。
 * 鉴权码必须持久化，并且要和车辆档案关联起来校验。
 */
public class InMemoryAuthService implements Jt808AuthService {

    private static final char[] CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final SecureRandom random = new SecureRandom();
    private final Map<String, String> authCodes = new ConcurrentHashMap<>();
    private final Map<String, Register> registers = new ConcurrentHashMap<>();

    @Override
    public RegisterResult register(Jt808Header header, Register register) {
        registers.put(header.deviceId(), register);
        // 同一台终端重复注册（比如它丢了鉴权码），给它同一个鉴权码
        String code = authCodes.computeIfAbsent(header.deviceId(), k -> newCode());
        return RegisterResult.success(code);
    }

    @Override
    public boolean authenticate(Jt808Header header, Auth auth) {
        return auth.getAuthCode() != null && auth.getAuthCode().equals(authCodes.get(header.deviceId()));
    }

    /**
     * 终端注册时上报的信息（车牌、车牌颜色等）。809 转发要用车牌标识车辆。
     * 生产环境车牌应该来自车辆档案，不能只靠终端注册时报的值
     */
    public Register registerInfo(String deviceId) {
        return registers.get(deviceId);
    }

    /**
     * 按车牌找终端：809 上级平台的指令（拍照、督办）只带车牌，要找到对应的终端才能下发 808 指令
     */
    public String deviceIdByPlate(String plate, int plateColor) {
        for (Map.Entry<String, Register> e : registers.entrySet()) {
            Register r = e.getValue();
            if (plate.equals(r.getPlate()) && r.getPlateColor() == plateColor) {
                return e.getKey();
            }
        }
        return null;
    }

    /**
     * 测试和模拟器用：预先放入鉴权码，跳过注册
     */
    public void put(String deviceId, String authCode) {
        authCodes.put(deviceId, authCode);
    }

    private String newCode() {
        char[] c = new char[12];
        for (int i = 0; i < c.length; i++) {
            c[i] = CHARS[random.nextInt(CHARS.length)];
        }
        return new String(c);
    }
}
