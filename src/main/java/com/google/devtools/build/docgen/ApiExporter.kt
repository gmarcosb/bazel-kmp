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
package com.google.devtools.build.docgen

import com.google.devtools.build.docgen.builtin.BuiltinProtos.ApiContext

/** The main class for the Starlark documentation generator.  */
object ApiExporter {
    @Throws(BuildEncyclopediaDocException::class)
    private fun appendTypes(
        builtins: Builtins.Builder, docPage: StarlarkDocPage, nativeRules: MutableList<RuleDocumentation>
    ) {
        val type: Type.Builder = Type.newBuilder()
        type.setName(docPage.getName())
        type.setDoc(docPage.getDocumentation())
        // Sort members in case-sensitive name order.
        for (member in com.google.common.collect.ImmutableList.sortedCopyOf<MemberDoc?>(
            java.util.Comparator.comparing<MemberDoc?, String?>(java.util.function.Function { obj: MemberDoc? -> obj.getName() }),
            docPage.getMembers()
        )) {
            // Constructors are exported as global symbols.
            if (!member.isConstructor()) {
                val value: Value.Builder = collectMethodInfo(member)
                if (type.getName().equals("native")) {
                    // Methods from the native package are available as top level functions in BUILD files.
                    value.setApiContext(ApiContext.BUILD)
                    builtins.addGlobal(value)

                    value.setApiContext(ApiContext.BZL)
                    type.addField(value)
                } else {
                    value.setApiContext(ApiContext.ALL)
                    type.addField(value)
                }
            }
        }
        if (type.getName().equals("native")) {
            for (rule in nativeRules) {
                val field: Value.Builder = collectRuleInfo(rule)
                field.setApiContext(ApiContext.BZL)
                type.addField(field)
            }
        }
        builtins.addType(type)
    }

    private fun appendGlobals(
        builtins: Builtins.Builder,
        globals: MutableMap<String?, Any?>,
        globalToDoc: MutableMap<String?, MemberDoc?>,
        typeNameToConstructor: MutableMap<String?, MemberDoc?>,
        context: ApiContext?
    ) {
        for (entry in globals.entries) {
            val name: String = entry.key!!
            var obj: Any = entry.value!!
            if (obj is GuardedValue) {
                obj = obj.getObject()
            }

            var value: Value.Builder = Value.newBuilder()
            if (obj is StarlarkCallable) {
                val meth: MemberDoc? = globalToDoc.get(name)
                if (meth != null) {
                    value = collectMethodInfo(meth)
                } else {
                    value = valueFromCallable(obj as StarlarkCallable)
                }
            } else {
                val typeModule: StarlarkBuiltin? = StarlarkAnnotations.getStarlarkBuiltin(obj.javaClass)
                if (typeModule != null) {
                    val selfCallMethod: java.lang.reflect.Method? =
                        Starlark.getSelfCallMethod(StarlarkSemantics.DEFAULT, obj.javaClass)
                    if (selfCallMethod != null) {
                        // selfCallMethod may be from a subclass of the annotated method.
                        val annotation: StarlarkMethod? = StarlarkAnnotations.getStarlarkMethod(selfCallMethod)
                        value = valueFromAnnotation(annotation)
                        // For constructors, we can also set the return type.
                        val constructor: MemberDoc? = typeNameToConstructor.get(entry.key)
                        if (constructor != null && value.hasCallable()) {
                            value.getCallableBuilder().setReturnType(constructor.getReturnType())
                        }
                    } else {
                        value.setName(name)
                        // TODO(b/255647089): We should use the type module's type here, since it will more
                        // accurately represent Providers, but has some issues with builtins. For now, just
                        // special case None which has type NoneType.
                        if (name != "None") {
                            value.setType(name)
                            value.setDoc(typeModule.doc)
                        } else {
                            value.setType("NoneType")
                        }
                    }
                } else if (name != "_builtins_dummy") { // Ignore the test only dummy global.
                    // Special case bool since we can't infer the type module for it.
                    if (name == "True" || name == "False") {
                        value.setType("bool")
                    }
                    value.setName(name)
                }
            }
            value.setApiContext(context)
            builtins.addGlobal(value)
        }
    }

    // Native rules are available as top level functions in BUILD files.
    @Throws(BuildEncyclopediaDocException::class)
    private fun appendNativeRules(
        builtins: Builtins.Builder, nativeRules: MutableList<RuleDocumentation>
    ) {
        for (rule in nativeRules) {
            val global: Value.Builder = collectRuleInfo(rule)
            global.setApiContext(ApiContext.BUILD)
            builtins.addGlobal(global)
        }
    }

    private fun valueFromCallable(x: StarlarkCallable): Value.Builder {
        // Starlark def statement?
        if (x is StarlarkFunction) {
            val sig = Signature()
            sig.name = x.getName()
            sig.doc = x.getDocumentation()
            sig.parameterNames = x.getParameterNames()
            sig.hasVarargs = x.hasVarargs()
            sig.hasKwargs = x.hasKwargs()
            sig.getDefaultValue =
                java.util.function.Function { i: Int? ->
                    val v: Any? = x.getDefaultValue(i)
                    if (v == null) null else Starlark.repr(v, StarlarkSemantics.DEFAULT)
                }
            return signatureToValue(sig)
        }

        // annotated Java method?
        if (x is BuiltinFunction) {
            return valueFromAnnotation(x.getAnnotation())
        }

        // application-defined callable?  Treat as def f(**kwargs).
        val sig = Signature()
        sig.name = x.getName()
        sig.parameterNames = com.google.common.collect.ImmutableList.of<String?>("kwargs")
        sig.hasKwargs = true
        return signatureToValue(sig)
    }

    private fun valueFromAnnotation(annot: StarlarkMethod): Value.Builder {
        return signatureToValue(getSignature(annot))
    }

    private fun signatureToValue(sig: Signature): Value.Builder {
        val value: Value.Builder = Value.newBuilder()
        value.setName(sig.name)
        value.setDoc(sig.doc)

        var nparams = sig.parameterNames!!.size
        val kwargsIndex = if (sig.hasKwargs) --nparams else -1
        val varargsIndex = if (sig.hasVarargs) --nparams else -1

        // Inv: nparams is number of regular parameters.
        val callable: Callable.Builder = Callable.newBuilder()
        for (i in sig.parameterNames.indices) {
            val name = sig.parameterNames!!.get(i)
            val param: Param.Builder = Param.newBuilder()
            if (i == varargsIndex) {
                // *args
                param.setName("*" + name) // * seems redundant
                param.setIsStarArg(true)
            } else if (i == kwargsIndex) {
                // **kwargs
                param.setName("**" + name) // ** seems redundant
                param.setIsStarStarArg(true)
            } else {
                // regular parameter
                param.setName(name)
                val v: String? = sig.getDefaultValue.apply(i)
                if (v != null) {
                    param.setDefaultValue(v)
                } else {
                    param.setIsMandatory(true) // bool seems redundant
                }
            }
            callable.addParam(param)
        }
        value.setCallable(callable)
        return value
    }

    private fun collectMethodInfo(meth: MemberDoc): Value.Builder {
        val field: Value.Builder = Value.newBuilder()
        field.setName(meth.getShortName())
        field.setDoc(meth.getDocumentation())
        if (meth.isCallable()) {
            val callable: Callable.Builder = Callable.newBuilder()
            for (par in meth.getParams()) {
                val param: Param.Builder = newParam(par.getName(), par.getDefaultValue().isEmpty())
                param.setType(par.getType())
                param.setDoc(par.getDocumentation())
                param.setDefaultValue(par.getDefaultValue())
                if (par.getKind() == com.google.devtools.build.docgen.starlark.ParamDoc.Kind.VARARGS) {
                    param.setName("*" + par.getName())
                    param.setIsStarArg(true)
                } else if (par.getKind() == com.google.devtools.build.docgen.starlark.ParamDoc.Kind.KWARGS) {
                    param.setName("**" + par.getName())
                    param.setIsStarStarArg(true)
                }
                callable.addParam(param)
            }
            callable.setReturnType(meth.getReturnType())
            field.setCallable(callable)
        } else {
            field.setType(meth.getReturnType())
        }
        return field
    }

    private fun newParam(name: String?, isMandatory: Boolean?): Param.Builder {
        val param: Param.Builder = Param.newBuilder()
        param.setName(name)
        param.setIsMandatory(isMandatory)
        return param
    }

    @Throws(BuildEncyclopediaDocException::class)
    private fun collectRuleInfo(rule: RuleDocumentation): Value.Builder {
        val value: Value.Builder = Value.newBuilder()
        value.setName(rule.getRuleName())
        value.setDoc(rule.getHtmlDocumentation())
        val callable: Callable.Builder = Callable.newBuilder()
        // All native rules have attribute "name". It is not included in the attributes list and needs
        // to be added separately.
        callable.addParam(newParam("name", true))
        for (attr in rule.getAttributes()) {
            callable.addParam(newParam(attr.getAttributeName(), attr.isMandatory()))
        }
        value.setCallable(callable)
        return value
    }

    @Throws(IOException::class)
    private fun writeBuiltins(filename: String?, builtins: Builtins.Builder) {
        BufferedOutputStream(FileOutputStream(filename)).use { out ->
            val build: Builtins = builtins.build()
            build.writeTo(out)
        }
    }

    private fun printUsage(parser: com.google.devtools.common.options.OptionsParser) {
        java.lang.System.err.println(
            ("Usage: api_exporter_bin -m link_map_path -p rule_class_provider\n"
                    + "    [-r input_root] (-i input_dir)+ (--be_stardoc_proto binproto)+\n"
                    + "    -f outputFile [-b denylist] [-h]\n\n"
                    + "Exports all Starlark builtins to a file including the embedded native rules.\n"
                    + "The link map path (-m), rule class provider (-p), output file (-f), and at least\n"
                    + " one input_dir (-i) or binproto (--be_stardoc_proto) must be specified.\n")
        )
        java.lang.System.err.println(
            parser.describeOptionsWithDeprecatedCategories(
                mutableMapOf<String?, String?>(), HelpVerbosity.LONG
            )
        )
    }

    @kotlin.jvm.JvmStatic
    fun main(args: Array<String>) {
        val parser: com.google.devtools.common.options.OptionsParser =
            com.google.devtools.common.options.OptionsParser.builder()
                .optionsClasses(BuildEncyclopediaOptions::class.java).build()
        parser.parseAndExitUponError(args)
        val options: BuildEncyclopediaOptions? =
            parser.getOptions<BuildEncyclopediaOptions?>(BuildEncyclopediaOptions::class.java)

        if (options.getHelp()) {
            printUsage(parser)
            java.lang.Runtime.getRuntime().exit(0)
        }

        if (options.getLinkMapPath().isEmpty()
            || (options.getInputJavaDirs().isEmpty()
                    && options.getBuildEncyclopediaStardocProtos().isEmpty())
            || options.getProvider().isEmpty()
            || options.getOutputFile().isEmpty()
        ) {
            printUsage(parser)
            java.lang.Runtime.getRuntime().exit(1)
        }

        try {
            val linkMap: DocLinkMap = DocLinkMap.Companion.createFromFile(options.getLinkMapPath())
            val ruleExpander: RuleLinkExpander = RuleLinkExpander(true, linkMap)
            val urlMapper: SourceUrlMapper = SourceUrlMapper(linkMap, options.getInputRoot())
            val symbols: SymbolFamilies =
                SymbolFamilies(
                    StarlarkDocExpander(ruleExpander),
                    urlMapper,
                    options.getProvider(),
                    options.getInputJavaDirs(),
                    options.getBuildEncyclopediaStardocProtos(),
                    options.getDenylist(),
                    options.getApiStardocProtos()
                )
            val allDocPages: com.google.common.collect.ImmutableMap<StarlarkDocumentationProcessor.Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?> =
                symbols.getAllDocPages()
            val builtins: Builtins.Builder = Builtins.newBuilder()

            val globalPages: com.google.common.collect.ImmutableList<StarlarkDocPage>? =
                allDocPages.get(com.google.devtools.build.docgen.StarlarkDocumentationProcessor.Category.GLOBAL_FUNCTION)
            val globalToDoc: MutableMap<String?, MemberDoc?> = HashMap<String?, MemberDoc?>()
            for (globalPage in globalPages) {
                for (meth in globalPage.getMembers()) {
                    globalToDoc.put(meth.getShortName(), meth)
                }
            }

            val typesIterator: MutableIterator<StarlarkDocPage> =
                allDocPages.entries.stream()
                    .filter { e: MutableMap.MutableEntry<StarlarkDocumentationProcessor.Category?, com.google.common.collect.ImmutableList<StarlarkDocPage?>?>? -> e!!.key != com.google.devtools.build.docgen.StarlarkDocumentationProcessor.Category.GLOBAL_FUNCTION }
                    .flatMap<StarlarkDocPage?> { e: MutableMap.MutableEntry<StarlarkDocumentationProcessor.Category?, com.google.common.collect.ImmutableList<StarlarkDocPage?>?>? -> e!!.value.stream() }
                    .iterator()
            val typeNameToConstructor: MutableMap<String?, MemberDoc?> = HashMap<String?, MemberDoc?>()
            while (typesIterator.hasNext()) {
                val typeDocPage: StarlarkDocPage = typesIterator.next()
                appendTypes(builtins, typeDocPage, symbols.getNativeRules())
                typeNameToConstructor.put(typeDocPage.getName(), typeDocPage.getConstructor())
            }
            appendGlobals(
                builtins, symbols.getGlobals(), globalToDoc, typeNameToConstructor, ApiContext.ALL
            )
            appendGlobals(
                builtins, symbols.getBzlGlobals(), globalToDoc, typeNameToConstructor, ApiContext.BZL
            )
            appendNativeRules(builtins, symbols.getNativeRules())
            writeBuiltins(options.getOutputFile(), builtins)
        } catch (e: Throwable) {
            java.lang.System.err.println("ERROR: " + e.message)
            e.printStackTrace()
        }
    }

    // Extracts signature and parameter default value expressions from a StarlarkMethod annotation.
    private fun getSignature(annot: StarlarkMethod): Signature {
        // Build-time annotation processing ensures mandatory parameters do not follow optional ones.
        var hasStar = false
        var star: String? = null
        var starStar: String? = null
        val params: java.util.ArrayList<String?> = java.util.ArrayList<String?>()
        val defaults: java.util.ArrayList<String?> = java.util.ArrayList<String?>()

        for (param in annot.parameters) {
            // Ignore undocumented parameters
            if (!param.documented) {
                continue
            }
            // Implicit * or *args parameter separates transition from positional to named.
            // f (..., *, ... )  or  f(..., *args, ...)
            // TODO(adonovan): this logic looks fishy. Clean it up.
            if (param.named && !param.positional && !hasStar) {
                hasStar = true
                if (!annot.extraPositionals.name.isEmpty()) {
                    star = annot.extraPositionals.name
                }
            }
            params.add(param.name)
            defaults.add(if (param.defaultValue.isEmpty()) null else param.defaultValue)
        }

        // f(..., *args, ...)
        if (!annot.extraPositionals.name.isEmpty() && !hasStar) {
            star = annot.extraPositionals.name
        }
        if (star != null) {
            params.add(star)
        }

        // f(..., **kwargs)
        if (!annot.extraKeywords.name.isEmpty()) {
            starStar = annot.extraKeywords.name
            params.add(starStar)
        }

        val sig = Signature()
        sig.name = annot.name
        sig.doc = annot.doc
        sig.parameterNames = params
        sig.hasVarargs = star != null
        sig.hasKwargs = starStar != null
        sig.getDefaultValue = java.util.function.Function { index: Int? -> defaults.get(index) }
        return sig
    }

    private class Signature {
        var name: String? = null
        var parameterNames: MutableList<String?>? = null
        var hasVarargs: Boolean = false
        var hasKwargs: Boolean = false
        var doc: String? = null

        // Returns the string form of the ith default value, using the
        // index, ordering, and null Conventions of StarlarkFunction.getDefaultValue.
        var getDefaultValue: java.util.function.Function<Int?, String?> =
            java.util.function.Function { i: Int? -> null }
    }
}
