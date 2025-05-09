/*
 * Copyright 2020 Netflix, Inc.
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

package com.netflix.zuul.netty.server.http2;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.netflix.netty.common.Http2ConnectionCloseHandler;
import com.netflix.netty.common.Http2ConnectionExpiryHandler;
import com.netflix.netty.common.channel.config.ChannelConfig;
import com.netflix.netty.common.channel.config.ChannelConfigValue;
import com.netflix.netty.common.channel.config.CommonChannelConfigKeys;
import com.netflix.netty.common.metrics.Http2MetricsChannelHandlers;
import com.netflix.spectator.api.NoopRegistry;
import com.netflix.zuul.netty.server.BaseZuulChannelInitializer;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.ssl.ApplicationProtocolNames;
import org.junit.jupiter.api.Test;

/**
 * @author Argha C
 * @since November 18, 2020
 */
class Http2OrHttpHandlerTest {

    @Test
    void swapInHttp2HandlerBasedOnALPN() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        NoopRegistry registry = new NoopRegistry();
        ChannelConfig channelConfig = new ChannelConfig();
        channelConfig.add(new ChannelConfigValue<>(CommonChannelConfigKeys.maxHttp2HeaderListSize, 32768));

        Http2ConnectionCloseHandler connectionCloseHandler = new Http2ConnectionCloseHandler(registry);
        Http2ConnectionExpiryHandler connectionExpiryHandler =
                new Http2ConnectionExpiryHandler(100, 100, 20 * 60 * 1000);
        Http2MetricsChannelHandlers http2MetricsChannelHandlers =
                new Http2MetricsChannelHandlers(registry, "server", "http2-443");
        Http2OrHttpHandler http2OrHttpHandler = new Http2OrHttpHandler(
                new Http2StreamInitializer(
                        channel,
                        (x) -> {},
                        http2MetricsChannelHandlers,
                        connectionCloseHandler,
                        connectionExpiryHandler),
                channelConfig,
                cp -> {});

        channel.pipeline().addLast("codec_placeholder", new DummyChannelHandler());
        channel.pipeline().addLast(Http2OrHttpHandler.class.getSimpleName(), http2OrHttpHandler);

        http2OrHttpHandler.configurePipeline(channel.pipeline().lastContext(), ApplicationProtocolNames.HTTP_2);

        assertThat(channel.pipeline().get(Http2FrameCodec.class)).isInstanceOf(Http2FrameCodec.class);
        assertThat(channel.pipeline().get(BaseZuulChannelInitializer.HTTP_CODEC_HANDLER_NAME))
                .isInstanceOf(Http2MultiplexHandler.class);
        assertEquals("HTTP/2", channel.attr(Http2OrHttpHandler.PROTOCOL_NAME).get());
    }

    @Test
    void protocolCloseHandlerAddedByDefault() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelConfig channelConfig = new ChannelConfig();
        channelConfig.add(new ChannelConfigValue<>(CommonChannelConfigKeys.maxHttp2HeaderListSize, 32768));

        Http2OrHttpHandler http2OrHttpHandler =
                new Http2OrHttpHandler(new ChannelInboundHandlerAdapter(), channelConfig, cp -> {});

        channel.pipeline().addLast("codec_placeholder", new DummyChannelHandler());
        channel.pipeline().addLast(Http2OrHttpHandler.class.getSimpleName(), http2OrHttpHandler);

        http2OrHttpHandler.configurePipeline(channel.pipeline().lastContext(), ApplicationProtocolNames.HTTP_2);
        assertNotNull(channel.pipeline().context(Http2ConnectionErrorHandler.class));
    }

    @Test
    void skipProtocolCloseHandler() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelConfig channelConfig = new ChannelConfig();
        channelConfig.add(new ChannelConfigValue<>(CommonChannelConfigKeys.http2CatchConnectionErrors, false));
        channelConfig.add(new ChannelConfigValue<>(CommonChannelConfigKeys.maxHttp2HeaderListSize, 32768));

        Http2OrHttpHandler http2OrHttpHandler =
                new Http2OrHttpHandler(new ChannelInboundHandlerAdapter(), channelConfig, cp -> {});

        channel.pipeline().addLast("codec_placeholder", new DummyChannelHandler());
        channel.pipeline().addLast(Http2OrHttpHandler.class.getSimpleName(), http2OrHttpHandler);

        http2OrHttpHandler.configurePipeline(channel.pipeline().lastContext(), ApplicationProtocolNames.HTTP_2);
        assertNull(channel.pipeline().context(Http2ConnectionErrorHandler.class));
    }
}
