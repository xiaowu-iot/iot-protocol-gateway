package io.github.xiaowuiot.iot.hj212;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * HJ212-2025 的数据段加密（标准 6.4 和附录 A.2）。
 * <ul>
 *     <li>算法 SM4，密钥 16 字节，ECB 模式，不填充</li>
 *     <li>只加密 {@code CP=&&} 和结尾 {@code &&} 之间的内容；每 16 字节一组，最后不足 16 字节的部分保持明文</li>
 *     <li>先按明文算长度和 CRC、组好包，再把 CP 内容替换成密文。所以密文和明文一样长，长度字段、CRC 都是明文的</li>
 *     <li>接收方先解密，再校验 CRC</li>
 * </ul>
 * 密文是原始字节，直接替换在报文里（附录 A.2 的示例把它写成 {0xE4,0x3E,...} 只是为了印出来看）。
 * 报文里没有"是否加密"的标志位：要加密的命令（2000~2999 数据命令、1014 密钥、3020 现场机信息）
 * 和其他命令靠 CN 区分，接收时先按明文校验 CRC，不对再用设备的密钥解密后校验。
 * <p>
 * 密钥（NewSK）在报文里是 16 个字符（0~9、a~z、A~Z），按 ASCII 取字节就是 SM4 的 16 字节密钥；
 * 附录 A.2 的示例密钥是 16 个 0x30，也就是字符串 "0000000000000000"。
 */
public final class Hj212Crypto {

    public static final int KEY_LENGTH = 16;

    private static final String KEY_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] CP_START = Hj212Format.CP_START.getBytes(StandardCharsets.US_ASCII);

    private Hj212Crypto() {
    }

    /**
     * 标准 6.4.4 规定要加密的命令：现场机上传的数据命令（2000~2999）、密钥命令 1014、现场机信息 3020。
     * 9011~9014 这些应答不加密
     */
    public static boolean mustEncrypt(String cn) {
        return cn != null && (cn.startsWith("2") && cn.length() == 4 || Hj212Commands.SECRET_KEY.equals(cn)
                || Hj212Commands.GET_INFO.equals(cn));
    }

    /**
     * 报文里的 16 位密钥字符串 → SM4 密钥
     */
    public static byte[] key(String text) {
        if (text == null || text.length() != KEY_LENGTH) {
            throw new IllegalArgumentException("密钥要 16 个字符");
        }
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * 随机生成一个新密钥（标准 6.4.3：随机产生，不能是固定值、顺序值）
     */
    public static String newKey() {
        StringBuilder sb = new StringBuilder(KEY_LENGTH);
        for (int i = 0; i < KEY_LENGTH; i++) {
            sb.append(KEY_CHARS.charAt(RANDOM.nextInt(KEY_CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * 加密数据段（明文字节）。返回新数组，长度不变
     */
    public static byte[] encrypt(byte[] data, byte[] key) {
        return transform(data, key, true);
    }

    /**
     * 解密数据段（报文里收到的字节）。返回新数组，长度不变
     */
    public static byte[] decrypt(byte[] data, byte[] key) {
        return transform(data, key, false);
    }

    private static byte[] transform(byte[] data, byte[] key, boolean encrypt) {
        int start = indexOf(data, CP_START);
        int end = data.length - 2;
        if (start < 0 || end < start + CP_START.length || data[end] != '&' || data[end + 1] != '&') {
            throw new IllegalArgumentException("找不到 CP=&&...&&");
        }
        start += CP_START.length;
        int blocks = (end - start) / 16 * 16;
        byte[] out = data.clone();
        if (blocks > 0) {
            byte[] part = new byte[blocks];
            System.arraycopy(data, start, part, 0, blocks);
            byte[] done = encrypt ? Sm4.encryptEcb(key, part) : Sm4.decryptEcb(key, part);
            System.arraycopy(done, 0, out, start, blocks);
        }
        return out;
    }

    private static int indexOf(byte[] data, byte[] target) {
        outer:
        for (int i = 0; i <= data.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (data[i + j] != target[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
