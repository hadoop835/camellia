package com.netease.nim.camellia.redis.proxy.upstream.local.storage.value.string;

import com.netease.nim.camellia.redis.proxy.monitor.LocalStorageCacheMonitor;
import com.netease.nim.camellia.redis.proxy.upstream.kv.cache.ValueWrapper;
import com.netease.nim.camellia.redis.proxy.upstream.local.storage.cache.EstimateSizeValueCalculator;
import com.netease.nim.camellia.redis.proxy.upstream.local.storage.cache.LRUCache;
import com.netease.nim.camellia.redis.proxy.upstream.local.storage.cache.LRUCacheName;
import com.netease.nim.camellia.redis.proxy.upstream.local.storage.cache.SizeCalculator;
import com.netease.nim.camellia.redis.proxy.upstream.local.storage.flush.FlushResult;
import com.netease.nim.camellia.redis.proxy.upstream.local.storage.key.Key;
import com.netease.nim.camellia.redis.proxy.upstream.local.storage.key.KeyInfo;
import com.netease.nim.camellia.redis.proxy.upstream.local.storage.value.persist.ValueFlushExecutor;
import com.netease.nim.camellia.redis.proxy.upstream.local.storage.value.string.block.StringBlockReadWrite;
import com.netease.nim.camellia.tools.utils.CamelliaMapUtils;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Created by caojiajun on 2025/1/3
 */
public class StringReadWrite {

    private final ConcurrentHashMap<Short, SlotStringReadWrite> map = new ConcurrentHashMap<>();

    private final ValueFlushExecutor flushExecutor;
    private final StringBlockReadWrite stringBlockReadWrite;

    private final LRUCache<Key, byte[]> readCache;
    private final LRUCache<Key, byte[]> writeCache;

    public StringReadWrite(ValueFlushExecutor flushExecutor, StringBlockReadWrite stringBlockReadWrite) {
        this.flushExecutor = flushExecutor;
        this.stringBlockReadWrite = stringBlockReadWrite;
        this.readCache = new LRUCache<>(LRUCacheName.string_read_cache, 1024, new EstimateSizeValueCalculator<>(), SizeCalculator.BYTES_INSTANCE);
        this.writeCache = new LRUCache<>(LRUCacheName.string_write_cache, 1024, new EstimateSizeValueCalculator<>(), SizeCalculator.BYTES_INSTANCE);
    }

    /**
     * put data
     * @param slot slot
     * @param keyInfo key info
     * @param data data
     * @throws IOException exception
     */
    public void put(short slot, KeyInfo keyInfo, byte[] data) throws IOException {
        Key key = new Key(keyInfo.getKey());
        byte[] bytes = readCache.get(key);
        if (bytes != null) {
            readCache.put(key, data);
        } else {
            writeCache.put(key, data);
        }
        get(slot).put(keyInfo, data);
    }

    /**
     * get
     * @param slot slot
     * @param keyInfo key info
     * @return data
     * @throws IOException exception
     */
    public byte[] get(short slot, KeyInfo keyInfo) throws IOException {
        Key key = new Key(keyInfo.getKey());
        byte[] data = readCache.get(key);
        if (data != null) {
            LocalStorageCacheMonitor.update(LocalStorageCacheMonitor.Type.row_cache, "string");
            return data;
        }
        data = writeCache.get(key);
        if (data != null) {
            readCache.put(key, data);
            writeCache.delete(key);
            LocalStorageCacheMonitor.update(LocalStorageCacheMonitor.Type.row_cache, "string");
            return data;
        }
        data = get(slot).get(keyInfo);
        if (data != null) {
            readCache.put(key, data);
        }
        return data;
    }

    /**
     * get for run to completion
     * @param slot slot
     * @param keyInfo key info
     * @return data
     */
    public ValueWrapper<byte[]> getForRunToCompletion(short slot, KeyInfo keyInfo) {
        Key key = new Key(keyInfo.getKey());
        byte[] bytes1 = readCache.get(key);
        if (bytes1 != null) {
            LocalStorageCacheMonitor.update(LocalStorageCacheMonitor.Type.row_cache, "string");
            return () -> bytes1;
        }
        byte[] bytes2 = writeCache.get(key);
        if (bytes2 != null) {
            LocalStorageCacheMonitor.update(LocalStorageCacheMonitor.Type.row_cache, "string");
            readCache.put(key, bytes2);
            writeCache.delete(key);
            return () -> bytes2;
        }
        return get(slot).getForRunToCompletion(keyInfo);
    }

    /**
     * flush to disk
     * @param slot slot
     * @param keyMap key info map
     * @return flush result
     * @throws IOException exception
     */
    public CompletableFuture<FlushResult> flush(short slot, Map<Key, KeyInfo> keyMap) throws IOException {
        SlotStringReadWrite slotStringReadWrite = get(slot);
        if (slotStringReadWrite == null) {
            return CompletableFuture.completedFuture(FlushResult.OK);
        }
        return slotStringReadWrite.flush(keyMap);
    }

    /**
     * check need to flush
     * @param slot slot
     * @return result
     */
    public boolean needFlush(short slot) {
        SlotStringReadWrite slotStringReadWrite = get(slot);
        if (slotStringReadWrite == null) {
            return false;
        }
        return slotStringReadWrite.needFlush();
    }

    private SlotStringReadWrite get(short slot) {
        return CamelliaMapUtils.computeIfAbsent(map, slot, s -> new SlotStringReadWrite(slot, flushExecutor, stringBlockReadWrite));
    }

}
