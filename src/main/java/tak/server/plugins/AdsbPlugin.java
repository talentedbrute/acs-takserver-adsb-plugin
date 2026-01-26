/*
 * Copyright (c) 2026 Adeptus Cyber Solutions, LLC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package tak.server.plugins;

import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import atakmap.commoncommo.protobuf.v1.MessageOuterClass.Message;

/**
 * TAK Server plugin that polls the airplanes.live API and injects ADS-B aircraft
 * data as CoT messages.
 *
 * Configuration (YAML file: /opt/tak/conf/plugins/tak.server.plugins.AdsbPlugin.yaml):
 *
 * interval: 5000           # Polling interval in ms (min 5000 for rate limits)
 * latitude: 34.0007        # Center point latitude
 * longitude: -81.0348      # Center point longitude
 * radius: 250              # Query radius in nm (max 250)
 * staleTimeSec: 30         # Seconds until CoT marker becomes stale
 * groups:                  # TAK groups to publish to
 *   - "__ANON__"
 */
@TakServerPlugin(
    name = "ADS-B Aircraft Feed",
    description = "Injects ADS-B aircraft data from airplanes.live API into TAK Server"
)
public class AdsbPlugin extends MessageSenderBase {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final String API_BASE = "https://api.airplanes.live/v2";
    private static final long DEFAULT_INTERVAL = 5000;
    private static final double DEFAULT_LATITUDE = 34.0007;
    private static final double DEFAULT_LONGITUDE = -81.0348;
    private static final int DEFAULT_RADIUS = 75;
    private static final int DEFAULT_STALE_TIME_SEC = 30;
    private static final int MAX_RADIUS = 250;

    private final ScheduledExecutorService worker = Executors.newScheduledThreadPool(1);
    private ScheduledFuture<?> future;
    private CloseableHttpClient httpClient;

    private final long interval;
    private final double latitude;
    private final double longitude;
    private final int radius;
    private final int staleTimeSec;
    private final Set<String> groups;
    private final String pluginId;
    private final HttpHost proxy;

    private final AtomicInteger totalMessagesSent = new AtomicInteger(0);
    private final AtomicInteger totalPollCycles = new AtomicInteger(0);

    @SuppressWarnings("unchecked")
    public AdsbPlugin() {
        this.pluginId = Integer.toString(System.identityHashCode(this));

        // Read configuration from YAML
        if (config.containsProperty("interval")) {
            long configInterval = ((Number) config.getProperty("interval")).longValue();
            this.interval = Math.max(configInterval, DEFAULT_INTERVAL); // Enforce minimum
        } else {
            this.interval = DEFAULT_INTERVAL;
        }

        if (config.containsProperty("latitude")) {
            this.latitude = ((Number) config.getProperty("latitude")).doubleValue();
        } else {
            this.latitude = DEFAULT_LATITUDE;
        }

        if (config.containsProperty("longitude")) {
            this.longitude = ((Number) config.getProperty("longitude")).doubleValue();
        } else {
            this.longitude = DEFAULT_LONGITUDE;
        }

        if (config.containsProperty("radius")) {
            int configRadius = ((Number) config.getProperty("radius")).intValue();
            this.radius = Math.min(configRadius, MAX_RADIUS); // Enforce maximum
        } else {
            this.radius = DEFAULT_RADIUS;
        }

        if (config.containsProperty("staleTimeSec")) {
            this.staleTimeSec = ((Number) config.getProperty("staleTimeSec")).intValue();
        } else {
            this.staleTimeSec = DEFAULT_STALE_TIME_SEC;
        }

        if (config.containsProperty("groups")) {
            Object groupsConfig = config.getProperty("groups");
            this.groups = new HashSet<>();
            if (groupsConfig instanceof List) {
                this.groups.addAll((List<String>) groupsConfig);
            } else if (groupsConfig instanceof String) {
                // Handle single group as string
                this.groups.add((String) groupsConfig);
            }
        } else {
            this.groups = new HashSet<>();
        }

        // Get proxy from environment
        this.proxy = getProxyFromEnvironment();

        logger.info("ADS-B Plugin Configuration:");
        logger.info("  Interval: {} ms", interval);
        logger.info("  Center: ({}, {})", latitude, longitude);
        logger.info("  Radius: {} nm", radius);
        logger.info("  Stale time: {} sec", staleTimeSec);
        logger.info("  Groups: {}", groups.isEmpty() ? "(all)" : groups);
        if (proxy != null) {
            logger.info("  Proxy: {}:{}", proxy.getHostName(), proxy.getPort());
        }
    }

    @Override
    public void start() {
        logger.info("Starting ADS-B Plugin - polling airplanes.live API");

        // Create HTTP client once and reuse
        httpClient = createHttpClient();

        future = worker.scheduleWithFixedDelay(
            this::pollAndSend,
            0,
            interval,
            TimeUnit.MILLISECONDS
        );
    }

    @Override
    public void stop() {
        logger.info("Stopping ADS-B Plugin");
        logger.info("  Total poll cycles: {}", totalPollCycles.get());
        logger.info("  Total messages sent: {}", totalMessagesSent.get());

        if (future != null) {
            future.cancel(true);
        }
        worker.shutdown();

        if (httpClient != null) {
            try {
                httpClient.close();
            } catch (Exception e) {
                logger.debug("Error closing HTTP client", e);
            }
        }
    }

    /**
     * Main polling loop - fetches aircraft data and sends CoT messages.
     */
    private void pollAndSend() {
        int cycle = totalPollCycles.incrementAndGet();
        logger.debug("Poll cycle #{}", cycle);

        try {
            String url = String.format("%s/point/%.6f/%.6f/%d",
                API_BASE, latitude, longitude, radius);

            HttpGet request = new HttpGet(url);
            logger.debug("Fetching: {}", url);

            httpClient.execute(request, response -> {
                int statusCode = response.getCode();
                if (statusCode == 200) {
                    String body = EntityUtils.toString(response.getEntity());
                    logger.debug("API response length: {} bytes", body.length());
                    List<Aircraft> aircraft = parseAircraft(body);
                    logger.debug("Parsed {} aircraft with positions", aircraft.size());
                    sendAircraft(aircraft);
                } else if (statusCode == 429) {
                    logger.warn("Rate limited by airplanes.live API (429). Consider increasing interval.");
                } else {
                    logger.warn("API returned status {} - body: {}", statusCode,
                        EntityUtils.toString(response.getEntity()));
                }
                return null;
            });

        } catch (Exception e) {
            logger.error("Error in poll cycle: {}", e.getMessage());
            logger.debug("Full error", e);
        }
    }

    /**
     * Parse JSON response from airplanes.live API into Aircraft objects.
     */
    private List<Aircraft> parseAircraft(String json) {
        List<Aircraft> aircraft = new ArrayList<>();

        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray ac = root.getAsJsonArray("ac");

            if (ac != null) {
                for (JsonElement element : ac) {
                    try {
                        Aircraft plane = Aircraft.fromJson(element.getAsJsonObject());
                        if (plane.hasPosition()) {
                            aircraft.add(plane);
                        }
                    } catch (Exception e) {
                        logger.debug("Failed to parse aircraft: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to parse API response", e);
        }

        return aircraft;
    }

    /**
     * Convert aircraft to CoT messages and send to TAK Server.
     */
    private void sendAircraft(List<Aircraft> aircraft) {
        int sent = 0;
        int military = 0;

        for (Aircraft plane : aircraft) {
            try {
                String cotXml = plane.toCotXml(staleTimeSec);
                if (cotXml != null) {
                    logger.info("Sending aircraft: hex={} flight={}",
                        plane.getHex(),
                        plane.getFlight());
                    logger.debug("Sending aircraft: hex={} flight={} lat={} lon={} alt={} mil={}",
                        plane.getHex(),
                        plane.getFlight(),
                        plane.getLat(),
                        plane.getLon(),
                        plane.getAltitude(),
                        plane.isMilitary());

                    if (logger.isTraceEnabled()) {
                        logger.trace("CoT XML: {}", cotXml);
                    }

                    Message message = getConverter().cotStringToDataMessage(cotXml, groups, pluginId);
                    send(message);
                    sent++;
                    if (plane.isMilitary()) {
                        military++;
                    }
                }
            } catch (Exception e) {
                logger.debug("Failed to send aircraft {}: {}", plane.getHex(), e.getMessage());
            }
        }

        totalMessagesSent.addAndGet(sent);
        logger.info("Sent {} aircraft ({} military) to TAK Server", sent, military);
    }

    private CloseableHttpClient createHttpClient() {
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectionRequestTimeout(Timeout.ofSeconds(15))
            .setResponseTimeout(Timeout.ofSeconds(15))
            .build();

        var builder = HttpClients.custom()
            .setDefaultRequestConfig(requestConfig);

        // Configure proxy if found in environment
        if (proxy != null) {
            builder.setProxy(proxy);
            logger.info("HTTP client using proxy: {}:{}", proxy.getHostName(), proxy.getPort());
        }

        return builder.build();
    }

    /**
     * Read proxy configuration from environment variables.
     * Checks HTTPS_PROXY, https_proxy, HTTP_PROXY, http_proxy in that order.
     */
    private HttpHost getProxyFromEnvironment() {
        // Check for proxy environment variables (same ones curl uses)
        String[] proxyVars = {"HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy"};

        for (String varName : proxyVars) {
            String proxyUrl = System.getenv(varName);
            if (proxyUrl != null && !proxyUrl.isEmpty()) {
                try {
                    URI uri = new URI(proxyUrl);
                    String host = uri.getHost();
                    int port = uri.getPort();

                    if (host == null) {
                        // Handle case like "proxy:8080" without scheme
                        String[] parts = proxyUrl.replace("http://", "").replace("https://", "").split(":");
                        host = parts[0];
                        port = parts.length > 1 ? Integer.parseInt(parts[1]) : 8080;
                    }

                    if (port == -1) {
                        port = 8080; // Default proxy port
                    }

                    logger.debug("Found proxy from {}: {}:{}", varName, host, port);
                    return new HttpHost(host, port);
                } catch (Exception e) {
                    logger.warn("Failed to parse proxy URL from {}: {}", varName, e.getMessage());
                }
            }
        }

        return null;
    }
}
