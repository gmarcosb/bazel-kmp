// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.common

import com.google.devtools.build.lib.cmdline.RepositoryMapping

/** Options shared between blaze query implementations.  */
@com.google.devtools.common.options.OptionsClass
abstract class CommonQueryOptions : com.google.devtools.common.options.OptionsBase() {
    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "universe_scope",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        converter = com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter::class,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS],
        help = ("A comma-separated set of target patterns (additive and subtractive). The query may be"
                + " performed in the universe defined by the transitive closure of the specified"
                + " targets. This option is used for the query and cquery commands.\n"
                + "For cquery, the input to this option is the targets all answers are built under"
                + " and so this option may affect configurations and transitions. If this option is"
                + " not specified, the top-level targets are assumed to be the targets parsed from"
                + " the query expression. Note: For cquery, not specifying this option may cause the"
                + " build to break if targets parsed from the query expression are not buildable"
                + " with top-level options.")
    )
    abstract val universeScope: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "line_terminator_null",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = "Whether each format is terminated with \\0 instead of newline."
    )
    abstract val lineTerminatorNull: Boolean

    val lineTerminator: String
        /** Ugly workaround since line terminator option default has to be constant expression.  */
        get() {
            if (this.lineTerminatorNull) {
                return "\u0000"
            }

            return "\n"
        }

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "infer_universe_scope",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS],
        help = ("If set and --universe_scope is unset, then a value of --universe_scope will be inferred"
                + " as the list of unique target patterns in the query expression. Note that the"
                + " --universe_scope value inferred for a query expression that uses universe-scoped"
                + " functions (e.g.`allrdeps`) may not be what you want, so you should use this"
                + " option only if you know what you are doing. See"
                + " https://bazel.build/reference/query#sky-query for details and"
                + " examples. If --universe_scope is set, then this option's value is ignored. Note:"
                + " this option applies only to `query` (i.e. not `cquery`).")
    )
    abstract val inferUniverseScope: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "tool_deps",
        oldName = "host_deps",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BUILD_FILE_SEMANTICS],
        help = ("Query: If disabled, dependencies on 'exec configuration' will"
                + " not be included in the dependency graph over which the query operates. An 'exec"
                + " configuration' dependency edge, such as the one from any 'proto_library' rule to"
                + " the Protocol Compiler, usually points to a tool executed during the build rather"
                + " than a part of the same 'target' program.\n"
                + "Cquery: If disabled, filters out all configured targets which cross an"
                + " execution transition from the top-level target that discovered this configured"
                + " target. That means if the top-level target is in the target configuration, only"
                + " configured targets also in the target configuration will be returned. If the"
                + " top-level target is in the exec configuration, only exec configured targets will"
                + " be returned. This option will NOT exclude resolved toolchains.")
    )
    abstract var includeToolDeps: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "implicit_deps",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BUILD_FILE_SEMANTICS],
        help = ("If enabled, implicit dependencies will be included in the dependency graph over "
                + "which the query operates. An implicit dependency is one that is not explicitly "
                + "specified in the BUILD file but added by bazel. For cquery, this option controls "
                + "filtering resolved toolchains.")
    )
    abstract var includeImplicitDeps: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "nodep_deps",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BUILD_FILE_SEMANTICS],
        help = ("If enabled, deps from \"nodep\" attributes will be included in the dependency graph "
                + "over which the query operates. A common example of a \"nodep\" attribute is "
                + "\"visibility\". Run and parse the output of `info build-language` to learn about "
                + "all the \"nodep\" attributes in the build language.")
    )
    abstract var includeNoDepDeps: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "include_aspects",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = ("aquery, cquery: whether to include aspect-generated actions in the output. "
                + "query: no-op (aspects are always followed).")
    )
    abstract val useAspects: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "incompatible_package_group_includes_double_slash",
        defaultValue = com.google.devtools.build.lib.query2.common.FlagConstants.DEFAULT_INCOMPATIBLE_PACKAGE_GROUP_INCLUDES_DOUBLE_SLASH,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = ("If enabled, when outputting package_group's `packages` attribute, the leading `//`"
                + " will not be omitted.")
    )
    abstract var incompatiblePackageGroupIncludesDoubleSlash: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "incompatible_package_group_build_output",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = "If enabled, `blaze query --output=build` will output `package_group` targets."
    )
    abstract var incompatiblePackageGroupBuildOutput: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_explicit_aspects",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = ("aquery, cquery: whether to include aspect-generated actions in the output. "
                + "query: no-op (aspects are always followed).")
    )
    abstract val explicitAspects: Boolean

    /** Return the current options as a set of QueryEnvironment settings.  */
    open fun toSettings(): MutableSet<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting?> {
        val settings: MutableSet<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting?> =
            EnumSet.noneOf<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting?>(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting::class.java)
        if (!this.includeToolDeps) {
            settings.add(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.ONLY_TARGET_DEPS)
        }
        if (!this.includeImplicitDeps) {
            settings.add(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        }
        if (!this.includeNoDepDeps) {
            settings.add(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.NO_NODEP_DEPS)
        }
        if (this.useAspects) {
            settings.add(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.INCLUDE_ASPECTS)
        }
        if (this.explicitAspects) {
            settings.add(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.EXPLICIT_ASPECTS)
        }
        return settings
    }

    @get:com.google.devtools.common.options.Option(
        name = "consistent_labels",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = ("If enabled, every query command emits labels as if by the Starlark `str`"
                + " function applied to a `Label` instance. This is useful for tools that"
                + " need to match the output of different query commands and/or labels emitted by"
                + " rules. If not enabled, output formatters are free to emit apparent repository"
                + " names (relative to the main repository) instead to make the output more"
                + " readable.")
    )
    abstract val emitConsistentLabels: Boolean

    fun getLabelPrinter(
        starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?, mainRepoMapping: RepositoryMapping?
    ): LabelPrinter {
        return if (this.emitConsistentLabels)
            LabelPrinter.starlark(starlarkSemantics)
        else
            LabelPrinter.displayForm(mainRepoMapping)
    }

    fun getLabelPrinterLegacy(starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?): LabelPrinter {
        return if (this.emitConsistentLabels)
            LabelPrinter.starlark(starlarkSemantics)
        else
            LabelPrinter.LEGACY
    }

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "relative_locations",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = ("If true, the location of BUILD files in xml and proto outputs will be relative. "
                + "By default, the location output is an absolute path and will not be consistent "
                + "across machines. You can set this option to true to have a consistent result "
                + "across machines.")
    )
    abstract var relativeLocations: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "proto:locations",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = "Whether to output location information in proto output at all."
    )
    abstract var protoIncludeLocations: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "proto:default_values",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = ("If true, attributes whose value is not explicitly specified in the BUILD file are "
                + "included; otherwise they are omitted. This option is applicable to --output=proto")
    )
    abstract var protoIncludeDefaultValues: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "proto:flatten_selects",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BUILD_FILE_SEMANTICS],
        help = ("If enabled, configurable attributes created by select() are flattened. For list types "
                + "the flattened representation is a list containing each value of the select map "
                + "exactly once. Scalar types are flattened to null.")
    )
    abstract var protoFlattenSelects: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "proto:output_rule_attrs",
        converter = com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter::class,
        defaultValue = "all",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = ("Comma separated list of attributes to include in output. Defaults to all attributes. "
                + "Set to empty string to not output any attribute. "
                + "This option is applicable to --output=proto.")
    )
    abstract var protoOutputRuleAttributes: MutableList<String?>?

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "proto:rule_inputs_and_outputs",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = "Whether or not to populate the rule_input and rule_output fields."
    )
    abstract var protoIncludeRuleInputsAndOutputs: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "proto:include_synthetic_attribute_hash",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = "Whether or not to calculate and populate the \$internal_attr_hash attribute."
    )
    abstract var protoIncludeSyntheticAttributeHash: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "proto:instantiation_stack",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = ("Populate the instantiation call stack of each rule. "
                + "Note that this requires the stack to be present")
    )
    abstract var protoIncludeInstantiationStack: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "proto:definition_stack",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = ("Populate the definition_stack proto field, which records for each rule instance the "
                + "Starlark call stack at the moment the rule's class was defined.")
    )
    abstract val protoIncludeDefinitionStack: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "proto:include_starlark_rule_env",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = ("Use the starlark environment in the value of the generated \$internal_attr_hash"
                + " attribute. This ensures that the starlark rule definition (and its transitive"
                + " imports) are part of this identifier.")
    )
    abstract val protoIncludeStarlarkRuleEnv: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "proto:include_attribute_source_aspects",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = ("Populate the source_aspect_name proto field of each Attribute with the source aspect "
                + "that the attribute came from (empty string if it did not).")
    )
    abstract val protoIncludeAttributeSourceAspects: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "proto:rule_classes",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = ("Populate the rule_class_key field of each rule; and for the first rule with a given"
                + " rule_class_key, also populate its rule_class_info proto field. The rule_class_key"
                + " field uniquely identifies a rule class, and the rule_class_info field is a"
                + " Stardoc-format rule class API definition.")
    )
    abstract var protoRuleClasses: Boolean

    /** An enum converter for `AspectResolver.Mode` . Should be used internally only.  */
    class AspectResolutionModeConverter :
        com.google.devtools.common.options.EnumConverter<com.google.devtools.build.lib.query2.query.aspectresolvers.AspectResolver.Mode?>(
            com.google.devtools.build.lib.query2.query.aspectresolvers.AspectResolver.Mode::class.java,
            "Aspect resolution mode"
        )

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "aspect_deps",
        converter = AspectResolutionModeConverter::class,
        defaultValue = "conservative",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BUILD_FILE_SEMANTICS],
        help = ("How to resolve aspect dependencies when the output format is one of {xml,proto,record}. "
                + "'off' means no aspect dependencies are resolved, 'conservative' (the default) "
                + "means all declared aspect dependencies are added regardless of whether they are "
                + "given the rule class of direct dependencies, 'precise' means that only those "
                + "aspects are added that are possibly active given the rule class of the direct "
                + "dependencies. Note that precise mode requires loading other packages to evaluate "
                + "a single target thus making it slower than the other modes. Also note that even "
                + "precise mode is not completely precise: the decision whether to compute an aspect "
                + "is decided in the analysis phase, which is not run during 'bazel query'.")
    )
    abstract val aspectDeps: com.google.devtools.build.lib.query2.query.aspectresolvers.AspectResolver.Mode?

    abstract fun setAspectDeps(value: com.google.devtools.build.lib.query2.query.aspectresolvers.AspectResolver.Mode?)

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "graph:node_limit",
        defaultValue = "512",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = ("The maximum length of the label string for a graph node in the output.  Longer labels"
                + " will be truncated; -1 means no truncation.  This option is only applicable to"
                + " --output=graph.")
    )
    abstract var graphNodeStringLimit: Int

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "graph:factored",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = ("If true, then the graph will be emitted 'factored', i.e. topologically-equivalent nodes "
                + "will be merged together and their labels concatenated. This option is only "
                + "applicable to --output=graph.")
    )
    abstract var graphFactored: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "query_file",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS],
        help = ("If set, query will read the query from the file named here, rather than on the command "
                + "line. It is an error to specify a file here as well as a command-line query.")
    )
    abstract val queryFile: String?

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "output_file",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = ("When specified, query results will be written directly to this file, and nothing will be"
                + " printed to Bazel's standard output stream (stdout). In benchmarks, this is"
                + " generally faster than `bazel query &gt; file`.")
    )
    abstract val outputFile: String?
}
