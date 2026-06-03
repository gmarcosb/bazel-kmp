// Copyright 2019 The Bazel Authors. All rights reserved.
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

/** Helper functions for Starlark to access coverage-related infrastructure.  */
class CoverageCommon : CoverageCommonApi<ConstraintValueInfo?, StarlarkRuleContext?> {
    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun instrumentedFilesInfo(
        starlarkRuleContext: StarlarkRuleContext,
        sourceAttributes: net.starlark.java.eval.Sequence<*>?,  // <String>
        dependencyAttributes: net.starlark.java.eval.Sequence<*>?,  // <String>
        supportFiles: Any?,  // Depset<Artifact>|Sequence<Artifact|Depset<Artifact>|FilesToRunProvider>
        environment: Dict<*, *>?,  // <String, String>
        extensions: Any?,
        metadataFiles: net.starlark.java.eval.Sequence<*>?,  // Sequence<Artifact>
        reportedToActualSourcesObject: Any?,
        baselineCoverageFilesObject: Any?,  // Sequence<Artifact>|NoneType
        thread: StarlarkThread?
    ): InstrumentedFilesInfoApi? {
        val extensionsList: MutableList<String?>? =
            if (extensions === Starlark.NONE) null else net.starlark.java.eval.Sequence.cast<String?>(
                extensions,
                String::class.java,
                "extensions"
            )
        val reportedToActualSources: NestedSet<Tuple?> =
            if (reportedToActualSourcesObject === Starlark.NONE)
                NestedSetBuilder.create(Order.STABLE_ORDER)
            else
                Depset.cast(reportedToActualSourcesObject, Tuple::class.java, "reported_to_actual_sources")
        val baselineCoverageFiles: MutableList<Artifact?>? =
            if (baselineCoverageFilesObject === Starlark.NONE)
                null
            else
                net.starlark.java.eval.Sequence.cast<Artifact?>(
                    baselineCoverageFilesObject,
                    Artifact::class.java,
                    "baseline_coverage_files"
                )
        val environmentDict: Dict<String?, String?> =
            Dict.cast<String?, String?>(environment, String::class.java, String::class.java, "coverage_environment")
        val supportFilesBuilder: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
        if (supportFiles is Depset) {
            supportFilesBuilder.addTransitive(
                Depset.cast(supportFiles, Artifact::class.java, "coverage_support_files")
            )
        } else if (supportFiles is net.starlark.java.eval.Sequence<*>) {
            for (i in supportFiles.indices) {
                val supportFilesElement: Any? = supportFiles.get(i)
                if (supportFilesElement is Depset) {
                    supportFilesBuilder.addTransitive(
                        Depset.cast(supportFilesElement, Artifact::class.java, "coverage_support_files")
                    )
                } else if (supportFilesElement is Artifact) {
                    supportFilesBuilder.add(supportFilesElement)
                } else {
                    throw Starlark.errorf(
                        "at index %d of coverage_support_files, got element of type %s, want one of depset,"
                                + " File or FilesToRunProvider",
                        i, Starlark.type(supportFilesElement)
                    )
                }
            }
        } else {
            // Should have been verified by Starlark before this function is called
            throw java.lang.IllegalStateException()
        }
        if (!supportFilesBuilder.isEmpty() || !reportedToActualSources.isEmpty() || !environmentDict.isEmpty()) {
            BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread)
        }
        return createInstrumentedFilesInfo(
            starlarkRuleContext.getRuleContext(),
            net.starlark.java.eval.Sequence.cast<String?>(sourceAttributes, String::class.java, "source_attributes"),
            net.starlark.java.eval.Sequence.cast<String?>(
                dependencyAttributes,
                String::class.java,
                "dependency_attributes"
            ),
            supportFilesBuilder.build(),
            com.google.common.collect.ImmutableMap.copyOf<String?, String?>(environmentDict),
            extensionsList,
            net.starlark.java.eval.Sequence.cast<Artifact?>(metadataFiles, Artifact::class.java, "metadata_files"),
            reportedToActualSources,
            baselineCoverageFiles
        )
    }

    public override fun repr(printer: net.starlark.java.eval.Printer, semantics: StarlarkSemantics?) {
        printer.append("<coverage_common>")
    }

    companion object {
        /**
         * @param extensions file extensions used to filter files from source_attributes. If null, all
         * files on the source attributes will be treated as instrumented. Otherwise, only files with
         * extensions listed in `extensions` will be used
         * @param baselineCoverageFiles if not null, the files to use as baseline coverage instead of
         * running the default action to generate it
         */
        private fun createInstrumentedFilesInfo(
            ruleContext: RuleContext?,
            sourceAttributes: MutableList<String?>?,
            dependencyAttributes: MutableList<String?>?,
            supportFiles: NestedSet<Artifact?>?,
            environment: com.google.common.collect.ImmutableMap<String?, String?>?,
            extensions: MutableList<String?>?,
            metadataFiles: MutableList<Artifact?>?,
            reportedToActualSources: NestedSet<Tuple?>?,
            baselineCoverageFiles: MutableList<Artifact?>?
        ): InstrumentedFilesInfo? {
            var fileTypeSet: FileTypeSet? = FileTypeSet.ANY_FILE
            if (extensions != null) {
                if (extensions.isEmpty()) {
                    fileTypeSet = FileTypeSet.NO_FILE
                } else {
                    val fileTypes: Array<FileType?> = arrayOfNulls<FileType>(extensions.size())
                    java.util.Arrays.setAll<FileType?>(
                        fileTypes,
                        java.util.function.IntFunction { i: Int -> FileType.of(extensions.get(i)) })
                    fileTypeSet = FileTypeSet.of(fileTypes)
                }
            }
            val instrumentationSpec: InstrumentationSpec =
                InstrumentationSpec(fileTypeSet)
                    .withSourceAttributes(sourceAttributes)
                    .withDependencyAttributes(dependencyAttributes)
            return InstrumentedFilesCollector.collect(
                ruleContext,
                instrumentationSpec,  /* coverageSupportFiles= */
                supportFiles,  /* coverageEnvironment= */
                environment,  /* reportedToActualSources= */
                reportedToActualSources,  /* additionalMetadata= */
                metadataFiles,  /* baselineCoverageFiles= */
                baselineCoverageFiles
            )
        }
    }
}
