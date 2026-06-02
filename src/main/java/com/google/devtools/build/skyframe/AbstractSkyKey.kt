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
package com.google.devtools.build.skyframe

import com.google.devtools.build.skyframe.SkyKey

/**
 * For use when the [.argument] of the [SkyKey] cannot be a [SkyKey] itself,
 * either because it is a type like List or because it is already a different [SkyKey].
 * Provides convenient boilerplate.
 */
abstract class AbstractSkyKey<T> protected constructor(arg: T?) : SkyKey {
    // Visible for serialization.
    @kotlin.jvm.JvmField
    protected val arg: T?

    init {
        this.arg = com.google.common.base.Preconditions.checkNotNull<T?>(arg)
    }

    override fun argument(): T? {
        return arg
    }

    override fun hashCode(): Int {
        return 31 * functionName().hashCode() + arg!!.hashCode()
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false
        }
        if (this is WithCachedHashCode<*> && hashCode() != obj.hashCode()) {
            return false
        }
        val that = obj as AbstractSkyKey<*>
        return functionName() == that.functionName() && arg == that.arg
    }

    override fun toString(): String {
        return functionName().toString() + ":" + arg
    }

    /**
     * An [AbstractSkyKey] that computes and caches its hash code upon creation.
     * 
     * 
     * Only subclass this class when caching the hash code is worth spending a field on it. If the
     * hash code computation for the key's argument is already fast, just subclass [ ] to save memory.
     */
    abstract class WithCachedHashCode<T> protected constructor(arg: T?) : AbstractSkyKey<T?>(arg) {
        @Transient
        private val hashCode: Int

        init {
            this.hashCode = super.hashCode()
        }

        override fun hashCode(): Int {
            return hashCode
        }
    }
}
