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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.Label

/** The Starlark native module.  */
class StarlarkNativeModule : StarlarkNativeModuleApi {
    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    override fun glob(
        include: net.starlark.java.eval.Sequence<*>?,
        exclude: net.starlark.java.eval.Sequence<*>?,
        excludeDirs: net.starlark.java.eval.StarlarkInt,
        allowEmptyArgument: Any?,
        thread: net.starlark.java.eval.StarlarkThread
    ): net.starlark.java.eval.Sequence<*>? {
        val pkgBuilder: com.google.devtools.build.lib.packages.Package.AbstractBuilder =
            com.google.devtools.build.lib.packages.Package.AbstractBuilder.Companion.fromOrFailAllowBuildOnly(
                thread,
                "glob()"
            )

        val includes: MutableList<String?> =
            com.google.devtools.build.lib.packages.Types.STRING_LIST.convert(include, "'glob' argument")
        val excludes: MutableList<String?> =
            com.google.devtools.build.lib.packages.Types.STRING_LIST.convert(exclude, "'glob' argument")
        val op: com.google.devtools.build.lib.packages.Globber.Operation =
            if (excludeDirs.signum() != 0) com.google.devtools.build.lib.packages.Globber.Operation.FILES else com.google.devtools.build.lib.packages.Globber.Operation.FILES_AND_DIRS

        val allowEmpty: Boolean
        if (allowEmptyArgument === net.starlark.java.eval.Starlark.UNBOUND) {
            allowEmpty =
                !thread.getSemantics().getBool(BuildLanguageOptions.Companion.INCOMPATIBLE_DISALLOW_EMPTY_GLOB)
        } else if (allowEmptyArgument is Boolean) {
            allowEmpty = allowEmptyArgument
        } else {
            throw net.starlark.java.eval.Starlark.errorf(
                "expected boolean for argument `allow_empty`, got `%s`", allowEmptyArgument
            )
        }

        val matches = runGlobOperation(pkgBuilder, thread, includes, excludes, op, allowEmpty)

        val result: java.util.ArrayList<String> = java.util.ArrayList<String>(matches.size())
        for (match in matches) {
            var match = match
            if (match.charAt(0) == '@') {
                // Add explicit colon to disambiguate from external repository.
                match = ":" + match
            }
            result.add(match)
        }
        result.sort(java.util.Comparator.naturalOrder<String?>())

        return net.starlark.java.eval.StarlarkList.copyOf<String?>(thread.mutability(), result)
    }

    /**
     * WARNING -- HACK: We're using this marker type to signify that we're in module extension eval,
     * and native.existing_rule[s] should just return nothing. We can't check for
     * ModuleExtensionEvalStarlarkThreadContext because that would cause a cyclic dependency. The
     * proper way to implement this would be to create a distinct no-op "StarlarkNativeModule" object
     * that's only used for bzlmod, but that requires a big refactor that we're not going to have time
     * for before Bazel 5.0.
     */
    // TODO(wyv): Do the proper fix described above.
    class ExistingRulesShouldBeNoOp

    // TODO(https://github.com/bazelbuild/bazel/issues/13605): implement StarlarkMapping (after we've
    // added such an interface) to allow `dict(native.existing_rule(x))`.
    private interface DictLikeView

        : net.starlark.java.eval.StarlarkIndexable, net.starlark.java.eval.StarlarkIterable<String?>,
        MutableMap<String?, Any?> {
        override fun isImmutable(): Boolean {
            return true
        }

        // java.util.Map accessor.
        // For absent keys, Java callers will see null and Starlark callers will see None.
        @net.starlark.java.annot.StarlarkMethod(
            name = "get",
            doc = "Behaves the same as <a href=\"dict.html#get\"><code>dict.get</code></a>.",
            parameters = [net.starlark.java.annot.Param(
                name = "key",
                doc = "The key to look for."
            ), net.starlark.java.annot.Param(
                name = "default",
                defaultValue = "None",
                named = true,
                doc = "The default value to use (instead of None) if the key is not found."
            )],
            allowReturnNones = true
        )
        override fun getOrDefault(key: Any?, defaultValue: Any?): Any?

        @net.starlark.java.annot.StarlarkMethod(
            name = "keys", doc = ("Behaves like <a href=\"dict.html#keys\"><code>dict.keys</code></a>, but the returned"
                    + " value is an immutable sequence.")
        )
        fun keys(): net.starlark.java.eval.StarlarkIterable<String?>? {
            // TODO(https://github.com/bazelbuild/starlark/issues/203): return a sequence view which
            // supports efficient membership lookup (`"foo" in existing_rule("bar").keys()`), and
            // materializes into a list (to allow len() or lookup by integer index) only if needed. Note
            // that materialization into a list would need to be thread-safe (assuming it's possible for
            // the sequence view to be used from multiple starlark threads). For now, we return an
            // immutable list, so that migration to a sequence view is less likely to cause breakage.
            return net.starlark.java.eval.StarlarkList.immutableCopyOf<String?>(this)
        }

        @net.starlark.java.annot.StarlarkMethod(
            name = "values", doc = ("Behaves like <a href=\"dict.html#values\"><code>dict.values</code></a>, but the"
                    + " returned value is an immutable sequence.")
        ) // This method is named starlarkValues to avoid collision with Map#values
        // (StarlarkAnnotations.getStarlarkMethod does not support overloading).
        fun starlarkValues(): net.starlark.java.eval.StarlarkIterable<Any?>? {
            // TODO(https://github.com/bazelbuild/starlark/issues/203): return a sequence view; see keys()
            // for implementation concerns.
            val valueList: java.util.ArrayList<Any?> = java.util.ArrayList<Any?>()
            for (key in this) {
                valueList.add(com.google.common.base.Preconditions.checkNotNull<Any?>(get(key)))
            }
            return net.starlark.java.eval.StarlarkList.immutableCopyOf<Any?>(valueList)
        }

        @net.starlark.java.annot.StarlarkMethod(
            name = "items",
            doc = ("Behaves like <a href=\"dict.html#items\"><code>dict.items</code></a>, but the returned"
                    + " value is an immutable sequence.")
        )
        fun items(): net.starlark.java.eval.StarlarkIterable<net.starlark.java.eval.Tuple>? {
            // TODO(https://github.com/bazelbuild/starlark/issues/203): return a sequence view; see keys()
            // for implementation concerns.
            val itemsList: java.util.ArrayList<net.starlark.java.eval.Tuple?> =
                java.util.ArrayList<net.starlark.java.eval.Tuple?>()
            for (key in this) {
                itemsList.add(
                    net.starlark.java.eval.Tuple.pair(
                        key,
                        com.google.common.base.Preconditions.checkNotNull<Any?>(get(key))
                    )
                )
            }
            return net.starlark.java.eval.StarlarkList.immutableCopyOf<net.starlark.java.eval.Tuple?>(itemsList)
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun getIndex(semantics: net.starlark.java.eval.StarlarkSemantics?, key: Any?): Any {
            val `val` = get(key)
            if (`val` != null) {
                return `val`
            }
            throw net.starlark.java.eval.Starlark.errorf(
                "key %s not found in view",
                net.starlark.java.eval.Starlark.repr(key, semantics)
            )
        }

        override fun containsKey(semantics: net.starlark.java.eval.StarlarkSemantics?, key: Any?): Boolean {
            return containsKey(key)
        }

        // java.util.Map accessors
        override fun get(key: Any?): Any? {
            return getOrDefault(key, null)
        }

        override fun isEmpty(): Boolean {
            return !iterator().hasNext()
        }

        override fun keySet(): MutableSet<String?> {
            return com.google.common.collect.ImmutableSet.copyOf<String?>(keys())
        }

        override fun values(): MutableCollection<Any?>? {
            return net.starlark.java.eval.StarlarkList.immutableCopyOf<Any?>(starlarkValues())
        }

        override fun entrySet(): MutableSet<MutableMap.MutableEntry<String?, Any?>?> {
            val entries: com.google.common.collect.ImmutableSet.Builder<MutableMap.MutableEntry<String?, Any?>?> =
                com.google.common.collect.ImmutableSet.Builder<MutableMap.MutableEntry<String?, Any?>?>()
            for (keyValuePair in items()) {
                entries.add(
                    AbstractMap.SimpleEntry<String?, Any?>(
                        keyValuePair.get(0) as String?, keyValuePair.get(1)
                    )
                )
            }
            return entries.build()
        }

        override fun containsValue(value: Any?): Boolean {
            for (key in this) {
                if (com.google.common.base.Preconditions.checkNotNull<Any?>(get(key)) == value) {
                    return true
                }
            }
            return false
        }

        // disallow java.util.Map mutators

        @Deprecated("Not supported: immutable view.")
        override fun clear() {
            throw java.lang.UnsupportedOperationException()
        }

        @Deprecated("Not supported: immutable view.")
        override fun put(key: String?, value: Any?): Any? {
            throw java.lang.UnsupportedOperationException()
        }

        @Deprecated("Not supported: immutable view.")
        override fun putAll(map: MutableMap<out String?, out Any?>?) {
            throw java.lang.UnsupportedOperationException()
        }

        @Deprecated("Not supported: immutable view.")
        override fun remove(key: Any?): Any? {
            throw java.lang.UnsupportedOperationException()
        }
    }

    // Note: Attribute values that are not representable in Starlark are treated as if they are absent
    // in the view.
    private class ExistingRuleView(rule: com.google.devtools.build.lib.packages.Rule) : DictLikeView {
        private val rule: com.google.devtools.build.lib.packages.Rule

        init {
            this.rule = rule
        }

        override fun repr(
            printer: net.starlark.java.eval.Printer,
            semantics: net.starlark.java.eval.StarlarkSemantics?
        ) {
            printer.append("<native.ExistingRuleView for target '").append(rule.getName()).append("'>")
        }

        /**
         * Returns the starlark representation of our rule's attribute value if the attribute is
         * exportable and the value can be represented in starlark; otherwise, returns null.
         */
        fun starlarkifyAttribute(attributeName: String): Any? {
            if (!isPotentiallyExportableAttribute(attributeName)) {
                return null
            }
            return starlarkifyValue(
                null,  /* immutable */
                rule.getAttr(attributeName),
                rule.getPackageoid().getPackageIdentifier()
            )
        }

        // Starlark callers get None where Java callers would expect null.
        override fun getOrDefault(key: Any?, defaultValue: Any?): Any? {
            if (key !is String) {
                return defaultValue
            }
            val attributeName = key
            when (attributeName) {
                "name" -> {
                    return rule.getName()
                }

                "kind" -> {
                    return rule.getRuleClass()
                }

                else -> {
                    val value = starlarkifyAttribute(attributeName)
                    if (value != null) {
                        return value
                    }
                }
            }
            return defaultValue
        }

        override fun iterator(): MutableIterator<String?> {
            return com.google.common.collect.Iterators.concat<String?>(
                com.google.common.collect.ImmutableList.of<String?>("name", "kind")
                    .iterator(),  // Compared to using stream().map(...).filter(...).iterator(), this bespoke iterator
                // reduces loading time by 15% for a 4000-target package making heavy use of
                // `native.existing_rules`.
                object : com.google.common.collect.UnmodifiableIterator<String?>() {
                    private val attributes: MutableIterator<com.google.devtools.build.lib.packages.Attribute?> =
                        rule.getAttributes().iterator()
                    private var nextRelevantAttributeName: String? = null

                    fun isRelevant(attributeName: String): Boolean {
                        return when (attributeName) {
                            "name", "kind" -> false
                            else -> starlarkifyAttribute(attributeName) != null
                        }
                    }

                    fun findNextRelevantName() {
                        if (nextRelevantAttributeName == null) {
                            while (attributes.hasNext()) {
                                val attributeName: String = attributes.next().getName()
                                if (isRelevant(attributeName)) {
                                    nextRelevantAttributeName = attributeName
                                    break
                                }
                            }
                        }
                    }

                    override fun hasNext(): Boolean {
                        findNextRelevantName()
                        return nextRelevantAttributeName != null
                    }

                    override fun next(): String {
                        findNextRelevantName()
                        if (nextRelevantAttributeName != null) {
                            val attributeName = nextRelevantAttributeName
                            nextRelevantAttributeName = null
                            return attributeName!!
                        } else {
                            throw java.util.NoSuchElementException()
                        }
                    }
                })
        }

        override fun containsKey(key: Any?): Boolean {
            return get(key) != null
        }

        // Necessarily O(n), since we need to scan which attributes are exportable/starlakifiable. We
        // could cache the result, but the complexity of doing so does not seem to be worth (currently,
        // this method is not expected to be called).
        override fun size(): Int {
            return com.google.common.collect.Iterables.size(this)
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun existingRule(name: String?, thread: net.starlark.java.eval.StarlarkThread): Any? {
        if (thread.getThreadLocal<ExistingRulesShouldBeNoOp?>(ExistingRulesShouldBeNoOp::class.java) != null) {
            return net.starlark.java.eval.Starlark.NONE
        }
        val targetDefinitionContext: TargetDefinitionContext =
            TargetDefinitionContext.Companion.fromOrFailDisallowNonFinalizerMacros(thread, "existing_rule()")
        if (targetDefinitionContext is com.google.devtools.build.lib.packages.PackagePiece.ForBuildFile.Builder) {
            // TODO(https://github.com/bazelbuild/bazel/issues/25539): Figure out what to do if we
            // encounter native.existing_rule() under PackagePiece.ForBuildFile.Builder.
            throw net.starlark.java.eval.Starlark.errorf(
                "under lazy macro expansion, existing_rule() is supported only in finalizer macros"
            )
        }
        val rule: com.google.devtools.build.lib.packages.Rule? =
            targetDefinitionContext.getNonFinalizerInstantiatedRule(name)
        if (rule != null) {
            return ExistingRuleView(rule)
        } else {
            return net.starlark.java.eval.Starlark.NONE
        }
    }

    private class ExistingRulesView(rulesSnapshotView: MutableMap<String?, com.google.devtools.build.lib.packages.Rule?>) :
        DictLikeView {
        // We take a lightweight snapshot of the rules existing in a Package.Builder to avoid exposing
        // any rules added to Package.Builder after the existing_rules() call which created this view.
        private val rulesSnapshotView: MutableMap<String?, com.google.devtools.build.lib.packages.Rule?>

        init {
            this.rulesSnapshotView = rulesSnapshotView
        }

        override fun repr(
            printer: net.starlark.java.eval.Printer,
            semantics: net.starlark.java.eval.StarlarkSemantics?
        ) {
            printer.append("<native.ExistingRulesView object>")
        }

        // Starlark callers get None where Java callers would expect null.
        override fun getOrDefault(key: Any?, defaultValue: Any?): Any? {
            if (key !is String) {
                return defaultValue
            }
            val rule: com.google.devtools.build.lib.packages.Rule? = rulesSnapshotView.get(key)
            if (rule != null) {
                return ExistingRuleView(rule)
            } else {
                return defaultValue
            }
        }

        override fun iterator(): MutableIterator<String?>? {
            return rulesSnapshotView.keySet().iterator()
        }

        override fun containsKey(key: Any?): Boolean {
            if (key !is String) {
                return false
            }
            return rulesSnapshotView.containsKey(key)
        }

        override fun size(): Int {
            return rulesSnapshotView.size()
        }
    }

    /*
    If necessary, we could allow filtering by tag (anytag, alltags), name (regexp?), kind ?
    For now, we ignore this, since users can implement it in Starlark.
  */
    @Throws(net.starlark.java.eval.EvalException::class)
    override fun existingRules(thread: net.starlark.java.eval.StarlarkThread): Any? {
        if (thread.getThreadLocal<ExistingRulesShouldBeNoOp?>(ExistingRulesShouldBeNoOp::class.java) != null) {
            return net.starlark.java.eval.Dict.empty<Any?, Any?>()
        }
        val targetDefinitionContext: TargetDefinitionContext =
            TargetDefinitionContext.Companion.fromOrFailDisallowNonFinalizerMacros(thread, "existing_rules()")
        if (targetDefinitionContext is com.google.devtools.build.lib.packages.PackagePiece.ForBuildFile.Builder) {
            // TODO(https://github.com/bazelbuild/bazel/issues/25539): figure out what to do if we
            // encounter native.existing_rules() under PackagePiece.ForBuildFile.Builder.
            throw net.starlark.java.eval.Starlark.errorf(
                "under lazy macro expansion, existing_rules() is supported only in finalizer macros"
            )
        }
        return ExistingRulesView(targetDefinitionContext.getRulesSnapshotView())
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun packageGroup(
        name: String?,
        packagesO: net.starlark.java.eval.Sequence<*>?,
        includesO: net.starlark.java.eval.Sequence<*>?,
        thread: net.starlark.java.eval.StarlarkThread
    ): net.starlark.java.eval.NoneType? {
        val targetDefinitionContext: TargetDefinitionContext =
            TargetDefinitionContext.Companion.fromOrFail(thread, "package_group()")

        val packages: MutableList<String?>? =
            com.google.devtools.build.lib.packages.Types.STRING_LIST.convert(
                packagesO,
                "'package_group.packages argument'"
            )
        val includes: MutableList<Label?>? =
            BuildType.LABEL_LIST.convert(
                includesO,
                "'package_group.includes argument'",
                targetDefinitionContext.getLabelConverter()
            )

        val loc: net.starlark.java.syntax.Location? = thread.getCallerLocation()
        try {
            targetDefinitionContext.addPackageGroup(
                name,
                packages,
                includes,  /* allowPublicPrivate= */
                thread
                    .getSemantics()
                    .getBool(BuildLanguageOptions.Companion.INCOMPATIBLE_PACKAGE_GROUP_HAS_PUBLIC_SYNTAX),  /* repoRootMeansCurrentRepo= */
                thread
                    .getSemantics()
                    .getBool(BuildLanguageOptions.Companion.INCOMPATIBLE_FIX_PACKAGE_GROUP_REPOROOT_SYNTAX),  // TODO(#19922): addPackageGroup should access the builder's own eventHandler directly.
                targetDefinitionContext.getLocalEventHandler(),
                loc
            )
            return net.starlark.java.eval.Starlark.NONE
        } catch (e: LabelSyntaxException) {
            throw net.starlark.java.eval.Starlark.errorf("package group has invalid name: %s: %s", name, e.getMessage())
        } catch (e: NameConflictException) {
            throw net.starlark.java.eval.EvalException(e)
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun exportsFiles(
        srcs: net.starlark.java.eval.Sequence<*>?,
        visibilityO: Any?,
        licensesO: Any?,
        thread: net.starlark.java.eval.StarlarkThread
    ): net.starlark.java.eval.NoneType? {
        val targetDefinitionContext: TargetDefinitionContext =
            TargetDefinitionContext.Companion.fromOrFail(thread, "exports_files()")
        val files: MutableList<String?> =
            com.google.devtools.build.lib.packages.Types.STRING_LIST.convert(srcs, "'exports_files' operand")

        var visibility: RuleVisibility? =
            if (net.starlark.java.eval.Starlark.isNullOrNone(visibilityO))
                RuleVisibility.Companion.PUBLIC
            else
                RuleVisibility.Companion.parse(
                    BuildType.LABEL_LIST.convert(
                        visibilityO,
                        "'exports_files' operand",
                        targetDefinitionContext.getLabelConverter()
                    )
                )
        val currentMacro: MacroInstance? = targetDefinitionContext.currentMacro()
        if (currentMacro != null) {
            visibility = visibility.concatWithPackage(currentMacro.getDefinitionPackage())
        }

        // TODO(bazel-team): is licenses plural or singular?
        val license: License? = BuildType.LICENSE.convertOptional(licensesO, "'exports_files' operand")

        val loc: net.starlark.java.syntax.Location? = thread.getCallerLocation()
        for (file in files) {
            val errorMessage: String? = LabelValidator.validateTargetName(file)
            if (errorMessage != null) {
                throw net.starlark.java.eval.Starlark.errorf("%s", errorMessage)
            }
            try {
                val inputFile: InputFile = targetDefinitionContext.createInputFile(file, loc)
                // TODO: #19922 - The use of identity inequality in this visibility check seems suspect,
                // since the same logical visibility may have multiple RuleVisibility instances. But it's
                // unclear why we want to support idempotent exports_files() with the same logical
                // visibility at all. With Macro-Aware Visibility, it becomes possible for two identical
                // visibility lines to declare different actual visibility values depending on context.
                if (inputFile.isVisibilitySpecified() && inputFile.getVisibility() !== visibility) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "visibility for exported file '%s' declared twice", inputFile.getName()
                    )
                }
                if (license != null && inputFile.isLicenseSpecified()) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "licenses for exported file '%s' declared twice", inputFile.getName()
                    )
                }

                targetDefinitionContext.setVisibilityAndLicense(inputFile, visibility, license)
            } catch (e: NameConflictException) {
                throw net.starlark.java.eval.Starlark.errorf("%s", e.getMessage())
            }
        }
        return net.starlark.java.eval.Starlark.NONE
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun packageName(thread: net.starlark.java.eval.StarlarkThread?): String {
        val targetDefinitionContext: TargetDefinitionContext =
            TargetDefinitionContext.Companion.fromOrFail(thread, "package_name()")
        return targetDefinitionContext.getPackageIdentifier().getPackageFragment().getPathString()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun packageDefaultVisibility(thread: net.starlark.java.eval.StarlarkThread?): MutableList<Label?>? {
        val targetDefinitionContext: TargetDefinitionContext =
            TargetDefinitionContext.Companion.fromOrFail(thread, "package_default_visibility()")
        return targetDefinitionContext
            .getPartialPackageArgs()
            .defaultVisibility() // Add the package itself to the returned value. This matches the semantics that anything
            // that implicitly uses the default_visibility is also visible to the package.
            .concatWithPackage(targetDefinitionContext.getPackageIdentifier())
            .getDeclaredLabels()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun repositoryName(thread: net.starlark.java.eval.StarlarkThread?): String {
        // for legacy reasons, this is prefixed with a single '@'.
        TargetDefinitionContext.Companion.fromOrFail(thread, "repository_name()")
        return '@'.toString() + repoName(thread)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun repoName(thread: net.starlark.java.eval.StarlarkThread?): String {
        val targetDefinitionContext: TargetDefinitionContext =
            TargetDefinitionContext.Companion.fromOrFail(thread, "repo_name()")
        return targetDefinitionContext.getPackageIdentifier().getRepository().name
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun packageRelativeLabel(input: Any?, thread: net.starlark.java.eval.StarlarkThread): Label? {
        // In an initializer, BazelStarlarkContext isn't available, just the label converter.
        var labelConverter: LabelConverter? = thread.getThreadLocal<LabelConverter?>(LabelConverter::class.java)
        if (labelConverter == null) {
            val targetDefinitionContext: TargetDefinitionContext =
                TargetDefinitionContext.Companion.fromOrFail(thread, "package_relative_label()")
            labelConverter = targetDefinitionContext.getLabelConverter()
        }
        if (input is Label) {
            return input
        }
        try {
            return labelConverter.convert(input as String?)
        } catch (e: LabelSyntaxException) {
            throw net.starlark.java.eval.Starlark.errorf(
                "invalid label in native.package_relative_label: %s",
                e.getMessage()
            )
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun moduleName(thread: net.starlark.java.eval.StarlarkThread?): String? {
        val targetDefinitionContext: TargetDefinitionContext =
            TargetDefinitionContext.Companion.fromOrFail(thread, "module_name()")
        return targetDefinitionContext.getAssociatedModuleName().orElse(null)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun moduleVersion(thread: net.starlark.java.eval.StarlarkThread?): String? {
        val targetDefinitionContext: TargetDefinitionContext =
            TargetDefinitionContext.Companion.fromOrFail(thread, "module_version()")
        return targetDefinitionContext.getAssociatedModuleVersion().orElse(null)
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    override fun subpackages(
        include: net.starlark.java.eval.Sequence<*>?,
        exclude: net.starlark.java.eval.Sequence<*>?,
        allowEmpty: Boolean,
        thread: net.starlark.java.eval.StarlarkThread
    ): net.starlark.java.eval.Sequence<*>? {
        val pkgBuilder: com.google.devtools.build.lib.packages.Package.AbstractBuilder =
            com.google.devtools.build.lib.packages.Package.AbstractBuilder.Companion.fromOrFailAllowBuildOnly(
                thread,
                "subpackages()"
            )

        val includes: MutableList<String?> =
            com.google.devtools.build.lib.packages.Types.STRING_LIST.convert(include, "'subpackages' argument")
        val excludes: MutableList<String?> =
            com.google.devtools.build.lib.packages.Types.STRING_LIST.convert(exclude, "'subpackages' argument")

        var matches =
            runGlobOperation(
                pkgBuilder,
                thread,
                includes,
                excludes,
                com.google.devtools.build.lib.packages.Globber.Operation.SUBPACKAGES,
                allowEmpty
            )
        if (!matches.isEmpty()) {
            try {
                matches.sort(java.util.Comparator.naturalOrder<String?>())
            } catch (e: java.lang.UnsupportedOperationException) {
                matches = com.google.common.collect.ImmutableList.sortedCopyOf<String?>(
                    java.util.Comparator.naturalOrder<String?>(),
                    matches
                )
            }
        }
        return net.starlark.java.eval.StarlarkList.copyOf<String?>(thread.mutability(), matches)
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun runGlobOperation(
        pkgBuilder: com.google.devtools.build.lib.packages.Package.AbstractBuilder,
        thread: net.starlark.java.eval.StarlarkThread,
        includes: MutableList<String?>,
        excludes: MutableList<String?>,
        operation: com.google.devtools.build.lib.packages.Globber.Operation?,
        allowEmpty: Boolean
    ): MutableList<String> {
        val cpuSemaphore: Semaphore? = pkgBuilder.getCpuBoundSemaphore()
        try {
            if (cpuSemaphore != null) {
                // Throwing exceptions inside the try block before this release could lead to the semaphore
                // being acquired more times than it is released.
                cpuSemaphore.release()
            }
            val globToken: com.google.devtools.build.lib.packages.Globber.Token? =
                pkgBuilder.getGlobber().runAsync(includes, excludes, operation, allowEmpty)
            return pkgBuilder.getGlobber().fetchUnsorted(globToken)
        } catch (e: IOException) {
            logger.atWarning().withCause(e).log(
                "Exception processing includes=%s, excludes=%s)", includes, excludes
            )
            val errorMessage: String? =
                java.lang.String.format(
                    "error globbing [%s]%s op=%s: %s",
                    com.google.common.base.Joiner.on(", ").join(includes),
                    if (excludes.isEmpty()) "" else " - [" + com.google.common.base.Joiner.on(", ")
                        .join(excludes) + "]",
                    operation,
                    e.getMessage()
                )
            val loc: net.starlark.java.syntax.Location? = thread.getCallerLocation()
            val error: Event =
                when (e) {
                    -> com.google.devtools.build.lib.packages.Package.Companion.errorWithDetailedExitCode(
                        loc, errorMessage, detailed.getDetailedExitCode()
                    )

                    -> com.google.devtools.build.lib.packages.Package.Companion.error(
                        loc,
                        errorMessage,
                        Code.EVAL_GLOBS_SYMLINK_ERROR
                    )

                    else -> com.google.devtools.build.lib.packages.Package.Companion.error(
                        loc,
                        errorMessage,
                        Code.GLOB_IO_EXCEPTION
                    )
                }
            pkgBuilder.getLocalEventHandler().handle(error)
            pkgBuilder.setIOException(e, errorMessage, error.getProperty(DetailedExitCode::class.java))
            return com.google.common.collect.ImmutableList.of<String?>()
        } catch (e: BadGlobException) {
            throw net.starlark.java.eval.EvalException(e)
        } finally {
            if (cpuSemaphore != null) {
                cpuSemaphore.acquire()
            }
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /**
         * This map contains all the (non-rule) functions of the native module (keyed by their symbol
         * name). These native module bindings should be added (without the 'native' module namespace) to
         * the global Starlark environment for BUILD files.
         * 
         * 
         * For example, the function "glob" is available under both a global symbol name `glob()`
         * as well as under the native module namepsace `native.glob()`. An entry of this map is
         * thus ("glob" : glob function).
         */
        val BINDINGS_FOR_BUILD_FILES: com.google.common.collect.ImmutableMap<String?, Any?> = initializeBindings()

        private fun initializeBindings(): com.google.common.collect.ImmutableMap<String?, Any?> {
            val bindings: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
                com.google.common.collect.ImmutableMap.builder<String?, Any?>()
            net.starlark.java.eval.Starlark.addMethods(bindings, StarlarkNativeModule())
            return bindings.buildOrThrow()
        }

        /**
         * Returns true if the given attribute of a rule class is generally allowed to be exposed via
         * `native.existing_rule()` and `native.existing_rules()`.
         * 
         * 
         * This method makes no attempt to validate that the attribute exists in the rule class.
         * 
         * 
         * Even if this method returns true, the attribute may still be suppressed if it has a
         * prohibited value (e.g. is of a bad type, or is a select() that cannot be processed).
         */
        private fun isPotentiallyExportableAttribute(attributeName: String): Boolean {
            if (attributeName.isEmpty() || !java.lang.Character.isAlphabetic(attributeName.charAt(0).code)) {
                // Do not expose hidden or implicit attributes.
                return false
            }
            return true
        }

        /**
         * Converts a target attribute value to a Starlark value for return in `native.existing_rule()` or `native.existing_rules()`.
         * 
         * 
         * Any dict values in the result have mutability `mu`.
         * 
         * 
         * Any label values in the result which are inside `pkg` (the current package) are
         * rewritten using ":foo" shorthand.
         * 
         * @return the value, or null if we don't want to export it to the user.
         */
        fun starlarkifyValue(
            mu: net.starlark.java.eval.Mutability?, `val`: Any?, packageIdentifier: PackageIdentifier
        ): Any? {
            // easy cases
            if (`val` == null) {
                return null
            }
            if (`val`.getClass().isAnonymousClass()) {
                // Computed defaults. They will be represented as
                // "deprecation": com.google.devtools.build.lib.analysis.BaseRuleClasses$2@6960884a,
                // Filter them until we invent something more clever.
                return null
            }

            return when (`val`) {
                -> b
                -> s
                -> i
                -> when (triState) {
                    com.google.devtools.build.lib.packages.TriState.AUTO -> net.starlark.java.eval.StarlarkInt.of(-1)
                    com.google.devtools.build.lib.packages.TriState.YES -> net.starlark.java.eval.StarlarkInt.of(1)
                    com.google.devtools.build.lib.packages.TriState.NO -> net.starlark.java.eval.StarlarkInt.of(0)
                }

                -> ":" + l.name
                -> "//" + l.getPackageFragment().getPathString() + ":" + l.name
                -> l.getUnambiguousCanonicalForm()
                -> {
                    val l: MutableList<Any?> = java.util.ArrayList<Any?>()
                    for (o in list) {
                        val elt = starlarkifyValue(mu, o, packageIdentifier)
                        if (elt == null) {
                            continue
                        }
                        l.add(elt)
                    }

                    net.starlark.java.eval.Tuple.copyOf(l)
                }

                -> {
                    val m: net.starlark.java.eval.Dict.Builder<Any?, Any?> =
                        net.starlark.java.eval.Dict.builder<Any?, Any?>()
                    for (e in map.entrySet()) {
                        val key = starlarkifyValue(mu, e.getKey(), packageIdentifier)
                        val mapVal = starlarkifyValue(mu, e.getValue(), packageIdentifier)

                        if (key == null || mapVal == null) {
                            continue
                        }

                        m.put(key, mapVal)
                    }
                    m.build(mu)
                }

                -> {
                    val selectors: MutableList<Any?> = java.util.ArrayList<Any?>()
                    for (selector in selectorList.getSelectors()) {
                        val m: com.google.common.collect.ImmutableMap.Builder<Any?, Any?> =
                            com.google.common.collect.ImmutableMap.builderWithExpectedSize<Any?, Any?>(selector.getNumEntries())
                        selector.forEach { label: Label?, rawValue: Any? ->
                            // BuildType.Selector constructor transforms `None` values of selector branches into
                            // Java nulls if the selector original type's default value is null. We need to
                            // reverse this transformation.
                            val mapVal =
                                if (rawValue == null && selector.getOriginalType().getDefaultValue() == null)
                                    net.starlark.java.eval.Starlark.NONE
                                else
                                    starlarkifyValue(mu, rawValue, packageIdentifier)
                            if (mapVal != null) {
                                // Preserve labels in select keys as such instead of prettifying them to strings -
                                // selects can't be inspected directly (ignoring their string representation) and
                                // the conversion risks resolving the label in a different context.
                                m.put(label, mapVal)
                            }
                        }
                        val selectorDict: com.google.common.collect.ImmutableMap<Any?, Any?> = m.buildKeepingLast()
                        if (!selectorDict.isEmpty()) {
                            selectors.add(SelectorValue(selectorDict, selector.getNoMatchError()))
                        }
                    }
                    if (selectors.isEmpty()) {
                        null
                    }
                    try {
                        com.google.devtools.build.lib.packages.SelectorList.Companion.of(selectors)
                    } catch (e: net.starlark.java.eval.EvalException) {
                        null
                    }
                }

                -> null
                -> starlarkValue
                else -> null
            }
        }
    }
}
