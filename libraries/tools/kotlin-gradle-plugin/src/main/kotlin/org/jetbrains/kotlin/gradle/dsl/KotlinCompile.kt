/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.gradle.dsl

import groovy.lang.Closure
import org.gradle.api.Task
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.jetbrains.kotlin.cli.common.arguments.CommonToolArguments
import org.jetbrains.kotlin.compilerRunner.ArgumentUtils

interface CompilerArgumentAware<T : CommonToolArguments> {
    @get:Input
    val serializedCompilerArguments: List<String>
        get() = ArgumentUtils.convertArgumentsToStringList(prepareCompilerArguments())

    @get:Internal
    val defaultSerializedCompilerArguments: List<String>
        get() = createCompilerArgs()
                .also { setupCompilerArgs(it, defaultsOnly = true) }
                .let(ArgumentUtils::convertArgumentsToStringList)

    fun createCompilerArgs(): T
    fun setupCompilerArgs(args: T, defaultsOnly: Boolean = false)
}

internal fun <T : CommonToolArguments> CompilerArgumentAware<T>.prepareCompilerArguments() =
        createCompilerArgs().also { setupCompilerArgs(it) }

interface KotlinCompile<T : KotlinCommonOptions> : Task {
    @get:Internal
    val kotlinOptions: T

    fun kotlinOptions(fn: T.() -> Unit) {
        kotlinOptions.fn()
    }

    fun kotlinOptions(fn: Closure<*>) {
        fn.delegate = kotlinOptions
        fn.call()
    }
}

interface KotlinJsCompile : KotlinCompile<KotlinJsOptions>

interface KotlinJvmCompile : KotlinCompile<KotlinJvmOptions>

interface KotlinCommonCompile : KotlinCompile<KotlinMultiplatformCommonOptions>

interface KotlinJsDce : Task {
    val dceOptions: KotlinJsDceOptions

    val keep: MutableList<String>

    fun dceOptions(fn: KotlinJsDceOptions.() -> Unit) {
        dceOptions.fn()
    }

    fun dceOptions(fn: Closure<*>) {
        fn.delegate = dceOptions
        fn.call()
    }

    fun keep(vararg fqn: String)
}