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
package com.google.devtools.build.lib.cmdline

import com.google.devtools.build.lib.actions.CommandLineItem

/**
 * A class to identify a BUILD target. All targets belong to exactly one package. The name of a
 * target is called its label. A typical label looks like this: //dir1/dir2:target_name where
 * 'dir1/dir2' identifies the package containing a BUILD file, and 'target_name' identifies the
 * target within the package.
 * 
 * 
 * Parsing is robust against bad input, for example, from the command line.
 */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "Label",
    category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
    doc = ("A BUILD target identifier.<p>For every <code>Label</code> instance <code>l</code>, the"
            + " string representation <code>str(l)</code> has the property that <code>Label(str(l))"
            + " == l</code>, regardless of where the <code>Label()</code> call occurs.<p>When"
            + " passed as positional arguments to <code>print()</code> or <code>fail()</code>,"
            + " <code>Label</code> use a string representation optimized for human readability"
            + " instead. This representation uses an <a"
            + " href=\"/external/overview#apparent-repo-name\">apparent repository name</a> from"
            + " the perspective of the main repository if possible.")
)
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
class Label private constructor(packageIdentifier: PackageIdentifier, name: String) : Comparable<Label?>,
    net.starlark.java.eval.StarlarkValue, SkyKey, CommandLineItem {
    /** The context of a current repo, necessary to parse a repo-relative label ("//foo:bar").  */
    interface RepoContext {
        fun currentRepo(): RepositoryName?

        fun repoMapping(): com.google.devtools.build.lib.cmdline.RepositoryMapping?

        fun rootPackage(): PackageContext {
            return PackageContext.Companion.of(
                PackageIdentifier.Companion.createRootPackage(currentRepo()),
                repoMapping()
            )
        }

        companion object {
            fun of(
                currentRepo: RepositoryName?,
                repoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?
            ): RepoContext {
                return AutoValue_Label_RepoContextImpl(currentRepo, repoMapping)
            }
        }
    }

    @AutoValue
    internal abstract class RepoContextImpl : RepoContext

    /** The context of a current package, necessary to parse a package-relative label (":foo").  */
    interface PackageContext : RepoContext {
        fun packageFragment(): PathFragment?

        fun packageIdentifier(): PackageIdentifier? {
            return PackageIdentifier.Companion.create(currentRepo(), packageFragment())
        }

        companion object {
            fun of(
                currentPackage: PackageIdentifier,
                repoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?
            ): PackageContext {
                return AutoValue_Label_PackageContextImpl(
                    currentPackage.getRepository(), repoMapping, currentPackage.getPackageFragment()
                )
            }
        }
    }

    @AutoValue
    internal abstract class PackageContextImpl : PackageContext

    /** Records repo mapping entries used by [.parseWithPackageContext].  */
    interface RepoMappingRecorder {
        fun record(fromRepo: RepositoryName?, apparentRepoName: String?, canonicalRepoName: RepositoryName?)

        fun record(entries: com.google.common.collect.Table<RepositoryName?, String?, RepositoryName?>) {
            for (cell in entries.cellSet()) {
                record(cell.getRowKey(), cell.getColumnKey(), cell.getValue())
            }
        }

        fun storeInThread(thread: net.starlark.java.eval.StarlarkThread) {
            thread.setThreadLocal<RepoMappingRecorder?>(RepoMappingRecorder::class.java, this)
        }
    }

    /**
     * A [RepoMappingRecorder] backed by a [Table] that is used for BUILD and .bzl load
     * threads.
     */
    class SimpleRepoMappingRecorder : RepoMappingRecorder {
        var entries: com.google.common.collect.Table<RepositoryName?, String?, RepositoryName?> =
            com.google.common.collect.HashBasedTable.create<RepositoryName?, String?, RepositoryName?>()

        override fun record(
            fromRepo: RepositoryName?, apparentRepoName: String?, canonicalRepoName: RepositoryName?
        ) {
            entries.put(fromRepo, apparentRepoName, canonicalRepoName)
        }

        fun recordedEntries(): com.google.common.collect.ImmutableTable<RepositoryName?, String?, RepositoryName?> {
            return com.google.common.collect.ImmutableTable.builder<RepositoryName?, String?, RepositoryName?>()
                .orderRowsBy(java.util.Comparator.comparing<RepositoryName?, String?>(java.util.function.Function { obj: RepositoryName? -> obj.getName() }))
                .orderColumnsBy(java.util.Comparator.naturalOrder<String?>())
                .putAll(entries)
                .buildOrThrow()
        }
    }

    /** The name and repository of the package.  */
    private val packageIdentifier: PackageIdentifier

    /**
     * Returns the name by which this rule was declared (e.g. `//foo/bar:baz` returns `baz`).
     */
    /** The name of the target within the package. Canonical.  */
    @kotlin.jvm.JvmField
    @get:net.starlark.java.annot.StarlarkMethod(
        name = "name", structField = true, doc = ("The name of the target referred to by this label. For instance:<br>"
                + "<pre class=language-python>Label(\"@@foo//pkg/foo:abc\").name == \"abc\"</pre>")
    )
    val name: String

    init {
        com.google.common.base.Preconditions.checkNotNull<PackageIdentifier?>(packageIdentifier)
        com.google.common.base.Preconditions.checkNotNull<String?>(name)

        this.packageIdentifier = packageIdentifier
        this.name = name
    }

    fun getPackageIdentifier(): PackageIdentifier {
        return packageIdentifier
    }

    val repository: RepositoryName?
        get() = packageIdentifier.getRepository()

    @get:net.starlark.java.annot.StarlarkMethod(
        name = "package",
        structField = true,
        doc = ("The name of the package containing the target referred to by this label, without the"
                + " repository name. For instance:<br><pre"
                + " class=language-python>Label(\"@@repo//pkg/foo:abc\").package =="
                + " \"pkg/foo\"</pre>")
    )
    val packageName: String?
        /**
         * Returns the name of the package in which this rule was declared (e.g. `//file/base:fileutils_test` returns `file/base`).
         */
        get() = packageIdentifier.getPackageFragment().getPathString()

    /**
     * Returns the execution root for the workspace, relative to the execroot (e.g., for label
     * `@repo//pkg:b`, it will returns `external/repo/pkg` and for label `//pkg:a`,
     * it will returns an empty string.
     * 
     */
    @net.starlark.java.annot.StarlarkMethod(
        name = "workspace_root",
        structField = true,
        doc = ("Returns the execution root for the repository containing the target referred to by this"
                + " label, relative to the execroot. For instance:<br><pre"
                + " class=language-python>Label(\"@repo//pkg/foo:abc\").workspace_root =="
                + " \"external/repo\"</pre>"),
        useStarlarkSemantics = true
    )
    @Deprecated(
        """The sole purpose of this method is to implement the workspace_root method. For
        other purposes, use {@link RepositoryName#getExecPath} instead."""
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun getWorkspaceRootForStarlarkOnly(semantics: net.starlark.java.eval.StarlarkSemantics): String? {
        checkRepoVisibilityForStarlark("workspace_root")
        return packageIdentifier
            .getRepository()
            .getExecPath(semantics.getBool(BuildLanguageOptions.EXPERIMENTAL_SIBLING_REPOSITORY_LAYOUT))
            .toString()
    }

    val packageFragment: PathFragment
        /**
         * Returns the path fragment of the package in which this rule was declared (e.g. `//file/base:fileutils_test` returns `file/base`).
         * 
         * 
         * This is **not** suitable for inferring a path under which files related to a rule with
         * this label will be under the exec root, in particular, it won't work for rules in external
         * repositories.
         */
        get() = packageIdentifier.getPackageFragment()

    /**
     * Returns the label as a path fragment, using the package and the label name.
     * 
     * 
     * Make sure that the label refers to a file. Non-file labels do not necessarily have
     * PathFragment representations.
     * 
     * 
     * The package's repository is not included in the returned fragment. To account for it,
     * compose this with `#getRepository()#getExecPath`.
     */
    fun toPathFragment(): PathFragment? {
        // PathFragments are normalized, so if we do this on a non-file target named '.'
        // then the package would be returned. Detect this and throw.
        // A target named '.' can never refer to a file.
        com.google.common.base.Preconditions.checkArgument(name != ".")
        return packageIdentifier.getPackageFragment().getRelative(name)
    }

    /**
     * Renders this label in canonical form.
     * 
     * 
     * invariant: `parseCanonical(x.toString()).equals(x)`. Note that using [ ][.parseWithPackageContext] or [.parseWithRepoContext] on the returned string might not
     * yield the same label! For that, use [.getUnambiguousCanonicalForm].
     */
    override fun toString(): String {
        return this.canonicalForm
    }

    val canonicalForm: String
        /**
         * Renders this label in canonical form.
         * 
         * 
         * invariant: `parseCanonical(x.getCanonicalForm()).equals(x)`. Note that using [ ][.parseWithPackageContext] or [.parseWithRepoContext] on the returned string might not
         * yield the same label! For that, use [.getUnambiguousCanonicalForm].
         */
        get() = packageIdentifier.getCanonicalForm() + ":" + name

    val unambiguousCanonicalForm: String
        /**
         * Returns an absolutely unambiguous canonical form for this label. Parsing this string in any
         * environment should yield the same label (as in `Label.parse*(x.getUnambiguousCanonicalForm(), ...).equals(x)`).
         */
        get() = packageIdentifier.getUnambiguousCanonicalForm() + ":" + name

    /**
     * Returns a full label string that is suitable for display, i.e., it resolves to this label when
     * parsed in the context of the main repository and has a repository part that is as simple as
     * possible.
     * 
     * @param mainRepositoryMapping the [RepositoryMapping] of the main repository
     * @return analogous to [PackageIdentifier.getDisplayForm]
     */
    fun getDisplayForm(mainRepositoryMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?): String {
        return packageIdentifier.getDisplayForm(mainRepositoryMapping) + ":" + name
    }

    /**
     * Returns a shorthand label string that is suitable for display, i.e. in addition to simplifying
     * the repository part, labels of the form `[@repo]//foo/bar:bar` are simplified to the
     * shorthand form `[@repo]//foo/bar`, and labels of the form `@repo//:repo` and
     * `@@repo//:repo` are simplified to `@repo`. The returned shorthand string resolves
     * back to this label only when parsed in the context of the main repository whose repository
     * mapping is provided.
     * 
     * 
     * Unlike [.getDisplayForm], this method elides the name part of the label if possible.
     * 
     * @param mainRepositoryMapping the [RepositoryMapping] of the main repository
     */
    fun getShorthandDisplayForm(mainRepositoryMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?): String {
        if (this.packageFragment.getBaseName() == name) {
            return packageIdentifier.getDisplayForm(mainRepositoryMapping)
        } else if (this.packageFragment.getBaseName().isEmpty()) {
            val repositoryDisplayForm: String =
                getPackageIdentifier().getRepository().getDisplayForm(mainRepositoryMapping)
            // Simplify @foo//:foo or @@foo//:foo to @foo; note that `name` cannot start with '@'
            if (repositoryDisplayForm == "@" + name || repositoryDisplayForm == "@@" + name) {
                return repositoryDisplayForm
            }
        }
        return getDisplayForm(mainRepositoryMapping)
    }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    @get:Deprecated("")
    @get:net.starlark.java.annot.StarlarkMethod(
        name = "workspace_name",
        structField = true,
        doc = ("<strong>Deprecated.</strong> The field name \"workspace name\" is a misnomer here; use"
                + " the identically-behaving <a href=\"#repo_name\"><code>Label.repo_name</code></a>"
                + " instead.<p>The canonical name of the repository containing the target referred to"
                + " by this label, without any leading at-signs (<code>@</code>). For instance, <pre"
                + " class=language-python>Label(\"@@foo//bar:baz\").workspace_name == \"foo\"</pre>"),
        enableOnlyWithFlag = BuildLanguageOptions.INCOMPATIBLE_ENABLE_DEPRECATED_LABEL_APIS
    )
    val workspaceName: String?
        /** Return the name of the repository label refers to without the leading `at` symbol.  */
        get() {
            checkRepoVisibilityForStarlark("workspace_name")
            return packageIdentifier.getRepository().getName()
        }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    @get:net.starlark.java.annot.StarlarkMethod(
        name = "repo_name",
        structField = true,
        doc = ("The canonical name of the repository containing the target referred to by this label,"
                + " without any leading at-signs (<code>@</code>). For instance, <pre"
                + " class=language-python>Label(\"@@foo//bar:baz\").repo_name == \"foo\"</pre>")
    )
    val repoName: String?
        /** Return the name of the repository label refers to without the leading `at` symbol.  */
        get() {
            checkRepoVisibilityForStarlark("repo_name")
            return packageIdentifier.getRepository().getName()
        }

    /**
     * Returns a label in the same package as this label with the given target name.
     * 
     * @throws LabelSyntaxException if `targetName` is not a valid target name
     */
    @net.starlark.java.annot.StarlarkMethod(
        name = "same_package_label",
        doc = "Creates a label in the same package as this label with the given target name.",
        parameters = [net.starlark.java.annot.Param(name = "target_name", doc = "The target name of the new label.")]
    )
    @Throws(LabelSyntaxException::class)
    fun getSamePackageLabel(targetName: String?): Label {
        return com.google.devtools.build.lib.cmdline.Label.Companion.create(packageIdentifier, targetName)
    }

    /**
     * Resolves a relative or absolute label name.
     * 
     * 
     * For example: `:quux` relative to `//foo/bar:baz` is `//foo/bar:quux`;
     * `//wiz:quux` relative to `//foo/bar:baz` is `//wiz:quux`.
     * 
     * @param relName the relative label name; must be non-empty.
     * @param thread the Starlark thread.
     */
    @net.starlark.java.annot.StarlarkMethod(
        name = "relative",
        doc = ("<strong>Deprecated.</strong> This method behaves surprisingly when used with an argument"
                + " containing an apparent repo name. Prefer <a"
                + " href=\"#same_package_label\"><code>Label.same_package_label()</code></a>, <a"
                + " href=\"../toplevel/native.html#package_relative_label\"><code>native.package_relative_label()</code></a>,"
                + " <a href=\"ctx.html#package_relative_label\"><code>ctx.package_relative_label()</code></a>,"
                + " or <a href=\"#Label\"><code>Label()</code></a> instead.<p>Resolves a label that"
                + " is either absolute (starts with <code>//</code>) or relative to the current"
                + " package. If this label is in a remote repository, the argument will be resolved"
                + " relative to that repository. If the argument contains a repository name, the"
                + " current label is ignored and the argument is returned as-is, except that the"
                + " repository name is rewritten if it is in the current repository mapping. Reserved"
                + " labels will also be returned as-is.<br>For example:<br><pre"
                + " class=language-python>\n"
                + "Label(\"//foo/bar:baz\").relative(\":quux\") == Label(\"//foo/bar:quux\")\n"
                + "Label(\"//foo/bar:baz\").relative(\"//wiz:quux\") == Label(\"//wiz:quux\")\n"
                + "Label(\"@repo//foo/bar:baz\").relative(\"//wiz:quux\") =="
                + " Label(\"@repo//wiz:quux\")\n"
                + "Label(\"@repo//foo/bar:baz\").relative(\"//visibility:public\") =="
                + " Label(\"//visibility:public\")\n"
                + "Label(\"@repo//foo/bar:baz\").relative(\"@other//wiz:quux\") =="
                + " Label(\"@other//wiz:quux\")\n"
                + "</pre><p>If the repository mapping passed in is <code>{'@other' :"
                + " '@remapped'}</code>, then the following remapping will take place:<br><pre"
                + " class=language-python>\n"
                + "Label(\"@repo//foo/bar:baz\").relative(\"@other//wiz:quux\") =="
                + " Label(\"@remapped//wiz:quux\")\n"
                + "</pre>"),
        parameters = [net.starlark.java.annot.Param(
            name = "relName",
            doc = "The label that will be resolved relative to this one."
        )],
        enableOnlyWithFlag = BuildLanguageOptions.INCOMPATIBLE_ENABLE_DEPRECATED_LABEL_APIS,
        useStarlarkThread = true
    )
    @Deprecated("")
    @Throws(LabelSyntaxException::class)
    fun getRelative(relName: String, thread: net.starlark.java.eval.StarlarkThread?): Label {
        return com.google.devtools.build.lib.cmdline.Label.Companion.parseWithPackageContext(
            relName,
            PackageContext.Companion.of(
                packageIdentifier, BazelModuleContext.Companion.ofInnermostBzlOrThrow(thread).repoMapping()
            )
        )
    }

    override fun functionName(): SkyFunctionName {
        return com.google.devtools.build.lib.cmdline.Label.Companion.TRANSITIVE_TRAVERSAL
    }

    override fun hashCode(): Int {
        return com.google.devtools.build.lib.cmdline.Label.Companion.hashCode(name, packageIdentifier)
    }

    /** Two labels are equal iff both their name and their package name are equal.  */
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is Label) {
            return false
        }
        val otherLabel = other
        // Package identifiers are (weakly) interned so we compare them first.
        return packageIdentifier == otherLabel.packageIdentifier && name == otherLabel.name
    }

    /**
     * Defines the order between labels.
     * 
     * 
     * Labels are ordered primarily by package name and secondarily by target name. Both components
     * are ordered lexicographically. Thus `//a:b/c` comes before `//a/b:a`, i.e. the
     * position of the colon is significant to the order.
     */
    override fun compareTo(other: Label): Int {
        if (this === other) {
            return 0
        }
        return com.google.common.collect.ComparisonChain.start()
            .compare(packageIdentifier, other.packageIdentifier)
            .compare(name, other.name)
            .result()
    }

    val isImmutable: Boolean
        get() = true

    private fun toStringInternal(semantics: net.starlark.java.eval.StarlarkSemantics): String {
        if (this.repository.isMain()
            && !semantics.getBool(
                BuildLanguageOptions.INCOMPATIBLE_UNAMBIGUOUS_LABEL_STRINGIFICATION
            )
        ) {
            // If this label is in the main repo and we're not using unambiguous label stringification,
            // the result should always be "//foo:bar".
            return this.canonicalForm
        }

        // Otherwise, we use canonical label literal syntax here and prepend an extra '@'.
        // So the result looks like "@@//foo:bar" for the main repo and "@@foo+//bar:quux" for
        // other repos.
        return this.unambiguousCanonicalForm
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics) {
        printer.append("Label(")
        printer.repr(toStringInternal(semantics), semantics)
        printer.append(")")
    }

    override fun debugPrint(printer: net.starlark.java.eval.Printer, thread: net.starlark.java.eval.StarlarkThread) {
        val threadContext: StarlarkThreadContext? =
            thread.getThreadLocal<StarlarkThreadContext?>(StarlarkThreadContext::class.java)
        var mainRepoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping? = null
        if (threadContext != null) {
            try {
                mainRepoMapping = threadContext.getMainRepoMapping()
            } catch (e: java.lang.InterruptedException) {
                // ignore
            }
        }
        printer.append(getShorthandDisplayForm(mainRepoMapping))
    }

    override fun str(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics) {
        printer.append(toStringInternal(semantics))
    }

    public override fun expandToCommandLine(): String {
        // TODO(wyv): Consider using StarlarkSemantics here too for optional unambiguity.
        return this.canonicalForm
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun checkRepoVisibilityForStarlark(method: String?) {
        if (!this.repository.isVisible()) {
            throw net.starlark.java.eval.Starlark.errorf("'%s' is not allowed on invalid Label %s", method, this)
        }
    }

    /** [PooledInterner] for [Label]s.  */
    class LabelInterner : com.google.devtools.build.lib.concurrent.PooledInterner<Label>() {
        private val interningLocks: com.google.common.util.concurrent.Striped<ReadWriteLock?> =
            com.google.common.util.concurrent.Striped.readWriteLock(com.google.devtools.build.lib.concurrent.BlazeInterners.concurrencyLevel())

        /**
         * Returns the read lock for [LabelInterner] to guard looking up [Label] instance
         * from either the pool or weak interner.
         */
        fun getLockForLabelLookup(label: Label): java.util.concurrent.locks.Lock? {
            return interningLocks.get(label.getPackageIdentifier()).readLock()
        }

        /**
         * Returns the write lock to guard transfer [Label] from weak interner to the in-memory
         * [com.google.devtools.build.lib.packages.Package] node when it is done evaluation in
         * `SkyframeProgressReceiver`.
         * 
         * @param packageIdentifier The [PackageIdentifier] of the done package node.
         */
        fun getLockForLabelTransferToPool(packageIdentifier: PackageIdentifier): java.util.concurrent.locks.Lock? {
            return interningLocks.get(packageIdentifier).writeLock()
        }

        val pool: com.google.devtools.build.lib.concurrent.PooledInterner.Pool<Label?>?
            get() = globalPool

        fun enabled(): Boolean {
            return globalPool != null
        }

        companion object {
            var globalPool: com.google.devtools.build.lib.concurrent.PooledInterner.Pool<Label?>? = null

            /**
             * Sets the [Pool] to be used for interning.
             * 
             * 
             * The pool is strongly retained until another pool is set. `null` can be passed to
             * clear the global pool.
             */
            @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible
            fun setGlobalPool(pool: com.google.devtools.build.lib.concurrent.PooledInterner.Pool<Label?>?) {
                // No synchronization is needed. Setting global pool is guaranteed to happen sequentially
                // since only one build can happen at the same time.
                globalPool = pool
            }
        }
    }

    private class LabelDeferredCodec : DeferredObjectCodec<Label?>() {
        override fun autoRegister(): Boolean {
            return false
        }

        val encodedClass: java.lang.Class<Label?>
            get() = com.google.devtools.build.lib.cmdline.Label::class.java

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun serialize(context: SerializationContext, obj: Label?, codedOut: CodedOutputStream?) {
            context.serializeLeaf<Label?>(
                obj,
                com.google.devtools.build.lib.cmdline.Label.Companion.labelCodec(),
                codedOut
            )
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?
        ): DeferredValue<Label?> {
            val value: Label? = context.deserializeLeaf<Label?>(
                codedIn,
                com.google.devtools.build.lib.cmdline.Label.Companion.labelCodec()
            )
            return DeferredValue { value }
        }

        companion object {
            private val INSTANCE = LabelDeferredCodec()
        }
    }

    @com.google.errorprone.annotations.Keep
    private class Codec : LeafObjectCodec<Label?>() {
        val encodedClass: java.lang.Class<Label?>
            get() = com.google.devtools.build.lib.cmdline.Label::class.java

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun serialize(context: LeafSerializationContext, obj: Label, codedOut: CodedOutputStream?) {
            context.serializeLeaf<PackageIdentifier?>(
                obj.getPackageIdentifier(),
                PackageIdentifier.Companion.packageIdentifierCodec(),
                codedOut
            )
            context.serializeLeaf<String?>(obj.name, UnsafeStringCodec.stringCodec(), codedOut)
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun deserialize(context: LeafDeserializationContext, codedIn: CodedInputStream?): Label {
            val pkgId: PackageIdentifier = context.deserializeLeaf<PackageIdentifier>(
                codedIn,
                PackageIdentifier.Companion.packageIdentifierCodec()
            )
            val name: String = context.deserializeLeaf<String>(codedIn, UnsafeStringCodec.stringCodec())
            return com.google.devtools.build.lib.cmdline.Label.Companion.createUnvalidated(pkgId, name)
        }

        companion object {
            private val INSTANCE: Codec = com.google.devtools.build.lib.cmdline.Label.Codec()
        }
    }

    companion object {
        /**
         * Package names that aren't made relative to the current repository because they mean special
         * things to Bazel.
         */
        private val ABSOLUTE_PACKAGE_NAMES: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>( // Used for select's `//conditions:default` label (not a target)
                "conditions",  // Used for the public and private visibility labels (not targets)
                "visibility"
            )

        // Intern "__pkg__" and "__subpackages__" pseudo-targets, which appears in labels used for
        // visibility specifications. This saves a couple tenths of a percent of RAM off the loading
        // phase. Note that general interning of all values for `name` is *not* beneficial. See
        // Google-internal cl/386077913 and cl/185394812 for more context.
        private const val PKG_VISIBILITY_NAME = "__pkg__"
        private const val SUBPACKAGES_VISIBILITY_NAME = "__subpackages__"

        @kotlin.jvm.JvmField
        val TRANSITIVE_TRAVERSAL: SkyFunctionName = SkyFunctionName.createHermetic("TRANSITIVE_TRAVERSAL")

        private val interner = LabelInterner()

        @kotlin.jvm.JvmStatic
        val labelInterner: LabelInterner
            get() = com.google.devtools.build.lib.cmdline.Label.Companion.interner

        /**
         * Parses a raw label string that contains the canonical form of a label. It must be of the form
         * `[@repo]//foo/bar[:quux]`. If the `@repo` part is present, it must be a canonical
         * repo name, otherwise the label will be assumed to be in the main repo.
         */
        @kotlin.jvm.JvmStatic
        @Throws(LabelSyntaxException::class)
        fun parseCanonical(raw: String): Label {
            val parts: Parts = Parts.Companion.parse(raw)
            parts.checkPkgDoesNotEndWithTripleDots()
            parts.checkPkgIsAbsolute()
            val repoName: RepositoryName? =
                if (parts.repo() == null) RepositoryName.Companion.MAIN else RepositoryName.Companion.createUnvalidated(
                    parts.repo()
                )
            return com.google.devtools.build.lib.cmdline.Label.Companion.createUnvalidated(
                PackageIdentifier.Companion.create(repoName, PathFragment.create(parts.pkg())), parts.target()
            )
        }

        /** Like [.parseCanonical], but throws an unchecked exception instead.  */
        @kotlin.jvm.JvmStatic
        fun parseCanonicalUnchecked(raw: String): Label {
            try {
                return com.google.devtools.build.lib.cmdline.Label.Companion.parseCanonical(raw)
            } catch (e: LabelSyntaxException) {
                throw java.lang.IllegalArgumentException(e)
            }
        }

        /** Computes the repo name for the label, within the context of a current repo.  */
        private fun computeRepoNameWithRepoContext(
            parts: Parts, repoContext: RepoContext
        ): RepositoryName? {
            if (parts.repo() == null) {
                // Certain package names when used without a "@" part are always absolutely in the main repo,
                // disregarding the current repo and repo mappings.
                if (com.google.devtools.build.lib.cmdline.Label.Companion.ABSOLUTE_PACKAGE_NAMES.contains(parts.pkg())) {
                    return RepositoryName.Companion.MAIN
                }
                return repoContext.currentRepo()
            }
            if (parts.repoIsCanonical()) {
                // This label uses the canonical label literal syntax starting with two @'s ("@@foo//bar").
                return RepositoryName.Companion.createUnvalidated(parts.repo())
            }
            return repoContext.repoMapping().get(parts.repo())
        }

        /**
         * Parses a raw label string within the context of a current repo. It must be of the form `[@repo]//foo/bar[:quux]`. If the `@repo` part is present, it will undergo `repoContext.repoMapping()`, otherwise the label will be assumed to be in `repoContext.currentRepo()`.
         */
        @kotlin.jvm.JvmStatic
        @Throws(LabelSyntaxException::class)
        fun parseWithRepoContext(raw: String, repoContext: RepoContext): Label {
            val parts: Parts = Parts.Companion.parse(raw)
            parts.checkPkgDoesNotEndWithTripleDots()
            parts.checkPkgIsAbsolute()
            val repoName: RepositoryName? =
                com.google.devtools.build.lib.cmdline.Label.Companion.computeRepoNameWithRepoContext(parts, repoContext)
            return com.google.devtools.build.lib.cmdline.Label.Companion.createUnvalidated(
                PackageIdentifier.Companion.create(repoName, PathFragment.create(parts.pkg())), parts.target()
            )
        }

        /**
         * Parses a raw label string within the context of a current package. It can be of a
         * package-relative form (`:quux`). Otherwise, it must be of the form `[@repo]//foo/bar[:quux]`. If the `@repo` part is present, it will undergo `packageContext.repoMapping()`, otherwise the label will be assumed to be in the repo of `packageContext.currentRepo()`.
         */
        @kotlin.jvm.JvmStatic
        @Throws(LabelSyntaxException::class)
        fun parseWithPackageContext(raw: String, packageContext: PackageContext): Label {
            return com.google.devtools.build.lib.cmdline.Label.Companion.parseWithPackageContextInternal(
                Parts.Companion.parse(
                    raw
                ), packageContext
            )
        }

        @kotlin.jvm.JvmStatic
        @Throws(LabelSyntaxException::class)
        fun parseWithPackageContext(
            raw: String, packageContext: PackageContext, repoMappingRecorder: RepoMappingRecorder?
        ): Label {
            val parts: Parts = Parts.Companion.parse(raw)
            val parsed: Label = com.google.devtools.build.lib.cmdline.Label.Companion.parseWithPackageContextInternal(
                parts,
                packageContext
            )
            if (repoMappingRecorder != null && parts.repo() != null && !parts.repoIsCanonical()) {
                repoMappingRecorder.record(
                    packageContext.currentRepo(), parts.repo(), parsed.repository
                )
            }
            return parsed
        }

        @Throws(LabelSyntaxException::class)
        private fun parseWithPackageContextInternal(parts: Parts, packageContext: PackageContext): Label {
            parts.checkPkgDoesNotEndWithTripleDots()
            // pkg is either absolute or empty
            if (!parts.pkg().isEmpty()) {
                parts.checkPkgIsAbsolute()
            }
            val repoName: RepositoryName? =
                com.google.devtools.build.lib.cmdline.Label.Companion.computeRepoNameWithRepoContext(
                    parts,
                    packageContext
                )
            val pkgFragment: PathFragment? =
                if (parts.pkgIsAbsolute()) PathFragment.create(parts.pkg()) else packageContext.packageFragment()
            return com.google.devtools.build.lib.cmdline.Label.Companion.createUnvalidated(
                PackageIdentifier.Companion.create(
                    repoName,
                    pkgFragment
                ), parts.target()
            )
        }

        /**
         * Factory for Labels from separate components.
         * 
         * @param packageName The name of the package. The package name does **not** include `//`. Must be valid according to [LabelValidator.validatePackageName].
         * @param targetName The name of the target within the package. Must be valid according to [     ][LabelValidator.validateTargetName].
         * @throws LabelSyntaxException if either of the arguments was invalid.
         */
        @kotlin.jvm.JvmStatic
        @Throws(LabelSyntaxException::class)
        fun create(packageName: String, targetName: String?): Label {
            return com.google.devtools.build.lib.cmdline.Label.Companion.createUnvalidated(
                PackageIdentifier.Companion.parse(packageName),
                LabelParser.validateAndProcessTargetName(packageName, targetName,  /* pkgEndsWithTripleDots= */false)
            )
        }

        /**
         * Similar factory to above, but takes a package identifier to allow external repository labels to
         * be created.
         */
        @Throws(LabelSyntaxException::class)
        fun create(packageId: PackageIdentifier, targetName: String?): Label {
            return com.google.devtools.build.lib.cmdline.Label.Companion.createUnvalidated(
                packageId,
                LabelParser.validateAndProcessTargetName(
                    packageId.getPackageFragment().getPathString(),
                    targetName,  /* pkgEndsWithTripleDots= */
                    false
                )
            )
        }

        /**
         * Similar factory to above, but does not perform target name validation.
         * 
         * 
         * Only call this method if you know what you're doing; in particular, don't call it on
         * arbitrary `name` inputs
         */
        fun createUnvalidated(packageIdentifier: PackageIdentifier, name: String): Label {
            return com.google.devtools.build.lib.cmdline.Label.Companion.interner.intern(
                com.google.devtools.build.lib.cmdline.Label(
                    packageIdentifier,
                    com.google.devtools.build.lib.cmdline.Label.Companion.internIfConstantName(name)
                )
            )
        }

        fun internIfConstantName(name: String): String {
            if (name == com.google.devtools.build.lib.cmdline.Label.Companion.PKG_VISIBILITY_NAME) {
                return com.google.devtools.build.lib.cmdline.Label.Companion.PKG_VISIBILITY_NAME
            }
            if (name == com.google.devtools.build.lib.cmdline.Label.Companion.SUBPACKAGES_VISIBILITY_NAME) {
                return com.google.devtools.build.lib.cmdline.Label.Companion.SUBPACKAGES_VISIBILITY_NAME
            }
            return name
        }

        /**
         * Specialization of [Arrays.hashCode] that does not require constructing a 2-element
         * array.
         */
        private fun hashCode(obj1: Any?, obj2: Any?): Int {
            val result = 31 + (if (obj1 == null) 0 else obj1.hashCode())
            return 31 * result + (if (obj2 == null) 0 else obj2.hashCode())
        }

        /**
         * Returns a suitable string for the user-friendly representation of the Label. Works even if the
         * argument is null.
         */
        @kotlin.jvm.JvmStatic
        fun print(label: Label?): String {
            return if (label == null) "(unknown)" else label.toString()
        }

        /**
         * Returns a [PathFragment] corresponding to the directory in which `label` would
         * reside, if it were interpreted to be a path.
         */
        @kotlin.jvm.JvmStatic
        fun getContainingDirectory(label: Label): PathFragment? {
            val pkg: PathFragment = label.packageFragment
            val name = label.name
            if (name == ".") {
                return pkg
            }
            if (PathFragment.isNormalizedRelativePath(name) && !PathFragment.containsSeparator(name)) {
                // Optimize for the common case of a label like '//pkg:target'.
                return pkg
            }
            return pkg.getRelative(name).getParentDirectory()
        }

        @kotlin.jvm.JvmStatic
        fun labelCodec(): Codec {
            return com.google.devtools.build.lib.cmdline.Label.Codec.Companion.INSTANCE
        }

        @kotlin.jvm.JvmStatic
        fun deferredCodec(): DeferredObjectCodec<Label?> {
            return LabelDeferredCodec.Companion.INSTANCE
        }
    }
}
