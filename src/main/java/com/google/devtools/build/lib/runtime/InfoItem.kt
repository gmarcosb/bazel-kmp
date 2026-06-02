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
package com.google.devtools.build.lib.runtime


import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue

/** An item that is returned by `blaze info`.  */
abstract class InfoItem protected constructor(
    /** The name of the info key.  */
    @kotlin.jvm.JvmField val name: String?,
    /** The help description of the info key.  */
    @kotlin.jvm.JvmField val description: String?,
    /**
     * Whether the key is printed when "blaze info" is invoked without arguments.
     * 
     * 
     * This is usually true for info keys that take multiple lines, thus, cannot really be included
     * in the output of argumentless "blaze info".
     */
    val isHidden: Boolean = false
) {
    /**
     * Returns true if this info item requires CommandEnvironment.syncPackageLoading to be called,
     * e.g. in order to initialize the skyframe executor.
     * 
     * 
     * Virtually all info items do not need it.
     */
    open fun needsSyncPackageLoading(): Boolean {
        return false
    }

    /** Returns the value of the info key. The return value is directly printed to stdout.  */
    @Throws(AbruptExitException::class, java.lang.InterruptedException::class)
    abstract fun get(
        configurationSupplier: com.google.common.base.Supplier<BuildConfigurationValue?>?, env: CommandEnvironment?
    ): ByteArray?

    companion object {
        @kotlin.jvm.JvmStatic
        protected fun print(value: Any?): ByteArray? {
            if (value is ByteArray) {
                return value
            }
            val unsafeBytes: ByteArray = StringUnsafe.getInternalStringBytes(java.lang.String.valueOf(value))
            val bytes: ByteArray = java.util.Arrays.copyOf(unsafeBytes, unsafeBytes.size + 1)
            bytes[bytes.size - 1] = '\n'.code.toByte()
            return bytes
        }
    }
}
