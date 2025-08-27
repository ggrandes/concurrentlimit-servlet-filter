package org.javastack.servlet.filters;

import java.io.IOException;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public final class ConcurrentLimitFilter implements Filter {
	private static final int DEFAULT_IPMAXSIZE = 8192;
	private static final Long NOT_SEEN = Long.valueOf(0);
	private FilterConfig cfg = null;
	private ServletContext ctx = null;
	private TreeMap<String, Limit> limits = null;

	@Override
	public void init(final FilterConfig filterConfig) throws ServletException {
		this.cfg = filterConfig;
		this.ctx = filterConfig.getServletContext();
		final TreeMap<String, Limit> limits = new TreeMap<String, Limit>();
		final Enumeration<String> e = filterConfig.getInitParameterNames();
		final int ipMaxSize = getIntConfigParam("ipMaxSize", DEFAULT_IPMAXSIZE);
		while (e.hasMoreElements()) {
			final String key = e.nextElement();
			if (!key.isEmpty() && (key.charAt(0) == '/')) {
				final String uri = key;
				final String value = filterConfig.getInitParameter(key);
				final String[] conf = value.split(":"); // 0=concurrent-limit, 1=ip-time-limit-millis
				final int concurrent = (conf.length > 0 ? Integer.parseInt(conf[0]) : 0);
				final long timeByIP = (conf.length > 1 ? Long.parseLong(conf[1]) : 0);
				final Limit limit = new Limit(timeByIP, ipMaxSize, concurrent);
				filterConfig.getServletContext().log("uri=" + uri + " limit=[" + limit + "]");
				limits.put(uri, limit);
			}
		}
		this.limits = limits;
	}

	private int getIntConfigParam(final String param, final int defaultValue) {
		final String str = cfg.getInitParameter(param);
		Exception ex = null;
		if ((str != null) && !str.isEmpty()) {
			try {
				return Integer.parseInt(str);
			} catch (Exception e) {
				ex = e;
			}
		}
		ctx.log("Using default value: <" + defaultValue + ">" //
				+ " for <" + param + ">" //
				+ " input: <" + str + ">"//
				+ (ex != null ? " exception: " + ex : ""));
		return defaultValue;
	}

	@Override
	public void doFilter(final ServletRequest request, final ServletResponse response,
			final FilterChain chain) throws IOException, ServletException {
		if (request instanceof HttpServletRequest) {
			final HttpServletRequest req = (HttpServletRequest) request;
			final String uriReq = req.getRequestURI();
			final String ip = req.getRemoteAddr();
			final Entry<String, Limit> e = limits.floorEntry(uriReq);
			final boolean found = ((e != null) && uriReq.startsWith(e.getKey()));
			final Limit limit = (e != null ? e.getValue() : null);
			boolean recent = false, lock = false;
			try {
				if (!found || (!(recent = limit.seenRecent(ip)) && (lock = limit.tryAcquire()))) {
					chain.doFilter(request, response);
					try {
						Thread.sleep(10);
					} catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
				} else {
					final StringBuilder sb = new StringBuilder();
					sb.append(getClass().getSimpleName());
					sb.append(" cfg=").append(e.getKey());
					sb.append(" uriReq=").append(uriReq);
					if (recent) {
						sb.append(" Lock-by-IP=").append(ip) //
								.append(" time=").append(limit.timeByIP).append("ms");
					} else if (!lock) {
						sb.append(" Lock-by-Concurrent=").append(limit.concurrentMax);
					}
					ctx.log(sb.toString());
					if (!response.isCommitted()) {
						handleError(req, (HttpServletResponse) response);
					}
				}
			} finally {
				if ((limit != null) && lock) {
					limit.release();
				}
			}
		} else {
			chain.doFilter(request, response);
		}
	}

	private void handleError(final HttpServletRequest request, final HttpServletResponse response)
			throws IOException {
		// handle limit case, e.g. return status code 429 (Too Many Requests)
		// see http://tools.ietf.org/html/rfc6585#page-3
		final String text = "TOO_MANY_REQUESTS";
		response.reset();
		response.setStatus(429);
		response.setContentType("text/plain");
		response.setCharacterEncoding("US-ASCII");
		response.setContentLength(text.length() + 2);
		response.setHeader("Pragma", "no-cache");
		response.setHeader("Cache-Control", "private, no-cache, no-store, must-revalidate");
		final ServletOutputStream out = response.getOutputStream();
		out.println(text);
		out.flush();
		response.flushBuffer();
	}

	@Override
	public void destroy() {
	}

	private static final class Limit {
		public final long timeByIP;
		public final int ipMaxSize;
		public final int concurrentMax;
		private final Semaphore concurrent;
		private final LinkedHashMap<String, Long> seenIP;

		Limit(final long timeByIP, final int ipMaxSize, final int concurrentMax) {
			this.timeByIP = Math.max(timeByIP, 0);
			this.ipMaxSize = Math.max(ipMaxSize, 0);
			this.concurrentMax = Math.max(concurrentMax, 0);
			this.concurrent = (concurrentMax <= 0 ? null : new Semaphore(concurrentMax));
			this.seenIP = (((timeByIP <= 0) || (ipMaxSize <= 0)) ? null : new LinkedHashMap<String, Long>() {
				private static final long serialVersionUID = 42L;

				@Override
				protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> eldest) {
					return (size() > ipMaxSize);
				}
			});
		}

		public boolean tryAcquire() {
			if (concurrent == null) {
				return true;
			}
			return concurrent.tryAcquire();
		}

		public void release() {
			if (concurrent == null) {
				return;
			}
			concurrent.release();
		}

		public boolean seenRecent(final String ip) {
			if (seenIP == null) {
				return false;
			}
			final Long now = Long.valueOf(System.currentTimeMillis());
			synchronized (seenIP) {
				Long lastSeen = seenIP.get(ip);
				if (lastSeen == null) {
					lastSeen = NOT_SEEN;
				}
				final boolean seen = ((lastSeen.longValue() + timeByIP) > now.longValue());
				if (!seen) {
					seenIP.put(ip, now);
				}
				return seen;
			}
		}

		public String toString() {
			return "timeByIP=" + timeByIP + " ipMaxSize=" + ipMaxSize + " concurrentMax=" + concurrentMax;
		}
	}
}