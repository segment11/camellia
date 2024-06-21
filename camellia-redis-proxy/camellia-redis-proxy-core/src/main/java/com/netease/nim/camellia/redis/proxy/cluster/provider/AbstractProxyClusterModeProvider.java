package com.netease.nim.camellia.redis.proxy.cluster.provider;

import com.netease.nim.camellia.redis.base.exception.CamelliaRedisException;
import com.netease.nim.camellia.redis.proxy.cluster.ProxyNode;
import com.netease.nim.camellia.redis.proxy.conf.ProxyDynamicConf;
import com.netease.nim.camellia.redis.proxy.netty.GlobalRedisProxyEnv;
import com.netease.nim.camellia.redis.proxy.reply.Reply;
import com.netease.nim.camellia.redis.proxy.upstream.connection.RedisConnectionAddr;
import com.netease.nim.camellia.tools.executor.CamelliaThreadFactory;
import com.netease.nim.camellia.tools.utils.CamelliaMapUtils;
import com.netease.nim.camellia.tools.utils.InetUtils;
import com.netease.nim.camellia.tools.utils.SysUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Created by caojiajun on 2024/6/19
 */
public abstract class AbstractProxyClusterModeProvider implements ProxyClusterModeProvider {

    private static final Logger logger = LoggerFactory.getLogger(AbstractProxyClusterModeProvider.class);

    protected static final int executorSize;
    static {
        executorSize = Math.max(4, Math.min(SysUtils.getCpuNum(), 8));
    }

    protected static final ScheduledExecutorService schedule = Executors.newScheduledThreadPool(executorSize,
            new CamelliaThreadFactory("proxy-cluster-mode-schedule"));

    protected static final ThreadPoolExecutor heartbeatExecutor = new ThreadPoolExecutor(executorSize, executorSize,
            0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(10000), new CamelliaThreadFactory("proxy-heartbeat-sender"), new ThreadPoolExecutor.AbortPolicy());


    protected final CopyOnWriteArrayList<ProxyNodeChangeListener> listenerList = new CopyOnWriteArrayList<>();

    private ProxyNode current;
    private Set<ProxyNode> initNodes;
    private final ConcurrentHashMap<ProxyNode, RedisConnectionAddr> addrCache = new ConcurrentHashMap<>();


    @Override
    public final void addNodeChangeListener(ProxyNodeChangeListener listener) {
        listenerList.add(listener);
    }

    protected final void nodeChangeNotify() {
        for (ProxyNodeChangeListener listener : listenerList) {
            try {
                listener.change();
            } catch (Exception e) {
                logger.error("nodeChangeNotify callback error", e);
            }
        }
    }

    protected final Reply sync(CompletableFuture<Reply> future) {
        try {
            return future.get(heartbeatTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.info("sync reply error", e);
            throw new CamelliaRedisException(e);
        }
    }

    protected final int heartbeatTimeoutSeconds() {
        return ProxyDynamicConf.getInt("proxy.cluster.mode.heartbeat.request.timeout.seconds", 10);
    }

    protected final Set<ProxyNode> initNodes() {
        if (initNodes != null) {
            return new HashSet<>(initNodes);
        }
        String string = ProxyDynamicConf.getString("proxy.cluster.mode.nodes", null);
        if (string == null) {
            throw new IllegalArgumentException("missing 'proxy.cluster.mode.nodes' in ProxyDynamicConf");
        }
        String[] split = string.split(",");
        Set<ProxyNode> initNodes = new HashSet<>();
        for (String str : split) {
            ProxyNode node = ProxyNode.parseString(str);
            if (node == null) continue;
            initNodes.add(node);
        }
        if (initNodes.isEmpty()) {
            throw new IllegalArgumentException("parse 'proxy.cluster.mode.nodes' error");
        }
        this.initNodes = initNodes;
        return this.initNodes;
    }

    protected final ProxyNode current() {
        if (current != null) return current;
        String host = ProxyDynamicConf.getString("proxy.cluster.mode.current.node.host", null);
        if (host != null) {
            int port = GlobalRedisProxyEnv.getPort();
            int cport = GlobalRedisProxyEnv.getCport();
            if (port == 0 || cport == 0) {
                throw new IllegalStateException("redis proxy not start");
            }
            this.current = new ProxyNode(host, port, cport);
        } else {
            this.current = currentNode0();
        }
        logger.info("current proxy node = {}", current);
        return current;
    }

    protected final RedisConnectionAddr toAddr(ProxyNode proxyNode) {
        return CamelliaMapUtils.computeIfAbsent(addrCache, proxyNode,
                node -> new RedisConnectionAddr(node.getHost(), node.getCport(), null, null));
    }

    private ProxyNode currentNode0() {
        InetAddress inetAddress = InetUtils.findFirstNonLoopbackAddress();
        if (inetAddress == null) {
            throw new IllegalStateException("not found non loopback address");
        }
        int port = GlobalRedisProxyEnv.getPort();
        int cport = GlobalRedisProxyEnv.getCport();
        if (port == 0 || cport == 0) {
            throw new IllegalStateException("redis proxy not start");
        }
        return new ProxyNode(inetAddress.getHostAddress(), port, cport);
    }
}
