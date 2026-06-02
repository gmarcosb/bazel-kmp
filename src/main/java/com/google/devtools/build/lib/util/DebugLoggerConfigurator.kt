// Copyright 2020 The Bazel Authors. All rights reserved.
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

/**
 * Utility to handle low-level interactions with debug ("info") logging. While actual logging is
 * done with the `GoogleLogger` class, getting at internals is easier with the native [ ] object.
 */
object DebugLoggerConfigurator {
    // Make sure we keep a strong reference to this logger, so that the
    // configuration isn't lost when the gc kicks in.
    private val templateLogger: java.util.logging.Logger =
        java.util.logging.Logger.getLogger("com.google.devtools.build")
    private var currentVerbosityLevel: java.util.logging.Level? = null

    /** Configures "com.google.devtools.build.*" loggers to the given `level`.  */
    fun setupLogging(level: java.util.logging.Level) {
        if (level != currentVerbosityLevel) {
            templateLogger.setLevel(level)
            templateLogger.info("Log level: " + templateLogger.getLevel())
            currentVerbosityLevel = level
        }
    }

    /** Flushes all loggers at com.google.devtools.build.* or higher.  */
    fun flushServerLog() {
        var logger: java.util.logging.Logger? = templateLogger
        while (logger != null) {
            for (handler in logger.getHandlers()) {
                if (handler != null) {
                    handler.flush()
                }
            }
            logger = logger.getParent()
        }
    }
}
