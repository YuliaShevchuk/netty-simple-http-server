package com.hc.http;

import java.util.Date;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Helper class to work with statistics
* */
public final class Statistic {
    private static final Statistic INSTANCE = new Statistic();

    private final static AtomicInteger requestsCount = new AtomicInteger(0);
    private final static AtomicInteger openConnections = new AtomicInteger(0);
    private final static Map<String, Integer> redirectsMap = new ConcurrentHashMap<>();
    private final static Map<String, RequestInfo> uniqueRequestsMap = new ConcurrentHashMap<>();
    private static final int LOG_SIZE = 16;
    private final static Queue<LogRecord> logRecords = new ConcurrentLinkedQueue<>();


    private Statistic() {
    }

    public static Statistic getInstance() {
        return INSTANCE;
    }


    public void addRequest(String ip) {
        requestsCount.incrementAndGet();
        synchronized (uniqueRequestsMap) {
            RequestInfo requestInfo = uniqueRequestsMap.get(ip);
            if (requestInfo == null) {
                // add new request info
                requestInfo = new RequestInfo(new Date());
                uniqueRequestsMap.put(ip, requestInfo);
            } else {
                // update existing request info
                requestInfo.incrementCount();
                requestInfo.setTime(new Date());
            }
        }
    }

    public int getRequestCount() {
        return requestsCount.get();
    }

    public void incrementOpenConnection() {
        openConnections.incrementAndGet();
    }

    public void decrementOpenConnection() {
        openConnections.decrementAndGet();
    }

    public int getOpenConnections() {
        return openConnections.get();
    }

    public void addRedirectUri(String uri) {
        synchronized (redirectsMap) {
            if (redirectsMap.containsKey(uri)) {
                redirectsMap.put(uri, redirectsMap.get(uri) + 1);
            } else {
                redirectsMap.put(uri, 1);
            }
        }
    }

    public Map<String, Integer> getRedirectMap() {
        return redirectsMap;
    }

    public void addLogRecord(LogRecord logRecord) {
        synchronized (logRecords) {
            if (logRecords.size() == LOG_SIZE) {
                // delete eldest log record
                logRecords.poll();
            }
            logRecords.add(logRecord);
        }
    }

    public Queue<LogRecord> getLogRecords() {
        return logRecords;
    }

    public Map<String, RequestInfo> getUniqueRequestsMap() {
        return uniqueRequestsMap;
    }

    public int getUniqueRequestsCount() {
        return uniqueRequestsMap.size();
    }


    /**
     * Contains info about unique requests
     * */
    public static final class RequestInfo {
        private AtomicLong count;
        private Date time;

        public RequestInfo(Date time) {
            this.count = new AtomicLong(1);
            this.time = time;
        }

        public void incrementCount() {
            count.incrementAndGet();
        }

        public Date getTime() {
            return time;
        }

        public long getCount() {
            return count.get();
        }

        public void setTime(Date time) {
            this.time = time;
        }
    }

    /**
     * Contains info about processed connection
     * */
    public static final class LogRecord {
        private String ip;
        private String uri;
        private Date ts;
        private long sentBytes;
        private long receivedBytes;
        private double speed;

        public LogRecord(String ip, String uri, Date ts, long sentBytes, long receivedBytes, double speed) {
            this.ip = ip;
            this.uri = uri;
            this.ts = ts;
            this.sentBytes = sentBytes;
            this.receivedBytes = receivedBytes;
            this.speed = speed;
        }

        public String getIp() {
            return ip;
        }

        public String getUri() {
            return uri;
        }

        public Date getTs() {
            return ts;
        }

        public long getSentBytes() {
            return sentBytes;
        }

        public long getReceivedBytes() {
            return receivedBytes;
        }

        public double getSpeed() {
            return speed;
        }
    }
}
