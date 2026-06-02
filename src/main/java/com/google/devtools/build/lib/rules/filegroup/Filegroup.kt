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
package com.google.devtools.build.lib.rules.filegroup

import com.google.devtools.build.lib.analysis.OutputGroupInfo.INTERNAL_SUFFIX
import java.lang.String
import kotlin.Unit

/** ConfiguredTarget for "filegroup".  */
class Filegroup : RuleConfiguredTargetFactory {
    @Throws(InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
    public override fun create(ruleContext: RuleContext): ConfiguredTarget {
        val outputGroupName: String = ruleContext.attributes().get("output_group", Type.STRING)
        if (outputGroupName.endsWith(INTERNAL_SUFFIX)) {
            ruleContext.throwWithAttributeError(
                "output_group", String.format(ILLEGAL_OUTPUT_GROUP_ERROR, outputGroupName)
            )
        }

        val filesToBuild: NestedSet<Artifact?> =
            if (outputGroupName.isEmpty())
                PrerequisiteArtifacts.nestedSet(ruleContext.getRulePrerequisitesCollection(), "srcs")
            else
                getArtifactsForOutputGroup(outputGroupName, ruleContext.getPrerequisites("srcs"))

        val instrumentedFilesProvider: InstrumentedFilesInfo? =
            InstrumentedFilesCollector.collect(
                ruleContext,  // Seems strange to have "srcs" in "dependency attributes" instead of "source
                // attributes", but that's correct behavior here because this rule just forwards
                // files, it doesn't process them. It doesn't know if the dependencies of the stuff
                // in srcs is a runtime dependency of its consumers or not. Consumers decide which
                // of the following is the case about a filegroup it depends on based on whether the
                // attribute the dependency is via is in the consumer's source attributes or
                // dependency attributes:
                // * If the filegroup contains coverage-relevant source files, it should be depended
                //   on via something in source attributes. The dependencies for actions which generate
                //   source files are generally not runtime dependencies.
                // * If the dependencies of the filegroup might be coverage-relevant source files (e.g.
                //   a binary target is included in filegroup's srcs and the filegroup target is
                //   included in some other target's data), it should be depended on via something in
                //   dependency attributes.
                InstrumentationSpec(FileTypeSet.ANY_FILE).withDependencyAttributes(
                    "srcs",
                    "data"
                ),  /* reportedToActualSources= */
                NestedSetBuilder.create(Order.STABLE_ORDER)
            )

        // If you're visiting a filegroup as data, then we also visit its data as data.
        val dataRunfilesBuilder: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Builder(ruleContext.getWorkspaceName()).addTransitiveArtifacts(filesToBuild)
        if (ruleContext
                .getConfiguration()
                .getOptions()
                .get(CoreOptions::class.java)
                .getFilegroupRunfilesForData()
        ) {
            // If you're visiting a filegroup as data, then we also visit its data as data.
            dataRunfilesBuilder.addRunfiles(ruleContext, RunfilesProvider.DATA_RUNFILES)
        } else {
            dataRunfilesBuilder.addDataDeps(ruleContext)
        }
        val runfilesProvider: RunfilesProvider? =
            RunfilesProvider.withData(
                Builder(ruleContext.getWorkspaceName())
                    .addRunfiles(ruleContext, RunfilesProvider.DEFAULT_RUNFILES)
                    .build(),
                dataRunfilesBuilder.build()
            )

        val builder: RuleConfiguredTargetBuilder =
            RuleConfiguredTargetBuilder(ruleContext)
                .addProvider(RunfilesProvider::class.java, runfilesProvider)
                .setFilesToBuild(filesToBuild)
                .setRunfilesSupport(null, getExecutable(filesToBuild))
                .addNativeDeclaredProvider(instrumentedFilesProvider)

        return builder.build()
    }

    /**
     * Returns the single Artifact from filesToBuild or `null` if there are multiple elements.
     */
    private fun getExecutable(filesToBuild: NestedSet<Artifact?>): Artifact? {
        return if (filesToBuild.isSingleton()) filesToBuild.getSingleton() else null
    }

    companion object {
        /** Error message for output groups that are explicitly forbidden from filegroup reference.  */
        @kotlin.jvm.JvmField
        val ILLEGAL_OUTPUT_GROUP_ERROR: kotlin.String =
            "Output group %s is not permitted for " + "reference in filegroups."

        /** Returns the artifacts from the given targets that are members of the given output group.  */
        private fun getArtifactsForOutputGroup(
            outputGroupName: kotlin.String?, deps: MutableList<out TransitiveInfoCollection?>
        ): NestedSet<Artifact?> {
            val result: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()

            for (dep in deps) {
                val outputGroupInfo: OutputGroupInfo? = OutputGroupInfo.get(dep)
                if (outputGroupInfo != null) {
                    result.addTransitive(outputGroupInfo.getOutputGroup(outputGroupName))
                }
            }

            return result.build()
        }
    }
}
