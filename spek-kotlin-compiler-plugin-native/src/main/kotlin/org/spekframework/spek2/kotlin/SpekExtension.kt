package org.spekframework.spek2.kotlin

import org.jetbrains.kotlin.backend.common.BackendContext
import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.descriptors.WrappedSimpleFunctionDescriptor
import org.jetbrains.kotlin.backend.common.descriptors.synthesizedName
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.reportWarning
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrExpressionBodyImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionReferenceImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

class SpekExtension : IrGenerationExtension {
    override fun generate(file: IrFile, backendContext: BackendContext, bindingContext: BindingContext) {
        val spekCollector = SpekCollector(file, backendContext)
        file.acceptChildrenVoid(spekCollector)
        spekCollector.generateRegistrations()
    }
}

private class SpekCollector(
        private val file: IrFile,
        private val backendContext: BackendContext
) : IrElementVisitorVoid {
    private val spekClassName = "org.spekframework.spek2.Spek"
    private val commonContext: CommonBackendContext = backendContext.ir.context
    private var collectedSpeks = mutableListOf<IrClass>()

    override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
    }

    override fun visitClass(declaration: IrClass) {
        super.visitClass(declaration)

        if (!declaration.isSpek) {
            return
        }

        if (declaration.kind != ClassKind.OBJECT) {
            if (!declaration.isAbstract) {
                commonContext.reportWarning("Declaration ${declaration.name} inherits from $spekClassName but is not an object (it has kind ${declaration.kind}) and so will be not be run.", file, declaration)
            }

            return
        }

        collectedSpeks.add(declaration)
    }

    fun generateRegistrations() {
        collectedSpeks.forEach { generateRegistration(it) }
    }

    // All of this is trying to create a call that looks like this:
    // registerSpek(SpecObject::class, { SpecObject })
    private fun generateRegistration(declaration: IrClass) {
        val testInfoClassDescriptor = backendContext.builtIns.builtInsModule.resolveClassByFqName(FqName.fromSegments(listOf("org", "spekframework", "spek2", "launcher", "TestInfo")), NoLookupLocation.FROM_BACKEND)!!
        val testInfoClass = backendContext.ir.symbols.externalSymbolTable.referenceClass(testInfoClassDescriptor)

        commonContext.createIrBuilder(file.symbol, file.startOffset, file.endOffset).run {
            val primaryConstructor = testInfoClass.constructors.first()
            val call = irCall(primaryConstructor).apply {
                // TODO: should both of the IrType parameters below be declaration.defaultType?
                // Should one be the equivalent of KClass<Spek> or KClass<DerivedSpek>?
                val classReference = IrClassReferenceImpl(startOffset, endOffset, declaration.defaultType, declaration.symbol, declaration.defaultType)
                putValueArgument(0, classReference)

                val factoryType = primaryConstructor.owner.valueParameters[1].type
                val factoryBlock = createFactoryLambdaBlock(declaration, factoryType)

                putValueArgument(1, factoryBlock)
            }
            file.addTopLevelInitializer(call, backendContext)
        }
    }

    private fun createFactoryLambdaBlock(declaration: IrClass, factoryType: IrType): IrBlock {
        return IrBlockImpl(
                declaration.startOffset,
                declaration.endOffset,
                factoryType,
                IrStatementOrigin.LAMBDA
        ).apply {
            val factory = createFactoryLambda(declaration)

            val factoryReference = IrFunctionReferenceImpl(
                    factory.startOffset,
                    factory.endOffset,
                    factoryType,
                    factory.symbol,
                    factory.descriptor,
                    0,
                    0,
                    IrStatementOrigin.LAMBDA
            )

            statements.add(factory)
            statements.add(factoryReference)
        }
    }

    private fun createFactoryLambda(declaration: IrClass): IrFunction = WrappedSimpleFunctionDescriptor().let { descriptor ->
        IrFunctionImpl(
                declaration.startOffset,
                declaration.endOffset,
                IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA,
                IrSimpleFunctionSymbolImpl(descriptor),
                Name.special("<anonymous>"),
                Visibilities.LOCAL,
                Modality.FINAL,
                declaration.defaultType,
                isInline = false,
                isExternal = false,
                isTailrec = false,
                isSuspend = false
        ).apply {
            descriptor.bind(this)
            parent = this@SpekCollector.file

            body = backendContext.createIrBuilder(symbol, symbol.owner.startOffset, symbol.owner.endOffset).irBlockBody {
                +irReturn(irGetObject(declaration.symbol))
            }
        }
    }

    private val IrClass.isSpek: Boolean
        get() = superTypes.any { it.classifierOrFail.descriptor.fqNameSafe.asString() == spekClassName }

    private val IrClass.isAbstract: Boolean
        get() = this.modality == Modality.ABSTRACT
}

private var topLevelInitializersCounter = 0

// originally taken from org.jetbrains.kotlin.ir.util/IrUtils2.kt but reworked
// 1. creates a top level field
// 2. creates a property using the previously declared field as its backing field
// Previously a top level field would cause the object to created but now it requires a property
private fun IrFile.addTopLevelInitializer(expression: IrExpression, context: BackendContext) {
    val threadLocalAnnotation = context.builtIns.builtInsModule.findClassAcrossModuleDependencies(
            ClassId.topLevel(FqName("kotlin.native.concurrent.ThreadLocal")))!!
    val t = context.ir.symbols.externalSymbolTable.referenceClass(threadLocalAnnotation)
    val fieldName = "topLevelInitializer${topLevelInitializersCounter++}".synthesizedName

    val field = addField {
        name = fieldName
        isFinal = true
        isStatic = true
        visibility = Visibilities.PRIVATE
        type = expression.type
        origin = IrDeclarationOrigin.PROPERTY_BACKING_FIELD
    }.also { field ->
        field.parent = this@addTopLevelInitializer
        field.initializer = IrExpressionBodyImpl(startOffset, endOffset, expression)
        field.annotations += context.createIrBuilder(field.symbol, startOffset, endOffset).irCallConstructor(t.constructors.first(), emptyList())
    }

    addProperty {
        name = fieldName
        visibility = Visibilities.PRIVATE
        origin = IrDeclarationOrigin.DEFINED
    }.also { property ->
        property.backingField = field
        property.getter = buildFun {
            origin = IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR
            name = "get-${fieldName.identifier}".synthesizedName
            returnType = field.type
            visibility = Visibilities.PRIVATE
        }.also { func ->
            func.parent = this@addTopLevelInitializer
            func.body = context.createIrBuilder(func.symbol, func.startOffset, func.endOffset).irBlockBody {
                +irReturn(irGetField(null, field))
            }
        }
    }
}

