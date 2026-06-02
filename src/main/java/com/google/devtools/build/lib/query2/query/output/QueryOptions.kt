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
package com.google.devtools.build.lib.query2.query.output

import com.google.devtools.build.lib.query2.common.CommonQueryOptions
import com.google.devtools.build.lib.query2.engine.QueryEnvironment
import com.google.devtools.common.options.*

/** Command-line options for the Blaze query language, revision 2.  */
@OptionsClass
abstract class QueryOptions : CommonQueryOptions() {
    /** An enum converter for `OrderOutput` . Should be used internally only.  */
    class OrderOutputConverter : EnumConverter<OrderOutput?>(OrderOutput::class.java, "Order output setting")

    @get:Option(
        name = "output",
        defaultValue = "label",
        documentationCategory = OptionDocumentationCategory.QUERY,
        effectTags = [OptionEffectTag.TERMINAL_OUTPUT],
        help = ("The format in which the query results should be printed. Allowed values for query are:"
                + " build, graph, streamed_jsonproto, label, label_kind, location, maxrank, minrank,"
                + " package, proto, streamed_proto, xml.")
    )
    abstract val outputFormat: String?

    @get:Option(
        name = "output:display_full_kind",
        defaultValue = "False",
        documentationCategory = OptionDocumentationCategory.QUERY,
        effectTags = [OptionEffectTag.TERMINAL_OUTPUT],
        help = ("When displaying rule kind, whether to display the short rule name, or the full name for"
                + " Starlark rules.")
    )
    abstract val displayFullKind: Boolean

    @get:Option(
        name = "null",
        defaultValue = "null",
        expansion = ["--line_terminator_null=true"],
        documentationCategory = OptionDocumentationCategory.QUERY,
        effectTags = [OptionEffectTag.TERMINAL_OUTPUT],
        help = "Whether each format is terminated with \\0 instead of newline."
    )
    abstract val isNull: Void?

    @get:Option(
        name = "order_results",
        defaultValue = "null",
        deprecationWarning = "Please use --order_output=auto or --order_output=no instead of this flag",
        expansion = ["--order_output=auto"],
        documentationCategory = OptionDocumentationCategory.QUERY,
        effectTags = [OptionEffectTag.TERMINAL_OUTPUT],
        help = ("Output the results in dependency-ordered (default) or unordered fashion. The "
                + "unordered output is faster but only supported when --output is not minrank, "
                + "maxrank, or graph.")
    )
    abstract val orderResults: Void?

    @get:Option(
        name = "noorder_results",
        defaultValue = "null",
        deprecationWarning = "Please use --order_output=no or --order_output=auto instead of this flag",
        expansion = ["--order_output=no"],
        documentationCategory = OptionDocumentationCategory.QUERY,
        effectTags = [OptionEffectTag.TERMINAL_OUTPUT],
        help = ("Output the results in dependency-ordered (default) or unordered fashion. The "
                + "unordered output is faster but only supported when --output is not minrank, "
                + "maxrank, or graph.")
    )
    abstract val noOrderResults: Void?

    /** Whether and how output should be ordered.  */
    enum class OrderOutput {
        /** Make no effort to order output besides that required by output formatter.  */
        NO,

        /** Output in dependency order when compatible with output formatter.  */
        DEPS,

        /** Same as full unless formatter is proto, minrank, maxrank, or graph, then deps.  */
        AUTO,

        /** Output in dependency order, breaking ties with alphabetical order when needed.  */
        FULL
    }

    @get:Option(
        name = "order_output",
        converter = OrderOutputConverter::class,
        defaultValue = "auto",
        documentationCategory = OptionDocumentationCategory.QUERY,
        effectTags = [OptionEffectTag.TERMINAL_OUTPUT],
        help = ("Output the results unordered (no), dependency-ordered (deps), or fully ordered (full)."
                + " The default is 'auto', meaning that results are output either dependency-ordered"
                + " or fully ordered, depending on the output formatter (dependency-ordered for"
                + " proto, minrank, maxrank, and graph, fully ordered for all others). When output"
                + " is fully ordered, nodes are printed in a fully deterministic (total) order."
                + " First, all nodes are sorted alphabetically. Then, each node in the list is used"
                + " as the start of a post-order depth-first search in which outgoing edges to"
                + " unvisited nodes are traversed in alphabetical order of the successor nodes."
                + " Finally, nodes are printed in the reverse of the order in which they were"
                + " visited.")
    )
    abstract var orderOutput: OrderOutput?

    @get:Option(
        name = "incompatible_lexicographical_output",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.NO_OP],
        metadataTags = [OptionMetadataTag.DEPRECATED],
        help = "No-op."
    )
    @get:Deprecated("")
    abstract val lexicographicalOutput: Boolean

    @get:Option(
        name = "graph:conditional_edges_limit",
        defaultValue = "4",
        documentationCategory = OptionDocumentationCategory.QUERY,
        effectTags = [OptionEffectTag.TERMINAL_OUTPUT],
        help = ("The maximum number of condition labels to show. -1 means no truncation and 0 means no "
                + "annotation. This option is only applicable to --output=graph.")
    )
    abstract val graphConditionalEdgesLimit: Int

    @get:Option(
        name = "xml:line_numbers",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.QUERY,
        effectTags = [OptionEffectTag.TERMINAL_OUTPUT],
        help = ("If true, XML output contains line numbers. Disabling this option may make diffs easier "
                + "to read.  This option is only applicable to --output=xml.")
    )
    abstract val xmlLineNumbers: Boolean

    @get:Option(
        name = "xml:default_values",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.QUERY,
        effectTags = [OptionEffectTag.TERMINAL_OUTPUT],
        help = ("If true, rule attributes whose value is not explicitly specified in the BUILD file are "
                + "printed; otherwise they are omitted.")
    )
    abstract val xmlShowDefaultValues: Boolean

    @get:Option(
        name = "strict_test_suite",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.QUERY,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS, OptionEffectTag.EAGERNESS_TO_EXIT],
        help = ("If true, the tests() expression gives an error if it encounters a test_suite containing "
                + "non-test targets.")
    )
    abstract val strictTestSuite: Boolean

    @get:Option(
        name = "experimental_graphless_query",
        defaultValue = "auto",
        documentationCategory = OptionDocumentationCategory.QUERY,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS, OptionEffectTag.EAGERNESS_TO_EXIT],
        help = ("If true, uses a Query implementation that does not make a copy of the graph. The new"
                + " implementation only supports --order_output=no, as well as only a subset of"
                + " output formatters.")
    )
    abstract var useGraphlessQuery: TriState?

    /** Return the current options as a set of QueryEnvironment settings.  */
    override fun toSettings(): MutableSet<QueryEnvironment.Setting?> {
        val settings = super.toSettings()
        if (this.strictTestSuite) {
            settings.add(QueryEnvironment.Setting.TESTS_EXPRESSION_STRICT)
        }
        return settings
    }
}
