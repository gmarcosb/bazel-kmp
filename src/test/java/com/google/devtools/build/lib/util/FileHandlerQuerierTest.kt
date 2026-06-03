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

import com.google.common.truth.Truth
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.logging.FileHandler

/** Tests for the [FileHandlerQuerier] class.  */
@RunWith(JUnit4::class)
class FileHandlerQuerierTest {
    @org.junit.Rule
    var tmp: TemporaryFolder = TemporaryFolder()

    private fun getLoggerWithFileHandler(handler: FileHandler?): java.util.logging.Logger {
        val logger: java.util.logging.Logger = java.util.logging.Logger.getAnonymousLogger()
        logger.addHandler(handler)
        return logger
    }

    @Throws(IOException::class)
    private fun getLoggerWithFileHandler(logPath: Path): java.util.logging.Logger {
        return getLoggerWithFileHandler(FileHandler(logPath.toString()))
    }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val loggerFilePath_onExpectedConfigurationOpenFile_returnsPath: Unit
        get() {
            val configuredLogPath: Path = Paths.get(tmp.getRoot().toString(), "hello.log")
            val mockLogManager: java.util.logging.LogManager =
                Mockito.mock<java.util.logging.LogManager>(java.util.logging.LogManager::class.java)
            Mockito.`when`<String?>(mockLogManager.getProperty("java.util.logging.FileHandler.pattern"))
                .thenReturn(configuredLogPath.toString())
            val logger: java.util.logging.Logger = getLoggerWithFileHandler(configuredLogPath)
            val handlerQuerier: FileHandlerQuerier = FileHandlerQuerier({ mockLogManager })

            val retrievedLogPath: java.util.Optional<Path?>? = handlerQuerier.getLoggerFilePath(logger)

            Truth.assertThat(retrievedLogPath).isPresent()
            Truth.assertThat(retrievedLogPath.get().toString()).isEqualTo(configuredLogPath.toString())
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val loggerFilePath_onExpectedConfigurationClosedFile_returnsEmpty: Unit
        get() {
            val configuredLogPath: Path = Paths.get(tmp.getRoot().toString(), "hello.log")
            val mockLogManager: java.util.logging.LogManager =
                Mockito.mock<java.util.logging.LogManager>(java.util.logging.LogManager::class.java)
            Mockito.`when`<String?>(mockLogManager.getProperty("java.util.logging.FileHandler.pattern"))
                .thenReturn(configuredLogPath.toString())
            val handler: FileHandler = FileHandler(configuredLogPath.toString())
            val logger: java.util.logging.Logger = getLoggerWithFileHandler(handler)
            val handlerQuerier: FileHandlerQuerier = FileHandlerQuerier({ mockLogManager })
            handler.close()

            assertThat(handlerQuerier.getLoggerFilePath(logger)).isEmpty()
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val loggerFilePath_onMissingConfiguration_fails: Unit
        get() {
            val configuredLogPath: Path = Paths.get(tmp.getRoot().toString(), "hello.log")
            val mockLogManager: java.util.logging.LogManager =
                Mockito.mock<java.util.logging.LogManager>(java.util.logging.LogManager::class.java)
            Mockito.`when`<String?>(mockLogManager.getProperty("java.util.logging.FileHandler.pattern"))
                .thenReturn(null)
            val logger: java.util.logging.Logger = getLoggerWithFileHandler(configuredLogPath)
            val handlerQuerier: FileHandlerQuerier = FileHandlerQuerier({ mockLogManager })

            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable { handlerQuerier.getLoggerFilePath(logger) })
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val loggerFilePath_onVariablesInPath_fails: Unit
        get() {
            val mockLogManager: java.util.logging.LogManager =
                Mockito.mock<java.util.logging.LogManager>(java.util.logging.LogManager::class.java)
            Mockito.`when`<String?>(mockLogManager.getProperty("java.util.logging.FileHandler.pattern"))
                .thenReturn(tmp.getRoot().toString() + java.io.File.separator + "hello_%u.log")
            val logger: java.util.logging.Logger =
                getLoggerWithFileHandler(Paths.get(tmp.getRoot().toString(), "hello_0.log"))
            val handlerQuerier: FileHandlerQuerier = FileHandlerQuerier()

            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable { handlerQuerier.getLoggerFilePath(logger) })
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val loggerFilePath_onUnsupportedLogHandler_fails: Unit
        get() {
            val handlerQuerier: FileHandlerQuerier = FileHandlerQuerier()
            val logger: java.util.logging.Logger = java.util.logging.Logger.getAnonymousLogger()
            logger.addHandler(
                SimpleLogHandler.builder().setPrefix(tmp.getRoot().toString() + java.io.File.separator + "hello.log")
                    .build()
            )

            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { handlerQuerier.getLoggerFilePath(logger) })
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val loggerFilePath_onMissingLogHandler_fails: Unit
        get() {
            val handlerQuerier: FileHandlerQuerier = FileHandlerQuerier()
            val logger: java.util.logging.Logger = java.util.logging.Logger.getAnonymousLogger()

            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { handlerQuerier.getLoggerFilePath(logger) })
        }
}
