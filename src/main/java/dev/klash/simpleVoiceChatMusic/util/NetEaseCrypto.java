package dev.klash.simpleVoiceChatMusic.util;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 网易云音乐 weapi / eapi 加密。
 *
 * <p>与 NeteaseCloudMusicApi 的 {@code util/crypto.js} 等价：</p>
 * <ul>
 *   <li>weapi：AES-128-CBC 两层（固定 key + 随机 16 字节 key），随机 key 经 RSA(NoPadding) 加密为 encSecKey</li>
 *   <li>eapi：md5("nobody" + url + "use" + body + "md5forencrypt") 拼串后 AES-128-ECB，hex 大写输出</li>
 * </ul>
 */
public final class NetEaseCrypto {

    private static final byte[] IV = "0102030405060708".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PRESET_KEY = "0CoJUm6Qyw8W8jud".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EAPI_KEY = "e82ckenh8dichen8".getBytes(StandardCharsets.US_ASCII);
    private static final String BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private static final String RSA_PUBLIC_PEM =
            "-----BEGIN PUBLIC KEY-----\n"
                    + "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7cl"
                    + "FSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMld"
                    + "czhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB\n"
                    + "-----END PUBLIC KEY-----";

    private static final SecureRandom RANDOM = new SecureRandom();

    private NetEaseCrypto() {
    }

    public record WeapiResult(String params, String encSecKey) {
    }

    /**
     * weapi 加密：返回可以直接放进 POST 表单的 params / encSecKey。
     */
    public static WeapiResult weapi(String json) {
        String first = Base64.getEncoder().encodeToString(aesCbc(json.getBytes(StandardCharsets.UTF_8), PRESET_KEY, IV));
        byte[] secret = randomSecretKey();
        String params = Base64.getEncoder().encodeToString(
                aesCbc(first.getBytes(StandardCharsets.UTF_8), secret, IV));
        String encSecKey = hex(rsaNoPadding(reverse(secret)));
        return new WeapiResult(params, encSecKey);
    }

    /**
     * eapi 加密（POST body = {@code params=<hex>}）。
     *
     * @param path API 路径，例如 {@code /api/song/enhance/player/url/v1}
     */
    public static String eapi(String path, String json) {
        String message = "nobody" + path + "use" + json + "md5forencrypt";
        String digest = md5Hex(message);
        String data = path + "-36cd479b6b5-" + json + "-36cd479b6b5-" + digest;
        return hex(aesEcb(data.getBytes(StandardCharsets.UTF_8), EAPI_KEY)).toUpperCase();
    }

    /**
     * eapi 响应解密（服务端返回 AES-128-ECB 密文）。
     */
    public static String decryptEapi(byte[] data) {
        return new String(aesEcbDecrypt(data, EAPI_KEY), StandardCharsets.UTF_8);
    }

    public static String md5Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return hex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }

    private static byte[] randomSecretKey() {
        byte[] bytes = new byte[16];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) BASE62.charAt(RANDOM.nextInt(BASE62.length()));
        }
        return bytes;
    }

    private static byte[] reverse(byte[] input) {
        byte[] output = new byte[input.length];
        for (int i = 0; i < input.length; i++) {
            output[i] = input[input.length - 1 - i];
        }
        return output;
    }

    private static byte[] aesCbc(byte[] data, byte[] key, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("AES-CBC failed", e);
        }
    }

    private static byte[] aesEcb(byte[] data, byte[] key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("AES-ECB failed", e);
        }
    }

    private static byte[] aesEcbDecrypt(byte[] data, byte[] key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("AES-ECB decrypt failed", e);
        }
    }

    private static byte[] rsaNoPadding(byte[] input) {
        try {
            String base64 = RSA_PUBLIC_PEM
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");

            byte[] keyBytes = Base64.getDecoder().decode(base64);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");

            Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keyFactory.generatePublic(new X509EncodedKeySpec(keyBytes)));

            // 与 crypto.js 一致：左补零到 128 字节（模数长度）
            byte[] padded = new byte[128];
            if (input.length > padded.length) {
                throw new IllegalArgumentException("RSA input too long");
            }
            System.arraycopy(input, 0, padded, padded.length - input.length, input.length);

            return cipher.doFinal(padded);
        } catch (Exception e) {
            throw new IllegalStateException("RSA encrypt failed", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }
}
