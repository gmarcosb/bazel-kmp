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
package com.google.devtools.build.lib.analysis.constraints

import com.google.devtools.build.lib.server.FailureDetails.Analysis

/**
 * Implementation of the semantics of Bazel's constraint specification and enforcement system.
 * 
 * 
 * This is how the system works:
 * 
 * 
 * All build rules can declare which "static environments" they can be built for, where a "static
 * environment" is a label instance of an [EnvironmentRule] rule declared in a BUILD file.
 * There are various ways to do this:
 * 
 * 
 *  * Through a "restricted to" attribute setting ([       ][com.google.devtools.build.lib.packages.RuleClass.RESTRICTED_ENVIRONMENT_ATTR]). This is the
 * most direct form of specification - it declares the exact set of environments the rule
 * supports (for its group - see precise details below).
 *  * Through a "compatible with" attribute setting ([       ][com.google.devtools.build.lib.packages.RuleClass.COMPATIBLE_ENVIRONMENT_ATTR]. This
 * declares **additional** environments a rule supports in addition to "standard"
 * environments that are supported by default (see below).
 *  * Through "default" specifications in [EnvironmentGroup] rules. Every environment
 * belongs to a group of thematically related peers (e.g. "target architectures", "JDK
 * versions", or "mobile devices"). An environment group's definition includes which of these
 * environments should be supported "by default" if not otherwise specified by one of the
 * above mechanisms. In particular, a rule with no environment-related attributes
 * automatically inherits all defaults.
 *  * Through a rule class default ([       ][com.google.devtools.build.lib.packages.RuleClass.Builder.restrictedTo] and [       ][com.google.devtools.build.lib.packages.RuleClass.Builder.compatibleWith]). This overrides
 * global defaults for all instances of the given rule class. This can be used, for example,
 * to make all *_test rules "testable" without each instance having to explicitly declare this
 * capability.
 * 
 * 
 * 
 * Groups exist to model the idea that some environments are related while others have nothing to
 * do with each other. Say, for example, we want to say a rule works for PowerPC platforms but not
 * x86. We can do so by setting its "restricted to" attribute to `['//sample/path:powerpc']`.
 * Because both PowerPC and x86 are in the same "target architectures" group, this setting removes
 * x86 from the set of supported environments. But since JDK support belongs to its own group ("JDK
 * versions") it says nothing about which JDK the rule supports.
 * 
 * 
 * More precisely, if a rule has a "restricted to" value of [A, B, C], this removes support for
 * all default environments D such that group(D) is in [group(A), group(B), group(C)] AND D is not
 * in [A, B, C] (in other words, D isn't explicitly opted back in). The rule's full set of supported
 * environments thus becomes [A, B, C] + all defaults that belong to unrelated groups.
 * 
 * 
 * If the rule has a "compatible with" value of [E, F, G], these are unconditionally added to its
 * set of supported environments (in addition to the results from above).
 * 
 * 
 * An environment may not appear in both a rule's "restricted to" and "compatible with" values.
 * If two environments belong to the same group, they must either both be in "restricted to", both
 * be in "compatible with", or not explicitly specified.
 * 
 * 
 * Given all the above, constraint enforcement is this: rule A can depend on rule B if, for every
 * static environment A supports, B also supports that environment.
 * 
 * 
 * Configurable attributes introduce the additional concept of "refined environments". Given:
 * 
 * <pre>
 * java_library(
 * name = "lib",
 * restricted_to = [":A", ":B"],
 * deps = select({
 * ":config_a": [":depA"],
 * ":config_b": [":depB"],
 * }))
 * java_library(
 * name = "depA",
 * restricted_to = [":A"])
 * java_library(
 * name = "depB",
 * restricted_to = [":B"])
</pre> * 
 * 
 * "lib"'s static environments are what are declared via restricted_to: `[":A", ":B"]`. But
 * normal constraint checking doesn't work well here: neither "depA" or "depB" supports both
 * environments, so each is technically invalid. But the two of them together *do* support both
 * environments. So constraint checking with selects checks that "lib"'s environments are supported
 * by the *union* of its selectable dependencies, then *refines* its environments to
 * whichever deps get chosen. In other words:
 * 
 * 
 *  1. The above example is considered constraint-valid.
 *  1. When building with "config_a", "lib"'s refined environment set is `[":A"]`.
 *  1. When building with "config_b", "lib"'s refined environment set is `[":B"]`.
 *  1. Any rule depending on "lib" has its environments refined by the intersection with "lib". So
 * if "depender" has `restricted_to = [":A", ":B"]` and `deps = [":lib"]`, then
 * when building with "config_a", "depender"'s refined environment set is `[":A"]`.
 *  1. For each environment group, every rule's refined environment set must be non-empty. This
 * ensures the "chosen" dep in a select matches all rules up the dependency chain. So if
 * "depender" had `restricted_to = [":B"]`, it wouldn't be allowed in a "config_a"
 * build.
 * 
 * 
 * .
 * 
 * @param <T> The type of object to check for constraints.
</T> */
interface ConstraintSemantics<T> {
    /**
     * Returns the set of environments this rule supports.
     * 
     * 
     * Note this set is **not complete** - it doesn't include environments from groups we don't
     * "know about". Environments and groups can be declared in any package. If the rule includes no
     * references to that package, then it simply doesn't know anything about them. But the constraint
     * semantics say the rule should support the defaults for that group. We encode this implicitly:
     * given the returned set, for any group that's not in the set the rule is also considered to
     * support that group's defaults.
     * 
     * @param context analysis context for the rule. A rule error is triggered here if invalid
     * constraint settings are discovered.
     * @return the environments this rule supports, not counting defaults "unknown" to this rule as
     * described above. Returns null if any errors are encountered.
     */
    fun getSupportedEnvironments(context: T?): EnvironmentCollection?

    /**
     * Performs constraint checking on the given rule's dependencies and reports any errors. This
     * includes:
     * 
     * 
     *  * Static environment checking: if this rule supports environment E, all deps outside
     * selects must also support E
     *  * Refined environment computation: this rule's refined environments are its static
     * environments intersected with the refined environments of all dependencies (including
     * chosen deps in selects)
     *  * Refined environment checking: no environment groups can be "emptied" due to refinement
     * 
     * 
     * @param context the rule to analyze
     * @param staticEnvironments the rule's supported environments, as defined by the return value of
     * [.getSupportedEnvironments]. In particular, for any environment group that's not in
     * this collection, the rule is assumed to support the defaults for that group.
     * @param refinedEnvironments a builder for populating this rule's refined environments
     * @param removedEnvironmentCulprits a builder for populating the core dependencies that trigger
     * pruning away environments through refinement. If multiple dependencies qualify (e.g. two
     * direct deps under the current rule), one is arbitrarily chosen.
     */
    fun checkConstraints(
        context: T?,
        staticEnvironments: EnvironmentCollection?,
        refinedEnvironments: com.google.devtools.build.lib.analysis.constraints.EnvironmentCollection.Builder?,
        removedEnvironmentCulprits: MutableMap<com.google.devtools.build.lib.cmdline.Label?, RemovedEnvironmentCulprit?>?
    )

    /** Exception indicating errors finding/parsing environments or their containing groups.  */
    class EnvironmentLookupException private constructor(detailedExitCode: DetailedExitCode) :
        java.lang.Exception(detailedExitCode.getFailureDetail().getMessage()), DetailedException {
        private val detailedExitCode: DetailedExitCode

        init {
            this.detailedExitCode = detailedExitCode
        }

        override fun getDetailedExitCode(): DetailedExitCode {
            return detailedExitCode
        }
    }

    companion object {
        /**
         * Returns the environment group that owns the given environment. Both must belong to the same
         * package.
         * 
         * @throws EnvironmentLookupException if the input is not an [EnvironmentRule] or no
         * matching group is found
         */
        @Throws(EnvironmentLookupException::class)
        fun getEnvironmentGroup(envTarget: com.google.devtools.build.lib.packages.Target): EnvironmentGroup {
            if (envTarget !is com.google.devtools.build.lib.packages.Rule || envTarget.getRuleClass() != ConstraintConstants.ENVIRONMENT_RULE) {
                throw createEnvironmentLookupException(
                    envTarget.getLabel().toString() + " is not a valid environment definition",
                    Code.INVALID_ENVIRONMENT
                )
            }
            // TODO(https://github.com/bazelbuild/bazel/issues/23852): support package pieces.
            val groups: Iterable<EnvironmentGroup>
            if (envTarget.getPackageoid() is com.google.devtools.build.lib.packages.Package) {
                groups = pkg.getTargets<EnvironmentGroup?>(EnvironmentGroup::class.java)
            } else if (envTarget.getPackageoid() is PackagePiece) {
                // Note that environments and environment groups are prohibited in symbolic macros; therefore,
                // if package piece evaluation is enabled and an environment and environment group belong to
                // have the same package ID, then they belong to the same package piece.
                groups = pkgPiece.getTargets<EnvironmentGroup?>(EnvironmentGroup::class.java)
            } else {
                throw java.lang.AssertionError("Unknown packageoid " + envTarget.getPackageoid())
            }
            for (group in groups) {
                if (group.getEnvironments().contains(envTarget.getLabel())) {
                    return group
                }
            }
            throw createEnvironmentLookupException(
                "cannot find the group for environment " + envTarget.getLabel(),
                Code.ENVIRONMENT_MISSING_FROM_GROUPS
            )
        }

        /**
         * Returns an [EnvironmentLookupException] with the specified message and detailed failure
         * code.
         */
        fun createEnvironmentLookupException(message: String?, code: Code?): EnvironmentLookupException {
            return EnvironmentLookupException(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(message)
                        .setAnalysis(Analysis.newBuilder().setCode(code))
                        .build()
                )
            )
        }
    }
}
