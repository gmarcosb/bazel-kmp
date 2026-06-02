// Copyright 2016 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner.junit4

import com.google.testing.junit.runner.util.Factory
import java.io.OutputStream
import java.util.function.Supplier

/**
 * A factory that supplies [OutputStream].
 */
class ProvideXmlStreamFactory(configSupplier: Supplier<JUnit4Config?>) : Factory<OutputStream?> {
    private val configSupplier: Supplier<JUnit4Config?>

    init {
        checkNotNull(configSupplier)

        this.configSupplier = configSupplier
    }

    public override fun get(): OutputStream {
        return LazyOutputStream(Supplier { JUnit4RunnerModule.Companion.provideXmlStream(configSupplier.get()) })
    }

    private class LazyOutputStream(private var supplier: Supplier<OutputStream?>?) : OutputStream() {
        @kotlin.concurrent.Volatile
        private var delegate: OutputStream? = null

        fun ensureDelegate(): OutputStream? {
            val delegate0 = delegate
            if (delegate0 != null) {
                return delegate0
            }

            synchronized(this) {
                if (delegate == null) {
                    delegate = supplier!!.get()
                    supplier = null
                }
            }

            return delegate
        }

        @Throws(IOException::class)
        override fun write(b: Int) {
            ensureDelegate()!!.write(b)
        }

        @Throws(IOException::class)
        override fun write(b: ByteArray?, off: Int, len: Int) {
            ensureDelegate()!!.write(b, off, len)
        }

        @Throws(IOException::class)
        override fun write(b: ByteArray?) {
            ensureDelegate()!!.write(b)
        }

        @Throws(IOException::class)
        override fun close() {
            if (delegate != null) {
                delegate!!.close()
            }
        }

        @Throws(IOException::class)
        override fun flush() {
            if (delegate != null) {
                delegate!!.flush()
            }
        }
    }

    companion object {
        fun create(configSupplier: Supplier<JUnit4Config?>): Factory<OutputStream?> {
            return ProvideXmlStreamFactory(configSupplier)
        }
    }
}
