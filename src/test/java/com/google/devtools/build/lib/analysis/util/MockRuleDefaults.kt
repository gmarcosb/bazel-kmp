// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.util

import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.packages.Attribute.attr

/**
 * Default behaviors for [MockRule].
 * 
 * 
 * All of these can be optionally added or overridden for specific mock rules.
 */
object MockRuleDefaults {
    /**
     * Stock `"deps"` attribute for rule classes that don't need special behavior.
     */
    val DEPS_ATTRIBUTE: Attribute.Builder<*>? = attr("deps", BuildType.LABEL_LIST).allowedFileTypes()

    /**
     * The default attributes added to all mock rules.
     * 
     * 
     * Does not apply when [MockRule.ancestor] is set.
     */
    val DEFAULT_ATTRIBUTES: ImmutableList<Attribute.Builder<*>?> = ImmutableList.of<E?>(
        attr("testonly", BOOLEAN).nonconfigurable("test").value(false),
        attr("deprecation", STRING).nonconfigurable("test").value(null as String?),
        attr("tags", STRING_LIST).nonconfigurable("test"),
        attr("visibility", NODEP_LABEL_LIST)
            .orderIndependent()
            .cfg(ExecutionTransitionFactory.createFactory())
            .nonconfigurable("test"),
        attr(RuleClass.COMPATIBLE_ENVIRONMENT_ATTR, LABEL_LIST)
            .allowedFileTypes(FileTypeSet.NO_FILE)
            .dontCheckConstraints(),
        attr(RuleClass.RESTRICTED_ENVIRONMENT_ATTR, LABEL_LIST)
            .allowedFileTypes(FileTypeSet.NO_FILE)
            .dontCheckConstraints(),
        attr(RuleClass.CONFIG_SETTING_DEPS_ATTRIBUTE, LABEL_LIST)
            .nonconfigurable("stores configurability keys")
    )

    /**
     * The default configured target factory for mock rules.
     * 
     * 
     * Can be overridden with [MockRule.factory].
     */
    open class DefaultConfiguredTargetFactory : RuleConfiguredTargetFactory {
        @Throws(InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
        public override fun create(ruleContext: RuleContext): ConfiguredTarget? {
            val filesToBuild: NestedSet<Artifact?>? =
                NestedSetBuilder.wrap(Order.STABLE_ORDER, ruleContext.getOutputArtifacts())
            for (artifact in ruleContext.getOutputArtifacts()) {
                ruleContext.registerAction(
                    FileWriteAction.createEmptyWithInputs(
                        ruleContext.getActionOwner(),
                        NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                        artifact
                    )
                )
            }
            return RuleConfiguredTargetBuilder(ruleContext)
                .setFilesToBuild(filesToBuild)
                .setRunfilesSupport(null, null)
                .add(RunfilesProvider::class.java, RunfilesProvider.simple(Runfiles.EMPTY))
                .build()
        }
    }
}
