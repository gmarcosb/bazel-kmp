// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.autocodec

import kotlin.reflect.KClass

/**
 * Specifies that AutoCodec should generate a codec implementation for the annotated class. This is
 * generally only needed in the following cases:
 * 
 * 
 *  1. Interning work. [com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey]
 * 
 *  1. Non-trivial calculations and field initialization. [ ] 
 *  1. Some paths are forbidden for DynamicCodec. [ ] 
 * 
 * 
 * 
 * Example:
 * 
 * <pre>`classTarget { }</pre> The { _AutoCodec} suffix is added to the { Target} to obtain the generated class name. In the example, that results in a class named { Target_AutoCodec} but applications should not need to directly access the generated class.`
</pre> */
@Target(AnnotationTarget.CLASS)
annotation class AutoCodec(
    /**
     * Checks whether or not this class is allowed to be serialized. See [ ][com.google.devtools.build.lib.skyframe.serialization.SerializationContext.checkClassExplicitlyAllowed].
     */
    val checkClassExplicitlyAllowed: Boolean = false,
    /**
     * Adds an explicitly allowed class for this serialization session. See [ ][com.google.devtools.build.lib.skyframe.serialization.SerializationContext.addExplicitlyAllowedClass].
     */
    val explicitlyAllowClass: Array<KClass<*>> = [],
    /**
     * An interface that the deserialized object must implement. If this is set, the deserialized
     * object will be of a different class than the originally serialized object (that is, the class
     * tagged by this annotation). This special class will be a subclass of the original and implement
     * the given interface.
     * 
     * 
     * If this is set, the annotated class *must* have a constructor as its [ ], *must not* be final, and *must not* be a non-static nested class.
     * (In other words, it must be trivially subclassable.)
     */
    val deserializedInterface: KClass<*> = Unit::class,
    /**
     * Whether or not the generated codec should be automatically registered. See [ ][com.google.devtools.build.lib.skyframe.serialization.ObjectCodec.autoRegister].
     */
    val autoRegister: Boolean = true
) {
    // AutoCodec works by determining a unique *instantiator*, either a constructor or factory method,
    // to serve as a specification for serialization. The @AutoCodec.Instantiator tag can be helpful
    // for marking a specific instantiator.
    //
    // AutoCodec inspects the parameters of the instantiator and finds fields of the class
    // corresponding in both name and type. For serialization, it generates code that reads those
    // fields using reflection. For deserialization it generates code to invoke the instantiator.
    /**
     * Marks a specific method to use as the instantiator.
     * 
     * 
     * This marking is required when the class has more than one constructor.
     * 
     * 
     * Indicates an instantiator, either a constructor or factory method, for codec generation. A
     * compile-time error will result if multiple methods are thus tagged.
     */
    @Target(
        AnnotationTarget.CONSTRUCTOR,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER
    )
    annotation class Instantiator

    /**
     * Marks a static method to use for interning.
     * 
     * 
     * The method must accept an instance of the enclosing `AutoCodec` tagged class and
     * return an instance of the tagged class.
     */
    @Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
    annotation class Interner
}
