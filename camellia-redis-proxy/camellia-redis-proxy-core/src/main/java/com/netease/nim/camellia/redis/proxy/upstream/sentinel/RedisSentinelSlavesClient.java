package com.netease.nim.camellia.redis.proxy.upstream.sentinel;

import com.netease.nim.camellia.core.model.Resource;
import com.netease.nim.camellia.redis.base.exception.CamelliaRedisException;
import com.netease.nim.camellia.redis.base.resource.RedisSentinelResource;
import com.netease.nim.camellia.redis.base.resource.RedisSentinelSlavesResource;
import com.netease.nim.camellia.redis.base.resource.RedissSentinelSlavesResource;
import com.netease.nim.camellia.redis.proxy.conf.ProxyDynamicConf;
import com.netease.nim.camellia.redis.proxy.upstream.connection.RedisConnectionStatus;
import com.netease.nim.camellia.redis.proxy.upstream.standalone.AbstractSimpleRedisClient;
import com.netease.nim.camellia.redis.proxy.upstream.utils.HostAndPort;
import com.netease.nim.camellia.redis.proxy.upstream.connection.RedisConnectionAddr;
import com.netease.nim.camellia.redis.proxy.upstream.connection.RedisConnectionHub;
import com.netease.nim.camellia.redis.proxy.monitor.PasswordMaskUtils;
import com.netease.nim.camellia.redis.proxy.upstream.utils.Renew;
import com.netease.nim.camellia.redis.proxy.util.ErrorLogCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *
 * Created by caojiajun on 2021/4/8
 */
public class RedisSentinelSlavesClient extends AbstractSimpleRedisClient {

    private static final Logger logger = LoggerFactory.getLogger(RedisSentinelSlavesClient.class);
    private final AtomicInteger masterRenewIndex = new AtomicInteger(0);
    private final AtomicInteger slaveRenewIndex = new AtomicInteger(0);
    private final Object lock = new Object();
    private final List<RedisSentinelMasterListener> masterListenerList = new ArrayList<>();
    private final List<RedisSentinelSlavesListener> slavesListenerList = new ArrayList<>();

    private final Resource resource;
    private final Resource sentinelResource;
    private final String masterName;
    private final String userName;
    private final String password;
    private final int db;
    private final String sentinelUserName;
    private final String sentinelPassword;
    private final List<RedisSentinelResource.Node> nodes;
    private final boolean withMaster;

    private RedisConnectionAddr masterAddr;
    private List<RedisConnectionAddr> slaves;

    private Renew renew;

    public RedisSentinelSlavesClient(RedissSentinelSlavesResource resource) {
        this.resource = resource;
        this.sentinelResource = RedisSentinelUtils.parseSentinelResource(resource);
        this.masterName = resource.getMaster();
        this.userName = resource.getUserName();
        this.password = resource.getPassword();
        this.db = resource.getDb();
        this.sentinelUserName = resource.getSentinelUserName();
        this.sentinelPassword = resource.getSentinelPassword();
        this.nodes = resource.getNodes();
        this.withMaster = resource.isWithMaster();
    }

    public RedisSentinelSlavesClient(RedisSentinelSlavesResource resource) {
        this.resource = resource;
        this.sentinelResource = RedisSentinelUtils.parseSentinelResource(resource);
        this.masterName = resource.getMaster();
        this.userName = resource.getUserName();
        this.password = resource.getPassword();
        this.db = resource.getDb();
        this.sentinelUserName = resource.getSentinelUserName();
        this.sentinelPassword = resource.getSentinelPassword();
        this.nodes = resource.getNodes();
        this.withMaster = resource.isWithMaster();
    }

    @Override
    public void start() {
        boolean sentinelAvailable = false;
        if (withMaster) {
            for (RedisSentinelResource.Node node : nodes) {
                RedisSentinelMasterResponse masterResponse = RedisSentinelUtils.getMasterAddr(sentinelResource, node.getHost(), node.getPort(), masterName,
                        sentinelUserName, sentinelPassword);
                if (masterResponse.sentinelAvailable()) {
                    sentinelAvailable = true;
                }
                if (masterResponse.master() != null) {
                    this.masterAddr = new RedisConnectionAddr(masterResponse.master().getHost(), masterResponse.master().getPort(), userName, password, db);
                    logger.info("redis-sentinel-slaves init, url = {}, master = {}", PasswordMaskUtils.maskResource(resource.getUrl()), PasswordMaskUtils.maskAddr(this.masterName));
                    break;
                }
            }
        }
        for (RedisSentinelResource.Node node : nodes) {
            RedisSentinelSlavesResponse slavesResponse = RedisSentinelUtils.getSlaveAddrs(sentinelResource, node.getHost(), node.getPort(), masterName, sentinelUserName, sentinelPassword);
            if (slavesResponse.sentinelAvailable()) {
                sentinelAvailable = true;
            }
            if (slavesResponse.slaves() != null) {
                List<RedisConnectionAddr> slaves = new ArrayList<>();
                for (HostAndPort slave : slavesResponse.slaves()) {
                    slaves.add(new RedisConnectionAddr(slave.getHost(), slave.getPort(), userName, password, db));
                }
                this.slaves = slaves;
                logger.info("redis-sentinel-slaves init, url = {}, slaves = {}", PasswordMaskUtils.maskResource(resource.getUrl()), PasswordMaskUtils.maskAddrs(slaves));
                break;
            }
        }
        if (masterName == null && (slaves == null || slaves.isEmpty())) {
            if (sentinelAvailable) {
                if (withMaster) {
                    throw new CamelliaRedisException("can connect to sentinel, but cannot found master/slaves node");
                } else {
                    throw new CamelliaRedisException("can connect to sentinel, but cannot found slaves node");
                }
            } else {
                throw new CamelliaRedisException("all sentinels down");
            }
        }

        for (RedisSentinelResource.Node node : nodes) {
            if (withMaster) {
                RedisSentinelMasterListener.MasterUpdateCallback masterUpdateCallback = master -> {
                    synchronized (lock) {
                        try {
                            RedisConnectionAddr oldMaster = RedisSentinelSlavesClient.this.masterAddr;
                            if (master == null) {
                                if (oldMaster != null) {
                                    RedisSentinelSlavesClient.this.masterAddr = null;
                                    logger.info("master update, resource = {}, newMaster = null, oldMaster = {}", PasswordMaskUtils.maskResource(getResource()), PasswordMaskUtils.maskAddr(oldMaster));
                                }
                            } else {
                                RedisConnectionAddr newMaster = new RedisConnectionAddr(master.getHost(), master.getPort(), userName, password, db);
                                boolean needUpdate = false;
                                if (oldMaster == null) {
                                    needUpdate = true;
                                } else if (!newMaster.getUrl().equals(oldMaster.getUrl())) {
                                    needUpdate = true;
                                }
                                if (needUpdate) {
                                    RedisSentinelSlavesClient.this.masterAddr = newMaster;
                                    logger.info("master update, resource = {}, newMaster = {}, oldMaster = {}",
                                            PasswordMaskUtils.maskResource(getResource()), PasswordMaskUtils.maskAddr(newMaster), PasswordMaskUtils.maskAddr(oldMaster));
                                }
                            }
                        } catch (Exception e) {
                            logger.error("MasterUpdateCallback error, resource = {}", PasswordMaskUtils.maskResource(getResource()), e);
                        }
                    }
                };
                RedisSentinelMasterListener masterListener = new RedisSentinelMasterListener(resource, new HostAndPort(node.getHost(), node.getPort()),
                        masterName, sentinelUserName, sentinelPassword, masterUpdateCallback);
                masterListener.setDaemon(true);
                masterListener.start();
                masterListenerList.add(masterListener);
            }

            RedisSentinelSlavesListener.SlavesUpdateCallback slavesUpdateCallback = slaves -> {
                synchronized (lock) {
                    try {
                        if (slaves == null) {
                            slaves = new ArrayList<>();
                        }
                        List<RedisConnectionAddr> newSlaves = new ArrayList<>();
                        for (HostAndPort slave : slaves) {
                            newSlaves.add(new RedisConnectionAddr(slave.getHost(), slave.getPort(), userName, password, db));
                        }
                        List<RedisConnectionAddr> oldSlaves = RedisSentinelSlavesClient.this.slaves;
                        boolean needUpdate = false;
                        if (oldSlaves == null) {
                            needUpdate = true;
                        } else if (oldSlaves.size() != newSlaves.size()) {
                            needUpdate = true;
                        } else {
                            List<String> newStr = new ArrayList<>();
                            for (RedisConnectionAddr newSlave : newSlaves) {
                                newStr.add(newSlave.getUrl());
                            }
                            Collections.sort(newStr);
                            List<String> oldStr = new ArrayList<>();
                            for (RedisConnectionAddr oldSlave : oldSlaves) {
                                oldStr.add(oldSlave.getUrl());
                            }
                            Collections.sort(oldStr);
                            if (!newStr.toString().equals(oldStr.toString())) {
                                needUpdate = true;
                            }
                        }
                        if (needUpdate) {
                            RedisSentinelSlavesClient.this.slaves = newSlaves;
                            logger.info("slaves update, resource = {}, newSlaves = {}, oldSlaves = {}",
                                    PasswordMaskUtils.maskResource(getResource()), PasswordMaskUtils.maskAddrs(newSlaves), PasswordMaskUtils.maskAddrs(oldSlaves));
                        }
                    } catch (Exception e) {
                        logger.error("SlavesUpdateCallback error, resource = {}", PasswordMaskUtils.maskResource(getResource()), e);
                    }
                }
            };
            RedisSentinelSlavesListener listener = new RedisSentinelSlavesListener(resource, new HostAndPort(node.getHost(), node.getPort()),
                    masterName, sentinelUserName, sentinelPassword, slavesUpdateCallback);
            listener.setDaemon(true);
            listener.start();
            slavesListenerList.add(listener);
        }

        int intervalSeconds = ProxyDynamicConf.getInt("redis.sentinel.schedule.renew.interval.seconds", 600);
        renew = new Renew(resource, this::renew0, intervalSeconds);
        logger.info("RedisSentinelSlavesClient start success, resource = {}", PasswordMaskUtils.maskResource(getResource()));
    }

    @Override
    public void preheat() {
        logger.info("try preheat, resource = {}", PasswordMaskUtils.maskResource(getResource()));
        boolean success = false;
        if (masterAddr != null) {
            logger.info("try preheat, resource = {}, master = {}", PasswordMaskUtils.maskResource(getResource()), PasswordMaskUtils.maskAddr(masterName));
            boolean result = RedisConnectionHub.getInstance().preheat(this, masterAddr);
            logger.info("preheat result = {}, resource = {}, master = {}", result, PasswordMaskUtils.maskResource(getResource()), PasswordMaskUtils.maskAddr(masterName));
            if (result) {
                success = true;
            }
        }
        if (slaves != null) {
            for (RedisConnectionAddr slave : slaves) {
                logger.info("try preheat, resource = {}, slave = {}", PasswordMaskUtils.maskResource(getResource()), PasswordMaskUtils.maskAddr(slave));
                boolean result = RedisConnectionHub.getInstance().preheat(this, slave);
                logger.info("preheat result = {}, resource = {}, slave = {}", result, PasswordMaskUtils.maskResource(getResource()), PasswordMaskUtils.maskAddr(slave));
                if (result) {
                    success = true;
                }
            }
        }
        if (!success) {
            logger.info("preheat failed, resource = {}", PasswordMaskUtils.maskResource(getResource()));
            throw new CamelliaRedisException("preheat failed, resource = " + PasswordMaskUtils.maskResource(getResource()));
        }
        logger.info("preheat success, resource = {}", PasswordMaskUtils.maskResource(getResource()));
    }

    @Override
    public boolean isValid() {
        if (masterName != null) {
            if (getStatus(masterAddr) == RedisConnectionStatus.VALID) {
                return true;
            }
        }
        List<RedisConnectionAddr> slaveNodes = new ArrayList<>(slaves);
        for (RedisConnectionAddr slave : slaveNodes) {
            if (getStatus(slave) == RedisConnectionStatus.VALID) {
                return true;
            }
        }
        return false;
    }

    @Override
    public RedisConnectionAddr getAddr() {
        try {
            if (masterAddr != null) {
                if (slaves.isEmpty()) return masterAddr;
                try {
                    int maxLoop = slaves.size() + 1;
                    int index = ThreadLocalRandom.current().nextInt(maxLoop);
                    for (int i=0; i<maxLoop; i++) {
                        try {
                            //current
                            RedisConnectionAddr addr;
                            if (index == 0) {
                                addr = masterAddr;
                            } else {
                                addr = slaves.get(index - 1);
                            }
                            if (getStatus(addr) == RedisConnectionStatus.VALID) {
                                return addr;
                            }
                            //next
                            index = index + 1;
                            if (index == slaves.size() + 1) {
                                index = 0;
                            }
                        } catch (Exception e) {
                            //slaves list maybe update
                            index = ThreadLocalRandom.current().nextInt(slaves.size() + 1);
                        }
                    }
                    index = ThreadLocalRandom.current().nextInt(slaves.size() + 1);
                    if (index == 0) {
                        return masterAddr;
                    } else {
                        return slaves.get(index - 1);
                    }
                } catch (Exception e) {
                    return masterAddr;
                }
            } else {
                if (slaves.isEmpty()) return null;
                if (slaves.size() == 1) return slaves.getFirst();
                try {
                    int maxLoop = slaves.size();
                    int index = ThreadLocalRandom.current().nextInt(maxLoop);
                    for (int i=0; i<maxLoop; i++) {
                        try {
                            //current
                            RedisConnectionAddr addr = slaves.get(index);
                            if (getStatus(addr) == RedisConnectionStatus.VALID) {
                                return addr;
                            }
                            //next
                            index = index + 1;
                            if (index == slaves.size()) {
                                index = 0;
                            }
                        } catch (Exception e) {
                            //slaves list maybe update
                            index = ThreadLocalRandom.current().nextInt(slaves.size());
                        }
                    }
                    return slaves.get(ThreadLocalRandom.current().nextInt(slaves.size()));
                } catch (Exception e) {
                    return slaves.getFirst();
                }
            }
        } catch (Exception e) {
            ErrorLogCollector.collect(RedisSentinelSlavesClient.class, "getAddr error, url = " + PasswordMaskUtils.maskResource(resource.getUrl()), e);
        }
        return null;
    }

    @Override
    public Resource getResource() {
        return resource;
    }

    @Override
    public synchronized void shutdown() {
        if (renew != null) {
            renew.close();
        }
        for (RedisSentinelMasterListener listener : masterListenerList) {
            listener.shutdown();
        }
        for (RedisSentinelSlavesListener listener : slavesListenerList) {
            listener.shutdown();
        }
        logger.warn("upstream client shutdown, resource = {}", PasswordMaskUtils.maskResource(getResource()));
    }

    @Override
    public void renew() {
        if (renew != null) {
            renew.renew();
        }
    }

    private void renew0() {
        try {
            if (!masterListenerList.isEmpty()) {
                int index = Math.abs(masterRenewIndex.getAndIncrement()) % masterListenerList.size();
                RedisSentinelMasterListener masterListener = masterListenerList.get(index);
                masterListener.renew();
            }
            if (!slavesListenerList.isEmpty()) {
                int index = Math.abs(slaveRenewIndex.getAndIncrement()) % slavesListenerList.size();
                RedisSentinelSlavesListener slavesListener = slavesListenerList.get(index);
                slavesListener.renew();
            }
        } catch (Exception e) {
            logger.error("redis sentinel slaves renew error, resource = {}", PasswordMaskUtils.maskResource(resource.getUrl()), e);
        }
    }
}
