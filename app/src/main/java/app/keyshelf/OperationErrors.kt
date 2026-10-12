package app.keyshelf

object OperationErrors {
    fun types(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        val errno = causes.filterIsInstance<android.system.ErrnoException>().firstOrNull()?.errno
        return causes.joinToString(" → ") { it.javaClass.name } + if (errno != null) " errno=$errno" else ""
    }
    fun message(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        return when {
            causes.any { it is java.net.SocketTimeoutException } -> "Компьютер не ответил. Проверьте, что KeyShelf открыт и обмен по Wi-Fi включён."
            causes.any { it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException } -> "Не удалось проверить защищённое соединение. Обновите QR на компьютере и свяжите устройства заново."
            causes.any { it is java.net.ConnectException || it is java.net.NoRouteToHostException } -> "Нет соединения с компьютером."
            causes.any { it is SecurityException } -> "Android не разрешил операцию. Проверьте разрешения KeyShelf."
            error is IllegalArgumentException -> error.message ?: "Проверьте введённые данные"
            else -> "Операция не выполнена. Проверьте пароль, файл и соединение."
        }
    }
}
