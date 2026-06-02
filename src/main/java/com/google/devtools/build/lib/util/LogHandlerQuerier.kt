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
import java.io.IOException

/**
 * A retriever for logging handler properties, e.g. the log file path.
 * 
 * 
 * A querier is intended for situations where a logging handler is configured on the JVM command
 * line, and where the code which needs to query the handler does not know the handler's class or
 * cannot import it. The command line then should in addition specify an appropriate child class of
 * [LogHandlerQuerier] via the `-Dcom.google.devtools.build.lib.util.LogHandlerQuerier.class` flag, and an instance of that
 * appropriate child class can be obtained from `LogHandlerQuerier.getInstance()`.
 */
abstract class LogHandlerQuerier {
    /**
     * Returns a logger's handler's log file path, iterating through all handlers and the logger's
     * ancestors' handlers as necessary.
     * 
     * 
     * The method will stop iterating at the first log handler that it can query, returning the log
     * path if it is available for that log handler, or an empty [Optional] if the log file for
     * that handler is currently unavailable.
     * 
     * @param logger a logger whose handlers, and ancestors' handlers if necessary, will be queried
     * @throws IOException if the [LogHandlerQuerier] cannot query any [Handler] for this
     * logger or its ancestors
     */
    @com.google.common.annotations.VisibleForTesting
    @Throws(IOException::class)
    fun getLoggerFilePath(logger: java.util.logging.Logger?): java.util.Optional<java.nio.file.Path?>? {
        var logger: java.util.logging.Logger? = logger
        while (logger != null) {
            for (handler in logger.getHandlers()) {
                if (canQuery(handler)) {
                    return getLogHandlerFilePath(handler)
                }
            }
            logger = logger.getParent()
        }
        throw IOException("Failed to find a queryable logging handler")
    }

    /** Checks if this [LogHandlerQuerier] can query the given handler.  */
    @com.google.errorprone.annotations.ForOverride
    protected abstract fun canQuery(handler: java.util.logging.Handler?): Boolean

    /**
     * Returns a logging handler's log file path.
     * 
     * @param handler logging handler to query
     * @return the log handler's log file path if the log file is currently available
     */
    @com.google.errorprone.annotations.ForOverride
    protected abstract fun getLogHandlerFilePath(handler: java.util.logging.Handler?): java.util.Optional<java.nio.file.Path?>?

    private class ReflectiveOperationRuntimeException(
        message: String?,
        exception: java.lang.ReflectiveOperationException?
    ) : java.lang.RuntimeException(message, exception)

    companion object {
        private val configuredInstanceSupplier: java.util.function.Supplier<LogHandlerQuerier?> =
            com.google.common.base.Suppliers.memoize<LogHandlerQuerier?>(com.google.common.base.Supplier { makeConfiguredInstance() })

        // Morally visible only for testing.
        protected val PROPERTY_NAME: String = LogHandlerQuerier::class.java.getName() + ".class"

        private fun makeConfiguredInstance(): LogHandlerQuerier? {
            val subclassName: String? = java.lang.System.getProperty(PROPERTY_NAME)
            com.google.common.base.Preconditions.checkNotNull<String?>(
                subclassName,
                "System property %s is not defined",
                PROPERTY_NAME
            )
            try {
                return java.lang.Class.forName(subclassName, true, LogHandlerQuerier::class.java.getClassLoader())
                    .asSubclass<LogHandlerQuerier?>(LogHandlerQuerier::class.java)
                    .getDeclaredConstructor()
                    .newInstance()
            } catch (e: java.lang.ReflectiveOperationException) {
                throw ReflectiveOperationRuntimeException(
                    "System property " + PROPERTY_NAME + " value is invalid", e
                )
            }
        }

        @get:Throws(IOException::class)
        val configuredInstance: LogHandlerQuerier?
            /**
             * Returns the singleton instance of the LogHandlerQuerier child class which was configured as a
             * system property on the JVM command line via the `-Dcom.google.devtools.build.lib.util.LogHandlerQuerier.class` flag.
             * 
             * 
             * This method is thread-safe.
             * 
             * @throws IOException if the JVM property was not defined or if an instance of the class named by
             * the property could not be constructed
             */
            get() {
                try {
                    return configuredInstanceSupplier.get()
                } catch (e: ReflectiveOperationRuntimeException) {
                    throw IOException("Could not find a querier for server log location", e.cause)
                }
            }
    }
}
