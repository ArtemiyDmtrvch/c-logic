package ru.impression.ui_generator_base

import androidx.annotation.CallSuper
import kotlinx.coroutines.Dispatchers
import kotlin.properties.ReadWriteProperty

abstract class CoroutineViewModel : ComponentViewModel(),
    ClearableCoroutineScope by ClearableCoroutineScopeImpl(Dispatchers.IO) {

    protected fun <T> state(
        getInitialValue: suspend () -> T,
        immediatelyBindChanges: Boolean = false,
        onChanged: ((T?) -> Unit)? = null
    ): ReadWriteProperty<CoroutineViewModel, T?> =
        ObservableImpl(this, null, getInitialValue, immediatelyBindChanges, onChanged)

    protected fun <T> observable(
        getInitialValue: suspend () -> T,
        onChanged: ((T?) -> Unit)? = null
    ): ReadWriteProperty<CoroutineViewModel, T?> =
        ObservableImpl(this, null, getInitialValue, null, onChanged)

    @CallSuper
    override fun onCleared() {
        clear()
    }
}