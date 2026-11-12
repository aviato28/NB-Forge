package android.print

/** The print callbacks have package-private constructors; tests reach them from the same package. */
object PrintCallbacks {
    fun layout(onFinished: (PrintDocumentInfo, Boolean) -> Unit): PrintDocumentAdapter.LayoutResultCallback =
        object : PrintDocumentAdapter.LayoutResultCallback() {
            override fun onLayoutFinished(info: PrintDocumentInfo, changed: Boolean) = onFinished(info, changed)
        }

    fun write(onFinished: () -> Unit = {}, onFailed: (CharSequence?) -> Unit = {}): PrintDocumentAdapter.WriteResultCallback =
        object : PrintDocumentAdapter.WriteResultCallback() {
            override fun onWriteFinished(pages: Array<out PageRange>?) = onFinished()
            override fun onWriteFailed(error: CharSequence?) = onFailed(error)
        }
}
