// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.profiler.AutoProfiler.profiled

/**
 * Used to keep track of resources consumed by the Blaze action execution threads and throttle them
 * when necessary.
 * 
 * 
 * Threads which are known to consume a significant amount of resources should call [ ][.acquireResources] method. This method will check whether requested resources are available and
 * will either mark them as used and allow the thread to proceed or will block the thread until
 * requested resources will become available. When the thread completes its task, it must release
 * allocated resources by calling [.releaseResources] method.
 * 
 * 
 * Available resources can be calculated using one of three ways:
 * 
 * 
 *  1. They can be preset using [.setAvailableResources] method. This is used
 * mainly by the unit tests (however it is possible to provide a future option that would
 * artificially limit amount of CPU/RAM consumed by the Blaze).
 *  1. They can be preset based on the /proc/cpuinfo and /proc/meminfo information. Blaze will
 * calculate amount of available CPU cores (adjusting for hyperthreading logical cores) and
 * amount of the total available memory and will limit itself to the number of effective cores
 * and 2/3 of the available memory. For details, please look at the [       ][LocalHostCapacity.getLocalHostCapacity] method.
 * 
 * 
 * 
 * The resource manager also allows a slight overallocation of the resources to account for the
 * fact that requested resources are usually estimated using a pessimistic approximation. It also
 * guarantees that at least one thread will always be able to acquire any amount of requested
 * resources (even if it is greater than amount of available resources). Therefore, assuming that
 * threads correctly release acquired resources, Blaze will never be fully blocked.
 */
@ThreadSafe
class ResourceManager : ResourceEstimator {
    private var allowOneActionOnResourceUnavailable = false

    /**
     * A handle returned by [.acquireResources] that must be closed in order to free the resources again.
     */
    class ResourceHandle private constructor(
        private val manager: ResourceManager,
        request: ResourceRequest,
        worker: Worker?
    ) : java.lang.AutoCloseable {
        private var worker: Worker?
        private val request: ResourceRequest
        private val resourceAcquiredTime: Long

        init {
            this.resourceAcquiredTime = com.google.devtools.build.lib.clock.BlazeClock.instance().nanoTime()
            this.worker = worker
            this.request = request
        }

        fun getWorker(): Worker? {
            return worker
        }

        @com.google.common.annotations.VisibleForTesting
        fun getRequest(): ResourceRequest {
            return request
        }

        /** Closing the ResourceHandle releases the resources associated with it.  */
        @Throws(IOException::class, java.lang.InterruptedException::class, UserExecException::class)
        override fun close() {
            manager.releaseResources(request, worker)
            Profiler.instance()
                .completeTask(
                    resourceAcquiredTime, ProfilerTask.LOCAL_ACTION_COUNTS, "Resources acquired"
                )
        }

        @Throws(IOException::class, java.lang.InterruptedException::class, UserExecException::class)
        fun invalidateAndClose(e: java.lang.Exception?) {
            // If there is an exception, we need to set the kill cause before invalidating the object.
            // This ensures that the worker implementation updates their worker metrics accordingly
            // if/when it destroys itself.
            if (e != null) {
                if (e is java.lang.InterruptedException) {
                    worker.getStatus().maybeUpdateStatus(Status.PENDING_KILL_DUE_TO_INTERRUPTED_EXCEPTION)
                } else if (e is IOException) {
                    worker.getStatus().maybeUpdateStatus(Status.PENDING_KILL_DUE_TO_IO_EXCEPTION)
                } else if (e is UserExecException) {
                    if (e.getFailureDetail().hasWorker()) {
                        worker
                            .getStatus()
                            .maybeUpdateStatus(
                                Status.PENDING_KILL_DUE_TO_USER_EXEC_EXCEPTION,
                                e.getFailureDetail().getWorker().getCode()
                            )
                    }
                } else {
                    worker.getStatus().maybeUpdateStatus(Status.PENDING_KILL_DUE_TO_USER_EXEC_EXCEPTION)
                }
            } else {
                worker.getStatus().maybeUpdateStatus(Status.PENDING_KILL_DUE_TO_UNKNOWN)
            }

            manager.workerPool.invalidateWorker(worker)
            worker = null
            this.close()
        }
    }

    private val threadLocked: java.lang.ThreadLocal<Boolean?> = object : java.lang.ThreadLocal<Boolean?>() {
        override fun initialValue(): Boolean {
            return false
        }
    }

    /**
     * Defines the possible priorities of resources. The earlier elements in this enum will get first
     * chance at grabbing resources.
     */
    enum class ResourcePriority {
        LOCAL,  // Local execution not under dynamic execution
        DYNAMIC_WORKER,
        DYNAMIC_STANDALONE
    }

    fun setAllowOneActionOnResourceUnavailable(allowOneActionOnResourceUnavailable: Boolean) {
        this.allowOneActionOnResourceUnavailable = allowOneActionOnResourceUnavailable
    }

    /** Returns prediction of RAM in Mb used by registered actions.  */
    public override fun getUsedMemoryInMb(): Double {
        return usedResources.getOrDefault(ResourceSet.Companion.MEMORY, 0.0)!!
    }

    /** Returns prediction of CPUs used by registered actions.  */
    public override fun getUsedCPU(): Double {
        return usedResources.getOrDefault(ResourceSet.Companion.CPU, 0.0)!!
    }

    // Pair of requested resources and latch represented it for waiting.
    @kotlin.jvm.JvmRecord
    internal data class WaitingRequest(val getResourceRequest: ResourceRequest?, val getResourceLatch: ResourceLatch?)

    // Lists of blocked threads. Associated CountDownLatch object will always
    // be initialized to 1 during creation in the acquire() method.
    // We use LinkedList because we will need to remove elements from the middle frequently in the
    // middle of iterating through the list.
    private val localRequests: Deque<WaitingRequest> = LinkedList<WaitingRequest>()

    private val dynamicWorkerRequests: Deque<WaitingRequest> = LinkedList<WaitingRequest>()

    private val dynamicStandaloneRequests: Deque<WaitingRequest> = LinkedList<WaitingRequest>()

    private var workerPool: WorkerPool? = null

    // The total amount of available for Bazel resources on the local host. Must be set by
    // an explicit call to setAvailableResources(), often using
    // LocalHostCapacity.getLocalHostCapacity() as an argument.
    @com.google.common.annotations.VisibleForTesting
    var availableResources: ResourceSet? = null

    // Used amount of resources. Corresponds to the resource
    // definition in the ResourceSet class.
    private var usedResources: MutableMap<String?, Double?> = HashMap<String?, Double?>()

    // Used local test count. Corresponds to the local test count definition in the ResourceSet class.
    private var usedLocalTestCount = 0

    // The following flags are responsible for experimental action scheduling based on load of the
    // machine.
    //
    // With this functionality the whole timeline is splitted on the window of the same duration.
    // In this case the CPU usage by blaze is defined by the formula:
    // CPU usage = System CPU load + Window estimation.
    // System CPU load defined by information about system running blaze process.
    // Window estimation is an sum of ResourceSets defined for all action started to run during this
    // window. This term added to compensate the pressure by actions which are started to run during
    // the window but not represented on CPU load yet.
    // Experimental scheduling have showed the large benefit on a large local builds on a powerful
    // machines with the large number of cores.
    // The known issue with this flag that it cannot distinguish the load of Bazel and load of
    // different process on the machine, so it tries to load machine no more than defined in flag
    // local_resources, so for better utilization it's recommended to set
    // --local_resources=cpu=HOST_CPUS.
    // Enables experimental action scheduling using CPU load of a machine.
    private var cpuLoadScheduling = false

    // The size of window for running actions.
    private var windowSize: java.time.Duration = java.time.Duration.ofSeconds(5)

    // Estimation of CPU usage by actions started during the window.
    private var windowEstimationCpu = 0.0

    // Set of request ids which resource acquiring started during the window.
    private val windowRequestIds: MutableSet<Int?> = HashSet<Int?>()

    // Executor for periodic window update.
    var windowUpdateExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

    // Future for periodic window update.
    var windowUpdateFuture: java.util.concurrent.ScheduledFuture<*>? = null

    // Total number of actions running locally.
    private var runningActions = 0

    // Collects the information about the load of a machine.
    private var machineLoadProvider: MachineLoadProvider? = null

    fun initializeCpuLoadFunctionality(
        machineLoadProvider: MachineLoadProvider, cpuLoadScheduling: Boolean, windowSize: java.time.Duration
    ) {
        this.machineLoadProvider = machineLoadProvider
        this.cpuLoadScheduling = cpuLoadScheduling
        this.windowSize = windowSize
    }

    internal inner class WindowUpdateRunner(name: String) : java.lang.Thread(name) {
        override fun run() {
            try {
                windowUpdate()
            } catch (e: IOException) {
                com.google.devtools.build.lib.actions.ResourceManager.Companion.logger.atWarning().withCause(e).log(
                    "Exception while updating window of locally scheduled action: %s", e
                )
            } catch (e: java.lang.InterruptedException) {
                com.google.devtools.build.lib.actions.ResourceManager.Companion.logger.atWarning().withCause(e).log(
                    "Exception while updating window of locally scheduled action: %s", e
                )
            } catch (e: UserExecException) {
                com.google.devtools.build.lib.actions.ResourceManager.Companion.logger.atWarning().withCause(e).log(
                    "Exception while updating window of locally scheduled action: %s", e
                )
            }
        }
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class, java.lang.InterruptedException::class, UserExecException::class)
    fun windowUpdate() {
        windowRequestIds.clear()
        windowEstimationCpu = 0.0
        processAllWaitingRequests()
    }

    /**
     * Resets resource manager state and releases all thread locks.
     * 
     * 
     * Note - it does not reset available resources. Use separate call to setAvailableResources().
     */
    @kotlin.jvm.Synchronized
    fun resetResourceUsage() {
        usedResources = HashMap<String?, Double?>()
        usedLocalTestCount = 0
        for (request in localRequests) {
            request.getResourceLatch.getLatch().countDown()
        }
        for (request in dynamicWorkerRequests) {
            request.getResourceLatch.getLatch().countDown()
        }
        for (request in dynamicStandaloneRequests) {
            request.getResourceLatch.getLatch().countDown()
        }
        localRequests.clear()
        dynamicWorkerRequests.clear()
        dynamicStandaloneRequests.clear()

        windowRequestIds.clear()
        windowEstimationCpu = 0.0
        runningActions = 0
    }

    /**
     * Sets available resources using given resource set.
     * 
     * 
     * Must be called at least once before using resource manager.
     */
    @kotlin.jvm.Synchronized
    fun setAvailableResources(resources: ResourceSet?) {
        com.google.common.base.Preconditions.checkNotNull<ResourceSet?>(resources)
        resetResourceUsage()
        availableResources = resources
        com.google.devtools.build.lib.actions.ResourceManager.Companion.logger.atInfo()
            .log("Set available resources: %s", resources)
    }

    @kotlin.jvm.Synchronized
    fun scheduleCpuLoadWindowUpdate() {
        if (windowUpdateFuture != null) {
            windowUpdateFuture.cancel(true)
        }

        if (cpuLoadScheduling) {
            windowUpdateFuture =
                windowUpdateExecutor.scheduleAtFixedRate(
                    WindowUpdateRunner("window-update"), 0, windowSize.toMillis(), TimeUnit.MILLISECONDS
                )
        }
    }

    /** Sets worker pool for taking the workers. Must be called before requesting the workers.  */
    fun setWorkerPool(workerPool: WorkerPool) {
        this.workerPool = workerPool
    }

    /** Request with the information of resource acquiring.  */
    internal class ResourceRequest(
        getOwner: ActionExecutionMetadata?,
        getResourceSet: ResourceSet,
        getPriority: ResourcePriority?,
        getId: Int
    ) {
        val getOwner: ActionExecutionMetadata?
        val getResourceSet: ResourceSet
        val getPriority: ResourcePriority?
        val getId: Int

        init {
            this.getOwner = getOwner
            this.getResourceSet = getResourceSet
            this.getPriority = getPriority
            this.getId = getId
        }
    }

    /**
     * Acquires requested resource set. Will block if resource is not available. NB! This method must
     * be thread-safe!
     */
    @Throws(java.lang.InterruptedException::class, IOException::class, ExecException::class)
    fun acquireResources(
        owner: ActionExecutionMetadata, resources: ResourceSet?, priority: ResourcePriority?
    ): ResourceHandle {
        com.google.common.base.Preconditions.checkNotNull<ResourceSet?>(
            resources, "acquireResources called with resources == NULL during %s", owner
        )
        com.google.common.base.Preconditions.checkState(
            !threadHasResources(), "acquireResources with existing resource lock during %s", owner
        )

        var resourceLatch: ResourceLatch? = null

        // Validate requested resources exist before creating a request.
        assertResourcesTracked(resources)
        val request =
            ResourceRequest(
                owner,
                resources,
                priority,
                com.google.devtools.build.lib.actions.ResourceManager.Companion.requestIdGenerator.getAndIncrement()
            )

        val p: AutoProfiler =
            profiled("Acquiring resources for: " + owner.describe(), ProfilerTask.ACTION_LOCK)
        try {
            resourceLatch = acquire(request)
            if (resourceLatch!!.getLatch() != null) {
                resourceLatch.getLatch().await()
            }
        } catch (e: java.lang.InterruptedException) {
            // Synchronize on this to avoid any racing with #processWaitingRequests
            synchronized(this) {
                if (resourceLatch != null) {
                    if (resourceLatch.getLatch() == null || resourceLatch.getLatch().getCount() == 0L) {
                        // Resources already acquired by other side. Release them, but not inside this
                        // synchronized block to avoid deadlock.
                        release(request, resourceLatch.getWorker())
                    } else {
                        // Inform other side that resources shouldn't be acquired.
                        resourceLatch.getLatch().countDown()
                    }
                }
            }
            throw e
        }

        threadLocked.set(true)

        val latch: CountDownLatch?
        val worker: Worker?
        synchronized(this) {
            latch = resourceLatch.getLatch()
            worker = resourceLatch.getWorker()
        }

        // Profile acquisition only if it waited for resource to become available.
        if (latch != null) {
            p.complete()
        }

        return ResourceHandle(this, request, worker)
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun incrementResources(request: ResourceRequest): Worker? {
        val resources: ResourceSet = request.getResourceSet

        resources
            .getResources()
            .forEach { (key: String?, value: Double?) ->
                var value = value
                if (usedResources.containsKey(key)) {
                    this.value += usedResources.get(key)!!
                }
                usedResources.put(key, value)
            }

        windowRequestIds.add(request.getId)
        windowEstimationCpu += resources.getResources().getOrDefault(ResourceSet.Companion.CPU, 0.0)
        usedLocalTestCount += resources.getLocalTestCount()
        if (resources.getWorkerKey() != null) {
            return this.workerPool.borrowWorker(resources.getWorkerKey())
        }

        runningActions++
        return null
    }

    /** Return true if any resources have been claimed through this manager.  */
    @kotlin.jvm.Synchronized
    fun inUse(): Boolean {
        return !usedResources.isEmpty() || usedLocalTestCount != 0 || !localRequests.isEmpty() || !dynamicWorkerRequests.isEmpty() || !dynamicStandaloneRequests.isEmpty()
    }

    /** Return true iff this thread has a lock on non-zero resources.  */
    fun threadHasResources(): Boolean {
        return threadLocked.get()
    }

    /**
     * Releases previously requested resource.
     * 
     * 
     * NB! This method must be thread-safe!
     * 
     * @param request initial request of resource acquiring
     * @param worker the worker, which used during execution
     * @throws java.io.IOException if could not return worker to the workerPool
     */
    @Throws(IOException::class, java.lang.InterruptedException::class, UserExecException::class)
    fun releaseResources(request: ResourceRequest, worker: Worker?) {
        com.google.common.base.Preconditions.checkNotNull<ResourceSet?>(
            request.getResourceSet,
            "releaseResources called with resources == NULL during %s",
            request.getOwner
        )

        com.google.common.base.Preconditions.checkState(
            threadHasResources(),
            "releaseResources without resource lock during %s",
            request.getOwner
        )

        try {
            release(request, worker)
        } finally {
            threadLocked.set(false)
        }
    }

    fun releaseResourceOwnership() {
        threadLocked.set(false)
    }

    fun acquireResourceOwnership() {
        threadLocked.set(true)
    }

    /**
     * Returns the pair of worker and latch. Worker should be null if there is no workerKey in
     * resources. The latch isn't null if we could not acquire the resources right now and need to
     * wait.
     */
    @kotlin.jvm.Synchronized
    @Throws(IOException::class, java.lang.InterruptedException::class, UserExecException::class)
    private fun acquire(request: ResourceRequest): ResourceLatch? {
        if (areResourcesAvailable(request.getResourceSet)) {
            val worker: Worker? = incrementResources(request)
            return ResourceLatch( /* latch= */null, worker)
        }
        val waitingRequest =
            WaitingRequest(request, ResourceLatch(CountDownLatch(1),  /* worker= */null))
        when (request.getPriority) {
            ResourcePriority.LOCAL -> localRequests.addLast(waitingRequest)
            ResourcePriority.DYNAMIC_WORKER ->  // Dynamic requests should be LIFO, because we are more likely to win the race on newer
                // actions.
                dynamicWorkerRequests.addFirst(waitingRequest)

            ResourcePriority.DYNAMIC_STANDALONE ->  // Dynamic requests should be LIFO, because we are more likely to win the race on newer
                // actions.
                dynamicStandaloneRequests.addFirst(waitingRequest)
        }
        return waitingRequest.getResourceLatch
    }

    /** Release resources and process the queues of waiting threads.  */
    @kotlin.jvm.Synchronized
    @Throws(IOException::class, java.lang.InterruptedException::class, UserExecException::class)
    private fun release(request: ResourceRequest, worker: Worker?) {
        if (worker != null) {
            this.workerPool.returnWorker(worker.getWorkerKey(), worker)
        }

        val resources: ResourceSet = request.getResourceSet
        usedLocalTestCount -= resources.getLocalTestCount()
        // TODO(bazel-team): (2010) rounding error can accumulate and value below can end up being
        // e.g. 1E-15. So if it is small enough, we set it to 0. But maybe there is a better solution.
        val epsilon = 0.0001
        val toRemove: MutableSet<String?> = HashSet<String?>()
        for (resource in resources.getResources().entries) {
            val key: String? = resource.key
            val value: Double = usedResources.getOrDefault(key, 0.0) - resource.value
            usedResources.put(key, value)
            if (value < epsilon) {
                toRemove.add(key)
            }
        }
        usedResources.keys.removeAll(toRemove)
        for (key in toRemove) {
            usedResources.remove(key)
        }

        if (windowRequestIds.remove(request.getId)) {
            windowEstimationCpu -= resources.getResources().getOrDefault(ResourceSet.Companion.CPU, 0.0)
        }
        runningActions--

        processAllWaitingRequests()
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class, java.lang.InterruptedException::class, UserExecException::class)
    private fun processAllWaitingRequests() {
        processWaitingRequests(localRequests)
        processWaitingRequests(dynamicWorkerRequests)
        processWaitingRequests(dynamicStandaloneRequests)
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class, java.lang.InterruptedException::class, UserExecException::class)
    private fun processWaitingRequests(requests: Deque<WaitingRequest>) {
        if (requests.isEmpty()) {
            return
        }

        val iterator: MutableIterator<WaitingRequest> = requests.iterator()
        while (iterator.hasNext()) {
            val request = iterator.next()
            if (request.getResourceLatch!!.getLatch().getCount() != 0L) {
                if (areResourcesAvailable(request.getResourceRequest!!.getResourceSet)) {
                    val worker: Worker? = incrementResources(request.getResourceRequest)
                    request.getResourceLatch.setWorker(worker)
                    request.getResourceLatch.getLatch().countDown()
                    iterator.remove()
                }
            } else {
                // Cancelled by other side.
                iterator.remove()
            }
        }
    }

    /** Throws an exception if requested extra resource isn't being tracked  */
    @Throws(ExecException::class)
    private fun assertResourcesTracked(resources: ResourceSet) {
        for (resource in resources.getResources().entries) {
            val key: String? = resource.key
            if (!availableResources.getResources().containsKey(key)) {
                val message: java.lang.StringBuilder = java.lang.StringBuilder()
                message.append("Resource ")
                message.append(key)
                message.append(" is not being tracked by the resource manager.")
                message.append(" Available resources are: ")
                message.append(java.lang.String.join(", ", availableResources.getResources().keys))
                throw UserExecException(
                    FailureDetails.FailureDetail.newBuilder()
                        .setMessage(message.toString())
                        .setLocalExecution(
                            FailureDetails.LocalExecution.newBuilder()
                                .setCode(FailureDetails.LocalExecution.Code.UNTRACKED_RESOURCE)
                                .build()
                        )
                        .build()
                )
            }
        }
    }

    @Throws(UserExecException::class)
    private fun <T : Number?> isAvailable(
        available: T?, used: T?, requested: T?, resourceName: String?
    ): Boolean {
        if (!allowOneActionOnResourceUnavailable
            && available!!.toDouble() + used!!.toDouble() < requested!!.toDouble()
        ) {
            throw UserExecException(
                FailureDetails.FailureDetail.newBuilder()
                    .setMessage(
                        String.format(
                            ("The `%s` resources are not enough to fulfill the request. To allow Bazel to"
                                    + " bypass this limitation, please adjust the --local_resources flag or"
                                    + " specify --allow_one_action_on_resource_unavailable in your Bazel"
                                    + " command."),
                            resourceName
                        )
                    )
                    .setLocalExecution(
                        FailureDetails.LocalExecution.newBuilder()
                            .setCode(FailureDetails.LocalExecution.Code.NOT_ENOUGH_LOCAL_RESOURCE)
                            .build()
                    )
                    .build()
            )
        }
        // Resources are considered available if any one of the conditions below is true:
        // 1) If resource is not requested at all, it is available.
        // 2) If resource is not used at the moment and the flag
        // "allow_one_action_on_resource_unavailable" is enabled, it is considered to be
        // available regardless of how much is requested. This is necessary to
        // ensure that at any given time, at least one thread is able to acquire
        // resources even if it requests more than available.
        // 3) If used resource amount is less than total available resource amount.
        return requested!!.toDouble() == 0.0 || (allowOneActionOnResourceUnavailable && used!!.toDouble() == 0.0)
                || used!!.toDouble() + requested.toDouble() <= available!!.toDouble()
    }

    // Method will return true if all requested resources are considered to be available.
    @com.google.common.annotations.VisibleForTesting
    @kotlin.jvm.Synchronized
    @Throws(UserExecException::class)
    fun areResourcesAvailable(resources: ResourceSet): Boolean {
        com.google.common.base.Preconditions.checkNotNull<ResourceSet?>(availableResources)

        // Comparison below is robust, since any calculation errors will be fixed
        // by the release() method.
        val workerKey: WorkerKey? = resources.getWorkerKey()
        if (workerKey != null && !this.workerPool.hasAvailableQuota(workerKey)) {
            return false
        }

        if (allowOneActionOnResourceUnavailable
            && usedResources.isEmpty()
            && usedLocalTestCount == 0 && resources.getLocalTestCount() > 0
        ) {
            return true
        }

        val availableLocalTestCount: Int = availableResources.getLocalTestCount()
        if (!isAvailable<Int?>(
                availableLocalTestCount,
                usedLocalTestCount,
                resources.getLocalTestCount(),
                "local_test_count"
            )
        ) {
            return false
        }

        for (resource in resources.getResources().entries) {
            val key: String = resource.key

            if (key == ResourceSet.Companion.CPU) {
                if (!isCpuAvailable(resource)) {
                    return false
                }
                continue
            }
            // Use only MIN_NECESSARY_RATIO of the resource value to check for
            // allocation. This is necessary to account for the fact that most of the
            // requested resource sets use pessimistic estimations. Note that this
            // ratio is used only during comparison - for tracking we will actually
            // mark whole requested amount as used.
            val requested: Double =
                resource.value * com.google.devtools.build.lib.actions.ResourceManager.Companion.MIN_NECESSARY_RATIO.getOrDefault(
                    key,
                    com.google.devtools.build.lib.actions.ResourceManager.Companion.DEFAULT_MIN_NECESSARY_RATIO
                )
            val used: Double = usedResources.getOrDefault(key, 0.0)!!
            val available: Double = availableResources.get(key)
            if (!isAvailable<Double?>(available, used, requested, key)) {
                return false
            }
        }
        return true
    }

    @kotlin.jvm.Synchronized
    @Throws(UserExecException::class)
    fun isCpuAvailable(resource: MutableMap.MutableEntry<String, Double?>): Boolean {
        val key: String? = resource.key

        val requested: Double =
            resource.value * com.google.devtools.build.lib.actions.ResourceManager.Companion.MIN_NECESSARY_RATIO.getOrDefault(
                key,
                com.google.devtools.build.lib.actions.ResourceManager.Companion.DEFAULT_MIN_NECESSARY_RATIO
            )
        val available: Double = availableResources.get(key)
        val used: Double = usedResources.getOrDefault(key, 0.0)!!

        if (cpuLoadScheduling) {
            val currentUsage: Double = machineLoadProvider.getCurrentCpuUsage()
            val windowEstimation = windowEstimationCpu
            // Don't allow to run more than x3 of number cores actions simultaneously.
            if (runningActions >= com.google.devtools.build.lib.actions.ResourceManager.Companion.MAX_ACTIONS_PER_CPU * availableResources.get(
                    ResourceSet.Companion.CPU
                )
            ) {
                return false
            }
            return isAvailable<Double?>(available, windowEstimation + currentUsage, requested, key)
        }

        return isAvailable<Double?>(available, used, requested, key)
    }

    @com.google.common.annotations.VisibleForTesting
    @kotlin.jvm.Synchronized
    fun getWaitCount(): Int {
        return localRequests.size + dynamicStandaloneRequests.size + dynamicWorkerRequests.size
    }

    // Latch which indicates the availability of resources. Also via this latch worker could be passed
    // when it's ready.
    private class ResourceLatch(latch: CountDownLatch?, worker: Worker?) {
        private val latch: CountDownLatch?
        private var worker: Worker?

        init {
            this.latch = latch
            this.worker = worker
        }

        fun getLatch(): CountDownLatch? {
            return latch
        }

        fun getWorker(): Worker? {
            return worker
        }

        fun setWorker(worker: Worker?) {
            this.worker = worker
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        // Allocated resources are allowed to go "negative", but at least
        // MIN_NECESSARY_RATIO portion of each resource should be available.
        // Please note that this value is purely empirical - we assume that generally
        // requested resources are somewhat pessimistic and thread would end up
        // using less than requested amount.
        private const val DEFAULT_MIN_NECESSARY_RATIO = 1.0
        private val MIN_NECESSARY_RATIO: com.google.common.collect.ImmutableMap<String?, Double?> =
            com.google.common.collect.ImmutableMap.of<String?, Double?>(ResourceSet.Companion.CPU, 0.6)
        private const val MAX_ACTIONS_PER_CPU = 3

        /** Generates the ids for requests  */
        private val requestIdGenerator: AtomicInteger = AtomicInteger(0)
    }
}
