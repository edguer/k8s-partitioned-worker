# Kubernetes Partitioned Worker with Redis

This is an example on how to build a partitioned batch worker for a Kubernetes cluster.

## Problem statement

Suppose a continuous batch worker should be built on top of a single data set in the same data source, for example: make data transformation over a database table, or picking up scheduled items in the cache. Running a single instance of that worker would be simple enough, but only one record at a time could be processed. Multi-threading will definitely help, but how can it be scaled to more than one server? Now suppose your worker is able to process 500 transactions per second in a single host, and you want to increase it to 1,000 TPS. Logic says adding one more worker would do, but then they would be processing the same data set at the same time, which will end up in concurrency problems or duplicated processing, with potentially no gain.

If using a message broker, the problem could be solved in the broker itself (such as Kafka partitions), but as the current scenario doesn't include the use of a queue or topic, there is nothing between the worker and the database to know what data that worker should process.

## Solution

The solution was to create multiple replicas of the worker and make each of them work on a different "chunk" of information, or "partition". The concept is similar to sharding, for example: worker1 will only process transactions related to Canada customers, whereas worker2 will only process US transactions. So worker1 is responsible for partition CA and worker2 for partition US. Assuming US and Canada produce the same amount of transactions, the work will be split between the workers. 

## Demand Surge

Unfortunately, the pattern does not deal with a sudden rise in the number of items for a specific partition, meaning the performance level for that partition will bump into the worker's compute resources.

## Failover

Having one worker per partition is risky, since if it fails, then a good chunk of information won't get processed. To solve this, the code has the concept of primary and secondary partition, meaning that a worker will be mainly focused on processing its primary partition, but always ready to process the secondary partition if the main worker of that partition fails. 

For example, let's say we have a couple of workers:

- worker1, primary partition: 1, secondary partition: 2, host: server1
- worker2, primary partition: 2, secondary partition: 1, host: server2

In a regular, healthy environment, worker1 will always process partition 1, and worker2 always processes partition 2. Now suppose the server2 goes down. What must happen is that worker1 will take both partition 1 and 2, and, as soon as worker2 comes back online, it starts processing the partition 2 again, so worker1 can release partition 2 and focus only on partition 1.

However, how worker1 knows that worker2 stopped processing those items? Seems like RPC communication would solve it, but it would mean more complexity, exposing ports, standing up tiny RPC server threads (with gRPC, let's say), but turns out there is better solution if a shared memory cluster is at hand (such as Memcache or Redis): building a distributed locking system.

### Failover and Locking

The idea is simple: mimic a regular multi-thread locking mechanism (```lock``` keyword for C#, ```synchronized``` in java or any Mutex implementation) on a distributed cross-process, cross-server system. That way, worker1 would acquire a persistent lock over partition 1, and worker2 over partition 2. In turn, worker1 will continuously try to acquire a short-lived lock on partition 2. Worker2 will never release the lock on partition 2, only if the server goes down. In that case, as the worker1 is constant trying to get the lock on partition 2, it will soon acquire the lock, process it and release the lock as soon as possible, so that whenever the worker2 comes back online, the lock will be released for it to acquire a persistent lock again.

### Distributed Locker Implementation

The distributed locker algorithm implementation reference can be found [here](https://redislabs.com/ebook/part-2-core-concepts/chapter-6-application-components-in-redis/6-2-distributed-locking/6-2-3-building-a-lock-in-redis/).

## Kubernetes Deployment

We opted to use a Kubernetes StatefulSet, since it gives each POD a fixed name (example: worker-0, worker-1 etc) which can be used to infer the partition name. A Cronjob could be used, however we would need to have one Cronjob, with a different name, per partition - with Helm it could be done dynamically, but the StatefulSets seem to be better suited.

## Code & Solution

## Build & Run

## Deployment