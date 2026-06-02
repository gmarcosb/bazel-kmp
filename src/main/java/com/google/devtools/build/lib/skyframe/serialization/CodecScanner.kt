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

import com.google.common.flogger.GoogleLogger
import com.google.devtools.build.lib.skyframe.serialization.CodecRegisterer
import com.google.devtools.build.lib.skyframe.serialization.CodecScanningConstants
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry
import com.google.devtools.build.lib.skyframe.serialization.autocodec.RegisteredSingletonDoNotUse
import java.io.IOException

/**
 * Scans the classpath to find [ObjectCodec] and [CodecRegisterer] instances.
 * 
 * 
 * To avoid loading classes unnecessarily, the scanner filters by class name before loading.
 * [ObjectCodec] implementation class names should end in "Codec" while [ ] implementation class names should end in "CodecRegisterer".
 * 
 * 
 * See [CodecRegisterer] for more details.
 */
internal object CodecScanner {
    private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

    /**
     * Initializes an [ObjectCodecRegistry] builder by scanning classes matching the given
     * package filter.
     * 
     * @param packageFilter a filter applied to the package name of each class
     * @see CodecRegisterer
     */
    @Throws(IOException::class, java.lang.ReflectiveOperationException::class)
    fun initializeCodecRegistry(packageFilter: java.util.function.Predicate<String?>): com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry.Builder {
        logger.atInfo().log("Building ObjectCodecRegistry")
        val builder: com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry.Builder =
            ObjectCodecRegistry.Companion.newBuilder()
        for (classInfo in getClassInfos(packageFilter)) {
            if (classInfo.getName().endsWith("Codec")) {
                processLikelyCodec(classInfo.load(), builder)
            } else if (classInfo.getName().endsWith("CodecRegisterer")) {
                processLikelyRegisterer(classInfo.load(), builder)
            } else if (classInfo.getName().endsWith(CodecScanningConstants.REGISTERED_SINGLETON_SUFFIX)) {
                processLikelyConstant(classInfo.load(), builder)
            } else {
                builder.addClassName(classInfo.getName().intern())
            }
        }
        return builder
    }

    @Throws(java.lang.ReflectiveOperationException::class)
    private fun processLikelyCodec(
        type: java.lang.Class<*>,
        builder: com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry.Builder
    ) {
        if (ObjectCodec::class.java == type
            || !ObjectCodec::class.java.isAssignableFrom(type) || java.lang.reflect.Modifier.isAbstract(type.getModifiers())
        ) {
            return
        }

        try {
            val constructor: java.lang.reflect.Constructor<*> = type.getDeclaredConstructor()
            constructor.setAccessible(true)
            val codec: ObjectCodec<*> = constructor.newInstance() as ObjectCodec<*>
            if (codec.autoRegister()) {
                builder.add(codec)
            }
        } catch (e: java.lang.NoSuchMethodException) {
            logger.atFine().withCause(e).log(
                "Skipping registration of %s because it had no default constructor", type
            )
        }
    }

    @Throws(
        java.lang.NoSuchMethodException::class,
        java.lang.reflect.InvocationTargetException::class,
        java.lang.InstantiationException::class,
        java.lang.IllegalAccessException::class
    )
    private fun processLikelyRegisterer(
        type: java.lang.Class<*>,
        builder: com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry.Builder
    ) {
        if (CodecRegisterer::class.java == type || !CodecRegisterer::class.java.isAssignableFrom(type)) {
            return
        }

        val constructor: java.lang.reflect.Constructor<out CodecRegisterer> =
            type.asSubclass<CodecRegisterer>(CodecRegisterer::class.java).getDeclaredConstructor()
        constructor.setAccessible(true)
        val registerer: CodecRegisterer = constructor.newInstance()
        for (codec in registerer.getCodecsToRegister()) {
            builder.add(codec)
        }
    }

    private fun processLikelyConstant(
        type: java.lang.Class<*>,
        builder: com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry.Builder
    ) {
        if (!RegisteredSingletonDoNotUse::class.java.isAssignableFrom(type)) {
            return
        }
        val field: java.lang.reflect.Field
        try {
            field = type.getDeclaredField(CodecScanningConstants.REGISTERED_SINGLETON_INSTANCE_VAR_NAME)
        } catch (e: java.lang.NoSuchFieldException) {
            throw java.lang.IllegalStateException(
                (type
                    .toString() + " inherits from "
                        + RegisteredSingletonDoNotUse::class.java
                        + " but does not have a field "
                        + CodecScanningConstants.REGISTERED_SINGLETON_INSTANCE_VAR_NAME),
                e
            )
        }
        try {
            builder.addReferenceConstant(
                com.google.common.base.Preconditions.checkNotNull<Any?>(field.get(null), "%s %s", field, type)
            )
        } catch (e: java.lang.IllegalAccessException) {
            throw java.lang.IllegalStateException("Could not access field " + field + " for " + type, e)
        }
    }

    /** Return the [ClassInfo]s matching `packageFilter`, sorted by name.  */
    @Throws(IOException::class)
    private fun getClassInfos(packageFilter: java.util.function.Predicate<String?>): com.google.common.collect.ImmutableList<com.google.common.reflect.ClassPath.ClassInfo> {
        // Search all classes in the classloader that loaded this class.
        // This is the system classloader when using a monolithic binary, and a custom classloader for
        // the Logic Component when using a split binary.
        return com.google.common.reflect.ClassPath.from(CodecScanner::class.java.getClassLoader()).getResources()
            .stream()
            .filter(java.util.function.Predicate { obj: com.google.common.reflect.ClassPath.ResourceInfo? ->
                com.google.common.reflect.ClassPath.ClassInfo::class.java.isInstance(
                    obj
                )
            })
            .map<com.google.common.reflect.ClassPath.ClassInfo?>(java.util.function.Function { obj: com.google.common.reflect.ClassPath.ResourceInfo? ->
                com.google.common.reflect.ClassPath.ClassInfo::class.java.cast(
                    obj
                )
            })
            .filter(java.util.function.Predicate { c: com.google.common.reflect.ClassPath.ClassInfo? ->
                packageFilter.test(
                    c.getPackageName()
                )
            })
            .sorted(java.util.Comparator.comparing<com.google.common.reflect.ClassPath.ClassInfo?, String?>(java.util.function.Function { obj: com.google.common.reflect.ClassPath.ClassInfo? -> obj.getName() }))
            .collect(com.google.common.collect.ImmutableList.toImmutableList<com.google.common.reflect.ClassPath.ClassInfo?>())
    }
}
