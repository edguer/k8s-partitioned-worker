package com.sample;

import java.net.InetAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Entrypoint for the application.
 */
public class App {
    public static void main(String[] args) throws Exception {
        Logger.getGlobal().info("Initializing worker...");

        /**
         * Gets the hostname and tries to extract the partition number. Kubernetes
         * appends the StatefulSet pod name at the and, with a dash before it. Example:
         * myapp-0, myapp-1 and so on.
         */
        // If environment variable PARTITION_HOSTNAME is set then use that as the HOSTNAME (helps with local testing)
        String partitionHostName = System.getenv("PARTITION_HOSTNAME");
        String hostName = InetAddress.getLocalHost().getHostName();
        if (partitionHostName != null) {
            hostName = partitionHostName;
        }
        String[] hostNameFragments = hostName.split("-");
        if (hostNameFragments.length <= 1) {
            Logger.getGlobal().severe("Could not infer partition from host name.");
            System.exit(-1);
        }

        // Redis hostname comes from the first and only argument
        if (args.length != 1) {
            Logger.getGlobal().severe("Worker only accepts one arguments: Redis cluster hostname.");
            System.exit(-1);
        }

        /**
         * Calculates primary and secondary partitions. The primary partition is the one
         * the worker is primarily concerned, so the it will lock and stick with it by
         * continuously renewing the lock. The second partition, on the other hand,
         * works as a backup for a given partition, so it will only get the lock it is
         * released by the primary worker of that partition. We should alway release the
         * lock of the second partition, so when the primary worker for that partition
         * comes back online, it can take the lock as soon as possible.
         * 
         * The primary partition is extracted directly from the host name + 1 (if worker
         * is called "worker-0" the corresponding partition will be "1"), and the second
         * will always be "primary + 1", if primary is odd, or "primary - 1" if primary
         * is even. That will give the following result:
         */

        // worker/primary_partition/secondary_partition:
        // worker-0/1/2
        // worker-1/2/1
        // worker-2/3/4
        // worker-3/4/3

        int primaryPartition = Integer.parseInt(hostNameFragments[hostNameFragments.length - 1]) + 1;
        int secondaryPartition = primaryPartition % 2 == 0 ? primaryPartition - 1 : primaryPartition + 1;
        Logger.getGlobal().log(Level.INFO, "Primary partition ID is {0}", primaryPartition);
        Logger.getGlobal().log(Level.INFO, "Secondary partition ID is {0}", secondaryPartition);

        /**
         * Creates the thread pool, the JedisPool, the hardcoded config values and
         * kicks-off the scheduler
         */
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(128);
        JedisPool connectionPool = new JedisPool(poolConfig, args[0]);

        int lockTTL = 5;
        int lockCheckTime = 100;
        int lockRenewTime = 1000;
        int lockAttemptTimeout = 30000;

        int delayBetweenRuns = 5000;

        executor.scheduleWithFixedDelay(new WorkerProcess(primaryPartition, true, hostName, connectionPool, lockTTL,
                lockCheckTime, lockRenewTime, lockAttemptTimeout), 0, delayBetweenRuns, TimeUnit.MILLISECONDS);

        executor.scheduleWithFixedDelay(new WorkerProcess(secondaryPartition, false, hostName, connectionPool, lockTTL,
                lockCheckTime, lockRenewTime, lockAttemptTimeout), 0, delayBetweenRuns, TimeUnit.MILLISECONDS);

    }
}
