package spinalgpu.toolchain

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import spinalgpu.Isa

object KernelBinaryIO {
  def writeWords(path: Path, words: Seq[Int]): Unit = {
    Option(path.getParent).foreach(parent => Files.createDirectories(parent))

    val buffer = ByteBuffer.allocate(words.length * Isa.instructionBytes).order(ByteOrder.LITTLE_ENDIAN)
    words.foreach(buffer.putInt)
    Files.write(path, buffer.array())
  }

  def readWords(path: Path): Seq[Int] = {
    val bytes = Files.readAllBytes(path)
    require(
      bytes.length % Isa.instructionBytes == 0,
      s"${path.toString}: expected file length to be a multiple of ${Isa.instructionBytes}, got ${bytes.length}"
    )

    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    Seq.fill(bytes.length / Isa.instructionBytes)(buffer.getInt)
  }
}
