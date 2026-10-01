package com.github.h3yow.requesttracealertingjava.trace;

import java.security.SecureRandom;

/**
 * 链路标识的生成与校验规则。
 *
 * <p>格式规则：32 位小写十六进制字符（[0-9a-f]{32}）。
 * 任何不满足该格式的上游值都视为非法，不会被原样透传。
 */
public final class TraceId {

    private static final int LENGTH = 32;
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private TraceId() {
    }

    /** 生成一个新的链路标识：128 位随机数的小写十六进制表示。 */
    public static String generate() {
        byte[] bytes = new byte[LENGTH / 2];
        RANDOM.nextBytes(bytes);
        char[] chars = new char[LENGTH];
        for (int i = 0; i < bytes.length; i++) {
            chars[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
            chars[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(chars);
    }

    /** 校验给定字符串是否是合法的链路标识（32 位小写十六进制）。 */
    public static boolean isValid(String value) {
        if (value == null || value.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < LENGTH; i++) {
            char c = value.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            boolean lowerHex = c >= 'a' && c <= 'f';
            if (!digit && !lowerHex) {
                return false;
            }
        }
        return true;
    }
}
