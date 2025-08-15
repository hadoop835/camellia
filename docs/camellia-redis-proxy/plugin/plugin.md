
## 插件体系
* 插件使用统一的接口来拦截和控制请求和响应
* proxy内置了很多插件，可以通过简单配置后即可直接使用
* 你也可以实现自定义插件，插件内务必不要添加可能产生阻塞行为的代码逻辑，避免卡住proxy工作线程

### 插件接口
```java
public interface ProxyPlugin {

    /**
     * 初始化方法
     */
    default void init(ProxyBeanFactory factory) {
    }

    /**
     * 从大到小依次执行，请求和响应可以分开定义
     * @return 优先级
     */
    default ProxyPluginOrder order() {
        return ProxyPluginOrder.DEFAULT;
    }

    /**
     * 请求（此时命令刚到proxy，还未到后端redis）
     * @param request 请求command的上下文
     */
    default ProxyPluginResponse executeRequest(ProxyRequest request) {
        return ProxyPluginResponse.SUCCESS;
    }

    /**
     * 响应（此时命令已经从后端redis响应，即将返回给客户端）
     * @param reply 响应reply上下文
     */
    default ProxyPluginResponse executeReply(ProxyReply reply) {
        return ProxyPluginResponse.SUCCESS;
    }

}
```

### 如何启用插件
* application.yml
```yaml
server:
  port: 6380
spring:
  application:
    name: camellia-redis-proxy-server

camellia-redis-proxy:
  console-port: 16379 #console端口，默认是16379，如果设置为-16379则会随机一个可用端口，如果设置为0，则不启动console
  password: pass123   #proxy的密码，如果设置了自定义的client-auth-provider-class-name，则密码参数无效
  plugins: #使用yml配置插件，内置插件可以直接使用别名启用，自定义插件需要配置全类名
    - monitorPlugin
    - bigKeyPlugin
    - hotKeyPlugin
    - com.xxx.xxx.CustomProxyPlugin
  transpond:
    type: local #使用本地配置
    local:
      type: simple
      resource: redis://@127.0.0.1:6379 #转发的redis地址
```
* camellia-redis-proxy.properties
```
#使用properties配置的插件可以运行期自定义增减
proxy.plugin.list=monitorPlugin,bigKeyPlugin,com.xxx.xxx.CustomProxyPlugin
```

### 内置插件
```java
public enum BuildInProxyPluginEnum {

    //用于监控，主要是监控请求量和响应时间以及慢查询
    MONITOR_PLUGIN("monitorPlugin", MonitorProxyPlugin.class, Integer.MAX_VALUE, Integer.MIN_VALUE),
    //控制访问权限，ip黑白名单
    IP_CHECKER_PLUGIN("ipCheckerPlugin", IPCheckProxyPlugin.class, Integer.MAX_VALUE - 10000, 0),
    //Dynamic IP Checker, configured by camellia-dashboard
    DYNAMIC_IP_CHECKER_PLUGIN("dynamicIpCheckerPlugin", DynamicIpCheckProxyPlugin.class, Integer.MAX_VALUE - 10000, 0),

    //只读
    READ_ONLY_PLUGIN("readOnlyPlugin", ReadOnlyProxyPlugin.class, Integer.MAX_VALUE - 15000, 0),
    //屏蔽某些命令
    COMMAND_DISABLE_PLUGIN("commandDisablePlugin", CommandDisableProxyPlugin.class, Integer.MAX_VALUE - 20000, 0),
    //用于控制请求速率
    RATE_LIMIT_PLUGIN("rateLimitPlugin", RateLimitProxyPlugin.class, Integer.MAX_VALUE - 30000, 0),
    //Dynamic Rate Limit, configured by camellia-dashboard
    DYNAMIC_RATE_LIMIT_PLUGIN("dynamicRateLimitPlugin", DynamicRateLimitProxyPlugin.class, Integer.MAX_VALUE - 30000, 0),
    //用于拦截非法的key，直接快速失败
    TROUBLE_TRICK_KEYS_PLUGIN("troubleTrickKeysPlugin", TroubleTrickKeysProxyPlugin.class, Integer.MAX_VALUE - 40000, 0),

    //用于监控热key
    HOT_KEY_PLUGIN("hotKeyPlugin", HotKeyProxyPlugin.class, 20000, 0),
    //用于监控热key并转发到自定义路由
    HOT_KEY_ROUTE_REWRITE_PLUGIN("hotKeyRouteRewritePlugin", HotKeyRouteRewriteProxyPlugin.class, 20000, 0),
    //用于热key缓存（仅支持GET命令）
    HOT_KEY_CACHE_PLUGIN("hotKeyCachePlugin", HotKeyCacheProxyPlugin.class, 10000, Integer.MIN_VALUE + 10000),

    //用于监控大key
    BIG_KEY_PLUGIN("bigKeyPlugin", BigKeyProxyPlugin.class, 0, 0),
    //用于缓存双删（仅拦截DEL命令）
    DELAY_DOUBLE_DELETE_PLUGIN("delayDoubleDeletePlugin", DelayDoubleDeleteProxyPlugin.class, 0, 0),
    //用于自定义双写规则（key维度的）
    MULTI_WRITE_PLUGIN("multiWritePlugin", MultiWriteProxyPlugin.class, 0, 0),

    //用于进行key/value的转换
    CONVERTER_PLUGIN("converterPlugin", ConverterProxyPlugin.class, Integer.MIN_VALUE, Integer.MAX_VALUE),
    ;
    private final String alias;
    private final Class<? extends ProxyPlugin> clazz;
    private final int requestOrder;
    private final int replyOrder;

    BuildInProxyPluginEnum(String alias, Class<? extends ProxyPlugin> clazz, int requestOrder, int replyOrder) {
        this.alias = alias;
        this.clazz = clazz;
        this.requestOrder = requestOrder;
        this.replyOrder = replyOrder;
    }
}
```
内置插件列表：    
* MonitorProxyPlugin，用于监控命令的请求qps和响应，以及监控慢查询等，具体见：[monitor-plugin](monitor-plugin.md)
* BigKeyProxyPlugin，用于监控大key，具体见：[big-key](big-key.md)
* HotKeyProxyPlugin，用于监控热key，具体见：[hot-key](hot-key.md)
* HotKeyCacheProxyPlugin，用于热key缓存，支持GET命令，具体见：[hot-key-cache](hot-key-cache.md)
* ConverterProxyPlugin，用于key/value的转换，如加解密，key命名空间等，具体见：[converter](converter.md)
* MultiWriteProxyPlugin，用于自定义双写（可以到key级别），具体见：[multi-write](multi-write.md)
* DelayDoubleDeleteProxyPlugin，用于透明的进行缓存双删（仅拦截DEL命令），具体见：[delay-double-delete](delay-double-delete.md)
* TroubleTrickKeysProxyPlugin，用于临时拦截问题key的某些命令，具体见：[trouble-trick-keys](trouble-trick-keys.md)
* RateLimitProxyPlugin，用于进行频率控制，支持租户级别进行控制，具体见：[rate-limit](rate-limit.md)
* DynamicRateLimitProxyPlugin，用于进行频率控制，支持租户级别进行控制，通过camellia-dashboard托管配置，具体见：[dynamic-rate-limit](dynamic-rate-limit.md)
* IPCheckProxyPlugin，用于控制客户端接入，支持ip黑白名单，具体见：[ip-checker](ip-checker.md)
* DynamicIpCheckProxyPlugin，用于控制客户端接入，支持ip黑白名单，通过camellia-dashboard托管配置，具体见：[dynamic-ip-checker](dynamic-ip-checker.md)
* CommandDisableProxyPlugin，用于屏蔽某些命令，具体见：[command-disable](command-disable.md)
* HotKeyRouteRewriteProxyPlugin，覆盖了HotKeyProxyPlugin的功能，用于监控热key并转发到自定义路由，具体见：[hot-key-route-rewrite](hot-key-route-rewrite.md)
* ReadOnlyProxyPlugin，开启后，只接受读命令，具体见：[read-only](read-only.md)

### 内置插件修改默认执行顺序的方法(1.2.4开始支持)
在camellia-redis-proxy.properties或者application.yml的config中，配置如下key-value-config：
```
## 修改request的order
{alias}.build.in.plugin.request.order=10
## 修改reply的order
{alias}.build.in.plugin.reply.order=10
```

### 其他camellia提供的插件（不是内置，需要额外引入maven依赖）
* MqMultiWriteProducerProxyPlugin，用于使用mq进行异步双写，具体见：[mq-multi-write](mq-multi-write.md)
* HotKeyMonitorPlugin/HotKeyCachePlugin，基于hot-key-sdk/hot-key-server的热key监控/缓存，具体见: [hot-key-cache-extension](hot-key-cache-extension.md)

### 自定义插件
实现ProxyPlugin接口，并把实现类的全类名配置到application.yml或者camellia-redis-proxy.properties里即可

