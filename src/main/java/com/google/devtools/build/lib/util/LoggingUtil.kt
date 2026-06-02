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
package com.google.devtools.build.lib.util

import java.util.concurrent.ExecutionException
import java.util.logging.LogRecord

/**
 * Logging utilities for sending log messages to a remote service. Log messages will not be output
 * anywhere else, including the terminal and blaze clients.
 */
@com.google.errorprone.annotations.ThreadSafe
object LoggingUtil {
    // TODO(bazel-team): this class is a thin wrapper around Logger and could probably be discarded.
    private var remoteLogger: java.util.concurrent.Future<java.util.logging.Logger?>? = null

    /**
     * Installs the remote logger.
     * 
     * 
     * This can only be called once, and the caller should not keep the reference to the logger.
     * 
     * @param logger The logger future. Must have already started.
     */
    @kotlin.jvm.Synchronized
    fun installRemoteLogger(logger: java.util.concurrent.Future<java.util.logging.Logger?>?) {
        com.google.common.base.Preconditions.checkState(remoteLogger == null)
        remoteLogger = logger
    }

    /**
     * Installs the remote logger. Same as [.installRemoteLogger], but since multiple tests will
     * run in the same JVM, does not assert that this is the first time the logger is being installed.
     */
    @kotlin.jvm.Synchronized
    fun installRemoteLoggerForTesting(logger: java.util.concurrent.Future<java.util.logging.Logger?>?) {
        remoteLogger = logger
    }

    /** Returns the installed logger, or null if none is installed.  */
    @kotlin.jvm.Synchronized
    fun getRemoteLogger(): java.util.logging.Logger? {
        try {
            return if (remoteLogger == null) null else com.google.common.util.concurrent.Uninterruptibles.getUninterruptibly<java.util.logging.Logger?>(
                remoteLogger
            )
        } catch (e: ExecutionException) {
            throw java.lang.RuntimeException("Unexpected error initializing remote logging", e)
        }
    }

    /**
     * @see .logToRemote
     */
    fun logToRemote(level: java.util.logging.Level?, msg: String?, trace: Throwable?) {
        val logger: java.util.logging.Logger? = getRemoteLogger()
        if (logger != null) {
            logger.log(level, msg, trace)
        }
    }

    /**
     * Log a message to the remote backend. This is done out of thread, so this method is
     * non-blocking.
     * 
     * @param level The severity level. Non null.
     * @param msg The log message. Non null.
     * @param trace The stack trace. May be null.
     * @param values Additional values to upload.
     */
    fun logToRemote(level: java.util.logging.Level, msg: String?, trace: Throwable?, vararg values: String?) {
        val logger: java.util.logging.Logger? = getRemoteLogger()
        if (logger != null) {
            val logRecord: LogRecord = LogRecord(level, msg)
            logRecord.setThrown(trace)
            logRecord.setParameters(values)
            logger.log(logRecord)
        }
    }
}
