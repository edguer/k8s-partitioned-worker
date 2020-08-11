package com.sample;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import redis.clients.jedis.Jedis;

/**
 * Locker class represents a locker system in a memory cache
 */
public class DistributedLocker {
	String lockName;
	String ownerName;
	int lockTTL;
	int checkTime;
	int renewTime;
	Jedis connection;

	/**
	 * Initializes the locker. For simplicty, the class access the Jedis API
	 * directly. For a production-ready code, please consider using layer separation
	 * and dependency injection.
	 *
	 * @param lockName   name of the lock, usually the name key of a key/value pair
	 * @param ownerName  owner's name of the lock, usually the name value of a
	 *                   key/value pair
	 * @param lockTTL    locker time-to-live in seconds
	 * @param checkTime  time in milliseconds to wait between lock attempts.
	 *                   Example: if lock is currently locked, the lock attempt
	 *                   timeout (see lock(int) method) is 10,000 and the
	 *                   "checkTime" is 1,000, it will try to acquire the lock 10
	 *                   times
	 * @param renewTime  time in milliseconds between lock renewals, only applies
	 *                   when using fixed lock. A background thread will run each
	 *                   "renewTime" milliseconds to renew the lock. This value
	 *                   should always be lower than the "lockTTL", otherwise the
	 *                   lock will be released before renewing it.
	 * @param connection Jedis connection. This class will use a single Jedis
	 *                   connection to, if it is closed, an exception will be
	 *                   raised. We kept it that way for simplicity, but ina
	 *                   production code you should manage the connection in a more
	 *                   appropriated way, such as returning the connection to the
	 *                   pull and soon as any code block is finished, and lending a
	 *                   new one whenever necessary.
	 */
	public DistributedLocker(String lockName, String ownerName, int lockTTL, int checkTime, int renewTime,
			Jedis connection) {
		this.lockName = lockName;
		this.ownerName = ownerName;
		this.lockTTL = lockTTL;
		this.checkTime = checkTime;
		this.renewTime = renewTime;
		this.connection = connection;
	}

	/**
	 * Acquires the lock. It tries to acquired the lock, if it is already locked, it
	 * will wait until it is realeased or until the timeout is reached.
	 *
	 * @param fixedLock if true, lock will be renewed automatically
	 * @param timeout   time-out in milliseconds. If 0 is provided, it will wait
	 *                  indefinitely
	 * @return true if the lock was acquired, false if it timed out
	 */
	public boolean lock(boolean fixedLock, long timeout) throws DistributedLockerException {
		long startTime = Instant.now().toEpochMilli();

		/**
		 * If I can't set the lock value (setnx == 0), somebody ownes it, which means I
		 * will have to wait until it is released, except when I'm the lock owner.
		 */
		long lockResult;
		while ((lockResult = connection.setnx(lockName, ownerName)) <= 0) {
			// If lockerResult is less than 0, then we had a problem
			if (lockResult < 0) {
				throw new DistributedLockerException("Unable to acquire the lock: unkown error.");
			}

			// Gets the locker name. If it's me, just assume I can keep the lock
			String lockedPartition = connection.get(lockName);
			if ((lockedPartition != null) && lockedPartition.equals(ownerName)) {
				return true;
			}

			/**
			 * If timeout is 0, don't watch for the timeout. Otherwise, give up trying if we
			 * reach the timeout
			 */
			if ((timeout > 0) && ((Instant.now().toEpochMilli() - startTime) >= timeout)) {
				return false;
			}

			// Wait for the next try
			try {
				Thread.sleep(checkTime);
			} catch (InterruptedException e) {
				Logger.getGlobal().warning("Lock wait was interrupted.");
			}
		}

		connection.expire(lockName, lockTTL);

		// If it is a fixed lock, then we try to renew it continuously
		if (fixedLock) {
			// Fire and forget thread
			ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
			executor.scheduleAtFixedRate(() -> renewLock(), 0, renewTime, TimeUnit.MILLISECONDS);
		}

		return true;
	}

	/**
	 * renewLock only sets the TTL of the lock.
	 */
	private void renewLock() {
		try {
			connection.expire(lockName, lockTTL);
		} catch (Exception error) {
			Logger.getGlobal().severe("Error renewing lock: " + error.getMessage());
		}
	}

	/**
	 * Releases the lock.
	 */
	public void unlock() {
		String currentOwner = connection.get(lockName);

		/**
		 * If the owner name is empty or different from the current one, we simply
		 * ignore the unlock action.
		 */
		if (((currentOwner == null) || currentOwner.equals("")) || !currentOwner.equals(ownerName)) {
			Logger.getGlobal().warning("Tried to unlock without ownership, ignoring");
		} else {
			connection.del(lockName);
		}
	}
}