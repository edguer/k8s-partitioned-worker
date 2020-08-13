package com.sample;

import java.time.Instant;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * TimeTask for processing items.
 */
public class WorkerProcess extends java.util.TimerTask {
    int partition;
    int lockTTL;
    int lockCheckTime;
    int lockRenewTime;
    int lockAttemptTimeout;
    String workerName;
    String sortedSetName;
    boolean primaryPartition;
    JedisPool connectionPool;

    /**
     * Initializes the worker process time task.
     * 
     * @param partition          partition number
     * @param primaryPartition   true if it should process the partition as the
     *                           worker's primary partition
     * @param workerName         name of the worker
     * @param connectionPool     Jedis connection pool
     * @param lockTTL            locker time-to-live in seconds
     * @param lockCheckTime      time in milliseconds to wait between lock attempts.
     *                           Example: if lock is currently locked, the
     *                           "lockAttemptTimeout" is 10,000 and the
     *                           "lockCheckTime" is 1,000, it will try to acquire
     *                           the lock 10 times
     * @param lockRenewTime      time in milliseconds between lock renewals, only
     *                           applies when it is a primary partition. A
     *                           background thread will run each "lockRenewTime"
     *                           milliseconds to renew the lock. This value should
     *                           always be lower than the "lockTTL", otherwise the
     *                           lock will be released before renewing it.
     * @param lockAttemptTimeout time in milliseconds to wait before giving up
     *                           locking.
     */
    public WorkerProcess(int partition, boolean primaryPartition, String workerName, JedisPool connectionPool,
            int lockTTL, int lockCheckTime, int lockRenewTime, int lockAttemptTimeout) {
        this.partition = partition;
        this.primaryPartition = primaryPartition;
        this.connectionPool = connectionPool;
        this.workerName = workerName;
        this.sortedSetName = "scheduled-items-" + partition;
        this.lockTTL = lockTTL;
        this.lockCheckTime = lockCheckTime;
        this.lockRenewTime = lockRenewTime;
        this.lockAttemptTimeout = lockAttemptTimeout;
    }

    @Override
    public void run() {
        // Gets a Jedis connection
        Jedis connection = connectionPool.getResource();

        // Builds and configures the distributed locker class
        DistributedLocker locker = new DistributedLocker("partition-" + partition, workerName, lockTTL, lockCheckTime,
                lockRenewTime, connectionPool);

        // Tries to acquire the lock
        boolean lockAcquired = false;
        try {
            lockAcquired = locker.lock(primaryPartition, lockAttemptTimeout);
        } catch (DistributedLockerException e) {
            Logger.getGlobal().log(Level.SEVERE, "Unable to acquired lock on partition {0} because of an exception",
                    partition);
            return;
        }

        // Ends the process if unable to acquire the lock
        if (!lockAcquired) {
            Logger.getGlobal().log(Level.WARNING, "Got time out while trying to acquired lock on partition {0}",
                    partition);
            return;
        }

        /**
         * Reads data related to the partition. In this example, we are using a Redis
         * SortedSet that contains items scheduled to certain period of time. The
         * process reads, process and deletes anything from the begining of times until
         * now. Epoch time in milliseconds is used as the score
         */
        Logger.getGlobal().log(Level.INFO, "Processing partition {0}", partition);

        Set<String> scheduledItems = connection.zrangeByScore(sortedSetName, 0, Instant.now().toEpochMilli());
        for (String scheduledItem : scheduledItems) {
            // Do some processing and remove
            // ... processing ...
            connection.zrem(sortedSetName, scheduledItem);
            Logger.getGlobal().log(Level.INFO, "Processed item {0}", scheduledItem);
        }

        // If it is not a primary partition, we release if the lock as soon as possible
        if (!primaryPartition) {
            locker.unlock();
        }

        connection.close();
    }
}