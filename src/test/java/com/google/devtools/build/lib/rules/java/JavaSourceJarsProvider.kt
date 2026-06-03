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
package com.google.devtools.build.lib.rules.java

import com.google.devtools.build.lib.actions.Artifact

/**
 * The collection of source jars from the transitive closure.
 * 
 * @param transitiveSourceJars Returns all the source jars in the transitive closure, that can be
 * reached by a chain of JavaSourceJarsProvider instances.
 * @param sourceJars Return the source jars that are to be built when the target is on the command
 * line.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
@AutoCodec
class JavaSourceJarsProvider(
    transitiveSourceJars: NestedSet<Artifact?>?,
    sourceJars: com.google.common.collect.ImmutableList<Artifact?>?
) : JavaInfoInternalProvider {
    /** A builder for [JavaSourceJarsProvider].  */
    class Builder {
        // CompactHashSet preserves insertion order here since we never perform any removals
        private val sourceJars: com.google.devtools.build.lib.collect.compacthashset.CompactHashSet<Artifact?> =
            com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.create<Artifact?>()
        private val transitiveSourceJars: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()

        /** Add a source jar that is to be built when the target is on the command line.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addSourceJar(sourceJar: Artifact?): Builder {
            sourceJars.add(com.google.common.base.Preconditions.checkNotNull<Artifact?>(sourceJar))
            return this
        }

        /** Add source jars to be built when the target is on the command line.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addAllSourceJars(sourceJars: MutableCollection<Artifact?>?): Builder {
            this.sourceJars.addAll(
                com.google.common.base.Preconditions.checkNotNull<MutableCollection<Artifact?>?>(
                    sourceJars
                )
            )
            return this
        }

        /**
         * Add a source jar in the transitive closure, that can be reached by a chain of
         * JavaSourceJarsProvider instances.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addTransitiveSourceJar(transitiveSourceJar: Artifact?): Builder {
            transitiveSourceJars.add(com.google.common.base.Preconditions.checkNotNull<T?>(transitiveSourceJar))
            return this
        }

        /**
         * Add source jars in the transitive closure, that can be reached by a chain of
         * JavaSourceJarsProvider instances.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addAllTransitiveSourceJars(transitiveSourceJars: NestedSet<Artifact?>?): Builder {
            this.transitiveSourceJars.addTransitive(
                com.google.common.base.Preconditions.checkNotNull<T?>(
                    transitiveSourceJars
                )
            )
            return this
        }

        fun build(): JavaSourceJarsProvider {
            return create(
                transitiveSourceJars.build(), com.google.common.collect.ImmutableList.copyOf<Artifact?>(sourceJars)
            )
        }
    }

    val transitiveSourceJars: NestedSet<Artifact?>?
    val sourceJars: com.google.common.collect.ImmutableList<Artifact?>?

    init {
        this.sourceJars = sourceJars
        this.transitiveSourceJars = transitiveSourceJars
        java.util.Objects.requireNonNull<Any?>(transitiveSourceJars, "transitiveSourceJars")
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<Artifact?>?>(sourceJars, "sourceJars")
    }

    companion object {
        @SerializationConstant
        val EMPTY: JavaSourceJarsProvider = create(
            NestedSetBuilder.emptySet(Order.STABLE_ORDER),
            com.google.common.collect.ImmutableList.of<Artifact?>()
        )

        fun create(
            transitiveSourceJars: NestedSet<Artifact?>?, sourceJars: Iterable<Artifact?>
        ): JavaSourceJarsProvider {
            return JavaSourceJarsProvider(
                transitiveSourceJars,
                com.google.common.collect.ImmutableList.copyOf<Artifact?>(sourceJars)
            )
        }

        /** Returns a builder for a [JavaSourceJarsProvider].  */
        fun builder(): Builder {
            return com.google.devtools.build.lib.rules.java.JavaSourceJarsProvider.Builder()
        }

        @Throws(net.starlark.java.eval.EvalException::class, TypeException::class)
        fun fromStarlarkJavaInfo(javaInfo: StructImpl): JavaSourceJarsProvider {
            return create(
                javaInfo.getValue("transitive_source_jars", Depset::class.java).getSet(Artifact::class.java),
                net.starlark.java.eval.Sequence.cast<T?>(
                    javaInfo.getValue("source_jars", StarlarkList::class.java), Artifact::class.java, "source_jars"
                )
            )
        }
    }
}
