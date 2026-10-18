package io.github.xiaowuiot.iot.jt808.server;

import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.github.xiaowuiot.iot.jt808.body.Auth;
import io.github.xiaowuiot.iot.jt808.body.Register;
import io.github.xiaowuiot.iot.jt808.body.RegisterResponse;

/**
 * 注册和鉴权的业务规则，由使用方实现：这台终端能不能注册、鉴权码怎么生成、存在哪里。
 * <p>
 * 方法在 IO 线程里调用，实现里如果要查数据库，务必加缓存，否则大量终端同时重连时会拖垮 IO 线程。
 */
public interface Jt808AuthService {

    /**
     * @return 注册结果，成功时带上鉴权码，结果码见 {@link RegisterResponse}
     */
    RegisterResult register(Jt808Header header, Register register);

    boolean authenticate(Jt808Header header, Auth auth);

    final class RegisterResult {
        private final int result;
        private final String authCode;

        private RegisterResult(int result, String authCode) {
            this.result = result;
            this.authCode = authCode;
        }

        public static RegisterResult success(String authCode) {
            return new RegisterResult(RegisterResponse.SUCCESS, authCode);
        }

        public static RegisterResult fail(int result) {
            return new RegisterResult(result, null);
        }

        public int getResult() {
            return result;
        }

        public String getAuthCode() {
            return authCode;
        }
    }
}
