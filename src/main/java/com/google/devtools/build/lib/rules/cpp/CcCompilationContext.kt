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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.actions.Artifact

/**
 * Immutable store of information needed for C++ compilation that is aggregated across dependencies.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class CcCompilationContext private constructor(starlarkInfo: StarlarkInfo) {
    private val starlarkInfo: StarlarkInfo

    init {
        this.starlarkInfo = starlarkInfo
    }

    val starlarkHeaders: Depset
        get() {
            try {
                return starlarkInfo.getValue("headers", Depset::class.java)
            } catch (e: net.starlark.java.eval.EvalException) {
                throw java.lang.IllegalStateException(e)
            }
        }

    private fun getPathFragmentList(fieldName: String?): com.google.common.collect.ImmutableList<PathFragment?> {
        try {
            return starlarkInfo.getValue(fieldName, Depset::class.java).getSet(String::class.java).toList().stream()
                .map({ path: String? -> PathFragment.create(path) })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        } catch (e: net.starlark.java.eval.EvalException) {
            throw java.lang.IllegalStateException(e)
        } catch (e: Depset.TypeException) {
            throw java.lang.IllegalStateException(e)
        }
    }

    val includeDirs: com.google.common.collect.ImmutableList<PathFragment?>
        /**
         * Returns the immutable list of include directories to be added with "-I" (possibly empty but
         * never null). This includes the include dirs from the transitive deps closure of the target.
         * This list does not contain duplicates. All fragments are either absolute or relative to the
         * exec root (see [ ][com.google.devtools.build.lib.analysis.BlazeDirectories.getExecRoot]).
         */
        get() = getPathFragmentList("includes")

    val quoteIncludeDirs: com.google.common.collect.ImmutableList<PathFragment?>
        /**
         * Returns the immutable list of include directories to be added with "-iquote" (possibly empty
         * but never null). This includes the include dirs from the transitive deps closure of the target.
         * This list does not contain duplicates. All fragments are either absolute or relative to the
         * exec root (see [ ][com.google.devtools.build.lib.analysis.BlazeDirectories.getExecRoot]).
         */
        get() = getPathFragmentList("quote_includes")

    val systemIncludeDirs: com.google.common.collect.ImmutableList<PathFragment?>
        /**
         * Returns the immutable list of include directories to be added with "-isystem" (possibly empty
         * but never null). This includes the include dirs from the transitive deps closure of the target.
         * This list does not contain duplicates. All fragments are either absolute or relative to the
         * exec root (see [ ][com.google.devtools.build.lib.analysis.BlazeDirectories.getExecRoot]).
         */
        get() = getPathFragmentList("system_includes")

    val frameworkIncludeDirs: com.google.common.collect.ImmutableList<PathFragment?>
        /**
         * Returns the immutable list of include directories to be added with "-F" (possibly empty but
         * never null). This includes the include dirs from the transitive deps closure of the target.
         * This list does not contain duplicates. All fragments are either absolute or relative to the
         * exec root (see [com.google.devtools.build.lib.analysis.BlazeDirectories.getExecRoot]).
         */
        get() = getPathFragmentList("framework_includes")

    val externalIncludeDirs: com.google.common.collect.ImmutableList<PathFragment?>
        /**
         * Returns the immutable list of external include directories (possibly empty but never null).
         * This includes the include dirs from the transitive deps closure of the target. This list does
         * not contain duplicates. All fragments are either absolute or relative to the exec root (see
         * [com.google.devtools.build.lib.analysis.BlazeDirectories.getExecRoot]).
         */
        get() = getPathFragmentList("external_includes")

    val declaredIncludeSrcs: NestedSet<Artifact?>
        /**
         * Returns the immutable set of headers that have been declared in the `srcs` or `hdrs` attribute (possibly empty but never null).
         * 
         * 
         * Those are exactly transitive compilation prerequisites needed by all reverse dependencies;
         * note that these do specifically not include any compilation prerequisites that are only needed
         * by the rule itself (for example, compiled source files from the `srcs` attribute).
         * 
         * 
         * The returned set can be empty if there are no prerequisites.
         */
        get() {
            try {
                return starlarkInfo.getValue("headers", Depset::class.java).getSet(Artifact::class.java)
            } catch (e: net.starlark.java.eval.EvalException) {
                throw java.lang.IllegalStateException(e)
            } catch (e: Depset.TypeException) {
                throw java.lang.IllegalStateException(e)
            }
        }

    val nonCodeInputs: NestedSet<Artifact?>
        get() {
            try {
                return starlarkInfo.getValue("_non_code_inputs", Depset::class.java).getSet(Artifact::class.java)
            } catch (e: net.starlark.java.eval.EvalException) {
                throw java.lang.IllegalStateException(e)
            } catch (e: Depset.TypeException) {
                throw java.lang.IllegalStateException(e)
            }
        }

    val textualHdrs: com.google.common.collect.ImmutableList<Artifact?>
        /** Returns headers given as textual_hdrs in this target.  */
        get() {
            try {
                return starlarkInfo.getValue("direct_textual_headers", net.starlark.java.eval.StarlarkList::class.java)
                    .getImmutableList()
            } catch (e: net.starlark.java.eval.EvalException) {
                throw java.lang.IllegalStateException(e)
            }
        }

    val directPublicHdrs: com.google.common.collect.ImmutableList<Artifact?>
        /** Returns public headers (given as `hdrs`) in this target.  */
        get() {
            try {
                return starlarkInfo.getValue("direct_public_headers", net.starlark.java.eval.StarlarkList::class.java)
                    .getImmutableList()
            } catch (e: net.starlark.java.eval.EvalException) {
                throw java.lang.IllegalStateException(e)
            }
        }

    val directPrivateHdrs: com.google.common.collect.ImmutableList<Artifact?>
        /** Returns private headers (given as `srcs`) in this target.  */
        get() {
            try {
                return starlarkInfo.getValue("direct_private_headers", net.starlark.java.eval.StarlarkList::class.java)
                    .getImmutableList()
            } catch (e: net.starlark.java.eval.EvalException) {
                throw java.lang.IllegalStateException(e)
            }
        }

    val headerTokens: NestedSet<Artifact?>
        get() {
            try {
                return starlarkInfo.getValue("validation_artifacts", Depset::class.java).getSet(Artifact::class.java)
            } catch (e: net.starlark.java.eval.EvalException) {
                throw java.lang.IllegalStateException(e)
            } catch (e: Depset.TypeException) {
                throw java.lang.IllegalStateException(e)
            }
        }

    @get:com.google.common.annotations.VisibleForTesting
    val headerInfo: HeaderInfo
        get() {
            try {
                return starlarkInfo.getValue("_header_info", HeaderInfo::class.java)
            } catch (e: net.starlark.java.eval.EvalException) {
                throw java.lang.IllegalStateException(e)
            }
        }

    /** Helper class for creating include scanning header data.  */
    object IncludeScanningHeaderDataHelper {
        fun handleArtifact(
            artifact: Artifact,
            pathToLegalArtifact: MutableMap<PathFragment?, Artifact?>,
            treeArtifacts: java.util.ArrayList<Artifact?>
        ) {
            if (artifact.isTreeArtifact()) {
                treeArtifacts.add(artifact)
                return
            }
            pathToLegalArtifact.put(artifact.getExecPath(), artifact)
        }

        /**
         * Enter the TreeArtifactValues in each TreeArtifact into pathToLegalArtifact. Returns true on
         * success.
         * 
         * 
         * If a TreeArtifact's value is missing, returns false, and leave pathToLegalArtifact
         * unmodified.
         */
        @Throws(java.lang.InterruptedException::class)
        fun handleTreeArtifacts(
            env: SkyFunction.Environment,
            pathToLegalArtifact: MutableMap<PathFragment?, Artifact?>,
            treeArtifacts: java.util.ArrayList<Artifact?>
        ): Boolean {
            if (treeArtifacts.isEmpty()) {
                return true
            }
            val result: SkyframeLookupResult = env.getValuesAndExceptions(treeArtifacts)
            if (env.valuesMissing()) {
                return false
            }
            for (treeArtifact in treeArtifacts) {
                val value: SkyValue? = result.get(treeArtifact)
                if (value == null) {
                    BugReport.sendBugReport(
                        java.lang.IllegalStateException(
                            "Some value from " + treeArtifacts + " was missing, this should never happen"
                        )
                    )
                    return false
                }
                com.google.common.base.Preconditions.checkState(
                    value is TreeArtifactValue, "SkyValue %s is not TreeArtifactValue", value
                )
                val treeArtifactValue: TreeArtifactValue = value as TreeArtifactValue
                for (treeFileArtifact in treeArtifactValue.getChildren()) {
                    pathToLegalArtifact.put(treeFileArtifact.getExecPath(), treeFileArtifact)
                }
            }
            return true
        }
    }

    /**
     * This method returns null when a required SkyValue is missing and a Skyframe restart is
     * required.
     */
    @Throws(java.lang.InterruptedException::class)
    fun createIncludeScanningHeaderData(
        env: SkyFunction.Environment, usePic: Boolean, createModularHeaders: Boolean
    ): com.google.devtools.build.lib.rules.cpp.IncludeScanner.IncludeScanningHeaderData.Builder? {
        val headerInfo = this.headerInfo
        val transitiveHeaderInfos = headerInfo.transitiveCollection
        val treeArtifacts: java.util.ArrayList<Artifact?> = java.util.ArrayList<Artifact?>()
        // We'd prefer for these types to use ImmutableSet/ImmutableMap. However, constructing these is
        // substantially more costly in a way that shows up in profiles.
        val pathToLegalArtifact: MutableMap<PathFragment?, Artifact?> =
            com.google.devtools.build.lib.collect.compacthashmap.CompactHashMap.createWithExpectedSize(
                transitiveHeaderInfos.size
            )
        val modularHeaders: MutableSet<Artifact?> =
            com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.createWithExpectedSize<Artifact?>(
                transitiveHeaderInfos.size
            )
        // Not using range-based for loops here and below as the additional overhead of the
        // ImmutableList iterators has shown up in profiles.
        for (transitiveHeaderInfo in transitiveHeaderInfos) {
            val isModule = createModularHeaders && transitiveHeaderInfo.getModule(usePic) != null
            handleHeadersForIncludeScanning(
                transitiveHeaderInfo.modularPublicHeaders,
                pathToLegalArtifact,
                treeArtifacts,
                isModule,
                modularHeaders
            )
            handleHeadersForIncludeScanning(
                transitiveHeaderInfo.modularPrivateHeaders,
                pathToLegalArtifact,
                treeArtifacts,
                isModule,
                modularHeaders
            )
            handleHeadersForIncludeScanning(
                transitiveHeaderInfo.separateModuleHeaders,
                pathToLegalArtifact,
                treeArtifacts,
                isModule,
                modularHeaders
            )
            Companion.handleHeadersForIncludeScanning(
                transitiveHeaderInfo.textualHeaders,
                pathToLegalArtifact,
                treeArtifacts,  /* isModule= */
                false,
                null
            )
        }
        if (!IncludeScanningHeaderDataHelper.handleTreeArtifacts(
                env, pathToLegalArtifact, treeArtifacts
            )
        ) {
            return null
        }
        removeArtifactsFromSet(modularHeaders, headerInfo.modularPublicHeaders)
        removeArtifactsFromSet(modularHeaders, headerInfo.modularPrivateHeaders)
        removeArtifactsFromSet(modularHeaders, headerInfo.textualHeaders)
        removeArtifactsFromSet(modularHeaders, headerInfo.separateModuleHeaders)
        return com.google.devtools.build.lib.rules.cpp.IncludeScanner.IncludeScanningHeaderData.Builder(
            pathToLegalArtifact,
            modularHeaders
        )
    }

    /**
     * Returns a list of all headers from `includes` that are properly declared as well as all
     * the modules that they are in.
     */
    fun computeUsedModules(
        usePic: Boolean, includes: MutableSet<Artifact?>, separate: Boolean
    ): MutableSet<DerivedArtifact?> {
        val headerInfo = this.headerInfo
        val modules: com.google.devtools.build.lib.collect.compacthashset.CompactHashSet<DerivedArtifact?> =
            com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.create<DerivedArtifact?>()
        for (transitiveHeaderInfo in headerInfo.transitiveCollection) {
            val module: DerivedArtifact? = transitiveHeaderInfo.getModule(usePic)
            if (module == null) {
                // If we don't have a main module, there is also not going to be a separate module. This is
                // verified when constructing HeaderInfo instances.
                continue
            }
            // Not using range-based for loops here as often there is exactly one element in this list
            // and the amount of garbage created by SingletonImmutableList.iterator() is significant.
            for (i in transitiveHeaderInfo.modularPublicHeaders.indices) {
                val header: Artifact = transitiveHeaderInfo.modularPublicHeaders.get(i)
                if (includes.contains(header)) {
                    modules.add(module)
                    break
                }
            }
            for (i in transitiveHeaderInfo.modularPrivateHeaders.indices) {
                val header: Artifact = transitiveHeaderInfo.modularPrivateHeaders.get(i)
                if (includes.contains(header)) {
                    modules.add(module)
                    break
                }
            }
            for (i in transitiveHeaderInfo.separateModuleHeaders.indices) {
                val header: Artifact = transitiveHeaderInfo.separateModuleHeaders.get(i)
                if (includes.contains(header)) {
                    modules.add(transitiveHeaderInfo.getSeparateModule(usePic))
                    break
                }
            }
        }
        // Do not add the module of the current rule for both:
        // 1. the module compile itself
        // 2. compiles of other translation units of the same rule.
        modules.remove(if (separate) headerInfo.getSeparateModule(usePic) else headerInfo.getModule(usePic))
        return modules
    }

    fun getTransitiveModules(usePic: Boolean): NestedSet<Artifact?> {
        try {
            return starlarkInfo
                .getValue(if (usePic) "_transitive_pic_modules" else "_transitive_modules", Depset::class.java)
                .getSet(Artifact::class.java)
        } catch (e: net.starlark.java.eval.EvalException) {
            throw java.lang.IllegalStateException(e)
        } catch (e: Depset.TypeException) {
            throw java.lang.IllegalStateException(e)
        }
    }

    fun getModuleFiles(usePic: Boolean): NestedSet<Artifact?> {
        try {
            return starlarkInfo
                .getValue(if (usePic) "_module_files" else "_pic_module_files", Depset::class.java)
                .getSet(Artifact::class.java)
        } catch (e: net.starlark.java.eval.EvalException) {
            throw java.lang.IllegalStateException(e)
        } catch (e: Depset.TypeException) {
            throw java.lang.IllegalStateException(e)
        }
    }

    fun getModulesInfoFiles(usePic: Boolean): NestedSet<Artifact?> {
        try {
            return starlarkInfo
                .getValue(if (usePic) "_module_info_files" else "_pic_module_info_files", Depset::class.java)
                .getSet(Artifact::class.java)
        } catch (e: net.starlark.java.eval.EvalException) {
            throw java.lang.IllegalStateException(e)
        } catch (e: Depset.TypeException) {
            throw java.lang.IllegalStateException(e)
        }
    }

    /** Adds additional transitive inputs needed for compilation to builder.  */
    fun addAdditionalInputs(builder: NestedSetBuilder<Artifact?>) {
        builder.addTransitive(this.directModuleMaps)
        builder.addTransitive(this.nonCodeInputs)
        if (this.cppModuleMap != null) {
            builder.add(this.cppModuleMap.getArtifact())
        }
    }

    val directModuleMaps: NestedSet<Artifact?>
        /** Returns modules maps from direct dependencies.  */
        get() {
            try {
                return starlarkInfo.getValue("_direct_module_maps", Depset::class.java).getSet(Artifact::class.java)
            } catch (e: net.starlark.java.eval.EvalException) {
                throw java.lang.IllegalStateException(e)
            } catch (e: Depset.TypeException) {
                throw java.lang.IllegalStateException(e)
            }
        }

    fun getHeaderModule(usePic: Boolean): DerivedArtifact? {
        return this.headerInfo.getModule(usePic)
    }

    fun getSeparateHeaderModule(usePic: Boolean): DerivedArtifact? {
        return this.headerInfo.getSeparateModule(usePic)
    }

    /**
     * Returns all declared headers of the current module if the current target is compiled as a
     * module.
     */
    fun getHeaderModuleSrcs(separateModule: Boolean): com.google.common.collect.ImmutableList<Artifact>? {
        val headerInfo = this.headerInfo
        if (separateModule) {
            return headerInfo.separateModuleHeaders
        }
        return com.google.common.collect.ImmutableSet.Builder<Artifact?>()
            .addAll(headerInfo.modularPublicHeaders)
            .addAll(headerInfo.modularPrivateHeaders)
            .addAll(headerInfo.textualHeaders)
            .addAll(headerInfo.separateModuleHeaders)
            .build()
            .asList()
    }

    val defines: com.google.common.collect.ImmutableList<String?>
        /**
         * Returns the set of defines needed to compile this target. This includes definitions from the
         * transitive deps closure for the target. The order of the returned collection is deterministic.
         */
        get() {
            try {
                return starlarkInfo.getValue("defines", Depset::class.java).getSet(String::class.java).toList()
            } catch (e: net.starlark.java.eval.EvalException) {
                throw java.lang.IllegalStateException(e)
            } catch (e: Depset.TypeException) {
                throw java.lang.IllegalStateException(e)
            }
        }

    val nonTransitiveDefines: com.google.common.collect.ImmutableList<String?>
        /**
         * Returns the set of defines needed to compile this target. This doesn't include definitions from
         * the transitive deps closure for the target.
         */
        get() {
            try {
                return starlarkInfo.getValue("local_defines", Depset::class.java).getSet(String::class.java)
                    .toList()
            } catch (e: net.starlark.java.eval.EvalException) {
                throw java.lang.IllegalStateException(e)
            } catch (e: Depset.TypeException) {
                throw java.lang.IllegalStateException(e)
            }
        }

    val cppModuleMap: CppModuleMap?
        /** Returns the C++ module map of the owner.  */
        get() {
            try {
                val moduleMap: StarlarkInfo? = starlarkInfo.getNoneableValue("_module_map", StarlarkInfo::class.java)
                return if (moduleMap == null) null else CppModuleMap(moduleMap)
            } catch (e: net.starlark.java.eval.EvalException) {
                throw java.lang.IllegalStateException(e)
            }
        }

    val exportingModuleMaps: com.google.common.collect.ImmutableList<CppModuleMap?>?
        /** Returns the list of dependencies' C++ module maps re-exported by this compilation context.  */
        get() {
            try {
                val moduleMaps: java.util.stream.Stream<StarlarkInfo?> =
                    when (starlarkInfo.getValue("_exporting_module_maps")) {
                        -> depset.toList(StarlarkInfo::class.java).stream()
                        -> starlarkList.stream()
                            .map<StarlarkInfo?> { obj: Any? -> StarlarkInfo::class.java.cast(obj) }

                        -> throw java.lang.IllegalStateException(
                            "Unexpected type for _exporting_module_maps, want Depset or StarlarkList, got "
                                    + values.getClass()
                        )
                    }
                return moduleMaps.map<CppModuleMap?>(java.util.function.Function { moduleMap: StarlarkInfo? ->
                    CppModuleMap(
                        moduleMap
                    )
                }).collect(com.google.common.collect.ImmutableList.toImmutableList<CppModuleMap?>())
            } catch (e: Depset.TypeException) {
                throw java.lang.IllegalStateException(e)
            }
        }

    val virtualToOriginalHeaders: NestedSet<net.starlark.java.eval.Tuple?>
        get() {
            try {
                return starlarkInfo
                    .getValue("_virtual_to_original_headers", Depset::class.java)
                    .getSet(net.starlark.java.eval.Tuple::class.java)
            } catch (e: net.starlark.java.eval.EvalException) {
                throw java.lang.IllegalStateException(e)
            } catch (e: Depset.TypeException) {
                throw java.lang.IllegalStateException(e)
            }
        }

    /**
     * Gathers data about the PIC and no-PIC .pcm files belonging to this context and the associated
     * information about the headers, e.g. modular vs. textual headers and pre-grepped header files.
     * 
     * 
     * This also implements a data structure very similar to NestedSet, but choosing slightly
     * different trade-offs to account for the specific data stored in here, specifically, we know
     * that there is going to be a single entry in every node of the DAG. Contrary to NestedSet, we
     * reuse memoization data from dependencies to conserve both runtime and memory. Experiments have
     * shown that >90% of a node's flattened transitive deps come from the largest dependency.
     * 
     * 
     * The order of elements is stable, although not necessarily the same as a STABLE NestedSet.
     * The transitive collection can be iterated without materialization in memory.
     */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    @com.google.common.annotations.VisibleForTesting
    class HeaderInfo internal constructor(
        identityToken: net.starlark.java.eval.SymbolGenerator.Symbol<*>,
        headerModule: DerivedArtifact?,
        picHeaderModule: DerivedArtifact?,
        modularPublicHeaders: com.google.common.collect.ImmutableList<Artifact>,
        modularPrivateHeaders: com.google.common.collect.ImmutableList<Artifact>,
        textualHeaders: com.google.common.collect.ImmutableList<Artifact>,
        separateModuleHeaders: com.google.common.collect.ImmutableList<Artifact>,
        separateModule: DerivedArtifact?,
        separatePicModule: DerivedArtifact?,
        deps: com.google.common.collect.ImmutableList<HeaderInfo>
    ) : net.starlark.java.eval.StarlarkValue {
        // This class has non-private visibility testing and HeaderInfoCodec.
        val identityToken: net.starlark.java.eval.SymbolGenerator.Symbol<*>

        /**
         * The modules built for this context. If null, then no module is being compiled for this
         * context.
         */
        val headerModule: DerivedArtifact?

        val picHeaderModule: DerivedArtifact?

        /** All public header files that are compiled into this module.  */
        val modularPublicHeaders: com.google.common.collect.ImmutableList<Artifact>

        /** All private header files that are compiled into this module.  */
        val modularPrivateHeaders: com.google.common.collect.ImmutableList<Artifact>

        /** All textual header files that are contained in this module.  */
        val textualHeaders: com.google.common.collect.ImmutableList<Artifact>

        /** Headers that can be compiled into a separate, smaller module for performance reasons.  */
        val separateModuleHeaders: com.google.common.collect.ImmutableList<Artifact>

        val separateModule: DerivedArtifact?
        val separatePicModule: DerivedArtifact?

        /** HeaderInfos of direct dependencies of C++ target represented by this context.  */
        val deps: com.google.common.collect.ImmutableList<HeaderInfo>

        /** Collection representing the memoized form of transitive information, set by flatten().  */
        private var memo: TransitiveHeaderCollection? = null

        init {
            this.identityToken = identityToken
            this.headerModule = headerModule
            this.picHeaderModule = picHeaderModule
            this.modularPublicHeaders = modularPublicHeaders
            this.modularPrivateHeaders = modularPrivateHeaders
            this.textualHeaders = textualHeaders
            this.separateModuleHeaders = separateModuleHeaders
            this.separateModule = separateModule
            this.separatePicModule = separatePicModule
            this.deps = deps
        }

        fun getModule(pic: Boolean): DerivedArtifact? {
            return if (pic) picHeaderModule else headerModule
        }

        fun getSeparateModule(pic: Boolean): DerivedArtifact? {
            return if (pic) separatePicModule else separateModule
        }

        @net.starlark.java.annot.StarlarkMethod(
            name = "separate_module",
            documented = false,
            allowReturnNones = true,
            structField = true
        )
        fun getSeparateModule(): DerivedArtifact? {
            return separateModule
        }

        @net.starlark.java.annot.StarlarkMethod(
            name = "header_module",
            documented = false,
            allowReturnNones = true,
            structField = true
        )
        fun getHeaderModule(): DerivedArtifact? {
            return headerModule
        }

        @net.starlark.java.annot.StarlarkMethod(
            name = "pic_header_module",
            documented = false,
            allowReturnNones = true,
            structField = true
        )
        fun getPicHeaderModule(): DerivedArtifact? {
            return picHeaderModule
        }

        @net.starlark.java.annot.StarlarkMethod(name = "modular_public_headers", documented = false, structField = true)
        fun getModularPublicHeaders(): net.starlark.java.eval.StarlarkList<Artifact?>? {
            return net.starlark.java.eval.StarlarkList.immutableCopyOf<Artifact?>(modularPublicHeaders)
        }

        @net.starlark.java.annot.StarlarkMethod(
            name = "modular_private_headers",
            documented = false,
            structField = true
        )
        fun getModularPrivateHeaders(): net.starlark.java.eval.StarlarkList<Artifact?>? {
            return net.starlark.java.eval.StarlarkList.immutableCopyOf<Artifact?>(modularPrivateHeaders)
        }

        @net.starlark.java.annot.StarlarkMethod(name = "textual_headers", documented = false, structField = true)
        fun getTextualHeaders(): net.starlark.java.eval.StarlarkList<Artifact?>? {
            return net.starlark.java.eval.StarlarkList.immutableCopyOf<Artifact?>(textualHeaders)
        }

        @net.starlark.java.annot.StarlarkMethod(
            name = "separate_module_headers",
            documented = false,
            structField = true
        )
        fun getSeparateModuleHeaders(): net.starlark.java.eval.StarlarkList<Artifact?>? {
            return net.starlark.java.eval.StarlarkList.immutableCopyOf<Artifact?>(separateModuleHeaders)
        }

        @net.starlark.java.annot.StarlarkMethod(
            name = "separate_pic_module",
            documented = false,
            allowReturnNones = true,
            structField = true
        )
        fun getSeparatePicModule(): DerivedArtifact? {
            return separatePicModule
        }

        val transitiveCollection: MutableCollection<HeaderInfo>
            get() {
                if (deps.isEmpty()) {
                    return com.google.common.collect.ImmutableList.of<HeaderInfo?>(this)
                }
                if (memo == null) {
                    flatten()
                }
                return memo
            }

        @com.google.common.annotations.VisibleForTesting
        fun modularPublicHeaders(): com.google.common.collect.ImmutableList<Artifact> {
            return modularPublicHeaders
        }

        @kotlin.jvm.Synchronized
        private fun flatten() {
            if (memo != null) {
                return  // Some other thread has flattened this list while we waited for the lock.
            }
            var largestDepList: MutableCollection<HeaderInfo> =
                com.google.common.collect.ImmutableList.of<HeaderInfo?>()
            var largestDep: HeaderInfo? = null
            for (dep in deps) {
                val depList: MutableCollection<HeaderInfo> = dep.getTransitiveCollection()
                if (depList.size() > largestDepList.size()) {
                    largestDepList = depList
                    largestDep = dep
                }
            }
            val result: com.google.devtools.build.lib.collect.compacthashset.CompactHashSet<HeaderInfo?> =
                com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.create<HeaderInfo?>(largestDepList)
            result.add(this)
            val additionalDeps: java.util.ArrayList<HeaderInfo?> = java.util.ArrayList<HeaderInfo?>()
            for (dep in deps) {
                dep.addOthers(result, additionalDeps)
            }
            memo = TransitiveHeaderCollection(result.size(), largestDep!!, additionalDeps)
        }

        private fun addOthers(result: MutableSet<HeaderInfo?>, additionalDeps: MutableList<HeaderInfo?>) {
            if (result.add(this)) {
                additionalDeps.add(this)
                for (dep in deps) {
                    dep.addOthers(result, additionalDeps)
                }
            }
        }

        override fun equals(obj: Any?): Boolean {
            if (obj !is HeaderInfo) {
                return false
            }
            return identityToken == obj.identityToken
        }

        override fun hashCode(): Int {
            return identityToken.hashCode()
        }

        val isImmutable: Boolean
            get() = true

        /** Represents the memoized transitive information for a HeaderInfo instance.  */
        private inner class TransitiveHeaderCollection(
            private val size: Int,
            private val largestDep: HeaderInfo,
            additionalDeps: MutableList<HeaderInfo?>
        ) : AbstractCollection<HeaderInfo?>() {
            private val additionalDeps: com.google.common.collect.ImmutableList<HeaderInfo?>

            init {
                this.additionalDeps = com.google.common.collect.ImmutableList.copyOf<HeaderInfo?>(additionalDeps)
            }

            override fun size(): Int {
                return size
            }

            override fun iterator(): MutableIterator<HeaderInfo?> {
                return TransitiveHeaderIterator(this@HeaderInfo)
            }
        }

        /** Iterates over memoized transitive information, without materializing it in memory.  */
        private class TransitiveHeaderIterator(private var headerInfo: HeaderInfo) : MutableIterator<HeaderInfo?> {
            private var pos = -1

            override fun hasNext(): Boolean {
                return !headerInfo.deps.isEmpty()
            }

            override fun next(): HeaderInfo? {
                pos++
                if (pos > headerInfo.memo.additionalDeps.size()) {
                    pos = 0
                    headerInfo = headerInfo.memo.largestDep
                }
                if (pos == 0) {
                    return headerInfo
                }
                return headerInfo.memo.additionalDeps.get(pos - 1)
            }
        }

        companion object {
            val EMPTY: HeaderInfo = create(
                net.starlark.java.eval.SymbolGenerator.CONSTANT_SYMBOL,  /* headerModule= */
                null,  /* picHeaderModule= */
                null,  /* publicHeaders= */
                com.google.common.collect.ImmutableList.of<Artifact?>(),  /* privateHeaders= */
                com.google.common.collect.ImmutableList.of<Artifact?>(),  /* textualHeaders= */
                com.google.common.collect.ImmutableList.of<Artifact?>(),  /* separateModuleHeaders= */
                com.google.common.collect.ImmutableList.of<Artifact?>(),  /* separateModule= */
                null,  /* separatePicModule= */
                null,  /* deps= */
                com.google.common.collect.ImmutableList.of<HeaderInfo?>(),  /* mergedDeps= */
                com.google.common.collect.ImmutableList.of<HeaderInfo?>()
            )

            /**
             * Creates a new [HeaderInfo] instance.
             * 
             * @param identityToken The identity token for the HeaderInfo.
             * @param headerModule The .pcm file generated for this library.
             * @param picHeaderModule The .pic.pcm file generated for this library.
             * @param publicHeaders All public header files that are compiled into this module.
             * @param privateHeaders All private header files that are compiled into this module.
             * @param textualHeaders All textual header files that are contained in this module.
             * @param separateModuleHeaders Headers that can be compiled into a separate, smaller module for
             * performance reasons.
             * @param separateModule The .pcm file generated for the separate module.
             * @param separatePicModule The .pic.pcm file generated for the separate module.
             * @param deps HeaderInfos of direct dependencies of C++ target represented by this context.
             * @param mergedDeps HeaderInfos to merge into this one.
             */
            fun create(
                identityToken: net.starlark.java.eval.SymbolGenerator.Symbol<*>,
                headerModule: DerivedArtifact?,
                picHeaderModule: DerivedArtifact?,
                publicHeaders: MutableCollection<Artifact>,
                privateHeaders: MutableCollection<Artifact>,
                textualHeaders: MutableCollection<Artifact>,
                separateModuleHeaders: com.google.common.collect.ImmutableList<Artifact>,
                separateModule: DerivedArtifact?,
                separatePicModule: DerivedArtifact?,
                deps: com.google.common.collect.ImmutableList<HeaderInfo>,
                mergedDeps: com.google.common.collect.ImmutableList<HeaderInfo>
            ): HeaderInfo {
                com.google.common.base.Preconditions.checkState(
                    (separateModule == null || headerModule != null)
                            && (separatePicModule == null || picHeaderModule != null),
                    "Separate module ('%s', '%s') cannot be used without main module",
                    separateModule,
                    separatePicModule
                )
                val modularPublicHeaders: com.google.common.collect.ImmutableSet.Builder<Artifact?> =
                    com.google.common.collect.ImmutableSet.builder<Artifact?>()
                val modularPrivateHeaders: com.google.common.collect.ImmutableSet.Builder<Artifact?> =
                    com.google.common.collect.ImmutableSet.builder<Artifact?>()
                val allTextualHeaders: com.google.common.collect.ImmutableSet.Builder<Artifact?> =
                    com.google.common.collect.ImmutableSet.builder<Artifact?>()
                allTextualHeaders.addAll(textualHeaders)
                // TODO(djasper): CPP_TEXTUAL_INCLUDEs are currently special cased here and in
                // CppModuleMapAction. These should be moved to a place earlier in the Action construction.
                for (header in publicHeaders) {
                    if (header.isFileType(CppFileTypes.CPP_TEXTUAL_INCLUDE)) {
                        allTextualHeaders.add(header)
                    } else {
                        modularPublicHeaders.add(header)
                    }
                }
                for (header in privateHeaders) {
                    if (header.isFileType(CppFileTypes.CPP_TEXTUAL_INCLUDE)) {
                        allTextualHeaders.add(header)
                    } else {
                        modularPrivateHeaders.add(header)
                    }
                }
                for (otherHeaderInfo in mergedDeps) {
                    modularPublicHeaders.addAll(otherHeaderInfo.modularPublicHeaders)
                    modularPrivateHeaders.addAll(otherHeaderInfo.modularPrivateHeaders)
                    allTextualHeaders.addAll(otherHeaderInfo.textualHeaders)
                }
                return HeaderInfo(
                    identityToken,
                    headerModule,
                    picHeaderModule,
                    modularPublicHeaders.build().asList(),
                    modularPrivateHeaders.build().asList(),
                    allTextualHeaders.build().asList(),
                    separateModuleHeaders,
                    separateModule,
                    separatePicModule,
                    deps
                )
            }
        }
    }

    companion object {
        fun of(starlarkInfo: StarlarkInfo): CcCompilationContext {
            return CcCompilationContext(starlarkInfo)
        }

        /**
         * Passes a list of headers to the include scanning helper for handling, and optionally adds them
         * to a set that tracks modular headers.
         * 
         * 
         * This is factored out into its own method not only to reduce code duplication below, but also
         * to improve JIT optimization for this performance-sensitive region.
         */
        private fun handleHeadersForIncludeScanning(
            headers: com.google.common.collect.ImmutableList<Artifact>,
            pathToLegalArtifact: MutableMap<PathFragment?, Artifact?>,
            treeArtifacts: java.util.ArrayList<Artifact?>,
            isModule: Boolean,
            modularHeaders: MutableSet<Artifact?>
        ) {
            // Not using range-based for loops here and below as the additional overhead of the
            // ImmutableList iterators has shown up in profiles.
            for (i in headers.indices) {
                val a: Artifact = headers.get(i)
                IncludeScanningHeaderDataHelper.handleArtifact(a, pathToLegalArtifact, treeArtifacts)
                if (isModule) {
                    modularHeaders.add(a)
                }
            }
        }

        private fun removeArtifactsFromSet(
            set: MutableSet<Artifact?>,
            artifacts: com.google.common.collect.ImmutableList<Artifact>
        ) {
            // Not using iterators here as the resulting overhead is significant in profiles. Do not use
            // Iterables.removeAll() or Set.removeAll() here as with the given container sizes, that
            // needlessly deteriorates to a quadratic algorithm.
            for (i in artifacts.indices) {
                set.remove(artifacts.get(i))
            }
        }
    }
}
