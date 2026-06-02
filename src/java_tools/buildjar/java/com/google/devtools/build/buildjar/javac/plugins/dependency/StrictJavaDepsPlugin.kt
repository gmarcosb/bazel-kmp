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
package com.google.devtools.build.buildjar.javac.plugins.dependency

import com.google.common.base.Throwables
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Iterables
import com.google.common.collect.Streams
import com.google.devtools.build.buildjar.JarOwner
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics
import com.sun.source.tree.IdentifierTree
import com.sun.source.tree.Tree
import com.sun.source.util.TreePath
import com.sun.tools.javac.code.Attribute
import com.sun.tools.javac.code.Flags
import com.sun.tools.javac.code.Symbol
import com.sun.tools.javac.comp.Env
import com.sun.tools.javac.main.JavaCompiler
import com.sun.tools.javac.tree.TreeInfo
import com.sun.tools.javac.util.*
import java.io.File
import java.lang.String
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.invoke.VarHandle
import java.nio.file.Path
import java.util.*
import java.util.jar.Attributes
import java.util.jar.Manifest
import javax.lang.model.element.AnnotationValue
import kotlin.Any
import kotlin.AssertionError
import kotlin.Boolean
import kotlin.Comparator
import kotlin.Int
import kotlin.Throwable
import kotlin.UnsupportedOperationException
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.toString

/**
 * A plugin for BlazeJavaCompiler that checks for types referenced directly in the source, but
 * included through transitive dependencies. To get this information, we hook into the type
 * attribution phase of the BlazeJavaCompiler (thus the overhead is another tree scan with the
 * classic visitor). The constructor takes a map from jar names to target names, only for the jars
 * that come from transitive dependencies (Blaze computes this information).
 */
class StrictJavaDepsPlugin(private val dependencyModule: DependencyModule) : BlazeJavaCompilerPlugin() {
    private var implicitDependencyExtractor: ImplicitDependencyExtractor? = null
    private var checkingTreeScanner: CheckingTreeScanner? = null

    /** Marks seen compilation toplevels and their import sections  */
    private val toplevels: MutableSet<JCCompilationUnit?>

    /** Marks seen ASTs  */
    private val trees: MutableSet<JCTree?>

    /** Computed missing dependencies  */
    private val missingTargets: MutableSet<JarOwner?>

    /** Strict deps diagnostics.  */
    private val diagnostics: MutableList<SjdDiagnostic>

    private var errWriter: PrintWriter? = null

    @AutoValue
    internal abstract class SjdDiagnostic {
        abstract fun pos(): Int

        abstract fun message(): String?

        abstract fun source(): JavaFileObject?

        companion object {
            fun create(pos: Int, message: String?, source: JavaFileObject?): SjdDiagnostic {
                return AutoValue_StrictJavaDepsPlugin_SjdDiagnostic(pos, message, source)
            }
        }
    }

    public override fun init(
        context: Context,
        log: Log,
        compiler: JavaCompiler?,
        statisticsBuilder: BlazeJavacStatistics.Builder?
    ) {
        super.init(context, log, compiler, statisticsBuilder)
        errWriter = log.getWriter(WriterKind.ERROR)
        implicitDependencyExtractor =
            ImplicitDependencyExtractor(
                dependencyModule.getImplicitDependenciesMap(), dependencyModule.getPlatformJars()
            )
        checkingTreeScanner = context.get<CheckingTreeScanner?>(CheckingTreeScanner::class.java)
        if (checkingTreeScanner == null) {
            checkingTreeScanner =
                CheckingTreeScanner(
                    dependencyModule,
                    diagnostics,
                    missingTargets,
                    dependencyModule.getPlatformJars(),
                    context.get<JavaFileManager?>(JavaFileManager::class.java),
                    Names.instance(context)
                )
            context.put<CheckingTreeScanner?>(CheckingTreeScanner::class.java, checkingTreeScanner)
        }
    }

    /**
     * We want to make another pass over the AST and "type-check" the usage of direct/transitive
     * dependencies after the type attribution phase.
     */
    public override fun postAttribute(env: Env<AttrContext?>) {
        val previousSource: JavaFileObject? = checkingTreeScanner.source
        try {
            if (isAnnotationProcessorExempt(env.toplevel)) {
                return
            }
            checkingTreeScanner.source =
                if (env.enclClass.sym.sourcefile != null)
                    env.enclClass.sym.sourcefile
                else
                    env.toplevel.sourcefile
            val path = TreePath(env.toplevel)
            if (trees.add(env.tree)) {
                checkingTreeScanner.scan(TreePath(path, env.tree), null)
            }
            if (toplevels.add(env.toplevel)) {
                checkingTreeScanner.scan(path, null)
                dependencyModule.addPackage(env.toplevel.packge)
            }
        } finally {
            checkingTreeScanner.source = previousSource
        }
    }

    public override fun finish() {
        implicitDependencyExtractor!!.accumulate(context, checkingTreeScanner!!.seenClasses)

        for (diagnostic in diagnostics) {
            val prev: JavaFileObject? = log.useSource(diagnostic.source())
            try {
                when (dependencyModule.getStrictJavaDeps()) {
                    StrictJavaDeps.ERROR -> log.error(
                        diagnostic.pos(),
                        CompilerProperties.Errors.ProcMessager(diagnostic.message())
                    )

                    StrictJavaDeps.WARN -> log.warning(diagnostic.pos(), Warnings.ProcMessager(diagnostic.message()))
                    StrictJavaDeps.OFF -> {}
                }
            } finally {
                log.useSource(prev)
            }
        }

        if (!missingTargets.isEmpty()) {
            val canonicalizedLabel: String? =
                if (dependencyModule.getTargetLabel() == null)
                    null // we don't use the target mapping for the target, just the missing deps
                else
                    canonicalizeTarget(dependencyModule.getTargetLabel())
            val canonicalizedMissing: MutableSet<JarOwner?> =
                missingTargets.stream()
                    .filter { owner: JarOwner? -> owner.label().isPresent() }
                    .sorted(Comparator.comparing<T?, U?> { owner: JarOwner? ->
                        owner.label().get()
                    }) // for dependencies that are missing we canonicalize and remap the target so we don't
                    // suggest private build labels.
                    .map<Any?> { owner: JarOwner? ->
                        owner.withLabel(
                            owner.label().map({ label -> canonicalizeTarget(label) })
                        )
                    }
                    .collect(ImmutableSet.toImmutableSet<Any?>())
            if (dependencyModule.getStrictJavaDeps() != StrictJavaDeps.OFF) {
                errWriter.print(
                    dependencyModule.getFixMessage().get(canonicalizedMissing, canonicalizedLabel)
                )
                dependencyModule.setHasMissingTargets()
            }
        }
    }

    /**
     * An AST visitor that implements our strict_java_deps checks. For now, it only emits warnings for
     * types loaded from jar files provided by transitive (indirect) dependencies. Each type is
     * considered only once, so at most one warning is generated for it.
     */
    private class CheckingTreeScanner(
        dependencyModule: DependencyModule,
        /** Strict deps diagnostics.  */
        private val diagnostics: MutableList<SjdDiagnostic>,
        missingTargets: MutableSet<JarOwner?>,
        platformJars: MutableSet<Path?>,
        fileManager: JavaFileManager,
        names: Names
    ) : TreePathScanner<Void?, Void?>() {
        private val directJars: ImmutableSet<Path?>

        /** Missing targets  */
        private val missingTargets: MutableSet<JarOwner?>

        /** Collect seen direct dependencies and their associated information  */
        private val directDependenciesMap: MutableMap<Path?, Deps.Dependency?>

        /** We only emit one warning/error per class symbol  */
        val seenClasses: MutableSet<Symbol.ClassSymbol?> = HashSet<Symbol.ClassSymbol?>()

        private val seenTargets: MutableSet<JarOwner?> = HashSet<JarOwner?>()

        private val seenStrictDepsViolatingJars: MutableSet<NonPlatformJar?> = HashSet<NonPlatformJar?>()

        /** The set of jars on the compilation bootclasspath.  */
        private val platformJars: MutableSet<Path?>

        private val fileManager: JavaFileManager

        /** The current source, for diagnostics.  */
        private var source: JavaFileObject? = null

        /** Cache of classpath (not platform) jars in which given symbols can be found.  */
        private val classpathOnlyDepPaths: MutableMap<Symbol.ClassSymbol?, Optional<Path?>?> =
            HashMap<Symbol.ClassSymbol?, Optional<Path?>?>()

        private val jspecifyAnnotationsPackage: Name?

        init {
            this.directJars = dependencyModule.directJars()
            this.missingTargets = missingTargets
            this.directDependenciesMap = dependencyModule.getExplicitDependenciesMap()
            this.platformJars = platformJars
            this.fileManager = fileManager
            this.jspecifyAnnotationsPackage = names.fromString("org.jspecify.annotations")
        }

        /** Checks an AST node denoting a class type against direct/transitive dependencies.  */
        fun checkTypeLiteral(sym: Symbol?) {
            if (sym == null || sym.kind != Kinds.Kind.TYP) {
                return
            }
            val jar = getNonPlatformJar(sym.enclClass(), platformJars)

            // If this type symbol comes from a class file loaded from a jar, check
            // whether that jar was a direct dependency and error out otherwise.
            if (jar != null && seenClasses.add(sym.enclClass())) {
                collectExplicitDependency(jar, sym)
            }
        }

        /**
         * Marks the provided dependency as a direct/explicit dependency. Additionally, if
         * strict_java_deps is enabled, it emits a [strict] compiler warning/error.
         */
        fun collectExplicitDependency(jar: NonPlatformJar, sym: Symbol) {
            // Does it make sense to emit a warning/error for this pair of (type, owner)?
            // We want to emit only one error/warning per owner.
            if (!directJars.contains(jar.pathOrEmpty()) && seenStrictDepsViolatingJars.add(jar)) {
                // IO cost here is fine because we only hit this path for an explicit dependency
                // _not_ in the direct jars, i.e. an error
                val owner: JarOwner = readJarOwnerFromManifest(jar)
                if (seenTargets.add(owner)) {
                    // owner is of the form "//label/of:rule <Aspect name>" where <Aspect name> is
                    // optional.
                    val canonicalTargetName: Optional<String?> =
                        owner.label().map({ label -> canonicalizeTarget(label) })
                    missingTargets.add(owner)
                    val toolInfo =
                        if (owner.aspect().isPresent())
                            String.format(
                                "%s wrapped in %s", canonicalTargetName.get(), owner.aspect().get()
                            )
                        else
                            if (canonicalTargetName.isPresent())
                                canonicalTargetName.get()
                            else
                                owner.jar().toString()
                    val used =
                        if (sym.getSimpleName().contentEquals("package-info"))
                            "package " + sym.getEnclosingElement()
                        else
                            "type " + sym
                    val message: kotlin.String? = kotlin.String.format(
                        "[strict] Using %s from an indirect dependency (TOOL_INFO: \"%s\").%s",
                        used, toolInfo, (if (owner.label().isPresent()) " See command below **" else "")
                    )
                    val pos =
                        Streams.stream<Tree?>(getCurrentPath())
                            .map<Int?> { t: Tree? -> TreeInfo.getStartPos(t as JCTree?) }
                            .filter { p: Int? -> p != Position.NOPOS }
                            .findFirst()
                            .orElse(Position.NOPOS)
                    diagnostics.add(SjdDiagnostic.Companion.create(pos, message, source))
                }
            }

            if (!directDependenciesMap.containsKey(jar.pathOrEmpty())) {
                // Also update the dependency proto
                val dep: Dependency? =
                    Dependency.newBuilder() // Path.toString uses the platform separator (`\` on Windows), but the proto must
                        // use the same separator as in the arguments.
                        //
                        // An empty path is OK in the cases we produce it. See readJarOwnerFromManifest.
                        .setPath(jar.pathOrEmpty().toString().replace(File.separatorChar, '/'))
                        .setKind(Dependency.Kind.EXPLICIT)
                        .build()
                directDependenciesMap.put(jar.pathOrEmpty(), dep)
            }
        }

        fun readJarOwnerFromManifest(jar: NonPlatformJar): JarOwner {
            if (jar.kind == NonPlatformJar.Kind.FOR_JSPECIFY_FROM_PLATFORM) {
                return JSPECIFY_JAR_OWNER
            }
            val jarPath = jar.inClasspath()
            try {
                JarFile(jarPath.toFile()).use { jarFile ->
                    val manifest: Manifest? = jarFile.getManifest()
                    if (manifest == null) {
                        return JarOwner.create(jarPath)
                    }
                    val attributes = manifest.getMainAttributes()
                    val label = attributes.get(TARGET_LABEL) as kotlin.String?
                    if (label == null) {
                        return JarOwner.create(jarPath)
                    }
                    val injectingRuleKind = attributes.get(INJECTING_RULE_KIND) as kotlin.String?
                    return JarOwner.create(jarPath, label, Optional.ofNullable<T?>(injectingRuleKind))
                }
            } catch (e: IOException) {
                // This jar file pretty much has to exist, we just used it in the compiler. Throw unchecked.
                throw UncheckedIOException(e)
            }
        }

        override fun visitMethod(method: MethodTree, unused: Void?): Void? {
            if (((method as JCMethodDecl).mods.flags and Flags.GENERATEDCONSTR) != 0L) {
                // If this is the constructor for an anonymous inner class, refrain from checking the
                // compiler-generated method signature. Don't skip scanning the method body though, there
                // might have been an anonymous initializer which still needs to be checked.
                scan(method.getBody(), null)
            } else {
                super.visitMethod(method, null)
            }
            return null
        }

        override fun visitVariable(variable: VariableTree, unused: Void?): Void? {
            scan(variable.getModifiers(), null)
            if (!hasImplicitType(variable)) {
                scan(variable.getType(), null)
            }
            scan(variable.getNameExpression(), null)
            scan(variable.getInitializer(), null)
            return null
        }

        fun hasImplicitType(tree: VariableTree?): Boolean {
            val varDecl: JCVariableDecl = tree as JCVariableDecl
            if (varDecl.declaredUsingVar()) {
                return true
            }
            val vartype: JCTree = varDecl.vartype
            val unit: JCCompilationUnit? = getCurrentPath().getCompilationUnit() as JCCompilationUnit?
            if (vartype.pos == Position.NOPOS || getEndPosition(vartype, unit) == Position.NOPOS) {
                return true
            }
            return false
        }

        /** Visits an identifier in the AST. We only care about type symbols.  */
        override fun visitIdentifier(tree: IdentifierTree, unused: Void?): Void? {
            checkTypeLiteral((tree as JCIdent).sym)
            return null
        }

        /**
         * Visits a field selection in the AST. We care because in some cases types may appear fully
         * qualified and only inside a field selection (e.g., "com.foo.Bar.X", we want to catch the
         * reference to Bar).
         */
        override fun visitMemberSelect(tree: MemberSelectTree, unused: Void?): Void? {
            scan(tree.getExpression(), null)
            checkTypeLiteral((tree as JCFieldAccess).sym)
            return null
        }

        override fun visitLambdaExpression(tree: LambdaExpressionTree, unused: Void?): Void? {
            if ((tree as JCLambda).paramKind != JCTree.JCLambda.ParameterKind.IMPLICIT) {
                // don't record type uses for implicitly typed lambda parameters
                scan(tree.getParameters(), null)
            }
            scan(tree.getBody(), null)
            return null
        }

        override fun visitPackage(tree: PackageTree, unused: Void?): Void? {
            scan(tree.getAnnotations(), null)
            checkTypeLiteral((tree as JCPackageDecl).packge.package_info)
            return null
        }

        override fun visitCompilationUnit(tree: CompilationUnitTree, unused: Void?): Void? {
            scan(tree.getPackage(), null)
            scan(tree.getImports(), null)
            return null
        }

        /**
         * Returns the name of the *classpath* jar from which the given class symbol was loaded
         * (with an exception for the JSpecify annotations) or else `null`.
         * 
         * 
         * If the symbol came from the platform (i.e., system modules/bootclasspath), rather than
         * from the classpath, this method *usually* returns `null`. The exception is for
         * JSpecify-annotation symbols that are read from the platform: For such a symbol, this method
         * still returns the first *classpath* jar that contains the symbol, or, if no classpath
         * jar contains the symbol, it returns `FOR_JSPECIFY_FROM_PLATFORM`. (The calling code
         * later converts that to `JSPECIFY_JAR_OWNER`, which will lead to a strict-deps error,
         * since that jar clearly isn't a direct dependency.) In this way, we pretend that the
         * JSpecify-annotation symbols *aren't* part of the platform. That's important because in
         * fact they aren't part of it *at runtime* and so we want to force users of those classes
         * to declare a dependency on them.
         * 
         * 
         * This behavior is mildly unfortunate in the unusual situation that a project normally reads
         * a JSpecify-annotations class from an uber-jar, rather than from the normal JSpecify target.
         * In that case, we claim that the class is being loaded from the normal target. That is not the
         * target that the project's developers are likely to want. It's even possible that the class
         * isn't present on the reduced classpath but *would* be present (via the uber-jar) if only
         * we compiled with the full classpath. The full-classpath compilation would still produce a
         * strict-deps error, but it would produce one that recommends the correct jar/dependency. But
         * as this code is, we fail with a suggestion of the normal JSpecify target, and we may or may
         * not fall back to the full classpath.
         * 
         * 
         * OK, arguably it's unfortunate that *ever* we suggest that the normal JSpecify target
         * is on the classpath when it isn't really. However, the most common result of that is going to
         * be that we produce a more convenient error message. That convenience helps to offset any
         * confusion that we produce. Still, we won't introduce similar behavior for other classes
         * lightly.
         * 
         * @param platformJars jars on javac's bootclasspath
         */
        fun getNonPlatformJar(classSymbol: Symbol.ClassSymbol?, platformJars: MutableSet<Path?>): NonPlatformJar? {
            if (classSymbol == null) {
                return null
            }

            // Ignore symbols that appear in the sourcepath:
            if (haveSourceForSymbol(classSymbol)) {
                return null
            }

            val classfile: JavaFileObject? = classSymbol.classfile

            val path: Path? = ImplicitDependencyExtractor.Companion.getJarPath(classfile)
            // Filter out classes from the system modules and bootclasspath
            if (path == null || platformJars.contains(path)) {
                // ...except the JSpecify annotations, which we treat specially.
                if (classSymbol.packge().fullname == jspecifyAnnotationsPackage) {
                    val classpathJar = findLookingOnlyInClasspath(classSymbol)
                    return if (classpathJar != null)
                        NonPlatformJar.Companion.forClasspathJar(classpathJar)
                    else
                        NonPlatformJar.Companion.FOR_JSPECIFY_FROM_PLATFORM
                }
                return null
            }

            return NonPlatformJar.Companion.forClasspathJar(path)
        }

        /**
         * Returns the first jar file in the classpath (not system modules, not bootclasspath) that
         * contains the given class or `null` if no such jar is available.
         */
        fun findLookingOnlyInClasspath(sym: Symbol.ClassSymbol): Path? {
            /*
       * computeIfAbsent doesn't cache null results, so we store Optional instances instead.
       *
       * In practice, that won't normally matter much: The only case in which our computation
       * function runs once per usage of a JSpecify-annotation class is the failing-build case—that
       * is, when the class is not on the classpath.
       */
            return classpathOnlyDepPaths
                .computeIfAbsent(
                    sym
                ) { unused: com.sun.tools.javac.code.Symbol.ClassSymbol? ->
                    try {
                        for (file in fileManager.list(
                            StandardLocation.CLASS_PATH,
                            sym.packge().fullname.toString(),
                            com.google.common.collect.ImmutableSet.of<JavaFileObject.Kind?>(JavaFileObject.Kind.CLASS),
                            false /* do not return classes in subpackages */
                        )) {
                            /*
                     * The query above returns all classpath classes from the given package. We can
                     * imagine situations in which only *some* JSpecify annotations are present in a
                     * given classpath jar (an uber-jar with unused classes removed?), so we want to
                     * make sure that we found the class we want.
                     */
                            if (file.isNameCompatible(sym.getSimpleName().toString(), JavaFileObject.Kind.CLASS)) {
                                return@computeIfAbsent java.util.Optional.of<java.nio.file.Path?>(
                                    ImplicitDependencyExtractor.Companion.getJarPath(
                                        file
                                    )
                                )
                            }
                        }
                    } catch (e: IOException) {
                        throw UncheckedIOException(e)
                    }
                    java.util.Optional.empty<java.nio.file.Path?>()
                }!!
                .orElse(null)
        }
    }

    /**
     * Returns true if the compilation unit contains a single top-level class generated by an exempt
     * annotation processor (according to its [@Generated] annotation).
     * 
     * 
     * Annotation processors are expected to never generate more than one top level class, as
     * required by the style guide.
     */
    fun isAnnotationProcessorExempt(unit: JCCompilationUnit): Boolean {
        if (unit.getTypeDecls().size != 1) {
            return false
        }
        val sym = TreeInfo.symbolFor(Iterables.getOnlyElement<JCTree?>(unit.getTypeDecls()))
        if (sym == null) {
            return false
        }
        for (value in getGeneratedBy(sym)) {
            if (dependencyModule.getExemptGenerators().contains(value)) {
                return true
            }
        }
        return false
    }

    public override fun runOnAttributionErrors(): Boolean {
        return true
    }

    /**
     * Either a jar in the classpath or the well-known jar that contains the classes that are present
     * in the platform at compile time but not runtime.
     */
    @AutoOneOf(NonPlatformJar.Kind::class)
    internal abstract class NonPlatformJar {
        internal enum class Kind {
            IN_CLASSPATH,
            FOR_JSPECIFY_FROM_PLATFORM,
        }

        abstract val kind: Kind?

        abstract fun inClasspath(): Path

        abstract fun forJspecifyFromPlatform(): Placeholder?

        fun pathOrEmpty(): Path? {
            return if (this.kind == Kind.IN_CLASSPATH) inClasspath() else EMPTY_PATH
        }

        companion object {
            fun forClasspathJar(s: Path?): NonPlatformJar {
                return AutoOneOf_StrictJavaDepsPlugin_NonPlatformJar.inClasspath(s)
            }

            val FOR_JSPECIFY_FROM_PLATFORM: NonPlatformJar? =
                AutoOneOf_StrictJavaDepsPlugin_NonPlatformJar.forJspecifyFromPlatform(
                    Placeholder.INSTANCE
                )
        }
    }

    internal enum class Placeholder {
        INSTANCE
    }

    /**
     * On top of javac, we keep Blaze-specific information in the form of two maps. Both map jars
     * (exactly as they appear on the classpath) to target names, one is used for direct dependencies,
     * the other for the transitive dependencies.
     * 
     * 
     * This enables the detection of dependency issues. For instance, when a type com.Foo is
     * referenced in the source and it's coming from an indirect dependency, we emit a warning
     * flagging that dependency. Also, we can check whether the direct dependencies were actually
     * necessary, i.e. if their associated jars were used at all for looking up class definitions.
     */
    init {
        toplevels = HashSet<JCCompilationUnit?>()
        trees = HashSet<JCTree?>()
        missingTargets = HashSet<JarOwner?>()
        diagnostics = ArrayList<SjdDiagnostic>()
    }

    companion object {
        private val TARGET_LABEL = Attributes.Name("Target-Label")
        private val INJECTING_RULE_KIND = Attributes.Name("Injecting-Rule-Kind")

        private fun getGeneratedBy(symbol: Symbol): ImmutableSet<kotlin.String?> {
            val suppressions = ImmutableSet.builder<kotlin.String?>()
            symbol.getRawAttributes().stream()
                .filter { a: Attribute.Compound? ->
                    val name = a!!.type.tsym.getQualifiedName()
                    name.contentEquals("javax.annotation.Generated")
                            || name.contentEquals("javax.annotation.processing.Generated")
                }
                .flatMap<Attribute?> { a: Attribute.Compound? ->
                    a!!.getElementValues().entries.stream()
                        .filter { e: MutableMap.MutableEntry<Symbol.MethodSymbol?, Attribute?>? ->
                            e!!.key!!.getSimpleName().contentEquals("value")
                        }
                        .map<Attribute?> { e: MutableMap.MutableEntry<Symbol.MethodSymbol?, Attribute?>? -> e!!.value }
                }
                .forEachOrdered { a: Attribute? ->
                    a!!.accept<Void?, Void?>(
                        object : SimpleAnnotationValueVisitor8<Void?, Void?>() {
                            override fun visitString(s: kotlin.String?, aVoid: Void?): Void? {
                                suppressions.add(s)
                                return@forEachOrdered super.visitString(s, aVoid)
                            }

                            override fun visitArray(vals: MutableList<out AnnotationValue?>, aVoid: Void?): Void? {
                                vals.forEach { v: AnnotationValue? -> v!!.accept<Void?, Void?>(this, null) }
                                return@forEachOrdered super.visitArray(vals, aVoid)
                            }
                        },
                        null
                    )
                }
            return suppressions.build()
        }

        /** Returns the canonical version of the target name. Package private for testing.  */
        fun canonicalizeTarget(target: kotlin.String): kotlin.String {
            val colonIndex: Int = target.indexOf(':')
            if (colonIndex == -1) {
                // No ':' in target, nothing to do.
                return target
            }
            val lastSlash: Int = target.lastIndexOf('/', colonIndex)
            if (lastSlash == -1) {
                // No '/' or target is actually a filename in label format, return unmodified.
                return target
            }
            val packageName: kotlin.String = target.substring(lastSlash + 1, colonIndex)
            val suffix: kotlin.String = target.substring(colonIndex + 1)
            if (packageName == suffix) {
                // target ends in "/something:something", canonicalize.
                return target.substring(0, colonIndex)
            }
            return target
        }

        /** Returns true if the given classSymbol corresponds to one of the sources being compiled.  */
        private fun haveSourceForSymbol(classSymbol: Symbol.ClassSymbol): Boolean {
            if (classSymbol.sourcefile == null) {
                return false
            }

            try {
                // The classreader uses metadata to populate the symbol's sourcefile with a fake file object.
                // Call getLastModified() to check if it's a real file:
                classSymbol.sourcefile.getLastModified()
            } catch (e: UnsupportedOperationException) {
                return false
            }

            return true
        }

        private val EMPTY_PATH: Path? = Path.of("")

        /**
         * A special-purpose [JarOwner] instance that points to the main JSpecify target but is used
         * when the JSpecify annotations are instead read from the platform.
         * 
         * 
         * We use this instance to force users to add the explicit JSpecify dependency—again, even
         * though the annotations are present in the compile-time platform (i.e., bootclasspath or system
         * modules). We require users to add the dependency because the annotations are *not* present
         * in the *runtime* platform.
         * 
         * 
         * The [Path] argument that we pass to this instance doesn't matter because the build is
         * usually going to fail. (Or, if a strict-deps enforcement is disabled, the user can't reasonably
         * expect fully accurate dependency information, and our tools should be mostly resilient to an
         * empty path.)
         */
        private val JSPECIFY_JAR_OWNER: JarOwner = JarOwner.create(
            EMPTY_PATH, "//third_party/java/jspecify_annotations",  /* aspect= */Optional.empty<T?>()
        )

        /** Returns the source end position of the tree.  */
        private fun getEndPosition(tree: Tree?, unit: CompilationUnitTree?): Int {
            try {
                return GET_END_POS_HANDLE.invokeExact(tree as JCTree?, unit as JCCompilationUnit?) as Int
            } catch (e: Throwable) {
                Throwables.throwIfUnchecked(e)
                throw AssertionError(e)
            }
        }

        private val GET_END_POS_HANDLE: MethodHandle =
            endPosMethodHandle

        private val endPosMethodHandle: MethodHandle
            get() {
                val lookup = MethodHandles.lookup()
                try {
                    // JDK versions after https://bugs.openjdk.org/browse/JDK-8372948
                    // (tree, unit) -> tree.getEndPosition()
                    return MethodHandles.dropArguments(
                        lookup.findVirtual(
                            JCTree::class.java,
                            "getEndPosition",
                            MethodType.methodType(Int::class.javaPrimitiveType)
                        ),
                        1,
                        JCCompilationUnit::class.java
                    )
                } catch (e1: ReflectiveOperationException) {
                    try {
                        // JDK versions before https://bugs.openjdk.org/browse/JDK-8372948
                        // (tree, unit) -> tree.getEndPosition(unit.endPositions)
                        val endPosTableClass =
                            Class.forName("com.sun.tools.javac.tree.EndPosTable")
                        return MethodHandles.filterArguments(
                            lookup.findVirtual(
                                JCTree::class.java,
                                "getEndPosition",
                                MethodType.methodType(Int::class.javaPrimitiveType, endPosTableClass)
                            ),
                            1,
                            lookup
                                .findVarHandle(JCCompilationUnit::class.java, "endPositions", endPosTableClass)
                                .toMethodHandle(VarHandle.AccessMode.GET)
                        )
                    } catch (e2: ReflectiveOperationException) {
                        e2.addSuppressed(e1)
                        throw LinkageError(e2.message, e2)
                    }
                }
            }
    }
}
