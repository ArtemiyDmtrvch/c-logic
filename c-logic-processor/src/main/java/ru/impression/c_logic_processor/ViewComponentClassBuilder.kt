package ru.impression.c_logic_processor

import com.squareup.kotlinpoet.*
import ru.impression.c_logic_annotations.Bindable
import java.util.*
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.TypeElement
import javax.lang.model.type.DeclaredType
import javax.lang.model.type.TypeMirror
import kotlin.collections.ArrayList

class ViewComponentClassBuilder(
    scheme: TypeElement,
    resultClassName: String,
    resultClassPackage: String,
    superclass: TypeName,
    viewModelClass: TypeMirror,
    private val viewModelIsShared: Boolean
) : ComponentClassBuilder(
    scheme,
    resultClassName,
    resultClassPackage,
    superclass,
    viewModelClass
) {

    private val bindableProperties = ArrayList<BindableProperty>().apply {
        val viewModelEnclosedElements =
            (viewModelClass as DeclaredType).asElement().enclosedElements
        viewModelEnclosedElements.forEach { viewModelElement ->
            viewModelElement.getAnnotation(Bindable::class.java)?.let { annotation ->
                val propertyName = viewModelElement.toString().substringBefore('$')
                val capitalizedPropertyName = propertyName.substring(0, 1)
                    .toUpperCase(Locale.getDefault()) + propertyName.substring(1)
                val propertyGetter =
                    viewModelEnclosedElements.first { it.toString() == "get$capitalizedPropertyName()" }
                val propertyType = (propertyGetter as ExecutableElement).returnType
                add(
                    BindableProperty(
                        propertyName,
                        capitalizedPropertyName,
                        propertyType,
                        annotation.twoWay,
                        "${propertyName}AttrChanged"
                    )
                )
            }
        }
    }

    override fun buildViewModelProperty() =
        with(
            PropertySpec.builder(
                "viewModel",
                viewModelClass.asTypeName().let { if (viewModelIsShared) it.copy(true) else it })
        ) {
            if (viewModelIsShared) mutable(true)
            addModifiers(KModifier.OVERRIDE)
            initializer(
                "%M($viewModelClass::class)",
                MemberName("ru.impression.c_logic_base", "createViewModel")
            )
            build()
        }

    override fun buildContainerProperty() =
        with(PropertySpec.builder("container", ClassName("android.view", "View"))) {
            addModifiers(KModifier.OVERRIDE)
            initializer("this")
            build()
        }

    override fun buildLifecycleOwnerProperty() = with(
        PropertySpec.builder(
            "lifecycleOwner",
            ClassName("androidx.lifecycle", "LifecycleOwner")
        )
    ) {
        addModifiers(KModifier.OVERRIDE)
        initializer("this")
        build()
    }

    override fun TypeSpec.Builder.addRestMembers() {
        addSuperinterface(ClassName("androidx.lifecycle", "LifecycleOwner"))
        primaryConstructor(buildConstructor())
        addSuperclassConstructorParameter("context")
        addSuperclassConstructorParameter("attrs")
        addSuperclassConstructorParameter("defStyleAttr")
        addProperty(buildLifecycleRegistryProperty())
        addProperty(buildIsDetachedFromWindowProperty())
        bindableProperties.forEach {
            if (it.twoWay) addProperty(buildBindablePropertyAttrChangedProperty(it))
        }
        addInitializerBlock(buildInitializerBlock())
        addFunction(buildGetLifecycleFunction())
        if (bindableProperties.firstOrNull { it.twoWay } != null)
            addFunction(buildStartObservationsFunction())
        addFunction(buildOnAttachedToWindowFunction())
        if (viewModelIsShared) addFunction(buildRestoreViewModelFunction())
        addFunction(buildOnDetachedFromWindowFunction())
        if (viewModelIsShared) addFunction(buildReleaseViewModelFunction())
        addType(buildCompanionObject())
    }

    private fun buildConstructor(): FunSpec = with(FunSpec.constructorBuilder()) {
        addParameter("context", ClassName("android.content", "Context"))
        addParameter(
            ParameterSpec.builder(
                "attrs",
                ClassName("android.util", "AttributeSet").copy(true)
            ).defaultValue("null").build()
        )
        addParameter(
            ParameterSpec.builder("defStyleAttr", Int::class).defaultValue("0").build()
        )
        addAnnotation(JvmOverloads::class)
        build()
    }

    private fun buildLifecycleRegistryProperty() =
        with(
            PropertySpec.builder(
                "lifecycleRegistry",
                ClassName("androidx.lifecycle", "LifecycleRegistry")
            )
        ) {
            addModifiers(KModifier.PRIVATE)
            initializer("%T(this)", ClassName("androidx.lifecycle", "LifecycleRegistry"))
            build()
        }

    private fun buildIsDetachedFromWindowProperty() =
        with(PropertySpec.builder("isDetachedFromWindow", Boolean::class.java)) {
            mutable(true)
            addModifiers(KModifier.PRIVATE)
            initializer("false")
            build()
        }

    private fun buildBindablePropertyAttrChangedProperty(bindableProperty: BindableProperty) =
        with(
            PropertySpec.builder(
                bindableProperty.attrChangedPropertyName.toString(),
                ClassName("androidx.databinding", "InverseBindingListener").copy(true)
            )
        ) {
            mutable(true)
            addModifiers(KModifier.PRIVATE)
            initializer("null")
            build()
        }

    private fun buildInitializerBlock() = with(CodeBlock.builder()) {
        addStatement(
            """
                lifecycleRegistry.handleLifecycleEvent(%T.Event.ON_START)
                render()
                startObservations()""".trimIndent(),
            ClassName("androidx.lifecycle", "Lifecycle")
        )
        build()
    }

    private fun buildGetLifecycleFunction() = with(FunSpec.builder("getLifecycle")) {
        addModifiers(KModifier.OVERRIDE)
        addCode(
            """
                return lifecycleRegistry
            """.trimIndent(),
            MemberName("ru.impression.c_logic_base", "createViewModel"),
            MemberName("ru.impression.c_logic_base", "setViewModel")
        )
        build()
    }

    private fun buildStartObservationsFunction() =
        with(FunSpec.builder("startObservations")) {
            addModifiers(KModifier.OVERRIDE)
            addCode(
                """
                    super.startObservations()
                    """.trimIndent()
            )
            if (viewModelIsShared) addCode(
                """
                    
                    val viewModel = viewModel ?: return
                    """.trimIndent()
            )
            addCode(
                """
                    
                    viewModel.addOnStatePropertyChangedListener(this) { property, _ ->
                      when (property) {
            """.trimIndent()
            )
            bindableProperties.forEach {
                addCode(
                    """
                        
                              viewModel::${it.name} -> ${it.name}AttrChanged?.onChange()
                    """.trimIndent()
                )
            }
            addCode(
                """
                    
                      }
                    }
                    """.trimIndent()
            )
            build()
        }

    private fun buildOnAttachedToWindowFunction() = with(FunSpec.builder("onAttachedToWindow")) {
        addModifiers(KModifier.OVERRIDE)
        addCode(
            """
                super.onAttachedToWindow()
                lifecycleRegistry.handleLifecycleEvent(%T.Event.ON_RESUME)
                if (isDetachedFromWindow) {
                  isDetachedFromWindow = false
                  
                  """.trimIndent(),
            ClassName("androidx.lifecycle", "Lifecycle")
        )
        if (viewModelIsShared) addCode(
            """
                restoreViewModel()
                
            """.trimIndent()
        )
        addCode(
            """
                  startObservations()
                }
                """.trimIndent()
        )
        build()
    }

    private fun buildRestoreViewModelFunction() = with(FunSpec.builder("restoreViewModel")) {
        addCode(
            """
                viewModel = %M($viewModelClass::class)
                render()
                """.trimIndent(),
            MemberName("ru.impression.c_logic_base", "createViewModel")
        )
        build()
    }

    private fun buildOnDetachedFromWindowFunction() =
        with(FunSpec.builder("onDetachedFromWindow")) {
            addModifiers(KModifier.OVERRIDE)
            addCode(
                """
                    super.onDetachedFromWindow()
                    lifecycleRegistry.handleLifecycleEvent(%T.Event.ON_DESTROY)
                    isDetachedFromWindow = true
                    """.trimIndent(),
                ClassName("androidx.lifecycle", "Lifecycle")
            )
            if (viewModelIsShared) addCode(
                """
                    
                    releaseViewModel()
                    """.trimIndent()
            )
            build()
        }

    private fun buildReleaseViewModelFunction() =
        with(FunSpec.builder("releaseViewModel")) {
            addCode(
                """
                    viewModel = null
                    render()
                    """.trimIndent(),
                MemberName("ru.impression.c_logic_base", "setViewModel")
            )
            addModifiers(KModifier.PRIVATE)
            build()
        }

    private fun buildCompanionObject(): TypeSpec = with(TypeSpec.companionObjectBuilder()) {
        bindableProperties.forEach {
            addFunction(buildSetBindablePropertyFunction(it))
            if (it.twoWay) {
                addFunction(buildSetBindablePropertyAttrChangedFunction(it))
                addFunction(buildGetBindablePropertyFunction(it))
            }
        }
        build()
    }

    private fun buildSetBindablePropertyFunction(bindableProperty: BindableProperty) =
        with(FunSpec.builder("set${bindableProperty.capitalizedName}")) {
            addAnnotation(JvmStatic::class.java)
            addAnnotation(
                AnnotationSpec.builder(
                    ClassName("androidx.databinding", "BindingAdapter")
                ).addMember("%S", bindableProperty.name).build()
            )
            addParameter("view", ClassName(resultClassPackage, resultClassName))
            addParameter("value", bindableProperty.type.asTypeName().javaToKotlinType().copy(true))
            addCode("view.viewModel${if (viewModelIsShared) "?." else "."}${bindableProperty.name} = value${if (!bindableProperty.type.asTypeName().isNullable) " ?: return" else ""}")
            build()
        }

    private fun buildSetBindablePropertyAttrChangedFunction(bindableProperty: BindableProperty) =
        with(FunSpec.builder("set${bindableProperty.capitalizedName}AttrChanged")) {
            addAnnotation(JvmStatic::class.java)
            addAnnotation(
                AnnotationSpec.builder(
                    ClassName("androidx.databinding", "BindingAdapter")
                ).addMember("%S", bindableProperty.attrChangedPropertyName.toString()).build()
            )
            addParameter("view", ClassName(resultClassPackage, resultClassName))
            addParameter(
                "value",
                ClassName("androidx.databinding", "InverseBindingListener").copy(true)
            )
            addCode("view.${bindableProperty.attrChangedPropertyName} = value")
            build()
        }

    private fun buildGetBindablePropertyFunction(bindableProperty: BindableProperty) =
        with(FunSpec.builder("get${bindableProperty.capitalizedName}")) {
            addAnnotation(JvmStatic::class.java)
            addAnnotation(
                AnnotationSpec.builder(
                    ClassName("androidx.databinding", "InverseBindingAdapter")
                ).addMember("attribute = %S", bindableProperty.name).build()
            )
            addParameter("view", ClassName(resultClassPackage, resultClassName))
            returns(bindableProperty.type.asTypeName().javaToKotlinType().copy(true))
            addCode("return view.viewModel${if (viewModelIsShared) "?." else "."}${bindableProperty.name}")
            build()
        }

    class BindableProperty(
        val name: String,
        val capitalizedName: String,
        val type: TypeMirror,
        val twoWay: Boolean,
        val attrChangedPropertyName: String?
    )
}