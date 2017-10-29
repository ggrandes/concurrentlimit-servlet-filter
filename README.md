# Concurrent Limit Servlet Filter

Provides concurrent control in a Servlet container like Tomcat. Open Source Java project under Apache License v2.0

### Current Development Version is [1.0.0](https://search.maven.org/#search|ga|1|g%3Aorg.javastack%20a%3Aconcurrentlimit-servlet-filter)

---

## DOC

This filter works limiting access for specified URI.

1. Check if the IP has been seen in the last X milliseconds.
2. If not seen, try to acquire access (concurrent) for resource.
3. If any of this checks fail, HTTP code 429 (Too Many Requests) is returned to the client as described in [RFC-6585](http://tools.ietf.org/html/rfc6585#page-3).

#### Usage Example

```xml
<!-- Servlet Filter -->
<!-- WEB-INF/web.xml -->
<filter>
    <filter-name>ConcurrentLimitFilter</filter-name>
    <filter-class>org.javastack.servlet.filters.ConcurrentLimitFilter</filter-class>
    <!-- Example 5 concurrent updates, same IP allowed one time every 5 seconds -->
    <init-param>
        <param-name>/rest/update</param-name>
        <!-- concurrent-limit[:ip-time-limit-millis] -->
        <param-value>5:5000</param-value>
    </init-param>
    <!-- Example one delete per second from same IP -->
    <init-param>
        <param-name>/rest/delete</param-name>
        <param-value>0:1000</param-value>
    </init-param>
    <!-- How many IPs are remembered for each URI, default: 8192 -->
    <init-param>
        <param-name>ipMaxSize</param-name>
        <param-value>4096</param-value>
    </init-param>
</filter>
<filter-mapping>
    <filter-name>ConcurrentLimitFilter</filter-name>
    <url-pattern>/rest/*</url-pattern>
</filter-mapping>
```

---

## MAVEN

Add the dependency to your pom.xml:

    <dependency>
        <groupId>org.javastack</groupId>
        <artifactId>concurrentlimit-servlet-filter</artifactId>
        <version>1.0.0</version>
    </dependency>

---
Inspired in [mod_ratelimit](https://httpd.apache.org/docs/2.4/es/mod/mod_ratelimit.html), this code is Java-minimalistic version.
