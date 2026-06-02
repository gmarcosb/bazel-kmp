// Copyright 2021 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.bazel.bzlmod.ArchiveRepoSpecBuilder
import com.google.devtools.build.lib.bazel.bzlmod.CompiledModuleFile
import com.google.devtools.build.lib.bazel.bzlmod.GitRepoSpecBuilder
import com.google.devtools.build.lib.bazel.bzlmod.InterimModule
import com.google.devtools.build.lib.bazel.bzlmod.LocalPathRepoSpecs
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.bazel.bzlmod.ModuleThreadContext
import com.google.devtools.build.lib.bazel.bzlmod.ModuleThreadContext.ModuleExtensionUsageBuilder
import com.google.devtools.build.lib.bazel.bzlmod.MultipleVersionOverride
import com.google.devtools.build.lib.bazel.bzlmod.NonRegistryOverride
import com.google.devtools.build.lib.bazel.bzlmod.RepoSpec
import com.google.devtools.build.lib.bazel.bzlmod.SingleVersionOverride
import com.google.devtools.build.lib.cmdline.LabelSyntaxException
import com.google.devtools.build.lib.cmdline.PackageIdentifier
import com.google.devtools.build.lib.cmdline.RepositoryName
import com.google.devtools.build.lib.packages.StarlarkExportable
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions
import com.google.devtools.build.lib.vfs.PathFragment

/** A collection of global Starlark build API functions that apply to MODULE.bazel files.  */
@com.google.devtools.build.docgen.annot.GlobalMethods(environment = com.google.devtools.build.docgen.annot.GlobalMethods.Environment.MODULE)
class ModuleFileGlobals {
    @net.starlark.java.annot.StarlarkMethod(
        name = "module",
        doc = ("Declares certain properties of the Bazel module represented by the current Bazel repo."
                + " These properties are either essential metadata of the module (such as the name"
                + " and version), or affect behavior of the current module and its dependents.  <p>It"
                + " should be called at most once, and if called, it must be the very first directive"
                + " in the MODULE.bazel file. It can be omitted only if this module is the root"
                + " module (as in, if it's not going to be depended on by another module)."),
        parameters = [net.starlark.java.annot.Param(
            name = "name", doc = ("The name of the module. Can be omitted only if this module is the root module (as"
                    + " in, if it's not going to be depended on by another module). A valid module"
                    + " name must: 1) only contain lowercase letters (a-z), digits (0-9), dots (.),"
                    + " hyphens (-), and underscores (_); 2) begin with a lowercase letter; 3) end"
                    + " with a lowercase letter or digit."), named = true, positional = false, defaultValue = "''"
        ), net.starlark.java.annot.Param(
            name = "version", doc = ("The version of the module. Can be omitted only if this module is the root module"
                    + " (as in, if it's not going to be depended on by another module). The version"
                    + " must be in a relaxed SemVer format; see <a"
                    + " href=\"/external/module#version_format\">the documentation</a> for more"
                    + " details."), named = true, positional = false, defaultValue = "''"
        ), net.starlark.java.annot.Param(
            name = "compatibility_level",
            doc = "Deprecated. This is now a no-op and has no effect.",
            named = true,
            positional = false,
            defaultValue = "-1"
        ), net.starlark.java.annot.Param(
            name = "repo_name",
            doc = ("The name of the repository representing this module, as seen by the module itself."
                    + " By default, the name of the repo is the name of the module. This can be"
                    + " specified to ease migration for projects that have been using a repo name"
                    + " for itself that differs from its module name."),
            named = true,
            positional = false,
            defaultValue = "''"
        ), net.starlark.java.annot.Param(
            name = "bazel_compatibility",
            doc = ("A list of bazel versions that allows users to declare which Bazel versions"
                    + " are compatible with this module. It does NOT affect dependency resolution,"
                    + " but bzlmod will use this information to check if your current Bazel version"
                    + " is compatible. The format of this value is a string of some constraint"
                    + " values separated by comma. Three constraints are supported: <=X.X.X: The"
                    + " Bazel version must be equal or older than X.X.X. Used when there is a known"
                    + " incompatible change in a newer version. >=X.X.X: The Bazel version must be"
                    + " equal or newer than X.X.X.Used when you depend on some features that are"
                    + " only available since X.X.X. -X.X.X: The Bazel version X.X.X is not"
                    + " compatible. Used when there is a bug in X.X.X that breaks you, but fixed in"
                    + " later versions."),
            named = true,
            positional = false,
            allowedTypes = [net.starlark.java.annot.ParamType(type = Iterable::class, generic1 = String::class)],
            defaultValue = "[]"
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun module(
        name: String,
        version: String,
        compatibilityLevel: net.starlark.java.eval.StarlarkInt,
        repoName: String,
        bazelCompatibility: Iterable<*>?,
        thread: net.starlark.java.eval.StarlarkThread
    ) {
        var repoName = repoName
        val context: ModuleThreadContext = ModuleThreadContext.Companion.fromOrFail(thread, "module()")
        if (context.isModuleCalled()) {
            throw net.starlark.java.eval.Starlark.errorf("the module() directive can only be called once")
        }
        if (compatibilityLevel.toInt("compatibility_level") != -1
            && context.getModuleBuilder().getKey() == ModuleKey.Companion.ROOT
        ) {
            context.addWarning(
                com.google.devtools.build.lib.events.Event.warn(
                    thread.getCallerLocation(),
                    "The attribute 'compatibility_level' in module() is a no-op and will be removed in a"
                            + " future Bazel release. Please remove it from your MODULE.bazel file."
                )
            )
        }
        if (context.hadNonModuleCall()) {
            throw net.starlark.java.eval.Starlark.errorf("if module() is called, it must be called before any other functions")
        }
        context.setModuleCalled()
        if (!name.isEmpty()) {
            validateModuleName(name)
        }
        if (repoName.isEmpty()) {
            repoName = name
            context.addRepoNameUsage(name, "as the current module name", thread.getCallStack())
        } else {
            RepositoryName.validateUserProvidedRepoName(repoName)
            context.addRepoNameUsage(repoName, "as the module's own repo name", thread.getCallStack())
        }
        val parsedVersion: com.google.devtools.build.lib.bazel.bzlmod.Version?
        try {
            parsedVersion = com.google.devtools.build.lib.bazel.bzlmod.Version.Companion.parse(version)
        } catch (e: com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException) {
            throw net.starlark.java.eval.EvalException("Invalid version in module()", e)
        }
        context
            .getModuleBuilder()
            .setName(name)
            .setVersion(parsedVersion)
            .addBazelCompatibilityValues(
                checkAllCompatibilityVersions(bazelCompatibility, "bazel_compatibility")
            )
            .setRepoName(repoName)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "bazel_dep",
        doc = "Declares a direct dependency on another Bazel module.",
        parameters = [net.starlark.java.annot.Param(
            name = "name",
            doc = "The name of the module to be added as a direct dependency.",
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = "version",
            doc = "The version of the module to be added as a direct dependency.",
            named = true,
            positional = false,
            defaultValue = "''"
        ), net.starlark.java.annot.Param(
            name = "max_compatibility_level",
            doc = "Deprecated. This is now a no-op and has no effect.",
            named = true,
            positional = false,
            defaultValue = "-1"
        ), net.starlark.java.annot.Param(
            name = "repo_name",
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            doc = """
                The name of the external repo representing this dependency. This is by default the
                name of the module. Can be set to <code>None</code> to make this dependency a
                "<em>nodep</em>" dependency: in this case, this <code>bazel_dep</code> specification
                is only honored if the target module already exists in the dependency graph by some
                other means.
                
                """.trimIndent(),
            named = true,
            positional = false,
            defaultValue = "''"
        ), net.starlark.java.annot.Param(
            name = "dev_dependency",
            doc = "If true, this dependency will be ignored if the current module is not the root"
                    + " module or <code>--ignore_dev_dependency</code> is enabled.",
            named = true,
            positional = false,
            defaultValue = "False"
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun bazelDep(
        name: String,
        version: String,
        maxCompatibilityLevel: net.starlark.java.eval.StarlarkInt,
        repoNameArg: Any?,
        devDependency: Boolean,
        thread: net.starlark.java.eval.StarlarkThread
    ) {
        val context: ModuleThreadContext = ModuleThreadContext.Companion.fromOrFail(thread, "bazel_dep()")
        context.setNonModuleCalled()
        validateModuleName(name)
        val parsedVersion: com.google.devtools.build.lib.bazel.bzlmod.Version?
        try {
            parsedVersion = com.google.devtools.build.lib.bazel.bzlmod.Version.Companion.parse(version)
        } catch (e: com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException) {
            throw net.starlark.java.eval.EvalException("Invalid version in bazel_dep()", e)
        }
        if (maxCompatibilityLevel.toInt("max_compatibility_level") != -1
            && context.getModuleBuilder().getKey() == ModuleKey.Companion.ROOT
        ) {
            context.addWarning(
                com.google.devtools.build.lib.events.Event.warn(
                    thread.getCallerLocation(),
                    ("The attribute 'max_compatibility_level' in bazel_dep() is a no-op and will be"
                            + " removed in a future Bazel release. Please remove it from your MODULE.bazel"
                            + " file.")
                )
            )
        }

        val repoName: java.util.Optional<String?> =
            when (repoNameArg) {
                -> java.util.Optional.empty<String?>()
                -> java.util.Optional.of<String?>(name)
                -> {
                    RepositoryName.validateUserProvidedRepoName(s)
                    java.util.Optional.of<String?>(s)
                }

                else -> throw net.starlark.java.eval.Starlark.errorf("internal error: unexpected repoName type")
            }

        if (!(context.shouldIgnoreDevDeps() && devDependency)) {
            context.addDep(repoName, ModuleKey(name, parsedVersion))
        }

        if (repoName.isPresent()) {
            context.addRepoNameUsage(repoName.get(), "by a bazel_dep", thread.getCallStack())
        }
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "register_execution_platforms",
        doc = ("Specifies already-defined execution platforms to be registered when this module is"
                + " selected. Should be absolute <a"
                + " href='https://bazel.build/reference/glossary#target-pattern'>target patterns</a>"
                + " (ie. beginning with either <code>@</code> or <code>//</code>). See <a"
                + " href=\"\${link toolchains}\">toolchain resolution</a> for more information."
                + " Patterns that expand to multiple targets, such as <code>:all</code>, will be"
                + " registered in lexicographical order by name."),
        parameters = [net.starlark.java.annot.Param(
            name = "dev_dependency",
            doc = "If true, the execution platforms will not be registered if the current module is"
                    + " not the root module or `--ignore_dev_dependency` is enabled.",
            named = true,
            positional = false,
            defaultValue = "False"
        )],
        extraPositionals = net.starlark.java.annot.Param(
            name = "platform_labels",
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = String::class
            )],
            doc = "The target patterns to register."
        ),
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun registerExecutionPlatforms(
        devDependency: Boolean,
        platformLabels: net.starlark.java.eval.Sequence<*>?,
        thread: net.starlark.java.eval.StarlarkThread
    ) {
        val context: ModuleThreadContext =
            ModuleThreadContext.Companion.fromOrFail(thread, "register_execution_platforms()")
        context.setNonModuleCalled()
        if (context.shouldIgnoreDevDeps() && devDependency) {
            return
        }
        context
            .getModuleBuilder()
            .addExecutionPlatformsToRegister(
                checkAllAbsolutePatterns(platformLabels, "register_execution_platforms")
            )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "register_toolchains",
        doc = ("Specifies already-defined toolchains to be registered when this module is selected."
                + " Should be absolute <a"
                + " href='https://bazel.build/reference/glossary#target-pattern'>target patterns</a>"
                + " (ie. beginning with either <code>@</code> or <code>//</code>). See <a"
                + " href=\"\${link toolchains}\">toolchain resolution</a> for more information."
                + " Patterns that expand to multiple targets, such as <code>:all</code>, will be"
                + " registered in lexicographical order by target name (not the name of the toolchain"
                + " implementation)."),
        parameters = [net.starlark.java.annot.Param(
            name = "dev_dependency",
            doc = "If true, the toolchains will not be registered if the current module is not the"
                    + " root module or `--ignore_dev_dependency` is enabled.",
            named = true,
            positional = false,
            defaultValue = "False"
        )],
        extraPositionals = net.starlark.java.annot.Param(
            name = "toolchain_labels",
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = String::class
            )],
            doc = "The target patterns to register."
        ),
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun registerToolchains(
        devDependency: Boolean,
        toolchainLabels: net.starlark.java.eval.Sequence<*>?,
        thread: net.starlark.java.eval.StarlarkThread
    ) {
        val context: ModuleThreadContext = ModuleThreadContext.Companion.fromOrFail(thread, "register_toolchains()")
        context.setNonModuleCalled()
        if (context.shouldIgnoreDevDeps() && devDependency) {
            return
        }
        val checkedToolchainLabels: com.google.common.collect.ImmutableList<String> =
            checkAllAbsolutePatterns(toolchainLabels, "register_toolchains")
        if (thread
                .getSemantics()
                .getBool(BuildLanguageOptions.EXPERIMENTAL_SINGLE_PACKAGE_TOOLCHAIN_BINDING)
        ) {
            for (label in checkedToolchainLabels) {
                if (label.contains("...")) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "invalid target pattern \"%s\": register_toolchain target patterns may only refer to "
                                + "targets within a single package",
                        label
                    )
                }
            }
        }
        context.getModuleBuilder().addToolchainsToRegister(checkedToolchainLabels)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "use_extension",
        doc = ("Returns a proxy object representing a module extension; its methods can be invoked to"
                + " create module extension tags."),
        parameters = [net.starlark.java.annot.Param(
            name = "extension_bzl_file",
            doc = "A label to the Starlark file defining the module extension."
        ), net.starlark.java.annot.Param(
            name = "extension_name",
            doc = "The name of the module extension to use. A symbol with this name must be exported"
                    + " by the Starlark file."
        ), net.starlark.java.annot.Param(
            name = "dev_dependency",
            doc = "If true, this usage of the module extension will be ignored if the current module"
                    + " is not the root module or `--ignore_dev_dependency` is enabled.",
            named = true,
            positional = false,
            defaultValue = "False"
        ), net.starlark.java.annot.Param(
            name = "isolate",
            doc = ("If true, this usage of the module extension will be isolated from all other "
                    + "usages, both in this and other modules. Tags created for this usage do not "
                    + "affect other usages and the repositories generated by the extension for "
                    + "this usage will be distinct from all other repositories generated by the "
                    + "extension."
                    + "<p>This parameter is currently experimental and only available with the "
                    + "flag <code>--experimental_isolated_extension_usages</code>."),
            named = true,
            positional = false,
            defaultValue = "False",
            enableOnlyWithFlag = "-experimental_isolated_extension_usages"
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun useExtension(
        rawExtensionBzlFile: String?,
        extensionName: String,
        devDependency: Boolean,
        isolate: Boolean,
        thread: net.starlark.java.eval.StarlarkThread
    ): ModuleExtensionProxy {
        val context: ModuleThreadContext = ModuleThreadContext.Companion.fromOrFail(thread, "use_extension()")
        context.setNonModuleCalled()

        if (!net.starlark.java.syntax.Identifier.isValid(extensionName)) {
            throw net.starlark.java.eval.Starlark.errorf("extension name is not a valid identifier: %s", extensionName)
        }

        val proxyBuilder: com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage.Proxy.Builder =
            com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage.Proxy.Companion.builder()
                .setLocation(thread.getCallerLocation())
                .setDevDependency(devDependency)
                .setContainingModuleFilePath(context.getCurrentModuleFilePath())

        val extensionBzlFile = normalizeLabelString(context.getModuleBuilder(), rawExtensionBzlFile)

        if (context.shouldIgnoreDevDeps() && devDependency) {
            // This is a no-op proxy.
            return ModuleExtensionProxy(
                ModuleExtensionUsageBuilder(context, extensionBzlFile, extensionName, isolate),
                proxyBuilder
            )
        }

        return ModuleExtensionProxy(
            context.getOrCreateExtensionUsageBuilder(extensionBzlFile, extensionName, isolate),
            proxyBuilder
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun normalizeLabelString(
        module: com.google.devtools.build.lib.bazel.bzlmod.InterimModule.Builder,
        rawExtensionBzlFile: String?
    ): String {
        // Normalize the label by parsing and stringifying it with a repo mapping that preserves the
        // apparent repository name, except that a reference to the main repository via the empty
        // repo name is translated to using the module repo name. This is necessary as
        // ModuleExtensionUsages are grouped by the string value of this label, but later mapped to
        // their Label representation. If multiple strings map to the same Label, this would result in a
        // crash.
        // ownName can't change anymore as calling module() after this results in an error.
        val ownName: String = module.getRepoName().orElse(module.getName())
        val ownRepoName: RepositoryName = RepositoryName.createUnvalidated(ownName)
        var repoMapping: com.google.common.collect.ImmutableMap<String?, RepositoryName?> =
            com.google.common.collect.ImmutableMap.of<String?, RepositoryName?>()
        if (module.getKey() == ModuleKey.Companion.ROOT) {
            repoMapping = com.google.common.collect.ImmutableMap.of<String?, RepositoryName?>("", ownRepoName)
        }
        val label: com.google.devtools.build.lib.cmdline.Label
        try {
            label =
                com.google.devtools.build.lib.cmdline.Label.parseWithPackageContext(
                    rawExtensionBzlFile,
                    com.google.devtools.build.lib.cmdline.Label.PackageContext.of(
                        PackageIdentifier.create(ownRepoName, PathFragment.EMPTY_FRAGMENT),
                        com.google.devtools.build.lib.cmdline.RepositoryMapping.create(repoMapping, ownRepoName)
                    )
                )
        } catch (e: LabelSyntaxException) {
            throw net.starlark.java.eval.Starlark.errorf(
                "invalid label \"%s\": %s",
                rawExtensionBzlFile,
                e.getMessage()
            )
        }
        val apparentRepoName: String = label.getRepository().getName()
        val fabricatedLabel: com.google.devtools.build.lib.cmdline.Label =
            com.google.devtools.build.lib.cmdline.Label.createUnvalidated(
                PackageIdentifier.create(
                    RepositoryName.createUnvalidated(apparentRepoName), label.getPackageFragment()
                ),
                label.getName()
            )
        // Skip over the leading "@" of the unambiguous form.
        return fabricatedLabel.getUnambiguousCanonicalForm().substring(1)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun convertAndValidatePatchLabel(
        module: com.google.devtools.build.lib.bazel.bzlmod.InterimModule.Builder,
        rawLabel: String?
    ): com.google.devtools.build.lib.cmdline.Label {
        val repoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping =
            com.google.devtools.build.lib.cmdline.RepositoryMapping.create(
                com.google.common.collect.ImmutableMap.builder<String?, RepositoryName?>()
                    .put("", RepositoryName.MAIN)
                    .put(module.getRepoName().orElse(module.getName()), RepositoryName.MAIN)
                    .buildKeepingLast(),
                RepositoryName.MAIN
            )
        val label: com.google.devtools.build.lib.cmdline.Label
        try {
            label =
                com.google.devtools.build.lib.cmdline.Label.parseWithPackageContext(
                    rawLabel,
                    com.google.devtools.build.lib.cmdline.Label.PackageContext.of(
                        PackageIdentifier.EMPTY_PACKAGE_ID,
                        repoMapping
                    )
                )
        } catch (e: LabelSyntaxException) {
            throw net.starlark.java.eval.Starlark.errorf(
                "invalid label \"%s\" in 'patches': %s",
                rawLabel,
                e.getMessage()
            )
        }
        if (!label.getRepository().isVisible()) {
            throw net.starlark.java.eval.Starlark.errorf(
                "invalid label in 'patches': only patches in the main repository can be applied, not from"
                        + " '@%s'",
                label.getRepository().getName()
            )
        }
        return label
    }

    @net.starlark.java.annot.StarlarkBuiltin(name = "module_extension_proxy", documented = false)
    internal class ModuleExtensionProxy(
        usageBuilder: ModuleExtensionUsageBuilder,
        proxyBuilder: com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage.Proxy.Builder
    ) : net.starlark.java.eval.Structure, StarlarkExportable {
        private val usageBuilder: ModuleExtensionUsageBuilder
        private val proxyBuilder: com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage.Proxy.Builder

        init {
            this.usageBuilder = usageBuilder
            this.proxyBuilder = proxyBuilder
            usageBuilder.addProxyBuilder(proxyBuilder)
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        fun addImport(
            localRepoName: String?,
            exportedName: String?,
            byWhat: String?,
            stack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>?
        ) {
            usageBuilder.addImport(localRepoName, exportedName, byWhat, stack)
            proxyBuilder.addImport(localRepoName, exportedName)
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        fun addOverride(
            overriddenRepoName: String?,
            overridingRepoName: String?,
            mustExist: Boolean,
            stack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>?
        ) {
            usageBuilder.addRepoOverride(overriddenRepoName, overridingRepoName, mustExist, stack)
        }

        internal inner class TagCallable(val tagName: String?) : net.starlark.java.eval.StarlarkValue {
            @net.starlark.java.annot.StarlarkMethod(
                name = "call",
                selfCall = true,
                documented = false,
                extraKeywords = net.starlark.java.annot.Param(name = "kwargs"),
                useStarlarkThread = true
            )
            fun call(
                kwargs: net.starlark.java.eval.Dict<String?, Any?>?,
                thread: net.starlark.java.eval.StarlarkThread
            ) {
                usageBuilder.addTag(
                    com.google.devtools.build.lib.bazel.bzlmod.Tag.Companion.builder()
                        .setTagName(tagName)
                        .setAttributeValues(
                            com.google.devtools.build.lib.bazel.bzlmod.AttributeValues.Companion.create(
                                kwargs
                            )
                        )
                        .setDevDependency(proxyBuilder.isDevDependency())
                        .setLocation(thread.getCallerLocation())
                        .build()
                )
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun getValue(tagName: String?): TagCallable? {
            return TagCallable(tagName)
        }

        override fun getFieldNames(): com.google.common.collect.ImmutableCollection<String?> {
            return com.google.common.collect.ImmutableList.of<String?>()
        }

        override fun getErrorMessageForUnknownField(field: String?): String? {
            return null
        }

        override fun isExported(): Boolean {
            return !proxyBuilder.getProxyName().isEmpty()
        }

        override fun export(
            handler: com.google.devtools.build.lib.events.EventHandler?,
            bzlFileLabel: com.google.devtools.build.lib.cmdline.Label?,
            name: String?,
            exportedLocation: net.starlark.java.syntax.Location?
        ) {
            proxyBuilder.setProxyName(name)
        }
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "use_repo",
        doc = ("Imports one or more repos generated by the given module extension into the scope of the"
                + " current module."),
        parameters = [net.starlark.java.annot.Param(
            name = "extension_proxy",
            doc = "A module extension proxy object returned by a <code>use_extension</code> call."
        )],
        extraPositionals = net.starlark.java.annot.Param(name = "args", doc = "The names of the repos to import."),
        extraKeywords = net.starlark.java.annot.Param(
            name = "kwargs", doc = """
                  Specifies certain repos to import into the scope of the current module with
                  different names. The keys should be the name to use in the current scope,
                  whereas the values should be the original names exported by the module
                  extension.
                  <p>Keys that are not valid identifiers can be specified via a literal dict
                  passed as extra keyword arguments, e.g.,
                  <code>use_repo(extension_proxy, **{"foo.2": "foo"})</code>.
                  
                  """.trimIndent()
        ),
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun useRepo(
        extensionProxy: ModuleExtensionProxy,
        args: net.starlark.java.eval.Tuple?,
        kwargs: net.starlark.java.eval.Dict<String?, Any?>?,
        thread: net.starlark.java.eval.StarlarkThread
    ) {
        val context: ModuleThreadContext = ModuleThreadContext.Companion.fromOrFail(thread, "use_repo()")
        context.setNonModuleCalled()
        val stack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?> =
            thread.getCallStack()
        for (arg in net.starlark.java.eval.Sequence.cast<String?>(args, String::class.java, "args")) {
            extensionProxy.addImport(arg, arg, "by a use_repo() call", stack)
        }
        val moduleName: String = context.getModuleBuilder().getName()
        val moduleVersion: String = context.getModuleBuilder().getVersion().normalized
        for (entry in net.starlark.java.eval.Dict.cast<String?, String?>(
            kwargs,
            String::class.java,
            String::class.java,
            "kwargs"
        ).entrySet()) {
            extensionProxy.addImport(
                entry.getKey(),
                entry.getValue().replace("{name}", moduleName).replace("{version}", moduleVersion),
                "by a use_repo() call",
                stack
            )
        }
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "override_repo",
        doc = """
          Overrides one or more repos defined by the given module extension with the given repos
          visible to the current module. This is ignored if the current module is not the root
          module or `--ignore_dev_dependency` is enabled.

          <p>Use <a href="#inject_repo"><code>inject_repo</code></a> instead to add a new repo.
          
          """.trimIndent(),
        parameters = [net.starlark.java.annot.Param(
            name = "extension_proxy",
            doc = "A module extension proxy object returned by a <code>use_extension</code> call."
        )],
        extraPositionals = net.starlark.java.annot.Param(
            name = "args", doc = """
                  The repos in the extension that should be overridden with the repos of the same
                  name in the current module.
                  """.trimIndent()
        ),
        extraKeywords = net.starlark.java.annot.Param(
            name = "kwargs", doc = """
                  The overrides to apply to the repos generated by the extension, where the values
                  are the names of repos in the scope of the current module and the keys are the
                  names of the repos they will override in the extension.
                  <p>Keys that are not valid identifiers can be specified via a literal dict
                  passed as extra keyword arguments, e.g.,
                  <code>override_repo(extension_proxy, **{"foo.2": "foo"})</code>.
                  
                  """.trimIndent()
        ),
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun overrideRepo(
        extensionProxy: ModuleExtensionProxy,
        args: net.starlark.java.eval.Tuple?,
        kwargs: net.starlark.java.eval.Dict<String?, Any?>?,
        thread: net.starlark.java.eval.StarlarkThread
    ) {
        val context: ModuleThreadContext = ModuleThreadContext.Companion.fromOrFail(thread, "override_repo()")
        context.setNonModuleCalled()
        if (context.shouldIgnoreDevDeps()) {
            // Ignore calls early as they may refer to repos that are dev dependencies (or this is not the
            // root module).
            return
        }
        val stack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?> =
            thread.getCallStack()
        for (arg in net.starlark.java.eval.Sequence.cast<String?>(args, String::class.java, "args")) {
            extensionProxy.addOverride(arg, arg,  /* mustExist= */true, stack)
        }
        for (entry in net.starlark.java.eval.Dict.cast<String?, String?>(
            kwargs,
            String::class.java,
            String::class.java,
            "kwargs"
        ).entrySet()) {
            extensionProxy.addOverride(entry.getKey(), entry.getValue(),  /* mustExist= */true, stack)
        }
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "inject_repo",
        doc = """
          Injects one or more new repos into the given module extension.
          This is ignored if the current module is not the root module or
          <code>--ignore_dev_dependency</code> is enabled.

          <p>Use <a href="#override_repo"><code>override_repo</code></a> instead to override an
          existing repo.
          """.trimIndent(),
        parameters = [net.starlark.java.annot.Param(
            name = "extension_proxy",
            doc = "A module extension proxy object returned by a <code>use_extension</code> call."
        )],
        extraPositionals = net.starlark.java.annot.Param(
            name = "args", doc = """
                  The repos visible to the current module that should be injected into the
                  extension under the same name.
                  """.trimIndent()
        ),
        extraKeywords = net.starlark.java.annot.Param(
            name = "kwargs", doc = """
                  The new repos to inject into the extension, where the values are the names of
                  repos in the scope of the current module and the keys are the name they will be
                  visible under in the extension.
                  <p>Keys that are not valid identifiers can be specified via a literal dict
                  passed as extra keyword arguments, e.g.,
                  <code>inject_repo(extension_proxy, **{"foo.2": "foo"})</code>.
                  
                  """.trimIndent()
        ),
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun injectRepo(
        extensionProxy: ModuleExtensionProxy,
        args: net.starlark.java.eval.Tuple?,
        kwargs: net.starlark.java.eval.Dict<String?, Any?>?,
        thread: net.starlark.java.eval.StarlarkThread
    ) {
        val context: ModuleThreadContext = ModuleThreadContext.Companion.fromOrFail(thread, "inject_repo()")
        context.setNonModuleCalled()
        if (context.shouldIgnoreDevDeps()) {
            // Ignore calls early as they may refer to repos that are dev dependencies (or this is not the
            // root module).
            return
        }
        val stack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?> =
            thread.getCallStack()
        for (arg in net.starlark.java.eval.Sequence.cast<String?>(args, String::class.java, "args")) {
            extensionProxy.addOverride(arg, arg,  /* mustExist= */false, stack)
        }
        for (entry in net.starlark.java.eval.Dict.cast<String?, String?>(
            kwargs,
            String::class.java,
            String::class.java,
            "kwargs"
        ).entrySet()) {
            extensionProxy.addOverride(entry.getKey(), entry.getValue(),  /* mustExist= */false, stack)
        }
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "use_repo_rule",
        doc = ("Returns a proxy value that can be directly invoked in the MODULE.bazel file as a"
                + " repository rule, one or more times. Repos created in such a way are only visible"
                + " to the current module, under the name declared using the <code>name</code>"
                + " attribute on the proxy. The implicit Boolean <code>dev_dependency</code>"
                + " attribute can also be used on the proxy to denote that a certain repo is only to"
                + " be created when the current module is the root module."),
        parameters = [net.starlark.java.annot.Param(
            name = "repo_rule_bzl_file",
            doc = "A label to the Starlark file defining the repo rule."
        ), net.starlark.java.annot.Param(
            name = "repo_rule_name",
            doc = "The name of the repo rule to use. A symbol with this name must be exported by the"
                    + " Starlark file."
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun useRepoRule(bzlFile: String?, ruleName: String, thread: net.starlark.java.eval.StarlarkThread): RepoRuleProxy {
        val context: ModuleThreadContext = ModuleThreadContext.Companion.fromOrFail(thread, "use_repo_rule()")
        context.setNonModuleCalled()
        // Not a valid Starlark identifier so that it can't collide with a real extension.
        val extensionName = bzlFile + ' ' + ruleName
        // Find or create the builder for the singular "innate" extension of this repo rule for this
        // module.
        return RepoRuleProxy(
            context.getOrCreateExtensionUsageBuilder(
                "//:MODULE.bazel", extensionName,  /* isolate= */false
            )
        )
    }

    @net.starlark.java.annot.StarlarkBuiltin(name = "repo_rule_proxy", documented = false)
    internal class RepoRuleProxy private constructor(usageBuilder: ModuleExtensionUsageBuilder) :
        net.starlark.java.eval.StarlarkValue {
        private val usageBuilder: ModuleExtensionUsageBuilder

        init {
            this.usageBuilder = usageBuilder
        }

        @net.starlark.java.annot.StarlarkMethod(
            name = "call",
            selfCall = true,
            documented = false,
            parameters = [net.starlark.java.annot.Param(
                name = "name",
                positional = false,
                named = true
            ), net.starlark.java.annot.Param(
                name = "dev_dependency",
                positional = false,
                named = true,
                defaultValue = "False"
            )],
            extraKeywords = net.starlark.java.annot.Param(name = "kwargs"),
            useStarlarkThread = true
        )
        @Throws(net.starlark.java.eval.EvalException::class)
        fun call(
            name: String?,
            devDependency: Boolean,
            kwargs: net.starlark.java.eval.Dict<String?, Any?>,
            thread: net.starlark.java.eval.StarlarkThread
        ) {
            RepositoryName.validateUserProvidedRepoName(name)
            if (usageBuilder.getContext().shouldIgnoreDevDeps() && devDependency) {
                return
            }
            kwargs.putEntry("name", name)
            val extensionProxy =
                ModuleExtensionProxy(
                    usageBuilder,
                    com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage.Proxy.Companion.builder()
                        .setDevDependency(devDependency)
                        .setLocation(thread.getCallerLocation())
                        .setContainingModuleFilePath(
                            usageBuilder.getContext().getCurrentModuleFilePath()
                        )
                )
            extensionProxy.getValue("repo")!!.call(kwargs, thread)
            extensionProxy.addImport(name, name, "by a repo rule", thread.getCallStack())
        }
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = CompiledModuleFile.Companion.INCLUDE_IDENTIFIER,
        doc = ("Includes the contents of another MODULE.bazel-like file. Effectively,"
                + " <code>include()</code> behaves as if the included file is textually placed at the"
                + " location of the <code>include()</code> call, except that variable bindings (such"
                + " as those used for <code>use_extension</code>) are only ever visible in the file"
                + " they occur in, not in any included or including files.<p>Only the root module and"
                + " modules subject to a non-registry override may use <code>include()</code>."
                + "<p>Only files in the current module's repo may be included."
                + "<p><code>include()</code> allows you to segment a module file into multiple parts,"
                + " to avoid having an enormous MODULE.bazel file or to better manage access control"
                + " for individual semantic segments."),
        parameters = [net.starlark.java.annot.Param(
            name = "label", doc = ("The label pointing to the file to include. The label must point to a file in the"
                    + " main repo; in other words, it <strong>must<strong> start with double"
                    + " slashes (<code>//</code>). The name of the file must end with"
                    + " <code>.MODULE.bazel</code> and must not start with <code>.</code>.")
        )],
        useStarlarkThread = true
    )
    @Throws(java.lang.InterruptedException::class, net.starlark.java.eval.EvalException::class)
    fun include(label: String?, thread: net.starlark.java.eval.StarlarkThread) {
        val context: ModuleThreadContext =
            ModuleThreadContext.Companion.fromOrFail(thread, CompiledModuleFile.Companion.INCLUDE_IDENTIFIER + "()")
        context.setNonModuleCalled()
        context.include(label, thread)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "single_version_override",
        doc = ("Specifies that a dependency should still come from a registry, but its version should"
                + " be pinned, or its registry overridden, or a list of patches applied. This"
                + " directive only takes effect in the root module; in other words, if a module"
                + " is used as a dependency by others, its own overrides are ignored."),
        parameters = [net.starlark.java.annot.Param(
            name = "module_name",
            doc = "The name of the Bazel module dependency to apply this override to.",
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = "version", doc = ("Overrides the declared version of this module in the dependency graph. In other"
                    + " words, this module will be \"pinned\" to this override version. This"
                    + " attribute can be omitted if all one wants to override is the registry or"
                    + " the patches. "), named = true, positional = false, defaultValue = "''"
        ), net.starlark.java.annot.Param(
            name = "registry",
            doc = "Overrides the registry for this module; instead of finding this module from the"
                    + " default list of registries, the given registry should be used.",
            named = true,
            positional = false,
            defaultValue = "''"
        ), net.starlark.java.annot.Param(
            name = "patches",
            doc = ("A list of labels pointing to patch files to apply for this module. The patch files"
                    + " must exist in the source tree of the top level project. They are applied in"
                    + " the list order."
                    + ""
                    + "<p>If a patch makes changes to the MODULE.bazel file, these changes will"
                    + " only be effective if the patch file is provided by the root module."),
            allowedTypes = [net.starlark.java.annot.ParamType(type = Iterable::class, generic1 = String::class)],
            named = true,
            positional = false,
            defaultValue = "[]"
        ), net.starlark.java.annot.Param(
            name = "patch_cmds",
            doc = ("Sequence of Bash commands to be applied on Linux/Macos after patches are applied."
                    + ""
                    + "<p>Changes to the MODULE.bazel file will not be effective."),
            allowedTypes = [net.starlark.java.annot.ParamType(type = Iterable::class, generic1 = String::class)],
            named = true,
            positional = false,
            defaultValue = "[]"
        ), net.starlark.java.annot.Param(
            name = "patch_strip",
            doc = "Same as the --strip argument of Unix patch.",
            named = true,
            positional = false,
            defaultValue = "0"
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun singleVersionOverride(
        moduleName: String?,
        version: String,
        registry: String?,
        patches: Iterable<*>?,
        patchCmds: Iterable<*>?,
        patchStrip: net.starlark.java.eval.StarlarkInt,
        thread: net.starlark.java.eval.StarlarkThread
    ) {
        val context: ModuleThreadContext =
            ModuleThreadContext.Companion.fromOrFail(thread, "single_version_override()")
        context.setNonModuleCalled()
        validateModuleName(moduleName)
        val parsedVersion: com.google.devtools.build.lib.bazel.bzlmod.Version?
        try {
            parsedVersion = com.google.devtools.build.lib.bazel.bzlmod.Version.Companion.parse(version)
        } catch (e: com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException) {
            throw net.starlark.java.eval.Starlark.errorf("Invalid version in single_version_override(): %s", version)
        }
        val patchesBuilder: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.cmdline.Label?> =
            com.google.common.collect.ImmutableList.builder<com.google.devtools.build.lib.cmdline.Label?>()
        for (patch in net.starlark.java.eval.Sequence.cast<String?>(patches, String::class.java, "patches")) {
            patchesBuilder.add(convertAndValidatePatchLabel(context.getModuleBuilder(), patch))
        }
        context.addOverride(
            moduleName,
            SingleVersionOverride.Companion.create(
                parsedVersion,
                registry,
                patchesBuilder.build(),
                net.starlark.java.eval.Sequence.cast<String?>(patchCmds, String::class.java, "patchCmds")
                    .getImmutableList(),
                patchStrip.toInt("single_version_override.patch_strip")
            )
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "multiple_version_override",
        doc = ("Specifies that a dependency should still come from a registry, but multiple versions of"
                + " it should be allowed to coexist. See <a"
                + " href=\"/external/module#multiple-version_override\">the documentation</a> for"
                + " more details. This"
                + " directive only takes effect in the root module; in other words, if a module"
                + " is used as a dependency by others, its own overrides are ignored."),
        parameters = [net.starlark.java.annot.Param(
            name = "module_name",
            doc = "The name of the Bazel module dependency to apply this override to.",
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = "versions",
            doc = ("Explicitly specifies the versions allowed to coexist. These versions must already"
                    + " be present in the dependency graph pre-selection. Dependencies on this"
                    + " module will be \"upgraded\" to the nearest higher allowed version at the"
                    + " same compatibility level, whereas dependencies that have a higher version"
                    + " than any allowed versions at the same compatibility level will cause an"
                    + " error."),
            allowedTypes = [net.starlark.java.annot.ParamType(type = Iterable::class, generic1 = String::class)],
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = "registry",
            doc = "Overrides the registry for this module; instead of finding this module from the"
                    + " default list of registries, the given registry should be used.",
            named = true,
            positional = false,
            defaultValue = "''"
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun multipleVersionOverride(
        moduleName: String?, versions: Iterable<*>?, registry: String?, thread: net.starlark.java.eval.StarlarkThread
    ) {
        val context: ModuleThreadContext =
            ModuleThreadContext.Companion.fromOrFail(thread, "multiple_version_override()")
        context.setNonModuleCalled()
        validateModuleName(moduleName)
        val parsedVersionsBuilder: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.bazel.bzlmod.Version?> =
            com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.bazel.bzlmod.Version?>()
        try {
            for (version in net.starlark.java.eval.Sequence.cast<String?>(versions, String::class.java, "versions")
                .getImmutableList()) {
                parsedVersionsBuilder.add(com.google.devtools.build.lib.bazel.bzlmod.Version.Companion.parse(version))
            }
        } catch (e: com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException) {
            throw net.starlark.java.eval.EvalException("Invalid version in multiple_version_override()", e)
        }
        val parsedVersions: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.bazel.bzlmod.Version?> =
            parsedVersionsBuilder.build()
        if (parsedVersions.size() < 2) {
            throw net.starlark.java.eval.EvalException("multiple_version_override() must specify at least 2 versions")
        }
        context.addOverride(moduleName, MultipleVersionOverride.Companion.create(parsedVersions, registry))
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "archive_override",
        doc = """
          Specifies that this dependency should come from an archive file (zip, gzip, etc) at a
          certain location, instead of from a registry. Effectively, this dependency will be
          backed by an <a href="../repo/http#http_archive"><code>http_archive</code></a> rule.

          <p>This directive only takes effect in the root module; in other words, if a module is
          used as a dependency by others, its own overrides are ignored.
          """.trimIndent(),
        parameters = [net.starlark.java.annot.Param(
            name = "module_name",
            doc = "The name of the Bazel module dependency to apply this override to.",
            named = true,
            positional = false
        )],
        extraKeywords = net.starlark.java.annot.Param(
            name = "kwargs", doc = """
                  All other arguments are forwarded to the underlying <code>http_archive</code> repo
                  rule. Note that the <code>name</code> attribute shouldn't be specified; use
                  <code>module_name</code> instead.
                  """.trimIndent()
        ),
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun archiveOverride(
        moduleName: String?,
        kwargs: net.starlark.java.eval.Dict<String?, Any?>,
        thread: net.starlark.java.eval.StarlarkThread
    ) {
        val context: ModuleThreadContext = ModuleThreadContext.Companion.fromOrFail(thread, "archive_override()")
        context.setNonModuleCalled()
        validateModuleName(moduleName)
        val patches: net.starlark.java.eval.Sequence<String?> =
            net.starlark.java.eval.Sequence.cast<String?>(
                kwargs.getOrDefault("patches", net.starlark.java.eval.StarlarkList.empty<Any?>()),
                String::class.java,
                "patches"
            )
        for (patch in patches) {
            val unused: com.google.devtools.build.lib.cmdline.Label =
                convertAndValidatePatchLabel(context.getModuleBuilder(), patch)
        }
        context.addOverride(
            moduleName,
            NonRegistryOverride(
                RepoSpec(
                    ArchiveRepoSpecBuilder.Companion.HTTP_ARCHIVE,
                    com.google.devtools.build.lib.bazel.bzlmod.AttributeValues.Companion.create(kwargs)
                )
            )
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "git_override",
        doc = """
          Specifies that this dependency should come from a certain commit in a Git repository,
          instead of from a registry. Effectively, this dependency will be backed by a
          <a href="../repo/git#git_repository"><code>git_repository</code></a> rule.

          <p>This directive only takes effect in the root module; in other words, if a module is
          used as a dependency by others, its own overrides are ignored.
          """.trimIndent(),
        parameters = [net.starlark.java.annot.Param(
            name = "module_name",
            doc = "The name of the Bazel module dependency to apply this override to.",
            named = true,
            positional = false
        )],
        extraKeywords = net.starlark.java.annot.Param(
            name = "kwargs", doc = """
                  All other arguments are forwarded to the underlying <code>git_repository</code>
                  repo rule. Note that the <code>name</code> attribute shouldn't be specified; use
                  <code>module_name</code> instead.
                  """.trimIndent()
        ),
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun gitOverride(
        moduleName: String?,
        kwargs: net.starlark.java.eval.Dict<String?, Any?>,
        thread: net.starlark.java.eval.StarlarkThread
    ) {
        val context: ModuleThreadContext = ModuleThreadContext.Companion.fromOrFail(thread, "git_override()")
        context.setNonModuleCalled()
        validateModuleName(moduleName)
        val patches: net.starlark.java.eval.Sequence<String?> =
            net.starlark.java.eval.Sequence.cast<String?>(
                kwargs.getOrDefault("patches", net.starlark.java.eval.StarlarkList.empty<Any?>()),
                String::class.java,
                "patches"
            )
        for (patch in patches) {
            val unused: com.google.devtools.build.lib.cmdline.Label =
                convertAndValidatePatchLabel(context.getModuleBuilder(), patch)
        }
        context.addOverride(
            moduleName,
            NonRegistryOverride(
                RepoSpec(
                    GitRepoSpecBuilder.Companion.GIT_REPOSITORY,
                    com.google.devtools.build.lib.bazel.bzlmod.AttributeValues.Companion.create(kwargs)
                )
            )
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "local_path_override",
        doc = """
          Specifies that this dependency should come from a certain directory on local disk,
          instead of from a registry. Effectively, this dependency will be backed by a
          <a href="../repo/local#local_repository"><code>local_repository</code></a> rule.

          <p>This directive only takes effect in the root module; in other words, if a module is
          used as a dependency by others, its own overrides are ignored.
          """.trimIndent(),
        parameters = [net.starlark.java.annot.Param(
            name = "module_name",
            doc = "The name of the Bazel module dependency to apply this override to.",
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = "path",
            doc = "The path to the directory where this module is.",
            named = true,
            positional = false
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun localPathOverride(moduleName: String?, path: String?, thread: net.starlark.java.eval.StarlarkThread) {
        val context: ModuleThreadContext = ModuleThreadContext.Companion.fromOrFail(thread, "local_path_override()")
        context.setNonModuleCalled()
        validateModuleName(moduleName)
        context.addOverride(moduleName, NonRegistryOverride(LocalPathRepoSpecs.create(path)))
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "flag_alias",
        doc = """
            Maps a command-line flag --foo to a Starlark flag --@repo//defs:foo. Bazel translates all
            instances of ${'$'} bazel build //target --foo to ${'$'} bazel build //target --@repo//defs:foo.
          
          """.trimIndent(),
        parameters = [net.starlark.java.annot.Param(
            name = "name",
            doc = "The name of the flag.",
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = "starlark_flag",
            doc = "The label of the Starlark flag to alias to.",
            named = true,
            positional = false
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class, LabelSyntaxException::class)
    fun flagAlias(nativeName: String?, starlarkLabel: String?, thread: net.starlark.java.eval.StarlarkThread) {
        val context: ModuleThreadContext = ModuleThreadContext.Companion.fromOrFail(thread, "flag_alias()")
        val normalizedStarlarkLabel =
            normalizeLabelString(context.getModuleBuilder(), starlarkLabel)

        // TODO: add input validation for stalark flag label
        context.setNonModuleCalled()
        context.getModuleBuilder().addFlagAlias(nativeName, normalizedStarlarkLabel)
    }

    companion object {
        /* Valid bazel compatibility argument must 1) start with (<,<=,>,>=,-);
     2) then contain a version number in form of X.X.X where X has one or two digits
  */
        private val VALID_BAZEL_COMPATIBILITY_VERSION: java.util.regex.Pattern =
            java.util.regex.Pattern.compile("(>|<|-|<=|>=)(\\d+\\.){2}\\d+")

        @kotlin.jvm.JvmStatic
        @com.google.common.annotations.VisibleForTesting
        @Throws(net.starlark.java.eval.EvalException::class)
        fun validateModuleName(moduleName: String?) {
            if (!RepositoryName.VALID_MODULE_NAME.matcher(moduleName).matches()) {
                throw net.starlark.java.eval.Starlark.errorf(
                    ("invalid module name '%s': valid names must 1) only contain lowercase letters (a-z),"
                            + " digits (0-9), dots (.), hyphens (-), and underscores (_); 2) begin with a"
                            + " lowercase letter; 3) end with a lowercase letter or digit."),
                    moduleName
                )
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun checkAllAbsolutePatterns(
            iterable: Iterable<*>?,
            where: String?
        ): com.google.common.collect.ImmutableList<String> {
            val list: net.starlark.java.eval.Sequence<String> =
                net.starlark.java.eval.Sequence.cast<String?>(iterable, String::class.java, where)
            for (item in list) {
                if (!item.startsWith("//") && !item.startsWith("@")) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "Expected absolute target patterns (must begin with '//' or '@') for '%s' argument, but"
                                + " got '%s' as an argument",
                        where, item
                    )
                }
            }
            return list.getImmutableList()
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun checkAllCompatibilityVersions(
            iterable: Iterable<*>?, where: String?
        ): com.google.common.collect.ImmutableList<String?> {
            val list: net.starlark.java.eval.Sequence<String?> =
                net.starlark.java.eval.Sequence.cast<String?>(iterable, String::class.java, where)
            for (version in list) {
                if (!VALID_BAZEL_COMPATIBILITY_VERSION.matcher(version).matches()) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "invalid version argument '%s': valid argument must 1) start with (<,<=,>,>=,-); "
                                + "2) contain a version number in form of X.X.X where X is a number",
                        version
                    )
                }
            }
            return list.getImmutableList()
        }
    }
}
