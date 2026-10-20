package com.anying.helpdesk.knowledge.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

public record OssSourceObject(String etag, long size, long modifiedAt, Map<String, String> metadata) {
    /** 固定元数据副本，防止读取后发生本地修改影响比较。 */
    public OssSourceObject {
        metadata = Map.copyOf(metadata);
    }

    /** 内容标识与业务元数据一起参与变化检测，ETag 不充当文件的 SHA-256。 */
    public String fingerprint() {
        StringBuilder value = new StringBuilder(etag).append(':').append(size).append(':').append(modifiedAt);
        new TreeMap<>(metadata).forEach((key, entry) -> value.append(':').append(key.length()).append(':')
                .append(key).append(':').append(entry.length()).append(':').append(entry));
        return hash(value.toString());
    }

    /** 将外部键或变化标识转换为定长索引值，实际对象键仍保留供核对。 */
    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
