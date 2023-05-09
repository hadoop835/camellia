package com.netease.nim.camellia.hot.key.sdk.netty;

import com.netease.nim.camellia.core.discovery.CamelliaDiscovery;

/**
 * Created by caojiajun on 2023/5/8
 */
public abstract class HotKeyServerDiscovery implements CamelliaDiscovery<HotKeyServerAddr> {

    /**
     * 每个HotKeyServerDiscovery应该有一个唯一的名字
     * @return 名字
     */
    public abstract String getName();

}