/*
 * Copyright 2016-2018 shardingsphere.io.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package io.shardingsphere.proxy.backend.netty.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.pool.AbstractChannelPoolMap;
import io.netty.channel.pool.ChannelPoolMap;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.channel.pool.SimpleChannelPool;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.shardingsphere.core.metadata.datasource.DataSourceMetaData;
import io.shardingsphere.core.rule.DataSourceParameter;
import io.shardingsphere.proxy.config.RuleRegistry;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Backend connection client for netty.
 *
 * @author wangkai
 * @author linjiaqi
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Slf4j
public final class BackendNettyClient {
    
    private static final BackendNettyClient INSTANCE = new BackendNettyClient();
    
    private static final RuleRegistry RULE_REGISTRY = RuleRegistry.getInstance();
    
    private static final int WORKER_MAX_THREADS = Runtime.getRuntime().availableProcessors();
    
    private static final int MAX_CONNECTIONS = RULE_REGISTRY.getBackendNIOConfig().getMaxConnections();
    
    private static final int CONNECTION_TIMEOUT_SECONDS = RULE_REGISTRY.getBackendNIOConfig().getConnectionTimeoutSeconds();
    
    private final Map<String, DataSourceConfig> dataSourceConfigMap = new HashMap<>();
    
    private EventLoopGroup workerGroup;
    
    @Getter
    private ChannelPoolMap<String, SimpleChannelPool> poolMap;
    
    /**
     * Get instance of backend connection client for netty.
     *
     * @return instance of backend connection client for netty
     */
    public static BackendNettyClient getInstance() {
        return INSTANCE;
    }
    
    /**
     * Start backend connection client for netty.
     *
     * @throws InterruptedException  interrupted exception
     */
    public void start() throws InterruptedException {
        Map<String, DataSourceParameter> dataSourceConfigurationMap = RULE_REGISTRY.getDataSourceConfigurationMap();
        for (Entry<String, DataSourceParameter> each : dataSourceConfigurationMap.entrySet()) {
            String actualDataSourceName = each.getKey();
            DataSourceParameter dataSourceParameter = each.getValue();
            DataSourceMetaData dataSourceMetaData = RULE_REGISTRY.getMetaData().getDataSource().getActualDataSourceMetaData(actualDataSourceName);
            dataSourceConfigMap.put(each.getKey(), new DataSourceConfig(
                    dataSourceMetaData.getHostName(), dataSourceMetaData.getPort(), dataSourceMetaData.getSchemeName(), dataSourceParameter.getUsername(), dataSourceParameter.getPassword()));
        }
        Bootstrap bootstrap = new Bootstrap();
        // TODO :jiaqi where to init workerGroup?
        if (workerGroup instanceof EpollEventLoopGroup) {
            groupsEpoll(bootstrap);
        } else {
            groupsNio(bootstrap);
        }
        initPoolMap(bootstrap);
    }
    
    /**
     * Stop backend connection client for netty.
     */
    public void stop() {
        if (null != workerGroup) {
            workerGroup.shutdownGracefully();
        }
    }
    
    private void groupsEpoll(final Bootstrap bootstrap) {
        workerGroup = new EpollEventLoopGroup(WORKER_MAX_THREADS);
        bootstrap.group(workerGroup)
                .channel(EpollSocketChannel.class)
                .option(EpollChannelOption.TCP_CORK, true)
                .option(EpollChannelOption.SO_KEEPALIVE, true)
                .option(EpollChannelOption.SO_BACKLOG, 128)
                .option(EpollChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
    }
    
    private void groupsNio(final Bootstrap bootstrap) {
        workerGroup = new NioEventLoopGroup(WORKER_MAX_THREADS);
        bootstrap.group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_BACKLOG, 128)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 100)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
    }
    
    private void initPoolMap(final Bootstrap bootstrap) throws InterruptedException {
        poolMap = new AbstractChannelPoolMap<String, SimpleChannelPool>() {
            
            @Override
            protected SimpleChannelPool newPool(final String datasourceName) {
                DataSourceConfig dataSourceConfig = dataSourceConfigMap.get(datasourceName);
                return new FixedChannelPool(bootstrap.remoteAddress(dataSourceConfig.getIp(), dataSourceConfig.getPort()), new NettyChannelPoolHandler(dataSourceConfig), MAX_CONNECTIONS);
            }
        };
        for (String each : dataSourceConfigMap.keySet()) {
            SimpleChannelPool pool = poolMap.get(each);
            Channel[] channels = new Channel[MAX_CONNECTIONS];
            for (int i = 0; i < MAX_CONNECTIONS; i++) {
                try {
                    channels[i] = pool.acquire().get(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (final ExecutionException | TimeoutException ex) {
                    log.error(ex.getMessage(), ex);
                }
            }
            for (int i = 0; i < MAX_CONNECTIONS; i++) {
                pool.release(channels[i]);
            }
        }
    }
}
