// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.shell

import com.google.devtools.build.lib.shell.Consumers.OutErrConsumers

@RunWith(JUnit4::class)
class ConsumersTest {
    @Before
    @Throws(java.lang.Exception::class)
    fun configureLogger() {
        // enable all log statements to ensure there are no problems with
        // logging code
        java.util.logging.Logger.getLogger("com.google.devtools.build.lib.shell.Command")
            .setLevel(java.util.logging.Level.FINEST)
    }

    /**
     * Tests that if an IOException occurs in an output stream, the exception
     * will be recorded and thrown when we call waitForCompletion.
     */
    @org.junit.Test
    fun testAsynchronousIOExceptionInConsumerOutputStream() {
        val out: java.io.OutputStream = object : java.io.OutputStream() {
            @Throws(IOException::class)
            override fun write(b: Int) {
                throw IOException(SECRET_MESSAGE)
            }
        }
        val outErr: OutErrConsumers = Consumers.createStreamingConsumers(out, out)
        val outInput: ByteArrayInputStream = ByteArrayInputStream(byteArrayOf('a'.code.toByte()))
        val errInput: ByteArrayInputStream = ByteArrayInputStream(ByteArray(0))
        outErr.registerInputs(outInput, errInput, false)
        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { outErr.waitForCompletion() })
        Truth.assertThat(e).hasMessageThat().isEqualTo(SECRET_MESSAGE)
    }

    /**
     * Tests that if an OutOfMemeoryError occurs in an output stream, it
     * will be recorded and thrown when we call waitForCompletion.
     */
    @org.junit.Test
    fun testAsynchronousOutOfMemoryErrorInConsumerOutputStream() {
        val error: java.lang.OutOfMemoryError = java.lang.OutOfMemoryError(SECRET_MESSAGE)
        val out: java.io.OutputStream = object : java.io.OutputStream() {
            @Throws(IOException::class)
            override fun write(b: Int) {
                throw error
            }
        }
        val outErr: OutErrConsumers = Consumers.createStreamingConsumers(out, out)
        val outInput: ByteArrayInputStream = ByteArrayInputStream(byteArrayOf('a'.code.toByte()))
        val errInput: ByteArrayInputStream = ByteArrayInputStream(ByteArray(0))
        outErr.registerInputs(outInput, errInput, false)
        try {
            outErr.waitForCompletion()
            org.junit.Assert.fail()
        } catch (e: IOException) {
            org.junit.Assert.fail()
        } catch (e: java.lang.OutOfMemoryError) {
            Truth.assertWithMessage("OutOfMemoryError is not masked").that(e).isSameInstanceAs(error)
        }
    }

    /**
     * Tests that if an Error occurs in an output stream, the error
     * will be recorded and thrown when we call waitForCompletion.
     */
    @org.junit.Test
    fun testAsynchronousErrorInConsumerOutputStream() {
        val out: java.io.OutputStream = object : java.io.OutputStream() {
            @Throws(IOException::class)
            override fun write(b: Int) {
                throw java.lang.OutOfMemoryError(SECRET_MESSAGE)
            }
        }
        val outErr: OutErrConsumers = Consumers.createStreamingConsumers(out, out)
        val outInput: ByteArrayInputStream = ByteArrayInputStream(byteArrayOf('a'.code.toByte()))
        val errInput: ByteArrayInputStream = ByteArrayInputStream(ByteArray(0))
        outErr.registerInputs(outInput, errInput, false)
        val error: java.lang.Error? = org.junit.Assert.assertThrows<java.lang.Error?>(
            java.lang.Error::class.java,
            org.junit.function.ThrowingRunnable { outErr.waitForCompletion() })
        Truth.assertThat(error).isNotInstanceOf(IOException::class.java)
        Truth.assertThat(error).hasMessageThat().isEqualTo(SECRET_MESSAGE)
    }

    /**
     * Tests that if an RuntimeException occurs in an output stream, the exception
     * will be recorded and thrown when we call waitForCompletion.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAsynchronousRuntimeExceptionInConsumerOutputStream() {
        val out: java.io.OutputStream = object : java.io.OutputStream() {
            override fun write(b: Int) {
                throw java.lang.RuntimeException(SECRET_MESSAGE)
            }
        }
        val outErr: OutErrConsumers = Consumers.createStreamingConsumers(out, out)
        val outInput: ByteArrayInputStream = ByteArrayInputStream(byteArrayOf('a'.code.toByte()))
        val errInput: ByteArrayInputStream = ByteArrayInputStream(ByteArray(0))
        outErr.registerInputs(outInput, errInput, false)
        val e: java.lang.RuntimeException? = org.junit.Assert.assertThrows<java.lang.RuntimeException?>(
            java.lang.RuntimeException::class.java,
            org.junit.function.ThrowingRunnable { outErr.waitForCompletion() })
        Truth.assertThat(e).hasMessageThat().isEqualTo(SECRET_MESSAGE)
    }

    companion object {
        private const val SECRET_MESSAGE = "This is a secret message."
    }
}
