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

import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.actions.Artifact
import com.google.errorprone.annotations.CanIgnoreReturnValue
import kotlin.collections.ArrayList
import kotlin.collections.Iterable
import kotlin.collections.MutableCollection
import kotlin.collections.MutableList
import kotlin.collections.MutableMap

/**
 * An object that captures the temporary state we need to pass around while the initialization hook
 * for a java rule is running.
 */
class JavaTargetAttributes private constructor(
    sourceFiles: ImmutableSet<Artifact?>?,
    compileTimeClassPath: NestedSet<Artifact?>?,
    bootClassPath: BootClassPathInfo?,
    sourcePath: ImmutableList<Artifact?>?,
    plugins: JavaPluginInfo?,
    resources: ImmutableMap<PathFragment?, Artifact?>?,
    resourceJars: NestedSet<Artifact?>?,
    sourceJars: ImmutableList<Artifact?>?,
    classPathResources: ImmutableList<Artifact?>?,
    additionalOutputs: ImmutableSet<Artifact?>?,
    directJars: NestedSet<Artifact?>?,
    headerCompilationDirectJars: NestedSet<Artifact?>?,
    compileTimeDependencyArtifacts: NestedSet<Artifact?>?,
    targetLabel: Label?,
    injectingRuleKind: String?,
    strictJavaDeps: StrictDepsMode?
) {
    /** A builder class for JavaTargetAttributes.  */
    class Builder {
        // The order of source files is important, and there must not be duplicates.
        // Unfortunately, there is no interface in Java that represents a collection
        // without duplicates that has a stable and deterministic iteration order,
        // but is not sorted according to a property of the elements. Thus we are
        // stuck with Set.
        private val sourceFiles: MutableList<Artifact?> = ArrayList<Artifact?>()

        private val compileTimeClassPathBuilder: NestedSetBuilder<Artifact?> = NestedSetBuilder.naiveLinkOrder()

        private var bootClassPath: BootClassPathInfo = BootClassPathInfo.Companion.empty()
        private var sourcePath: ImmutableList<Artifact?> = ImmutableList.of<Artifact?>()

        private var plugins: JavaPluginInfo = JavaPluginInfo.Companion.empty(JavaPluginInfo.Companion.PROVIDER)

        private val resources: MutableMap<PathFragment?, Artifact?> = LinkedHashMap<PathFragment?, Artifact?>()
        private val resourceJars: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
        private val sourceJars: MutableList<Artifact?> = ArrayList<Artifact?>()

        private val classPathResources: ImmutableList.Builder<Artifact?> = ImmutableList.builder<Artifact?>()

        private val additionalOutputs: ImmutableSet.Builder<Artifact?> = ImmutableSet.builder<Artifact?>()

        /** @see {@link .setStrictJavaDeps}.
         */
        private var strictJavaDeps: StrictDepsMode? = StrictDepsMode.ERROR

        private val directJarsBuilder: NestedSetBuilder<Artifact?> = NestedSetBuilder.naiveLinkOrder()
        private val headerCompilationDirectJarsBuilder: NestedSetBuilder<Artifact?> = NestedSetBuilder.naiveLinkOrder()
        private val compileTimeDependencyArtifacts: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
        private var targetLabel: Label? = null
        private var injectingRuleKind: String? = null

        private var prependDirectJars = true

        private var built = false

        @CanIgnoreReturnValue
        fun addSourceFiles(sourceFiles: Iterable<Artifact>): Builder {
            Preconditions.checkArgument(!built)
            for (artifact in sourceFiles) {
                if (JavaSemantics.Companion.JAVA_SOURCE.matches(artifact.getFilename())) {
                    this.sourceFiles.add(artifact)
                }
            }
            return this
        }

        @CanIgnoreReturnValue
        fun addSourceJars(sourceJars: MutableCollection<Artifact?>?): Builder {
            Preconditions.checkArgument(!built)
            this.sourceJars.addAll(sourceJars)
            return this
        }

        @CanIgnoreReturnValue
        @VisibleForTesting
        fun addCompileTimeClassPathEntries(entries: NestedSet<Artifact?>?): Builder {
            Preconditions.checkArgument(!built)
            compileTimeClassPathBuilder.addTransitive(entries)
            return this
        }

        /**
         * Avoids prepending the direct jars to the compile-time classpath when building the attributes,
         * assuming that they have already been prepended. This avoids creating a new [NestedSet]
         * instance.
         * 
         * 
         * After this method is called, [.addDirectJars] will throw an exception.
         */
        @CanIgnoreReturnValue
        fun setCompileTimeClassPathEntriesWithPrependedDirectJars(
            entries: NestedSet<Artifact?>?
        ): Builder {
            Preconditions.checkArgument(!built)
            Preconditions.checkArgument(compileTimeClassPathBuilder.isEmpty())
            prependDirectJars = false
            compileTimeClassPathBuilder.addTransitive(entries)
            return this
        }

        @CanIgnoreReturnValue
        fun setTargetLabel(targetLabel: Label?): Builder {
            Preconditions.checkArgument(!built)
            this.targetLabel = targetLabel
            return this
        }

        @CanIgnoreReturnValue
        fun setInjectingRuleKind(injectingRuleKind: String?): Builder {
            Preconditions.checkArgument(!built)
            this.injectingRuleKind = injectingRuleKind
            return this
        }

        /**
         * Sets the bootclasspath to be passed to the Java compiler.
         * 
         * 
         * If this method is called, then the bootclasspath specified in this JavaTargetAttributes
         * instance overrides the default bootclasspath.
         */
        @CanIgnoreReturnValue
        @Throws(RuleErrorException::class)
        fun setBootClassPath(bootClassPath: BootClassPathInfo): Builder {
            Preconditions.checkArgument(!built)
            Preconditions.checkArgument(!bootClassPath.isEmpty())
            Preconditions.checkState(this.bootClassPath.isEmpty())
            this.bootClassPath = bootClassPath
            return this
        }

        /** Sets the sourcepath to be passed to the Java compiler.  */
        @CanIgnoreReturnValue
        fun setSourcePath(artifacts: ImmutableList<Artifact?>): Builder {
            Preconditions.checkArgument(!built)
            Preconditions.checkArgument(sourcePath.isEmpty())
            this.sourcePath = artifacts
            return this
        }

        /**
         * Controls how strict the javac compiler will be in checking correct use of direct
         * dependencies.
         * 
         * 
         * Defaults to [StrictDepsMode.ERROR].
         * 
         * @param strictDeps one of WARN, ERROR or OFF
         */
        @CanIgnoreReturnValue
        fun setStrictJavaDeps(strictDeps: StrictDepsMode?): Builder {
            Preconditions.checkArgument(!built)
            strictJavaDeps = strictDeps
            return this
        }

        /**
         * In tandem with strictJavaDeps, directJars represents a subset of the compile-time classpath
         * jars that were provided by direct dependencies. When strictJavaDeps is OFF, there is no need
         * to provide directJars, and no extra information is passed to javac. When strictJavaDeps is
         * set to WARN or ERROR, the compiler command line will include extra flags to indicate the
         * warning/error policy and to map the classpath jars to direct or transitive dependencies,
         * using the information in directJars. The compiler command line will include an extra flag to
         * indicate which classpath jars are direct dependencies.
         */
        @CanIgnoreReturnValue
        fun addDirectJars(directJars: NestedSet<Artifact?>?): Builder {
            Preconditions.checkArgument(!built)
            Preconditions.checkArgument(prependDirectJars)
            this.directJarsBuilder.addTransitive(directJars)
            return this
        }

        @CanIgnoreReturnValue
        fun addHeaderCompilationDirectJars(headerCompilationDirectJars: NestedSet<Artifact?>?): Builder {
            Preconditions.checkArgument(!built)
            this.headerCompilationDirectJarsBuilder.addTransitive(headerCompilationDirectJars)
            return this
        }

        @CanIgnoreReturnValue
        fun addCompileTimeDependencyArtifacts(dependencyArtifacts: NestedSet<Artifact?>?): Builder {
            Preconditions.checkArgument(!built)
            compileTimeDependencyArtifacts.addTransitive(dependencyArtifacts)
            return this
        }

        @CanIgnoreReturnValue
        fun addResource(execPath: PathFragment?, resource: Artifact?): Builder {
            Preconditions.checkArgument(!built)
            this.resources.put(execPath, resource)
            return this
        }

        @CanIgnoreReturnValue
        fun addResourceJars(resourceJars: NestedSet<Artifact?>?): Builder {
            Preconditions.checkArgument(!built)
            this.resourceJars.addTransitive(resourceJars)
            return this
        }

        @CanIgnoreReturnValue
        fun addPlugin(plugins: JavaPluginInfo?): Builder {
            Preconditions.checkArgument(!built)
            this.plugins = JavaPluginInfo.Companion.mergeWithoutJavaOutputs(this.plugins, plugins)
            return this
        }

        @CanIgnoreReturnValue
        fun addClassPathResources(classPathResources: MutableList<Artifact?>): Builder {
            Preconditions.checkArgument(!built)
            this.classPathResources.addAll(classPathResources)
            return this
        }

        /** Adds additional outputs to this target's compile action.  */
        @CanIgnoreReturnValue
        fun addAdditionalOutputs(outputs: Iterable<Artifact?>): Builder {
            Preconditions.checkArgument(!built)
            additionalOutputs.addAll(outputs)
            return this
        }

        fun build(): JavaTargetAttributes {
            built = true
            val directJars: NestedSet<Artifact?>? = directJarsBuilder.build()
            val headerCompilationDirectJars: NestedSet<Artifact?>? = headerCompilationDirectJarsBuilder.build()
            val compileTimeClassPath: NestedSet<Artifact?>? =
                if (prependDirectJars)
                    NestedSetBuilder.< Artifact > naiveLinkOrder < Artifact ? > ()
                        .addTransitive(directJars)
                        .addTransitive(compileTimeClassPathBuilder.build())
                        .build()
                else
                    compileTimeClassPathBuilder.build()
            return JavaTargetAttributes(
                ImmutableSet.copyOf<Artifact?>(sourceFiles),
                compileTimeClassPath,
                bootClassPath,
                sourcePath,
                plugins,
                ImmutableMap.copyOf<PathFragment?, Artifact?>(resources),
                resourceJars.build(),
                ImmutableList.copyOf<Artifact?>(sourceJars),
                classPathResources.build(),
                additionalOutputs.build(),
                directJars,  /* headerCompilationDirectJars= */
                headerCompilationDirectJars,
                compileTimeDependencyArtifacts.build(),
                targetLabel,
                injectingRuleKind,
                strictJavaDeps
            )
        }

        // TODO(bazel-team): delete the following method - users should use the built
        // JavaTargetAttributes instead of accessing mutable state in the Builder.

        @Deprecated("prefer {@link JavaTargetAttributes#getSourceFiles} ")
        fun hasSourceFiles(): Boolean {
            return !sourceFiles.isEmpty()
        }
    }

    //
    // -------------------------- END OF BUILDER CLASS -------------------------
    //
    private val sourceFiles: ImmutableSet<Artifact?>?

    private val compileTimeClassPath: NestedSet<Artifact?>?

    val bootClassPath: BootClassPathInfo?
    private val sourcePath: ImmutableList<Artifact?>?

    private val plugins: JavaPluginInfo?

    private val resources: ImmutableMap<PathFragment?, Artifact?>?
    private val resourceJars: NestedSet<Artifact?>?

    private val sourceJars: ImmutableList<Artifact?>?

    private val classPathResources: ImmutableList<Artifact?>?

    private val additionalOutputs: ImmutableSet<Artifact?>?

    private val directJars: NestedSet<Artifact?>?
    private val headerCompilationDirectJars: NestedSet<Artifact?>?
    private val compileTimeDependencyArtifacts: NestedSet<Artifact?>?
    private val targetLabel: Label?
    val injectingRuleKind: String?

    private val strictJavaDeps: StrictDepsMode?

    /** Constructor of JavaTargetAttributes.  */
    init {
        this.sourceFiles = sourceFiles
        this.directJars = directJars
        this.headerCompilationDirectJars = headerCompilationDirectJars
        this.compileTimeClassPath = compileTimeClassPath
        this.bootClassPath = bootClassPath
        this.sourcePath = sourcePath
        this.plugins = plugins
        this.resources = resources
        this.resourceJars = resourceJars
        this.sourceJars = sourceJars
        this.classPathResources = classPathResources
        this.additionalOutputs = additionalOutputs
        this.compileTimeDependencyArtifacts = compileTimeDependencyArtifacts
        this.targetLabel = targetLabel
        this.injectingRuleKind = injectingRuleKind
        this.strictJavaDeps = strictJavaDeps
    }

    fun appendAdditionalTransitiveClassPathEntries(
        additionalClassPathEntries: NestedSet<Artifact?>?
    ): JavaTargetAttributes {
        val compileTimeClassPath: NestedSet<Artifact?>? =
            NestedSetBuilder.fromNestedSet(this.compileTimeClassPath)
                .addTransitive(additionalClassPathEntries)
                .build()
        return JavaTargetAttributes(
            sourceFiles,
            compileTimeClassPath,
            bootClassPath,
            sourcePath,
            plugins,
            resources,
            resourceJars,
            sourceJars,
            classPathResources,
            additionalOutputs,
            directJars,  /* headerCompilationDirectJars= */
            headerCompilationDirectJars,
            compileTimeDependencyArtifacts,
            targetLabel,
            injectingRuleKind,
            strictJavaDeps
        )
    }

    fun getDirectJars(): NestedSet<Artifact?>? {
        return directJars
    }

    fun getHeaderCompilationDirectJars(): NestedSet<Artifact?>? {
        return headerCompilationDirectJars
    }

    fun getCompileTimeDependencyArtifacts(): NestedSet<Artifact?>? {
        return compileTimeDependencyArtifacts
    }

    fun getSourceJars(): ImmutableList<Artifact?>? {
        return sourceJars
    }

    fun getResources(): MutableMap<PathFragment?, Artifact?>? {
        return resources
    }

    fun getResourceJars(): NestedSet<Artifact?>? {
        return resourceJars
    }

    fun getClassPathResources(): ImmutableList<Artifact?>? {
        return classPathResources
    }

    fun getAdditionalOutputs(): ImmutableSet<Artifact?>? {
        return additionalOutputs
    }

    fun getCompileTimeClassPath(): NestedSet<Artifact?>? {
        return compileTimeClassPath
    }

    fun getSourcePath(): ImmutableList<Artifact?>? {
        return sourcePath
    }

    fun plugins(): JavaPluginInfo? {
        return plugins
    }

    fun getSourceFiles(): ImmutableSet<Artifact?>? {
        return sourceFiles
    }

    fun getTargetLabel(): Label? {
        return targetLabel
    }

    fun getStrictJavaDeps(): StrictDepsMode? {
        return strictJavaDeps
    }
}
