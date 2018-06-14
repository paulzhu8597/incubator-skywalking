/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.apm.collector.receiver.zipkin.provider.cache.caffeine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import org.apache.skywalking.apm.collector.receiver.zipkin.provider.ZipkinReceiverConfig;
import org.apache.skywalking.apm.collector.receiver.zipkin.provider.cache.ISpanCache;
import org.apache.skywalking.apm.collector.receiver.zipkin.provider.data.ZipkinTrace;
import org.apache.skywalking.apm.collector.receiver.zipkin.provider.transform.Zipkin2SkyWalkingTransfer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zipkin2.Span;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * NOTICE: FROM my test, Caffeine cache triggers/checks expire only face write/read op.
 * In order to make trace finish in time, I have to set a timer to write a meaningless trace, for active expire.
 *
 * @author wusheng
 */
public class CaffeineSpanCache implements ISpanCache, RemovalListener<String, ZipkinTrace> {
    private static final Logger logger = LoggerFactory.getLogger(CaffeineSpanCache.class);
    private Cache<String, ZipkinTrace> inProcessSpanCache;
    private ReentrantLock newTraceLock;

    public CaffeineSpanCache(ZipkinReceiverConfig config) {
        newTraceLock = new ReentrantLock();
        inProcessSpanCache = Caffeine.newBuilder()
                //TODO: uncomment this for real codes.
                //.expireAfterWrite(config.getExpireTime(), TimeUnit.MINUTES)
                .expireAfterWrite(10, TimeUnit.SECONDS)
                .maximumSize(config.getMaxCacheSize())
                .removalListener(this)
                .build();
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            inProcessSpanCache.put("ACTIVE", new ZipkinTrace.TriggerTrace());
        }, 2, 3, TimeUnit.SECONDS);
    }

    /**
     * Zipkin trace finished by the expired rule.
     *
     * @param key
     * @param trace
     * @param cause
     */
    @Override
    public void onRemoval(@Nullable String key, @Nullable ZipkinTrace trace, @Nonnull RemovalCause cause) {
        if (trace instanceof ZipkinTrace.TriggerTrace) {
            return;
        }
        try {
            Zipkin2SkyWalkingTransfer.INSTANCE.transfer(trace);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    @Override
    public void addSpan(Span span) {
        ZipkinTrace trace = inProcessSpanCache.getIfPresent(span.traceId());
        if (trace == null) {
            newTraceLock.lock();
            try {
                trace = inProcessSpanCache.getIfPresent(span.traceId());
                if (trace == null) {
                    trace = new ZipkinTrace();
                    inProcessSpanCache.put(span.traceId(), trace);
                }
            } finally {
                newTraceLock.unlock();
            }
        }
        trace.addSpan(span);
    }
}
