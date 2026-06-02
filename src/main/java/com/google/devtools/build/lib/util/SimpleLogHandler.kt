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

import com.google.devtools.build.lib.clock.Clock.now
import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import com.google.devtools.build.lib.util.LogHandlerQuerier
import com.google.devtools.build.lib.util.SingleLineFormatter
import com.google.devtools.build.lib.util.StringEncoding
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStreamWriter
import java.nio.file.LinkOption
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import java.util.logging.ErrorManager
import java.util.logging.LogRecord

/**
 * A simple file-based logging handler that provides an API for getting the current log file and
 * (optionally) in addition creates a short symlink to the current log file.
 * 
 * 
 * The log file path is concatenated from 4 elements: the prefix (a fixed string, typically a
 * directory); the pattern (allowing some % variable substitutions); the timestamp; and the
 * extension.
 * 
 * 
 * The handler can be configured from the JVM command line: `
 * -Djava.util.logging.config.file=/foo/bar/javalog.properties
` *  where the javalog.properties file might contain something like `
 * handlers=com.google.devtools.build.lib.util.SimpleLogHandler
 * com.google.devtools.build.lib.util.SimpleLogHandler.level=INFO
 * com.google.devtools.build.lib.util.SimpleLogHandler.prefix=/foo/bar/logs/java.log
 * com.google.devtools.build.lib.util.SimpleLogHandler.rotate_limit_bytes=1048576
 * com.google.devtools.build.lib.util.SimpleLogHandler.total_limit_bytes=10485760
 * com.google.devtools.build.lib.util.SimpleLogHandler.formatter=com.google.devtools.build.lib.util.SingleLineFormatter
` * 
 * 
 * 
 * The handler is thread-safe. IO operations ([.publish], [.flush], [.close])
 * and [.getCurrentLogFilePath] block other access to the handler until completed.
 */
class SimpleLogHandler private constructor(
    prefix: String?,
    pattern: String?,
    extension: String?,
    symlinkName: String?,
    createSymlink: Boolean?,
    rotateLimitBytes: Int?,
    totalLimit: Int?,
    logLevel: java.util.logging.Level?,
    formatter: java.util.logging.Formatter?,
    clock: java.time.Clock?
) : java.util.logging.Handler() {
    /** Max number of bytes to write before rotating the log.  */
    private val rotateLimitBytes: Int

    /** Max number of bytes in all logs to keep before deleting oldest ones.  */
    private val totalLimitBytes: Int

    /** Log file extension; the current process ID by default.  */
    private val extension: String?

    /** True if the log file extension is not the process ID.  */
    private val isStaticExtension: Boolean

    /**
     * Absolute path to symbolic link to current log file, or `Optional#empty()` if the link
     * should not be created.
     */
    private val symlinkPath: java.util.Optional<java.nio.file.Path?>

    /** Absolute path to common base name of log files.  */
    private val baseFilePath: java.nio.file.Path

    /** Log file currently in use.  */
    @javax.annotation.concurrent.GuardedBy("this")
    private val output: Output = com.google.devtools.build.lib.util.SimpleLogHandler.Output()

    /** Source for timestamps in filenames; non-static for testing.  */
    private val clock: java.time.Clock?

    /**
     * Timestamp format for log filenames; non-static because [SimpleDateFormat] is not
     * thread-safe.
     */
    @javax.annotation.concurrent.GuardedBy("this")
    private val timestampFormat: SimpleDateFormat =
        SimpleDateFormat(com.google.devtools.build.lib.util.SimpleLogHandler.Companion.DEFAULT_TIMESTAMP_FORMAT)

    /**
     * A [] LogHandlerQuerier for working with `SimpleLogHandler` instances.
     * 
     * 
     * This querier is intended for situations where the logging handler is configured on the JVM
     * command line to be [SimpleLogHandler], but where the code which needs to query the
     * handler does not know the handler's class or cannot import it. The command line then should in
     * addition specify `-Dcom.google.devtools.build.lib.util.LogHandlerQuerier.class=com.google.devtools.build.lib.util.SimpleLogHandler$HandlerQuerier`
     * and an instance of [SimpleLogHandler.HandlerQuerier] class can then be obtained from
     * `LogHandlerQuerier.getInstance()`.
     */
    class HandlerQuerier : LogHandlerQuerier() {
        override fun canQuery(handler: java.util.logging.Handler?): Boolean {
            return handler is SimpleLogHandler
        }

        override fun getLogHandlerFilePath(handler: java.util.logging.Handler): java.util.Optional<java.nio.file.Path?> {
            return (handler as SimpleLogHandler).currentLogFilePath
        }
    }

    /**
     * Builder class for [SimpleLogHandler].
     * 
     * 
     * All setters are optional; if unset, values from the JVM logging configuration or (if those
     * too are unset) reasonable fallback values will be used. See individual setter documentation.
     */
    class Builder {
        private var prefix: String? = null
        private var pattern: String? = null
        private var extension: String? = null
        private var symlinkName: String? = null
        private var createSymlink: Boolean? = null
        private var rotateLimitBytes: Int? = null
        private var totalLimitBytes: Int? = null
        private var logLevel: java.util.logging.Level? = null
        private var formatter: java.util.logging.Formatter? = null
        private var clock: java.time.Clock? = null

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setPrefix(prefix: String?): Builder {
            this.prefix = prefix
            return this
        }

        /**
         * Sets the pattern for the log file name. The pattern may contain the following variables:
         * 
         * 
         *  * `%u` will be expanded to the username
         *  * `%h` will be expanded to the hostname
         *  * `%%` will be expanded to %
         * 
         * 
         * 
         * The log file name will be constructed by appending the expanded pattern to the prefix and
         * then by appending a timestamp and the extension.
         * 
         * 
         * If unset, the value of "pattern" from the JVM logging configuration for [ ] will be used; and if that's unset, [.DEFAULT_BASE_FILE_NAME_PATTERN]
         * will be used.
         * 
         * @param pattern the pattern string, possibly containing `%u`, `%h`,
         * `%%` variables as above
         * @return this `Builder` object
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setPattern(pattern: String?): Builder {
            this.pattern = pattern
            return this
        }

        /**
         * Sets the log file extension.
         * 
         * 
         * If unset, the value of "extension" from the JVM logging configuration for [ ] will be used; and if that's unset, the process ID will be used.
         * 
         * @param extension log file extension
         * @return this `Builder` object
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExtension(extension: String?): Builder {
            this.extension = extension
            return this
        }

        /**
         * Sets the log file symlink filename.
         * 
         * 
         * If unset, the value of "symlink" from the JVM logging configuration for [ ] will be used; and if that's unset, the prefix will be used.
         * 
         * @param symlinkName either symlink filename without a directory part, or an absolute path
         * whose directory part matches the prefix
         * @return this `Builder` object
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setSymlinkName(symlinkName: String?): Builder {
            this.symlinkName = symlinkName
            return this
        }

        /**
         * Sets whether symlinks to the log file should be created.
         * 
         * 
         * If unset, the value of "create_symlink" from the JVM logging configuration for [ ] will be used; and if that's unset, the default behavior will depend on the
         * platform: false on Windows (because by default, only administrator accounts can create
         * symbolic links there) and true on other platforms.
         * 
         * @return this `Builder` object
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setCreateSymlink(createSymlink: Boolean): Builder {
            this.createSymlink = createSymlink
            return this
        }

        /**
         * Sets the log file size limit; if unset or 0, log size is unlimited.
         * 
         * 
         * If unset, the value of "rotate_limit_bytes" from the JVM logging configuration for [ ] will be used; and if that's unset, the log fie size is unlimited.
         * 
         * @param rotateLimitBytes maximum log file size in bytes; must be >= 0; 0 means unlimited
         * @return this `Builder` object
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setRotateLimitBytes(rotateLimitBytes: Int): Builder {
            this.rotateLimitBytes = rotateLimitBytes
            return this
        }

        /**
         * Sets the total rotateLimitBytes for log files.
         * 
         * 
         * If set, when opening a new handler or rotating a log file, the handler will scan for all
         * log files with names matching the expected prefix, pattern, timestamp format, and extension,
         * and delete the oldest ones to keep the total size under rotateLimitBytes.
         * 
         * 
         * If unset, the value of "total_limit_bytes" from the JVM logging configuration for [ ] will be used; and if that's unset, the total log size is unlimited.
         * 
         * @param totalLimitBytes maximum total log file size in bytes; must be >= 0; 0 means unlimited
         * @return this `Builder` object
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setTotalLimitBytes(totalLimitBytes: Int): Builder {
            this.totalLimitBytes = totalLimitBytes
            return this
        }

        /**
         * Sets the minimum level at which to log records.
         * 
         * 
         * If unset, the level named by the "level" field in the JVM logging configuration for [ ] will be used; and if that's unset, all records are logged.
         * 
         * @param logLevel minimum log level
         * @return this `Builder` object
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setLogLevel(logLevel: java.util.logging.Level?): Builder {
            this.logLevel = logLevel
            return this
        }

        /**
         * Sets the log formatter.
         * 
         * 
         * If unset, the class named by the "formatter" field in the JVM logging configuration for
         * [SimpleLogHandler] will be used; and if that's unset, [SingleLineFormatter] will
         * be used.
         * 
         * @param formatter log formatter
         * @return this `Builder` object
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setFormatter(formatter: java.util.logging.Formatter?): Builder {
            this.formatter = formatter
            return this
        }

        /**
         * Sets the time source for timestamps in log filenames.
         * 
         * 
         * Intended for testing. If unset, the system clock in the system timezone will be used.
         * 
         * @param clock time source for timestamps
         * @return this `Builder` object
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @com.google.common.annotations.VisibleForTesting
        fun setClockForTesting(clock: java.time.Clock?): Builder {
            this.clock = clock
            return this
        }

        /** Builds a [SimpleLogHandler] instance.  */
        fun build(): SimpleLogHandler {
            return com.google.devtools.build.lib.util.SimpleLogHandler(
                prefix,
                pattern,
                extension,
                symlinkName,
                createSymlink,
                rotateLimitBytes,
                totalLimitBytes,
                logLevel,
                formatter,
                clock
            )
        }
    }

    /**
     * Constructs a log handler with all state taken from the JVM logging configuration or (as
     * fallback) the defaults; see [SimpleLogHandler.Builder] documentation.
     * 
     * @throws IllegalArgumentException if invalid JVM logging configuration values are encountered;
     * see [SimpleLogHandler.Builder] documentation
     */
    constructor() : this(null, null, null, null, null, null, null, null, null, null)

    /**
     * Constructs a log handler, falling back to the JVM logging configuration or (as last fallback)
     * the defaults for those arguments which are null; see [SimpleLogHandler.Builder]
     * documentation.
     * 
     * @throws IllegalArgumentException if invalid non-null arguments or configured values are
     * encountered; see [SimpleLogHandler.Builder] documentation
     */
    init {
        this.baseFilePath =
            com.google.devtools.build.lib.util.SimpleLogHandler.Companion.getBaseFilePath(
                com.google.devtools.build.lib.util.SimpleLogHandler.Companion.getConfiguredStringProperty(
                    prefix,
                    "prefix",
                    com.google.devtools.build.lib.util.SimpleLogHandler.Companion.DEFAULT_PREFIX_STRING
                ),
                com.google.devtools.build.lib.util.SimpleLogHandler.Companion.getConfiguredStringProperty(
                    pattern,
                    "pattern",
                    com.google.devtools.build.lib.util.SimpleLogHandler.Companion.DEFAULT_BASE_FILE_NAME_PATTERN
                )
            )

        val configuredSymlinkName: String? =
            com.google.devtools.build.lib.util.SimpleLogHandler.Companion.getConfiguredStringProperty(
                symlinkName,
                "symlink",
                com.google.devtools.build.lib.util.SimpleLogHandler.Companion.getConfiguredStringProperty(
                    prefix,
                    "prefix",
                    com.google.devtools.build.lib.util.SimpleLogHandler.Companion.DEFAULT_PREFIX_STRING
                )
            )
        val configuredCreateSymlink: Boolean =
            com.google.devtools.build.lib.util.SimpleLogHandler.Companion.getConfiguredBooleanProperty(
                createSymlink,
                "create_symlink",
                com.google.devtools.build.lib.util.OS.Companion.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS
            )
        this.symlinkPath =
            if (configuredCreateSymlink)
                java.util.Optional.of<java.nio.file.Path?>(
                    com.google.devtools.build.lib.util.SimpleLogHandler.Companion.getSymlinkAbsolutePath(
                        this.baseFilePath.getParent(),
                        configuredSymlinkName
                    )
                )
            else
                java.util.Optional.empty<java.nio.file.Path?>()
        this.extension =
            com.google.devtools.build.lib.util.SimpleLogHandler.Companion.getConfiguredStringProperty(
                extension, "extension", java.lang.ProcessHandle.current().pid().toString()
            )
        this.isStaticExtension =
            (com.google.devtools.build.lib.util.SimpleLogHandler.Companion.getConfiguredStringProperty(
                extension,
                "extension",
                null
            ) != null)
        this.rotateLimitBytes = com.google.devtools.build.lib.util.SimpleLogHandler.Companion.getConfiguredIntProperty(
            rotateLimitBytes,
            "rotate_limit_bytes",
            0
        )
        com.google.common.base.Preconditions.checkArgument(
            this.rotateLimitBytes >= 0,
            "File size limits cannot be negative"
        )
        this.totalLimitBytes = com.google.devtools.build.lib.util.SimpleLogHandler.Companion.getConfiguredIntProperty(
            totalLimit,
            "total_limit_bytes",
            0
        )
        com.google.common.base.Preconditions.checkArgument(
            this.totalLimitBytes >= 0,
            "File size limits cannot be negative"
        )
        setLevel(
            com.google.devtools.build.lib.util.SimpleLogHandler.Companion.getConfiguredLevelProperty(
                logLevel,
                "level",
                java.util.logging.Level.ALL
            )
        )
        setFormatter(
            com.google.devtools.build.lib.util.SimpleLogHandler.Companion.getConfiguredFormatterProperty(
                formatter,
                "formatter",
                SingleLineFormatter()
            )
        )
        if (clock != null) {
            this.clock = clock
            this.timestampFormat.setTimeZone(TimeZone.getTimeZone(clock.getZone()))
        } else {
            this.clock = java.time.Clock.system(ZoneId.systemDefault())
        }
    }

    @get:kotlin.jvm.Synchronized
    val currentLogFilePath: java.util.Optional<java.nio.file.Path?>
        /**
         * Returns the absolute path of the current log file if a log file is open or `Optional#empty()` otherwise.
         * 
         * 
         * Since the log file is opened lazily, this method is expected to return `Optional#empty()` if no record has yet been published.
         */
        get() = if (output.isOpen) java.util.Optional.of<java.nio.file.Path?>(output.path) else java.util.Optional.empty<java.nio.file.Path?>()

    val symbolicLinkPath: java.util.Optional<java.nio.file.Path?>
        /**
         * Returns the expected absolute path for the symbolic link to the current log file, or `Optional#empty()` if not used.
         */
        get() = symlinkPath

    override fun isLoggable(record: LogRecord?): Boolean {
        return record != null && super.isLoggable(record)
    }

    @kotlin.jvm.Synchronized
    override fun publish(record: LogRecord?) {
        if (!isLoggable(record)) {
            // Silently ignore null or filtered records, matching FileHandler behavior.
            return
        }

        // This allows us to do the I/O while not forgetting that we were interrupted.
        var isInterrupted: Boolean = java.lang.Thread.interrupted()
        try {
            val message: String? = getFormatter().format(record)
            openOutputIfNeeded()
            output.write(message)
        } catch (e: java.lang.Exception) {
            reportError(null, e, ErrorManager.WRITE_FAILURE)
            // Failing to log is non-fatal. Continue to try to rotate the log if necessary, which may fix
            // the underlying IO problem with the file.
            if (e is InterruptedIOException) {
                isInterrupted = true
            }
        }

        try {
            if (rotateLimitBytes > 0) {
                output.closeIfByteCountAtleast(rotateLimitBytes)
                openOutputIfNeeded()
            }
        } catch (e: IOException) {
            reportError("Failed to rotate log file", e, ErrorManager.GENERIC_FAILURE)
            if (e is InterruptedIOException) {
                isInterrupted = true
            }
        }
        if (isInterrupted) {
            java.lang.Thread.currentThread().interrupt()
        }
    }

    @kotlin.jvm.Synchronized
    override fun flush() {
        var isInterrupted: Boolean = java.lang.Thread.interrupted()
        if (output.isOpen) {
            try {
                output.flush()
            } catch (e: IOException) {
                reportError(null, e, ErrorManager.FLUSH_FAILURE)
                if (e is InterruptedIOException) {
                    isInterrupted = true
                }
            }
        }
        if (isInterrupted) {
            java.lang.Thread.currentThread().interrupt()
        }
    }

    @kotlin.jvm.Synchronized
    override fun close() {
        var isInterrupted: Boolean = java.lang.Thread.interrupted()
        if (output.isOpen) {
            try {
                output.write(getFormatter().getTail(this))
            } catch (e: IOException) {
                reportError("Failed to write log tail", e, ErrorManager.WRITE_FAILURE)
                if (e is InterruptedIOException) {
                    isInterrupted = true
                }
            }

            try {
                output.close()
            } catch (e: IOException) {
                reportError(null, e, ErrorManager.CLOSE_FAILURE)
                if (e is InterruptedIOException) {
                    isInterrupted = true
                }
            }
        }
        if (isInterrupted) {
            java.lang.Thread.currentThread().interrupt()
        }
    }

    private class Output {
        /** Log file currently in use.  */
        private var file: java.nio.file.Path? = null

        /** Output stream for [.file] which counts the number of bytes written.  */
        private var stream: com.google.common.io.CountingOutputStream? = null

        /** Writer for [.stream].  */
        private var writer: OutputStreamWriter? = null

        val isOpen: Boolean
            get() = writer != null

        /**
         * Opens the specified file in append mode, first closing the current file if needed.
         * 
         * @throws IOException if the file could not be opened
         */
        @Throws(IOException::class)
        fun open(file: java.nio.file.Path) {
            try {
                close()
                this.file = file
                stream = com.google.common.io.CountingOutputStream(FileOutputStream(file.toFile(), true))
                writer = OutputStreamWriter(stream, java.nio.charset.StandardCharsets.ISO_8859_1)
            } catch (e: IOException) {
                close()
                throw e
            }
        }

        val path: java.nio.file.Path?
            /**
             * Returns the currently open file's path.
             * 
             * @throws NullPointerException if not open
             */
            get() = file

        /**
         * Writes the string to the current file in UTF-8 encoding.
         * 
         * @throws NullPointerException if not open
         * @throws IOException if an underlying IO operation failed
         */
        @Throws(IOException::class)
        fun write(string: String?) {
            writer.write(string)
        }

        /**
         * Flushes the current file.
         * 
         * @throws NullPointerException if not open
         * @throws IOException if an underlying IO operation failed
         */
        @Throws(IOException::class)
        fun flush() {
            writer.flush()
        }

        /**
         * Closes the current file if it is open.
         * 
         * @throws IOException if an underlying IO operation failed
         */
        @Throws(IOException::class)
        fun close() {
            try {
                if (this.isOpen) {
                    writer.close()
                }
            } finally {
                writer = null
                stream = null
                file = null
            }
        }

        /**
         * Closes the current file unless the number of bytes written to it was under the specified
         * limit.
         * 
         * @throws NullPointerException if not open
         * @throws IOException if an underlying IO operation failed
         */
        @Throws(IOException::class)
        fun closeIfByteCountAtleast(limit: Int) {
            if (stream.getCount() < limit && stream.getCount() + 8192L >= limit) {
                // The writer and its internal encoder buffer output before writing to the output stream.
                // The default size of the encoder's buffer is 8192 bytes. To count the bytes in the output
                // stream accurately, we have to flush. But flushing unnecessarily harms performance; let's
                // flush only when it matters - per record and within expected buffer size from the limit.
                flush()
            }
            if (stream.getCount() >= limit) {
                close()
            }
        }
    }

    /**
     * Opens a new log file if one is not open, updating the symbolic link and deleting old logs if
     * needed.
     */
    @javax.annotation.concurrent.GuardedBy("this")
    private fun openOutputIfNeeded() {
        if (!output.isOpen) {
            // Ensure the log file's directory exists.
            com.google.common.base.Preconditions.checkState(baseFilePath.isAbsolute())
            baseFilePath.getParent().toFile().mkdirs()

            try {
                output.open(
                    java.nio.file.Path.of(
                        baseFilePath.toString() + timestampFormat.format(java.util.Date.from(Instant.now(clock))) + extension
                    )
                )
                output.write(getFormatter().getHead(this))
            } catch (e: IOException) {
                try {
                    output.close()
                } catch (eClose: IOException) {
                    // Already handling a prior IO failure.
                }
                reportError("Failed to open log file", e, ErrorManager.OPEN_FAILURE)
                return
            }

            if (totalLimitBytes > 0) {
                deleteOldLogs()
            }

            // Try to create relative symlink from currentLogFile to baseFile, but don't treat a failure
            // as fatal.
            if (symlinkPath.isPresent()) {
                try {
                    com.google.common.base.Preconditions.checkState(
                        symlinkPath.get().getParent() == output.path.getParent()
                    )
                    if (java.nio.file.Files.exists(symlinkPath.get(), LinkOption.NOFOLLOW_LINKS)) {
                        java.nio.file.Files.delete(symlinkPath.get())
                    }
                    java.nio.file.Files.createSymbolicLink(symlinkPath.get(), output.path.getFileName())
                } catch (e: IOException) {
                    reportError(
                        "Failed to create symbolic link to log file", e, ErrorManager.GENERIC_FAILURE
                    )
                }
            }
        }
    }

    /**
     * Parses the absolute path of a logfile (e.g from a previous run of the program) and extracts the
     * timestamp.
     * 
     * @throws ParseException if the path does not match the expected prefix, resolved pattern,
     * timestamp format, or extension
     */
    @javax.annotation.concurrent.GuardedBy("this")
    @Throws(java.text.ParseException::class)
    private fun parseLogFileTimestamp(path: java.nio.file.Path): java.util.Date {
        val pathString = path.toString()
        if (!pathString.startsWith(baseFilePath.toString())) {
            throw java.text.ParseException("Wrong prefix or pattern", 0)
        }
        val parsePosition: ParsePosition = ParsePosition(baseFilePath.toString().length)
        val timestamp: java.util.Date = timestampFormat.parse(pathString, parsePosition)
        if (timestamp == null) {
            throw java.text.ParseException("Wrong timestamp format", parsePosition.getErrorIndex())
        }
        if (isStaticExtension) {
            if (pathString.substring(parsePosition.getIndex()) != extension) {
                throw java.text.ParseException("Wrong file extension", parsePosition.getIndex())
            }
        } else {
            try {
                pathString.substring(parsePosition.getIndex()).toLong()
            } catch (e: java.lang.NumberFormatException) {
                throw java.text.ParseException("File extension is not a numeric PID", parsePosition.getIndex())
            }
        }
        return timestamp
    }

    /** File path ordered by timestamp.  */
    private class PathByTimestamp(path: java.nio.file.Path?, timestamp: java.util.Date, size: Long) :
        Comparable<PathByTimestamp?> {
        private val path: java.nio.file.Path?
        private val timestamp: java.util.Date
        val size: Long

        init {
            this.path = path
            this.timestamp = timestamp
            this.size = size
        }

        fun getPath(): java.nio.file.Path? {
            return path
        }

        override fun compareTo(rhs: PathByTimestamp): Int {
            return this.timestamp.compareTo(rhs.timestamp)
        }
    }

    /**
     * Deletes the oldest log files matching the expected prefix, pattern, timestamp format, and
     * extension, to keep the total size under [.totalLimitBytes] (if set to non-0).
     * 
     * 
     * Each log file's timestamp is determined only from the filename. The current log file will
     * not be deleted.
     */
    @javax.annotation.concurrent.GuardedBy("this")
    private fun deleteOldLogs() {
        com.google.common.base.Preconditions.checkState(baseFilePath.isAbsolute())
        val queue: java.util.PriorityQueue<PathByTimestamp> = java.util.PriorityQueue<PathByTimestamp>()
        var totalSize: Long = 0
        try {
            java.nio.file.Files.newDirectoryStream(baseFilePath.getParent()).use { dirStream ->
                for (path in dirStream) {
                    try {
                        val timestamp: java.util.Date = parseLogFileTimestamp(path)
                        val size: Long = java.nio.file.Files.size(path)
                        totalSize += size
                        if (output.path != path) {
                            queue.add(PathByTimestamp(path, timestamp, size))
                        }
                    } catch (e: java.text.ParseException) {
                        // Ignore files which don't look like our logs.
                    }
                }
                if (totalLimitBytes > 0) {
                    while (totalSize > totalLimitBytes && !queue.isEmpty()) {
                        val entry: PathByTimestamp = queue.poll()
                        java.nio.file.Files.delete(entry.getPath())
                        totalSize -= entry.size
                    }
                }
            }
        } catch (e: IOException) {
            reportError("Failed to clean up old log files", e, ErrorManager.GENERIC_FAILURE)
        }
    }

    companion object {
        private const val DEFAULT_PREFIX_STRING = "java.log"
        private const val DEFAULT_BASE_FILE_NAME_PATTERN = ".%h.%u.log.java."

        @com.google.common.annotations.VisibleForTesting
        const val DEFAULT_TIMESTAMP_FORMAT: String = "yyyyMMdd-HHmmss."

        /** Creates a new [Builder].  */
        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return com.google.devtools.build.lib.util.SimpleLogHandler.Builder()
        }

        /**
         * Checks if a value is null, and if it is, falls back to the JVM logging configuration, and if
         * that too is missing, to a provided fallback value.
         * 
         * @param builderValue possibly null value provided by the caller, e.g. from [     ]
         * @param configuredName field name in the JVM logging configuration for [SimpleLogHandler]
         * @param parse parser for the string value from the JVM logging configuration
         * @param fallbackValue fallback to use if the `builderValue` is null and no value is
         * configured in the JVM logging configuration
         * @param <T> value type
        </T> */
        private fun <T> getConfiguredProperty(
            builderValue: T?,
            configuredName: String?,
            parse: java.util.function.Function<String?, T?>,
            fallbackValue: T?
        ): T? {
            if (builderValue != null) {
                return builderValue
            }

            // .properties files are read as Latin-1 by java.util.Properties, with Unicode escape sequences
            // interpreted. Since the Bazel client passes path properties as UTF-8 without escaping,
            // configuredValue already contains a string in Bazel's internal string encoding (see
            // StringEncoding).
            val configuredValue: String? =
                java.util.logging.LogManager.getLogManager()
                    .getProperty(com.google.devtools.build.lib.util.SimpleLogHandler::class.java.getName() + "." + configuredName)
            if (configuredValue != null) {
                return parse.apply(configuredValue)
            }
            return fallbackValue
        }

        /** Matches java.logging.* configuration behavior; configured strings are trimmed.  */
        private fun getConfiguredStringProperty(
            builderValue: String?, configuredName: String?, fallbackValue: String?
        ): String? {
            return com.google.devtools.build.lib.util.SimpleLogHandler.Companion.getConfiguredProperty<String?>(
                builderValue,
                configuredName,
                java.util.function.Function { obj: String? -> obj.trim { it <= ' ' } },
                fallbackValue
            )
        }

        /**
         * Matches java.logging.* configuration behavior; "true" and "1" are true, "false" and "0" are
         * false.
         * 
         * @throws IllegalArgumentException if the configured boolean property cannot be parsed
         */
        private fun getConfiguredBooleanProperty(
            builderValue: Boolean?, configuredName: String?, fallbackValue: Boolean
        ): Boolean {
            val value: Boolean? =
                com.google.devtools.build.lib.util.SimpleLogHandler.Companion.getConfiguredProperty<Boolean?>(
                    builderValue,
                    configuredName,
                    java.util.function.Function { `val`: String? ->
                        var `val` = `val`
                        `val` = `val`.trim { it <= ' ' }.lowercase(Locale.getDefault())
                        if ("true" == `val` || "1" == `val`) {
                            return@getConfiguredProperty true
                        } else if ("false" == `val` || "0" == `val`) {
                            return@getConfiguredProperty false
                        } else if (`val`.isEmpty()) {
                            return@getConfiguredProperty null
                        }
                        throw java.lang.IllegalArgumentException("Cannot parse boolean property value")
                    },
                    null
                )
            return if (value != null) value else fallbackValue
        }

        /**
         * Empty configured values are ignored and the fallback is used instead.
         * 
         * @throws NumberFormatException if the configured formatter value is non-numeric
         */
        private fun getConfiguredIntProperty(
            builderValue: Int?, configuredName: String?, fallbackValue: Int
        ): Int {
            val value: Int? =
                com.google.devtools.build.lib.util.SimpleLogHandler.Companion.getConfiguredProperty<Int?>(
                    builderValue,
                    configuredName,
                    java.util.function.Function { `val`: String? ->
                        var `val` = `val`
                        `val` = `val`.trim { it <= ' ' }
                        if (!`val`.isEmpty()) `val`.toInt() else null
                    },
                    null
                )
            return if (value != null) value else fallbackValue
        }

        /**
         * Empty configured values are ignored and the fallback is used instead.
         * 
         * @throws IllegalArgumentException if the configured level name cannot be parsed
         */
        private fun getConfiguredLevelProperty(
            builderValue: java.util.logging.Level?, configuredName: String?, fallbackValue: java.util.logging.Level?
        ): java.util.logging.Level? {
            val value: java.util.logging.Level? =
                com.google.devtools.build.lib.util.SimpleLogHandler.Companion.getConfiguredProperty<java.util.logging.Level?>(
                    builderValue,
                    configuredName,
                    java.util.function.Function { `val`: String? ->
                        var `val` = `val`
                        `val` = `val`.trim { it <= ' ' }
                        if (!`val`.isEmpty()) java.util.logging.Level.parse(`val`) else null
                    },
                    null
                )
            return if (value != null) value else fallbackValue
        }

        /**
         * Empty configured values are ignored and the fallback is used instead.
         * 
         * @throws IllegalArgumentException if a formatter object cannot be instantiated from the
         * configured class name
         */
        private fun getConfiguredFormatterProperty(
            builderValue: java.util.logging.Formatter?,
            configuredName: String?,
            fallbackValue: java.util.logging.Formatter?
        ): java.util.logging.Formatter? {
            return com.google.devtools.build.lib.util.SimpleLogHandler.Companion.getConfiguredProperty<java.util.logging.Formatter?>(
                builderValue,
                configuredName,
                java.util.function.Function { `val`: String? ->
                    var `val` = `val`
                    `val` = `val`.trim { it <= ' ' }
                    if (!`val`.isEmpty()) {
                        try {
                            return@getConfiguredProperty java.lang.Class.forName(
                                `val`,
                                true,
                                com.google.devtools.build.lib.util.SimpleLogHandler::class.java.getClassLoader()
                            )
                                .asSubclass<java.util.logging.Formatter?>(java.util.logging.Formatter::class.java)
                                .getDeclaredConstructor()
                                .newInstance() as java.util.logging.Formatter?
                        } catch (e: java.lang.ReflectiveOperationException) {
                            throw java.lang.IllegalArgumentException(e)
                        }
                    } else {
                        return@getConfiguredProperty fallbackValue
                    }
                },
                fallbackValue
            )
        }

        @kotlin.jvm.JvmStatic
        @get:com.google.common.annotations.VisibleForTesting
        val localHostnameFirstComponent: String?
            get() {
                var name: String = com.google.devtools.build.lib.util.NetUtil.getCachedShortHostName()
                if (!com.google.common.net.InetAddresses.isInetAddress(name)) {
                    // Keep only the first component of the name.
                    val firstDot: Int = name.indexOf('.')
                    if (firstDot >= 0) {
                        name = name.substring(0, firstDot)
                    }
                }
                return name.lowercase(Locale.getDefault())
            }

        /**
         * Creates the log file absolute base path according to the given pattern.
         * 
         * @param prefix non-null string to prepend to the base path
         * @param pattern non-null string which may include the following variables: %h will be expanded
         * to the hostname; %u will be expanded to the username; %% will be expanded to %
         * @throws IllegalArgumentException if an unknown variable is encountered in the pattern
         */
        private fun getBaseFilePath(prefix: String?, pattern: String?): java.nio.file.Path {
            com.google.common.base.Preconditions.checkNotNull<String?>(prefix, "prefix")
            com.google.common.base.Preconditions.checkNotNull<String?>(pattern, "pattern")

            val sb: java.lang.StringBuilder = java.lang.StringBuilder(100) // Typical name is < 100 bytes
            var inVar = false
            var username: String? = StringEncoding.platformToInternal(java.lang.System.getProperty("user.name"))

            if (com.google.common.base.Strings.isNullOrEmpty(username)) {
                username = "unknown_user"
            }

            sb.append(prefix)

            for (i in 0..<pattern!!.length) {
                val c = pattern.get(i)
                if (inVar) {
                    inVar = false
                    when (c) {
                        '%' -> sb.append('%')
                        'h' -> sb.append(com.google.devtools.build.lib.util.SimpleLogHandler.Companion.getLocalHostnameFirstComponent())
                        'u' -> sb.append(username)
                        else -> throw java.lang.IllegalArgumentException("Unknown variable " + c + " in " + pattern)
                    }
                } else {
                    if (c == '%') {
                        inVar = true
                    } else {
                        sb.append(c)
                    }
                }
            }

            return java.nio.file.Path.of(StringEncoding.internalToPlatform(sb.toString())).toAbsolutePath()
        }

        /**
         * Returns the absolute path for a symlink in the specified directory.
         * 
         * @throws IllegalArgumentException if the symlink includes a directory component which doesn't
         * equal `logDir`
         */
        private fun getSymlinkAbsolutePath(logDir: java.nio.file.Path, symlink: String?): java.nio.file.Path {
            com.google.common.base.Preconditions.checkNotNull<String?>(symlink)
            com.google.common.base.Preconditions.checkArgument(!symlink.isEmpty())
            var symlinkPath: java.nio.file.Path = java.nio.file.Path.of(StringEncoding.internalToPlatform(symlink))
            if (!symlinkPath.isAbsolute()) {
                symlinkPath = logDir.resolve(symlinkPath)
            }
            com.google.common.base.Preconditions.checkArgument(
                symlinkPath.getParent() == logDir, "symlink is not a top-level file in logDir"
            )
            return symlinkPath
        }
    }
}
