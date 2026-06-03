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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType.ABSTRACT

/**
 * Knows about every rule Blaze supports and the associated configuration options.
 * 
 * 
 * This class is initialized on server startup and the set of rules, build info factories and
 * configuration options is guaranteed not to change over the life time of the Blaze server.
 */
// This class has no subclasses except those created by the evil that is mockery.
/*final*/class ConfiguredRuleClassProvider
private constructor(
    preludeLabel: Label?,
    runfilesPrefix: String?,
    toolsRepository: RepositoryName?,
    bundledBuiltinsRoot: Root?,
    builtinsBzlPackagePathInSource: String?,
    ruleClassMap: com.google.common.collect.ImmutableMap<String?, RuleClass?>?,
    ruleDefinitionMap: com.google.common.collect.ImmutableMap<String?, RuleDefinition?>,
    nativeAspectClassMap: com.google.common.collect.ImmutableMap<String?, NativeAspectClass?>,
    fragmentRegistry: FragmentRegistry,
    trimmingTransitionFactory: TransitionFactory<RuleTransitionData?>?,
    toolchainTaggedTrimmingTransition: PatchTransition?,
    shouldInvalidateCacheForOptionDiff: OptionsDiffPredicate,
    prerequisiteValidator: PrerequisiteValidator,
    buildFileToplevels: com.google.common.collect.ImmutableMap<String?, Any?>?,
    starlarkAccessibleTopLevels: com.google.common.collect.ImmutableMap<String?, Any?>,
    starlarkBuiltinsInternals: com.google.common.collect.ImmutableMap<String?, Any?>?,
    starlarkBootstraps: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.starlarkbuildapi.core.Bootstrap>,
    symlinkDefinitions: com.google.common.collect.ImmutableList<SymlinkDefinition?>?,
    reservedActionMnemonics: com.google.common.collect.ImmutableSet<String?>?,
    actionEnvironmentProvider: java.util.function.Function<BuildOptions?, ActionEnvironment?>,
    constraintSemantics: ConstraintSemantics<RuleContext?>?,
    networkAllowlistForTests: Label?
) : RuleClassProvider, GlobalStateProvider {
    /**
     * A coherent set of options, fragments, aspects and rules; each of these may declare a dependency
     * on other such sets.
     */
    interface RuleSet {
        /** Add stuff to the configured rule class provider builder.  */
        fun init(builder: Builder?)

        /** List of required modules.  */
        fun requires(): com.google.common.collect.ImmutableList<RuleSet?> {
            return com.google.common.collect.ImmutableList.of<RuleSet?>()
        }
    }

    /** An InMemoryFileSystem for bundled builtins .bzl files.  */
    class BundledFileSystem : InMemoryFileSystem(DigestHashFunction.SHA256) {
        // Pretend the digest of a bundled file is uniquely determined by its name, not its contents.
        //
        // The contents bundled files are guaranteed to not change throughout the lifetime of the Bazel
        // server, we do not need to detect changes to a bundled file's contents. Not needing to worry
        // about get the actual digest and detect changes to that digest helps avoid peculiarities in
        // the interaction of InMemoryFileSystem and Skyframe. See cl/354809138 for further discussion,
        // including of possible (but unlikely) future caveats of this approach.
        //
        // On the other hand, we do need to want different bundled files to have different digests. That
        // way the bzl environment hashes for bzl rule classes defined in two different bundled files
        // are guaranteed to be different, even if their set of transitive load statements is the same.
        // This is important because it's possible for bzl rule classes defined in different files to
        // have the same name string, and various part of Blaze rely on the pair of
        // "rule class name string" and "bzl environment hash" to uniquely identify a bzl rule class.
        // See b/226379109 for details.
        @kotlin.jvm.Synchronized
        public override fun getFastDigest(path: PathFragment): ByteArray {
            return getDigest(path)
        }

        @kotlin.jvm.Synchronized
        public override fun getDigest(path: PathFragment): ByteArray {
            return getDigestFunction()
                .getHashFunction()
                .hashBytes(StringUnsafe.getInternalStringBytes(path.getPathString()))
                .asBytes()
        }
    }

    /** Builder for [ConfiguredRuleClassProvider].  */
    class Builder : RuleDefinitionEnvironment {
        private var preludeLabel: Label? = null
        private var runfilesPrefix: String? = null
        private var toolsRepository: RepositoryName? = null
        private var builtinsBzlZipResource: String? = null
        private var useDummyBuiltinsBzlInsteadOfResource = false
        private var builtinsBzlPackagePathInSource: String? = null
        private val configurationFragmentClasses: MutableList<java.lang.Class<out com.google.devtools.build.lib.analysis.config.Fragment?>?> =
            java.util.ArrayList<java.lang.Class<out com.google.devtools.build.lib.analysis.config.Fragment?>?>()
        private val configurationOptions: MutableList<java.lang.Class<out FragmentOptions?>?> =
            java.util.ArrayList<java.lang.Class<out FragmentOptions?>?>()

        private val ruleClassMap: MutableMap<String?, RuleClass?> = LinkedHashMap<String?, RuleClass?>()
        private val ruleDefinitionMap: MutableMap<String?, RuleDefinition?> = LinkedHashMap<String?, RuleDefinition?>()
        private val nativeAspectClassMap: MutableMap<String?, NativeAspectClass?> =
            LinkedHashMap<String?, NativeAspectClass?>()
        private val ruleMap: MutableMap<java.lang.Class<out RuleDefinition?>?, RuleClass?> =
            LinkedHashMap<java.lang.Class<out RuleDefinition?>?, RuleClass?>()
        private val dependencyGraph: Digraph<java.lang.Class<out RuleDefinition?>?> =
            Digraph<java.lang.Class<out RuleDefinition?>?>()
        private val universalFragments: MutableList<java.lang.Class<out com.google.devtools.build.lib.analysis.config.Fragment?>?> =
            java.util.ArrayList<java.lang.Class<out com.google.devtools.build.lib.analysis.config.Fragment?>?>()
        private var trimmingTransitionFactory: TransitionFactory<RuleTransitionData?>? = null
        private var toolchainTaggedTrimmingTransition: PatchTransition? = null
        private var shouldInvalidateCacheForOptionDiff: OptionsDiffPredicate = OptionsDiffPredicate.ALWAYS_INVALIDATE
        private var prerequisiteValidator: PrerequisiteValidator? = null
        private val buildFileToplevels: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
            com.google.common.collect.ImmutableMap.builder<String?, Any?>()
        private val starlarkBootstraps: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.starlarkbuildapi.core.Bootstrap?> =
            com.google.common.collect.ImmutableList.builder<com.google.devtools.build.lib.starlarkbuildapi.core.Bootstrap?>()
        private val starlarkAccessibleTopLevels: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
            com.google.common.collect.ImmutableMap.builder<String?, Any?>()
        private val starlarkBuiltinsInternals: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
            com.google.common.collect.ImmutableMap.builder<String?, Any?>()
        private val symlinkDefinitions: com.google.common.collect.ImmutableList.Builder<SymlinkDefinition?> =
            com.google.common.collect.ImmutableList.builder<SymlinkDefinition?>()
        private val reservedActionMnemonics: MutableSet<String?> = TreeSet<String?>()
        private var actionEnvironmentProvider: java.util.function.Function<BuildOptions?, ActionEnvironment?> =
            java.util.function.Function { options: BuildOptions? -> ActionEnvironment.EMPTY }
        private var constraintSemantics: ConstraintSemantics<RuleContext?>? = RuleContextConstraintSemantics()

        // TODO(b/192694287): Remove once we migrate all tests from the allowlist
        private var networkAllowlistForTests: Label? = null

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setPrelude(preludeLabelString: String): Builder {
            com.google.common.base.Preconditions.checkArgument(
                preludeLabelString.startsWith("//"),
                "Prelude label '%s' must start with '//'",
                preludeLabelString
            )
            try {
                // We're parsing this label as if it's in the main repository but it will actually get
                // massaged into a label in the repository where the package being loaded resides.
                this.preludeLabel = Label.parseCanonical(preludeLabelString)
            } catch (e: LabelSyntaxException) {
                val errorMsg: String? =
                    java.lang.String.format("Prelude label '%s' is invalid: %s", preludeLabelString, e.getMessage())
                throw java.lang.IllegalArgumentException(errorMsg)
            }
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setRunfilesPrefix(runfilesPrefix: String?): Builder {
            this.runfilesPrefix = runfilesPrefix
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setToolsRepository(toolsRepository: RepositoryName?): Builder {
            this.toolsRepository = toolsRepository
            return this
        }

        /**
         * Sets the resource path to the builtins_bzl.zip resource.
         * 
         * 
         * This value is required for production uses. For uses in tests, this may be left null, but
         * the resulting rule class provider will not work with `--experimental_builtins_bzl_path=%bundled%`. Alternatively, tests may call [ ][.useDummyBuiltinsBzl] if they do not rely on any native rules that may be migratable to
         * Starlark.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setBuiltinsBzlZipResource(name: String?): Builder {
            this.builtinsBzlZipResource = name
            this.useDummyBuiltinsBzlInsteadOfResource = false
            return this
        }

        /**
         * Instructs the rule class provider to use a set of dummy builtins definitions that inject no
         * symbols.
         * 
         * 
         * This is only suitable for use in tests, and only when the test does not depend (even
         * implicitly) on native rules. For example, pure tests of package loading behavior may call
         * this method, but not tests that use AnalysisMock. Otherwise the test may break when a native
         * rule is migrated to Starlark via builtins injection.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun useDummyBuiltinsBzl(): Builder {
            this.builtinsBzlZipResource = null
            this.useDummyBuiltinsBzlInsteadOfResource = true
            return this
        }

        /**
         * Sets the relative location of the builtins_bzl directory within a Bazel source tree.
         * 
         * 
         * This is required if the rule class provider will be used with `--experimental_builtins_bzl_path=%workspace%`, but can be skipped in unit tests.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setBuiltinsBzlPackagePathInSource(path: String?): Builder {
            this.builtinsBzlPackagePathInSource = path
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setPrerequisiteValidator(prerequisiteValidator: PrerequisiteValidator): Builder {
            this.prerequisiteValidator = prerequisiteValidator
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addRuleDefinition(ruleDefinition: RuleDefinition): Builder {
            val ruleDefinitionClass: java.lang.Class<out RuleDefinition?> = ruleDefinition.getClass()
            ruleDefinitionMap.put(ruleDefinitionClass.getName(), ruleDefinition)
            dependencyGraph.createNode(ruleDefinitionClass)
            for (ancestor in ruleDefinition.getMetadata().ancestors) {
                dependencyGraph.addEdge(ancestor, ruleDefinitionClass)
            }

            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addNativeAspectClass(aspectFactoryClass: NativeAspectClass): Builder {
            nativeAspectClassMap.put(aspectFactoryClass.getName(), aspectFactoryClass)
            return this
        }

        /**
         * Adds a configuration fragment and all build options required by its fragment.
         * 
         * 
         * Note that configuration fragments annotated with a Starlark name must have a unique name;
         * no two different configuration fragments can share the same name.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addConfigurationFragment(fragmentClass: java.lang.Class<out com.google.devtools.build.lib.analysis.config.Fragment?>?): Builder {
            configurationFragmentClasses.add(fragmentClass)
            return this
        }

        /**
         * Adds configuration options that aren't required by configuration fragments.
         * 
         * 
         * If [.addConfigurationFragment] adds a fragment that also requires these options,
         * this method is redundant.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addConfigurationOptions(configurationOptions: java.lang.Class<out FragmentOptions?>?): Builder {
            this.configurationOptions.add(configurationOptions)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addUniversalConfigurationFragment(fragment: java.lang.Class<out com.google.devtools.build.lib.analysis.config.Fragment?>?): Builder {
            this.universalFragments.add(fragment)
            addConfigurationFragment(fragment)
            return this
        }

        /**
         * Registers a new top-level symbol for BUILD files.
         * 
         * 
         * The symbol will also be available in BUILD-loaded .bzl files under the `native`
         * module.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addBuildFileToplevel(name: String?, `object`: Any?): Builder {
            this.buildFileToplevels.put(name, `object`)
            return this
        }

        /**
         * Registers all symbols contained in the `Bootstrap` as top-level symbols for .bzl files.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addStarlarkBootstrap(bootstrap: com.google.devtools.build.lib.starlarkbuildapi.core.Bootstrap): Builder {
            this.starlarkBootstraps.add(bootstrap)
            return this
        }

        /** Registers a new top-level symbol for .bzl files.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addBzlToplevel(name: String?, `object`: Any?): Builder {
            this.starlarkAccessibleTopLevels.put(name, `object`)
            return this
        }

        /**
         * Registers a new symbol for `@_builtins` .bzl files, to be made available under the
         * `_builtins.internal` object.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addStarlarkBuiltinsInternal(name: String?, `object`: Any?): Builder {
            this.starlarkBuiltinsInternals.put(name, `object`)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addSymlinkDefinition(symlinkDefinition: SymlinkDefinition): Builder {
            this.symlinkDefinitions.add(symlinkDefinition)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addReservedActionMnemonic(mnemonic: String?): Builder {
            this.reservedActionMnemonics.add(mnemonic)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setActionEnvironmentProvider(
            actionEnvironmentProvider: java.util.function.Function<BuildOptions?, ActionEnvironment?>
        ): Builder {
            this.actionEnvironmentProvider = actionEnvironmentProvider
            return this
        }

        /**
         * Sets the logic that lets rules declare which environments they support and validates rules
         * don't depend on rules that aren't compatible with the same environments. Defaults to [ ]. See [ConstraintSemantics] for more details.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setConstraintSemantics(constraintSemantics: ConstraintSemantics<RuleContext?>?): Builder {
            this.constraintSemantics = constraintSemantics
            return this
        }

        /**
         * Adds a transition factory that produces a trimming transition to be run over all targets
         * after other transitions.
         * 
         * 
         * Transitions are run in the order they're added.
         * 
         * 
         * This is a temporary measure for supporting trimming of test rules and manual trimming of
         * feature flags, and support for this transition factory will likely be removed at some point
         * in the future (whenever automatic trimming is sufficiently workable).
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addTrimmingTransitionFactory(factory: TransitionFactory<RuleTransitionData?>?): Builder {
            com.google.common.base.Preconditions.checkNotNull<Any?>(factory)
            com.google.common.base.Preconditions.checkArgument(!factory.isSplit())
            if (trimmingTransitionFactory == null) {
                trimmingTransitionFactory = factory
            } else {
                trimmingTransitionFactory =
                    ComposingTransitionFactory.of(trimmingTransitionFactory, factory)
            }
            return this
        }

        /** Sets the transition manual feature flag trimming should apply to toolchain deps.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setToolchainTaggedTrimmingTransition(transition: PatchTransition?): Builder {
            com.google.common.base.Preconditions.checkNotNull<Any?>(transition)
            com.google.common.base.Preconditions.checkState(toolchainTaggedTrimmingTransition == null)
            this.toolchainTaggedTrimmingTransition = transition
            return this
        }

        /**
         * Overrides the transition factory run over all targets.
         * 
         * @see .addTrimmingTransitionFactory
         */
        @com.google.common.annotations.VisibleForTesting
        fun overrideTrimmingTransitionFactoryForTesting(
            factory: TransitionFactory<RuleTransitionData?>?
        ): Builder {
            trimmingTransitionFactory = null
            return this.addTrimmingTransitionFactory(factory)
        }

        /**
         * Sets the predicate which determines whether the analysis cache should be invalidated for the
         * given options diff.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setShouldInvalidateCacheForOptionDiff(
            shouldInvalidateCacheForOptionDiff: OptionsDiffPredicate
        ): Builder {
            com.google.common.base.Preconditions.checkState(
                this.shouldInvalidateCacheForOptionDiff.equals(OptionsDiffPredicate.ALWAYS_INVALIDATE),
                "Cache invalidation function was already set"
            )
            this.shouldInvalidateCacheForOptionDiff = shouldInvalidateCacheForOptionDiff
            return this
        }

        /**
         * Overrides the predicate which determines whether the analysis cache should be invalidated for
         * the given options diff.
         */
        @com.google.common.annotations.VisibleForTesting
        fun overrideShouldInvalidateCacheForOptionDiffForTesting(
            shouldInvalidateCacheForOptionDiff: OptionsDiffPredicate
        ): Builder {
            this.shouldInvalidateCacheForOptionDiff = OptionsDiffPredicate.ALWAYS_INVALIDATE
            return this.setShouldInvalidateCacheForOptionDiff(shouldInvalidateCacheForOptionDiff)
        }

        private fun commitRuleDefinition(definitionClass: java.lang.Class<out RuleDefinition?>) {
            val instance: RuleDefinition =
                com.google.common.base.Preconditions.checkNotNull<RuleDefinition>(
                    ruleDefinitionMap.get(definitionClass.getName()),
                    "addRuleDefinition(new %s()) should be called before build()",
                    definitionClass.getName()
                )

            val metadata: com.google.devtools.build.lib.analysis.RuleDefinition.Metadata = instance.getMetadata()
            com.google.common.base.Preconditions.checkArgument(
                ruleClassMap.get(metadata.name) == null,
                "The rule %s was committed already, use another name",
                metadata.name
            )

            val ancestors: MutableList<java.lang.Class<out RuleDefinition?>?> = metadata.ancestors

            com.google.common.base.Preconditions.checkArgument(
                (metadata.type === ABSTRACT)
                        xor (metadata.factoryClass != RuleConfiguredTargetFactory::class.java)
            )

            val ancestorClasses: Array<RuleClass?> = arrayOfNulls<RuleClass>(ancestors.size())
            for (i in ancestorClasses.indices) {
                ancestorClasses[i] = ruleMap.get(ancestors.get(i))
                checkNotNull(ancestorClasses[i]) { "Ancestor " + ancestors.get(i) + " of " + metadata.name + " is not initialized" }
            }

            var factory: RuleConfiguredTargetFactory? = null
            if (metadata.type !== ABSTRACT) {
                factory =
                    com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider.Builder.Companion.createFactory(
                        metadata.factoryClass
                    )
            }

            val builder: RuleClass.Builder =
                Builder(metadata.name, metadata.type, false, ancestorClasses)
            builder.factory(factory)
            val ruleClass: RuleClass = instance.build(builder, this)
            ruleMap.put(definitionClass, ruleClass)
            ruleClassMap.put(ruleClass.getName(), ruleClass)
            ruleDefinitionMap.put(ruleClass.getName(), instance)
        }

        fun build(): ConfiguredRuleClassProvider {
            for (ruleDefinition in dependencyGraph.topologicalOrder) {
                commitRuleDefinition(ruleDefinition.label)
            }

            // Determine the bundled builtins root, if it exists.
            val builtinsRoot: Root?
            if (builtinsBzlZipResource == null && !useDummyBuiltinsBzlInsteadOfResource) {
                // Use of --experimental_builtins_bzl_path=%bundled% is disallowed.
                builtinsRoot = null
            } else {
                val fs = BundledFileSystem()
                val builtinsPath: Path = fs.getPath("/virtual_builtins_bzl")
                if (builtinsBzlZipResource != null) {
                    // Production case.
                    com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider.Builder.Companion.unpackBuiltinsBzlZipResource(
                        builtinsBzlZipResource,
                        builtinsPath
                    )
                } else {
                    // Dummy case, use empty bundled builtins content.
                    try {
                        builtinsPath.createDirectoryAndParents()
                        builtinsPath.getRelative("exports.bzl").getOutputStream().use { os ->
                            val emptyExports =
                                (("exported_rules = {}\n" //
                                        + "exported_toplevels = {}\n"
                                        + "exported_to_java = {}\n"))
                            os.write(emptyExports.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        }
                    } catch (ex: IOException) {
                        throw java.lang.IllegalStateException("Failed to write dummy builtins root", ex)
                    }
                }
                builtinsRoot = Root.fromPath(builtinsPath)
            }

            return ConfiguredRuleClassProvider(
                preludeLabel,
                runfilesPrefix,
                toolsRepository,
                builtinsRoot,
                builtinsBzlPackagePathInSource,
                com.google.common.collect.ImmutableMap.copyOf<String?, RuleClass?>(ruleClassMap),
                com.google.common.collect.ImmutableMap.copyOf<String?, RuleDefinition?>(ruleDefinitionMap),
                com.google.common.collect.ImmutableMap.copyOf<String?, NativeAspectClass?>(nativeAspectClassMap),
                FragmentRegistry.create(
                    configurationFragmentClasses, universalFragments, configurationOptions
                ),
                trimmingTransitionFactory,
                toolchainTaggedTrimmingTransition,
                shouldInvalidateCacheForOptionDiff,
                prerequisiteValidator,
                buildFileToplevels.buildOrThrow(),
                starlarkAccessibleTopLevels.buildOrThrow(),
                starlarkBuiltinsInternals.buildOrThrow(),
                starlarkBootstraps.build(),
                symlinkDefinitions.build(),
                com.google.common.collect.ImmutableSet.copyOf<String?>(reservedActionMnemonics),
                actionEnvironmentProvider,
                constraintSemantics,
                networkAllowlistForTests
            )
        }

        public override fun getToolsRepository(): RepositoryName? {
            return toolsRepository
        }

        public override fun getNetworkAllowlistForTests(): java.util.Optional<Label?> {
            return java.util.Optional.ofNullable<Label?>(networkAllowlistForTests)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setNetworkAllowlistForTests(allowlist: Label?): Builder {
            networkAllowlistForTests = allowlist
            return this
        }

        companion object {
            private fun createFactory(
                factoryClass: java.lang.Class<out RuleConfiguredTargetFactory?>
            ): RuleConfiguredTargetFactory? {
                try {
                    val ctor: java.lang.reflect.Constructor<out RuleConfiguredTargetFactory?> =
                        factoryClass.getConstructor()
                    return ctor.newInstance()
                } catch (e: java.lang.ReflectiveOperationException) {
                    throw java.lang.IllegalStateException(e)
                }
            }

            /**
             * Locates the builtins zip file as a Java resource, and unpacks it into the given directory.
             * Note that the builtins_bzl/ entry itself in the zip is not copied, just its children.
             */
            private fun unpackBuiltinsBzlZipResource(builtinsResourceName: String?, targetRoot: Path) {
                val loader: java.lang.ClassLoader = ConfiguredRuleClassProvider::class.java.getClassLoader()
                try {
                    loader.getResourceAsStream(builtinsResourceName).use { builtinsZip ->
                        com.google.common.base.Preconditions.checkArgument(
                            builtinsZip != null, "No resource with name %s", builtinsResourceName
                        )
                        ZipInputStream(builtinsZip).use { zip ->
                            var entry: ZipEntry? = zip.getNextEntry()
                            while (entry != null) {
                                val entryName: String = entry.getName()
                                com.google.common.base.Preconditions.checkArgument(entryName.startsWith("builtins_bzl/"))
                                val dest: Path = targetRoot.getRelative(entryName.substring("builtins_bzl/".length()))

                                dest.getParentDirectory().createDirectoryAndParents()
                                dest.getOutputStream().use { os ->
                                    com.google.common.io.ByteStreams.copy(zip, os)
                                }
                                entry = zip.getNextEntry()
                            }
                        }
                    }
                } catch (ex: IOException) {
                    throw java.lang.IllegalArgumentException(
                        "Error while unpacking builtins_bzl zip resource file", ex
                    )
                }
            }
        }
    }

    /** Label for the prelude file.  */
    private val preludeLabel: Label?

    /** The default runfiles prefix.  */
    private val runfilesPrefix: String?

    /** The path to the tools repository.  */
    private val toolsRepository: RepositoryName?

    /**
     * Where the builtins bzl files are located (if not overridden by
     * --experimental_builtins_bzl_path). Note that this lives in a separate InMemoryFileSystem.
     * 
     * 
     * May be null in tests, in which case --experimental_builtins_bzl_path must point to a
     * builtins root.
     */
    private val bundledBuiltinsRoot: Root?

    /**
     * The relative location of the builtins_bzl directory within a Bazel source tree.
     * 
     * 
     * May be null in tests, in which case --experimental_builtins_bzl_path may not be
     * "%workspace%".
     */
    private val builtinsBzlPackagePathInSource: String?

    /** Maps rule class name to the metaclass instance for that rule.  */
    private val ruleClassMap: com.google.common.collect.ImmutableMap<String?, RuleClass?>?

    /** Maps rule class name to the rule definition objects.  */
    private val ruleDefinitionMap: com.google.common.collect.ImmutableMap<String?, RuleDefinition?>

    /** Maps aspect name to the aspect factory meta class.  */
    private val nativeAspectClassMap: com.google.common.collect.ImmutableMap<String?, NativeAspectClass?>

    private val fragmentRegistry: FragmentRegistry?

    /** The transition factory used to produce the transition that will trim targets.  */
    private val trimmingTransitionFactory: TransitionFactory<RuleTransitionData?>?

    /** The transition to apply to toolchain deps for manual trimming.  */
    private val toolchainTaggedTrimmingTransition: PatchTransition?

    /** The predicate used to determine whether a diff requires the cache to be invalidated.  */
    private val shouldInvalidateCacheForOptionDiff: OptionsDiffPredicate

    private val prerequisiteValidator: PrerequisiteValidator

    private val bazelStarlarkEnvironment: BazelStarlarkEnvironment

    private val symlinkDefinitions: com.google.common.collect.ImmutableList<SymlinkDefinition?>?

    private val reservedActionMnemonics: com.google.common.collect.ImmutableSet<String?>?

    private val actionEnvironmentProvider: java.util.function.Function<BuildOptions?, ActionEnvironment?>

    private val configurationFragmentMap: com.google.common.collect.ImmutableMap<String?, java.lang.Class<*>?>

    private val constraintSemantics: ConstraintSemantics<RuleContext?>?

    // TODO(b/192694287): Remove once we migrate all tests from the allowlist
    private val networkAllowlistForTests: Label?

    init {
        this.preludeLabel = preludeLabel
        this.runfilesPrefix = runfilesPrefix
        this.toolsRepository = toolsRepository
        this.bundledBuiltinsRoot = bundledBuiltinsRoot
        this.builtinsBzlPackagePathInSource = builtinsBzlPackagePathInSource
        this.ruleClassMap = ruleClassMap
        this.ruleDefinitionMap = ruleDefinitionMap
        this.nativeAspectClassMap = nativeAspectClassMap
        this.fragmentRegistry = fragmentRegistry
        this.trimmingTransitionFactory = trimmingTransitionFactory
        this.toolchainTaggedTrimmingTransition = toolchainTaggedTrimmingTransition
        this.shouldInvalidateCacheForOptionDiff = shouldInvalidateCacheForOptionDiff
        this.prerequisiteValidator = prerequisiteValidator
        this.symlinkDefinitions = symlinkDefinitions
        this.reservedActionMnemonics = reservedActionMnemonics
        this.actionEnvironmentProvider = actionEnvironmentProvider
        this.configurationFragmentMap = createFragmentMap(fragmentRegistry.getAllFragments())
        this.constraintSemantics = constraintSemantics
        this.networkAllowlistForTests = networkAllowlistForTests

        val registeredBzlToplevels: com.google.common.collect.ImmutableMap<String?, Any?> =
            createRegisteredBzlToplevels(starlarkAccessibleTopLevels, starlarkBootstraps)
        // If needed, we could allow the version to be customized by the builder e.g. for unit testing,
        // but at the moment it suffices to use the production value unconditionally.
        val version: String? = com.google.devtools.build.lib.analysis.BlazeVersionInfo.Companion.instance().getVersion()
        this.bazelStarlarkEnvironment =
            BazelStarlarkEnvironment(
                StarlarkGlobalsImpl.Companion.INSTANCE,
                version,  /* ruleFunctions= */
                RuleFactory.buildRuleFunctions(ruleClassMap),
                buildFileToplevels,
                registeredBzlToplevels,  /* builtinsInternals= */
                starlarkBuiltinsInternals
            )
    }

    fun getPrerequisiteValidator(): PrerequisiteValidator {
        return prerequisiteValidator
    }

    public override fun getPreludeLabel(): Label? {
        return preludeLabel
    }

    public override fun isPackageUnderExperimental(packageIdentifier: PackageIdentifier?): Boolean {
        return prerequisiteValidator.packageUnderExperimental(packageIdentifier)
    }

    public override fun isPackageUnderPrototypes(packageIdentifier: PackageIdentifier?): Boolean {
        return prerequisiteValidator.packageUnderPrototypes(packageIdentifier)
    }

    public override fun mayPackageDependOnPrototypes(packageIdentifier: PackageIdentifier?): Boolean {
        return prerequisiteValidator.mayDependOnPrototypes(packageIdentifier)
    }

    override fun getRunfilesPrefix(): String? {
        return runfilesPrefix
    }

    public override fun getToolsRepository(): RepositoryName? {
        return toolsRepository
    }

    public override fun getBundledBuiltinsRoot(): Root? {
        return bundledBuiltinsRoot
    }

    public override fun getBuiltinsBzlPackagePathInSource(): String? {
        return builtinsBzlPackagePathInSource
    }

    public override fun getRuleClassMap(): com.google.common.collect.ImmutableMap<String?, RuleClass?>? {
        return ruleClassMap
    }

    public override fun getNativeAspectClassMap(): MutableMap<String?, NativeAspectClass?> {
        return nativeAspectClassMap
    }

    public override fun getNativeAspectClass(key: String?): NativeAspectClass? {
        return nativeAspectClassMap.get(key)
    }

    override fun getFragmentRegistry(): FragmentRegistry? {
        return fragmentRegistry
    }

    /**
     * Returns the transition factory used to produce the transition to trim targets.
     * 
     * 
     * This is a temporary measure for supporting manual trimming of feature flags, and support for
     * this transition factory will likely be removed at some point in the future (whenever automatic
     * trimming is sufficiently workable
     */
    fun getTrimmingTransitionFactory(): TransitionFactory<RuleTransitionData?>? {
        return trimmingTransitionFactory
    }

    /**
     * Returns the transition manual feature flag trimming should apply to toolchain deps.
     * 
     * 
     * See extra notes on [.getTrimmingTransitionFactory].
     */
    fun getToolchainTaggedTrimmingTransition(): PatchTransition? {
        return toolchainTaggedTrimmingTransition
    }

    /** Returns whether the analysis cache should be invalidated for the given option diff.  */
    fun shouldInvalidateCacheForOptionDiff(
        newOptions: BuildOptions?, changedOption: OptionDefinition?, oldValue: Any?, newValue: Any?
    ): Boolean {
        return shouldInvalidateCacheForOptionDiff.apply(newOptions, changedOption, oldValue, newValue)
    }

    /** Returns the definition of the rule class definition with the specified name.  */
    fun getRuleClassDefinition(ruleClassName: String?): RuleDefinition? {
        return ruleDefinitionMap.get(ruleClassName)
    }

    public override fun getBazelStarlarkEnvironment(): BazelStarlarkEnvironment {
        return bazelStarlarkEnvironment
    }

    public override fun getConfigurationFragmentMap(): com.google.common.collect.ImmutableMap<String?, java.lang.Class<*>?> {
        return configurationFragmentMap
    }

    /**
     * Returns the symlink definitions introduced by the fragments registered with this rule class
     * provider.
     * 
     * 
     * This only includes definitions added by [Builder.addSymlinkDefinition], not the
     * standard symlinks in [com.google.devtools.build.lib.buildtool.OutputDirectoryLinksUtils].
     * 
     * 
     * Note: Usages of custom symlink definitions should be very rare. This feature was added to
     * implement the py2-bin / py3-bin symlinks, which have since been removed from Bazel.
     */
    // TODO(bazel-team): Delete?
    fun getSymlinkDefinitions(): com.google.common.collect.ImmutableList<SymlinkDefinition?>? {
        return symlinkDefinitions
    }

    fun getConstraintSemantics(): ConstraintSemantics<RuleContext?>? {
        return constraintSemantics
    }

    public override fun getNetworkAllowlistForTests(): java.util.Optional<Label?> {
        return java.util.Optional.ofNullable<Label?>(networkAllowlistForTests)
    }

    /** Returns a reserved set of action mnemonics. These cannot be used from a Starlark action.  */
    override fun getReservedActionMnemonics(): com.google.common.collect.ImmutableSet<String?>? {
        return reservedActionMnemonics
    }

    override fun getActionEnvironment(buildOptions: BuildOptions?): ActionEnvironment? {
        return actionEnvironmentProvider.apply(buildOptions)
    }

    companion object {
        private fun createRegisteredBzlToplevels(
            starlarkAccessibleTopLevels: com.google.common.collect.ImmutableMap<String?, Any?>,
            bootstraps: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.starlarkbuildapi.core.Bootstrap>
        ): com.google.common.collect.ImmutableMap<String?, Any?> {
            val bindings: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
                com.google.common.collect.ImmutableMap.builder<String?, Any?>()
            bindings.putAll(starlarkAccessibleTopLevels)
            for (bootstrap in bootstraps) {
                bootstrap.addBindingsToBuilder(bindings)
            }
            return bindings.buildOrThrow()
        }

        private fun createFragmentMap(
            configurationFragments: FragmentClassSet
        ): com.google.common.collect.ImmutableMap<String?, java.lang.Class<*>?> {
            val mapBuilder: com.google.common.collect.ImmutableMap.Builder<String?, java.lang.Class<*>?> =
                com.google.common.collect.ImmutableMap.builder<String?, java.lang.Class<*>?>()
            for (fragmentClass in configurationFragments) {
                val fragmentModule: StarlarkBuiltin? = StarlarkAnnotations.getStarlarkBuiltin(fragmentClass)
                if (fragmentModule != null) {
                    mapBuilder.put(fragmentModule.name, fragmentClass)
                }
            }
            return mapBuilder.buildOrThrow()
        }
    }
}
