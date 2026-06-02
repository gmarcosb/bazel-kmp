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
package net.starlark.java.annot

/**
 * Annotates a Java method that can be called from Starlark.
 * 
 * 
 * A method annotated with `@StarlarkMethod` may not have overloads or hide any static or
 * default methods. Overriding is allowed, but the `@StarlarkMethod` annotation itself must
 * not be repeated on the override. This ensures that given a method, we can always determine its
 * corresponding `@StarlarkMethod` annotation, if it has one, by scanning all methods of the
 * same name in its class hierarchy, without worrying about complications like overloading or
 * generics. The lookup functionality is implemented by [ ][StarlarkAnnotations.getStarlarkMethod].
 * 
 * 
 * Methods having this annotation must satisfy the following requirements, which are enforced at
 * compile time by [StarlarkMethodProcessor]:
 * 
 * 
 *  * The method must be public and non-static, and its class must implement StarlarkValue.
 *  * The method must declare the following parameters, in order:
 * 
 *  1. one for each `Param` marked [Param.positional]. These parameters may be
 * specified positionally. Among these, required parameters must precede optional ones.
 * A suffix of the optional positional parameters may additionally be marked [             ][Param.named], meaning they may be specified by position or by name.
 *  1. one for each `Param` marked [Param.named] but not [             ][Param.positional]. These parameters must be specified by name. Again, required
 * named-only parameters must precede optional ones.
 *  1. one for the `Tuple` of extra positional arguments (`*args`), if `extraPositionals`;
 *  1. a `Dict<String, Object>` of extra keyword arguments (`**kwargs`), if
 * `extraKeywords`;
 *  1. a `StarlarkThread`, if `useStarlarkThread`;
 *  1. a `StarlarkSemantics`, if `useStarlarkSemantics`.
 * 
 * The last three parameters are implicitly supplied by the interpreter when the method is
 * called from Starlark.
 *  * If `structField`, there must be no `@Param` annotations or parameters, and the
 * only permitted special parameter is `StarlarkSemantics`. Rationale: unlike a method,
 * which is actively called within in the context of a Starlark thread (which encapsulates a
 * call stack of locations), a field is a passive thing, part of a data structure, that may be
 * accessed by a Java caller without a Starlark thread.
 *  * Each `Param` annotation, if explicitly typed, may use either `type` or `allowedTypes`, but not both.
 *  * Each `Param` annotation must be positional or named, or both.
 *  * Noneable parameter variables must be declared with type Object, as the actual value may be
 * either `None` or some other value, which do not share a superclass other than Object
 * (or StarlarkValue, which is typically no more descriptive than Object).
 *  * Parameter variables whose class is generic must be declared using wildcard types. For
 * example, `Sequence<?>` is allowed but `Sequence<String>` is forbidden. This is
 * because the call-time dynamic checks verify the class but cannot verify the type
 * parameters. Such parameters may require additional validation within the method
 * implementation.
 *  * The class of the declared result type, if final, must be accepted by [       ][Starlark.fromJava]. Rationale: this check helps reject clearly invalid parameter types.
 *  * The `doc` string must be non-empty, or `documented` must be false. Rationale:
 * Leaving a function undocumented requires an explicit decision.
 *  * Each class may have up to one method annotated with `selfCall`, which must not be
 * marked `structField=true`.
 * 
 * 
 * 
 * When an annotated method is called from Starlark, it is a dynamic error if it returns null,
 * unless the method is marked as [.allowReturnNones], in which case [Starlark.fromJava]
 * converts the Java null value to [Starlark.NONE]. This feature prevents a method whose
 * declared (and documented) result type is T from unexpectedly returning a value of type NoneType.
 * 
 * 
 * The annotated method may throw any checked or unchecked exceptions. When it is invoked,
 * unchecked exceptions, `EvalException`s, and `InterruptedException`s are passed
 * through; all other (checked) exceptions are wrapped in an `EvalException` and thrown.
 */
// TODO(adonovan): rename to StarlarkAttribute and factor Starlark{Method,Field} as subinterfaces.
@com.google.errorprone.annotations.Keep
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
@Retention(AnnotationRetention.RUNTIME)
annotation class StarlarkMethod(
    /** Name of the method, as exposed to Starlark.  */
    val name: String,
    /**
     * The documentation text in Starlark. It can contain HTML tags for special formatting.
     * 
     * 
     * It is allowed to be empty only if [.documented] is false.
     */
    val doc: String = "",
    /**
     * If true, the function will appear in the Starlark documentation. Set this to false if the
     * function is experimental or an overloading and doesn't need to be documented.
     */
    val documented: Boolean = true,
    /**
     * If true, this method will be considered as a field of the enclosing Java object. E.g., if set
     * to true on a method `foo`, then the callsites of this method will look like `bar.foo` instead of `bar.foo()`. The annotated method must be parameterless and [ ][.parameters] should be empty.
     */
    val structField: Boolean = false,
    /**
     * List of parameters this function accepts.
     */
    val parameters: Array<net.starlark.java.annot.Param> = [],
    /**
     * Defines a catch-all list for additional unspecified positional parameters.
     * 
     * 
     * If this is left as default, it is an error for the caller to pass more positional arguments
     * than are explicitly allowed by the method signature. If this is defined, all additional
     * positional arguments are passed as elements of a [Tuple] to the method.
     * 
     * 
     * See Python's `*args` (http://thepythonguru.com/python-args-and-kwargs/).
     * 
     * 
     * If defined, the annotated method must declare a corresponding parameter to which a `Tuple` may be assigned. See the interface-level javadoc for details.
     */
    // TODO(adonovan): consider using a simpler type than Param here. All that's needed at run-time
    // is a boolean. The doc tools want a name and doc string, but the rest is irrelevant and
    // distracting.
    // Ditto extraKeywords.
    val extraPositionals: net.starlark.java.annot.Param = net.starlark.java.annot.Param(name = ""),
    /**
     * Defines a catch-all dictionary for additional unspecified named parameters.
     * 
     * 
     * If this is left as default, it is an error for the caller to pass any named arguments not
     * explicitly declared by the method signature. If this is defined, all additional named arguments
     * are passed as elements of a `Dict<String, Object>` to the method.
     * 
     * 
     * See Python's `**kwargs` (http://thepythonguru.com/python-args-and-kwargs/).
     * 
     * 
     * If defined, the annotated method must declare a corresponding parameter to which a `Dict<String, Object>` may be assigned. See the interface-level javadoc for details.
     */
    val extraKeywords: net.starlark.java.annot.Param = net.starlark.java.annot.Param(name = ""),
    /**
     * If true, indicates that the class containing the annotated method has the ability to be called
     * from Starlark (as if it were a function) and that the annotated method should be invoked when
     * this occurs.
     * 
     * 
     * A class may only have one method with selfCall set to true.
     * 
     * 
     * A method with selfCall=true must not be a structField, and must have name specified (used
     * for descriptive errors if, for example, there are missing arguments).
     */
    val selfCall: Boolean = false,
    /**
     * Permits the Java method to return null, which [Starlark.fromJava] then converts to [ ][Starlark.NONE]. If false, a null result causes the Starlark call to fail.
     */
    val allowReturnNones: Boolean = false,
    /**
     * If true, the StarlarkThread will be passed as an argument of the annotated function. (Thus, the
     * annotated method signature must contain StarlarkThread as a parameter. See the interface-level
     * javadoc for details.)
     * 
     * 
     * This is incompatible with structField=true. If structField is true, this must be false.
     */
    val useStarlarkThread: Boolean = false,
    /**
     * If true, the Starlark semantics will be passed to the annotated Java method. (Thus, the
     * annotated method signature must contain StarlarkSemantics as a parameter. See the
     * interface-level javadoc for details.)
     * 
     * 
     * This option is allowed only for fields (`structField=true`). For methods, the `StarlarkThread` parameter provides access to the semantics, and more.
     */
    val useStarlarkSemantics: Boolean = false,
    /**
     * Whether this method can act as a type in a type expression.
     * 
     * 
     * An example would be the `list` builtin symbol.
     * 
     * 
     * If true, the class identified by the Java method's return type is taken to be the Java class
     * whose instances are Starlark values of this Starlark type. For example, `list()` is
     * implemented by [MethodLibrary.list], whose return type is [StarlarkList], and
     * instances of `StarlarkList` are Starlark values of the `list` type.
     * 
     * 
     * The return type's class must define a static method with the signature:
     * 
     * <pre>
     * public static TypeConstructor getAssociatedTypeConstructor() {...}
    </pre> * 
     * 
     * which is reflectively invoked to identify the appropriate type constructor (e.g. [ ][Types.LIST_CONSTRUCTOR]) that will be called when this method appears in a type application.
     */
    val isTypeConstructor: Boolean = false,
    /**
     * If non-empty, the annotated method will only be callable if the given semantic flag is true.
     * Note that at most one of [.enableOnlyWithFlag] and [.disableWithFlag] can be
     * non-empty.
     */
    val enableOnlyWithFlag: String = "",
    /**
     * If non-empty, the annotated method will only be callable if the given semantic flag is false.
     * Note that at most one of [.enableOnlyWithFlag] and [.disableWithFlag] can be
     * non-empty.
     */
    val disableWithFlag: String = ""
)
