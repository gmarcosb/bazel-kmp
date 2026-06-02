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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.concurrent.BlazeInterners

/**
 * An instance of a given `AspectClass` with loaded definition and parameters.
 * 
 * 
 * This is an aspect equivalent of [Rule] class for build rules.
 * 
 * 
 * Note: equality is only implemented for purposes of interning. It delegates to [ ] equality, which is not overridden. For this reason, this class should not be
 * used in SkyKeys - use [AspectDescriptor] instead.
 */
@Immutable
class Aspect private constructor(aspectDescriptor: AspectDescriptor?, aspectDefinition: AspectDefinition?) :
    AttributeInfoProvider {
    private val aspectDescriptor: AspectDescriptor
    private val aspectDefinition: AspectDefinition

    init {
        this.aspectDescriptor = com.google.common.base.Preconditions.checkNotNull<AspectDescriptor>(aspectDescriptor)
        this.aspectDefinition = com.google.common.base.Preconditions.checkNotNull<AspectDefinition>(aspectDefinition)
    }

    /** Returns the aspectClass required for building the aspect.  */
    fun getAspectClass(): AspectClass? {
        return aspectDescriptor.getAspectClass()
    }

    /** Returns parameters for evaluation of the aspect.  */
    fun getParameters(): AspectParameters? {
        return aspectDescriptor.getParameters()
    }

    fun getDescriptor(): AspectDescriptor {
        return aspectDescriptor
    }

    fun getDefinition(): AspectDefinition {
        return aspectDefinition
    }

    override fun isAttributeValueExplicitlySpecified(attribute: com.google.devtools.build.lib.packages.Attribute?): Boolean {
        // All aspect attributes are implicit.
        return false
    }

    override fun toString(): String {
        return "Aspect " + aspectDescriptor
    }

    override fun hashCode(): Int {
        return HashCodes.hashObjects(aspectDescriptor, aspectDefinition)
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj !is Aspect) {
            return false
        }

        val that = obj
        return aspectDescriptor == that.aspectDescriptor
                && aspectDefinition == that.aspectDefinition
    }

    /**
     * Codec for [Aspect].
     * 
     * 
     * This codec calls [Aspect.forNative] and [Aspect.forStarlark] as the final step
     * in serialization, which is important for interning. It also optimizes the way that native
     * aspects are serialized by taking advantage of the fact that native aspect definitions can be
     * determined from their descriptors alone.
     */
    @Suppress("unused") // Used reflectively.
    private class AspectCodec : DeferredObjectCodec<Aspect?>() {
        override fun getEncodedClass(): java.lang.Class<Aspect?> {
            return Aspect::class.java
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun serialize(context: SerializationContext, obj: Aspect, codedOut: CodedOutputStream) {
            val descriptor: AspectDescriptor = obj.getDescriptor()
            val isNativeAspect = descriptor.getAspectClass() is NativeAspectClass
            codedOut.writeBoolNoTag(isNativeAspect)
            context.serialize(descriptor, codedOut)
            if (!isNativeAspect) {
                context.serialize(obj.getDefinition(), codedOut)
            }
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream
        ): DeferredValue<Aspect?> {
            if (codedIn.readBool()) {
                val builder = AspectDeserializationBuilderForNative()
                context.deserialize<AspectDeserializationBuilderForNative?>(
                    codedIn,
                    builder,
                    AsyncDeserializationContext.FieldSetter { builder: AspectDeserializationBuilderForNative?, value: Any? ->
                        AspectDeserializationBuilderForNative.Companion.setDescriptor(
                            builder,
                            value
                        )
                    })
                return builder
            }
            val builder = AspectDeserializationBuilderForStarlark()
            context.deserialize<AspectDeserializationBuilderForStarlark?>(
                codedIn,
                builder,
                AsyncDeserializationContext.FieldSetter { builder: AspectDeserializationBuilderForStarlark?, value: Any? ->
                    AspectDeserializationBuilderForStarlark.Companion.setDescriptor(
                        builder,
                        value
                    )
                })
            context.deserialize<AspectDeserializationBuilderForStarlark?>(
                codedIn,
                builder,
                AsyncDeserializationContext.FieldSetter { builder: AspectDeserializationBuilderForStarlark?, value: Any? ->
                    AspectDeserializationBuilderForStarlark.Companion.setDefinition(
                        builder,
                        value
                    )
                })
            return builder
        }

        private class AspectDeserializationBuilderForNative : DeferredValue<Aspect?> {
            private var descriptor: AspectDescriptor? = null

            override fun call(): Aspect {
                return forNative(
                    descriptor.getAspectClass() as NativeAspectClass?, descriptor.getParameters()
                )
            }

            companion object {
                private fun setDescriptor(
                    builder: AspectDeserializationBuilderForNative, value: Any?
                ) {
                    builder.descriptor = value as AspectDescriptor
                }
            }
        }

        private class AspectDeserializationBuilderForStarlark : DeferredValue<Aspect?> {
            private var descriptor: AspectDescriptor? = null
            private var definition: AspectDefinition? = null

            override fun call(): Aspect {
                return forStarlark(
                    descriptor.getAspectClass() as StarlarkAspectClass?,
                    definition,
                    descriptor.getParameters()
                )
            }

            companion object {
                private fun setDescriptor(
                    builder: AspectDeserializationBuilderForStarlark, value: Any?
                ) {
                    builder.descriptor = value as AspectDescriptor
                }

                private fun setDefinition(
                    builder: AspectDeserializationBuilderForStarlark, value: Any?
                ) {
                    builder.definition = value as AspectDefinition?
                }
            }
        }
    }

    companion object {
        /**
         * The aspect definition is a function of the aspect class + its parameters, so we can cache that.
         * 
         * 
         * The native aspects are loaded with blaze and are not stateful. Reference equality works fine
         * in this case.
         */
        private val definitionCache: com.github.benmanes.caffeine.cache.LoadingCache<NativeAspectClass?, com.github.benmanes.caffeine.cache.LoadingCache<AspectParameters?, AspectDefinition?>?> =
            Caffeine.newBuilder()
                .build<NativeAspectClass?, com.github.benmanes.caffeine.cache.LoadingCache<AspectParameters?, AspectDefinition?>?>(
                    com.github.benmanes.caffeine.cache.CacheLoader { nativeAspectClass: NativeAspectClass? ->
                        Caffeine.newBuilder()
                            .build<AspectParameters?, AspectDefinition?>(com.github.benmanes.caffeine.cache.CacheLoader { aspectParameters: AspectParameters? ->
                                nativeAspectClass.getDefinition(aspectParameters)
                            })
                    })

        private val interner: com.google.common.collect.Interner<Aspect> = BlazeInterners.newWeakInterner()

        @kotlin.jvm.JvmOverloads
        fun forNative(
            nativeAspectClass: NativeAspectClass?,
            parameters: AspectParameters? = AspectParameters.Companion.EMPTY
        ): Aspect {
            val definition: AspectDefinition? = definitionCache.get(nativeAspectClass).get(parameters)
            return createInterned(nativeAspectClass, definition, parameters)
        }

        fun forStarlark(
            starlarkAspectClass: StarlarkAspectClass?,
            aspectDefinition: AspectDefinition?,
            parameters: AspectParameters?
        ): Aspect {
            return createInterned(starlarkAspectClass, aspectDefinition, parameters)
        }

        private fun createInterned(
            aspectClass: AspectClass?, definition: AspectDefinition?, parameters: AspectParameters?
        ): Aspect {
            return interner.intern(Aspect(AspectDescriptor.Companion.of(aspectClass, parameters), definition))
        }
    }
}
