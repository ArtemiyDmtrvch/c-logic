package ru.impression.ui_generator_processor

import com.squareup.kotlinpoet.*
import javax.lang.model.element.TypeElement
import javax.lang.model.type.TypeMirror

class FragmentComponentClassBuilder(
    scheme: TypeElement,
    resultClassName: String,
    resultClassPackage: String,
    superclass: TypeName,
    viewModelClass: TypeMirror
) : ComponentClassBuilder(
    scheme,
    resultClassName,
    resultClassPackage,
    superclass,
    viewModelClass
) {

    override fun buildViewModelProperty() =
        with(PropertySpec.builder("viewModel", viewModelClass.asTypeName())) {
            addModifiers(KModifier.OVERRIDE)
            delegate(if (propProperties.isEmpty()) CodeBlock.of("lazy { createViewModel($viewModelClass::class) } ") else
                with(CodeBlock.builder()) {
                    add(
                        """
                        lazy { 
                          val viewModel = createViewModel($viewModelClass::class)
                        
                        """.trimIndent()
                    )
                    propProperties.forEach {
                        add(
                            """
                                if (${it.name} != null && ${it.name} !== viewModel.${it.name})
                                  viewModel::${it.name}.%M(${it.name})
                                  viewModel.onStateChanged(renderImmediately = true)

                                  """.trimIndent(),
                            MemberName("ru.impression.ui_generator_base", "nullSafetySet")
                        )
                    }
                    add(
                        """
                            viewModel
                            }
                        """.trimIndent()
                    )
                    build()
                })
            build()
        }

    override fun buildContainerProperty() =
        with(PropertySpec.builder("container", ClassName("android.view", "View").copy(true))) {
            mutable(true)
            addModifiers(KModifier.OVERRIDE)
            initializer("null")
            build()
        }

    override fun buildBoundLifecycleOwnerProperty() = with(
        PropertySpec.builder(
            "boundLifecycleOwner",
            ClassName("androidx.lifecycle", "LifecycleOwner")
        )
    ) {
        addModifiers(KModifier.OVERRIDE)
        getter(FunSpec.getterBuilder().addCode("return viewLifecycleOwner").build())
        build()
    }

    override fun TypeSpec.Builder.addRestMembers() {
        propProperties.forEach { addProperty(buildPropWrapperProperty(it)) }
        addFunction(buildOnCreateViewFunction())
        addFunction(buildOnActivityCreatedFunction())
        addFunction(buildOnDestroyViewFunction())
    }

    private fun buildPropWrapperProperty(propProperty: PropProperty) = with(
        PropertySpec.builder(
            propProperty.name,
            propProperty.type.asTypeName().javaToKotlinType().copy(true)
        )
    ) {
        mutable(true)
        initializer("null")
        build()
    }

    private fun buildOnCreateViewFunction() = with(FunSpec.builder("onCreateView")) {
        addModifiers(KModifier.OVERRIDE)
        addParameter("inflater", ClassName("android.view", "LayoutInflater"))
        addParameter("container", ClassName("android.view", "ViewGroup").copy(true))
        addParameter("savedInstanceState", ClassName("android.os", "Bundle").copy(true))
        returns(ClassName("android.view", "View").copy(true))
        addCode(
            """
                this.container = container
                return render(false, false)?.root
                """.trimIndent()
        )
        build()
    }

    private fun buildOnActivityCreatedFunction() = with(FunSpec.builder("onActivityCreated")) {
        addModifiers(KModifier.OVERRIDE)
        addParameter("savedInstanceState", ClassName("android.os", "Bundle").copy(true))
        addCode(
            """
                super.onActivityCreated(savedInstanceState)
        
        """.trimIndent()
        )
        addCode("startObservations()")
        build()
    }

    private fun buildOnDestroyViewFunction() = with(FunSpec.builder("onDestroyView")) {
        addModifiers(KModifier.OVERRIDE)
        addCode(
            """
                super.onDestroyView()
                renderer.release()
                """.trimIndent()
        )
        build()
    }
}