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

/** A helper class for collecting instrumented files and metadata for a target.  */
object InstrumentedFilesCollector {
    /**
     * Forwards any instrumented files from the given target's dependencies (as defined in `dependencyAttributes`) for further export. No files from this target are considered
     * instrumented.
     * 
     * @return instrumented file provider of all dependencies in `dependencyAttributes`
     */
    fun forward(
        ruleContext: RuleContext?, vararg dependencyAttributes: String?
    ): InstrumentedFilesInfo {
        return collect(
            ruleContext,
            InstrumentationSpec(FileTypeSet.NO_FILE).withDependencyAttributes(*dependencyAttributes),  /* reportedToActualSources= */
            NestedSetBuilder.emptySet(Order.STABLE_ORDER)
        )
    }

    fun forwardAll(ruleContext: RuleContext): InstrumentedFilesInfo {
        if (!ruleContext.getConfiguration().isCodeCoverageEnabled()) {
            return InstrumentedFilesInfo.EMPTY
        }
        val instrumentedFilesInfoBuilder =
            InstrumentedFilesInfoBuilder(ruleContext)
        for (dep in getAllNonToolPrerequisites(ruleContext)) {
            instrumentedFilesInfoBuilder.addFromDependency(dep)
        }
        return instrumentedFilesInfoBuilder.build()
    }

    fun collect(ruleContext: RuleContext?, spec: InstrumentationSpec?): InstrumentedFilesInfo {
        return collect(
            ruleContext,
            spec,  /* reportedToActualSources= */
            NestedSetBuilder.emptySet(Order.STABLE_ORDER)
        )
    }

    fun collect(
        ruleContext: RuleContext?, spec: InstrumentationSpec?, reportedToActualSources: NestedSet<Tuple?>?
    ): InstrumentedFilesInfo {
        return collect(
            ruleContext,
            spec,
            NestedSetBuilder.emptySet(Order.STABLE_ORDER),
            com.google.common.collect.ImmutableMap.of<String?, String?>(),
            reportedToActualSources,  /* additionalMetadata= */
            null,  /* baselineCoverageFiles= */
            null
        )
    }

    /**
     * Collects transitive instrumentation data from dependencies, collects local source files from
     * dependencies, collects local metadata files by traversing the action graph of the current
     * configured target, collect rule-specific instrumentation support files and creates baseline
     * coverage actions for the local source files (if any).
     */
    fun collect(
        ruleContext: RuleContext?,
        spec: InstrumentationSpec?,
        coverageSupportFiles: NestedSet<Artifact?>?,
        coverageEnvironment: com.google.common.collect.ImmutableMap<String?, String?>,
        reportedToActualSources: NestedSet<Tuple?>?,
        additionalMetadata: Iterable<Artifact?>?,
        baselineCoverageFiles: MutableList<Artifact?>?
    ): InstrumentedFilesInfo {
        com.google.common.base.Preconditions.checkNotNull<RuleContext?>(ruleContext)
        com.google.common.base.Preconditions.checkNotNull<InstrumentationSpec?>(spec)

        if (!ruleContext.getConfiguration().isCodeCoverageEnabled()) {
            return InstrumentedFilesInfo.EMPTY
        }

        val instrumentedFilesInfoBuilder =
            InstrumentedFilesInfoBuilder(
                ruleContext, coverageSupportFiles, reportedToActualSources
            )

        // Transitive instrumentation data.
        for (dep in getPrerequisitesForAttributes(ruleContext, spec.dependencyAttributes)) {
            instrumentedFilesInfoBuilder.addFromDependency(dep)
        }

        // add top-level coverage env last so that it overrides conflicting keys from deps
        instrumentedFilesInfoBuilder.coverageEnvironmentBuilder.putAll(coverageEnvironment)

        // Local sources.
        val localSources: com.google.common.collect.ImmutableSet.Builder<Artifact?> =
            com.google.common.collect.ImmutableSet.builder<Artifact?>()
        if (shouldIncludeLocalSources(
                ruleContext.getConfiguration(), ruleContext.getLabel(), ruleContext.isTestTarget()
            )
        ) {
            for (dep in getPrerequisitesForAttributes(ruleContext, spec.sourceAttributes)) {
                for (artifact in dep.getProvider(FileProvider::class.java).getFilesToBuild().toList()) {
                    if (shouldIncludeArtifact(ruleContext.getConfiguration(), artifact)
                        && spec.instrumentedFileTypes.matches(artifact.getFilename())
                    ) {
                        localSources.add(artifact)
                    }
                }
            }
        }
        instrumentedFilesInfoBuilder.setLocalSources(localSources.build())
        if (baselineCoverageFiles != null) {
            instrumentedFilesInfoBuilder.setBaselineCoverageFiles(baselineCoverageFiles)
        }

        if (additionalMetadata != null) {
            instrumentedFilesInfoBuilder.addMetadataFiles(additionalMetadata)
        }

        return instrumentedFilesInfoBuilder.build()
    }

    /**
     * Return whether the sources included by `target` (a [TransitiveInfoCollection]
     * representing a rule) should be instrumented according the --instrumentation_filter and
     * --instrument_test_targets settings in `config`.
     */
    fun shouldIncludeLocalSources(
        config: BuildConfigurationValue, target: TransitiveInfoCollection
    ): Boolean {
        return shouldIncludeLocalSources(
            config, target.getLabel(), target.getProvider(TestProvider::class.java) != null
        )
    }

    /**
     * Return whether the sources of the rule in `ruleContext` should be instrumented based on
     * the --instrumentation_filter and --instrument_test_targets config settings.
     */
    fun shouldIncludeLocalSources(
        config: BuildConfigurationValue, label: Label, isTest: Boolean
    ): Boolean {
        return ((config.shouldInstrumentTestTargets() || !isTest)
                && config.getInstrumentationFilter().isIncluded(label.toString()))
    }

    /**
     * Return whether the artifact should be collected based on the origin of the artifact and the
     * --experimental_collect_code_coverage_for_generated_files config setting.
     */
    fun shouldIncludeArtifact(config: BuildConfigurationValue, artifact: Artifact): Boolean {
        return artifact.isSourceArtifact() || config.shouldCollectCodeCoverageForGeneratedFiles()
    }

    private fun getPrerequisitesForAttributes(
        ruleContext: RuleContext, attributeNames: MutableCollection<String?>
    ): Iterable<TransitiveInfoCollection> {
        val prerequisites: MutableList<TransitiveInfoCollection> = java.util.ArrayList<TransitiveInfoCollection>()
        for (attributeName in attributeNames) {
            val attribute: Attribute? =
                ruleContext
                    .getRule()
                    .getRuleClassObject()
                    .getAttributeProvider()
                    .getAttributeByNameMaybe(attributeName)
            if (attribute != null) {
                prerequisites.addAll(attributeDependencyPrerequisites(attribute, ruleContext))
            }
        }
        return prerequisites
    }

    private fun getAllNonToolPrerequisites(
        ruleContext: RuleContext
    ): Iterable<TransitiveInfoCollection> {
        val prerequisites: MutableList<TransitiveInfoCollection> = java.util.ArrayList<TransitiveInfoCollection>()
        for (attribute in ruleContext.getRule().getAttributes()) {
            if (!attribute.isToolDependency()) {
                prerequisites.addAll(attributeDependencyPrerequisites(attribute, ruleContext))
            }
        }
        return prerequisites
    }

    private fun attributeDependencyPrerequisites(
        attribute: Attribute, ruleContext: RuleContext
    ): MutableList<out TransitiveInfoCollection?>? {
        if (attribute.getType().getLabelClass() === LabelClass.DEPENDENCY) {
            return ruleContext.getPrerequisites(attribute.getName())
        }
        return com.google.common.collect.ImmutableList.of<TransitiveInfoCollection?>()
    }

    /**
     * The set of file types and attributes to visit to collect instrumented files for a certain rule
     * type. The class is intentionally immutable, so that a single instance is sufficient for all
     * rules of the same type (and in some cases all rules of related types, such as all `foo_*`
     * rules).
     */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    class InstrumentationSpec private constructor(
        instrumentedFileTypes: FileTypeSet,
        instrumentedSourceAttributes: com.google.common.collect.ImmutableList<String?>,
        instrumentedDependencyAttributes: com.google.common.collect.ImmutableList<String?>
    ) {
        private val instrumentedFileTypes: FileTypeSet

        /** The list of attributes which should be checked for sources.  */
        private val sourceAttributes: com.google.common.collect.ImmutableList<String?>

        /** The list of attributes from which to collect transitive coverage information.  */
        private val dependencyAttributes: com.google.common.collect.ImmutableList<String?>

        init {
            this.instrumentedFileTypes = instrumentedFileTypes
            this.sourceAttributes = instrumentedSourceAttributes
            this.dependencyAttributes = instrumentedDependencyAttributes
        }

        constructor(instrumentedFileTypes: FileTypeSet) : this(
            instrumentedFileTypes,
            com.google.common.collect.ImmutableList.of<String?>(),
            com.google.common.collect.ImmutableList.of<String?>()
        )

        /**
         * Returns a new instrumentation spec with the given attribute names replacing the ones stored
         * in this object.
         */
        fun withSourceAttributes(attributes: MutableCollection<String?>): InstrumentationSpec {
            return InstrumentationSpec(
                instrumentedFileTypes,
                com.google.common.collect.ImmutableList.copyOf<String?>(attributes),
                dependencyAttributes
            )
        }

        /**
         * Returns a new instrumentation spec with the given attribute names replacing the ones stored
         * in this object.
         */
        fun withSourceAttributes(vararg attributes: String?): InstrumentationSpec {
            return withSourceAttributes(com.google.common.collect.ImmutableList.copyOf<String?>(attributes))
        }

        /**
         * Returns a new instrumentation spec with the given attribute names replacing the ones stored
         * in this object.
         */
        fun withDependencyAttributes(attributes: MutableCollection<String?>): InstrumentationSpec {
            return InstrumentationSpec(
                instrumentedFileTypes,
                sourceAttributes,
                com.google.common.collect.ImmutableList.copyOf<String?>(attributes)
            )
        }

        /**
         * Returns a new instrumentation spec with the given attribute names replacing the ones stored
         * in this object.
         */
        fun withDependencyAttributes(vararg attributes: String?): InstrumentationSpec {
            return withDependencyAttributes(com.google.common.collect.ImmutableList.copyOf<String?>(attributes))
        }
    }

    private class InstrumentedFilesInfoBuilder @kotlin.jvm.JvmOverloads constructor(
        ruleContext: RuleContext,
        coverageSupportFiles: NestedSet<Artifact?>? = NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        reportedToActualSources: NestedSet<Tuple?>? = NestedSetBuilder.emptySet(Order.STABLE_ORDER)
    ) {
        val ruleContext: RuleContext
        val instrumentedFilesBuilder: NestedSetBuilder<Artifact?>
        val metadataFilesBuilder: NestedSetBuilder<Artifact?>
        val baselineCoverageArtifactsBuilder: NestedSetBuilder<Artifact?>
        val coverageSupportFilesBuilder: NestedSetBuilder<Artifact?>
        val coverageEnvironmentBuilder: com.google.common.collect.ImmutableMap.Builder<String?, String?>
        val reportedToActualSources: NestedSet<Tuple?>?
        private var localSources: NestedSet<Artifact?>? = null
        private var localBaselineCoverageArtifacts: MutableList<Artifact?>? = null

        init {
            this.ruleContext = ruleContext
            instrumentedFilesBuilder = NestedSetBuilder.stableOrder()
            metadataFilesBuilder = NestedSetBuilder.stableOrder()
            baselineCoverageArtifactsBuilder = NestedSetBuilder.stableOrder()
            coverageSupportFilesBuilder =
                NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ().addTransitive(coverageSupportFiles)
            coverageEnvironmentBuilder = com.google.common.collect.ImmutableMap.builder<String?, String?>()
            this.reportedToActualSources = reportedToActualSources
        }

        fun addFromDependency(dep: TransitiveInfoCollection) {
            val provider: InstrumentedFilesInfo? = dep.get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR)
            if (provider != null) {
                instrumentedFilesBuilder.addTransitive(provider.getInstrumentedFiles())
                metadataFilesBuilder.addTransitive(provider.getInstrumentationMetadataFiles())
                baselineCoverageArtifactsBuilder.addTransitive(provider.getBaselineCoverageArtifacts())
                coverageSupportFilesBuilder.addTransitive(provider.getCoverageSupportFiles())
                coverageEnvironmentBuilder.putAll(provider.getCoverageEnvironment())
            }
        }

        fun setLocalSources(localSources: com.google.common.collect.ImmutableSet<Artifact?>?) {
            // Wrap in a nested set shared between the transitive set of instrumented files and the inputs
            // to the local baseline coverage action. Avoid NestedSetBuilder#wrap as it caches the list
            // to nested set mapping and the list is expected to be unique.
            val localSourcesNestedSet: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ().addAll(localSources).build()
            instrumentedFilesBuilder.addTransitive(localSourcesNestedSet)
            this.localSources = localSourcesNestedSet
        }

        fun setBaselineCoverageFiles(baselineCoverageFiles: MutableList<Artifact?>?) {
            localBaselineCoverageArtifacts = baselineCoverageFiles
        }

        fun addMetadataFiles(files: Iterable<Artifact?>?) {
            metadataFilesBuilder.addAll(files)
        }

        fun build(): InstrumentedFilesInfo {
            if (localSources != null && !localSources.isEmpty()) {
                if (localBaselineCoverageArtifacts != null) {
                    baselineCoverageArtifactsBuilder.addAll(localBaselineCoverageArtifacts)
                } else {
                    val baselineCoverageAction: BaselineCoverageAction =
                        BaselineCoverageAction.Companion.create(ruleContext, localSources)
                    ruleContext.registerAction(baselineCoverageAction)
                    baselineCoverageArtifactsBuilder.add(baselineCoverageAction.getPrimaryOutput())
                }
            }

            return InstrumentedFilesInfo(
                instrumentedFilesBuilder.build(),
                metadataFilesBuilder.build(),
                baselineCoverageArtifactsBuilder.build(),
                coverageSupportFilesBuilder.build(),
                coverageEnvironmentBuilder.buildKeepingLast(),
                reportedToActualSources
            )
        }
    }
}
