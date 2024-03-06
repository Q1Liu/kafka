/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.clients;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.network.ChannelBuilder;
import org.apache.kafka.common.network.ChannelBuilders;
import org.apache.kafka.common.security.JaasContext;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.common.utils.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.apache.kafka.common.utils.Utils.getHost;
import static org.apache.kafka.common.utils.Utils.getPort;

public final class ClientUtils {
    private static final Logger log = LoggerFactory.getLogger(ClientUtils.class);
    private static final long DUPLICATE_WINDOW_MS = 1000; // 1 second
    private static final Map<String, Long> ERROR_DEDUPLICATION_CACHE = new ConcurrentHashMap<>();

    private ClientUtils() {
    }

    public static List<InetSocketAddress> parseAndValidateAddresses(List<String> urls, String clientDnsLookupConfig) {
        return parseAndValidateAddresses(urls, ClientDnsLookup.forConfig(clientDnsLookupConfig));
    }

    /**
     * Kafka does not use this function directly. However,
     * some third-party applications still rely on this API to parse and validate addresses.
     */
    public static List<InetSocketAddress> parseAndValidateAddresses(List<String> urls) {
        return parseAndValidateAddresses(urls, ClientDnsLookup.USE_ALL_DNS_IPS);
    }

    public static List<InetSocketAddress> parseAndValidateAddresses(List<String> urls, ClientDnsLookup clientDnsLookup) {
        List<InetSocketAddress> addresses = new ArrayList<>();
        for (String url : urls) {
            if (url != null && !url.isEmpty()) {
                try {
                    String host = getHost(url);
                    Integer port = getPort(url);
                    if (host == null || port == null)
                        throw new ConfigException("Invalid url in " + CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG + ": " + url);

                    if (clientDnsLookup == ClientDnsLookup.RESOLVE_CANONICAL_BOOTSTRAP_SERVERS_ONLY) {
                        InetAddress[] inetAddresses = InetAddress.getAllByName(host);
                        for (InetAddress inetAddress : inetAddresses) {
                            String resolvedCanonicalName = inetAddress.getCanonicalHostName();
                            InetSocketAddress address = new InetSocketAddress(resolvedCanonicalName, port);
                            if (address.isUnresolved()) {
                                String message = String.format("Couldn't resolve server %s from %s as DNS resolution of the canonical hostname %s failed for %s",
                                    url, CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, resolvedCanonicalName, host);
                                dedupeAndHandleMessage(message, false);
                            } else {
                                addresses.add(address);
                            }
                        }
                    } else {
                        InetSocketAddress address = new InetSocketAddress(host, port);
                        if (address.isUnresolved()) {
                            String message = String.format("Couldn't resolve server %s from %s as DNS resolution failed for %s", url, CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, host);
                            dedupeAndHandleMessage(message, false);
                        } else {
                            addresses.add(address);
                        }
                    }

                } catch (IllegalArgumentException e) {
                    throw new ConfigException("Invalid port in " + CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG + ": " + url);
                } catch (UnknownHostException e) {
                    throw new ConfigException("Unknown host in " + CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG + ": " + url);
                }
            }
        }
        if (addresses.isEmpty())
            dedupeAndHandleMessage("No resolvable bootstrap server in provided urls: " + String.join(",", urls), true);
        return addresses;
    }

    public static void dedupeAndHandleMessage(String message, Boolean isError) {
        long currentTime = System.currentTimeMillis();
        if (!isDuplicateError(message, currentTime)) {
            ERROR_DEDUPLICATION_CACHE.put(message, currentTime);
            if (isError) {
                throw new ConfigException(message);
            } else {
                log.warn(message);
            }
        }
    }

    private static boolean isDuplicateError(String message, long currentTime) {
        Long previousTime = ERROR_DEDUPLICATION_CACHE.get(message);
        return previousTime != null && (currentTime - previousTime) < DUPLICATE_WINDOW_MS;
    }

    /**
     * @param config client configs
     * @return configured ChannelBuilder based on the configs.
     */
    public static ChannelBuilder createChannelBuilder(AbstractConfig config, Time time) {
        SecurityProtocol securityProtocol = SecurityProtocol.forName(config.getString(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        String clientSaslMechanism = config.getString(SaslConfigs.SASL_MECHANISM);
        return ChannelBuilders.clientChannelBuilder(securityProtocol, JaasContext.Type.CLIENT, config, null,
                clientSaslMechanism, time, true);
    }

    static List<InetAddress> resolve(String host, ClientDnsLookup clientDnsLookup) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        log.debug("Resolved {} and got addresses: ", host);
        for (InetAddress address: addresses) {
            log.debug(address.getHostAddress() + ";");
        }
        if (ClientDnsLookup.USE_ALL_DNS_IPS == clientDnsLookup) {
            return filterPreferredAddresses(addresses);
        } else {
            return Collections.singletonList(addresses[0]);
        }
    }

    static List<InetAddress> filterPreferredAddresses(InetAddress[] allAddresses) {
        List<InetAddress> preferredAddresses = new ArrayList<>();
        Class<? extends InetAddress> clazz = null;
        for (InetAddress address : allAddresses) {
            if (clazz == null) {
                clazz = address.getClass();
            }
            if (clazz.isInstance(address)) {
                preferredAddresses.add(address);
            }
        }
        return preferredAddresses;
    }
}
