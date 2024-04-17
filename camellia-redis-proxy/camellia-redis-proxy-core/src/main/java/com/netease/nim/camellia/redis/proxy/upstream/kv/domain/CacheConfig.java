package com.netease.nim.camellia.redis.proxy.upstream.kv.domain;

import com.netease.nim.camellia.redis.proxy.upstream.kv.RedisKvConf;

/**
 * Created by caojiajun on 2024/4/8
 */
public class CacheConfig {

    private final String namespace;
    private final boolean valueCacheEnable;
    private final boolean metaCacheEnable;

    public CacheConfig(String namespace, boolean metaCacheEnable, boolean valueCacheEnable) {
        this.namespace = namespace;
        this.metaCacheEnable = metaCacheEnable;
        this.valueCacheEnable = valueCacheEnable;
    }

    public boolean isMetaCacheEnable() {
        return metaCacheEnable;
    }

    public boolean isValueCacheEnable() {
        return valueCacheEnable;
    }

    public long metaCacheMillis() {
        return RedisKvConf.getLong(namespace, "kv.key.meta.cache.millis", 1000L * 60 * 10);
    }

    public long keyMetaTimeoutMillis() {
        return RedisKvConf.getLong(namespace, "kv.key.meta.timeout.millis", 2000L);
    }

    public long cacheTimeoutMillis() {
        return RedisKvConf.getLong(namespace, "kv.cache.timeout.millis", 2000L);
    }

    public long hgetCacheMillis() {
        return RedisKvConf.getLong(namespace, "kv.cache.hget.cache.millis", 5*60*1000L);
    }

    public long hgetallCacheMillis() {
        return RedisKvConf.getLong(namespace, "kv.cache.hgetall.cache.millis", 5*60*1000L);
    }
}