// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.LabelAndLocation
import com.google.devtools.build.lib.analysis.constraints.EnvironmentCollection
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec

/**
 * A provider that advertises which environments the associated target is compatible with
 * (from the point of view of the constraint enforcement system).
 */
interface SupportedEnvironmentsProvider : com.google.devtools.build.lib.analysis.TransitiveInfoProvider {
    /**
     * Returns the static environments this target is compatible with. Static environments
     * are those that are independent of build configuration (e.g. declared in `restricted_to` /
     * `compatible_with`). See [ConstraintSemantics] for details.
     */
    fun getStaticEnvironments(): EnvironmentCollection?

    /**
     * Returns the refined environments this rule is compatible with. Refined environments are
     * static environments with unsupported environments from `select`able deps removed (on the
     * principle that others paths in the select would have provided those environments, so this rule
     * is "refined" to match whichever deps got chosen).
     * 
     * 
     * >Refined environments require knowledge of the build configuration. See
     * [ConstraintSemantics] for details.
     */
    fun getRefinedEnvironments(): EnvironmentCollection?

    /**
     * Provides all context necessary to communicate which dependencies caused an environment to be
     * refined out of the current rule.
     * 
     * 
     * The culprit**s** are actually two rules:
     * 
     * <pre>
     * some_rule(name = "adep", restricted_to = ["//foo:a"])
     * 
     * some_rule(name = "bdep", restricted_to = ["//foo:b"])
     * 
     * some_rule(
     * name = "has_select",
     * restricted_to = ["//foo:a", "//foo:b"],
     * deps = select({
     * ":acond": [:"adep"],
     * ":bcond": [:"bdep"],
     * }
    </pre> * 
     * 
     * 
     * If we build a target with `":has_select"` somewhere in its deps and trigger
     * `":bcond"` and that strips `"//foo:a"` out of the top-level target's
     * environments in a way that triggers an error, the user needs to understand two rules to trace
     * this error. `":has_select"` is the direct culprit, because this is the first rule
     * that strips `"//foo:a"`. But it does that because its `select()` path
     * chooses `":bdep"`, and `":bdep"` is why `":has_select"`
     * decides it's a `"//foo:b"`-only rule for this build.
     */
    @AutoCodec
    class RemovedEnvironmentCulprit(
        culprit: LabelAndLocation?,
        selectedDepForCulprit: com.google.devtools.build.lib.cmdline.Label?
    ) {
        val culprit: LabelAndLocation?
        val selectedDepForCulprit: com.google.devtools.build.lib.cmdline.Label?

        init {
            this.selectedDepForCulprit = selectedDepForCulprit
            this.culprit = culprit
            java.util.Objects.requireNonNull<LabelAndLocation?>(culprit, "culprit")
            java.util.Objects.requireNonNull<com.google.devtools.build.lib.cmdline.Label?>(
                selectedDepForCulprit,
                "selectedDepForCulprit"
            )
        }

        companion object {
            fun create(
                culprit: LabelAndLocation?,
                selectedDepForCulprit: com.google.devtools.build.lib.cmdline.Label?
            ): RemovedEnvironmentCulprit {
                return RemovedEnvironmentCulprit(culprit, selectedDepForCulprit)
            }
        }
    }

    /**
     * If the given environment was refined away from this target's set of supported environments,
     * returns the dependency that originally removed the environment.
     * 
     * 
     * For example, if the current rule is restricted_to [E] and depends on D1, D1 is restricted_to
     * [E] and depends on D2, and D2 is restricted_to [E, F] and has a select() with one path
     * following an E-restricted dep and the other path following an F-restricted dep, then when the
     * build chooses the F path the current rule has [E] refined to [] and D2 is the culprit.
     * 
     * 
     * If the given environment was not refined away for this rule, returns null.
     * 
     * 
     * See [ConstraintSemantics] class documentation for more details on refinement.
     */
    fun getRemovedEnvironmentCulprit(environment: com.google.devtools.build.lib.cmdline.Label?): RemovedEnvironmentCulprit?
}
