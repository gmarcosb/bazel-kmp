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

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMultimap
import com.google.common.collect.Iterables
import com.google.common.hash.HashFunction
import com.google.devtools.build.lib.cmdline.Label
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.OutputStream
import java.lang.String
import javax.xml.transform.Transformer
import kotlin.Any
import kotlin.Boolean
import kotlin.IllegalArgumentException
import kotlin.IllegalStateException
import kotlin.Int
import kotlin.toString

/** An output formatter that prints the result as XML.  */
internal class XmlOutputFormatter : AbstractUnorderedFormatter() {
    private var aspectResolver: AspectResolver? = null
    private var dependencyFilter: DependencyFilter? = null
    private var packageGroupIncludesDoubleSlash = false
    private var relativeLocations = false
    private var queryOptions: QueryOptions? = null

    override fun getName(): String {
        return "xml"
    }

    override fun createStreamCallback(
        out: OutputStream?, options: QueryOptions?, env: QueryEnvironment<*>
    ): ThreadSafeOutputFormatterCallback<Target?> {
        return SynchronizedDelegatingOutputFormatterCallback<Target?>(
            createPostFactoStreamCallback(out, options, env.getLabelPrinter())
        )
    }

    override fun setOptions(
        options: CommonQueryOptions, aspectResolver: AspectResolver, hashFunction: HashFunction?
    ) {
        super.setOptions(options, aspectResolver, hashFunction)
        this.aspectResolver = aspectResolver
        this.dependencyFilter = FormatUtils.getDependencyFilter(options)
        this.packageGroupIncludesDoubleSlash = options.getIncompatiblePackageGroupIncludesDoubleSlash()
        this.relativeLocations = options.getRelativeLocations()

        Preconditions.checkArgument(options is QueryOptions)
        this.queryOptions = options as QueryOptions
    }

    override fun createPostFactoStreamCallback(
        out: OutputStream?, options: QueryOptions?, labelPrinter: LabelPrinter
    ): OutputFormatterCallback<Target?> {
        return object : OutputFormatterCallback<Target?>() {
            private var doc: Document? = null
            private var queryElem: Element? = null

            override fun start() {
                try {
                    val factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance()
                    doc = factory.newDocumentBuilder().newDocument()
                } catch (e: ParserConfigurationException) {
                    // This shouldn't be possible: all the configuration is hard-coded.
                    throw IllegalStateException("XML output failed", e)
                }
                doc!!.setXmlVersion("1.1")
                queryElem = doc!!.createElement("query")
                queryElem!!.setAttribute("version", "2")
                doc!!.appendChild(queryElem)
            }

            @Throws(InterruptedException::class)
            override fun processOutput(partialResult: Iterable<Target>) {
                for (target in partialResult) {
                    queryElem!!.appendChild(createTargetElement(doc!!, target, labelPrinter))
                }
            }

            override fun close(failFast: Boolean) {
                if (!failFast) {
                    try {
                        val transformer: Transformer = TransformerFactory.newInstance().newTransformer()
                        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
                        transformer.transform(DOMSource(doc), StreamResult(out))
                    } catch (e: TransformerFactoryConfigurationError) {
                        // This shouldn't be possible: all the configuration is hard-coded.
                        throw IllegalStateException("XML output failed", e)
                    } catch (e: TransformerException) {
                        throw IllegalStateException("XML output failed", e)
                    }
                }
            }
        }
    }

    /**
     * Creates and returns a new DOM tree for the specified build target.
     * 
     * 
     * XML structure: - element tag is &lt;source-file>, &lt;generated-file> or &lt;rule
     * class="cc_library">, following the terminology of [Target.getTargetKind]. - 'name'
     * attribute is target's label. - 'location' attribute is consistent with output of --output
     * location. - rule attributes are represented in the DOM structure.
     */
    @Throws(InterruptedException::class)
    private fun createTargetElement(doc: Document, target: Target, labelPrinter: LabelPrinter): Element {
        val elem: Element
        if (target is Rule) {
            elem = doc.createElement("rule")
            elem.setAttribute("class", Companion.getRuleClass(queryOptions!!, target))
            for (attr in target.getAttributes()) {
                if (target.isAttributeValueExplicitlySpecified(attr)
                    || queryOptions!!.getXmlShowDefaultValues()
                ) {
                    // TODO(b/162524370): mayTreatMultipleAsNone should be true for types that drop multiple
                    //  values.
                    val values =
                        PossibleAttributeValues.forRuleAndAttribute(
                            target, attr,  /* mayTreatMultipleAsNone= */false
                        )
                    val attrElem = createValueElement(doc, attr.getType(), values!!, labelPrinter)
                    attrElem.setAttribute("name", attr.name)
                    elem.appendChild(attrElem)
                }
            }

            // Include explicit elements for all direct inputs and outputs of a rule; this goes beyond
            // what is available from the attributes above, since it may also (depending on options)
            // include implicit outputs, exec-configuration outputs, and default values.
            for (label in target.getSortedLabels(dependencyFilter)) {
                val inputElem = doc.createElement("rule-input")
                inputElem.setAttribute("name", labelPrinter.toString(label))
                elem.appendChild(inputElem)
            }

            aspectResolver.computeAspectDependencies(target, dependencyFilter).values.stream()
                .flatMap<Label?> { m: ImmutableMultimap<Attribute?, Label?>? -> m!!.values().stream() }
                .distinct()
                .forEach { label: Label? ->
                    val inputElem = doc.createElement("rule-input")
                    inputElem.setAttribute("name", labelPrinter.toString(label))
                    elem.appendChild(inputElem)
                }

            for (outputFile in target.getOutputFiles()) {
                val outputElem = doc.createElement("rule-output")
                outputElem.setAttribute("name", labelPrinter.toString(outputFile.getLabel()))
                elem.appendChild(outputElem)
            }
            for (feature in target.getPackageDeclarations().getPackageArgs().features().toStringList()) {
                val outputElem = doc.createElement("rule-default-setting")
                outputElem.setAttribute("name", feature)
                elem.appendChild(outputElem)
            }
        } else if (target is PackageGroup) {
            elem = doc.createElement("package-group")
            elem.setAttribute("name", target.getName())
            val includes =
                createValueElement(doc, BuildType.LABEL_LIST, target.getIncludes(), labelPrinter)
            includes.setAttribute("name", "includes")
            elem.appendChild(includes)
            val packages =
                createValueElement(
                    doc,
                    Types.STRING_LIST,
                    target.getContainedPackages(packageGroupIncludesDoubleSlash),
                    labelPrinter
                )
            packages.setAttribute("name", "packages")
            elem.appendChild(packages)
        } else if (target is OutputFile) {
            elem = doc.createElement("generated-file")
            elem.setAttribute(
                "generating-rule", labelPrinter.toString(target.getGeneratingRule().getLabel())
            )
        } else if (target is InputFile) {
            elem = doc.createElement("source-file")
            if (target.getName().equals("BUILD")) {
                addStarlarkFilesToElement(doc, elem, target, labelPrinter)
                addFeaturesToElement(doc, elem, target)
                elem.setAttribute(
                    "package_contains_errors", String.valueOf(target.getPackageoid().containsErrors())
                )
            }

            // TODO(bazel-team): We're being inconsistent about whether we include the package's
            // default_visibility in the target. For files we do, but for rules we don't.
            addPackageGroupsToElement(doc, elem, target, labelPrinter)
        } else if (target is EnvironmentGroup) {
            elem = doc.createElement("environment-group")
            elem.setAttribute("name", target.getName())
            val environments =
                createValueElement(doc, BuildType.LABEL_LIST, target.getEnvironments(), labelPrinter)
            environments.setAttribute("name", "environments")
            elem.appendChild(environments)
            val defaults =
                createValueElement(doc, BuildType.LABEL_LIST, target.getDefaults(), labelPrinter)
            defaults.setAttribute("name", "defaults")
            elem.appendChild(defaults)
        } else if (target is FakeLoadTarget) {
            elem = doc.createElement("source-file")
        } else {
            throw IllegalArgumentException(target.toString())
        }

        elem.setAttribute("name", labelPrinter.toString(target.getLabel()))
        var location = FormatUtils.getLocation(target, relativeLocations)
        if (!queryOptions!!.getXmlLineNumbers()) {
            val firstColon: Int = location.indexOf(':')
            if (firstColon != -1) {
                location = location.substring(0, firstColon)
            }
        }

        elem.setAttribute("location", location)
        return elem
    }

    @Throws(InterruptedException::class)
    private fun addStarlarkFilesToElement(
        doc: Document, parent: Element, buildFile: InputFile?, labelPrinter: LabelPrinter
    ) {
        val dependencies: Iterable<Label?> = aspectResolver.computeBuildFileDependencies(buildFile)

        for (starlarkFileDep in dependencies) {
            val elem = doc.createElement("load")
            elem.setAttribute("name", labelPrinter.toString(starlarkFileDep))
            parent.appendChild(elem)
        }
    }

    companion object {
        // This is distinct from AbstractUnorderedFormatter.getKind because it should **not** have the
        // " rule" suffix expected from --output=label_kind and --output=location.
        private fun getRuleClass(queryOptions: QueryOptions, rule: Rule): kotlin.String {
            if (queryOptions.getDisplayFullKind()) {
                return rule.getRuleClassObject().getRuleClassId().key()
            }
            return rule.getRuleClass()
        }

        private fun addPackageGroupsToElement(
            doc: Document, parent: Element, target: Target, labelPrinter: LabelPrinter
        ) {
            for (visibilityDependency in target.getVisibilityDependencyLabels()) {
                val elem = doc.createElement("package-group")
                elem.setAttribute("name", labelPrinter.toString(visibilityDependency))
                parent.appendChild(elem)
            }

            for (visibilityDeclaration in target.getVisibilityDeclaredLabels()) {
                val elem = doc.createElement("visibility-label")
                elem.setAttribute("name", labelPrinter.toString(visibilityDeclaration))
                parent.appendChild(elem)
            }
        }

        private fun addFeaturesToElement(doc: Document, parent: Element, inputFile: InputFile) {
            for (feature in inputFile.getPackageDeclarations().getPackageArgs().features().toStringList()) {
                val elem = doc.createElement("feature")
                elem.setAttribute("name", feature)
                parent.appendChild(elem)
            }
        }

        /**
         * Creates and returns a new DOM tree for the specified attribute values. For non-configurable
         * attributes, this is a single value. For configurable attributes, this contains one value for
         * each configuration. (Only toplevel values are named attributes; list elements are unnamed.)
         * 
         * 
         * In the case of configurable attributes, multi-value attributes (e.g. lists) merge all
         * configured lists into an aggregate flattened list. Single-value attributes simply refrain to
         * set a value and annotate the DOM element as configurable.
         * 
         * 
         * (The ungainly qualified class name is required to avoid ambiguity with
         * OutputFormatter.OutputType.)
         */
        private fun createValueElement(
            doc: Document, type: Type<*>, values: Iterable<Any>, labelPrinter: LabelPrinter
        ): Element {
            val elem: Element
            val hasMultipleValues = Iterables.size(values) > 1
            val elemType: Type<*>? = type.getListElementType()
            if (elemType != null) { // it's a list (includes "distribs")
                elem = doc.createElement("list")
                for (value in values) {
                    for (elemValue in (value as kotlin.collections.MutableCollection<*>?)!!) {
                        elem.appendChild(Companion.createValueElement(doc, elemType, elemValue!!, labelPrinter))
                    }
                }
            } else if (type is Type.DictType<*, *>) {
                val visitedValues: MutableSet<Any?> = HashSet<Any?>()
                elem = doc.createElement("dict")
                for (value in values) {
                    for (entry in (value as MutableMap<*, *>).entries) {
                        if (visitedValues.add(entry.key)) {
                            val pairElem = doc.createElement("pair")
                            elem.appendChild(pairElem)
                            pairElem.appendChild(
                                createValueElement(doc, type.keyType, entry.key!!, labelPrinter)
                            )
                            pairElem.appendChild(
                                createValueElement(doc, type.valueType, entry.value!!, labelPrinter)
                            )
                        }
                    }
                }
            } else if (type === BuildType.LICENSE) {
                elem = createSingleValueElement(doc, "license", hasMultipleValues)
                if (!hasMultipleValues) {
                    val license: License? = Iterables.getOnlyElement<Any?>(values) as License?

                    val exceptions =
                        createValueElement(doc, BuildType.LABEL_LIST, license.getExceptions(), labelPrinter)
                    exceptions.setAttribute("name", "exceptions")
                    elem.appendChild(exceptions)

                    val licenseTypes =
                        createValueElement(doc, Types.STRING_LIST, license.getLicenseTypes(), labelPrinter)
                    licenseTypes.setAttribute("name", "license-types")
                    elem.appendChild(licenseTypes)
                }
            } else { // INTEGER STRING LABEL OUTPUT
                elem = createSingleValueElement(doc, type.toString(), hasMultipleValues)
                if (!hasMultipleValues && !Iterables.isEmpty(values)) {
                    val value = Iterables.getOnlyElement<Any?>(values)
                    // Values such as those of attribute "linkstamp" may be null.
                    if (value != null) {
                        try {
                            if (value is Label) {
                                elem.setAttribute("value", labelPrinter.toString(value))
                            } else {
                                elem.setAttribute("value", value.toString())
                            }
                        } catch (e: DOMException) {
                            elem.setAttribute("value", "[[[ERROR: could not be encoded as XML]]]")
                        }
                    }
                }
            }
            return elem
        }

        private fun createValueElement(
            doc: Document, type: Type<*>, value: Any, labelPrinter: LabelPrinter
        ): Element {
            return Companion.createValueElement(doc, type, ImmutableList.of<Any?>(value), labelPrinter)
        }

        /**
         * Creates the given DOM element, adding `configurable="yes"` if it represents a
         * configurable single-value attribute (configurable list attributes simply have their lists
         * merged into an aggregate flat list).
         */
        private fun createSingleValueElement(doc: Document, name: kotlin.String?, configurable: Boolean): Element {
            val elem = doc.createElement(name)
            if (configurable) {
                elem.setAttribute("configurable", "yes")
            }
            return elem
        }
    }
}
