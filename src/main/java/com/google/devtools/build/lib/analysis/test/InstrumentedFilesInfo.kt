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
package com.google.devtools.build.lib.analysis.test

import com.google.devtools.build.lib.actions.Artifact

/** An implementation class for the InstrumentedFilesProvider interface.  */
class InstrumentedFilesInfo internal constructor(
    instrumentedFiles: NestedSet<Artifact?>,
    instrumentationMetadataFiles: NestedSet<Artifact?>,
    baselineCoverageArtifacts: NestedSet<Artifact?>?,
    coverageSupportFiles: NestedSet<Artifact?>?,
    coverageEnvironment: com.google.common.collect.ImmutableMap<String?, String?>?,
    reportedToActualSources: NestedSet<net.starlark.java.eval.Tuple?>?
) : NativeInfo(), InstrumentedFilesInfoApi {
    private val instrumentedFiles: NestedSet<Artifact?>
    private val instrumentationMetadataFiles: NestedSet<Artifact?>
    private val baselineCoverageArtifacts: NestedSet<Artifact?>?
    private val coverageSupportFiles: NestedSet<Artifact?>?
    private val coverageEnvironment: com.google.common.collect.ImmutableMap<String?, String?>?
    private val reportedToActualSources: NestedSet<net.starlark.java.eval.Tuple?>?

    init {
        this.instrumentedFiles = instrumentedFiles
        this.instrumentationMetadataFiles = instrumentationMetadataFiles
        this.baselineCoverageArtifacts = baselineCoverageArtifacts
        this.coverageSupportFiles = coverageSupportFiles
        this.coverageEnvironment = coverageEnvironment
        this.reportedToActualSources = reportedToActualSources
    }

    /** The transitive closure of instrumented source files.  */
    fun getInstrumentedFiles(): NestedSet<Artifact?> {
        return instrumentedFiles
    }

    val instrumentedFilesForStarlark: Depset?
        get() = Depset.of<Artifact?>(Artifact::class.java, instrumentedFiles)

    /** Returns a collection of instrumentation metadata files.  */
    fun getInstrumentationMetadataFiles(): NestedSet<Artifact?> {
        return instrumentationMetadataFiles
    }

    val instrumentationMetadataFilesForStarlark: Depset?
        get() = Depset.of<Artifact?>(Artifact::class.java, instrumentationMetadataFiles)

    /**
     * Returns the output artifacts of the [BaselineCoverageAction]s for the transitive closure
     * of source files.
     */
    fun getBaselineCoverageArtifacts(): NestedSet<Artifact?>? {
        return baselineCoverageArtifacts
    }

    /**
     * Extra files that are needed on the inputs of test actions for coverage collection to happen,
     * for example, `gcov`.
     * 
     * 
     * They aren't mentioned in the instrumented files manifest.
     */
    fun getCoverageSupportFiles(): NestedSet<Artifact?>? {
        return coverageSupportFiles
    }

    /** Environment variables that need to be set for tests collecting code coverage.  */
    fun getCoverageEnvironment(): com.google.common.collect.ImmutableMap<String?, String?>? {
        return coverageEnvironment
    }

    /**
     * A set of pairs of reported source file path and the actual source file path, relative to the
     * workspace directory, if the two values are different. If the reported source file is the same
     * as the actual source path it will not be included in this set.
     * 
     * 
     * This is useful for virtual include paths in C++, which get reported at the include location
     * and not the real source path. For example, the reported include source file can be
     * "bazel-out/k8-fastbuild/bin/include/common/_virtual_includes/strategy/strategy.h", but its
     * actual source path is "include/common/strategy.h".
     */
    fun getReportedToActualSources(): NestedSet<net.starlark.java.eval.Tuple?>? {
        return reportedToActualSources
    }

    /** Provider implementation for [InstrumentedFilesInfo].  */
    class InstrumentedFilesProvider :
        BuiltinProvider<InstrumentedFilesInfo?>("InstrumentedFilesInfo", InstrumentedFilesInfo::class.java)

    companion object {
        /** Singleton provider instance for [InstrumentedFilesInfo].  */
        val provider: InstrumentedFilesProvider = InstrumentedFilesProvider()
            get() = Companion.field

        val EMPTY: InstrumentedFilesInfo = InstrumentedFilesInfo(
            NestedSetBuilder.emptySet<Artifact?>(com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER),
            NestedSetBuilder.emptySet<Artifact?>(com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER),
            NestedSetBuilder.emptySet<Artifact?>(com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER),
            NestedSetBuilder.emptySet<Artifact?>(com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER),
            com.google.common.collect.ImmutableMap.of<String?, String?>(),
            NestedSetBuilder.emptySet<net.starlark.java.eval.Tuple?>(com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER)
        )
    }
}
