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
package com.google.devtools.build.buildjar.javac

import com.google.auto.value.AutoValue
import com.google.devtools.build.buildjar.javac.WerrorCustomOption
import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.Builder.build
import com.google.devtools.build.buildjar.javac.plugins.processing.AnnotationProcessingModule.Builder.build
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.build
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.build
import java.util.LinkedHashSet

/**
 * Preprocess javac -Xlint options. We also need to make the different versions of javac treat
 * -Xlint options uniformly.
 * 
 * 
 * Some versions of javac now process the -Xlint options without allowing later options to
 * override earlier ones on the command line. For example, `-Xlint:All -Xlint:None` results in
 * all warnings being enabled.
 * 
 * 
 * This class preprocesses the -Xlint options within the javac options to achieve a command line
 * that is sensitive to ordering. That is, with this preprocessing step, `-Xlint:all -Xlint:none` results in no warnings being enabled.
 */
class JavacOptions internal constructor(normalizers: com.google.common.collect.ImmutableList<JavacOptionNormalizer>) {
    /** A collection of javac flags, divided into Bazel-specific and standard options.  */
    @AutoValue
    abstract class FilteredJavacopts {
        /** Bazel-specific javac flags, e.g. Error Prone's -Xep: flags.  */
        abstract fun bazelJavacopts(): com.google.common.collect.ImmutableList<String?>?

        /** Standard javac flags.  */
        abstract fun standardJavacopts(): com.google.common.collect.ImmutableList<String?>?

        companion object {
            /** Creates a [FilteredJavacopts].  */
            fun create(
                bazelJavacopts: com.google.common.collect.ImmutableList<String?>?,
                standardJavacopts: com.google.common.collect.ImmutableList<String?>?
            ): FilteredJavacopts {
                return AutoValue_JavacOptions_FilteredJavacopts(bazelJavacopts, standardJavacopts)
            }
        }
    }

    /**
     * Interface to define an option normalizer. For instance, to group all -Xlint: option into one
     * place.
     * 
     * 
     * For each option, the first option normalized whose [.processOption] method returns
     * true stops its parsing and the option is supposed to be added at the end to the normalized list
     * of option with the [.normalize] method. Options not handled by a normalizer will be
     * returned as such in the normalized option list.
     */
    interface JavacOptionNormalizer {
        /**
         * Process an option and return true if the option was handled by this normalizer. `remaining` provides an iterator to any remaining options so normalizers that process
         * non-nullary options can also process the options' arguments.
         */
        fun processOption(option: String?, remaining: MutableIterator<String?>?): Boolean

        /**
         * Add the normalized versions of the options handled by [.processOption] to the `normalized` list
         */
        fun normalize(normalized: MutableList<String?>?)
    }

    /**
     * Parse an option that starts with `-Xlint:` into a bunch of xlintopts. We silently drop
     * xlintopts that would disable any warnings that we turn into errors by default (treating them
     * like invalid xlintopts). It also parse -nowarn option as -Xlint:none.
     */
    class XlintOptionNormalizer @kotlin.jvm.JvmOverloads constructor(enforcedXlints: com.google.common.collect.ImmutableList<String?> = com.google.common.collect.ImmutableList.of<String?>()) :
        JavacOptionNormalizer {
        /**
         * This type models a starting selection from which lint options can be added or removed. E.g.,
         * `-Xlint` indicates we start with the set of recommended checks enabled, and `-Xlint:none` means we start without any checks enabled.
         */
        private enum class BasisXlintSelection {
            /** `-Xlint:none`  */
            None,

            /** `-Xlint:all`  */
            All,

            /** `-Xlint`  */
            Recommended,

            /** Nothing specified; default}  */
            Empty
        }

        private val enforcedXlints: com.google.common.collect.ImmutableList<String?>
        private val xlintPlus: MutableSet<String?>
        private val xlintMinus: MutableSet<String?> = LinkedHashSet<String?>()
        private var xlintBasis = BasisXlintSelection.Empty

        init {
            this.enforcedXlints = enforcedXlints
            xlintPlus = LinkedHashSet<String?>(enforcedXlints)
            resetBasisTo(BasisXlintSelection.Empty)
        }

        override fun processOption(option: String, remaining: MutableIterator<String?>?): Boolean {
            if (option == "-nowarn") {
                // It is equivalent to -Xlint:none
                resetBasisTo(BasisXlintSelection.None)
                return true
            } else if (option == "-Xlint") {
                resetBasisTo(BasisXlintSelection.Recommended)
                return true
            } else if (option.startsWith("-Xlint")) {
                for (arg in option.substring("-Xlint:".length).split(",".toRegex()).toTypedArray()) {
                    var arg: String = arg
                    if (arg == "all" || arg.isEmpty()) {
                        resetBasisTo(BasisXlintSelection.All)
                    } else if (arg == "none") {
                        resetBasisTo(BasisXlintSelection.None)
                    } else if (arg.startsWith("-")) {
                        arg = arg.substring("-".length)
                        if (!enforcedXlints.contains(arg)) {
                            xlintPlus.remove(arg)
                            if (xlintBasis != BasisXlintSelection.None) {
                                xlintMinus.add(arg)
                            }
                        }
                    } else { // not a '-' prefix
                        xlintMinus.remove(arg)
                        if (xlintBasis != BasisXlintSelection.All) {
                            xlintPlus.add(arg)
                        }
                    }
                }
                return true
            }
            return false
        }

        override fun normalize(normalized: MutableList<String?>) {
            when (xlintBasis) {
                BasisXlintSelection.Recommended -> normalized.add("-Xlint")
                BasisXlintSelection.All -> normalized.add("-Xlint:all")
                BasisXlintSelection.None -> if (xlintPlus.isEmpty()) {
                    /*
             * This should never happen with warnings as errors. The plus set should always contain
             * at least the warnings in warningsAsErrors.
             */
                    normalized.add("-Xlint:none")
                }

                else -> {}
            }
            if (xlintBasis != BasisXlintSelection.All && !xlintPlus.isEmpty()) {
                normalized.add("-Xlint:" + COMMA_JOINER.join(xlintPlus))
            }
            if (xlintBasis != BasisXlintSelection.None && !xlintMinus.isEmpty()) {
                normalized.add("-Xlint:-" + COMMA_MINUS_JOINER.join(xlintMinus))
            }
        }

        private fun resetBasisTo(selection: BasisXlintSelection) {
            xlintBasis = selection
            xlintPlus.clear()
            xlintMinus.clear()
            if (selection != BasisXlintSelection.All) {
                xlintPlus.addAll(enforcedXlints)
            }
        }

        companion object {
            private val COMMA_MINUS_JOINER: com.google.common.base.Joiner = com.google.common.base.Joiner.on(",-")
            private val COMMA_JOINER: com.google.common.base.Joiner = com.google.common.base.Joiner.on(",")
        }
    }

    /**
     * Normalizer for `-source`, `-target`, and `--release` options. If both `-source` and `--release` are specified, `--release` wins.
     */
    class ReleaseOptionNormalizer : JavacOptionNormalizer {
        private var source: String? = null
        private var target: String? = null
        private var release: String? = null
        private val modular: MutableList<String?> = java.util.ArrayList<String?>()
        private var preview = false

        override fun processOption(option: String, remaining: MutableIterator<String?>): Boolean {
            when (option) {
                "-source" -> {
                    if (remaining.hasNext()) {
                        source = remaining.next()
                        release = null
                    }
                    return true
                }

                "-target" -> {
                    if (remaining.hasNext()) {
                        target = remaining.next()
                        release = null
                    }
                    return true
                }

                "--release" -> {
                    if (remaining.hasNext()) {
                        release = remaining.next()
                        source = null
                        target = null
                    }
                    return true
                }

                "--add-exports", "--add-opens", "--add-modules" -> {
                    if (remaining.hasNext()) {
                        modular.add(option)
                        modular.add(remaining.next())
                    }
                    return true
                }

                else -> {}
            }
            if (option.startsWith("--release=")) {
                release = option.substring("--release=".length)
                source = null
                target = null
                return true
            }
            if (option.startsWith("--add-exports=")
                || option.startsWith("--add-opens=")
                || option.startsWith("--add-modules=")
            ) {
                modular.add(option)
                return true
            }
            if (option == "--enable-preview") {
                preview = true
                return true
            }
            return false
        }

        private fun addModular(normalized: MutableList<String?>) {
            val value: String?
            if (release != null) {
                value = release
            } else if (target != null) {
                value = target
            } else {
                return
            }
            val hasPrefix: Boolean = value.startsWith("1.")
            val version: Int? =
                com.google.common.primitives.Ints.tryParse(if (hasPrefix) value.substring("1.".length) else value)
            if (version == null) {
                return
            }
            if (version > 8) {
                normalized.addAll(modular)
                if (preview) {
                    normalized.add("--enable-preview")
                }
            }
        }

        override fun normalize(normalized: MutableList<String?>) {
            addModular(normalized)
            if (release != null) {
                normalized.add("--release")
                normalized.add(release)
            } else {
                if (source != null) {
                    normalized.add("-source")
                    normalized.add(source)
                }
                if (target != null) {
                    normalized.add("-target")
                    normalized.add(target)
                }
            }
        }
    }

    /**
     * Parse an option that starts with `-Werror:` into a bunch of werroropts. We silently drop
     * werroropts that would disable any warnings that we turn into errors by default (treating them
     * like invalid werroropts).
     */
    private class WErrorOptionNormalizer(warningsAsErrorsDefault: com.google.common.collect.ImmutableList<String?>) :
        JavacOptionNormalizer {
        private val builder: com.google.devtools.build.buildjar.javac.WerrorCustomOption.Builder

        init {
            builder = com.google.devtools.build.buildjar.javac.WerrorCustomOption.Builder(warningsAsErrorsDefault)
        }

        override fun processOption(option: String, remaining: MutableIterator<String?>?): Boolean {
            if (option.startsWith("-Werror:")) {
                builder.process(option)
                return true
            }
            if (option == "-Werror") {
                builder.all()
                return true
            }
            return false
        }

        override fun normalize(normalized: MutableList<String?>) {
            val flag = builder.build().toString()
            if (!flag.isEmpty()) {
                normalized.add(flag)
            }
        }
    }

    private val normalizers: com.google.common.collect.ImmutableList<JavacOptionNormalizer>

    init {
        this.normalizers = normalizers
    }

    /**
     * Outputs a reasonably normalized javac option list.
     * 
     * @param javacopts the raw javac option list to cleanup
     * @return a new cleaned up javac option list
     */
    fun normalize(javacopts: MutableList<String?>): MutableList<String?> {
        val normalized: MutableList<String?> = java.util.ArrayList<String?>()

        val it = javacopts.iterator()
        while (it.hasNext()) {
            val opt = it.next()
            var found = false
            for (normalizer in normalizers) {
                if (normalizer.processOption(opt, it)) {
                    found = true
                    break
                }
            }
            if (!found) {
                normalized.add(opt)
            }
        }

        for (normalizer in normalizers) {
            normalizer.normalize(normalized)
        }
        return normalized
    }

    companion object {
        /** Returns an immutable list containing all the non-Bazel specific Javac flags.  */
        fun removeBazelSpecificFlags(javacopts: Array<String?>): com.google.common.collect.ImmutableList<String?>? {
            return Companion.removeBazelSpecificFlags(java.util.Arrays.asList<String?>(*javacopts))
        }

        /** Returns an immutable list containing all the non-Bazel specific Javac flags.  */
        fun removeBazelSpecificFlags(javacopts: Iterable<String>): com.google.common.collect.ImmutableList<String?>? {
            return filterJavacopts(javacopts).standardJavacopts()
        }

        /** Filters a list of javac flags into Bazel-specific and standard flags.  */
        fun filterJavacopts(javacopts: Iterable<String>): FilteredJavacopts {
            val bazelJavacopts: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()
            val standardJavacopts: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()
            for (opt in javacopts) {
                if (isBazelSpecificFlag(opt)) {
                    bazelJavacopts.add(opt)
                } else {
                    standardJavacopts.add(opt)
                }
            }
            return FilteredJavacopts.Companion.create(bazelJavacopts.build(), standardJavacopts.build())
        }

        private fun isBazelSpecificFlag(opt: String): Boolean {
            return opt.startsWith("-Werror:") || opt.startsWith("-Xep")
        }

        /**
         * Outputs a reasonably normalized javac option list.
         * 
         * @param javacopts the raw javac option list to cleanup
         * @param normalizers the list of normalizers to apply
         * @return a new cleaned up javac option list
         */
        fun normalizeOptionsWithNormalizers(
            javacopts: MutableList<String?>, vararg normalizers: JavacOptionNormalizer?
        ): MutableList<String?> {
            return JavacOptions(com.google.common.collect.ImmutableList.copyOf<JavacOptionNormalizer?>(normalizers)).normalize(
                javacopts
            )
        }

        /**
         * Creates a [JavacOptions] normalizer that will ensure the given set of lint categories are
         * enabled as errors, overriding any user-provided configuration for those options.
         */
        fun createWithWarningsAsErrorsDefault(
            warningsAsErrorsDefault: com.google.common.collect.ImmutableList<String?>
        ): JavacOptions {
            return JavacOptions(
                com.google.common.collect.ImmutableList.of<JavacOptionNormalizer?>(
                    XlintOptionNormalizer(warningsAsErrorsDefault),
                    WErrorOptionNormalizer(warningsAsErrorsDefault),
                    ReleaseOptionNormalizer()
                )
            )
        }
    }
}
