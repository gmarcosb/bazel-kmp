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

/** The collection of gen jars from the transitive closure.  */
interface JavaGenJarsProvider

    : JavaInfoInternalProvider, JavaAnnotationProcessingApi<Artifact?> {
    @get:Throws(net.starlark.java.eval.EvalException::class, RuleErrorException::class)
    val isEmpty: Boolean
        get() = !usesAnnotationProcessing() && genClassJar == null && genSourceJar == null && this.transitiveGenClassJars.isEmpty()
                && this.transitiveGenSourceJars.isEmpty()

    @get:Throws(RuleErrorException::class)
    val transitiveGenClassJars: NestedSet<Artifact?>?

    @get:Throws(RuleErrorException::class)
    val transitiveGenSourceJars: NestedSet<Artifact?>?

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val processorClasspath: NestedSet<Artifact?>?

    /** Natively constructed JavaGenJarsProvider  */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    @AutoCodec
    class NativeJavaGenJarsProvider(
        usesAnnotationProcessing: Boolean,
        getGenClassJar: Artifact?,
        getGenSourceJar: Artifact?,
        getProcessorClasspath: NestedSet<Artifact?>?,
        getProcessorClassnames: NestedSet<String?>?,
        getTransitiveGenClassJars: NestedSet<Artifact?>?,
        getTransitiveGenSourceJars: NestedSet<Artifact?>?
    ) : JavaGenJarsProvider {
        val isImmutable: Boolean
            get() = true

        val transitiveGenClassJarsForStarlark: Depset
            get() = Depset.of(Artifact::class.java, this.getTransitiveGenClassJars)

        val transitiveGenSourceJarsForStarlark: Depset
            get() = Depset.of(Artifact::class.java, this.getTransitiveGenSourceJars)

        val processorClasspathForStarlark: Depset
            get() = Depset.of(Artifact::class.java, this.getProcessorClasspath)

        val processorClassNamesList: com.google.common.collect.ImmutableList<String?>
            get() = this.getProcessorClassnames.toList()
        val usesAnnotationProcessing: Boolean
        val getGenClassJar: Artifact?
        val getGenSourceJar: Artifact?
        val getProcessorClasspath: NestedSet<Artifact?>?
        val getProcessorClassnames: NestedSet<String?>?
        val getTransitiveGenClassJars: NestedSet<Artifact?>?
        val getTransitiveGenSourceJars: NestedSet<Artifact?>?

        init {
            this.getTransitiveGenSourceJars = getTransitiveGenSourceJars
            this.getTransitiveGenClassJars = getTransitiveGenClassJars
            this.getProcessorClassnames = getProcessorClassnames
            this.getProcessorClasspath = getProcessorClasspath
            this.getGenSourceJar = getGenSourceJar
            this.getGenClassJar = getGenClassJar
            this.usesAnnotationProcessing = usesAnnotationProcessing
            java.util.Objects.requireNonNull<Any?>(getProcessorClasspath, "getProcessorClasspath")
            java.util.Objects.requireNonNull<Any?>(getProcessorClassnames, "getProcessorClassnames")
            java.util.Objects.requireNonNull<Any?>(getTransitiveGenClassJars, "getTransitiveGenClassJars")
            java.util.Objects.requireNonNull<Any?>(getTransitiveGenSourceJars, "getTransitiveGenSourceJars")
        }
    }

    companion object {
        @Throws(net.starlark.java.eval.EvalException::class)
        fun from(obj: Any?): JavaGenJarsProvider {
            if (obj == null || obj === Starlark.NONE) {
                return EMPTY
            } else if (obj is JavaGenJarsProvider) {
                return obj
            } else if (obj is StructImpl) {
                return NativeJavaGenJarsProvider(
                    obj.getValue("enabled", Boolean::class.java),
                    JavaInfo.Companion.nullIfNone<T?>(obj.getValue("class_jar"), Artifact::class.java),
                    JavaInfo.Companion.nullIfNone<T?>(obj.getValue("source_jar"), Artifact::class.java),
                    Depset.cast(
                        obj.getValue("processor_classpath"), Artifact::class.java, "processor_classpath"
                    ),
                    NestedSetBuilder.wrap(
                        Order.NAIVE_LINK_ORDER,
                        net.starlark.java.eval.Sequence.cast<T?>(
                            obj.getValue("processor_classnames"), String::class.java, "processor_classnames"
                        )
                    ),
                    Depset.cast(
                        obj.getValue("transitive_class_jars"), Artifact::class.java, "transitive_class_jars"
                    ),
                    Depset.cast(
                        obj.getValue("transitive_source_jars"), Artifact::class.java, "transitive_source_jars"
                    )
                )
            }
            throw Starlark.errorf("wanted JavaGenJarsProvider, got %s", Starlark.type(obj))
        }

        val EMPTY: JavaGenJarsProvider = NativeJavaGenJarsProvider(
            false,
            null,
            null,
            NestedSetBuilder.emptySet(Order.STABLE_ORDER),
            NestedSetBuilder.emptySet(Order.STABLE_ORDER),
            NestedSetBuilder.emptySet(Order.STABLE_ORDER),
            NestedSetBuilder.emptySet(Order.STABLE_ORDER)
        )
    }
}
