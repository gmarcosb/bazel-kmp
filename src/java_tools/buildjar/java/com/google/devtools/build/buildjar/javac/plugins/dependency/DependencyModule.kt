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
package com.google.devtools.build.buildjar.javac.plugins.dependency

import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Joiner
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Streams
import com.google.devtools.build.buildjar.JarOwner
import com.google.errorprone.annotations.CanIgnoreReturnValue
import com.sun.tools.javac.code.Symbol
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.stream.Stream
import kotlin.collections.Iterable
import kotlin.collections.MutableCollection
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet

/**
 * Wrapper class for managing dependencies on top of [ ]. If strict_java_deps is enabled, it
 * keeps two maps between jar names (as they appear on the classpath) and their originating targets,
 * one for direct dependencies and the other for transitive (indirect) dependencies, and enables the
 * [StrictJavaDepsPlugin] to perform the actual checks. The plugin also collects dependency
 * information during compilation, and DependencyModule generates a .jdeps artifact summarizing the
 * discovered dependencies.
 */
class DependencyModule internal constructor(
    /** Returns the strict dependency checking (strictJavaDeps) setting.  */
    val strictJavaDeps: StrictJavaDeps?,
    /** Returns which tool to use for adding missing dependencies.  */
    val fixDepsTool: FixTool?,
    private val directJars: ImmutableSet<Path?>,
    private val strictClasspathMode: Boolean,
    private val depsArtifacts: MutableSet<Path>,
    platformJars: ImmutableSet<Path?>?,
    /** Returns the name (label) of the originating target.  */
    val targetLabel: String?,
    /** Returns the file name collecting dependency information.  */
    val outputDepsProtoFile: Path?,
    fixMessage: FixMessage?,
    exemptGenerators: MutableSet<String?>?
) {
    enum class StrictJavaDeps {
        /** Legacy behavior: Silently allow referencing transitive dependencies.  */
        OFF,

        /** Warn about transitive dependencies being used directly.  */
        WARN,

        /** Fail the build when transitive dependencies are used directly.  */
        ERROR
    }

    private var hasMissingTargets = false
    private val explicitDependenciesMap: MutableMap<Path?, Dependency?>
    private val implicitDependenciesMap: MutableMap<Path?, Dependency?>

    /** Returns the jars in the platform classpath.  */
    val platformJars: ImmutableSet<Path?>?
    var requiredClasspath: MutableSet<Path?>? = null

    /** Returns a message to suggest fix when a missing indirect dependency is found.  */
    val fixMessage: FixMessage?

    /** Return a set of generator values that are exempt from strict dependencies.  */
    val exemptGenerators: MutableSet<String?>?
    private val packages: MutableSet<Symbol.PackageSymbol?>

    init {
        this.explicitDependenciesMap = HashMap<Path?, Dependency?>()
        this.implicitDependenciesMap = HashMap<Path?, Dependency?>()
        this.platformJars = platformJars
        this.fixMessage = fixMessage
        this.exemptGenerators = exemptGenerators
        this.packages = HashSet<Symbol.PackageSymbol?>()
    }

    val plugin: BlazeJavaCompilerPlugin
        /** Returns a plugin to be enabled in the compiler.  */
        get() = StrictJavaDepsPlugin(this)

    /**
     * Writes dependency information to the deps file in proto format, if specified.
     * 
     * 
     * We collect precise dependency information to allow Blaze to analyze both strict and unused
     * dependencies, as well as packages contained by the output jar.
     */
    @Throws(IOException::class)
    fun emitDependencyInformation(
        classpath: ImmutableList<Path?>, successful: Boolean, requiresFallback: Boolean
    ) {
        if (outputDepsProtoFile == null) {
            return
        }

        try {
            BufferedOutputStream(Files.newOutputStream(outputDepsProtoFile)).use { out ->
                buildDependenciesProto(classpath, successful, requiresFallback).writeTo(out)
            }
        } catch (ex: IOException) {
            throw IOException("Cannot write dependencies to " + outputDepsProtoFile, ex)
        }
    }

    @VisibleForTesting
    fun buildDependenciesProto(
        classpath: ImmutableList<Path?>, successful: Boolean, requiresFallback: Boolean
    ): Dependencies {
        val deps: Dependencies.Builder = Dependencies.newBuilder()
        if (targetLabel != null) {
            deps.setRuleLabel(targetLabel)
        }
        deps.setSuccess(successful)
        if (requiresFallback) {
            deps.setRequiresReducedClasspathFallback(true)
        }

        deps.addAllContainedPackage(
            packages
                .stream()
                .map<String?> { pkg: Symbol.PackageSymbol? ->
                    if (pkg!!.isUnnamed()) "" else pkg.getQualifiedName().toString()
                }
                .sorted()
                .collect(ImmutableList.toImmutableList<E?>()))

        // Filter using the original classpath, to preserve ordering.
        for (entry in classpath) {
            if (explicitDependenciesMap.containsKey(entry)) {
                deps.addDependency(explicitDependenciesMap.get(entry))
            } else if (implicitDependenciesMap.containsKey(entry)) {
                deps.addDependency(implicitDependenciesMap.get(entry))
            }
        }
        return deps.build()
    }

    /** Returns the paths of direct dependencies.  */
    fun directJars(): ImmutableSet<Path?> {
        return directJars
    }

    /** Returns the map collecting precise explicit dependency information.  */
    fun getExplicitDependenciesMap(): MutableMap<Path?, Dependency?> {
        return explicitDependenciesMap
    }

    /** Returns the map collecting precise implicit dependency information.  */
    fun getImplicitDependenciesMap(): MutableMap<Path?, Dependency?> {
        return implicitDependenciesMap
    }

    /** Adds a package to the set of packages built by this target.  */
    fun addPackage(packge: Symbol.PackageSymbol?): Boolean {
        return packages.add(packge)
    }

    /** Returns whether classpath reduction is enabled for this invocation.  */
    fun reduceClasspath(): Boolean {
        return strictClasspathMode
    }

    fun setHasMissingTargets() {
        hasMissingTargets = true
    }

    /** Returns true if any missing transitive dependencies were reported.  */
    fun hasMissingTargets(): Boolean {
        return hasMissingTargets
    }

    /**
     * Computes a reduced compile-time classpath from the union of direct dependencies and their
     * dependencies, as listed in the associated .deps artifacts.
     */
    @Throws(IOException::class)
    fun computeStrictClasspath(originalClasspath: ImmutableList<Path?>): ImmutableList<Path?>? {
        if (!strictClasspathMode) {
            return originalClasspath
        }

        // Classpath = direct deps + runtime direct deps + their .deps
        requiredClasspath = HashSet<Path?>(directJars)

        for (depsArtifact in depsArtifacts) {
            collectDependenciesFromArtifact(depsArtifact)
        }

        // TODO(b/71936047): it should be an error for requiredClasspath to contain paths that are not
        // in originalClasspath

        // Filter the initial classpath and keep the original order
        return originalClasspath
            .stream()
            .filter { o: Path? -> requiredClasspath!!.contains(o) }
            .collect(ImmutableList.toImmutableList<Path?>())
    }

    @VisibleForTesting
    fun setStrictClasspath(strictClasspath: MutableSet<Path?>) {
        this.requiredClasspath = strictClasspath
    }

    /** Updates [.requiredClasspath] to include dependencies from the given output artifact.  */
    @Throws(IOException::class)
    private fun collectDependenciesFromArtifact(path: Path) {
        try {
            BufferedInputStream(Files.newInputStream(path)).use { bis ->
                val deps: Dependencies = Dependencies.parseFrom(bis)
                // Quick check to make sure we have a valid proto.
                if (!deps.hasRuleLabel()) {
                    throw IOException("Could not parse Deps.Dependencies message from proto.")
                }
                for (dep in deps.getDependencyList()) {
                    if (dep.getKind() === Kind.EXPLICIT || dep.getKind() === Kind.IMPLICIT || dep.getKind() === Kind.INCOMPLETE) {
                        requiredClasspath!!.add(Paths.get(dep.getPath()))
                    }
                }
            }
        } catch (e: IOException) {
            throw IOException(String.format("error reading deps artifact: %s", path), e)
        }
    }

    /** Emits a message to the user about missing dependencies to add to unbreak their build.  */
    interface FixMessage {
        /**
         * Gets a message describing what dependencies are missing and how to fix them.
         * 
         * @param missing the missing dependencies to be added.
         * @param recipient the target from which the dependencies are missing.
         * @return the string message describing the dependency build issues, including fix.
         */
        fun get(missing: Iterable<JarOwner?>?, recipient: String?): String?
    }

    /** Tool with which to fix dependency issues.  */
    interface FixTool {
        /**
         * Applies this tool to find the missing import/dependency.
         * 
         * @param diagnostic a full javac diagnostic, possibly containing an import for a class which
         * cannot be found on the classpath.
         * @param javacopts list of all javac options/flags.
         * @return the missing import or dependency as a String, or empty Optional if the diagnostic did
         * not contain exactly one unresolved import that we know how to fix.
         */
        fun resolveMissingImport(
            diagnostic: Diagnostic<JavaFileObject?>?, javacopts: ImmutableList<String?>?
        ): Optional<String?>?

        /**
         * Returns a command for this tool to fix `recipient` by adding all `missing`
         * dependencies for this target.
         */
        fun getFixCommand(missing: Iterable<String?>?, recipient: String?): String?
    }

    /** Builder for [DependencyModule].  */
    class Builder {
        private var strictJavaDeps = StrictJavaDeps.OFF
        private var fixDepsTool: FixTool? = null
        private var directJars: ImmutableSet<Path?> = ImmutableSet.of<Path?>()
        private val depsArtifacts: MutableSet<Path> = HashSet<Path>()
        private var platformJars: ImmutableSet<Path?>? = ImmutableSet.of<Path?>()
        private var targetLabel: String? = null
        private var outputDepsProtoFile: Path? = null
        private var strictClasspathMode = false
        private var fixMessage: FixMessage? = DefaultFixMessage()
        private val exemptGenerators: MutableSet<String?> = LinkedHashSet<String?>(SJD_EXEMPT_PROCESSORS)

        private class DefaultFixMessage : FixMessage {
            override fun get(missing: Iterable<JarOwner?>, recipient: String?): String? {
                val missingTargets: ImmutableSet<String?> =
                    Streams.stream<JarOwner?>(missing)
                        .flatMap<Any?> { owner: JarOwner? -> owner.label().map(Stream::of).orElse(Stream.empty<T?>()) }
                        .collect(ImmutableSet.toImmutableSet<Any?>())
                if (missingTargets.isEmpty()) {
                    return ""
                }
                return String.format(
                    ("%1\$s ** Please add the following dependencies:%2\$s \n  %3\$s to %4\$s \n"
                            + "%1\$s ** You can use the following buildozer command:%2\$s "
                            + "\nbuildozer 'add deps %3\$s' %4\$s \n\n"),
                    "\u001b[35m\u001b[1m", "\u001b[0m", Joiner.on(" ").join(missingTargets), recipient
                )
            }
        }

        /**
         * Constructs the DependencyModule, guaranteeing that the maps are never null (they may be
         * empty), and the default strictJavaDeps setting is OFF.
         * 
         * @return an instance of DependencyModule
         */
        fun build(): DependencyModule {
            return DependencyModule(
                strictJavaDeps,
                fixDepsTool,
                directJars,
                strictClasspathMode,
                depsArtifacts,
                platformJars,
                targetLabel,
                outputDepsProtoFile,
                fixMessage,
                exemptGenerators
            )
        }

        /**
         * Sets the strictness level for dependency checking.
         * 
         * @param strictJavaDeps level, as specified by [StrictJavaDeps]
         * @return this Builder instance
         */
        @CanIgnoreReturnValue
        fun setStrictJavaDeps(strictJavaDeps: String?): Builder {
            this.strictJavaDeps = StrictJavaDeps.valueOf(strictJavaDeps!!)
            return this
        }

        /**
         * Sets which tool to use for fixing missing dependencies.
         * 
         * @param fixDepsTool tool name
         * @return this Builder instance
         */
        @CanIgnoreReturnValue
        fun setFixDepsTool(fixDepsTool: FixTool?): Builder {
            this.fixDepsTool = fixDepsTool
            return this
        }

        /**
         * Sets the name (label) of the originating target.
         * 
         * @param targetLabel label, such as the label of a RuleConfiguredTarget.
         * @return this Builder instance.
         */
        @CanIgnoreReturnValue
        fun setTargetLabel(targetLabel: String?): Builder {
            this.targetLabel = targetLabel
            return this
        }

        /** Sets the paths to jars that are direct dependencies.  */
        @CanIgnoreReturnValue
        fun setDirectJars(directJars: ImmutableSet<Path?>): Builder {
            this.directJars = directJars
            return this
        }

        /**
         * Sets the name of the file that will contain dependency information in the protocol buffer
         * format.
         * 
         * @param outputDepsProtoFile output file name for dependency information
         * @return this Builder instance
         */
        @CanIgnoreReturnValue
        fun setOutputDepsProtoFile(outputDepsProtoFile: Path?): Builder {
            this.outputDepsProtoFile = outputDepsProtoFile
            return this
        }

        /**
         * Adds a collection of dependency artifacts to use when reducing the compile-time classpath.
         * 
         * @param depsArtifacts dependency artifacts
         * @return this Builder instance
         */
        @CanIgnoreReturnValue
        fun addDepsArtifacts(depsArtifacts: MutableCollection<Path?>?): Builder {
            this.depsArtifacts.addAll(depsArtifacts)
            return this
        }

        /** Sets the platform classpath entries.  */
        @CanIgnoreReturnValue
        fun setPlatformJars(platformJars: ImmutableSet<Path?>?): Builder {
            this.platformJars = platformJars
            return this
        }

        /**
         * Requests compile-time classpath reduction based on provided dependency artifacts.
         * 
         * @return this Builder instance
         */
        @CanIgnoreReturnValue
        fun setReduceClasspath(): Builder {
            this.strictClasspathMode = true
            return this
        }

        /**
         * Set the message to display when a missing indirect dependency is found.
         * 
         * @param fixMessage the fix message
         * @return this Builder instance
         */
        @CanIgnoreReturnValue
        fun setFixMessage(fixMessage: FixMessage?): Builder {
            this.fixMessage = fixMessage
            return this
        }

        /**
         * Add a generator to the exempt set.
         * 
         * @param exemptGenerator the generator class name
         * @return this Builder instance
         */
        @CanIgnoreReturnValue
        fun addExemptGenerator(exemptGenerator: String?): Builder {
            exemptGenerators.add(exemptGenerator)
            return this
        }
    }

    companion object {
        private val SJD_EXEMPT_PROCESSORS: ImmutableSet<String?> =
            ImmutableSet.of<String?>( // Relax strict deps for dagger-generated code (b/17979436).
                "dagger.internal.codegen.ComponentProcessor",  // Relax strict deps for Hilt-generated code (b/21307381).
                "dagger.hilt.processor.internal.root.RootProcessor"
            )
    }
}
