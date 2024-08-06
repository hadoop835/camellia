package com.netease.nim.camellia.redis.proxy.upstream.kv.command.set;

import com.netease.nim.camellia.redis.proxy.command.Command;
import com.netease.nim.camellia.redis.proxy.enums.RedisCommand;
import com.netease.nim.camellia.redis.proxy.monitor.KvCacheMonitor;
import com.netease.nim.camellia.redis.proxy.reply.ErrorReply;
import com.netease.nim.camellia.redis.proxy.reply.IntegerReply;
import com.netease.nim.camellia.redis.proxy.reply.Reply;
import com.netease.nim.camellia.redis.proxy.upstream.kv.buffer.NoOpResult;
import com.netease.nim.camellia.redis.proxy.upstream.kv.buffer.Result;
import com.netease.nim.camellia.redis.proxy.upstream.kv.buffer.WriteBufferValue;
import com.netease.nim.camellia.redis.proxy.upstream.kv.cache.RedisSet;
import com.netease.nim.camellia.redis.proxy.upstream.kv.command.CommanderConfig;
import com.netease.nim.camellia.redis.proxy.upstream.kv.meta.EncodeVersion;
import com.netease.nim.camellia.redis.proxy.upstream.kv.meta.KeyMeta;
import com.netease.nim.camellia.redis.proxy.upstream.kv.meta.KeyType;
import com.netease.nim.camellia.tools.utils.BytesKey;

import java.util.*;

/**
 * SREM key member [member ...]
 * <p>
 * Created by caojiajun on 2024/8/5
 */
public class SRemCommander extends Set0Commander {

    public SRemCommander(CommanderConfig commanderConfig) {
        super(commanderConfig);
    }

    @Override
    public RedisCommand redisCommand() {
        return RedisCommand.SREM;
    }

    @Override
    protected boolean parse(Command command) {
        return command.getObjects().length >= 3;
    }

    @Override
    protected Reply execute(Command command) {
        byte[][] objects = command.getObjects();
        byte[] key = objects[1];
        Set<BytesKey> members = new HashSet<>();
        for (int i=2; i<objects.length; i++) {
            members.add(new BytesKey(objects[i]));
        }
        int size = members.size();
        KeyMeta keyMeta = keyMetaServer.getKeyMeta(key);
        if (keyMeta == null) {
            return IntegerReply.REPLY_0;
        }
        if (keyMeta.getKeyType() != KeyType.set) {
            return ErrorReply.WRONG_TYPE;
        }

        byte[] cacheKey = keyDesign.cacheKey(keyMeta, key);

        Set<BytesKey> removedMembers = null;
        Result result = null;

        WriteBufferValue<RedisSet> bufferValue = setWriteBuffer.get(cacheKey);
        if (bufferValue != null) {
            RedisSet set = bufferValue.getValue();
            KvCacheMonitor.writeBuffer(cacheConfig.getNamespace(), redisCommand().strRaw());
            removedMembers = set.srem(members);
            result = setWriteBuffer.put(cacheKey, set);
        }

        if (cacheConfig.isSetLocalCacheEnable()) {
            if (removedMembers == null) {
                removedMembers = cacheConfig.getSetLRUCache().srem(key, cacheKey, members);
            } else {
                cacheConfig.getSetLRUCache().srem(key, cacheKey, members);
            }

            if (result == null) {
                RedisSet set = cacheConfig.getSetLRUCache().getForWrite(key, cacheKey);
                if (set != null) {
                    result = setWriteBuffer.put(cacheKey, new RedisSet(new HashSet<>(set.smembers())));
                }
            }
        }

        if (result == null) {
            result = NoOpResult.INSTANCE;
        }

        EncodeVersion encodeVersion = keyMeta.getEncodeVersion();

        int removeSize = -1;

        if (removedMembers != null) {
            removeSize = removedMembers.size();
            members = removedMembers;
        }

        if (encodeVersion == EncodeVersion.version_2 || encodeVersion == EncodeVersion.version_3) {
            byte[][] cmd = new byte[members.size() + 2][];
            cmd[0] = RedisCommand.SREM.raw();
            cmd[1] = cacheKey;
            int i = 2;
            for (BytesKey member : members) {
                cmd[i] = member.getKey();
                i++;
            }
            Reply reply = sync(cacheRedisTemplate.sendCommand(new Command(cmd)));
            if (reply instanceof ErrorReply) {
                return reply;
            }
            if (reply instanceof IntegerReply) {
                Long integer = ((IntegerReply) reply).getInteger();
                if (integer > 0) {
                    removeSize = integer.intValue();
                }
            }
        }

        if (removeSize < 0) {
            if (encodeVersion == EncodeVersion.version_0 || encodeVersion == EncodeVersion.version_2) {
                Map<BytesKey, Boolean> smismember = smismemberFromKv(keyMeta, key, members);
                removeSize = 0;
                members = new HashSet<>();
                for (Map.Entry<BytesKey, Boolean> entry : smismember.entrySet()) {
                    if (entry.getValue()) {
                        removeSize++;
                        members.add(entry.getKey());
                    }
                }
            }
        }

        removeMembers(keyMeta, key, cacheKey, members, result);

        if (encodeVersion == EncodeVersion.version_0 || encodeVersion == EncodeVersion.version_2) {
            if (removeSize > 0) {
                updateKeyMeta(keyMeta, key, removeSize * -1);
            }
            return IntegerReply.parse(removeSize);
        } else {
            return IntegerReply.parse(size);
        }
    }
}
