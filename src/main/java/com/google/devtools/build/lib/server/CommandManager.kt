// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.server

import com.google.devtools.build.lib.server.CommandProtos.CancelRequest

/** Helper class for commands that are currently running on the server.  */
internal class CommandManager(
    /** Whether idle tasks are enabled.  */
    private val doIdleServerTasks: Boolean, private val slowInterruptMessageSuffix: String?
) {
    /**
     * The list of currently running commands. Note that, even though most commands run serially
     * because of the output base lock, they're registered here before blocking for the lock, so the
     * map is effectively unbounded.
     */
    @javax.annotation.concurrent.GuardedBy("runningCommandsMap")
    private val runningCommandsMap: MutableMap<String?, RunningCommand> = HashMap<String?, RunningCommand>()

    /** The current IdleTaskManager. Null when a command is running or if idle tasks are disabled.  */
    @javax.annotation.concurrent.GuardedBy("this")
    private var idleTaskManager: IdleTaskManager? = null

    /**
     * Idle task results from the most recent idle period following a command that registered idle
     * tasks. Null after a subsequent command retrieves them or if idle tasks are disabled.
     */
    @javax.annotation.concurrent.GuardedBy("this")
    private var idleTaskResults: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.server.IdleTask.Result?>? =
        null

    private val interruptCounter: AtomicLong = AtomicLong(0)

    init {
        idle(java.util.Optional.empty<com.google.common.collect.ImmutableList<com.google.devtools.build.lib.server.IdleTask?>?>())
    }

    fun preemptEligibleCommands() {
        synchronized(runningCommandsMap) {
            val commandsToInterruptBuilder: com.google.common.collect.ImmutableSet.Builder<String?> =
                com.google.common.collect.ImmutableSet.Builder<String?>()
            for (command in runningCommandsMap.values) {
                if (command.isPreemptible) {
                    command.thread.interrupt()
                    commandsToInterruptBuilder.add(command.id)
                }
            }

            val commandsToInterrupt: com.google.common.collect.ImmutableSet<String?> =
                commandsToInterruptBuilder.build()
            if (!commandsToInterrupt.isEmpty()) {
                startSlowInterruptWatcher(commandsToInterrupt)
            }
        }
    }

    fun interruptInflightCommands() {
        synchronized(runningCommandsMap) {
            for (command in runningCommandsMap.values) {
                command.thread.interrupt()
            }
            startSlowInterruptWatcher(com.google.common.collect.ImmutableSet.copyOf<String?>(runningCommandsMap.keys))
        }
    }

    fun doCancel(request: CancelRequest) {
        createCommand().use { cancelCommand ->
            synchronized(runningCommandsMap) {
                val pendingCommand = runningCommandsMap.get(request.getCommandId())
                if (pendingCommand != null) {
                    logger.atInfo().log(
                        "Interrupting command %s on thread %s",
                        request.getCommandId(), pendingCommand.thread.getName()
                    )
                    pendingCommand.thread.interrupt()
                    startSlowInterruptWatcher(com.google.common.collect.ImmutableSet.of<E?>(request.getCommandId()))
                } else {
                    logger.atInfo().log("Cannot find command %s to interrupt", request.getCommandId())
                }
            }
        }
    }

    val isEmpty: Boolean
        get() {
            synchronized(runningCommandsMap) {
                return runningCommandsMap.isEmpty()
            }
        }

    @Throws(java.lang.InterruptedException::class)
    fun waitForChange() {
        synchronized(runningCommandsMap) {
            (runningCommandsMap as java.lang.Object).wait()
        }
    }

    @Throws(java.lang.InterruptedException::class)
    fun waitForChange(timeout: Long) {
        synchronized(runningCommandsMap) {
            (runningCommandsMap as java.lang.Object).wait(timeout)
        }
    }

    fun createPreemptibleCommand(): RunningCommand {
        val command = RunningCommand(true)
        registerCommand(command)
        return command
    }

    fun createCommand(): RunningCommand {
        val command = RunningCommand(false)
        registerCommand(command)
        return command
    }

    private fun registerCommand(command: RunningCommand) {
        synchronized(runningCommandsMap) {
            if (runningCommandsMap.isEmpty()) {
                busy()
            }
            runningCommandsMap.put(command.id, command)
            (runningCommandsMap as java.lang.Object).notify()
        }
        logger.atInfo().log("Starting command %s on thread %s", command.id, command.thread.getName())
    }

    /**
     * Enters an idle period.
     * 
     * 
     * Called when the set of running commands becomes empty.
     * 
     * @param idleTasks idle tasks to run during the idle period, if any.
     */
    private fun idle(idleTasks: java.util.Optional<com.google.common.collect.ImmutableList<com.google.devtools.build.lib.server.IdleTask?>?>) {
        if (doIdleServerTasks && idleTasks.isPresent()) {
            synchronized(this) {
                com.google.common.base.Preconditions.checkState(idleTaskManager == null)
                idleTaskManager = IdleTaskManager(idleTasks.get())
                idleTaskManager.idle()
            }
        }
    }

    /**
     * Leaves an idle period.
     * 
     * 
     * Called when the set of running commands becomes non-empty.
     */
    private fun busy() {
        synchronized(this) {
            if (idleTaskManager != null) {
                idleTaskResults = idleTaskManager.busy()
                idleTaskManager = null
            }
        }
    }

    private fun startSlowInterruptWatcher(commandIds: com.google.common.collect.ImmutableSet<String?>) {
        if (commandIds.isEmpty()) {
            return
        }

        val interruptWatcher: java.lang.Runnable =
            java.lang.Runnable {
                try {
                    java.lang.Thread.sleep((10 * 1000).toLong())
                    val ok: Boolean
                    synchronized(runningCommandsMap) {
                        ok = Collections.disjoint(commandIds, runningCommandsMap.keys)
                    }
                    if (!ok) {
                        // At least one command was not interrupted. Interrupt took too long.
                        com.google.devtools.build.lib.util.ThreadUtils.warnAboutSlowInterrupt(slowInterruptMessageSuffix)
                    }
                } catch (e: java.lang.InterruptedException) {
                    // Ignore.
                }
            }

        val interruptWatcherThread: java.lang.Thread =
            java.lang.Thread(interruptWatcher, "interrupt-watcher-" + interruptCounter.incrementAndGet())
        interruptWatcherThread.setDaemon(true)
        interruptWatcherThread.start()
    }

    /**
     * Returns idle task results returned by [IdleTaskManager] during a previous idle period, if
     * available and not yet retrieved.
     * 
     * 
     * Clears the stored idle task results as a side effect.
     */
    @kotlin.jvm.Synchronized
    fun getIdleTaskResults(): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.server.IdleTask.Result?>? {
        val result: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.server.IdleTask.Result?>? =
            idleTaskResults
        idleTaskResults = null
        return result
    }

    internal inner class RunningCommand private constructor(val isPreemptible: Boolean) : java.lang.AutoCloseable {
        private val thread: java.lang.Thread
        @kotlin.jvm.JvmField
        val id: String?
        private var idleTasks: java.util.Optional<com.google.common.collect.ImmutableList<com.google.devtools.build.lib.server.IdleTask?>?> =
            java.util.Optional.empty<com.google.common.collect.ImmutableList<com.google.devtools.build.lib.server.IdleTask?>?>()

        init {
            thread = java.lang.Thread.currentThread()
            id = UUID.randomUUID().toString()
        }

        override fun close() {
            synchronized(runningCommandsMap) {
                runningCommandsMap.remove(id)
                if (runningCommandsMap.isEmpty()) {
                    idle(idleTasks)
                }
                (runningCommandsMap as java.lang.Object).notify()
            }

            logger.atInfo().log("Finished command %s on thread %s", id, thread.getName())
        }

        /**
         * Set idle tasks to be run by [IdleTaskManager] during an idle period immediately
         * following this command, if one occurs and idle tasks are enabled.
         */
        fun setIdleTasks(idleTasks: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.server.IdleTask?>) {
            this.idleTasks =
                java.util.Optional.of<com.google.common.collect.ImmutableList<com.google.devtools.build.lib.server.IdleTask?>?>(
                    idleTasks
                )
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
