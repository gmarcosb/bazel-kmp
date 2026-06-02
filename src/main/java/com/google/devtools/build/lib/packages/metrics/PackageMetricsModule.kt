// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages.metrics

import com.google.common.annotations.VisibleForTesting
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider
import com.google.devtools.build.lib.vfs.FileSystem
import com.google.devtools.common.options.*

/** Provides logging for extreme package-loading events.  */
class PackageMetricsModule @VisibleForTesting internal constructor(private val packageLoadingListener: PackageMetricsPackageLoadingListener) :
    BlazeModule() {
    /** Options for [PackageMetricsModule].  */
    @OptionsClass
    abstract class Options : OptionsBase() {
        @Option(
            name = "log_top_n_packages",
            defaultValue = "10",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.BAZEL_MONITORING],
            help = "Configures number of packages included in top-package INFO logging, <= 0 disables."
        )
        abstract fun getNumberOfPackagesToTrack(): Int

        @Option(
            name = "record_metrics_for_all_packages",
            defaultValue = "false",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.BAZEL_MONITORING],
            help = ("Configures PackageMetrics to record all metrics for all packages. Disables Top-n INFO"
                    + " logging.")
        )
        abstract fun getEnableAllMetrics(): Boolean

        @Option(
            name = "experimental_publish_package_metrics_in_bep",
            defaultValue = "false",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.BAZEL_MONITORING],
            help = "Whether to publish package metrics in the BEP."
        )
        abstract fun getPublishPackageMetricsInBep(): Boolean
    }

    constructor() : this(PackageMetricsPackageLoadingListener.Companion.getInstance())

    override fun getPackageLoadingListener(
        packageSettings: PackageSettings?,
        ruleClassProvider: ConfiguredRuleClassProvider?,
        fs: FileSystem?
    ): PackageLoadingListener? {
        return packageLoadingListener
    }

    override fun getCommonCommandOptions(): Iterable<Class<out OptionsBase?>?> {
        return ImmutableList.of<Class<out OptionsBase?>?>(Options::class.java)
    }

    override fun beforeCommand(commandEnvironment: CommandEnvironment) {
        val options: Options? = commandEnvironment.getOptions().getOptions<Options?>(Options::class.java)
        val recorder =
            if (options!!.getEnableAllMetrics())
                CompletePackageMetricsRecorder()
            else
                ExtremaPackageMetricsRecorder(Math.max(options.getNumberOfPackagesToTrack(), 0))
        packageLoadingListener.setPackageMetricsRecorder(recorder)
        packageLoadingListener.setPublishPackageMetricsInBep(options.getPublishPackageMetricsInBep())
    }

    override fun afterCommand() {
        if (packageLoadingListener.getPackageMetricsRecorder() != null) {
            packageLoadingListener.getPackageMetricsRecorder().loadingFinished()
        }
    }
}
