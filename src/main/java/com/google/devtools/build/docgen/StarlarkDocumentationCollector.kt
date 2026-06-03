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
package com.google.devtools.build.docgen

import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.AspectInfo

/**
 * A helper class that collects Starlark module documentation.
 * 
 * 
 * The documentation comes from [StarlarkBuiltin] annotations in Java code or from Stardoc
 * protos produced (via `starlark_doc_extract` from specially-structured .bzl files serving as
 * entry points for Starlark APIs. Such an entry point .bzl file is expected to contain only the
 * following documentable entities (whose names must be unique across all .bzl files being
 * processed):
 * 
 * 
 *  * Providers, defined at global scope. Field docstrings can be prefixed with a type expression
 * enclosed in parentheses, optionally followed by a colon, for example `"(list[string])       Some free text about field foo"`
 *  * Structs, defined at global scope, documented using `#:`-prefixed doc comments, and
 * containing only function members or aliases of providers. The returns and parameter
 * sections of the function members' docstrings can be prefixed with a type expression
 * enclosed in parentheses, optionally followed by a colon, for example `"(string |       None): Some free text about parameter blah"`
 * 
 * 
 * 
 * Notably, .bzl files from which Build Encyclopedia content is extracted have a different,
 * incompatible structure.
 */
internal object StarlarkDocumentationCollector {
    private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    private var all: com.google.common.collect.ImmutableMap<StarlarkDocumentationProcessor.Category?, com.google.common.collect.ImmutableList<StarlarkDocPage?>?>? =
        null

    /** Applies [.collectDocPages] to all Bazel and Starlark classes.  */
    @kotlin.jvm.Synchronized
    @Throws(ClassPathException::class, IOException::class)
    fun getAllDocPages(
        expander: StarlarkDocExpander, apiStardocProtos: com.google.common.collect.ImmutableList<String?>
    ): com.google.common.collect.ImmutableMap<StarlarkDocumentationProcessor.Category?, com.google.common.collect.ImmutableList<StarlarkDocPage?>?> {
        if (all == null) {
            val parsedApiStardocProtos: com.google.common.collect.ImmutableList.Builder<ModuleInfo?> =
                com.google.common.collect.ImmutableList.builder<ModuleInfo?>()
            for (filename in apiStardocProtos) {
                parsedApiStardocProtos.add(
                    ModuleInfo.parseFrom(
                        FileInputStream(filename), ExtensionRegistry.getEmptyRegistry()
                    )
                )
            }
            all =
                collectDocPages(
                    expander,
                    com.google.common.collect.Iterables.concat( /*Bazel*/
                        Classpath.findClasses("com/google/devtools/build"),  /*Starlark*/
                        Classpath.findClasses("net/starlark/java")
                    ),
                    parsedApiStardocProtos.build()
                )
        }
        return all
    }

    /**
     * Collects the documentation for all Starlark modules comprised of the given classes and returns
     * a map from the name of each Starlark module to its documentation.
     */
    fun collectDocPages(
        expander: StarlarkDocExpander,
        classes: Iterable<java.lang.Class<*>>,
        apiStardocProtos: com.google.common.collect.ImmutableList<ModuleInfo>
    ): com.google.common.collect.ImmutableMap<StarlarkDocumentationProcessor.Category?, com.google.common.collect.ImmutableList<StarlarkDocPage?>?> {
        val pages: MutableMap<StarlarkDocumentationProcessor.Category?, MutableMap<String?, StarlarkDocPage>> =
            java.util.EnumMap<StarlarkDocumentationProcessor.Category?, MutableMap<String?, StarlarkDocPage>>(
                StarlarkDocumentationProcessor.Category::class.java
            )
        for (category in StarlarkDocumentationProcessor.Category.entries) {
            pages.put(category, HashMap<String?, StarlarkDocPage?>())
        }

        // 1. Add all classes/interfaces annotated with @StarlarkBuiltin with documented = true.
        for (candidateClass in classes) {
            collectStarlarkBuiltin(candidateClass, pages, expander)
        }

        // 2. Add all object methods and global functions.
        //
        //    Also, explicitly process the Starlark interpreter's MethodLibrary
        //    class, which defines None, len, range, etc.
        //    TODO(adonovan): do this without peeking into the implementation,
        //    e.g. by looking at Starlark.UNIVERSE, something like this:
        //
        //    for (Map<String, Object> e : Starlark.UNIVERSE.entrySet()) {
        //      if (e.getValue() instanceof BuiltinFunction) {
        //        BuiltinFunction fn = (BuiltinFunction) e.getValue();
        //        topLevelModuleDoc.addMethod(
        //          new StarlarkJavaMethodDoc("", fn.getJavaMethod(), fn.getAnnotation(), expander));
        //      }
        //    }
        //
        //    Note that BuiltinFunction doesn't actually have getJavaMethod.
        //
        for (candidateClass in classes) {
            collectBuiltinMethods(candidateClass, pages, expander)
            collectGlobalMethods(candidateClass, pages, expander)
        }

        // 3. Add all constructors.
        for (candidateClass in classes) {
            collectConstructorMethods(candidateClass, pages, expander)
        }

        // 4. Add docs from .bzl files.
        val bzlStructPages: HashMap<String?, ModuleInfo?> = HashMap<String?, ModuleInfo?>()
        for (moduleInfo in apiStardocProtos) {
            collectFromStardocProto(moduleInfo, pages, bzlStructPages, expander)
        }

        // 5. Define a parser for type expressions in .bzl-defined doc strings.
        // This parser needs a map from type identifiers (e.g. core Starlark types, BUILD language
        // types, and providers) to their categories, so that it can generate link URLs for them.
        val typeIdentifierToCategory: com.google.common.collect.ImmutableMap.Builder<String?, StarlarkDocumentationProcessor.Category?> =
            com.google.common.collect.ImmutableMap.builder<String?, StarlarkDocumentationProcessor.Category?>()
        for (pagesEntry in pages.entries) {
            if (pagesEntry.key == com.google.devtools.build.docgen.StarlarkDocumentationProcessor.Category.CONFIGURATION_FRAGMENT) {
                // Assume nothing returns a configuration fragment; some of them clash with names of
                // built-in modules.
                continue
            }
            for (page in pagesEntry.value.values) {
                typeIdentifierToCategory.put(page.getName(), pagesEntry.key)
            }
        }
        expander.setTypeParser(TypeParser(typeIdentifierToCategory.buildOrThrow()))

        return com.google.common.collect.ImmutableMap.copyOf<StarlarkDocumentationProcessor.Category?, com.google.common.collect.ImmutableList<StarlarkDocPage?>?>(
            com.google.common.collect.Maps.transformValues<StarlarkDocumentationProcessor.Category?, MutableMap<String?, StarlarkDocPage?>?, com.google.common.collect.ImmutableList<StarlarkDocPage?>?>(
                pages,
                com.google.common.base.Function { pagesInCategory: MutableMap<String?, StarlarkDocPage?>? ->
                    com.google.common.collect.ImmutableList.sortedCopyOf<StarlarkDocPage?>(
                        java.util.Comparator.comparing<StarlarkDocPage?, String?>(
                            java.util.function.Function { obj: StarlarkDocPage? -> obj.getTitle() },
                            Collator.getInstance(Locale.US)
                        ),
                        pagesInCategory!!.values
                    )
                })
        )
    }

    /**
     * Adds a single [StarlarkDocPage] entry to `pages` representing the given `builtinClass`, if it is a documented builtin.
     */
    private fun collectStarlarkBuiltin(
        builtinClass: java.lang.Class<*>,
        pages: MutableMap<StarlarkDocumentationProcessor.Category?, MutableMap<String?, StarlarkDocPage>>,
        expander: StarlarkDocExpander?
    ) {
        val starlarkBuiltin: StarlarkBuiltin? =
            builtinClass.getAnnotation<StarlarkBuiltin?>(StarlarkBuiltin::class.java)
        if (starlarkBuiltin == null || !starlarkBuiltin.documented) {
            return
        }

        val pagesInCategory: MutableMap<String?, StarlarkDocPage?> = pages.get(
            com.google.devtools.build.docgen.StarlarkDocumentationProcessor.Category.Companion.of(starlarkBuiltin)
        )
        val existingPage: StarlarkDocPage? = pagesInCategory.get(starlarkBuiltin.name)
        if (existingPage == null) {
            pagesInCategory.put(
                starlarkBuiltin.name,
                AnnotStarlarkBuiltinDoc(starlarkBuiltin, builtinClass, expander)
            )
            return
        }

        // Handle a strange corner-case: If builtinClass has a subclass which is also
        // annotated with @StarlarkBuiltin with the same name, and also has the same
        // docstring, then the subclass takes precedence.
        // (This is useful if one class is the "common" one with stable methods, and its subclass is
        // an experimental class that also supports all stable methods.)
        com.google.common.base.Preconditions.checkState(
            existingPage is AnnotStarlarkBuiltinDoc,
            "the same name %s is assigned to both a global method environment and a builtin type",
            starlarkBuiltin.name
        )
        val clazz: java.lang.Class<*> = (existingPage as AnnotStarlarkBuiltinDoc).getClassObject()
        validateCompatibleBuiltins(clazz, builtinClass)

        if (clazz.isAssignableFrom(builtinClass)) {
            // The new builtin is a subclass of the old builtin, so use the subclass.
            pagesInCategory.put(
                starlarkBuiltin.name,
                AnnotStarlarkBuiltinDoc(starlarkBuiltin, builtinClass, expander)
            )
        }
    }

    /** Validate that it is acceptable that the given builtin classes with the same name co-exist.  */
    private fun validateCompatibleBuiltins(one: java.lang.Class<*>, two: java.lang.Class<*>) {
        val builtinOne: StarlarkBuiltin = one.getAnnotation<StarlarkBuiltin>(StarlarkBuiltin::class.java)
        val builtinTwo: StarlarkBuiltin = two.getAnnotation<StarlarkBuiltin>(StarlarkBuiltin::class.java)
        if (one.isAssignableFrom(two) || two.isAssignableFrom(one)) {
            check(builtinOne.doc == builtinTwo.doc) {
                String.format(
                    "%s and %s are related builtins but have mismatching documentation for '%s'",
                    one, two, builtinOne.name
                )
            }
        } else {
            throw java.lang.IllegalStateException(
                String.format(
                    "%s and %s are unrelated builtins with documentation for '%s'",
                    one, two, builtinOne.name
                )
            )
        }
    }

    private fun collectBuiltinMethods(
        builtinClass: java.lang.Class<*>,
        pages: MutableMap<StarlarkDocumentationProcessor.Category?, MutableMap<String?, StarlarkDocPage>>,
        expander: StarlarkDocExpander?
    ) {
        val starlarkBuiltin: StarlarkBuiltin? =
            builtinClass.getAnnotation<StarlarkBuiltin?>(StarlarkBuiltin::class.java)

        if (starlarkBuiltin == null || !starlarkBuiltin.documented) {
            return
        }
        val builtinDoc: AnnotStarlarkBuiltinDoc =
            pages.get(
                com.google.devtools.build.docgen.StarlarkDocumentationProcessor.Category.Companion.of(
                    starlarkBuiltin
                )
            )!!.get(starlarkBuiltin.name) as AnnotStarlarkBuiltinDoc

        if (builtinClass != builtinDoc.getClassObject()) {
            return
        }
        for (entry in Starlark.getMethodAnnotations(builtinClass).entries) {
            // Collect methods that aren't directly constructors (i.e. have the @StarlarkConstructor
            // annotation).
            if (entry.key.isAnnotationPresent(com.google.devtools.build.docgen.annot.StarlarkConstructor::class.java)) {
                continue
            }
            var javaMethod: java.lang.reflect.Method? = entry.key
            val starlarkMethod: StarlarkMethod = entry.value
            // Struct fields that return a type that has @StarlarkConstructor are a bit special:
            // they're visited here because they're seen as an attribute of the module, but act more
            // like a reference to the type they construct.
            // TODO(wyv): does this actually happen???
            if (starlarkMethod.structField) {
                val selfCall: java.lang.reflect.Method? =
                    Starlark.getSelfCallMethod(StarlarkSemantics.DEFAULT, javaMethod.getReturnType())
                if (selfCall != null && selfCall.isAnnotationPresent(com.google.devtools.build.docgen.annot.StarlarkConstructor::class.java)) {
                    javaMethod = selfCall
                }
            }
            builtinDoc.addMember(
                AnnotStarlarkOrdinaryMethodDoc(
                    builtinDoc.getName(), javaMethod, starlarkMethod, expander
                )
            )
        }
    }

    /**
     * Adds [StarlarkJavaMethodDoc] entries to the top level module, one for
     * each @StarlarkMethod method defined in the given @GlobalMethods class `clazz`.
     */
    private fun collectGlobalMethods(
        clazz: java.lang.Class<*>,
        pages: MutableMap<StarlarkDocumentationProcessor.Category?, MutableMap<String?, StarlarkDocPage>>,
        expander: StarlarkDocExpander?
    ) {
        val globalMethods: com.google.devtools.build.docgen.annot.GlobalMethods? =
            clazz.getAnnotation<com.google.devtools.build.docgen.annot.GlobalMethods?>(com.google.devtools.build.docgen.annot.GlobalMethods::class.java)

        if (globalMethods == null && clazz.getName() != "net.starlark.java.eval.MethodLibrary") {
            return
        }

        val environments: Array<com.google.devtools.build.docgen.annot.GlobalMethods.Environment> =
            if (globalMethods == null) arrayOf<com.google.devtools.build.docgen.annot.GlobalMethods.Environment>(com.google.devtools.build.docgen.annot.GlobalMethods.Environment.ALL) else globalMethods.environment
        for (environment in environments) {
            val page: StarlarkDocPage =
                pages
                    .get(com.google.devtools.build.docgen.StarlarkDocumentationProcessor.Category.GLOBAL_FUNCTION)
                    .computeIfAbsent(
                        environment.getTitle()
                    ) { title: String? -> StarlarkGlobalsDoc(environment, expander) }
            for (entry in Starlark.getMethodAnnotations(clazz).entries) {
                // Only add non-constructor global library methods. Constructors are added later.
                // TODO(wyv): add a redirect instead
                if (!entry.key.isAnnotationPresent(com.google.devtools.build.docgen.annot.StarlarkConstructor::class.java)) {
                    page.addMember(
                        AnnotStarlarkOrdinaryMethodDoc("", entry.key, entry.value, expander)
                    )
                }
            }
        }
    }

    private fun collectConstructor(
        pages: MutableMap<StarlarkDocumentationProcessor.Category?, MutableMap<String?, StarlarkDocPage>>,
        method: java.lang.reflect.Method,
        expander: StarlarkDocExpander?
    ) {
        if (!method.isAnnotationPresent(com.google.devtools.build.docgen.annot.StarlarkConstructor::class.java)) {
            return
        }

        val starlarkBuiltin: StarlarkBuiltin? =
            StarlarkAnnotations.getStarlarkBuiltin(method.getReturnType())
        if (starlarkBuiltin == null || !starlarkBuiltin.documented) {
            // The class of the constructed object type has no documentation, so no place to add
            // constructor information.
            return
        }
        val methodAnnot: StarlarkMethod =
            com.google.common.base.Preconditions.checkNotNull<StarlarkMethod>(
                method.getAnnotation<StarlarkMethod?>(
                    StarlarkMethod::class.java
                )
            )
        val doc: StarlarkDocPage = pages.get(
            com.google.devtools.build.docgen.StarlarkDocumentationProcessor.Category.Companion.of(starlarkBuiltin)
        )!!.get(starlarkBuiltin.name)
        doc.setConstructor(
            AnnotStarlarkConstructorMethodDoc(
                starlarkBuiltin.name, method, methodAnnot, expander
            )
        )
    }

    /**
     * Parses a Starlark API proto to produce [StardocProtoStructDocPage] and [ ] pages, inserting them into the appropriate categories of `pages`.
     * 
     * @param moduleInfo a Stardoc proto for a .bzl file serving as an entry point for Starlark APIs
     * @param pages the categorized map of documentation pages; added to by this method
     * @param bzlStructPages a map from names of structs whose documentation has been collected to the
     * Stardoc protos defining them; added to by this method
     * @param expander the expander to use for links
     */
    private fun collectFromStardocProto(
        moduleInfo: ModuleInfo,
        pages: MutableMap<StarlarkDocumentationProcessor.Category?, MutableMap<String?, StarlarkDocPage>>,
        bzlStructPages: MutableMap<String?, ModuleInfo?>,
        expander: StarlarkDocExpander?
    ) {
        // For now, support only the following:
        // - structs containing only functions or provider aliases (classified as TOP_LEVEL_MODULE)
        // - providers not contained in a struct (classified as PROVIDER)
        val pagesInCategory: MutableMap<String?, StarlarkDocPage?> =
            pages.get(com.google.devtools.build.docgen.StarlarkDocumentationProcessor.Category.TOP_LEVEL_MODULE)
        for (symbolInfo in moduleInfo.getStarlarkOtherSymbolInfoList()) {
            if (symbolInfo.getTypeName().equals("struct")) {
                val structName: String = symbolInfo.getName()
                if (structName.contains(".")) {
                    // Skip nested structs.
                    continue
                }
                if (pagesInCategory.containsKey(structName)) {
                    checkState(
                        !bzlStructPages.containsKey(structName),
                        "Conflicting documentation for struct '%s' defined in Starlark files %s and %s",
                        structName,
                        moduleInfo.getFile(),
                        bzlStructPages.get(structName).getFile()
                    )
                    logger.atWarning().log(
                        "Documentation for struct %s defined in %s overrides previously-seen documentation"
                                + " for module %s implemented in Java",
                        structName, moduleInfo.getFile(), structName
                    )
                }
                pagesInCategory.put(
                    structName, StardocProtoStructDocPage(expander, moduleInfo, symbolInfo)
                )
            }
        }

        for (functionInfo in moduleInfo.getFuncInfoList()) {
            val functionName: String = functionInfo.getFunctionName()
            checkState(
                functionName.contains("."),
                "Function %s defined in %s must be namespaced inside a struct",
                functionName,
                moduleInfo.getFile()
            )
            val structName = getStructName(functionName, moduleInfo)
            val page: StarlarkDocPage =
                com.google.common.base.Preconditions.checkNotNull<StarlarkDocPage>(pagesInCategory.get(structName))
            page.addMember(StardocProtoFunctionDoc(expander, moduleInfo, structName, functionInfo))
        }

        for (providerInfo in moduleInfo.getProviderInfoList()) {
            val providerName: String = providerInfo.getProviderName()
            if (providerName.contains(".")) {
                // Aliased provider inside a struct.
                val structName = getStructName(providerName, moduleInfo)
                val structPage: StardocProtoStructDocPage =
                    com.google.common.base.Preconditions.checkNotNull<StarlarkDocPage?>(pagesInCategory.get(structName)) as StardocProtoStructDocPage
                structPage.addProviderAlias(providerInfo)
            } else {
                // Top-level provider.
                pages
                    .get(com.google.devtools.build.docgen.StarlarkDocumentationProcessor.Category.PROVIDER)!!
                    .put(providerName, StardocProtoProviderDocPage(expander, moduleInfo, providerInfo))
            }
        }

        // TODO(arostovtsev): What about other types of members in structs? Need changes to
        // starlark_doc_extract to check for their presence.
        verifyDoNotExist(
            moduleInfo,
            "aspects",
            moduleInfo.getAspectInfoList().stream()
                .map(AspectInfo::getAspectName)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        )
        verifyDoNotExist(
            moduleInfo,
            "macros",
            moduleInfo.getMacroInfoList().stream()
                .map(MacroInfo::getMacroName)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        )
        verifyDoNotExist(
            moduleInfo,
            "module extesions",
            moduleInfo.getModuleExtensionInfoList().stream()
                .map(ModuleExtensionInfo::getExtensionName)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        )
        verifyDoNotExist(
            moduleInfo,
            "repository rules",
            moduleInfo.getRepositoryRuleInfoList().stream()
                .map(RepositoryRuleInfo::getRuleName)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        )
        verifyDoNotExist(
            moduleInfo,
            "rules",
            moduleInfo.getRuleInfoList().stream()
                .map(RuleInfo::getRuleName)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        )
    }

    /**
     * Given a name of a struct member, for example "a.b.c", verifies that "a" is the name of a
     * documented struct in the moduleInfo and returns it.
     */
    private fun getStructName(memberName: String, moduleInfo: ModuleInfo): String {
        val structName: String = com.google.common.base.Splitter.on('.').splitToList(memberName).getFirst()
        checkState(
            moduleInfo.getStarlarkOtherSymbolInfoList().stream()
                .anyMatch({ symbolInfo -> symbolInfo.getName().equals(structName) }),
            "Struct %s defined in %s must be documented with '#:'-prefixed doc comments",
            structName,
            moduleInfo.getFile()
        )
        return structName
    }

    private fun verifyDoNotExist(moduleInfo: ModuleInfo, what: String?, badNames: MutableList<String?>) {
        checkState(
            badNames.isEmpty(),
            "Starlark and BUILD language API entry point %s is expected not to contain %s;"
                    + " found %s",
            moduleInfo.getFile(),
            what,
            badNames
        )
    }

    /**
     * Collect two types of constructor methods:
     * 
     * 
     * 1. The single method with selfCall=true and @StarlarkConstructor (if present)
     * 
     * 
     * 2. Any methods annotated with @StarlarkConstructor
     * 
     * 
     * Structfield methods that return an object which itself has selfCall=true
     * and @StarlarkConstructor are *not* collected here (collectModuleMethods does that). (For
     * example, supposed Foo has a structfield method named 'Bar', which refers to the Bar type. In
     * Foo's doc, we describe Foo.Bar as an attribute of type Bar and link to the canonical Bar type
     * documentation)
     */
    private fun collectConstructorMethods(
        clazz: java.lang.Class<*>,
        pages: MutableMap<StarlarkDocumentationProcessor.Category?, MutableMap<String?, StarlarkDocPage>>,
        expander: StarlarkDocExpander?
    ) {
        if (!clazz.isAnnotationPresent(StarlarkBuiltin::class.java)
            && !clazz.isAnnotationPresent(com.google.devtools.build.docgen.annot.GlobalMethods::class.java)
        ) {
            return
        }
        val selfCall: java.lang.reflect.Method? = Starlark.getSelfCallMethod(StarlarkSemantics.DEFAULT, clazz)
        if (selfCall != null) {
            collectConstructor(pages, selfCall, expander)
        }

        for (method in Starlark.getMethodAnnotations(clazz).keys) {
            collectConstructor(pages, method, expander)
        }
    }
}
