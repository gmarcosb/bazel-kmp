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
package com.google.devtools.build.lib.skyframe.serialization

/**
 * A container class that holds an [.object] of type [T] and a possibly null [ ]. If the [ListenableFuture] returned by [.getFutureToBlockWritesOn]
 * is non-null, then, if [.object] is the serialized representation of some Bazel object, then
 * it should not be written anywhere until the [ListenableFuture] in [ ][.getFutureToBlockWritesOn] completes successfully.
 * 
 * @param <T> Some serialized representation of an object, for instance a `byte[]` or a [     ].
</T> */
abstract class SerializationResult<T> private constructor(@kotlin.jvm.JvmField private val `object`: T?) {
    /**
     * Returns a new [SerializationResult] with the same future (if any) and `newObj`
     * replacing the current [.getObject].
     */
    abstract fun <S> with(newObj: S?): SerializationResult<S?>?

    /**
     * Returns a [ListenableFuture] that, if not null, must complete successfully before [ ][.getObject] can be written remotely.
     */
    abstract fun getFutureToBlockWritesOn(): com.google.common.util.concurrent.ListenableFuture<*>?

    /** Returns the stored object that should not be written remotely before the future completes.  */
    fun getObject(): T? {
        return `object`
    }

    private class ObjectWithoutFuture<T>(obj: T?) : SerializationResult<T?>(obj) {
        override fun <S> with(newObj: S?): SerializationResult<S?> {
            return ObjectWithoutFuture<S?>(newObj)
        }

        override fun getFutureToBlockWritesOn(): com.google.common.util.concurrent.ListenableFuture<*>? {
            return null
        }
    }

    private class ObjectWithFuture<T>(
        obj: T?,
        futureToBlockWritesOn: com.google.common.util.concurrent.ListenableFuture<*>?
    ) : SerializationResult<T?>(obj) {
        private val futureToBlockWritesOn: com.google.common.util.concurrent.ListenableFuture<*>

        init {
            this.futureToBlockWritesOn = com.google.common.base.Preconditions.checkNotNull(futureToBlockWritesOn, obj)
        }

        override fun <S> with(newObj: S?): SerializationResult<S?> {
            return ObjectWithFuture<S?>(newObj, futureToBlockWritesOn)
        }

        override fun getFutureToBlockWritesOn(): com.google.common.util.concurrent.ListenableFuture<*> {
            return futureToBlockWritesOn
        }
    }

    companion object {
        fun <T> create(
            `object`: T?, futureToBlockWritesOn: com.google.common.util.concurrent.ListenableFuture<*>?
        ): SerializationResult<T?> {
            return if (futureToBlockWritesOn != null)
                ObjectWithFuture<T?>(`object`, futureToBlockWritesOn)
            else
                createWithoutFuture<T?>(`object`)
        }

        /** Creates a [SerializationResult] with a null future (no waiting necessary).  */
        fun <T> createWithoutFuture(`object`: T?): SerializationResult<T?> {
            return ObjectWithoutFuture<T?>(`object`)
        }
    }
}
