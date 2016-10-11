package org.jetbrains.kotlin.native.interop.indexer

import clang.*
import clang.CXIdxEntityKind.*
import clang.CXTypeKind.*
import kotlin_native.interop.*
import java.io.File

private class StructDeclImpl(spelling: String) : StructDecl(spelling) {
    override var def: StructDefImpl? = null
}

private class StructDefImpl(size: Long, decl: StructDecl, hasNaturalLayout: Boolean) :
        StructDef(size, decl, hasNaturalLayout) {

    override val fields = mutableListOf<Field>()
}

private class EnumDefImpl(spelling: String, type: PrimitiveType) : EnumDef(spelling, type) {
    override val values = mutableListOf<EnumValue>()
}

private class NativeIndexImpl : NativeIndex() {

    val arena = Arena()

    fun clearNativeMem() = arena.clear()

    private data class DeclarationID(val usr: String)

    val structById = mutableMapOf<DeclarationID, StructDeclImpl>()

    override val structs: List<StructDecl>
        get() = structById.values.toList()

    val enumById = mutableMapOf<DeclarationID, EnumDefImpl>()

    override val enums: List<EnumDef>
        get() = enumById.values.toList()

    val functionByName = mutableMapOf<String, FunctionDecl>()

    override val functions: List<FunctionDecl>
        get() = functionByName.values.toList()

    fun getDeclarationId(cursor: CXCursor) = DeclarationID(clang_getCursorUSR(cursor, arena).convertAndDispose())

    fun getStructTypeDecl(type: CXType): StructDeclImpl {
        assert (type.kind.value == CXType_Record)
        return getStructDeclAt(clang_getTypeDeclaration(type, arena))
    }

    fun getStructDeclAt(cursor: CXCursor): StructDeclImpl {
        val declId = getDeclarationId(cursor)

        val decl = structById[declId]
        if (decl != null) {
            return decl
        }

        val cursorType = clang_getCursorType(cursor, arena)
        val typeSpelling = clang_getTypeSpelling(cursorType, arena).convertAndDispose()

        val res = StructDeclImpl(typeSpelling)
        structById[declId] = res
        return res
    }

    fun getEnumTypeDef(type: CXType): EnumDefImpl {
        assert (type.kind.value == CXType_Enum)
        val declCursor = clang_getTypeDeclaration(type, arena)
        return getEnumDefAt(declCursor)
    }

    fun getEnumDefAt(cursor: CXCursor): EnumDefImpl {
        if (clang_isCursorDefinition(cursor) == 0) {
            TODO("support enum forward declarations")
        }

        val declId = getDeclarationId(cursor)

        val enum = enumById[declId]
        if (enum != null) {
            return enum
        }

        val cursorType = clang_getCursorType(cursor, arena)
        val typeSpelling = clang_getTypeSpelling(cursorType, arena).convertAndDispose()

        val baseType = convertType(clang_getEnumDeclIntegerType(cursor, arena)) as PrimitiveType

        val res = EnumDefImpl(typeSpelling, baseType)
        enumById[declId] = res
        return res
    }

    /**
     * Computes [StructDef.hasNaturalLayout] property.
     */
    fun structHasNaturalLayout(structDefCursor: CXCursor): Boolean {
        val hasAttributes = malloc(Int32Box)
        hasAttributes.value = 0
        clang_visitChildren(structDefCursor, { cursor, parent, clientData ->
            if (clang_isAttribute(cursor.kind.value) != 0) {
                clientData.asRef(Int32Box)!!.value = 1
            }
            CXChildVisitResult.CXChildVisit_Continue
        }, hasAttributes.ptr)

        return hasAttributes.value == 0
    }

    fun convertType(type: CXType): Type {
        val kind = type.kind.value
        return when (kind) {
            CXType_Unexposed -> {
                val canonicalType = clang_getCanonicalType(type, arena)
                if (canonicalType.kind.value != CXType_Unexposed) {
                    convertType(canonicalType)
                } else {
                    throw NotImplementedError()
                }
            }

            // TODO: is e.g. CXType_Int guaranteed to be int32_t?
            CXType_Void -> VoidType

            CXType_Char_U, CXType_UChar -> UInt8Type
            CXType_Char_S, CXType_SChar -> Int8Type

            CXType_UShort -> UInt16Type
            CXType_Short -> Int16Type

            CXType_UInt -> UInt32Type
            CXType_Int -> Int32Type

            CXType_ULong -> UIntPtrType
            CXType_Long -> IntPtrType

            CXType_ULongLong -> UInt64Type
            CXType_LongLong -> Int64Type

            CXType_Typedef -> {
                val declaration = clang_getTypeDeclaration(type, arena)
                val underlying = clang_getTypedefDeclUnderlyingType(declaration, arena)
                assert (underlying.kind.value != CXType_Invalid)
                convertType(underlying)
            }

            CXType_Record -> RecordType(getStructTypeDecl(type))
            CXType_Enum -> EnumType(getEnumTypeDef(type))

            CXType_Pointer -> PointerType(convertType(clang_getPointeeType(type, arena)))

            CXType_ConstantArray -> {
                val elemType = convertType(clang_getArrayElementType(type, arena))
                val length = clang_getArraySize(type)
                ConstArrayType(elemType, length)
            }

            CXType_IncompleteArray -> {
                val elemType = convertType(clang_getArrayElementType(type, arena))
                IncompleteArrayType(elemType)
            }

            CXType_FunctionProto -> {
                if (clang_isFunctionTypeVariadic(type) != 0) {
                    UnsupportedType
                } else {
                    val returnType = convertType(clang_getResultType(type, arena))
                    val numArgs = clang_getNumArgTypes(type)
                    val paramTypes = (0..numArgs - 1).map {
                        convertType(clang_getArgType(type, it, arena))
                    }
                    FunctionType(paramTypes, returnType)
                }
            }

            else -> UnsupportedType
        }
    }

    fun indexDeclaration(info: CXIdxDeclInfo) {
        val cursor = info.cursor
        val entityInfo = info.entityInfo.value!!
        val entityName = entityInfo.name.value?.asCString()?.toString()
        val kind = entityInfo.kind.value

        when (kind) {
            CXIdxEntity_Field -> {
                val name = entityName!!
                val type = convertType(clang_getCursorType(cursor, arena))
                val offset = clang_Cursor_getOffsetOfField(cursor)

                val container = info.semanticContainer.value!!
                val structDef = getStructDeclAt(container.cursor).def!!
                structDef.fields.add(Field(name, type, offset))
            }

            CXIdxEntity_Struct, CXIdxEntity_Union -> {
                val structDecl = getStructDeclAt(cursor)
                if (clang_isCursorDefinition(cursor) != 0) {
                    val size = clang_Type_getSizeOf(clang_getCursorType(cursor, arena))
                    val hasNaturalLayout = structHasNaturalLayout(cursor)
                    structDecl.def = StructDefImpl(size, structDecl, hasNaturalLayout)
                }
            }

            CXIdxEntity_Function -> {
                val name = entityName!!
                val returnType = convertType(clang_getCursorResultType(cursor, arena))
                val argNum = clang_Cursor_getNumArguments(cursor)
                val args = (0 .. argNum - 1).map {
                    val argCursor = clang_Cursor_getArgument(cursor, it, arena)
                    val argName = clang_getCursorSpelling(argCursor, arena).convertAndDispose()
                    val type = convertType(clang_getCursorType(argCursor, arena))
                    Parameter(argName, type)
                }

                functionByName[name] = FunctionDecl(name, args, returnType)
            }

            CXIdxEntity_Enum -> {
                getEnumDefAt(cursor)
            }

            CXIdxEntity_EnumConstant -> {
                val container = info.semanticContainer.value!!
                val name = entityName!!
                val value = clang_getEnumConstantDeclValue(info.cursor)

                val values = getEnumDefAt(container.cursor).values
                val existingValue = values.find { it.name == name }
                if (existingValue == null) {
                    values.add(EnumValue(name, value))
                } else {
                    // in some cases Clang may index the same definition multiple times; ignore redeclaration
                    // TODO: implement the same fix for structs
                    assert (existingValue.value == value)
                }
            }
        }
    }

}

fun CXString.convertAndDispose(): String {
    try {
        return clang_getCString(this)!!.asCString().toString()
    } finally {
        clang_disposeString(this)
    }
}

fun buildNativeIndexImpl(headerFile: File, args: List<String>): NativeIndex {
    val args1 = args.map { CString.fromString(it)!!.asCharPtr() }.toTypedArray()

    val index = clang_createIndex(0, 0)
    val indexAction = clang_IndexAction_create(index)
    val callbacks = malloc(IndexerCallbacks.Companion)

    val res = NativeIndexImpl()
    try {
        val nativeIndexPtr = StableObjPtr.create(res)
        val clientData = nativeIndexPtr.value

        try {
            with(callbacks) {
                abortQuery.setStatic(null)
                diagnostic.setStatic(null)
                enteredMainFile.setStatic(null)
                ppIncludedFile.setStatic(null)
                importedASTFile.setStatic(null)
                startedTranslationUnit.setStatic(null)
                indexDeclaration.setStatic { clientData, info ->
                    val index = StableObjPtr.fromValue(clientData!!).get() as NativeIndexImpl
                    index.indexDeclaration(info!!)
                }
                indexEntityReference.setStatic(null)
            }

            clang_indexSourceFile(indexAction, clientData, callbacks, IndexerCallbacks.size, 0, headerFile.path,
                    mallocNativeArrayOf(Int8Box.Companion, *args1)[0], args1.size, null, 0, null, 0)


            return res

        } finally {
            nativeIndexPtr.dispose()
        }
    } finally {
        res.clearNativeMem()
    }
}