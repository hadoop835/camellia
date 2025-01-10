package com.netease.nim.camellia.redis.proxy.upstream.local.storage.command.db;

import com.netease.nim.camellia.redis.proxy.command.Command;
import com.netease.nim.camellia.redis.proxy.enums.RedisCommand;
import com.netease.nim.camellia.redis.proxy.reply.IntegerReply;
import com.netease.nim.camellia.redis.proxy.reply.Reply;
import com.netease.nim.camellia.redis.proxy.upstream.local.storage.command.CommandConfig;
import com.netease.nim.camellia.redis.proxy.upstream.local.storage.key.Key;
import com.netease.nim.camellia.redis.proxy.upstream.local.storage.command.ICommand;
import com.netease.nim.camellia.redis.proxy.upstream.local.storage.key.KeyInfo;
import com.netease.nim.camellia.redis.proxy.util.Utils;

/**
 * EXPIREAT key unix-time-seconds [NX | XX | GT | LT]
 * <p>
 * Created by caojiajun on 2025/1/10
 */
public class ExpireAtCommand extends ICommand {

    public ExpireAtCommand(CommandConfig commandConfig) {
        super(commandConfig);
    }

    @Override
    public RedisCommand redisCommand() {
        return RedisCommand.EXPIREAT;
    }

    @Override
    protected boolean parse(Command command) {
        byte[][] objects = command.getObjects();
        return objects.length == 3 || objects.length == 4;
    }

    @Override
    protected Reply execute(short slot, Command command) throws Exception {
        byte[][] objects = command.getObjects();
        Key cacheKey = new Key(objects[1]);
        long unixTimeSeconds = Utils.bytesToNum(objects[2]);
        KeyInfo keyInfo = keyReadWrite.get(slot, cacheKey);
        if (keyInfo == null) {
            return IntegerReply.REPLY_0;
        }
        long expireTime = unixTimeSeconds * 1000L;
        String arg = null;
        if (objects.length == 4) {
            arg = Utils.bytesToString(objects[3]);
        }
        if (arg != null) {
            if (arg.equalsIgnoreCase("NX")) {
                if (keyInfo.getExpireTime() > 0) {
                    return IntegerReply.REPLY_0;
                }
            } else if (arg.equalsIgnoreCase("XX")) {
                if (keyInfo.getExpireTime() <= 0) {
                    return IntegerReply.REPLY_0;
                }
            } else if (arg.equalsIgnoreCase("GT")) {
                if (expireTime <= keyInfo.getExpireTime()) {
                    return IntegerReply.REPLY_0;
                }
            } else if (arg.equalsIgnoreCase("LT")) {
                if (expireTime >= keyInfo.getExpireTime()) {
                    return IntegerReply.REPLY_0;
                }
            }
        }
        keyInfo.setExpireTime(expireTime);
        keyReadWrite.put(slot, keyInfo);
        return IntegerReply.REPLY_1;
    }
}
