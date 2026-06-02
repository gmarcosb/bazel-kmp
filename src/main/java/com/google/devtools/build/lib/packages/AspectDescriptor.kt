// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.concurrent.BlazeInterners

/**
 * A pair of [AspectClass] and [AspectParameters].
 * 
 * 
 * Used for dependency resolution.
 */
@Immutable
@AutoCodec
class AspectDescriptor private constructor(aspectClass: AspectClass?, aspectParameters: AspectParameters?) {
    private val aspectClass: AspectClass
    private val aspectParameters: AspectParameters

    init {
        this.aspectClass = com.google.common.base.Preconditions.checkNotNull<AspectClass>(aspectClass)
        this.aspectParameters = com.google.common.base.Preconditions.checkNotNull<AspectParameters>(aspectParameters)
    }

    fun getAspectClass(): AspectClass {
        return aspectClass
    }

    fun getParameters(): AspectParameters {
        return aspectParameters
    }

    override fun hashCode(): Int {
        return HashCodes.hashObjects(aspectClass, aspectParameters)
    }

    override fun equals(obj: Any?): Boolean {
        if (obj === this) {
            return true
        }

        if (obj !is AspectDescriptor) {
            return false
        }

        val that = obj
        return aspectClass == that.aspectClass && aspectParameters == that.aspectParameters
    }

    override fun toString(): String {
        return getDescription()!!
    }

    /**
     * Creates a presentable description of this aspect, available to Starlark via "Target.aspects".
     * 
     * 
     * The description is designed to be unique for each aspect descriptor, but not to be
     * parseable.
     */
    fun getDescription(): String? {
        if (aspectParameters.isEmpty()) {
            return aspectClass.getName()
        }

        val builder: java.lang.StringBuilder = java.lang.StringBuilder(aspectClass.getName())
        builder.append('[')
        val attributes: com.google.common.collect.ImmutableMultimap<String?, String?> = aspectParameters.getAttributes()
        var first = true
        for (attribute in attributes.entries()) {
            if (!first) {
                builder.append(',')
            } else {
                first = false
            }
            builder.append(attribute.getKey())
            builder.append("=\"")
            builder.append(TextFormat.escapeDoubleQuotesAndBackslashes(attribute.getValue()))
            builder.append("\"")
        }
        builder.append(']')
        return builder.toString()
    }

    companion object {
        private val interner: com.google.common.collect.Interner<AspectDescriptor> = BlazeInterners.newWeakInterner()

        @com.google.common.annotations.VisibleForTesting
        fun of(aspectClass: AspectClass?, aspectParameters: AspectParameters?): AspectDescriptor {
            return interner.intern(AspectDescriptor(aspectClass, aspectParameters))
        }

        @AutoCodec.Interner
        @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
        fun intern(descriptor: AspectDescriptor?): AspectDescriptor {
            return interner.intern(descriptor)
        }
    }
}
