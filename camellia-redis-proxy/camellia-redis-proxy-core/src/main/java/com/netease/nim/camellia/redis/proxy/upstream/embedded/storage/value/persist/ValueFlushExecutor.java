package com.netease.nim.camellia.redis.proxy.upstream.embedded.storage.value.persist;

import com.netease.nim.camellia.redis.proxy.upstream.embedded.storage.codec.StringValueCodec;
import com.netease.nim.camellia.redis.proxy.upstream.embedded.storage.enums.FlushResult;
import com.netease.nim.camellia.redis.proxy.upstream.embedded.storage.file.FileReadWrite;
import com.netease.nim.camellia.redis.proxy.upstream.embedded.storage.flush.FlushExecutor;
import com.netease.nim.camellia.redis.proxy.upstream.embedded.storage.key.KeyInfo;
import com.netease.nim.camellia.redis.proxy.upstream.embedded.storage.value.block.*;
import com.netease.nim.camellia.redis.proxy.upstream.embedded.storage.value.string.StringBlockCache;
import com.netease.nim.camellia.tools.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Created by caojiajun on 2025/1/6
 */
public class ValueFlushExecutor {

    private static final Logger logger = LoggerFactory.getLogger(ValueFlushExecutor.class);

    private final FlushExecutor executor;
    private final IValueManifest valueManifest;
    private final FileReadWrite fileReadWrite;
    private final StringBlockCache blockCache;

    public ValueFlushExecutor(FlushExecutor executor, IValueManifest valueManifest, FileReadWrite fileReadWrite, StringBlockCache blockCache) {
        this.executor = executor;
        this.valueManifest = valueManifest;
        this.fileReadWrite = fileReadWrite;
        this.blockCache = blockCache;
    }

    public CompletableFuture<FlushResult> submit(StringValueFlushTask flushTask) {
        CompletableFuture<FlushResult> future = new CompletableFuture<>();
        try {
            executor.submit(() -> {
                try {
                    execute(flushTask);
                    future.complete(FlushResult.OK);
                } catch (Exception e) {
                    logger.error("string value flush error, slot = {}", flushTask.slot(), e);
                    future.complete(FlushResult.ERROR);
                }
            });
        } catch (Exception e) {
            logger.error("submit string value flush error, slot = {}", flushTask.slot(), e);
            future.complete(FlushResult.ERROR);
        }
        return future;
    }

    private void execute(StringValueFlushTask task) throws Exception {
        short slot = task.slot();
        Map<KeyInfo, byte[]> flushValues = task.flushValues();
        Map<BlockType, List<Pair<KeyInfo, byte[]>>> blockMap = new HashMap<>();
        for (Map.Entry<KeyInfo, byte[]> entry : flushValues.entrySet()) {
            byte[] data = entry.getValue();
            BlockType blockType = BlockType.fromData(data);
            List<Pair<KeyInfo, byte[]>> buffers = blockMap.computeIfAbsent(blockType, k -> new ArrayList<>());
            buffers.add(new Pair<>(entry.getKey(), entry.getValue()));
        }
        List<BlockInfo> list = new ArrayList<>();
        for (Map.Entry<BlockType, List<Pair<KeyInfo, byte[]>>> entry : blockMap.entrySet()) {
            List<BlockInfo> blockInfos = StringValueCodec.encode(slot, entry.getKey(), valueManifest, entry.getValue());
            list.addAll(blockInfos);
        }
        for (BlockInfo blockInfo : list) {
            BlockLocation blockLocation = blockInfo.blockLocation();
            long fileId = blockLocation.fileId();
            long offset = (long) blockLocation.blockId() * blockInfo.blockType().getBlockSize();
            fileReadWrite.write(fileId, offset, blockInfo.data());
            blockCache.updateBlockCache(slot, fileId, offset, blockInfo.data());
        }
    }

}
