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

import com.google.devtools.build.lib.util.SimpleLogHandler.HandlerQuerier

/** Tests for the [SimpleLogHandler] class.  */
@RunWith(JUnit4::class)
class SimpleLogHandlerTest {
    @org.junit.Rule
    var tmp: TemporaryFolder = TemporaryFolder()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrefix() {
        val handler: SimpleLogHandler =
            SimpleLogHandler.builder()
                .setPrefix(tmp.getRoot().toString() + java.io.File.separator + "hello_world_%u%h%%_")
                .build()
        handler.publish(LogRecord(java.util.logging.Level.SEVERE, "Hello world")) // To open the log file.
        assertThat(handler.getCurrentLogFilePath().get().toString())
            .startsWith(tmp.getRoot().toString() + java.io.File.separator + "hello_world_%u%h%%_")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPatternVariables() {
        var username: String? = java.lang.System.getProperty("user.name")
        if (com.google.common.base.Strings.isNullOrEmpty(username)) {
            username = "unknown_user"
        }
        val hostname: String? = SimpleLogHandler.getLocalHostnameFirstComponent()

        val handler: SimpleLogHandler =
            SimpleLogHandler.builder()
                .setPrefix(tmp.getRoot().toString() + java.io.File.separator + "hello_")
                .setPattern("world_%u%%%h_")
                .build()
        handler.publish(LogRecord(java.util.logging.Level.SEVERE, "Hello world")) // To open the log file.
        assertThat(handler.getCurrentLogFilePath().get().toString())
            .startsWith(
                tmp.getRoot().toString() + java.io.File.separator + "hello_world_" + username + "%" + hostname + "_"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPatternInvalidVariable() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { SimpleLogHandler.builder().setPattern("hello_%t").build() })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExtensionDefaults() {
        val handler: SimpleLogHandler =
            SimpleLogHandler.builder().setPrefix(tmp.getRoot().toString() + java.io.File.separator + "hello").build()
        handler.publish(LogRecord(java.util.logging.Level.SEVERE, "Hello world")) // To open the log file.
        assertThat(handler.getCurrentLogFilePath().get().toString())
            .endsWith("." + java.lang.ProcessHandle.current().pid())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExtensionSetter() {
        val handler1: SimpleLogHandler =
            SimpleLogHandler.builder()
                .setPrefix(tmp.getRoot().toString() + java.io.File.separator + "hello")
                .setExtension("xyz")
                .build()
        handler1.publish(LogRecord(java.util.logging.Level.SEVERE, "Hello world")) // To open the log file.
        assertThat(handler1.getCurrentLogFilePath().get().toString()).endsWith(".xyz")
    }

    private class FakeClock(now: Instant?, zone: ZoneId?) : java.time.Clock() {
        private var now: Instant?
        private val zone: ZoneId?

        init {
            this.now = now
            this.zone = zone
        }

        fun set(now: Instant?) {
            this.now = now
        }

        override fun instant(): Instant? {
            return now
        }

        override fun getZone(): ZoneId? {
            return zone
        }

        override fun withZone(zone: ZoneId?): java.time.Clock {
            return FakeClock(this.now, zone)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTimestamp() {
        val instant: Instant = Instant.parse("2015-09-01T15:17:54Z")
        val clock = FakeClock(instant, ZoneOffset.UTC)
        val dateFormat: SimpleDateFormat = SimpleDateFormat(SimpleLogHandler.DEFAULT_TIMESTAMP_FORMAT)
        dateFormat.setTimeZone(TimeZone.getTimeZone(ZoneOffset.UTC))
        val handler: SimpleLogHandler =
            SimpleLogHandler.builder()
                .setPrefix(tmp.getRoot().toString() + java.io.File.separator + "hello")
                .setClockForTesting(clock)
                .build()
        handler.publish(LogRecord(java.util.logging.Level.SEVERE, "Hello world")) // To open the log file.

        Truth.assertThat(dateFormat.format(java.util.Date.from(instant))).isEqualTo("20150901-151754.")
        com.google.common.truth.Subject.contains("20150901-151754.")
    }

    private class TrivialFormatter : java.util.logging.Formatter() {
        override fun format(rec: LogRecord?): String {
            return formatMessage(rec) + "\n"
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPublish() {
        val handler: SimpleLogHandler =
            SimpleLogHandler.builder()
                .setPrefix(tmp.getRoot().toString() + java.io.File.separator + "hello")
                .setFormatter(TrivialFormatter())
                .build()
        handler.publish(LogRecord(java.util.logging.Level.SEVERE, "Hello world")) // To open the log file.
        val logPath: Path = handler.getCurrentLogFilePath().get()
        handler.close()

        Truth.assertThat(java.nio.file.Files.readString(logPath)).isEqualTo("Hello world\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicLinkDefaults() {
        val symlinkPath: Path = Paths.get(tmp.getRoot().toString(), "hello")
        java.nio.file.Files.createFile(symlinkPath)

        // On non-Windows platforms, expect to delete the file at symlinkPath and replace with a symlink
        // to the log.
        val handler: SimpleLogHandler = SimpleLogHandler.builder().setPrefix(symlinkPath.toString()).build()
        handler.publish(LogRecord(java.util.logging.Level.SEVERE, "Hello world")) // To open the log file.

        if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) {
            // On Windows, by default, only administrator accounts can create symbolic links.
            assertThat(handler.getSymbolicLinkPath()).isEmpty()
        } else {
            assertThat(handler.getSymbolicLinkPath()).isPresent()
            assertThat(handler.getSymbolicLinkPath().get().toString()).isEqualTo(symlinkPath.toString())
            Truth.assertThat(java.nio.file.Files.isSymbolicLink(handler.getSymbolicLinkPath().get())).isTrue()
            Truth.assertThat(java.nio.file.Files.readSymbolicLink(handler.getSymbolicLinkPath().get()).toString())
                .isEqualTo(handler.getCurrentLogFilePath().get().getFileName().toString())
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicLinkSetter() {
        val handler: SimpleLogHandler =
            SimpleLogHandler.builder()
                .setPrefix(tmp.getRoot().toString() + java.io.File.separator + "hello")
                .setSymlinkName("bye")
                .build()
        handler.publish(LogRecord(java.util.logging.Level.SEVERE, "Hello world")) // To open the log file.

        if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) {
            // On Windows, by default, only administrator accounts can create symbolic links.
            assertThat(handler.getSymbolicLinkPath()).isEmpty()
        } else {
            assertThat(handler.getSymbolicLinkPath()).isPresent()
            assertThat(handler.getSymbolicLinkPath().get().toString())
                .isEqualTo(tmp.getRoot().toString() + java.io.File.separator + "bye")
            Truth.assertThat(java.nio.file.Files.isSymbolicLink(handler.getSymbolicLinkPath().get())).isTrue()
            Truth.assertThat(java.nio.file.Files.readSymbolicLink(handler.getSymbolicLinkPath().get()).toString())
                .isEqualTo(handler.getCurrentLogFilePath().get().getFileName().toString())
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkEnabling() {
        val handler: SimpleLogHandler =
            SimpleLogHandler.builder()
                .setPrefix(tmp.getRoot().toString() + java.io.File.separator + "hello")
                .setSymlinkName("bye")
                .setCreateSymlink(true)
                .build()
        assertThat(handler.getSymbolicLinkPath()).isPresent()
    }

    @org.junit.Test
    fun testSymlinkDisabling() {
        val handler: SimpleLogHandler =
            SimpleLogHandler.builder()
                .setPrefix(tmp.getRoot().toString() + java.io.File.separator + "hello")
                .setSymlinkName("bye")
                .setCreateSymlink(false)
                .build()
        assertThat(handler.getSymbolicLinkPath()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicLinkInvalidPath() {
        // "bye/bye" is invalid as a symlink path - it's not at the top level of log directory.
        val builder: SimpleLogHandler.Builder =
            SimpleLogHandler.builder()
                .setPrefix(tmp.getRoot().toString() + java.io.File.separator + "hello")
                .setSymlinkName("bye" + java.io.File.separator + "bye")
                .setCreateSymlink(true)
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            builder::build
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicLinkInitiallyInvalidReplaced() {
        if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) {
            // On Windows, by default, only administrator accounts can create symbolic links.
            return
        }
        val symlinkPath: Path = Paths.get(tmp.getRoot().toString(), "hello")
        java.nio.file.Files.createSymbolicLink(symlinkPath, Paths.get("no-such-file"))

        // Expected to delete the (invalid) symlink and replace with a symlink to the log
        val handler: SimpleLogHandler =
            SimpleLogHandler.builder().setPrefix(symlinkPath.toString()).build()
        handler.publish(LogRecord(java.util.logging.Level.SEVERE, "Hello world")) // To open the log file.

        assertThat(handler.getSymbolicLinkPath().get().toString()).isEqualTo(symlinkPath.toString())
        Truth.assertThat(java.nio.file.Files.isSymbolicLink(handler.getSymbolicLinkPath().get())).isTrue()
        Truth.assertThat(java.nio.file.Files.readSymbolicLink(handler.getSymbolicLinkPath().get()).toString())
            .isEqualTo(handler.getCurrentLogFilePath().get().getFileName().toString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLogLevelEqualPublished() {
        val handler: SimpleLogHandler =
            SimpleLogHandler.builder()
                .setPrefix(tmp.getRoot().toString() + java.io.File.separator + "info")
                .setLogLevel(java.util.logging.Level.INFO)
                .build()
        handler.publish(LogRecord(java.util.logging.Level.INFO, "Hello"))
        val logPath: java.util.Optional<Path?> = handler.getCurrentLogFilePath()
        handler.close()

        Truth.assertThat(java.nio.file.Files.size(logPath.get())).isGreaterThan(0L)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLogLevelHigherPublished() {
        val handler: SimpleLogHandler =
            SimpleLogHandler.builder()
                .setPrefix(tmp.getRoot().toString() + java.io.File.separator + "info")
                .setLogLevel(java.util.logging.Level.INFO)
                .build()
        handler.publish(LogRecord(java.util.logging.Level.WARNING, "Hello"))
        val logPath: java.util.Optional<Path?> = handler.getCurrentLogFilePath()
        handler.close()

        Truth.assertThat(java.nio.file.Files.size(logPath.get())).isGreaterThan(0L)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLogLevelLowerNotPublished() {
        val handler: SimpleLogHandler =
            SimpleLogHandler.builder()
                .setPrefix(tmp.getRoot().toString() + java.io.File.separator + "info")
                .setLogLevel(java.util.logging.Level.INFO)
                .build()
        handler.publish(LogRecord(java.util.logging.Level.FINE, "Hello"))
        val logPath: java.util.Optional<Path?> = handler.getCurrentLogFilePath()
        handler.close()

        Truth.assertThat(logPath.isPresent()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLogLevelDefaultAllPublished() {
        val handler: SimpleLogHandler =
            SimpleLogHandler.builder().setPrefix(tmp.getRoot().toString() + java.io.File.separator + "all").build()
        handler.publish(LogRecord(java.util.logging.Level.FINEST, "Hello"))
        val logPath: java.util.Optional<Path?> = handler.getCurrentLogFilePath()
        handler.close()

        Truth.assertThat(java.nio.file.Files.size(logPath.get())).isGreaterThan(0L)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRotateLimitBytes() {
        val clock = FakeClock(Instant.parse("2018-01-01T12:00:00Z"), ZoneOffset.UTC)
        val handler: SimpleLogHandler =
            SimpleLogHandler.builder()
                .setPrefix(tmp.getRoot().toString() + java.io.File.separator + "limits")
                .setFormatter(TrivialFormatter())
                .setRotateLimitBytes(16)
                .setClockForTesting(clock)
                .build()
        val symlinkPath: java.util.Optional<Path?> = handler.getSymbolicLinkPath()
        handler.publish(LogRecord(java.util.logging.Level.SEVERE, "1234567" /* 8 bytes including "\n" */))
        val firstLogPath: Path = handler.getCurrentLogFilePath().get()
        clock.set(Instant.parse("2018-01-01T12:00:01Z")) // Ensure the next file has a different name.
        handler.publish(LogRecord(java.util.logging.Level.SEVERE, "1234567" /* 8 bytes including "\n" */))
        val secondLogPath: Path = handler.getCurrentLogFilePath().get()
        handler.publish(LogRecord(java.util.logging.Level.SEVERE, "1234567" /* 8 bytes including "\n" */))
        handler.close()

        if (symlinkPath.isPresent()) {
            // The symlink path is expected to be present on non-Windows platforms; see tests above.
            Truth.assertThat(java.nio.file.Files.isSymbolicLink(symlinkPath.get())).isTrue()
            Truth.assertThat(java.nio.file.Files.readSymbolicLink(symlinkPath.get()).toString())
                .isEqualTo(secondLogPath.getFileName().toString())
        }
        Truth.assertThat(java.nio.file.Files.size(firstLogPath)).isEqualTo(16L /* including two "\n" */)
        Truth.assertThat(java.nio.file.Files.size(secondLogPath)).isEqualTo(8L /* including "\n" */)
        java.nio.file.Files.newDirectoryStream(tmp.getRoot().toPath()).use { dirStream ->
            Truth.assertThat(dirStream).hasSize(3)
        }
    }

    @Throws(IOException::class)
    private fun newFileWithContent(name: String?, content: String?): Path {
        val file: java.io.File = tmp.newFile(name)
        OutputStreamWriter(FileOutputStream(file.getPath()), java.nio.charset.StandardCharsets.UTF_8).use { writer ->
            writer.write(content)
        }
        return file.toPath()
    }

    @Throws(IOException::class)
    private fun newFileOfSize(name: String?, size: Int): Path {
        val buf = CharArray(size)
        java.util.Arrays.fill(buf, '\n')
        return newFileWithContent(name, String(buf))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOpenInAppendMode() {
        val logPath: Path = newFileWithContent("hello.20150901-151754.log", "Previous logs\n")
        val instant: Instant = Instant.parse("2015-09-01T15:17:54Z")
        val clock = FakeClock(instant, ZoneOffset.UTC)
        val handler: SimpleLogHandler =
            SimpleLogHandler.builder()
                .setPrefix(tmp.getRoot().toString() + java.io.File.separator + "hello")
                .setPattern(".")
                .setExtension("log")
                .setFormatter(TrivialFormatter())
                .setClockForTesting(clock)
                .build()
        handler.publish(LogRecord(java.util.logging.Level.SEVERE, "New logs"))
        assertThat(handler.getCurrentLogFilePath().get().toString()).isEqualTo(logPath.toString())
        handler.close()
        BufferedReader(
            java.io.InputStreamReader(
                FileInputStream(logPath.toFile()),
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).use { logReader ->
            Truth.assertThat(logReader.readLine()).isEqualTo("Previous logs")
            Truth.assertThat(logReader.readLine()).isEqualTo("New logs")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTotalLimit() {
        var username: String? = java.lang.System.getProperty("user.name")
        if (com.google.common.base.Strings.isNullOrEmpty(username)) {
            username = "unknown_user"
        }
        val hostname: String = SimpleLogHandler.getLocalHostnameFirstComponent()
        val baseFilename = "hello." + hostname + "." + username + ".log.java."
        val nonLog: Path = newFileOfSize("non_log", 16)
        val missingDate: Path = newFileOfSize(baseFilename + ".123", 16)
        val invalidExtension: Path = newFileOfSize(baseFilename + "19900101-120000.invalid", 16)
        val oldDeleted1: Path = newFileOfSize(baseFilename + "19900101-120000.123", 16)
        val oldDeleted2: Path = newFileOfSize(baseFilename + "19950101-120000.123", 16)
        val keptThenDeleted: Path = newFileOfSize(baseFilename + "19990101-120000.123", 16)
        val kept: Path = newFileOfSize(baseFilename + "19990606-060000.123", 16)

        val clock = FakeClock(Instant.parse("2018-01-01T12:00:00Z"), ZoneOffset.UTC)
        val handler: SimpleLogHandler =
            SimpleLogHandler.builder()
                .setPrefix(tmp.getRoot().toString() + java.io.File.separator + "hello")
                .setPattern(".%h.%u.log.java.")
                .setFormatter(TrivialFormatter())
                .setRotateLimitBytes(16)
                .setTotalLimitBytes(40)
                .setClockForTesting(clock)
                .build()
        // Print 8 bytes into the log file. Opening the log file triggers deletion of old logs.
        handler.publish(LogRecord(java.util.logging.Level.SEVERE, "1234567" /* 8 bytes including "\n" */))

        // We expect handler to delete all but 32 = 40 - 8 bytes worth of old log files.
        Truth.assertThat(java.nio.file.Files.exists(nonLog)).isTrue()
        Truth.assertThat(java.nio.file.Files.exists(missingDate)).isTrue()
        Truth.assertThat(java.nio.file.Files.exists(invalidExtension)).isTrue()
        Truth.assertThat(java.nio.file.Files.exists(oldDeleted1)).isFalse()
        Truth.assertThat(java.nio.file.Files.exists(oldDeleted2)).isFalse()
        Truth.assertThat(java.nio.file.Files.exists(keptThenDeleted)).isTrue()
        Truth.assertThat(java.nio.file.Files.exists(kept)).isTrue()

        handler.publish(LogRecord(java.util.logging.Level.SEVERE, "1234567" /* 8 bytes including "\n" */))
        val currentLogPath: Path = handler.getCurrentLogFilePath().get()
        handler.close()

        // We expect another old log file to be deleted after rotation.
        Truth.assertThat(java.nio.file.Files.exists(keptThenDeleted)).isFalse()
        Truth.assertThat(java.nio.file.Files.exists(kept)).isTrue()
        Truth.assertThat(java.nio.file.Files.exists(currentLogPath)).isTrue()
    }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val loggerFilePath_onSimpleLogHandler_withFile_returnsPath: Unit
        get() {
            val handlerQuerier: HandlerQuerier = HandlerQuerier()
            val handler: SimpleLogHandler =
                SimpleLogHandler.builder().setPrefix(tmp.getRoot().toString() + java.io.File.separator + "hello")
                    .build()
            val logger: java.util.logging.Logger = java.util.logging.Logger.getAnonymousLogger()
            logger.addHandler(handler)
            handler.publish(LogRecord(java.util.logging.Level.SEVERE, "Hello world")) // Ensure log file is opened.

            val retrievedLogPath: java.util.Optional<Path?>? = handlerQuerier.getLoggerFilePath(logger)

            Truth.assertThat(retrievedLogPath).isPresent()
            Truth.assertThat(retrievedLogPath.get().toString())
                .startsWith(tmp.getRoot().toString() + java.io.File.separator + "hello")

            handler.close()
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val loggerFilePath_onSimpleLogHandler_withoutFile_returnsEmpty: Unit
        get() {
            val handlerQuerier: HandlerQuerier = HandlerQuerier()
            val handler: SimpleLogHandler? =
                SimpleLogHandler.builder().setPrefix(tmp.getRoot().toString() + java.io.File.separator + "hello")
                    .build()
            val logger: java.util.logging.Logger = java.util.logging.Logger.getAnonymousLogger()
            logger.addHandler(handler)

            assertThat(handlerQuerier.getLoggerFilePath(logger)).isEmpty()
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val loggerFilePath_onUnsupportedLogHandler_fails: Unit
        get() {
            val handlerQuerier: HandlerQuerier = HandlerQuerier()
            val unsupportedHandler: FileHandler =
                FileHandler(tmp.getRoot().toString() + java.io.File.separator + "hello")
            val logger: java.util.logging.Logger = java.util.logging.Logger.getAnonymousLogger()
            logger.addHandler(unsupportedHandler)

            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { handlerQuerier.getLoggerFilePath(logger) })

            unsupportedHandler.close()
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val loggerFilePath_onMissingLogHandler_fails: Unit
        get() {
            val handlerQuerier: HandlerQuerier = HandlerQuerier()
            val logger: java.util.logging.Logger = java.util.logging.Logger.getAnonymousLogger()

            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { handlerQuerier.getLoggerFilePath(logger) })
        }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun publish_handlesInterrupt() {
        val handler: SimpleLogHandler =
            SimpleLogHandler.builder()
                .setPrefix(tmp.getRoot().toString() + java.io.File.separator + "hello")
                .setFormatter(TrivialFormatter())
                .build()
        val t: java.lang.Thread =
            java.lang.Thread(
                java.lang.Runnable {
                    java.lang.Thread.currentThread().interrupt()
                    handler.publish(LogRecord(java.util.logging.Level.SEVERE, "Hello world")) // To open the log file.
                    Truth.assertThat(java.lang.Thread.currentThread().isInterrupted()).isTrue()
                    handler.flush()
                    Truth.assertThat(java.lang.Thread.currentThread().isInterrupted()).isTrue()
                    handler.close()
                    Truth.assertThat(java.lang.Thread.currentThread().isInterrupted()).isTrue()
                })
        t.start()
        t.join()
        // For b/176321271
        Truth.assertThat(java.lang.Thread.currentThread().isInterrupted()).isFalse()
    }
}
