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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.ActionConflictException

/**
 * A shortcut class to the appropriate specialization of `RuleClass.ConfiguredTargetFactory`.
 * 
 * 
 * Here follows an overview of how loading and analysis works in Bazel:
 * 
 * 
 * Actions (i.e. commands that are run during the build) are created by configured targets (see
 * [ConfiguredTarget]), which are a pair of a [ ] (e.g. `//src:bazel`) and a [ ], which is a key for a [BuildConfigurationValue], which is a blob of
 * data that contains extra information about how the target should be built (for example, for which
 * platform or with which C++ preprocessor definitions). Accordingly, a target can give rise to
 * multiple configured targets, for example, if it needs to be built both for the exec and the
 * target configuration.
 * 
 * 
 * The process of creating the appropriate [com.google.devtools.build.lib.actions.Action]s
 * for a configured target is called "analysis". The analysis of a configured target is composed of
 * the following steps (which process is orchestrated by [ ]):
 * 
 * 
 *  1. The corresponding [com.google.devtools.build.lib.packages.Target] is loaded, i.e. the
 * BUILD file is parsed.
 *  1. Its direct dependencies are analyzed, during which in turn indirect dependencies are also
 * analyzed.
 *  1. Aspects specified by the configured target are analyzed. These can be thought of as
 * visitations of the transitive dependencies of the target. For more information, see [       ].
 *  1. The configured target and the actions it generates are created based on the data from the
 * previous two steps.
 * 
 * 
 * Targets can be of three main kinds (plus a few special ones which are not important for
 * understanding the big picture):
 * 
 * 
 * 
 *  * Input and output files. These represent either a file that is in the source tree or a file
 * produced by during the build. Not every file produced during the build has a corresponding
 * output file target.
 *  * Rules. These describe things a build actually does. Each rule has a class (e.g. `
 * cc_binary`). Rule classes can be defined either in Starlark using the `rule()
` *  function or in Java code by implementing [     ].
 * 
 * 
 * During the analysis of a configured target, the following pieces of data are available:
 * 
 * 
 *  * The corresponding target itself. This is necessary so that the analysis has access to
 * e.g. the attributes a rule has in the BUILD file.
 *  * The [com.google.devtools.build.lib.analysis.TransitiveInfoCollection]s of direct
 * dependencies. They are used to gather information from the transitive closure, for
 * example, the include path entries for C++ compilation or all the object files that need
 * to be compiled into a C++ binary.
 *  * The configuration, which is used to determine which compiler to use and to get access
 * to some command line options of Bazel that influence analysis.
 *  * Skyframe, for requesting arbitrary Skyframe nodes. This is an escape hatch that should
 * be used when other mechanisms provided are not suitable and allows one to e.g. read
 * arbitrary files. With great power...
 * 
 * 
 * 
 * Analysis of non-rule configured targets is special-cased and is not covered here.
 * 
 * 
 * The analysis of a rule itself is done by implementations [     ] (there should be one for each rule class). The data above is
 * available using the [RuleContext] argument passed into its create() method. It should
 * result in three things:
 * 
 * 
 *  * A set of actions. These should be passed to [RuleContext.registerAction],
 * although for more common cases (e.g. [           ]), shortcuts are provided.
 *  * A set of artifacts (files produced by actions). These should be created using methods
 * of [RuleContext]. Each artifact thus created must have a generating action.
 *  * A set of [com.google.devtools.build.lib.analysis.TransitiveInfoProvider]s that
 * are passed on to direct dependencies. These must be registered using [           ][com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder.add]
 * 
 * 
 * 
 * Configured targets are currently allowed to create artifacts at any exec path. It would be
 * better if they could be constrained to a subtree based on the label of the configured target,
 * but this is currently not feasible because multiple rules violate this constraint and the
 * output format is part of its interface.
 * 
 * 
 * In principle, multiple configured targets should not create actions with conflicting
 * outputs. There are still a few exceptions to this rule that are slated to be eventually
 * removed, we have provisions to handle this case (Action instances that share at least one
 * output file are required to be exactly the same), but this does put some pressure on the
 * design and we are eventually planning to eliminate this option.
 * 
 * 
 * These restrictions together make it possible to:
 * 
 * 
 *  * Correctly cache the analysis phase; by tightly constraining what a configured target is
 * allowed to access and what it is not, we can know when it needs to invalidate a
 * particular one and when it can reuse an already existing one.
 *  * Serialize / deserialize individual configured targets at will, making it possible for
 * example to swap out part of the analysis state if there is memory pressure or to move
 * them in persistent storage so that the state can be reconstructed at a different time
 * or in a different process. The stretch goal is to eventually facilitate cross-user
 * caching of this information.
 * 
 */
interface RuleConfiguredTargetFactory

    : ConfiguredTargetFactory<ConfiguredTarget?, RuleContext?, ActionConflictException?> {
    /** Adds any rule implementation-specific requirements to the given builder.  */
    fun addRuleImplSpecificRequiredConfigFragments(
        requiredFragments: RequiredConfigFragmentsProvider.Builder?,
        attributes: AttributeMap?,
        configuration: BuildConfigurationValue?
    ) {
    }
}
