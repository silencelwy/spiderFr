/**
 * 
 */
package com.spider.http;

import com.alibaba.fastjson.JSON;
import com.spider.http.Config;
import com.spider.http.InitializedException;
import com.spider.bean.ParserType;
import com.spider.core.ThreadLocalRandom;
import com.spider.utils.EmailUtils;
import com.spider.utils.LocalUtils;
import com.spider.utils.Logs;
import com.spider.utils.TelnetUtils;
import net.rubyeye.xmemcached.MemcachedClient;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.ini4j.Ini;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Wayne.Wang<5waynewang@gmail.com>
 * @since 11:29:05 AM Jul 19, 2014
 */
public class IpPools {

	private final Logger log = LoggerFactory.getLogger(getClass());

	static final String DISABLE_IP_KEY_FORMAT = "DisableIp_%s";

	private List<String> selfIps;
	private List<String> thirdsIps;
	private List<String> avaliableIps;
	private AtomicInteger index = new AtomicInteger(0);
	private AtomicInteger selfIndex = new AtomicInteger(0);

	private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(2);

	private final int checkCorePoolSize = Runtime.getRuntime().availableProcessors() * 2;
	private final ScheduledExecutorService checkExec = Executors.newScheduledThreadPool(checkCorePoolSize);

	private String filePath;
	private long lastModified = -1;

	private MemcachedClient memcachedSpiderClient;
	
	public void setMemcachedSpiderClient(MemcachedClient memcachedSpiderClient) {
		this.memcachedSpiderClient = memcachedSpiderClient;
	}

	public void setFilePath(String filePath) {
		this.filePath = filePath;
	}

	String getFilePath() {
		return filePath;
	}

	/**
	 * <pre>
	 * 本地加载配置
	 * </pre>
	 */
	boolean loadFromConfig(boolean checked) {
		final File file = new File(this.getFilePath());
		if (file.lastModified() <= lastModified) {
			return false;
		}

		Ini conf;
		try {
			conf = new Ini(file);
		} catch (Exception e) {
			throw new InitializedException("can not create Ini4j by File:" + file.getAbsolutePath(), e);
		}

		final List<String> selfIpList = new ArrayList<String>();
		for (String address : conf.get("self").keySet()) {
			selfIpList.add(StringUtils.replaceChars(address, '@', ':'));
		}

		final List<String> thirdsIpList = new ArrayList<String>();
		for (String address : conf.get("thirds").keySet()) {
			thirdsIpList.add(StringUtils.replaceChars(address, '@', ':'));
		}

		final List<String> avaliableIpList;
		if (checked) {
			avaliableIpList = checkAndGetAvaliableIps(selfIpList, thirdsIpList);
		} else {
			avaliableIpList = mergeList(selfIpList, thirdsIpList);
		}

		this.lastModified = file.lastModified();
		this.selfIps = selfIpList;
		this.thirdsIps = thirdsIpList;
		this.avaliableIps = avaliableIpList;

		return true;
	}

	// 顺序打乱
	List<String> mergeList(List<String> selfIpList, List<String> thirdsIpList) {
		final int selfIpSize = selfIpList.size();
		final int thirdsIpSize = thirdsIpList.size();

		final List<String> maxList, minList;
		if (selfIpSize > thirdsIpSize) {
			maxList = selfIpList;
			minList = thirdsIpList;
		} else {
			maxList = thirdsIpList;
			minList = selfIpList;
		}
		final int step = maxList.size() / minList.size();
		int minIndex = 0;
		final List<String> ips = new ArrayList<String>(selfIpSize + thirdsIpSize);
		for (int i = 0; i < maxList.size(); i++) {
			if (i % step == 0 && minIndex < minList.size()) {
				ips.add(minList.get(minIndex++));
			}
			ips.add(maxList.get(i));
		}
		return ips;
	}

	// must call this method once for initializing
	public IpPools init() {
		if (log.isInfoEnabled()) {
			log.info("start to init " + this.getClass().getSimpleName());
		}

		if (StringUtils.isBlank(this.filePath)) {
			final URL url = this.getClass().getResource("/http/http.inf");
			if (url == null) {
				throw new InitializedException("can not find ip.inf");
			}

			this.filePath = url.getFile();
		}

		this.loadFromConfig(false);

		if (log.isInfoEnabled()) {
			log.info("Success to load self ips:\n{},\nthirds ips:\n{}", JSON.toJSONString(this.selfIps, true),
					JSON.toJSONString(this.thirdsIps, true));
		}

		// 定期加载并清洗ip
//		this.exec.scheduleWithFixedDelay(new Runnable() {
//
//			@Override
//			public void run() {
//				try {
//					final boolean reload = loadFromConfig(true);
//
//					if (!reload) {
//						final List<String> selfIpList = selfIps;
//						final List<String> thirdsIpList = thirdsIps;
//						avaliableIps = checkAndGetAvaliableIps(selfIpList, thirdsIpList);
//
//						if (log.isInfoEnabled()) {
//							log.info("Success to check avaliable ips:\n{}", JSON.toJSONString(avaliableIps, true));
//						}
//					} else if (log.isInfoEnabled()) {
//						log.info("Success to load self ips:\n{},\nthirds ips:\n{}", JSON.toJSONString(selfIps, true),
//								JSON.toJSONString(thirdsIps, true));
//					}
//				} catch (Exception e) {
//					log.error("Error to reload from File:" + filePath, e);
//				}
//			}
//		}, 50, 120, TimeUnit.SECONDS);

		// 定期检查可用ip并预警
//		this.exec.scheduleWithFixedDelay(new Runnable() {
//
//			@Override
//			public void run() {
//				try {
//					notifyIfWarning();
//				} catch (Exception e) {
//					log.error("Error to call notifyIfWarning()", e);
//				}
//			}
//		}, 30, 300, TimeUnit.SECONDS);

		if (log.isInfoEnabled()) {
			log.info("success to init " + this.getClass().getSimpleName());
		}
		return this;
	}

	// 有限ip阀值，小于此阀值则预警
	private int avaliableIpThreshold = 5;

	public void setAvaliableIpThreshold(int avaliableIpThreshold) {
		this.avaliableIpThreshold = avaliableIpThreshold;
	}

	int getAvaliableIpThreshold() {
		return avaliableIpThreshold;
	}

	// 预警
	void notifyIfWarning() {
		final List<String> avaliableIpList = this.avaliableIps;
		if (avaliableIpList.size() > getAvaliableIpThreshold()) {
			return;
		}
		final StringBuilder warning = new StringBuilder();
		for (String avaliableIp : avaliableIpList) {
			warning.append("\t").append(avaliableIp).append("\r\n");
		}
		if (warning.length() > 0) {
			final StringBuilder msg = new StringBuilder();
			msg.append("当前机器：\r\n").append("\t").append(LocalUtils.LOCAL_IP).append("\r\n");
			msg.append("可用代理ip：\r\n").append(warning);
			EmailUtils.sendQuietly("爬虫的有效代理ip已不多", msg.toString());
		}
	}

	List<String> checkAndGetAvaliableIps(List<String> selfIpList, List<String> thirdsIpList) {
		final long startTimeMillis = System.currentTimeMillis();
		final List<String> ips = mergeList(selfIpList, thirdsIpList);

		final int total = ips.size();
		final int initialCapacity = (int) (total * 0.3);
		final List<String> avaliableIpList = new ArrayList<String>(initialCapacity);
		final CountDownLatch cdl = new CountDownLatch(total);
		final int avg = total / checkCorePoolSize;
		for (int i = 0; i < checkCorePoolSize; i++) {
			final int bgn = avg * i;
			final int end = avg * (i + 1);
			this.checkExec.submit(new Runnable() {
				@Override
				public void run() {
					for (int index = bgn; index < end; index++) {
						try {
							final String ip = ips.get(index);
							if (isAvaliableIp(ip)) {
								avaliableIpList.add(ip);
							}
						} catch (Exception ignore) {
						} finally {
							cdl.countDown();
						}
					}
				}
			});
		}

		final int end = avg * checkCorePoolSize;
		if (total > end) {
			for (int index = end; index < total; index++) {
				try {
					final String ip = ips.get(index);
					if (isAvaliableIp(ip)) {
						avaliableIpList.add(ip);
					}
				} catch (Exception ignore) {
				} finally {
					cdl.countDown();
				}
			}
		}

		log.warn("start to check avaliable ip");

		try {
			cdl.await();
		} catch (InterruptedException ignore) {
		}

		log.warn("success to check avaliable ip, cost {} ms", System.currentTimeMillis() - startTimeMillis);

		return avaliableIpList;
	}

	boolean isAvaliableIp(String ip) {
		if (isDisableIp(ip)) {
			log.warn("{} is disabled", ip);
		} else if (!HttpStatics.isLocalIp(ip) && !TelnetUtils.telnetSuccessfully(ip)) {
			// dev环境不打印
			//log.warn("Failed to telnet {}", ip);
		} else {
			return true;
		}
		return false;
	}

	String getDisableIpKey(String ip) {
		return String.format(DISABLE_IP_KEY_FORMAT, ip);
	}

	boolean isDisableIp(String ip) {
		final String key = this.getDisableIpKey(ip);
		try {
			final Object value = this.memcachedSpiderClient.get(key, 5000);

			return value != null;
		} catch (Exception e) {
			Logs.unpredictableLogger.error("Error to get by " + key, e);
			return false;
		}
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

	/**
	 * 获取一个有效的 ip
	 * 
	 * @param parserType
	 * @return
	 */
	public String getAvaliableIp(ParserType parserType) {
		// parserType 暂时不用
		final List<String> avaliableIpList = this.avaliableIps;

		if (CollectionUtils.isEmpty(avaliableIpList)) {
			log.warn("there is no avaliable ip of {}", parserType);
			return null;
		}

		int idx = index.getAndIncrement();
		if (idx >= avaliableIpList.size()) {
			idx = 0;
			index.set(0);
		}
		return avaliableIpList.get(idx);
	}

	/**
	 * 顺序获取一个有效的 ip
	 * 
	 * @param parserType
	 * @return
	 */
	public String pollingSafeAvaliableIp(ParserType parserType) {
		// TODO parserType 暂时不用
		final List<String> selfIpList = this.selfIps;
		int idx = selfIndex.getAndIncrement();
		if (idx >= selfIpList.size()) {
			idx = 0;
			selfIndex.set(0);
		}
		return selfIpList.get(idx);
	}

	/**
	 * 随机获取一个有效的ip
	 * 
	 * @param parserType
	 * @return
	 */
	public String randomSafeAvaliableIp(ParserType parserType) {
		// TODO parserType 暂时不用
		final List<String> selfIpList = this.selfIps;
		return selfIpList.get(ThreadLocalRandom.current().nextInt(selfIpList.size()));
	}

	/**
	 * <pre>
	 * 是否存在有效的ip可用
	 * </pre>
	 * 
	 * @param parserType
	 * @return
	 */
	public boolean hasAvaliableIp(ParserType parserType) {
		// TODO parserType 暂时不用
		final List<String> avaliableIpList = this.avaliableIps;

		if (CollectionUtils.isNotEmpty(avaliableIpList)) {
			return true;
		} else {
			log.warn("there is no avaliable ip of {}", parserType);
			return false;
		}
	}

	/**
	 * <pre>
	 * 失效此 ip
	 * </pre>
	 * 
	 * @param parserType
	 * @param ip
	 */
	public void disableIp(ParserType parserType, String ip) {
		// TODO parserType 暂时不用
		final List<String> avaliableIpList = this.avaliableIps;

		avaliableIpList.remove(ip);

		final String key = this.getDisableIpKey(ip);

		log.warn("will disable {}", ip);

		try {
			this.memcachedSpiderClient.incr(key, 1, 1, 5000, getDisableIpExp());
		} catch (Exception e) {
			Logs.unpredictableLogger.error("Error to incr by " + key, e);
		}
	}

	int getDisableIpExp() {
		return (int) Config.instance().getVpsTimeout() / 1000;
	}
}
