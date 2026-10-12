package app.keyshelf

import android.util.AtomicFile
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.RealObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Android rename replaces files; Windows File.renameTo does not. Keep Android semantics in JVM tests. */
@Implements(AtomicFile::class)
class WindowsAtomicFileShadow {
    @RealObject private lateinit var real: AtomicFile
    @Implementation
    fun finishWrite(stream: FileOutputStream?) {
        if (stream == null) return
        stream.fd.sync(); stream.close()
        fun file(name: String): File = AtomicFile::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(real) as File
        Files.move(file("mNewName").toPath(), file("mBaseName").toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
