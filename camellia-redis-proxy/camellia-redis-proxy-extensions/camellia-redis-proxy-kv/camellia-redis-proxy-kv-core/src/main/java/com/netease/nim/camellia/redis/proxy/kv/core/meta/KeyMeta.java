package com.netease.nim.camellia.redis.proxy.kv.core.meta;


import com.netease.nim.camellia.redis.proxy.kv.core.utils.BytesUtils;

/**
 * Created by caojiajun on 2024/4/7
 */
public class KeyMeta {
    private final KeyMetaVersion keyMetaVersion;
    private final KeyType keyType;
    private final long keyVersion;
    private final long expireTime;
    private final byte[] extra;
    private byte[] raw;

    public KeyMeta(KeyMetaVersion keyMetaVersion, KeyType keyType, long keyVersion, long expireTime) {
        this(keyMetaVersion, keyType, keyVersion, expireTime, null);
    }

    public KeyMeta(KeyMetaVersion keyMetaVersion, KeyType keyType, long keyVersion, long expireTime, byte[] extra) {
        this.keyMetaVersion = keyMetaVersion;
        this.keyType = keyType;
        this.keyVersion = keyVersion;
        this.expireTime = expireTime;
        this.extra = extra;
    }

    public KeyType getKeyType() {
        return keyType;
    }

    public long getExpireTime() {
        return expireTime;
    }

    public KeyMetaVersion getKeyMetaVersion() {
        return keyMetaVersion;
    }

    public long getKeyVersion() {
        return keyVersion;
    }

    public byte[] getExtra() {
        return extra;
    }

    private void setRaw(byte[] raw) {
        this.raw = raw;
    }

    public byte[] toBytes() {
        if (raw != null) {
            return raw;
        }
        byte[] flag = new byte[2];
        flag[0] = keyMetaVersion.getValue();
        flag[1] = keyType.getValue();
        byte[] data = BytesUtils.merge(flag, BytesUtils.toBytes(keyVersion), BytesUtils.toBytes(expireTime));
        if (extra != null && extra.length > 0) {
            data = BytesUtils.merge(data, extra);
        }
        this.raw = data;
        return data;
    }

    public static KeyMeta fromBytes(byte[] raw) {
        if (raw == null) {
            return null;
        }
        if (raw.length < 18) {
            return null;
        }
        KeyMetaVersion keyMetaVersion = KeyMetaVersion.getByValue(raw[0]);
        KeyType keyType = KeyType.getByValue(raw[1]);
        long keyVersion = BytesUtils.toLong(raw, 2);
        long expireTime = BytesUtils.toLong(raw, 10);
        byte[] extra = null;
        if (raw.length > 18) {
            extra = new byte[raw.length - 18];
            System.arraycopy(raw, 18, extra, 0, extra.length);
        }
        KeyMeta keyMeta = new KeyMeta(keyMetaVersion, keyType, keyVersion, expireTime, extra);
        keyMeta.setRaw(raw);
        return keyMeta;
    }
}
