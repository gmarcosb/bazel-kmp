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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import com.google.devtools.build.lib.util.LogHandlerQuerier
import java.nio.file.Paths
import java.util.logging.FileHandler
import java.util.logging.LogRecord

/**
 * A [LogHandlerQuerier] for working with [java.util.logging.FileHandler] instances.
 * 
 * 
 * This querier is intended for situations where the logging handler is configured on the JVM
 * command line to be [java.util.logging.FileHandler], but where the code which needs to query
 * the handler does not know the handler's class or cannot import it. The command line then should
 * in addition specify `-Dcom.google.devtools.build.lib.util.LogHandlerQuerier.class=com.google.devtools.build.lib.util.FileHandlerQuerier`
 * and an instance of FileHandlerQuerier class can then be obtained from `LogHandlerQuerier.getInstance()`.
 * 
 * 
 * Due to limitations of java.util.logging API, this querier only supports obtaining the log file
 * path when it's specified in java.util.logging.config with no % variables.
 * 
 * 
 * TODO: is intended that this class be removed once Bazel is no longer using
 * [java.util.logging.FileHandler].
 */
class FileHandlerQuerier @com.google.common.annotations.VisibleForTesting internal constructor(logManagerSupplier: com.google.common.base.Supplier<java.util.logging.LogManager?>) :
    LogHandlerQuerier() {
    /** Wrapper around LogManager.getLogManager() for testing.  */
    private val logManagerSupplier: com.google.common.base.Supplier<java.util.logging.LogManager?>

    init {
        this.logManagerSupplier = logManagerSupplier
    }

    constructor() : this(com.google.common.base.Supplier { java.util.logging.LogManager.getLogManager() })

    override fun canQuery(handler: java.util.logging.Handler?): Boolean {
        return handler is FileHandler
    }

    override fun getLogHandlerFilePath(handler: java.util.logging.Handler): java.util.Optional<java.nio.file.Path?> {
        // Hack: java.util.logging.FileHandler has no API for getting the current file path. Instead, we
        // try to parse the configured path and check that it has no % variables.
        val pattern: String = logManagerSupplier.get().getProperty("java.util.logging.FileHandler.pattern")
        checkNotNull(pattern) { "java.util.logging.config property java.util.logging.FileHandler.pattern is undefined" }
        check(!pattern.matches(".*%[thgu].*".toRegex())) {
            ("resolving %-coded variables in java.util.logging.config property "
                    + "java.util.logging.FileHandler.pattern is not supported")
        }
        val path: java.nio.file.Path = Paths.get(pattern.trim { it <= ' ' })

        // Hack: java.util.logging.FileHandler has no API for checking if a log file is currently open.
        // Instead, we try to query whether the handler can log a SEVERE level record - which for
        // expected configurations should be true iff a log file is open.
        if (!handler.isLoggable(LogRecord(java.util.logging.Level.SEVERE, ""))) {
            return java.util.Optional.empty<java.nio.file.Path?>()
        }
        return java.util.Optional.of<java.nio.file.Path?>(path)
    }
}
