/*
 * Copyright 2022 Netflix, Inc.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */

package com.netflix.zuul.integration.server;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.config.DynamicIntProperty;
import com.netflix.discovery.EurekaClient;
import com.netflix.netty.common.accesslog.AccessLogPublisher;
import com.netflix.netty.common.channel.config.ChannelConfig;
import com.netflix.netty.common.channel.config.CommonChannelConfigKeys;
import com.netflix.netty.common.metrics.EventLoopGroupMetrics;
import com.netflix.netty.common.proxyprotocol.StripUntrustedProxyHeadersHandler;
import com.netflix.netty.common.ssl.ServerSslConfig;
import com.netflix.netty.common.status.ServerStatusManager;
import com.netflix.spectator.api.Registry;
import com.netflix.zuul.FilterLoader;
import com.netflix.zuul.FilterUsageNotifier;
import com.netflix.zuul.RequestCompleteHandler;
import com.netflix.zuul.context.SessionContextDecorator;
import com.netflix.zuul.netty.server.BaseServerStartup;
import com.netflix.zuul.netty.server.DefaultEventLoopConfig;
import com.netflix.zuul.netty.server.DirectMemoryMonitor;
import com.netflix.zuul.netty.server.Http1MutualSslChannelInitializer;
import com.netflix.zuul.netty.server.NamedSocketAddress;
import com.netflix.zuul.netty.server.SocketAddressProperty;
import com.netflix.zuul.netty.server.ZuulDependencyKeys;
import com.netflix.zuul.netty.server.ZuulServerChannelInitializer;
import com.netflix.zuul.netty.server.http2.Http2SslChannelInitializer;
import com.netflix.zuul.netty.server.push.PushConnectionRegistry;
import com.netflix.zuul.netty.ssl.BaseSslContextFactory;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.compression.CompressionOptions;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.ssl.ClientAuth;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Singleton
public class ServerStartup extends BaseServerStartup {

    enum ServerType {
        HTTP,
        HTTP2,
        HTTP_MUTUAL_TLS,
        WEBSOCKET,
        SSE
    }

    private static final String[] WWW_PROTOCOLS = new String[] {"TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1", "SSLv3"};
    private static final ServerType SERVER_TYPE = ServerType.HTTP;
    private final PushConnectionRegistry pushConnectionRegistry;
    //    private final SamplePushMessageSenderInitializer pushSenderInitializer;

    @Inject
    public ServerStartup(
            ServerStatusManager serverStatusManager,
            FilterLoader filterLoader,
            SessionContextDecorator sessionCtxDecorator,
            FilterUsageNotifier usageNotifier,
            RequestCompleteHandler reqCompleteHandler,
            Registry registry,
            DirectMemoryMonitor directMemoryMonitor,
            EventLoopGroupMetrics eventLoopGroupMetrics,
            EurekaClient discoveryClient,
            ApplicationInfoManager applicationInfoManager,
            AccessLogPublisher accessLogPublisher,
            PushConnectionRegistry pushConnectionRegistry) {
        super(
                serverStatusManager,
                filterLoader,
                sessionCtxDecorator,
                usageNotifier,
                reqCompleteHandler,
                registry,
                directMemoryMonitor,
                eventLoopGroupMetrics,
                new DefaultEventLoopConfig(),
                discoveryClient,
                applicationInfoManager,
                accessLogPublisher);
        this.pushConnectionRegistry = pushConnectionRegistry;
        // this.pushSenderInitializer = pushSenderInitializer;
    }

    @Override
    protected Map<NamedSocketAddress, ChannelInitializer<?>> chooseAddrsAndChannels(ChannelGroup clientChannels) {
        Map<NamedSocketAddress, ChannelInitializer<?>> addrsToChannels = new HashMap<>();
        SocketAddress sockAddr;
        String metricId;
        {
            int port = new DynamicIntProperty("zuul.server.port.main", 7001).get();
            sockAddr = new SocketAddressProperty("zuul.server.addr.main", "=" + port).getValue();
            if (sockAddr instanceof InetSocketAddress) {
                metricId = String.valueOf(((InetSocketAddress) sockAddr).getPort());
            } else {
                // Just pick something.   This would likely be a UDS addr or a LocalChannel addr.
                metricId = sockAddr.toString();
            }
        }

        SocketAddress pushSockAddr;
        {
            int pushPort = new DynamicIntProperty("zuul.server.port.http.push", 7008).get();
            pushSockAddr = new SocketAddressProperty("zuul.server.addr.http.push", "=" + pushPort).getValue();
        }

        String mainListenAddressName = "main";
        ServerSslConfig sslConfig;
        ChannelConfig channelConfig = defaultChannelConfig(mainListenAddressName);
        ChannelConfig channelDependencies = defaultChannelDependencies(mainListenAddressName);

        /* These settings may need to be tweaked depending if you're running behind an ELB HTTP listener, TCP listener,
         * or directly on the internet.
         */
        switch (SERVER_TYPE) {
                /* The below settings can be used when running behind an ELB HTTP listener that terminates SSL for you
                 * and passes XFF headers.
                 */
            case HTTP:
                channelConfig.set(
                        CommonChannelConfigKeys.allowProxyHeadersWhen,
                        StripUntrustedProxyHeadersHandler.AllowWhen.ALWAYS);
                channelConfig.set(CommonChannelConfigKeys.preferProxyProtocolForClientIp, false);
                channelConfig.set(CommonChannelConfigKeys.isSSlFromIntermediary, false);
                channelConfig.set(CommonChannelConfigKeys.withProxyProtocol, false);

                addrsToChannels.put(
                        new NamedSocketAddress("http", sockAddr),
                        new ZuulServerChannelInitializer(metricId, channelConfig, channelDependencies, clientChannels) {
                            @Override
                            protected void addHttp1Handlers(ChannelPipeline pipeline) {
                                super.addHttp1Handlers(pipeline);
                                pipeline.addLast(new HttpContentCompressor((CompressionOptions[]) null));
                            }
                        });
                logAddrConfigured(sockAddr);
                break;

                /* The below settings can be used when running behind an ELB TCP listener with proxy protocol, terminating
                 * SSL in Zuul.
                 */
            case HTTP2:
                sslConfig = ServerSslConfig.withDefaultCiphers(
                        loadFromResources("server.cert"), loadFromResources("server.key"), WWW_PROTOCOLS);

                channelConfig.set(
                        CommonChannelConfigKeys.allowProxyHeadersWhen,
                        StripUntrustedProxyHeadersHandler.AllowWhen.NEVER);
                channelConfig.set(CommonChannelConfigKeys.preferProxyProtocolForClientIp, true);
                channelConfig.set(CommonChannelConfigKeys.isSSlFromIntermediary, false);
                channelConfig.set(CommonChannelConfigKeys.serverSslConfig, sslConfig);
                channelConfig.set(
                        CommonChannelConfigKeys.sslContextFactory, new BaseSslContextFactory(registry, sslConfig));

                addHttp2DefaultConfig(channelConfig, mainListenAddressName);

                addrsToChannels.put(
                        new NamedSocketAddress("http2", sockAddr),
                        new Http2SslChannelInitializer(metricId, channelConfig, channelDependencies, clientChannels));
                logAddrConfigured(sockAddr, sslConfig);
                break;

                /* The below settings can be used when running behind an ELB TCP listener with proxy protocol, terminating
                 * SSL in Zuul.
                 *
                 * Can be tested using certs in resources directory:
                 *  curl https://localhost:7001/test -vk --cert src/main/resources/ssl/client.cert:zuul123 --key src/main/resources/ssl/client.key
                 */
            case HTTP_MUTUAL_TLS:
                sslConfig = new ServerSslConfig(
                        WWW_PROTOCOLS,
                        ServerSslConfig.getDefaultCiphers(),
                        loadFromResources("server.cert"),
                        loadFromResources("server.key"),
                        ClientAuth.REQUIRE,
                        loadFromResources("truststore.jks"),
                        loadFromResources("truststore.key"),
                        false);

                channelConfig.set(
                        CommonChannelConfigKeys.allowProxyHeadersWhen,
                        StripUntrustedProxyHeadersHandler.AllowWhen.NEVER);
                channelConfig.set(CommonChannelConfigKeys.preferProxyProtocolForClientIp, true);
                channelConfig.set(CommonChannelConfigKeys.isSSlFromIntermediary, false);
                channelConfig.set(CommonChannelConfigKeys.withProxyProtocol, true);
                channelConfig.set(CommonChannelConfigKeys.serverSslConfig, sslConfig);
                channelConfig.set(
                        CommonChannelConfigKeys.sslContextFactory, new BaseSslContextFactory(registry, sslConfig));

                addrsToChannels.put(
                        new NamedSocketAddress("http_mtls", sockAddr),
                        new Http1MutualSslChannelInitializer(
                                metricId, channelConfig, channelDependencies, clientChannels));
                logAddrConfigured(sockAddr, sslConfig);
                break;

                /* Settings to be used when running behind an ELB TCP listener with proxy protocol as a Push notification
                 * server using WebSockets */
            case WEBSOCKET:
                channelConfig.set(
                        CommonChannelConfigKeys.allowProxyHeadersWhen,
                        StripUntrustedProxyHeadersHandler.AllowWhen.NEVER);
                channelConfig.set(CommonChannelConfigKeys.preferProxyProtocolForClientIp, true);
                channelConfig.set(CommonChannelConfigKeys.isSSlFromIntermediary, false);
                channelConfig.set(CommonChannelConfigKeys.withProxyProtocol, true);

                channelDependencies.set(ZuulDependencyKeys.pushConnectionRegistry, pushConnectionRegistry);

                /*
                addrsToChannels.put(
                        new NamedSocketAddress("websocket", sockAddr),
                        new SampleWebSocketPushChannelInitializer(
                                metricId, channelConfig, channelDependencies, clientChannels)); */
                logAddrConfigured(sockAddr);

                // port to accept push message from the backend, should be accessible on internal network only.
                // TODO ? addrsToChannels.put(new NamedSocketAddress("http.push", pushSockAddr), pushSenderInitializer);
                logAddrConfigured(pushSockAddr);
                break;

                /* Settings to be used when running behind an ELB TCP listener with proxy protocol as a Push notification
                 * server using Server Sent Events (SSE) */
            case SSE:
                channelConfig.set(
                        CommonChannelConfigKeys.allowProxyHeadersWhen,
                        StripUntrustedProxyHeadersHandler.AllowWhen.NEVER);
                channelConfig.set(CommonChannelConfigKeys.preferProxyProtocolForClientIp, true);
                channelConfig.set(CommonChannelConfigKeys.isSSlFromIntermediary, false);
                channelConfig.set(CommonChannelConfigKeys.withProxyProtocol, true);

                channelDependencies.set(ZuulDependencyKeys.pushConnectionRegistry, pushConnectionRegistry);

                /*
                addrsToChannels.put(
                        new NamedSocketAddress("sse", sockAddr),
                        new SampleSSEPushChannelInitializer(
                                metricId, channelConfig, channelDependencies, clientChannels)); */
                logAddrConfigured(sockAddr);
                // port to accept push message from the backend, should be accessible on internal network only.
                // todo ? addrsToChannels.put(new NamedSocketAddress("http.push", pushSockAddr), pushSenderInitializer);
                logAddrConfigured(pushSockAddr);
                break;
        }

        return Collections.unmodifiableMap(addrsToChannels);
    }

    private File loadFromResources(String s) {
        return new File(ClassLoader.getSystemResource("ssl/" + s).getFile());
    }
}
