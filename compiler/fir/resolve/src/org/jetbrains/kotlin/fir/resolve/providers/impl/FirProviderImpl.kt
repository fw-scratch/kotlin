/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.providers.impl

import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.synthetic.FirSyntheticProperty
import org.jetbrains.kotlin.fir.resolve.providers.FirProvider
import org.jetbrains.kotlin.fir.resolve.providers.FirProviderInternals
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.scopes.KotlinScopeProvider
import org.jetbrains.kotlin.fir.scopes.impl.nestedClassifierScope
import org.jetbrains.kotlin.fir.symbols.CallableId
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.visitors.FirDefaultVisitor
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

class FirProviderImpl(val session: FirSession, val kotlinScopeProvider: KotlinScopeProvider) : FirProvider() {
    override fun getFirCallableContainerFile(symbol: FirCallableSymbol<*>): FirFile? {
        symbol.overriddenSymbol?.let {
            return getFirCallableContainerFile(it)
        }
        if (symbol is FirAccessorSymbol) {
            val fir = symbol.fir
            if (fir is FirSyntheticProperty) {
                return getFirCallableContainerFile(fir.getter.delegate.symbol)
            }
        }
        return state.callableContainerMap[symbol]
    }

    override fun getClassLikeSymbolByFqName(classId: ClassId): FirClassLikeSymbol<*>? {
        return getFirClassifierByFqName(classId)?.symbol
    }

    override fun getTopLevelCallableSymbols(packageFqName: FqName, name: Name): List<FirCallableSymbol<*>> {
        return getCachedTopLevelCallableSymbolsOrNull(packageFqName, name) ?: emptyList()
    }

    fun getCachedTopLevelCallableSymbolsOrNull(packageFqName: FqName, name: Name): List<FirCallableSymbol<*>>? {
        return state.callableMap[CallableId(packageFqName, null, name)]
    }

    override fun getNestedClassifierScope(classId: ClassId): FirScope? {
        return (getFirClassifierByFqName(classId) as? FirRegularClass)?.let {
            nestedClassifierScope(it)
        }
    }

    override fun getFirClassifierContainerFile(fqName: ClassId): FirFile {
        return state.classifierContainerFileMap[fqName] ?: error("Couldn't find container for $fqName")
    }

    override fun getFirClassifierContainerFileIfAny(fqName: ClassId): FirFile? {
        return state.classifierContainerFileMap[fqName]
    }

    override fun getClassNamesInPackage(fqName: FqName): Set<Name> {
        return state.classesInPackage[fqName] ?: emptySet()
    }

    fun recordFile(file: FirFile) {
        recordFile(file, state)
    }

    @FirProviderInternals
    override fun recordGeneratedClass(owner: FirAnnotatedDeclaration, klass: FirRegularClass) {
        klass.accept(FirRecorder, state to owner.file)
    }

    @FirProviderInternals
    override fun recordGeneratedMember(owner: FirAnnotatedDeclaration, klass: FirDeclaration) {
        klass.accept(FirRecorder, state to owner.file)
    }

    private val FirAnnotatedDeclaration.file: FirFile
        get() = when (this) {
            is FirFile -> this
            is FirRegularClass -> getFirClassifierContainerFile(this.symbol.classId)
            else -> error("Should not be here")
        }

    private fun recordFile(file: FirFile, state: State) {
        val packageName = file.packageFqName
        state.fileMap.merge(packageName, listOf(file)) { a, b -> a + b }

        file.acceptChildren(FirRecorder, state to file)
    }

    object FirRecorder : FirDefaultVisitor<Unit, Pair<State, FirFile>>() {
        override fun visitElement(element: FirElement, data: Pair<State, FirFile>) {}

        override fun visitRegularClass(regularClass: FirRegularClass, data: Pair<State, FirFile>) {
            val classId = regularClass.symbol.classId
            val (state, file) = data
            state.classifierMap[classId] = regularClass
            state.classifierContainerFileMap[classId] = file

            if (!classId.isNestedClass && !classId.isLocal) {
                state.classesInPackage.getOrPut(classId.packageFqName, ::mutableSetOf).add(classId.shortClassName)
            }

            regularClass.acceptChildren(this, data)
        }

        override fun visitTypeAlias(typeAlias: FirTypeAlias, data: Pair<State, FirFile>) {
            val classId = typeAlias.symbol.classId
            val (state, file) = data
            state.classifierMap[classId] = typeAlias
            state.classifierContainerFileMap[classId] = file
        }

        override fun <F : FirCallableDeclaration<F>> visitCallableDeclaration(callableDeclaration: FirCallableDeclaration<F>, data: Pair<State, FirFile>) {
            val symbol = callableDeclaration.symbol
            val callableId = symbol.callableId
            val (state, file) = data
            state.callableMap.merge(callableId, listOf(symbol)) { a, b -> a + b }
            state.callableContainerMap[symbol] = file
        }

        override fun visitConstructor(constructor: FirConstructor, data: Pair<State, FirFile>) {
            visitCallableDeclaration(constructor, data)
        }

        override fun visitSimpleFunction(simpleFunction: FirSimpleFunction, data: Pair<State, FirFile>) {
            visitCallableDeclaration(simpleFunction, data)
        }

        override fun visitProperty(property: FirProperty, data: Pair<State, FirFile>) {
            visitCallableDeclaration(property, data)
        }

        override fun visitEnumEntry(enumEntry: FirEnumEntry, data: Pair<State, FirFile>) {
            visitCallableDeclaration(enumEntry, data)
        }
    }

    private val state = StateImpl()

    abstract class State {
        abstract val fileMap: MutableMap<FqName, List<FirFile>>
        abstract val classifierMap: MutableMap<ClassId, FirClassLikeDeclaration<*>>
        abstract val classifierContainerFileMap: MutableMap<ClassId, FirFile>
        abstract val classesInPackage: MutableMap<FqName, MutableSet<Name>>
        abstract val callableMap: MutableMap<CallableId, List<FirCallableSymbol<*>>>
        abstract val callableContainerMap: MutableMap<FirCallableSymbol<*>, FirFile>
    }

    class StateImpl : State() {
        override val fileMap: MutableMap<FqName, List<FirFile>> = mutableMapOf()
        override val classifierMap: MutableMap<ClassId, FirClassLikeDeclaration<*>> = mutableMapOf()
        override val classifierContainerFileMap: MutableMap<ClassId, FirFile> = mutableMapOf()
        override val classesInPackage: MutableMap<FqName, MutableSet<Name>> = mutableMapOf()
        override val callableMap: MutableMap<CallableId, List<FirCallableSymbol<*>>> = mutableMapOf()
        override val callableContainerMap: MutableMap<FirCallableSymbol<*>, FirFile> = mutableMapOf()

        @TestOnly
        fun setFrom(other: State) {
            fileMap.clear()
            classifierMap.clear()
            classifierContainerFileMap.clear()
            callableMap.clear()
            callableContainerMap.clear()

            fileMap.putAll(other.fileMap)
            classifierMap.putAll(other.classifierMap)
            classifierContainerFileMap.putAll(other.classifierContainerFileMap)
            callableMap.putAll(other.callableMap)
            callableContainerMap.putAll(other.callableContainerMap)
            classesInPackage.putAll(other.classesInPackage)
        }
    }

    override fun getFirFilesByPackage(fqName: FqName): List<FirFile> {
        return getCachedFirFilesByPackage(fqName).orEmpty()
    }

    fun getCachedFirFilesByPackage(fqName: FqName): List<FirFile>? {
        return state.fileMap[fqName]
    }

    override fun getFirClassifierByFqName(classId: ClassId): FirClassLikeDeclaration<*>? {
        require(!classId.isLocal) {
            "Local $classId should never be used to find its corresponding classifier"
        }
        return state.classifierMap[classId]
    }

    @TestOnly
    fun ensureConsistent(files: List<FirFile>) {
        val newState = StateImpl()
        files.forEach { recordFile(it, newState) }

        val failures = mutableListOf<String>()

        fun <K, V> checkMapDiff(
            title: String,
            a: Map<K, V>,
            b: Map<K, V>,
            equal: (old: V?, new: V?) -> Boolean = { old, new -> old === new }
        ) {
            var hasTitle = false
            val unionKeys = a.keys + b.keys

            for ((key, aValue, bValue) in unionKeys.map { Triple(it, a[it], b[it]) }) {
                if (!equal(aValue, bValue)) {
                    if (!hasTitle) {
                        failures += title
                        hasTitle = true
                    }
                    failures += "diff at key = '$key': was: '$aValue', become: '$bValue'"
                }
            }
        }

        fun <K, V> checkMMapDiff(title: String, a: Map<K, List<V>>, b: Map<K, List<V>>) {
            var hasTitle = false
            val unionKeys = a.keys + b.keys
            for ((key, aValue, bValue) in unionKeys.map { Triple(it, a[it], b[it]) }) {
                if (aValue == null || bValue == null) {
                    if (!hasTitle) {
                        failures += title
                        hasTitle = true
                    }
                    failures += "diff at key = '$key': was: $aValue, become: $bValue"
                } else {
                    val aSet = aValue.toSet()
                    val bSet = bValue.toSet()

                    val aLost = aSet - bSet
                    val bNew = bSet - aSet
                    if (aLost.isNotEmpty() || bNew.isNotEmpty()) {
                        failures += "diff at key = '$key':"
                        failures += "    Lost:"
                        aLost.forEach { failures += "     $it" }
                        failures += "    New:"
                        bNew.forEach { failures += "     $it" }
                    }
                }
            }

        }

        checkMMapDiff("fileMap", state.fileMap, newState.fileMap)
        checkMapDiff("classifierMap", state.classifierMap, newState.classifierMap)
        checkMapDiff("classifierContainerFileMap", state.classifierContainerFileMap, newState.classifierContainerFileMap)
        checkMMapDiff("callableMap", state.callableMap, newState.callableMap)
        checkMapDiff("callableContainerMap", state.callableContainerMap, newState.callableContainerMap)

        if (!rebuildIndex) {
            assert(failures.isEmpty()) {
                failures.joinToString(separator = "\n")
            }
        } else {
            state.setFrom(newState)
        }
    }
}

private const val rebuildIndex = true
