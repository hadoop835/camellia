package com.netease.nim.camellia.redis.proxy.upstream.proxies;

import com.netease.nim.camellia.redis.base.exception.CamelliaRedisException;
import com.netease.nim.camellia.redis.proxy.conf.ProxyDynamicConf;
import com.netease.nim.camellia.redis.proxy.monitor.PasswordMaskUtils;
import com.netease.nim.camellia.redis.proxy.upstream.connection.RedisConnectionAddr;
import com.netease.nim.camellia.redis.proxy.upstream.connection.RedisConnectionHub;
import com.netease.nim.camellia.redis.proxy.upstream.connection.RedisConnectionStatus;
import com.netease.nim.camellia.redis.proxy.upstream.standalone.AbstractSimpleRedisClient;
import com.netease.nim.camellia.redis.proxy.upstream.utils.Renew;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Created by caojiajun on 2023/2/2
 */
public abstract class AbstractRedisProxiesClient extends AbstractSimpleRedisClient {

    private static final Logger logger = LoggerFactory.getLogger(AbstractRedisProxiesClient.class);

    private final Object lock = new Object();
    private List<RedisConnectionAddr> originalList = new ArrayList<>();
    private List<RedisConnectionAddr> dynamicList = new ArrayList<>();
    private Renew renew;

    /**
     * default constructor
     */
    public AbstractRedisProxiesClient() {
    }

    @Override
    public void preheat() {
        logger.info("try preheat, resource = {}", PasswordMaskUtils.maskResource(getResource()));
        for (RedisConnectionAddr addr : getAll()) {
            logger.info("try preheat, resource = {}, proxy = {}", PasswordMaskUtils.maskResource(getResource()), PasswordMaskUtils.maskAddr(addr));
            boolean result = RedisConnectionHub.getInstance().preheat(this, addr);
            logger.info("preheat result = {}, resource = {}, proxy = {}", result, PasswordMaskUtils.maskResource(getResource()), PasswordMaskUtils.maskAddr(addr));
        }
        logger.info("preheat success, resource = {}", PasswordMaskUtils.maskResource(getResource()));
    }

    @Override
    public void start() {
        refresh(true);
        if (originalList.isEmpty()) {
            throw new CamelliaRedisException("init fail, no reachable proxy, resource = " + PasswordMaskUtils.maskResource(getResource()));
        }
        int seconds = ProxyDynamicConf.getInt("redis.proxies.reload.interval.seconds", 60);
        this.renew = new Renew(getResource(), this::renew0, seconds);
    }

    @Override
    public synchronized void shutdown() {
        if (renew != null) {
            renew.close();
        }
        logger.warn("upstream client shutdown, resource = {}", PasswordMaskUtils.maskResource(getResource()));
    }

    @Override
    public boolean isValid() {
        List<RedisConnectionAddr> list = new ArrayList<>(originalList);
        for (RedisConnectionAddr addr : list) {
            if (getStatus(addr) == RedisConnectionStatus.VALID) {
                return true;
            }
        }
        return false;
    }

    /**
     * get all addrs list
     * @return list
     */
    public abstract List<RedisConnectionAddr> getAll();

    private void refresh(boolean first) {
        List<RedisConnectionAddr> list = getAll();
        if (list == null || list.isEmpty()) {
            logger.warn("addr list is empty, skip refresh, resource = {}", PasswordMaskUtils.maskResource(getResource()));
            return;
        }
        synchronized (lock) {
            List<RedisConnectionAddr> validList = new ArrayList<>();
            for (RedisConnectionAddr addr : list) {
                RedisConnectionStatus status = getStatus(addr);
                if (status == RedisConnectionStatus.VALID) {
                    validList.add(addr);
                } else if (status == RedisConnectionStatus.INITIALIZE && first) {//如果是初次初始化，则INITIALIZE也算valid
                    validList.add(addr);
                }
            }
            if (validList.isEmpty()) {
                logger.warn("no reachable addr list {}, skip refresh, resource = {}", list, PasswordMaskUtils.maskResource(getResource()));
                return;
            }
            this.originalList = new ArrayList<>(validList);
            this.dynamicList = new ArrayList<>(originalList);
        }
    }

    /**
     * add addr to list
     * @param addr addr
     */
    public void add(RedisConnectionAddr addr) {
        synchronized (lock) {
            if (!originalList.contains(addr)) {
                originalList.add(addr);
                dynamicList = new ArrayList<>(originalList);
            }
        }
    }

    /**
     * remove addr from  list
     * @param addr addr
     */
    public void remove(RedisConnectionAddr addr) {
        synchronized (lock) {
            if (originalList.contains(addr)) {
                originalList.remove(addr);
                dynamicList = new ArrayList<>(originalList);
            }
        }
    }

    @Override
    public RedisConnectionAddr getAddr() {
        try {
            if (originalList.isEmpty()) return null;
            if (originalList.size() == 1) {
                return originalList.getFirst();
            }
            int retry = originalList.size();
            while (retry-- > 0) {
                if (dynamicList.isEmpty()) {
                    dynamicList = new ArrayList<>(originalList);
                }
                int i = ThreadLocalRandom.current().nextInt(dynamicList.size());
                RedisConnectionAddr addr = dynamicList.get(i);
                if (getStatus(addr) == RedisConnectionStatus.VALID) {
                    return addr;
                } else {
                    dynamicList.remove(addr);
                }
            }
            int i = ThreadLocalRandom.current().nextInt(originalList.size());
            return originalList.get(i);
        } catch (Exception e) {
            try {
                if (originalList.isEmpty()) return null;
                int i = ThreadLocalRandom.current().nextInt(originalList.size());
                return originalList.get(i);
            } catch (Exception ex) {
                try {
                    return originalList.getFirst();
                } catch (Exception exc) {
                    return null;
                }
            }
        }
    }

    @Override
    public void renew() {
        if (renew != null) {
            renew.renew();
        }
    }

    private void renew0() {
        refresh(false);
    }
}
