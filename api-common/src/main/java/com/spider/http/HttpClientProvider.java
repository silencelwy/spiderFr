/**
 *
 */
package com.spider.http;

import com.spider.http.InitializedException;
import com.spider.bean.ParserType;
import com.spider.core.ThreadLocalRandom;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.impl.client.DefaultHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author Wayne.Wang<5waynewang@gmail.com>
 * @since 11:46:09 AM Jul 19, 2014
 */
public class HttpClientProvider {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private IpPools ipPools;
    private List<String> userAgents;

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
    private String filePath;
    private long lastModified = -1;
    private CookieStoreProvider cookieStoreProvider;//单例

    public void setCookieStoreProvider(CookieStoreProvider cookieStoreProvider) {
        this.cookieStoreProvider = cookieStoreProvider;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    String getFilePath() {
        return filePath;
    }

    List<String> loadFromConfig() {
        final File file = new File(this.getFilePath());
        if (file.lastModified() <= lastModified) {
            if (log.isDebugEnabled()) {
                log.debug("{} has not changed", this.getFilePath());
            }
            return null;
        }

        try {
            return FileUtils.readLines(file);
        } catch (IOException e) {
            log.error("Error to read from " + file.getAbsolutePath(), e);
            return null;
        }
    }

    // must call this method once for initializing
    public HttpClientProvider init() {
        if (log.isInfoEnabled()) {
            log.info("start to init " + this.getClass().getSimpleName());
        }

        if (StringUtils.isBlank(this.filePath)) {
            final URL url = this.getClass().getResource("/http/user-agent.txt");
            if (url == null) {
                throw new InitializedException("can not find user-agent.txt");
            }

            this.filePath = url.getFile();
        }

        // 首次初始化
        this.userAgents = this.loadFromConfig();
        if (this.userAgents == null || this.userAgents.isEmpty()) {
            throw new InitializedException("Nothing to find from " + this.filePath);
        }

        this.exec.scheduleWithFixedDelay(new Runnable() {

            @Override
            public void run() {
                final List<String> userAgents = HttpClientProvider.this.loadFromConfig();
                if (userAgents != null && !userAgents.isEmpty()) {
                    synchronized (HttpClientProvider.this) {
                        HttpClientProvider.this.userAgents = userAgents;
                    }
                }
            }
        }, 15, 15, TimeUnit.SECONDS);

        if (log.isInfoEnabled()) {
            log.info("success to init " + this.getClass().getSimpleName());
        }
        return this;
    }

    // must call this method once for destroying
    public void destroy() {
        if (log.isInfoEnabled()) {
            log.info("start to destroy " + this.getClass().getSimpleName());
        }

        this.exec.shutdown();

        if (log.isInfoEnabled()) {
            log.info("success to destroy " + this.getClass().getSimpleName());
        }
    }

    public IpPools getIpPools() {
        return ipPools;
    }

    public void setIpPools(IpPools ipPools) {
        this.ipPools = ipPools;
    }

    public HttpClientInvoker provide(final String url, final HttpClientInvoker refer) {
        final String ip = refer.getIp();
        IpCookiePair ipCookiePair = new IpCookiePair(ip, null, null);
        return this.provide(refer.getParserType(), url, refer.getUrl(), ipCookiePair, refer.getUserAgent());
    }

    public HttpClientInvoker provide(final ParserType parserType, final String url) {
        return this.provide(parserType, url, null);
    }

    public HttpClientInvoker provide(final ParserType parserType, final String url, final String refer) {
        final String ip = this.ipPools.getAvaliableIp(parserType);
        IpCookiePair ipCookiePair = new IpCookiePair(ip, null, null);
        return this.provide(parserType, url, refer, ipCookiePair);
    }

    public HttpClientInvoker provide(final ParserType parserType, final String url, final String refer, final IpCookiePair ipCookiePair) {
        final String userAgent = this.getUserAgent();

        return this.provide(parserType, url, refer, ipCookiePair, userAgent);
    }

    public HttpClientInvoker provide(final ParserType parserType, final String url, final String refer, final IpCookiePair ipCookiePair, final String userAgent) {

        return this.provide(parserType, url, refer, ipCookiePair, userAgent, new DefaultHttpClient());
    }

    public HttpClientInvoker provideSSL(final ParserType parserType, final String url, final String refer, final IpCookiePair ipCookiePair) {
        final String userAgent = this.getUserAgent();

        return this.provideSSL(parserType, url, refer, ipCookiePair, userAgent);
    }

    public HttpClientInvoker provideSSL(final ParserType parserType, final String url, final String refer, final IpCookiePair ipCookiePair, final String userAgent) {

//		return this.provide(parserType, url, refer, ip, userAgent, new SSLClient());
        return this.provide(parserType, url, refer, ipCookiePair, userAgent, null);
    }

    public HttpClientInvoker provide(final ParserType parserType, final String url, final String refer, final IpCookiePair ipCookiePair, final String userAgent, final DefaultHttpClient httpclient) {

        final HttpClientHolder httpClient;

        if (httpclient != null) {
            httpClient = new PoolingHttpClientHolder(cookieStoreProvider ,ipCookiePair, userAgent, refer, httpclient);
        } else {
            httpClient = new PoolingHttpClientHolder(cookieStoreProvider ,ipCookiePair, userAgent, refer);
        }

        final HttpClientInvoker invoker = new HttpClientInvoker();
        invoker.setProvider(this);
        invoker.setHttpClient(httpClient);
        invoker.setParserType(parserType);
        invoker.setUrl(url);
        invoker.setRefer(refer);
        invoker.setCookie_key(ipCookiePair.getCookie_key());
        invoker.setIp(ipCookiePair.getIp());
        invoker.setUserAgent(userAgent);
        invoker.setCookieStoreProvider(this.cookieStoreProvider);

        return invoker;
    }

    String getUserAgent() {
        synchronized (this) {
            return this.userAgents.get(ThreadLocalRandom.current().nextInt(this.userAgents.size()));
        }
    }

    /**
     * <pre>
     * 做流控规则限制
     * </pre>
     *
     * @param invoker
     */
    public void disable(HttpClientInvoker invoker) {
        // 目前仅禁用ip
        this.ipPools.disableIp(invoker.getParserType(), invoker.getIp());
    }
}
